package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
 * Sword With Sharpened Tears — a RAPIER-class E.G.O Equipment built for swift, piercing thrusts. Not a
 * greatsword: everything about it is puncture and agility, never a heavy cleave.
 *
 * <p>The vanilla IRON_SWORD swing lands its normal (piercing) damage, uncancelled — a rapier a novice
 * can still fence with. The character lives in a wheeling formation of four spectral <b>rapier
 * companions</b> that hover behind the wielder while the weapon is held, following like companion
 * wolves ({@link #onTick}). They are lightweight {@link ItemDisplay} entities — a floating sword each —
 * tagged {@link #RAPIER_TAG}, non-persistent, and reliably reaped on unequip / quit / disable so no
 * orphan ever litters the world.
 *
 * <ul>
 *   <li><b>Double tag</b> ({@link #onHit}) — land a melee hit and one <em>available</em> rapier darts
 *       from behind you to the struck body and strikes ALONGSIDE you: a piercing bonus hit, leaving a
 *       light-blue tear trail as it flies. A sent rapier then goes on a per-sword cooldown
 *       ({@link #RAPIER_COOLDOWN_MS}). With no rapier available, the blow is just the normal hit.</li>
 *   <li><b>Recharge</b> ({@link #onInteract}, shift + right-click) — expended rapiers fly back to the
 *       wielder and re-form the four-strong ring; but a recalled sword still can't strike until its own
 *       cooldown has elapsed.</li>
 * </ul>
 *
 * <p>The bonus strikes are non-vanilla hits, so each wears the main-hand a mild point
 * ({@link EgoDurability#wearMainHand(Player)}); normal thrusts wear via the vanilla swing. A re-entrancy
 * fence ({@link #reentry}) guards the rapier's {@code victim.damage(...)} so it never recurses into a
 * second double-tag. The weapon is no longer unbreakable.
 */
public final class SwordOfTearsWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Sword With Sharpened Tears. */
    private final NamespacedKey key;

    /** Wielder -> their live rapier formation. Present only while the weapon is held. */
    private final Map<UUID, Formation> formations = new HashMap<>();

    /** Wielders currently in the middle of dealing a rapier's bonus hit — re-entrancy fence for {@link #onHit}. */
    private final Set<UUID> reentry = new HashSet<>();

    // ---- tuning (Nyrrine will tune) -----------------------------------------------
    // Placeholders, kept together and clearly named so they're trivial to find and retune later.
    private static final int  RAPIER_COUNT         = 4;      // how many rapiers wheel behind the wielder
    private static final long RAPIER_COOLDOWN_MS   = 4000L;  // per-sword cooldown after it darts out (~4s placeholder)
    private static final double RAPIER_DAMAGE      = 2.0;    // primary piercing stab a rapier lands (nerfed 3.0 -> 2.0: a small assist)
    private static final double RAPIER_CHIP_DAMAGE = 0.5;    // tiny follow-up per ricochet slash during the brief engagement
    private static final long RECHARGE_COOLDOWN_MS = 45000L; // shift-RC replenish is gated to once per 45s

    /** Scoreboard tag stamped on every rapier display, for a belt-and-braces world sweep on shutdown. */
    private static final String RAPIER_TAG = "reliquary_sword_of_tears_rapier";

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

    // ---- follow: the rapier formation, only while held ----------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Spawns the four-rapier
     * formation the moment the rapier is drawn, drives its follow/hover motion, and — the instant the
     * blade leaves the main hand — reaps the formation and returns {@code false} so ticking stops and no
     * entity is left behind.
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
        return true;
    }

    // ---- double tag: a rapier darts out alongside the strike -----------------------

    /**
     * A melee thrust landed. Vanilla piercing damage is left untouched; if an available rapier is
     * charged, one darts from behind to strike the same body alongside you (a piercing bonus hit with a
     * tear trail). If our own rapier hit re-enters this dispatch, the fence drops it.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (reentry.contains(attacker.getUniqueId())) return; // our rapier's own damage re-entered — ignore

        Formation f = formations.computeIfAbsent(attacker.getUniqueId(), k -> new Formation(this, attacker));
        if (f.launchStrike(attacker, victim)) {
            attacker.sendActionBar(EgoHud.pips("Rapiers", STAR_HUD, f.readyCount(), RAPIER_COUNT));
        }
    }

    /**
     * The rapier's PRIMARY stab (the fast lunge landing). Fenced so the vanilla damage event it fires
     * can't recurse into another double-tag; a mild main-hand wear follows (a non-vanilla hit), then a
     * rich light-blue puncture burst on the struck body.
     */
    void dealRapierStrike(Player owner, LivingEntity victim) {
        if (!damageFenced(owner, victim, RAPIER_DAMAGE)) return;
        EgoDurability.wearMainHand(owner); // non-vanilla piercing hit -> a mild point of wear (once, on the stab)
        strikeFx(victim);
    }

    /** A tiny ricochet chip during the brief engagement flurry. Fenced; no extra wear (the stab already wore). */
    void dealRapierChip(Player owner, LivingEntity victim) {
        if (damageFenced(owner, victim, RAPIER_CHIP_DAMAGE)) chipFx(victim);
    }

    /** Deal {@code dmg} from {@code owner} to {@code victim} inside the re-entrancy fence. */
    private boolean damageFenced(Player owner, LivingEntity victim, double dmg) {
        if (victim.isDead() || !victim.isValid()) return false;
        UUID oid = owner.getUniqueId();
        reentry.add(oid);
        try {
            victim.damage(dmg, owner);
        } finally {
            reentry.remove(oid);
        }
        return true;
    }

    /** A lush light-blue puncture burst where a rapier lands its primary stab. */
    private void strikeFx(LivingEntity victim) {
        World w = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, c, 18, 0.30, 0.40, 0.30, 0, TEAR_SHIMMER);
        w.spawnParticle(Particle.FALLING_WATER, c, 8, 0.25, 0.35, 0.25, 0);
        w.spawnParticle(Particle.CRIT, c, 12, 0.25, 0.35, 0.25, 0.15);
        w.spawnParticle(Particle.ENCHANTED_HIT, c, 8, 0.20, 0.30, 0.20, 0.10);
        w.spawnParticle(Particle.END_ROD, c, 7, 0.10, 0.12, 0.10, 0.04); // white sparkle bloom
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.6f);
        w.playSound(c, Sound.ITEM_TRIDENT_HIT, 0.5f, 1.8f);
    }

    /** A small pale-blue chip flourish where a ricochet slash grazes the body. */
    private void chipFx(LivingEntity victim) {
        World w = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.55, 0);
        w.spawnParticle(Particle.DUST, c, 6, 0.20, 0.30, 0.20, 0, TEAR_FINE);
        w.spawnParticle(Particle.ENCHANTED_HIT, c, 4, 0.15, 0.25, 0.15, 0.05);
        w.spawnParticle(Particle.END_ROD, c, 2, 0.05, 0.05, 0.05, 0.02);
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.4f, 1.9f);
    }

    // ---- recharge: shift + right-click reforms the ring ---------------------------

    /**
     * Shift + right-click recharges: every expended rapier flies back to the wielder and re-forms the
     * ring. Cooldowns are left untouched, so a recalled sword still can't strike until its own cooldown
     * has elapsed. Plain right-click does nothing.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!sneaking) return;
        Formation f = formations.get(player.getUniqueId());
        if (f == null) return;
        int recharged = f.recharge(player);
        if (recharged > 0) {
            player.sendActionBar(EgoHud.pips("Rapiers", STAR_HUD, f.readyCount(), RAPIER_COUNT));
        } else if (recharged < 0) {
            // gated by the 45s replenish cooldown — show whole-second remaining
            player.sendActionBar(EgoHud.cooldown("Recharge", f.rechargeRemaining(), FAINT_HUD));
        } else {
            player.sendActionBar(EgoHud.status("The rapiers already circle you.", FAINT_HUD));
        }
    }

    // ---- lifecycle: never orphan an entity ----------------------------------------

    @Override
    public void onQuit(UUID id) {
        Formation f = formations.remove(id);
        if (f != null) f.dispose();
    }

    @Override
    public void onDisable() {
        for (Formation f : formations.values()) f.dispose();
        formations.clear();
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
            out.add("sword_of_tears  " + e.getValue().readyCount() + "/" + RAPIER_COUNT + " rapiers ready  (" + who + ")");
        }
        return out;
    }

    // ---- the formation: four hovering rapier companions ---------------------------

    /**
     * A wielder's ring of four rapier {@link ItemDisplay} companions and their per-sword state. Each
     * blade is either <b>formed</b> (hovering in the ring behind the wielder) or expended (sent out and
     * awaiting a recharge), and carries its own cooldown clock. {@code busy} means a short flight
     * animation currently owns the blade, so the follow loop leaves it alone.
     */
    private static final class Formation {

        // Formation geometry — a curved fan that WRAPS around behind the wielder (not a rigid line).
        private static final double FORM_RADIUS  = 1.90;   // orbit radius of the fan behind the wielder
        private static final double FORM_HEIGHT  = 1.25;   // hover height
        private static final double FORM_ARC_DEG = 62.0;   // half-span of the arc; blades wrap toward the flanks
        private static final double FORM_ARC_LIFT = 0.18;  // the outer blades ride a touch higher -> a curved fan

        // Flight feel. All in blocks / blocks-per-tick. SLOW glide-in + SLOW recall, but a FAST stab.
        private static final int    APPROACH_TICKS    = 16;   // cap on the slow homing glide-in
        private static final double APPROACH_SPEED    = 0.55; // slow homing travel from formation toward the target
        private static final double APPROACH_STANDOFF = 2.20; // glide to here, then commit to the lunge
        private static final double STRIKE_SPEED      = 3.20; // the quick stab into the body
        private static final int    STRIKE_TICKS      = 6;    // safety cap on the lunge
        private static final int    ENGAGE_TICKS      = 14;   // brief visible ricochet/slash engagement after the stab
        private static final int    ENGAGE_HIT_PERIOD = 5;    // land a tiny chip every N ticks of the engagement
        private static final double ENGAGE_RADIUS     = 1.25; // how wide the ricochet slashes arc around the body
        private static final int    RETURN_TICKS      = 16;   // slow recall cap
        private static final double RETURN_SPEED      = 0.60; // slow recall glide

        private final SwordOfTearsWeapon weapon;
        private final Reliquary plugin;
        private final UUID ownerId;

        private final ItemDisplay[] blades = new ItemDisplay[RAPIER_COUNT];
        private final boolean[] formed     = new boolean[RAPIER_COUNT];
        private final boolean[] busy       = new boolean[RAPIER_COUNT];
        private final long[] cooldownUntil = new long[RAPIER_COUNT];
        private long rechargeReadyAt = 0L; // formation-wide 45s replenish gate
        private boolean alive = true;

        Formation(SwordOfTearsWeapon weapon, Player owner) {
            this.weapon = weapon;
            this.plugin = weapon.plugin;
            this.ownerId = owner.getUniqueId();
            Location spawn = owner.getLocation().add(0, FORM_HEIGHT, 0);
            for (int i = 0; i < RAPIER_COUNT; i++) {
                blades[i] = spawnBlade(owner.getWorld(), spawn);
                formed[i] = true;
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
                d.setTeleportDuration(3);          // smooth the follow/glide between ticks (snapped shorter for the stab)
                d.setTransformation(hoverTransform());
                d.addScoreboardTag(RAPIER_TAG);
            });
        }

        // ---- follow ---------------------------------------------------------------

        /** Wheel the formed blades into their hovering ring behind the wielder; leave expended ones hidden. */
        void tick(Player owner, long tick) {
            if (!alive) return;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                ItemDisplay b = blades[i];
                if (b == null || b.isDead() || !b.isValid()) {
                    if (formed[i] && !busy[i]) { // it should be visible — respawn it
                        b = spawnBlade(owner.getWorld(), owner.getLocation().add(0, FORM_HEIGHT, 0));
                        blades[i] = b;
                    } else {
                        continue;
                    }
                }
                if (busy[i]) continue;      // a flight animation owns this blade
                if (!formed[i]) continue;   // expended — parked hidden until recharge
                if (b.getTeleportDuration() != 3) b.setTeleportDuration(3); // restore the smooth hover cadence
                b.setTransformation(hoverTransform());
                Location slot = slotFor(owner, i, tick);
                b.teleport(slot);
                if ((tick % 18) == 0) { // an occasional tear weeping from the hovering blade + a faint sparkle
                    Location w = b.getLocation().add(0, -0.2, 0);
                    b.getWorld().spawnParticle(Particle.FALLING_WATER, w, 1, 0.03, 0.03, 0.03, 0);
                    b.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, w, 1, 0.05, 0.05, 0.05, 0, TEAR_SHIMMER);
                    if (((tick / 18 + i) % 3) == 0) {
                        b.getWorld().spawnParticle(Particle.END_ROD, b.getLocation(), 1, 0.02, 0.02, 0.02, 0.003);
                    }
                }
            }
        }

        /**
         * The hovering slot for blade {@code i}: placed on a curved fan that WRAPS around behind the
         * wielder (an arc/orbit, not a rigid lateral line), at hover height, gently bobbing. The end
         * blades ride a touch higher and angle outward so the fan reads as wrapping the body.
         */
        private Location slotFor(Player owner, int i, long tick) {
            Location base = owner.getLocation();
            float yaw = base.getYaw();
            double rad = Math.toRadians(yaw);
            Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector right   = new Vector(Math.cos(rad), 0, Math.sin(rad));
            // spread the blades symmetrically across the arc: frac in [-1, 1]
            double frac = RAPIER_COUNT == 1 ? 0.0 : (i / (double) (RAPIER_COUNT - 1)) * 2.0 - 1.0;
            double theta = Math.toRadians(frac * FORM_ARC_DEG); // angle off "directly behind"
            // direction from the wielder out to the blade: behind, swung around toward a flank
            Vector dir = forward.clone().multiply(-Math.cos(theta)).add(right.clone().multiply(Math.sin(theta)));
            double bob  = Math.sin(tick * 0.12 + i * 1.6) * 0.10;
            double lift = Math.abs(frac) * FORM_ARC_LIFT; // curve the fan vertically at its ends
            Location slot = base.clone()
                    .add(dir.multiply(FORM_RADIUS))
                    .add(0, FORM_HEIGHT + lift + bob, 0);
            slot.setYaw(yaw + (float) (frac * FORM_ARC_DEG)); // angle each blade outward along the arc
            slot.setPitch(0f);
            return slot;
        }

        // ---- the strike dart ------------------------------------------------------

        /**
         * Send the first available rapier (formed, idle, off cooldown) darting to the victim to strike
         * alongside the wielder, trailing tears. Returns true if one was sent; false if none was ready.
         */
        boolean launchStrike(Player owner, LivingEntity victim) {
            if (!alive) return false;
            long now = System.currentTimeMillis();
            int idx = -1;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (formed[i] && !busy[i] && now >= cooldownUntil[i]) { idx = i; break; }
            }
            if (idx < 0) return false;

            final int i = idx;
            final ItemDisplay b = blades[i];
            if (b == null || !b.isValid()) { formed[i] = false; return false; }
            busy[i] = true;
            b.setTeleportDuration(3); // smooth the slow glide-in
            owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.6f, 1.7f);

            new BukkitRunnable() {
                // phase 0 = APPROACH (slow homing glide-in), 1 = STRIKE (fast stab), 2 = ENGAGE (ricochet flurry)
                int phase = 0;
                int t = 0;
                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || b.isDead() || !b.isValid() || p == null || !p.isOnline()
                            || victim.isDead() || !victim.isValid()) {
                        expend(i, b, p); // abort cleanly: mark spent + on cooldown, hide
                        cancel();
                        return;
                    }
                    Location center = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                    Location cur = b.getLocation();

                    if (phase == 0) {                        // ---- APPROACH: slow, tip-leading glide-in ----
                        Vector to = center.toVector().subtract(cur.toVector());
                        double dist = to.length();
                        Vector dir = dist < 1.0e-4 ? new Vector(0, 0, 1) : to.clone().normalize();
                        b.setTransformation(pointing(dir));  // tip lances forward at the target
                        trailTear(cur);
                        if (dist <= APPROACH_STANDOFF || t >= APPROACH_TICKS) {
                            phase = 1; t = 0;
                            b.setTeleportDuration(1);         // snap the cadence for the quick stab
                            return;
                        }
                        Location next = cur.clone().add(dir.multiply(Math.min(dist, APPROACH_SPEED)));
                        next.setYaw(cur.getYaw());
                        next.setPitch(cur.getPitch());
                        b.teleport(next);
                        t++;
                        return;
                    }

                    if (phase == 1) {                        // ---- STRIKE: fast lunge into the body ----
                        Vector to = center.toVector().subtract(cur.toVector());
                        double dist = to.length();
                        Vector dir = dist < 1.0e-4 ? new Vector(0, 0, 1) : to.clone().normalize();
                        b.setTransformation(pointing(dir));
                        trailTear(cur);
                        if (dist <= 1.0 || t >= STRIKE_TICKS) {
                            b.teleport(center);
                            weapon.dealRapierStrike(p, victim); // fenced primary damage + wear + burst
                            phase = 2; t = 0;
                            return;
                        }
                        Location next = cur.clone().add(dir.multiply(Math.min(dist, STRIKE_SPEED)));
                        next.setYaw(cur.getYaw());
                        next.setPitch(cur.getPitch());
                        b.teleport(next);
                        t++;
                        return;
                    }

                    // ---- ENGAGE: ricochet/slash from the air for a little while, chipping small damage ----
                    if (t >= ENGAGE_TICKS) {
                        expend(i, b, p);
                        cancel();
                        return;
                    }
                    double ang = t * 1.35 + i;                       // wheel around the body
                    double rr = ENGAGE_RADIUS * (0.55 + 0.45 * Math.sin(t * 0.9));
                    Location slashPt = center.clone().add(
                            Math.cos(ang) * rr, Math.sin(t * 0.7) * 0.5, Math.sin(ang) * rr);
                    Vector inward = center.toVector().subtract(slashPt.toVector());
                    b.setTransformation(pointing(inward.lengthSquared() < 1.0e-4 ? new Vector(0, -1, 0) : inward.normalize()));
                    Location cut = slashPt.clone();
                    cut.setYaw(cur.getYaw());
                    cut.setPitch(cur.getPitch());
                    b.teleport(cut);
                    slashTrail(slashPt);
                    if (t > 0 && (t % ENGAGE_HIT_PERIOD) == 0) {
                        weapon.dealRapierChip(p, victim);           // tiny follow-up during the engagement
                    }
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
            return true;
        }

        /** Retire a sent blade: spent (out of the ring), on cooldown, hidden and tucked back at the wielder. */
        private void expend(int i, ItemDisplay b, Player owner) {
            if (!alive) return;
            formed[i] = false;
            busy[i] = false;
            cooldownUntil[i] = System.currentTimeMillis() + RAPIER_COOLDOWN_MS;
            if (b != null && b.isValid()) {
                b.setViewRange(0f); // stop rendering it while it's expended
                if (owner != null) b.teleport(owner.getLocation().add(0, FORM_HEIGHT, 0));
            }
        }

        // ---- recharge -------------------------------------------------------------

        /**
         * Fly every expended rapier back to the wielder and re-form the ring, gated to once per 45s.
         * Returns how many were recalled ({@code >0}); {@code 0} if nothing was expended; {@code -1} if
         * the 45s replenish cooldown is still running.
         */
        int recharge(Player owner) {
            if (!alive) return 0;
            int expended = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (!formed[i] && !busy[i]) expended++;
            }
            if (expended == 0) return 0;                              // the ring is already full
            long now = System.currentTimeMillis();
            if (now < rechargeReadyAt) return -1;                     // gated by the 45s replenish cooldown
            int n = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (!formed[i] && !busy[i]) { animateReturn(owner, i); n++; }
            }
            if (n > 0) {
                rechargeReadyAt = now + RECHARGE_COOLDOWN_MS;         // start the 45s replenish gate
                World w = owner.getWorld();
                w.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                w.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.5f, 1.6f);
            }
            return n;
        }

        /** Whole milliseconds left on the 45s replenish gate (0 if ready). */
        long rechargeRemaining() {
            return Math.max(0L, rechargeReadyAt - System.currentTimeMillis());
        }

        /** One recalled blade: re-appear at the wielder, then glide into its ring slot (cooldown untouched). */
        private void animateReturn(Player owner, final int i) {
            ItemDisplay b = blades[i];
            if (b == null || !b.isValid()) {
                b = spawnBlade(owner.getWorld(), owner.getEyeLocation());
                blades[i] = b;
            }
            final ItemDisplay blade = b;
            busy[i] = true;
            blade.setViewRange(1.0f);            // visible again as it flies home
            blade.setTeleportDuration(3);        // smooth the slow recall glide
            blade.teleport(owner.getEyeLocation());

            new BukkitRunnable() {
                int t = 0;
                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || blade.isDead() || !blade.isValid() || p == null || !p.isOnline()) {
                        if (alive) { formed[i] = true; busy[i] = false; }
                        cancel();
                        return;
                    }
                    Location slot = slotFor(p, i, t);
                    if (t >= RETURN_TICKS) {
                        blade.setTransformation(hoverTransform());
                        blade.teleport(slot);
                        formed[i] = true;
                        busy[i] = false;
                        cancel();
                        return;
                    }
                    Location cur = blade.getLocation();
                    Vector to = slot.toVector().subtract(cur.toVector());
                    double d = to.length();
                    blade.setTransformation(hoverTransform());
                    Location next = d <= RETURN_SPEED ? slot : cur.clone().add(to.multiply(RETURN_SPEED / d));
                    next.setYaw(slot.getYaw());
                    next.setPitch(0f);
                    blade.teleport(next);
                    trailTear(cur);
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        // ---- shared -----------------------------------------------------------------

        int readyCount() {
            long now = System.currentTimeMillis();
            int n = 0;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (formed[i] && !busy[i] && now >= cooldownUntil[i]) n++;
            }
            return n;
        }

        /** Reap every blade entity and mark the formation dead so its animations bail out. */
        void dispose() {
            alive = false;
            for (int i = 0; i < RAPIER_COUNT; i++) {
                if (blades[i] != null) { blades[i].remove(); blades[i] = null; }
            }
        }

        /** A light-blue tear trail (shimmering dust + dripping water + a faint white glint) marking a flying rapier's path. */
        private static void trailTear(Location at) {
            World w = at.getWorld();
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 2, 0.06, 0.06, 0.06, 0, TEAR_SHIMMER);
            w.spawnParticle(Particle.FALLING_WATER, at, 1, 0.05, 0.05, 0.05, 0);
            w.spawnParticle(Particle.END_ROD, at, 1, 0.01, 0.01, 0.01, 0.004); // faint white sparkle
        }

        /** A brighter arc trail for the ricochet/slash engagement flurry. */
        private static void slashTrail(Location at) {
            World w = at.getWorld();
            w.spawnParticle(Particle.DUST, at, 3, 0.12, 0.12, 0.12, 0, TEAR_FINE);
            w.spawnParticle(Particle.CRIT, at, 2, 0.08, 0.08, 0.08, 0.05);
            w.spawnParticle(Particle.END_ROD, at, 1, 0.02, 0.02, 0.02, 0.01);
        }

        /** Rapier hanging tip-down, at rest. */
        private static Transformation hoverTransform() {
            return new Transformation(new Vector3f(),
                    new Quaternionf().rotationTo(0, 1, 0, 0, -1, 0),
                    new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf());
        }

        /**
         * Rapier flattened tip-leading along {@code dir} — a lance/thrust pose (NOT held like a slash),
         * so it visibly points forward at the target during its dart. Stretched a touch along the blade
         * axis to read as a committed thrust.
         */
        private static Transformation pointing(Vector dir) {
            return new Transformation(new Vector3f(),
                    new Quaternionf().rotationTo(0, 1, 0, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ()),
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

    // The moveset is written from the code, not from the old how-to block: the ability names below are
    // placeholders except "Double Tag" and "Recharge", which the class docs and the action bar already
    // name. The chivalry line keeps the dim italic the old block gave it, in the helper's quote slot.
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
                            "behind you while the sword is held."),
                    new EgoLore.Ability("[Left Click] Double Tag",
                            "Landing a hit darts one ready rapier",
                            "in to stab for 2, then ricochet around",
                            "the body for 0.5 a slash. The sent",
                            "rapier is spent, and rests 4 seconds."),
                    new EgoLore.Ability("[Shift + Right-click] Recharge",
                            "Calls every spent rapier back into the",
                            "fan. Once per 45 seconds.")
            ));
}
