package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import com.nyrrine.reliquary.ego.SlashVfx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Penitence — "One Sin and Hundreds of Good Deeds". A Lobotomy Corp E.G.O weapon shaped as a
 * cross-headed mace, materialized from a hollow-skulled archetype and reshaped by the observer.
 *
 * <p>It is a <b>penitent's weapon, not a killer's</b>: its outgoing damage is hard-capped in
 * {@link #onHit} so an ordinary blow only ever grazes. The one exception is a committed mace fall-slam,
 * which lands the cap and bites through half the target's armour — the atonement made forceful. Beyond
 * the blow, every landed strike can still return a little of the penitent's own body and comfort:
 *
 * <ul>
 *   <li><b>Saturation grace</b> — a flat 10% chance per hit to restore a little food + saturation.</li>
 *   <li><b>Mending grace</b> — a <i>ramping</i> chance that starts at 5% and climbs +5% for every strike
 *       that does not heal, until it finally procs: the wielder mends a little (clamped to max, never
 *       overhealing) and the chance resets to 5%. A soft church-bell toll and a faint white cross mark
 *       the mend.</li>
 * </ul>
 *
 * <p>The per-player ramp is held in {@link #healChance} and dropped on quit.
 */
public final class PenitenceWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Penitence. */
    private final NamespacedKey key;

    /** Hard ceiling on outgoing melee damage — an ordinary blow only ever grazes for this much (PLACEHOLDER, was 6.0). */
    private static final double MACE_DAMAGE_CAP = 9.0;

    // ---- [Right Click] Penance (a penitent smite) ---------------------------------
    // Penitence's one real offensive tool: an overhead mace smite on a cooldown that strikes a small AoE and
    // mends the wielder on landing — atonement made forceful, then grace. Its damage is dealt through the
    // framework's fenced pierceDamage, so the melee cap above never touches it. All magnitudes PLACEHOLDER.
    private static final long   PENANCE_COOLDOWN_MS  = 15_000L; // the ability rest
    private static final double PENANCE_DAMAGE       = 9.0;     // raw, per body in the AoE (fenced, uncapped)
    private static final double PENANCE_ARMOR_PIERCE = 0.35;    // the smite bites a little through guard
    private static final double PENANCE_RADIUS       = 3.0;     // a modest smite, not a room-clear
    private static final int    PENANCE_MAX_TARGETS  = 6;
    private static final double PENANCE_SELF_HEAL    = 4.0;     // mend on landing — two hearts
    private static final double PENANCE_HEAL_PER_HIT = 1.0;     // + a little more per foe the smite reaches
    private static final int    PENANCE_SHOW_TICKS   = 16;      // the ground show's lifetime, 0.8s
    private static final int    PENANCE_RING_POINTS  = 20;      // fixed ring density — a wider ring is a sparser one
    private static final long   GRACE_FLASH_MS       = 1_500L;  // how long a grace cue rides the always-on line

    /** A committed mace fall-slam ignores this fraction of the target's armour (the atonement made forceful). */
    private static final double FALL_SLAM_ARMOR_PIERCE = 0.50;

    /** How far the wielder must be falling for a hit to count as a fall-slam rather than a plain swing. */
    private static final float  FALL_SLAM_MIN_FALL = 1.5f;

    /** Weakness I on the struck foe when a mend procs — pressure without lifting the damage cap. 2s. */
    private static final int    MEND_WEAKNESS_TICKS = 40;

    /** How much health a mending proc restores — one heart. Clamped to max, never overheals. */
    private static final double HEAL_PER_HIT = 2.0;

    /** Flat per-hit chance to restore a little saturation/food. */
    private static final double SATURATION_CHANCE = 0.10;

    /** Ramping heal chance: starts here and resets here after every proc. */
    private static final double HEAL_CHANCE_BASE = 0.05;

    /** Each non-healing strike adds this to the ramping heal chance. */
    private static final double HEAL_CHANCE_STEP = 0.05;

    /** Per-player mending ramp. Grows by {@link #HEAL_CHANCE_STEP} per miss, resets to base on proc. */
    private final Map<UUID, Double> healChance = new ConcurrentHashMap<>();

    // Stigmata (ego-enchant): banks a fraction of damage the wielder takes and releases it as EXTRA HEAL on
    // the next mend proc (never durability — Mending is dead). Pure heal utility, capped, no damage output.
    private static final double STIGMATA_FRACTION = 0.15; // of each blow, per level, into the bank
    private static final int    STIGMATA_MAX_LVL  = 3;
    private static final double STIGMATA_BANK_MAX = 6.0;  // cap the stored wound (three hearts of extra mend)
    /** Per-player banked wound, spent into the next mend. */
    private final Map<UUID, Double> stigmataBank = new ConcurrentHashMap<>();

    /** Wielder -> epoch-millis Penance is ready again. Absent = ready / never cast. Cleared on quit. */
    private final Map<UUID, Long> penanceReadyAt = new ConcurrentHashMap<>();

    /** Wielder -> a short-lived grace cue folded into the always-on line (so a proc isn't stomped by the tick). */
    private final Map<UUID, Cue> graceCue = new ConcurrentHashMap<>();

    /** Open Penance ground shows, reaped on disable so nothing outlives a reload. */
    private final Set<PenanceShow> penanceShows = ConcurrentHashMap.newKeySet();

    /** A grace word ("comforted" / "mended" / "atoned") and when it stops riding the HUD line. */
    private record Cue(String word, long until) {}

    public PenitenceWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "penitence");
    }

    @Override
    public String id() {
        return "penitence";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.PENITENCE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.PENITENCE.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.PENITENCE);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: a weapon that heals but cannot harm --------------------------------

    /**
     * Melee hit landed. Penitence is not a real weapon: its outgoing damage is capped to a tiny value so
     * a mace fall-slam can never land a real blow. In exchange the strike may restore a little of the
     * wielder's saturation (flat 10%) and, on a ramping chance, mend a little of their health.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        // An ordinary swing only ever grazes — cap it. The exception is a committed mace fall-slam: it
        // still lands only the cap, but half the target's armour is ignored, so the slam is worth throwing
        // without turning Penitence into a mace. The slam scales the cap through the framework's pierceInput
        // on the event, keeping the mace's fall-slam knockback and sweep (no cancel, no re-deal).
        // The Truly Damned (Smite, vs UNDEAD only): a Smited mace lifts the cap entirely against the undead,
        // so its holy bonus lands in full. Undead-gated, so it can never be turned on a player — the cap still
        // holds for everything else. When it applies we leave the vanilla damage untouched (it already carries
        // Smite's undead bonus).
        boolean trulyDamned = Tag.ENTITY_TYPES_UNDEAD.isTagged(victim.getType())
                && attacker.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SMITE) > 0;
        if (!trulyDamned) {
            if (attacker.getFallDistance() > FALL_SLAM_MIN_FALL) {
                event.setDamage(plugin.weapons().pierceInput(victim, MACE_DAMAGE_CAP, FALL_SLAM_ARMOR_PIERCE));
            } else {
                event.setDamage(Math.min(event.getDamage(), MACE_DAMAGE_CAP));
            }
        }

        // TODO(flavor): "Special: against any wielder of 'Paradise Lost', deals 50000% more damage."
        // Deliberately NOT implemented. When a Paradise Lost weapon/wielder exists, detect the victim
        // here and multiply the pre-cap damage (bypassing MACE_DAMAGE_CAP) instead of capping.

        boolean touched = false;

        // Saturation grace: a flat 10% chance to restore a little food + saturation.
        if (ThreadLocalRandom.current().nextDouble() < SATURATION_CHANCE) {
            if (restoreSaturation(attacker)) {
                flashGrace(attacker, "comforted"); // folded into the always-on line, not flashed over it
                touched = true;
            }
        }

        // Mending grace: a ramping chance. 5%, then +5% for every strike that does not heal; on a
        // proc the wielder mends a little and the ramp resets to 5%.
        UUID id = attacker.getUniqueId();
        double chance = healChance.getOrDefault(id, HEAL_CHANCE_BASE);
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            healChance.put(id, HEAL_CHANCE_BASE); // proc — reset the ramp
            // A mend-proc presses the struck foe: a short Weakness, so the grace has a little bite without
            // the damage cap ever rising.
            victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, MEND_WEAKNESS_TICKS, 0, false, true, true));
            if (mend(attacker)) {
                tollBell(attacker);
                traceCross(attacker, victim);
                if (!touched) flashGrace(attacker, "mended");
            }
        } else {
            healChance.put(id, Math.min(1.0, chance + HEAL_CHANCE_STEP));
        }
    }

    /** Restore a couple of food + saturation. Returns true if anything actually changed. */
    private boolean restoreSaturation(Player attacker) {
        int food = Math.min(20, attacker.getFoodLevel() + 2);
        float sat = Math.min(food, attacker.getSaturation() + 2.0f);
        boolean changed = food != attacker.getFoodLevel() || sat != attacker.getSaturation();
        attacker.setFoodLevel(food);
        attacker.setSaturation(sat);
        return changed;
    }

    /** Mend a flat sip of health, clamped to max — never overheals. Returns true if any HP was restored. */
    private boolean mend(Player attacker) {
        UUID id = attacker.getUniqueId();
        AttributeInstance maxAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = attacker.getHealth();
        double bank = stigmataBank.getOrDefault(id, 0.0); // Stigmata: the banked wound pours into this mend
        double healed = Math.min(maxHp, current + HEAL_PER_HIT + bank);
        if (healed <= current) return false;
        attacker.setHealth(healed);
        stigmataBank.remove(id); // spent (or nothing to spend) — the wound is answered
        return true;
    }

    /**
     * Stigmata (ego-enchant): while the wielder holds Penitence, bank a fraction of every blow they take, to
     * be released as extra healing on the next mend. Read-only on the event — this only ever adds to the
     * heal, never to any outgoing damage.
     */
    @Override
    public void onDamaged(Player victim, EntityDamageEvent event) {
        int lvl = Math.min(EgoEnchants.level(victim.getInventory().getItemInMainHand(), "stigmata"), STIGMATA_MAX_LVL);
        if (lvl <= 0) return;
        UUID id = victim.getUniqueId();
        double banked = stigmataBank.getOrDefault(id, 0.0) + event.getFinalDamage() * STIGMATA_FRACTION * lvl;
        stigmataBank.put(id, Math.min(STIGMATA_BANK_MAX, banked));
    }

    // ---- [Right Click] Penance -----------------------------------------------------

    // The smite's palette (kept apart from the lore colours so tuning one never disturbs the other).
    private static final Color GOLD_RING = Color.fromRGB(0xE8, 0xD9, 0xA0); // church gold — the ring + slash trail
    private static final Color GOLD_EDGE = Color.fromRGB(0xFF, 0xF4, 0xD0); // near-white gold — the leading edge
    private static final Particle.DustOptions RING_DUST = new Particle.DustOptions(GOLD_RING, 1.1f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(GOLD_EDGE, 1.0f);

    /**
     * Right-click: Penance. A penitent smite on a {@value #PENANCE_COOLDOWN_MS}ms cooldown — an overhead mace
     * slam that opens a small holy AoE a step in front of the wielder and mends them on landing. Its damage
     * is fenced through the framework, so the melee cap never touches it. Sneaking is irrelevant here; either
     * right-click casts.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long ready = penanceReadyAt.get(id);
        if (ready != null && now < ready) {
            player.sendActionBar(EgoHud.cooldown("Penance", ready - now, WARM_HUD));
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0.7f);
            return;
        }
        penanceReadyAt.put(id, now + PENANCE_COOLDOWN_MS);
        penance(player);
        EgoDurability.wearMainHand(player); // the ability is non-vanilla — wear the mace here, not via a swing
    }

    /**
     * The smite itself: an overhead slam that strikes a small AoE a step in front of the wielder and mends
     * them on landing. Damage is dealt through {@code pierceDamage} — fenced, so it never returns to
     * {@link #onHit} and the melee cap never clamps it — with a little of the target's armour ignored so the
     * atonement actually bites. Non-grief by construction: nothing here edits the world.
     */
    private void penance(Player player) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        Vector flat = new Vector(look.getX(), 0.0, look.getZ());
        if (flat.lengthSquared() < 1.0e-6) flat = new Vector(1, 0, 0);
        flat.normalize();
        Location impact = player.getLocation().add(flat.multiply(1.6)); // a step in front, at foot height

        smiteFx(player, eye, look, impact);

        UUID selfId = player.getUniqueId();
        World world = impact.getWorld();
        int reached = 0;
        for (var entity : world.getNearbyEntities(impact, PENANCE_RADIUS, PENANCE_RADIUS + 1.0, PENANCE_RADIUS)) {
            if (entity.getUniqueId().equals(selfId)) continue;                       // never the wielder
            if (!(entity instanceof LivingEntity target) || target.isDead() || !target.isValid()) continue;
            if (target.getLocation().distance(impact) > PENANCE_RADIUS) continue;    // the box is square, the smite round
            plugin.weapons().pierceDamage(target, PENANCE_DAMAGE, PENANCE_ARMOR_PIERCE, player);
            if (++reached >= PENANCE_MAX_TARGETS) break;
        }

        // Grace on landing: a mend for the wielder, a little more for every foe the smite reached.
        healOnLanding(player, PENANCE_SELF_HEAL + PENANCE_HEAL_PER_HIT * reached);
    }

    /** The overhead slam show: a SlashVfx crescent brought down hard, a holy flash, and a layered ground bloom. */
    private void smiteFx(Player player, Location eye, Vector look, Location impact) {
        World world = player.getWorld();
        SlashVfx.slash(plugin, eye, look)
                .arcSpan(170).reach(3.4)
                .colours(GOLD_RING, GOLD_EDGE)
                .thickness(1.3f).duration(4).tilt(65)   // a steep tilt reads as an overhead downward slam
                .blade(Material.MACE).bladeScale(1.1)
                .play();

        world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 0.7f, 0.7f);       // the heavy fall of the head
        world.playSound(impact, Sound.BLOCK_BELL_RESONATE, 0.7f, 0.8f);    // a toll under it
        world.playSound(impact, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);  // grace ringing out
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.5f); // a soft, holy concussion

        // FLASH takes a Color on 26.1.2 — the 8-arg form, never a bare FLASH (which crashes the scheduler).
        world.spawnParticle(Particle.FLASH, impact.clone().add(0, 0.2, 0), 1, 0.0, 0.0, 0.0, 0.0, GLYPH);
        PenanceShow show = new PenanceShow(impact);
        penanceShows.add(show);
        show.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The ground bloom: a gold ring sweeping out to the true smite radius under a rising column of light,
     * over {@value #PENANCE_SHOW_TICKS} ticks. Bought with time and shape, not counts — the ring is a fixed
     * {@value #PENANCE_RING_POINTS} motes whatever its radius, so its widest frame costs no more than its
     * narrowest. Purely cosmetic; the damage was all dealt before this ever started.
     */
    private final class PenanceShow extends BukkitRunnable {
        private final World world;
        private final Location at;
        private int age = 0;
        private boolean done = false;

        PenanceShow(Location at) { this.world = at.getWorld(); this.at = at.clone(); }

        @Override
        public void run() {
            if (age >= PENANCE_SHOW_TICKS) { stop(); return; }
            double t = age / (double) PENANCE_SHOW_TICKS;
            double ease = 1.0 - Math.pow(1.0 - t, 3.0);          // snaps open, then settles
            double r = 0.4 + (PENANCE_RADIUS - 0.4) * ease;

            double spin = age * 0.12;                            // the ring turns as it opens
            for (int i = 0; i < PENANCE_RING_POINTS; i++) {
                double a = (Math.PI * 2.0 * i) / PENANCE_RING_POINTS + spin;
                Location p = at.clone().add(Math.cos(a) * r, 0.15 + r * 0.06, Math.sin(a) * r);
                world.spawnParticle(Particle.DUST, p, 1, 0.03, 0.03, 0.03, 0,
                        (i & 1) == 0 ? RING_DUST : EDGE_DUST);
            }
            if (t < 0.6 && age % 2 == 0) {                       // a rising column of light, the first half
                world.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.2 + t * 1.8, 0), 2, 0.1, 0.2, 0.1, 0.0);
            }
            world.spawnParticle(Particle.DUST, at.clone().add(0, 0.1, 0), 2, 0.25, 0.12, 0.25, 0, RING_DUST);
            age++;
        }

        void stop() { if (done) return; done = true; penanceShows.remove(this); cancel(); }
    }

    /** Mend the wielder on a Penance landing, clamped to max — never overheals. A faint flag on the HUD line. */
    private void healOnLanding(Player player, double amount) {
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double healed = Math.min(maxHp, player.getHealth() + amount);
        if (healed > player.getHealth()) {
            player.setHealth(healed);
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.1, 0), 2, 0.25, 0.3, 0.25, 0);
        }
        flashGrace(player, "atoned");
    }

    /** Set a short-lived grace cue for the always-on line — a proc word that rides beside Penance for a moment. */
    private void flashGrace(Player player, String word) {
        graceCue.put(player.getUniqueId(), new Cue(word, System.currentTimeMillis() + GRACE_FLASH_MS));
    }

    // ---- the always-on line --------------------------------------------------------

    /**
     * The composed action-bar line, painted every tick while held: Penance's readiness (or its rest), plus a
     * short-lived grace cue folded in beside it so a proc is never a lone flash stomped a tick later. Returns
     * false the instant the mace leaves the main hand, so a holder is ticked and no one else.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        player.sendActionBar(EgoHud.row(penanceReadout(player), graceReadout(player)));
        return true;
    }

    private Component penanceReadout(Player player) {
        Long ready = penanceReadyAt.get(player.getUniqueId());
        long rem = ready == null ? 0L : ready - System.currentTimeMillis();
        return rem > 0 ? EgoHud.cooldown("Penance", rem, WARM) : EgoHud.ready("Penance", GOLD);
    }

    private Component graceReadout(Player player) {
        Cue cue = graceCue.get(player.getUniqueId());
        if (cue == null) return null;
        if (System.currentTimeMillis() >= cue.until()) { graceCue.remove(player.getUniqueId()); return null; }
        return EgoHud.status("Penitence — " + cue.word(), WARM_HUD);
    }

    // ---- lifecycle -----------------------------------------------------------------

    /** Drop the per-player ramp, banked wound, cooldown and grace cue when a wielder leaves. */
    @Override
    public void onQuit(UUID id) {
        healChance.remove(id);
        stigmataBank.remove(id);
        penanceReadyAt.remove(id);
        graceCue.remove(id);
    }

    @Override
    public void onDisable() {
        // Reap any open Penance ground shows so a cosmetic runnable can't outlive a reload. Iterate a copy —
        // stop() untracks as it goes. Nothing else of this weapon lives in the world.
        for (PenanceShow show : new ArrayList<>(penanceShows)) show.stop();
        penanceShows.clear();
    }

    /** A soft church-bell toll on the mend, pitch-jittered so repeated strikes don't drone. */
    private void tollBell(Player attacker) {
        float pitch = 0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f; // ~0.85 - 1.15
        attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_BELL_USE, 0.55f, pitch);
    }

    /**
     * A faint white cross-glyph over the victim's upper body: a short vertical line crossed by a
     * short horizontal one, drawn in a plane facing the attacker. Low particle count, short-lived.
     */
    private void traceCross(Player attacker, LivingEntity victim) {
        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, 1.4, 0);

        // Horizontal axis: perpendicular to the attacker->victim look, so the cross faces the striker.
        Vector to = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        Vector right = to.lengthSquared() < 1.0e-6
                ? new Vector(1, 0, 0)
                : to.normalize().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();

        Particle.DustOptions dust = new Particle.DustOptions(GLYPH, 0.7f);

        // Vertical beam of the cross: from just below the chest up past the head.
        for (int i = -2; i <= 3; i++) {
            Location p = chest.clone().add(0, i * 0.22, 0);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
        // Horizontal beam, at chest height.
        for (int i = -2; i <= 2; i++) {
            if (i == 0) continue; // centre already drawn by the vertical beam
            Location p = chest.clone().add(right.clone().multiply(i * 0.22));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
    }

    // ---- lore ---------------------------------------------------------------------

    /** Primary — pale church gold. Display name, "How to use:", ability headers. */
    private static final TextColor GOLD  = TextColor.color(0xE8D9A0);
    /** Secondary — the grace/mending accent, already the weapon's own. The Abnormality title line. */
    private static final TextColor WARM  = TextColor.color(0xC9A94E);
    private static final TextColor WARM_HUD = WARM;                   // subtle action-bar cue on grace

    // Particle colour (kept apart from the lore palette so tuning one never disturbs the other).
    private static final Color GLYPH = Color.fromRGB(0xF3EEDC);       // the traced cross — near-white gold (dust)

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Penitence",
            "One Sin and Hundreds of Good Deeds",
            GOLD,
            WARM,
            List.of(
                    "Not made to kill, but to atone.",
                    "It comforts the one who wields it.",
                    "",
                    // Paradise Lost is flavour only — no such mechanic is implemented. It closed the old
                    // tooltip, below the moveset; the shared format has no room down there, so it sits at
                    // the foot of the flavour instead. The words are hers, untouched.
                    "vs. 'Paradise Lost': +50000% dmg."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Damage Cap",
                            "Attacks deal at most 9 damage. A",
                            "committed mace fall-slam bites through",
                            "half the target's armor."),
                    new EgoLore.Ability("[Right Click] Penance",
                            "An overhead smite in front of you:",
                            "damage to everything close, half its",
                            "armor ignored, and it mends you on",
                            "landing, more for each foe it hits.",
                            "15 second cooldown."),
                    new EgoLore.Ability("[Passive] Saturation Grace",
                            "Each hit has a 10% chance to restore",
                            "a little food and saturation."),
                    new EgoLore.Ability("[Passive] Mending Grace",
                            "Each hit has a chance to mend one",
                            "heart, never overhealing. Starts at",
                            "5% and climbs 5% per hit until it",
                            "procs, then resets to 5%. A proc also",
                            "briefly weakens the struck foe.")
            ));
}
