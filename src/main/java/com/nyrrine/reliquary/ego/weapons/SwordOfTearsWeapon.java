package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sword With Sharpened Tears — a RAPIER-class E.G.O Equipment, and the roster's one <b>summoner</b>. Not a
 * greatsword: everything about it is puncture and agility, never a heavy cleave.
 *
 * <p>The vanilla IRON_SWORD swing lands its normal (piercing) damage, uncancelled — a rapier a novice can
 * still fence with. The character lives in the four spectral <b>rapier companions</b> that hover in a curved
 * fan behind the wielder while the weapon is held ({@link #onTick}): a knight's retinue, grief given edges.
 * They are lightweight {@link ItemDisplay} entities — a floating sword each — tagged {@link #RAPIER_TAG},
 * non-persistent, and reliably reaped on unequip / quit / disable so no orphan ever litters the world.
 *
 * <h2>The one decision the kit asks</h2>
 * Every rapier is either <b>in the fan</b> or <b>sent</b>, and the two are worth completely different things:
 *
 * <ul>
 *   <li><b>Rapier Formation</b> ({@link #onTick}) — the fan itself. Blades resting here are the wielder's
 *       escorts and the ammunition for the Impale.</li>
 *   <li><b>Double Tag</b> ({@link #onHit}) — land a melee hit and one blade <em>from the fan</em> darts out
 *       to stab the same body alongside you ({@link #STAB_DAMAGE}) and glide back. Only blades in the fan
 *       answer; a blade away on escort duty is busy. This is what holding rapiers back buys.</li>
 *   <li><b>Send Rapier</b> ({@link #onInteract}, right-click) — the aim. One blade leaves the fan for the
 *       foe under your crosshair and <em>duels it there on its own</em> — wheeling in and puncturing once a
 *       second — until its mark falls, it is called back, or the mark outruns {@link #LEASH}. Press again to
 *       send the next. This is what spending rapiers buys: pressure on a body you never touch.</li>
 *   <li><b>Converging Impale</b> ({@link #onInteract}, shift + right-click) — the signature. Every blade
 *       <em>still in the fan</em> lifts, cages the mark from four sides, and drives in on the same tick for
 *       {@link #IMPALE_DAMAGE} apiece. A full fan is the whole {@value #RAPIER_COUNT}; a fan you emptied
 *       into duels is not. Once per {@link #IMPALE_COOLDOWN_MS}.</li>
 *   <li><b>Recharge</b> ({@link #onSwapHands}, F) — calls every sent rapier home. Free and instant to press,
 *       but the glide back is slow and each blade still serves its {@link #BLADE_REST_MS} on arrival, so it
 *       is a real setup step rather than an undo button.</li>
 * </ul>
 *
 * <p>So the loop is a summoner's, not a proc's: <em>deploy → let the retinue work → call them home → execute
 * with a full fan</em>, on the Impale's 45s rhythm. Holding blades back and spending them are both correct;
 * they buy different things, and the fan tells you at a glance which you chose.
 *
 * <h2>The fences that keep it honest</h2>
 * A rapier's strike re-deals damage through {@code victim.damage(..., owner)}, which re-enters
 * {@link #onHit} — so every one runs inside the {@link #reentry} fence, or a stab would double-tag itself
 * recursively. Conversely a body's hurt-immunity would swallow the strike: the wielder's own melee stamps
 * i-frames the instant before a Double Tag lands, and the Impale asks {@value #RAPIER_COUNT} blades to hurt
 * one body in a <em>single</em> tick. Both clear {@code setNoDamageTicks(0)} first ({@link #stab},
 * {@link #impaleAll}); without it the tag lands only when the dart happens to arrive late, and three of the
 * Impale's four instances vanish with no error at all. Both restore the victim's velocity afterward — a
 * rapier punctures, it does not shove; the vanilla swing owns the knockback.
 *
 * <h2>Cost</h2>
 * Sending a blade and committing the Impale are non-vanilla uses, so they wear the main hand
 * ({@link EgoDurability#wearMainHand}); the Double Tag's stab wears its mild point as it always did. A
 * duelling blade's own stabs do not wear — the sortie was already paid for, and the whole point of a summon
 * is that it works while you do not. Normal thrusts wear via the vanilla swing. The weapon is not
 * unbreakable and its meta is set exactly once, in {@link #createItem()}.
 */
public final class SwordOfTearsWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Sword With Sharpened Tears. */
    private final NamespacedKey key;

    /** Wielder -> their live rapier formation. Present only while the weapon is held. */
    private final Map<UUID, Formation> formations = new HashMap<>();

    /**
     * Wielders currently inside a rapier's own {@code victim.damage(...)} — the re-entrancy fence for
     * {@link #onHit}. Keyed by the WIELDER, never the victim: a victim-keyed map would leak a key per mob
     * forever (mobs never fire {@link #onQuit}). Entries are added and removed in the same try/finally, so
     * the set is empty between strikes.
     */
    private final Set<UUID> reentry = new HashSet<>();

    // ---- tuning (Nyrrine will tune) -----------------------------------------------
    // Placeholders, kept together and clearly named so they're trivial to find and retune later.
    //
    // Damage maths, per instance (the ceiling for ONE instance is a Sharpness-V netherite sword, ~11):
    //   melee thrust  7.0 base (EgoModels.SWORD_OF_TEARS), ~10.0 with Sharpness V   -> under the ceiling
    //   rapier stab   2.0 flat, no enchant scaling (a custom damage call)           -> far under
    //   impale blade  5.5 flat                                                      -> far under
    // Worst-case burst: a full fan's Impale is 4 x 5.5 = 22.0 in one tick, once per 45s — deliberately the
    // same figure the roster's other big single commit lands, and it costs the whole fan to have ready.
    private static final int    RAPIER_COUNT       = 4;      // HARD CAP on the retinue — 4 displays per wielder, no more
    private static final long   BLADE_REST_MS      = 4000L;  // a blade rests this long after landing back in the fan

    // Swift Return (a custom enchant — id "swift_return"): a returned blade rests less before it can be spent
    // again. Cuts BLADE_REST_MS 12% per level, up to 36% at level 3. Cadence only — never a blade's damage.
    private static final double SWIFT_RETURN_PER_LEVEL = 0.12;
    private static final int    SWIFT_RETURN_CAP       = 3;
    private static final double STAB_DAMAGE        = 2.0;    // one rapier's puncture — Double Tag and duel stab alike
    private static final double IMPALE_DAMAGE      = 5.5;    // per committed blade on the Converging Impale
    private static final long   IMPALE_COOLDOWN_MS = 45000L; // the formation-wide commit gate (the old 45s, re-homed)

    // Converging Grief (a custom enchant — id "converging_grief"): grief gathers sooner. Cuts the Converging
    // Impale gate by 12% per level, up to 36% at level 3 (~28.8s). Utility only — never a blade's damage or
    // the fan size.
    private static final double CONVERGING_GRIEF_PER_LEVEL = 0.12;
    private static final int    CONVERGING_GRIEF_CAP       = 3;

    /** The Impale gate for the sword held right now: the base 45s cut by its Converging Grief bonus. */
    private static long impaleCooldownMs(Player owner) {
        int lvl = Math.min(CONVERGING_GRIEF_CAP,
                EgoEnchants.level(owner.getInventory().getItemInMainHand(), "converging_grief"));
        return (long) (IMPALE_COOLDOWN_MS * (1.0 - CONVERGING_GRIEF_PER_LEVEL * lvl));
    }
    private static final double COMMAND_RANGE      = 24.0;   // how far a command reaches for a mark
    private static final double LEASH              = 32.0;   // a duelling blade gives up past this from its wielder
    private static final double LEASH_SQ           = LEASH * LEASH;

    /** Scoreboard tag stamped on every rapier display, for a belt-and-braces world sweep on shutdown. */
    private static final String RAPIER_TAG = "reliquary_sword_of_tears_rapier";

    /** What a single rapier is doing right now. Exactly one of these, always. */
    private enum BladeState {
        /** Hovering in the fan behind the wielder. Driven by the formation's 2-tick loop. */
        FAN,
        /** Sent: duelling its mark on its own. Also driven by the formation's 2-tick loop — no task of its own. */
        DUEL,
        /** A short 1-tick animation owns it (a dart, the Impale, a glide home). The loops leave it alone. */
        FLIGHT
    }

    public SwordOfTearsWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "sword_of_tears");
    }

    @Override
    public String id() {
        return "sword_of_tears";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.SWORD_OF_TEARS.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.SWORD_OF_TEARS.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.SWORD_OF_TEARS);

        item.setItemMeta(meta);
        return item;
    }

    // ---- the fan, and the retinue, only while held ---------------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Spawns the fan the moment the rapier
     * is drawn, then drives BOTH the hovering blades and the duelling ones from this one call — a sent blade
     * deliberately owns no task of its own, so the retinue's whole steady-state cost is this loop, which the
     * manager already runs at O(wielders). The instant the blade leaves the main hand the formation is reaped
     * and {@code false} stops the ticking, so no entity is left behind.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            Formation gone = formations.remove(id);
            if (gone != null) gone.dispose(); // sheathed -> reap the rapiers, stop ticking
            return false;
        }
        Formation f = formations.computeIfAbsent(id, k -> new Formation(this, player));
        f.tick(player, tick);
        renderBar(player, f); // the fan and the Impale gate, held on screen every tick — never flashed on a press
        return true;
    }

    // ---- double tag: a blade from the fan strikes alongside you --------------------

    /**
     * A melee thrust landed. Vanilla piercing damage is left untouched; if a rapier is resting in the fan,
     * one darts out to stab the same body alongside you and glide home. Blades away on escort duty do not
     * answer — that is the point of sending them. If our own rapier's hit re-enters this dispatch, the fence
     * drops it.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (reentry.contains(attacker.getUniqueId())) return; // our rapier's own damage re-entered — ignore

        Formation f = formations.computeIfAbsent(attacker.getUniqueId(), k -> new Formation(this, attacker));
        if (f.doubleTag(attacker, victim)) renderBar(attacker, f);
    }

    // ---- command: aim the retinue ---------------------------------------------------

    /**
     * Right-click sends ONE rapier to duel the foe under the crosshair; shift + right-click commits every
     * blade still in the fan to the Converging Impale. Both need a mark — this weapon is aimed, never
     * waited on.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        Formation f = formations.computeIfAbsent(player.getUniqueId(), k -> new Formation(this, player));
        LivingEntity mark = acquireTarget(player);
        if (mark == null) {
            player.sendActionBar(EgoHud.status("No one for the rapiers to answer.", FAINT_HUD));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 0.7f);
            return;
        }

        if (sneaking) {
            int committed = f.impale(player, mark);
            if (committed > 0) {
                EgoDurability.wearMainHand(player, 2); // the whole fan commits — a heavier, non-vanilla use
                renderBar(player, f);
            } else if (committed < 0) {
                renderBar(player, f); // the composed line already shows the Impale gate counting down
            } else {
                player.sendActionBar(EgoHud.status("No rapiers left in the fan.", FAINT_HUD));
            }
            return;
        }

        if (f.sendOne(player, mark)) {
            EgoDurability.wearMainHand(player); // a non-vanilla sortie -> a mild point of wear, once, at dispatch
            renderBar(player, f);
        } else {
            player.sendActionBar(EgoHud.status("No rapier ready to send.", FAINT_HUD));
        }
    }

    /**
     * Recharge (F): every sent rapier breaks off and glides home. Free to press — the cost is the slow flight
     * back and the rest each blade still owes on arrival, exactly as the old replenish left cooldowns alone.
     * This is the setup step before an Impale, not an undo button.
     */
    @Override
    public void onSwapHands(Player player, PlayerSwapHandItemsEvent event) {
        // Dispatched to every relic regardless of what's held — narrow to our own wielders first.
        if (!matches(player.getInventory().getItemInMainHand())) return;
        Formation f = formations.get(player.getUniqueId());
        if (f == null) return;
        event.setCancelled(true); // F commands the retinue; it doesn't shuffle the hands

        if (f.recallAll(player) > 0) {
            World w = player.getWorld();
            w.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
            w.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.5f, 1.6f);
        }
        // Either way, just repaint the steady readout — no lone "already circle you" flash that blinks the HUD.
        renderBar(player, f);
    }

    /**
     * The living body under the crosshair within {@link #COMMAND_RANGE}, else the nearest one roughly ahead.
     * This is the weapon's ONLY entity scan, and it runs once per command press — never per tick and never
     * per blade. A sent rapier is handed its mark here and holds the reference for the whole duel, so the
     * retinue re-seeks nothing.
     */
    private LivingEntity acquireTarget(Player player) {
        Entity looked = player.getTargetEntity((int) COMMAND_RANGE);
        if (looked instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId()) && !le.isDead()) {
            return le;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(COMMAND_RANGE, COMMAND_RANGE, COMMAND_RANGE)) {
            if (e.getUniqueId().equals(player.getUniqueId()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            Vector to = le.getLocation().add(0, le.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.01 || dist > COMMAND_RANGE) continue;
            if (to.multiply(1.0 / dist).dot(dir) < 0.5) continue; // off to the side / behind
            if (dist < bestDist) { bestDist = dist; best = le; }
        }
        return best;
    }

    /**
     * The always-on composed readout: how much of the fan is still yours to spend, and the Converging
     * Impale's gate, on ONE line via {@link EgoHud#row}. Every command path that used to flash a lone pip
     * count or a lone Impale cooldown now sends this, so neither state replaces the other as the retinue works.
     */
    private void renderBar(Player player, Formation f) {
        player.sendActionBar(EgoHud.row(
                EgoHud.pips("Rapiers", STAR_HUD, f.fanCount(), RAPIER_COUNT),
                impaleReadout(f)));
    }

    /** The Impale half: its 45s gate counting down, else ready to commit the fan. */
    private static Component impaleReadout(Formation f) {
        long rem = f.impaleRemaining();
        return rem > 0 ? EgoHud.cooldown("Impale", rem, FAINT_HUD) : EgoHud.ready("Impale", STAR_HUD);
    }

    // ---- damage: the two fenced strike paths ---------------------------------------

    /**
     * One rapier's puncture — the Double Tag's stab and every duelling blade's stab both land here.
     *
     * <p>Two hazards, both handled. The damage re-enters {@link #onHit}, so the {@link #reentry} fence holds
     * for the call. And the body's hurt-immunity would eat the strike outright: a Double Tag lands a handful
     * of ticks after the wielder's own melee — well inside the 10-tick window — and two duelling blades can
     * align on the same body in the same tick. Without {@code setNoDamageTicks(0)} those strikes are dropped
     * with no error, no log line, and no damage. Velocity is restored after: a rapier punctures, and the
     * vanilla swing already did the shoving.
     */
    void stab(Player owner, LivingEntity victim) {
        if (owner == null || victim.isDead() || !victim.isValid()) return;
        UUID oid = owner.getUniqueId();
        Vector preVel = victim.getVelocity();
        reentry.add(oid);
        try {
            victim.setNoDamageTicks(0); // or this stab lands inside the melee's i-frames and is silently swallowed
            victim.damage(STAB_DAMAGE, owner);
        } finally {
            reentry.remove(oid);
            victim.setVelocity(preVel); // a puncture, not a shove
        }
        stabFx(victim);
    }

    /**
     * The Converging Impale's landing: {@code blades} instances of {@link #IMPALE_DAMAGE} on ONE body, in ONE
     * tick.
     *
     * <p>This is precisely the case i-frames destroy. A body may only be hurt once per 10 ticks, so without
     * stripping them between each call the first blade lands and the other three are swallowed in silence —
     * a 22-damage execution that quietly deals 5.5. Each instance therefore clears
     * {@code setNoDamageTicks(0)} of its own, and the loop bails the moment the victim dies so a corpse never
     * takes the rest. One velocity capture spans all four: the cage pins the body, it does not punt it.
     */
    void impaleAll(Player owner, LivingEntity victim, int blades) {
        if (owner == null || victim.isDead() || !victim.isValid()) return;
        UUID oid = owner.getUniqueId();
        Vector preVel = victim.getVelocity();
        int landed = 0;
        reentry.add(oid);
        try {
            for (int k = 0; k < blades; k++) {
                if (victim.isDead() || !victim.isValid()) break; // it fell partway through the cage
                victim.setNoDamageTicks(0); // WITHOUT THIS ONLY THE FIRST BLADE LANDS
                victim.damage(IMPALE_DAMAGE, owner);
                landed++;
            }
        } finally {
            reentry.remove(oid);
            victim.setVelocity(preVel); // the cage pins; it doesn't launch
        }
        impaleFx(victim, landed);
    }

    // ---- vfx ------------------------------------------------------------------------

    /** A light-blue puncture burst where a rapier lands a stab. Deliberately lean — four blades fire it a second. */
    private void stabFx(LivingEntity victim) {
        World w = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, c, 5, 0.22, 0.30, 0.22, 0, TEAR_SHIMMER, true);
        w.spawnParticle(Particle.CRIT, c, 4, 0.20, 0.28, 0.20, 0.12);
        w.spawnParticle(Particle.ENCHANTED_HIT, c, 3, 0.16, 0.24, 0.16, 0.08);
        w.spawnParticle(Particle.FALLING_WATER, c, 2, 0.18, 0.26, 0.18, 0);
        w.playSound(c, Sound.ITEM_TRIDENT_HIT, 0.4f, 1.85f); // one sound only — this can fire 4x a second
    }

    /** The Impale's landing: the fan's whole grief driven home at once. Once per 45s, so it may be lavish. */
    private void impaleFx(LivingEntity victim, int blades) {
        World w = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, c, 34, 0.45, 0.55, 0.45, 0, TEAR_SHIMMER, true);
        w.spawnParticle(Particle.DUST, c, 20, 0.55, 0.65, 0.55, 0, TEAR_FINE, true);
        w.spawnParticle(Particle.CRIT, c, 22, 0.40, 0.50, 0.40, 0.28);
        w.spawnParticle(Particle.ENCHANTED_HIT, c, 14, 0.35, 0.45, 0.35, 0.15);
        w.spawnParticle(Particle.FALLING_WATER, c, 10, 0.35, 0.45, 0.35, 0);
        w.spawnParticle(Particle.END_ROD, c, 10, 0.14, 0.16, 0.14, 0.05);
        w.spawnParticle(Particle.SWEEP_ATTACK, c, blades, 0.30, 0.30, 0.30, 0);
        w.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 0.85f, 1.5f);
        w.playSound(c, Sound.ITEM_TRIDENT_HIT, 0.9f, 0.8f);
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.8f);
    }

    // ---- lifecycle: never orphan an entity ----------------------------------------

    @Override
    public void onQuit(UUID id) {
        Formation f = formations.remove(id);
        if (f != null) f.dispose();
        reentry.remove(id); // belt-and-braces: the try/finally already clears it
    }

    @Override
    public void onDisable() {
        for (Formation f : formations.values()) f.dispose();
        formations.clear();
        reentry.clear();
        sweepOrphans(); // belt-and-braces: reap any stray tagged rapier anywhere in the world
    }

    /** Remove every rapier display carrying our tag across all loaded worlds. */
    private void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(RAPIER_TAG)) e.remove();
            }
        }
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Formation> e : formations.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            Formation f = e.getValue();
            out.add("sword_of_tears  " + f.fanCount() + " in fan / " + f.sentCount() + " sent"
                    + " of " + RAPIER_COUNT + "  (" + who + ")");
        }
        return out;
    }

    // ---- the formation: four rapier companions and where they are ------------------

    /**
     * A wielder's four rapier {@link ItemDisplay} companions and their per-blade state. Each is
     * {@link BladeState#FAN} (hovering behind the wielder), {@link BladeState#DUEL} (sent, fighting its mark),
     * or {@link BladeState#FLIGHT} (a brief animation owns it).
     *
     * <p><b>Where the work happens.</b> FAN and DUEL blades are both stepped from {@link #tick}, which the
     * manager already calls once per wielder per 2 ticks — so a retinue of four fighting for a minute costs
     * one loop, not four tasks. Only the short, snappy motions get a 1-tick {@link BukkitRunnable} of their
     * own: the dart (~24 ticks), the Impale (~30 ticks), the glide home (≤{@value #RETURN_TICKS} ticks). Every
     * one of them is bounded and ends by handing the blade back to a steady state.
     */
    private static final class Formation {

        // Formation geometry — a curved fan that WRAPS around behind the wielder (not a rigid line).
        private static final double FORM_RADIUS   = 1.90;   // orbit radius of the fan behind the wielder
        private static final double FORM_HEIGHT   = 1.55;   // hover height — up behind the shoulders, a radiant fan
        private static final double FORM_ARC_DEG  = 62.0;   // half-span of the arc; blades wrap toward the flanks
        private static final double FORM_ARC_LIFT = 0.35;   // the outer blades ride higher -> a tall, curved fan
        private static final double FORM_POINT_UP = 0.90;   // how far the blade tips tilt UP off their outward spoke
        private static final float  FORM_ROLL_DEG = 0.0f;   // extra spin about each blade's own length — dials the guard/flat to face nicely (placeholder, tune live)

        // Dart feel (Double Tag + the opening of a sortie). Blocks / blocks-per-tick. SLOW tip-leading
        // glide-in, then a FAST stab — the contrast is the whole read.
        private static final int    APPROACH_TICKS    = 16;   // cap on the slow homing glide-in
        private static final double APPROACH_SPEED    = 0.55; // slow homing travel from the fan toward the mark
        private static final double APPROACH_STANDOFF = 2.20; // glide to here, then commit to the lunge
        private static final double STRIKE_SPEED      = 3.20; // the quick stab into the body
        private static final int    STRIKE_TICKS      = 6;    // safety cap on the lunge

        // The slow recall glide. Speed is unchanged from the old formation's gentle drift; the tick cap is
        // generous now because a return is a real journey (up to the leash) rather than a hop off the wielder,
        // and a blade snapping the last 20 blocks would throw away the best part of the animation.
        private static final double RETURN_SPEED = 0.60;
        private static final int    RETURN_TICKS = 60;   // 0.60 * 60 = 36 blocks > LEASH, so it never snaps

        // The duel: a sent blade wheeling its mark, winding inward, puncturing. Stepped at the formation's
        // 2-tick cadence, so these are in FORMATION steps, not server ticks.
        private static final int    DUEL_STEPS_PER_STAB = 10;   // 10 steps * 2 ticks = one stab a second, per blade
        private static final double DUEL_RADIUS         = 1.45; // how wide it wheels before winding in
        private static final double DUEL_ORBIT_SPEED    = 0.55; // radians per step
        private static final double DUEL_BOB            = 0.45; // vertical sway of the wheel

        // The Converging Impale: gather into a cage, then drive in together.
        private static final int    IMPALE_GATHER_TICKS   = 14;   // the blades rise and ring the mark
        private static final double IMPALE_GATHER_SPEED   = 1.60; // brisk — they have distance to cover
        private static final double IMPALE_STANDOFF       = 2.70; // radius of the cage
        private static final int    IMPALE_CONVERGE_TICKS = 5;    // the committed drive inward
        private static final double IMPALE_CONVERGE_SPEED = 3.40;
        private static final int    IMPALE_HOLD_TICKS     = 12;   // they stand in the wound, weeping, then leave

        private final SwordOfTearsWeapon weapon;
        private final Reliquary plugin;
        private final UUID ownerId;

        private final ItemDisplay[] blades  = new ItemDisplay[RAPIER_COUNT];
        private final BladeState[] state    = new BladeState[RAPIER_COUNT];
        /**
         * The mark a sent blade is duelling. A bounded, per-wielder array of at most
         * {@value SwordOfTearsWeapon#RAPIER_COUNT} references — never a victim-keyed map, which would leak a
         * key per mob forever. Every exit path (mark dead/invalid/out of world/past the leash, recall,
         * dispose) nulls the slot.
         */
        private final LivingEntity[] marks  = new LivingEntity[RAPIER_COUNT];
        /** A duelling blade's own step counter — drives both its wheel and its once-a-second stab cadence. */
        private final int[] duelT           = new int[RAPIER_COUNT];
        private final long[] restUntil      = new long[RAPIER_COUNT];
        private long impaleReadyAt = 0L;
        private boolean alive = true;

        Formation(SwordOfTearsWeapon weapon, Player owner) {
            this.weapon = weapon;
            this.plugin = weapon.plugin;
            this.ownerId = owner.getUniqueId();
            Location spawn = owner.getLocation().add(0, FORM_HEIGHT, 0);
            for (int i = 0; i < RAPIER_COUNT; i++) {
                blades[i] = spawnBlade(owner.getWorld(), spawn);
                state[i] = BladeState.FAN;
            }
        }

        /** Spawn one rapier display: the rapier model, tip hanging down, non-persistent + tagged. */
        private ItemDisplay spawnBlade(World world, Location at) {
            return world.spawn(at, ItemDisplay.class, d -> {
                ItemStack model = new ItemStack(EgoModels.SWORD_OF_TEARS.material());
                ItemMeta m = model.getItemMeta();
                if (m != null) {
                    EgoModels.stamp(m, EgoModels.SWORD_OF_TEARS); // model only — no attribute modifiers on a display
                    model.setItemMeta(m);
                }
                d.setItemStack(model);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(13, 15));
                d.setPersistent(false);            // a crash can never leave these on disk
                d.setInterpolationDuration(3);
                d.setTeleportDuration(3);          // smooth the follow/glide between ticks (snapped shorter for a stab)
                d.setTransformation(hoverTransform());
                d.addScoreboardTag(RAPIER_TAG);
            });
        }

        // ---- the one loop ---------------------------------------------------------

        /**
         * Step every blade once. Hovering blades wheel into the fan; sent blades fight. This is the retinue's
         * entire steady-state cost — no entity scan, no per-blade task, and every particle here is throttled.
         */
        void tick(Player owner, long tick) {
            if (!alive) return;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                ItemDisplay b = blades[i];
                if (b == null || b.isDead() || !b.isValid()) {
                    if (state[i] == BladeState.FLIGHT) continue; // its animation owns the slot; let it finish
                    b = spawnBlade(owner.getWorld(), owner.getLocation().add(0, FORM_HEIGHT, 0));
                    blades[i] = b;
                    state[i] = BladeState.FAN;                   // a blade that lost its entity comes home
                    marks[i] = null;
                }
                switch (state[i]) {
                    case FAN -> hoverStep(owner, b, i, tick);
                    case DUEL -> duelStep(owner, b, i);
                    case FLIGHT -> { /* a short animation owns this blade */ }
                }
            }
        }

        /** A blade at rest in the fan: wheeling into its slot, tip levelled where the wielder looks, weeping the occasional tear. */
        private void hoverStep(Player owner, ItemDisplay b, int i, long tick) {
            if (b.getTeleportDuration() != 3) b.setTeleportDuration(3); // restore the smooth hover cadence
            // Point the blade OUT along its own spoke (away from the back, toward its flank) and tilted UP, so the
            // retinue reads as a radiant fan of swords rising behind the wielder — not lying flat where you look.
            Vector point = bladeSpoke(owner, i).setY(FORM_POINT_UP);
            b.setTransformation(hoverPointing(point.normalize()));
            b.teleport(slotFor(owner, i, tick));
            if ((tick % 18) == 0) { // an occasional tear weeping from the hovering blade + a faint sparkle
                Location w = b.getLocation().add(0, -0.2, 0);
                b.getWorld().spawnParticle(Particle.FALLING_WATER, w, 1, 0.03, 0.03, 0.03, 0);
                b.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, w, 1, 0.05, 0.05, 0.05, 0, TEAR_SHIMMER);
                if (((tick / 18 + i) % 3) == 0) {
                    b.getWorld().spawnParticle(Particle.END_ROD, b.getLocation(), 1, 0.02, 0.02, 0.02, 0.003);
                }
            }
        }

        /**
         * The hovering slot for blade {@code i}: placed on a curved fan that WRAPS around behind the wielder
         * (an arc/orbit, not a rigid lateral line), at hover height, gently bobbing. The end blades ride a
         * touch higher and angle outward so the fan reads as wrapping the body.
         */
        private Location slotFor(Player owner, int i, long tick) {
            Location base = owner.getLocation();
            float yaw = base.getYaw();
            double frac = RAPIER_COUNT == 1 ? 0.0 : (i / (double) (RAPIER_COUNT - 1)) * 2.0 - 1.0;
            Vector dir = bladeSpoke(owner, i); // the blade's spoke: out behind, swung toward its flank
            double bob  = Math.sin(tick * 0.12 + i * 1.6) * 0.10;
            double lift = Math.abs(frac) * FORM_ARC_LIFT; // curve the fan vertically at its ends
            Location slot = base.clone()
                    .add(dir.multiply(FORM_RADIUS))
                    .add(0, FORM_HEIGHT + lift + bob, 0);
            slot.setYaw(yaw + (float) (frac * FORM_ARC_DEG)); // angle each blade outward along the arc
            slot.setPitch(0f);
            return slot;
        }

        /** The horizontal spoke blade {@code i} rides: pointing out behind the wielder, swung toward its flank. */
        private Vector bladeSpoke(Player owner, int i) {
            double rad = Math.toRadians(owner.getLocation().getYaw());
            Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector right   = new Vector(Math.cos(rad), 0, Math.sin(rad));
            double frac = RAPIER_COUNT == 1 ? 0.0 : (i / (double) (RAPIER_COUNT - 1)) * 2.0 - 1.0;
            double theta = Math.toRadians(frac * FORM_ARC_DEG); // angle off "directly behind"
            return forward.multiply(-Math.cos(theta)).add(right.multiply(Math.sin(theta)));
        }

        // ---- the duel: a sent blade fighting on its own ---------------------------

        /**
         * One step of a sent blade's duel. It wheels its mark and winds steadily inward, and on every
         * {@value #DUEL_STEPS_PER_STAB}th step it drives in and punctures — one stab a second, per blade,
         * whether or not the wielder is even looking. When the mark falls, leaves the world, or outruns the
         * leash, the blade breaks off and reports back rather than hunting a new one: a rapier answers the
         * order it was given.
         *
         * <p>No scan of any kind runs here — the mark was handed to the blade at dispatch and is held for the
         * duel's life. The only per-step checks are validity and one distance compare.
         */
        private void duelStep(Player owner, ItemDisplay b, int i) {
            LivingEntity mark = marks[i];
            if (mark == null || mark.isDead() || !mark.isValid()
                    || !mark.getWorld().equals(owner.getWorld())
                    || mark.getLocation().distanceSquared(owner.getLocation()) > LEASH_SQ) {
                marks[i] = null;
                beginReturn(owner, i); // the mark fell, fled, or unloaded — the blade comes home
                return;
            }

            int t = duelT[i]++;
            int phase = t % DUEL_STEPS_PER_STAB;
            Location center = mark.getLocation().add(0, mark.getHeight() * 0.6, 0);

            if (phase == 0) { // ---- the lunge: drive in and puncture ----
                b.setTeleportDuration(1);
                Vector in = center.toVector().subtract(b.getLocation().toVector());
                b.setTransformation(pointing(in.lengthSquared() < 1.0e-4 ? new Vector(0, -1, 0) : in.normalize()));
                Location at = center.clone();
                at.setYaw(0f);
                at.setPitch(0f);
                b.teleport(at);
                weapon.stab(owner, mark); // fenced, i-frames cleared, knockback undone
                return;
            }

            // ---- wheel the body, winding inward toward the next lunge ----
            b.setTeleportDuration(2); // interpolate across the 2-tick gap so the wheel glides, not snaps
            double frac = (DUEL_STEPS_PER_STAB - phase) / (double) DUEL_STEPS_PER_STAB; // 0.9 just after a stab -> 0.1 just before
            double rr = DUEL_RADIUS * (0.30 + 0.70 * frac);
            double ang = t * DUEL_ORBIT_SPEED + (Math.PI * 2 * i) / RAPIER_COUNT; // blades share a mark without overlapping
            Location pt = center.clone().add(Math.cos(ang) * rr, Math.sin(t * 0.42) * DUEL_BOB, Math.sin(ang) * rr);
            Vector in = center.toVector().subtract(pt.toVector());
            b.setTransformation(pointing(in.lengthSquared() < 1.0e-4 ? new Vector(0, -1, 0) : in.normalize()));
            pt.setYaw(0f);
            pt.setPitch(0f);
            b.teleport(pt);
            if ((phase & 1) == 0) duelTrail(pt); // half-rate: a duel can run as long as the wielder allows
        }

        // ---- commands -------------------------------------------------------------

        /** Land a Double Tag: one blade from the fan stabs the struck body and glides back. */
        boolean doubleTag(Player owner, LivingEntity victim) {
            if (!alive || victim.isDead() || !victim.isValid()) return false;
            int i = firstReady();
            if (i < 0) return false;
            return launchDart(owner, i, victim, false); // strike and come home — false if the blade had already gone
        }

        /** Send one ready blade to duel {@code mark} until it falls or is called back. */
        boolean sendOne(Player owner, LivingEntity mark) {
            if (!alive || mark.isDead() || !mark.isValid()) return false;
            int i = firstReady();
            if (i < 0) return false;
            return launchDart(owner, i, mark, true); // strike and STAY — false if the blade had already gone
        }

        /**
         * A blade leaves the fan for {@code mark}: the slow tip-leading glide-in, then the fast stab. What
         * happens after the stab is the whole difference between the kit's two orders — {@code stay} hands the
         * blade to the duel loop; otherwise it glides home. One flight, two endings.
         *
         * @return true if a blade actually left the fan; false if the chosen slot's display was already gone,
         *         so the caller reports (and charges) nothing for a sortie that never launched.
         */
        private boolean launchDart(Player owner, final int i, final LivingEntity mark, final boolean stay) {
            final ItemDisplay b = blades[i];
            if (b == null || !b.isValid()) return false;
            state[i] = BladeState.FLIGHT;
            b.setTeleportDuration(3); // smooth the slow glide-in
            owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.6f, stay ? 1.5f : 1.7f);

            new BukkitRunnable() {
                int phase = 0; // 0 = APPROACH (slow tip-leading glide-in), 1 = STRIKE (fast stab)
                int t = 0;

                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || b.isDead() || !b.isValid() || p == null || !p.isOnline()
                            || mark.isDead() || !mark.isValid() || !mark.getWorld().equals(b.getWorld())) {
                        abortToFan(p, i, b); // abort cleanly — the blade always ends up somewhere legal
                        cancel();
                        return;
                    }
                    Location center = mark.getLocation().add(0, mark.getHeight() * 0.6, 0);
                    Location cur = b.getLocation();
                    Vector to = center.toVector().subtract(cur.toVector());
                    double dist = to.length();
                    Vector dir = dist < 1.0e-4 ? new Vector(0, 0, 1) : to.clone().normalize();
                    b.setTransformation(pointing(dir)); // tip lances forward at the mark
                    trailTear(cur);

                    if (phase == 0) { // ---- APPROACH: slow, tip-leading glide-in ----
                        if (dist <= APPROACH_STANDOFF || t >= APPROACH_TICKS) {
                            phase = 1;
                            t = 0;
                            b.setTeleportDuration(1); // snap the cadence for the quick stab
                            return;
                        }
                        step(b, cur, dir, Math.min(dist, APPROACH_SPEED));
                        t++;
                        return;
                    }

                    // ---- STRIKE: the fast lunge into the body ----
                    if (dist > 1.0 && t < STRIKE_TICKS) {
                        step(b, cur, dir, Math.min(dist, STRIKE_SPEED));
                        t++;
                        return;
                    }
                    b.teleport(center);
                    weapon.stab(p, mark); // fenced primary damage + wear was paid at dispatch

                    if (stay && !mark.isDead() && mark.isValid()) { // take up the post: the duel loop owns it now
                        marks[i] = mark;
                        duelT[i] = 1;
                        state[i] = BladeState.DUEL;
                        b.setTeleportDuration(2);
                        b.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.9f);
                    } else {
                        abortToFan(p, i, b);
                    }
                    cancel();
                }

                /** Advance the blade {@code by} blocks along {@code dir}, keeping its pose. */
                private void step(ItemDisplay blade, Location cur, Vector dir, double by) {
                    Location next = cur.clone().add(dir.clone().multiply(by));
                    next.setYaw(cur.getYaw());
                    next.setPitch(cur.getPitch());
                    blade.teleport(next);
                }
            }.runTaskTimer(plugin, 0L, 1L);
            return true;
        }

        /**
         * The Converging Impale. Every blade STILL IN THE FAN commits — a fan you emptied into duels has less
         * to give, which is the price of having spent it. Returns how many committed, {@code 0} if the fan was
         * empty, {@code -1} if the 45s gate is still running.
         *
         * <p>A resting blade still counts: the Impale is the formation committing as one, not four individual
         * sorties, so it reads off {@link BladeState#FAN} rather than {@link #firstReady()}.
         */
        int impale(Player owner, LivingEntity mark) {
            if (!alive || mark.isDead() || !mark.isValid()) return 0;
            long now = System.currentTimeMillis();
            List<Integer> commit = new ArrayList<>(RAPIER_COUNT);
            for (int i = 0; i < RAPIER_COUNT; i++) if (state[i] == BladeState.FAN) commit.add(i);
            if (commit.isEmpty()) return 0;                 // nothing in the fan to commit
            if (now < impaleReadyAt) return -1;             // still on the 45s gate
            impaleReadyAt = now + impaleCooldownMs(owner); // Converging Grief may shorten the gate
            runImpale(owner, mark, commit);
            return commit.size();
        }

        /** Whole milliseconds left on the Impale's 45s gate (0 if ready). */
        long impaleRemaining() {
            return Math.max(0L, impaleReadyAt - System.currentTimeMillis());
        }

        /**
         * The signature, driven by ONE task for the whole formation rather than one per blade — which is both
         * cheaper and the only way to guarantee what the move is about: every blade landing on the SAME tick.
         */
        private void runImpale(Player owner, final LivingEntity mark, List<Integer> commit) {
            final int n = commit.size();
            final int[] idx = new int[n];
            for (int k = 0; k < n; k++) {
                idx[k] = commit.get(k);
                state[idx[k]] = BladeState.FLIGHT;
                ItemDisplay b = blades[idx[k]];
                if (b != null && b.isValid()) b.setTeleportDuration(2);
            }
            Location cast = owner.getLocation();
            owner.getWorld().playSound(cast, Sound.ITEM_TRIDENT_THROW, 0.9f, 0.6f);
            owner.getWorld().playSound(cast, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.7f);

            new BukkitRunnable() {
                int phase = 0; // 0 = GATHER (cage the mark), 1 = CONVERGE (drive in together), 2 = HOLD
                int t = 0;

                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || p == null || !p.isOnline() || mark.isDead() || !mark.isValid()) {
                        for (int i : idx) abortToFan(p, i, blades[i]);
                        cancel();
                        return;
                    }
                    Location center = mark.getLocation().add(0, mark.getHeight() * 0.6, 0);
                    World w = center.getWorld();

                    if (phase == 0) { // ---- GATHER: the fan lifts and rings the mark, tips inward ----
                        for (int k = 0; k < n; k++) fly(idx[k], center, cageSlot(center, k, n, t), IMPALE_GATHER_SPEED, (t & 1) == 0);
                        if (++t >= IMPALE_GATHER_TICKS) {
                            phase = 1;
                            t = 0;
                            for (int i : idx) {
                                ItemDisplay b = blades[i];
                                if (b != null && b.isValid()) b.setTeleportDuration(1);
                            }
                            w.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.65f);
                            w.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 0.6f);
                        }
                        return;
                    }

                    if (phase == 1) { // ---- CONVERGE: every blade drives in on the same tick ----
                        // Each drives to its OWN seat in the body, tip aimed at the mark's centre — four
                        // converging thrusts, not four blades stacked on one pixel.
                        for (int k = 0; k < n; k++) {
                            fly(idx[k], center, center.clone().add(woundOffset(k, n)), IMPALE_CONVERGE_SPEED, true);
                        }
                        if (++t >= IMPALE_CONVERGE_TICKS) {
                            for (int k = 0; k < n; k++) { // seat every tip in the body before a single point lands
                                ItemDisplay b = blades[idx[k]];
                                if (b == null || !b.isValid()) continue;
                                Location seat = center.clone().add(woundOffset(k, n));
                                seat.setYaw(0f);
                                seat.setPitch(0f);
                                b.teleport(seat);
                            }
                            weapon.impaleAll(p, mark, n); // n instances, ONE tick, i-frames cleared between each
                            phase = 2;
                            t = 0;
                        }
                        return;
                    }

                    // ---- HOLD: the blades stand in the wound, weeping, then withdraw ----
                    for (int k = 0; k < n; k++) {
                        ItemDisplay b = blades[idx[k]];
                        if (b == null || !b.isValid()) continue;
                        Location seat = center.clone().add(woundOffset(k, n));
                        seat.setYaw(0f);
                        seat.setPitch(0f);
                        b.teleport(seat);
                        if ((t & 1) == 0) {
                            w.spawnParticle(Particle.FALLING_WATER, seat, 1, 0.05, 0.05, 0.05, 0);
                            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, seat, 1, 0.06, 0.06, 0.06, 0, TEAR_SHIMMER);
                        }
                    }
                    if (++t >= IMPALE_HOLD_TICKS) {
                        for (int i : idx) abortToFan(p, i, blades[i]);
                        cancel();
                    }
                }

                /** Move blade {@code i} toward {@code want}, tip aimed at {@code center}, optionally trailing. */
                private void fly(int i, Location center, Location want, double speed, boolean trail) {
                    ItemDisplay b = blades[i];
                    if (b == null || !b.isValid()) return;
                    Location cur = b.getLocation();
                    Vector to = want.toVector().subtract(cur.toVector());
                    double d = to.length();
                    Location next = d <= speed ? want.clone() : cur.clone().add(to.multiply(speed / d));
                    Vector in = center.toVector().subtract(next.toVector());
                    b.setTransformation(pointing(in.lengthSquared() < 1.0e-4 ? new Vector(0, -1, 0) : in.normalize()));
                    next.setYaw(0f);
                    next.setPitch(0f);
                    b.teleport(next);
                    if (trail) trailTear(cur);
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        /** A point on the cage around the mark: evenly spaced, alternately high and low, wheeling slowly. */
        private static Location cageSlot(Location center, int k, int n, int t) {
            double ang = (Math.PI * 2 * k) / n + t * 0.05;
            double lift = ((k & 1) == 0 ? 0.80 : -0.60);
            return center.clone().add(Math.cos(ang) * IMPALE_STANDOFF, lift, Math.sin(ang) * IMPALE_STANDOFF);
        }

        /** A small per-blade offset so four tips seated in one body read as four wounds, not one flicker. */
        private static Vector woundOffset(int k, int n) {
            double ang = (Math.PI * 2 * k) / n;
            return new Vector(Math.cos(ang) * 0.22, ((k & 1) == 0 ? 0.14 : -0.14), Math.sin(ang) * 0.22);
        }

        /** Fold every sent rapier home. Returns how many broke off. */
        int recallAll(Player owner) {
            if (!alive) return 0;
            int n = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (state[i] == BladeState.DUEL) { beginReturn(owner, i); n++; }
            }
            return n;
        }

        // ---- coming home ----------------------------------------------------------

        /** End a flight the safest way available: glide home if the wielder is there, else just land. */
        private void abortToFan(Player owner, int i, ItemDisplay b) {
            if (!alive) return;
            if (owner == null || !owner.isOnline()) { landInFan(i, b); return; }
            beginReturn(owner, i);
        }

        /**
         * The slow recall glide — the blade drifts back to its slot in the fan rather than blinking there.
         * It is the same gentle drift the old formation recalled with, given room to cover a real distance.
         */
        private void beginReturn(Player owner, final int i) {
            ItemDisplay b = blades[i];
            marks[i] = null;
            duelT[i] = 0;
            if (b == null || !b.isValid()) { landInFan(i, null); return; }
            state[i] = BladeState.FLIGHT;
            final ItemDisplay blade = b;
            blade.setTeleportDuration(3); // smooth the slow recall glide

            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || blade.isDead() || !blade.isValid() || p == null || !p.isOnline()) {
                        landInFan(i, blade);
                        cancel();
                        return;
                    }
                    Location slot = slotFor(p, i, t);
                    Location cur = blade.getLocation();
                    blade.setTransformation(hoverTransform());
                    // Out of time, in the wrong world, or close enough: settle into the slot and be done.
                    if (t >= RETURN_TICKS || !cur.getWorld().equals(slot.getWorld())) {
                        blade.teleport(slot);
                        landInFan(i, blade);
                        cancel();
                        return;
                    }
                    Vector to = slot.toVector().subtract(cur.toVector());
                    double d = to.length();
                    if (d <= RETURN_SPEED) {
                        blade.teleport(slot);
                        landInFan(i, blade);
                        cancel();
                        return;
                    }
                    Location next = cur.clone().add(to.multiply(RETURN_SPEED / d));
                    next.setYaw(slot.getYaw());
                    next.setPitch(0f);
                    blade.teleport(next);
                    if ((t & 1) == 0) trailTear(cur);
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        /** The blade is back in the fan and owes its rest. The one place a blade becomes spendable again. */
        private void landInFan(int i, ItemDisplay b) {
            if (!alive) return;
            state[i] = BladeState.FAN;
            marks[i] = null;
            duelT[i] = 0;
            restUntil[i] = System.currentTimeMillis() + bladeRestMs();
            if (b != null && b.isValid()) b.setTeleportDuration(3);
        }

        /** The blade-rest for the sword held right now: the base rest cut by its Swift Return bonus. */
        private long bladeRestMs() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null) return BLADE_REST_MS;
            int lvl = Math.min(SWIFT_RETURN_CAP,
                    EgoEnchants.level(owner.getInventory().getItemInMainHand(), "swift_return"));
            return (long) (BLADE_REST_MS * (1.0 - SWIFT_RETURN_PER_LEVEL * lvl));
        }

        // ---- bookkeeping ----------------------------------------------------------

        /** The first blade resting in the fan and off its rest, or -1. */
        private int firstReady() {
            long now = System.currentTimeMillis();
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (state[i] == BladeState.FAN && now >= restUntil[i]) return i;
            }
            return -1;
        }

        /** How many blades are in the fan and ready to spend — what the action bar shows. */
        int fanCount() {
            long now = System.currentTimeMillis();
            int n = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (state[i] == BladeState.FAN && now >= restUntil[i]) n++;
            }
            return n;
        }

        /** How many blades are out duelling. */
        int sentCount() {
            int n = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) if (state[i] == BladeState.DUEL) n++;
            return n;
        }

        /** Reap every blade entity and mark the formation dead so its animations bail out. */
        void dispose() {
            alive = false;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (blades[i] != null) { blades[i].remove(); blades[i] = null; }
                marks[i] = null; // never hold a victim reference past the formation's life
            }
        }

        // ---- vfx ------------------------------------------------------------------

        /** A light-blue tear trail marking a flying rapier's path. Flights are short; this may be full-fat. */
        private static void trailTear(Location at) {
            World w = at.getWorld();
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 2, 0.06, 0.06, 0.06, 0, TEAR_SHIMMER);
            w.spawnParticle(Particle.FALLING_WATER, at, 1, 0.05, 0.05, 0.05, 0);
            w.spawnParticle(Particle.END_ROD, at, 1, 0.01, 0.01, 0.01, 0.004); // faint white sparkle
        }

        /** The duel's wheeling trail. Leaner than {@link #trailTear} — a duel has no time limit at all. */
        private static void duelTrail(Location at) {
            World w = at.getWorld();
            w.spawnParticle(Particle.DUST, at, 2, 0.10, 0.10, 0.10, 0, TEAR_FINE);
            w.spawnParticle(Particle.END_ROD, at, 1, 0.02, 0.02, 0.02, 0.006);
        }

        /**
         * A fully-specified look-rotation that maps the model's tip axis (+Y) onto {@code dir} with a
         * DETERMINISTIC roll. {@code rotationTo(+Y, dir)} pins only the tip direction and leaves the blade's
         * spin about its own length arbitrary — JOML picks a different roll axis per direction, so on an
         * asymmetric rapier each blade in the fan twisted to a different angle (the chaotic, non-symmetric
         * mess in the screenshot). Here the roll is pinned by a global reference (world up, projected off the
         * tip), so every blade shares one roll and the fan is mirror-symmetric: blade i mirrors blade n-1-i,
         * because their {@code dir}s mirror and the reference is the same for both. When the tip runs nearly
         * parallel to world up (e.g. a straight-down duel pose), the reference falls back to world forward so
         * the basis never degenerates. {@code rollDeg} adds a final spin about the tip to dial the guard/flat.
         */
        private static Quaternionf lookRotation(Vector dir, float rollDeg) {
            Vector3f y = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
            if (y.lengthSquared() < 1.0e-8f) y.set(0, 1, 0);
            y.normalize();
            // Side axis = ref x tip. Use world up as the roll reference; if the tip is ~parallel to it, the
            // cross would collapse, so pin off world forward (0,0,1) instead.
            Vector3f ref = Math.abs(y.dot(0, 1, 0)) > 0.999f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
            Vector3f x = new Vector3f(ref).cross(y);
            if (x.lengthSquared() < 1.0e-8f) x.set(1, 0, 0);
            x.normalize();
            Vector3f z = new Vector3f(x).cross(y).normalize(); // completes a right-handed basis (x, y, z)
            // Columns are where the model's local +X/+Y/+Z land in world space (Matrix3f is column-major).
            Quaternionf q = new Quaternionf().setFromNormalized(new Matrix3f(
                    x.x, x.y, x.z,
                    y.x, y.y, y.z,
                    z.x, z.y, z.z));
            if (rollDeg != 0f) q.rotateY((float) Math.toRadians(rollDeg)); // spin about the tip: dial the guard
            return q;
        }

        /** Rapier hanging tip-down — the spawn pose, before the hover levels it out where the wielder faces. */
        private static Transformation hoverTransform() {
            return new Transformation(new Vector3f(),
                    lookRotation(new Vector(0, -1, 0), FORM_ROLL_DEG),
                    new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf());
        }

        /** Rapier at rest but levelled, tip leading along {@code dir} — the wuxia poise, poised where you face. */
        private static Transformation hoverPointing(Vector dir) {
            return new Transformation(new Vector3f(),
                    lookRotation(dir, FORM_ROLL_DEG),
                    new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf());
        }

        /**
         * Rapier flattened tip-leading along {@code dir} — a lance/thrust pose (NOT held like a slash), so it
         * visibly points forward at the target during its dart. Stretched a touch along the blade axis to read
         * as a committed thrust. Shares the deterministic {@link #lookRotation} roll so a lunging blade never
         * spins to a random angle mid-dart.
         */
        private static Transformation pointing(Vector dir) {
            return new Transformation(new Vector3f(),
                    lookRotation(dir, FORM_ROLL_DEG),
                    new Vector3f(0.85f, 1.15f, 0.85f), new Quaternionf());
        }
    }

    // ---- palette & lore -----------------------------------------------------------
    // Dark starry sky-blue, with a readable grey standing in where black would be. The flavour's own
    // near-white is the shared off-white every E.G.O tooltip's body reads in; see EgoLore.

    /** Primary — starry sky-blue. Display name, "How to use:", ability headers. */
    private static final TextColor PRIMARY   = TextColor.color(0x8FB8E6);
    /** Secondary — the palette's readable grey (never black). The Abnormality title line. */
    private static final TextColor SECONDARY = TextColor.color(0x9AA7B4);

    private static final TextColor STAR_HUD  = TextColor.color(0x8FB8E6); // action-bar accent
    private static final TextColor FAINT_HUD = TextColor.color(0x9AA7B4); // action-bar status

    /** Light-blue teardrop dust for the flying rapiers' trail + the puncture burst, and a near-white glint it shimmers to. */
    private static final Color TEAR      = Color.fromRGB(0xAE, 0xD8, 0xF0); // light tear-blue
    private static final Color TEAR_PALE = Color.fromRGB(0xE8, 0xF3, 0xFC); // near-white shimmer
    private static final Particle.DustOptions TEAR_FINE = new Particle.DustOptions(TEAR, 0.5f);
    private static final Particle.DustTransition TEAR_SHIMMER = new Particle.DustTransition(TEAR, TEAR_PALE, 0.85f);

    // The moveset is written from the code. The ability names are placeholders: "Rapier Formation",
    // "Double Tag" and "Recharge" are kept because the code and the action bar already name them and each
    // still does what it always did; the two new orders are named for what they plainly do.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Sword With Sharpened Tears",
            "The Knight of Despair",
            PRIMARY,
            SECONDARY,
            List.of(
                    "A rapier for swift thrusts;",
                    "unskilled hands puncture fast.",
                    "By chivalry: no foul play, no mercy."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Rapier Formation",
                            "Four rapiers wheel in a curved fan",
                            "behind you while the sword is held.",
                            "A rapier back in the fan rests 4s",
                            "before it can be spent again."),
                    new EgoLore.Ability("[Left Click] Double Tag",
                            "Landing a hit darts one rapier out of",
                            "the fan to stab the same body for 2,",
                            "then glide home. Only rapiers in the",
                            "fan answer — sent ones are busy."),
                    new EgoLore.Ability("[Right Click] Send Rapier",
                            "Sends one rapier to the foe you look",
                            "at, up to 24 blocks. It duels there on",
                            "its own — wheeling in to puncture for 2",
                            "a second — until the mark falls, flees",
                            "32 blocks, or you call it back. Press",
                            "again to send the next."),
                    new EgoLore.Ability("[Shift + Right-click] Converging Impale",
                            "Every rapier still in the fan cages the",
                            "foe you look at and drives in at once,",
                            "5.5 a blade — 22 with all four, less",
                            "with a fan you spent. Once per 45s."),
                    new EgoLore.Ability("[Swap Hands] Recharge",
                            "Calls every sent rapier back into the",
                            "fan. Free, but the glide home is slow",
                            "and each still owes its 4s rest.")
            ));
}
