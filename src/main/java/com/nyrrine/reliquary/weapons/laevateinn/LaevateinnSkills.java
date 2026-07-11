package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Display;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lævateinn's right-click abilities — pure melee, no projectiles:
 * <ul>
 *   <li><b>Right-click (any form)</b> — <b>Gut Stab</b>: charge a draw-back, then rush to the
 *       looked-at target and impale + lift it, followed by a flurry of afterimage slashes.</li>
 *   <li><b>Shift-right-click (True Form)</b> — <b>Complete and Total Extermination</b>: hurl the flaming
 *       blade into a locked target, kick off the ground and rocket to it, pulling the sword out for damage.</li>
 * </ul>
 */
public final class LaevateinnSkills {

    private final Reliquary plugin;
    private final LaevateinnWeapon weapon;

    // ---- shutdown-safety registries (only ever touched on the main thread) ----------
    /**
     * Every live thrown molten-sword {@link ItemDisplay}. Its flight runnable removes it
     * on the normal paths, but a plugin disable / {@code /reload} / crash cancels that
     * runnable first, so {@link #flushThrownSwords()} clears any that were still in flight.
     */
    private final Set<ItemDisplay> thrownSwords = new HashSet<>();
    /** The flight tasks driving those displays, so a disable can stop them. */
    private final Set<BukkitRunnable> flightTasks = new HashSet<>();
    /**
     * Outstanding temp-carved blocks awaiting their ~7s timed restore, keyed by block
     * location so a timed restore and a disable flush can never double-restore the same
     * block. {@link #flushCarves()} restores them all immediately on shutdown.
     */
    private final Map<Location, BlockState> pendingCarves = new LinkedHashMap<>();

    // ---- Gut Stab (dash → impale → flurry) -----------------------------------------
    /** Gut Stab cooldown — a flat 7.5s dash across every form; it's a commitment, not a mobility spam. */
    private static long gutstabCd(int form) {
        return 7500L;
    }
    private static final double DASH_SPEED = 1.3;          // a snappy dash, a bit longer now
    private static final int DASH_TICKS = 8;
    private static final int RESTORE_DELAY_TICKS = 140;    // temp-broken blocks pop back after ~7s
    private static final int MAX_CARVE = 120;              // hard cap on temp-broken blocks per dash
    // Weak while sealed; at True Form the whole Gut Stab (impale + flurry) totals ~13 ≈ a Sharpness VII hit.
    private static final double[] IMPALE_DAMAGE = {2.0, 3.0, 4.0, 6.0};
    private static final double[] FLURRY_TOTAL = {3.0, 4.0, 5.0, 7.0};
    private static final int FLURRY_TICKS = 16;            // the stunned-flurry window
    private static final int FLURRY_DMG_EVERY = 4;         // a slash+hit every 4th tick (4 hits — easy on armor)

    // ---- Bullseye (True Form shift-right-click) ------------------------------------
    private static final long BULLSEYE_CD_MS = 90000L;    // 1:30
    private static final double THROW_SPEED = 2.5;        // the flaming sword's flight (fast, knife-like)
    private static final double SPIN_RATE = 1.15;         // radians/tick — a fast, consistent end-over-end tumble
    private static final int THROW_MAX_TICKS = 40;
    private static final int BULLSEYE_CHARGE_TICKS = 12;  // wind-up before the kick-off
    private static final double LEAP_SPEED = 1.6;
    private static final int LEAP_MAX_TICKS = 30;
    private static final double BULLSEYE_IMPACT = 10.0;   // the thrown-sword hit
    private static final double BULLSEYE_PULLOUT = 16.0;  // pulling the blade back out (akin to Gungnir dislodge)

    public LaevateinnSkills(Reliquary plugin, LaevateinnWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    public void onInteract(Player player, boolean sneaking) {
        int form = weapon.formOf(player.getUniqueId());
        if (sneaking) {
            if (form >= LaevateinnWeapon.MAX_FORM) exterminate(player);
            else sealedCue(player); // the finisher is still boxed away
            return;
        }
        gutstab(player, form);
    }

    private void sealedCue(Player player) {
        Location eye = player.getEyeLocation();
        player.getWorld().spawnParticle(Particle.SMOKE,
                eye.add(eye.getDirection().multiply(0.7)), 5, 0.12, 0.12, 0.12, 0.01);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_CHAIN_HIT, 0.6f, 0.7f);
    }

    // ---- Gut Stab ------------------------------------------------------------------

    /** Right-click: a short ~6-block dash that gut-stabs the first enemy it reaches and blasts a
     *  temporary hole through a wall in the way (side/top blocks, never the front or the floor). */
    private void gutstab(Player player, int form) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < weapon.gutstabReadyAt(id)) { onCooldown(player, weapon.gutstabReadyAt(id) - now, "Gut Stab"); return; }
        // Admin (Worthy) mode gets a 2s cooldown for testing; otherwise the full dash cooldown.
        long cd = weapon.isWorthy(player.getInventory().getItemInMainHand()) ? 2000L : gutstabCd(form);
        weapon.setGutstabReadyAt(id, now + cd);
        weapon.grantFallGrace(id, 3000L);

        final int f = clampForm(form);
        final Vector dir = player.getEyeLocation().getDirection().normalize();
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.3f);

        new BukkitRunnable() {
            int t = 0;
            boolean struck = false;
            final Set<Location> broken = new HashSet<>(); // blocks temp-carved beside the dash
            @Override
            public void run() {
                if (!player.isOnline() || !weapon.matches(player.getInventory().getItemInMainHand())
                        || struck || t++ >= DASH_TICKS) { cancel(); return; }
                carveSides(player, dir, broken);
                player.setVelocity(dir.clone().multiply(DASH_SPEED));
                player.setFallDistance(0f);
                dashTrail(player);
                LivingEntity tgt = dashHitTarget(player, dir);
                if (tgt != null) { struck = true; impaleAndFlurry(player, tgt, f); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** The first living thing the dash reaches, in a forward cone; null if the way is clear. */
    private LivingEntity dashHitTarget(Player player, Vector dir) {
        Location eye = player.getEyeLocation();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : player.getNearbyEntities(2.6, 2.6, 2.6)) {
            if (e == player || !(e instanceof LivingEntity le)) continue;
            if (!weapon.canHarm(player, le)) continue;
            Vector to = le.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double d = to.length();
            if (d < 1.0e-3 || to.clone().normalize().dot(dir) < 0.4) continue; // must be in front
            if (d < bestD) { bestD = d; best = le; }
        }
        return best;
    }

    /** A light purple streak for the dash — no block explosion (that comes from the carved blocks). */
    private void dashTrail(Player player) {
        World w = player.getWorld();
        Location c = player.getLocation().add(0, 1.0, 0);
        w.spawnParticle(Particle.DUST, c, 2, 0.15, 0.25, 0.15, 0,
                new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.1f));
    }

    /**
     * Blast a temporary hole for the dash — but only to the SIDES and TOP so the wielder's forward
     * view never fills with blocks. The player's own path is cleared quietly (no debris), while the
     * side/top blocks burst free chaotically as physics entities. Never touches the floor. Everything
     * returns slowly after ~7s with its contents intact — nothing drops, nothing is permanently lost.
     */
    private void carveSides(Player player, Vector dir, Set<Location> broken) {
        if (broken.size() >= MAX_CARVE) return;
        Vector fwd = dir.clone().setY(0);
        if (fwd.lengthSquared() < 1.0e-6) return;                 // looking straight up/down
        fwd.normalize();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        Location feet = player.getLocation();
        int feetY = feet.getBlockY();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (double ahead = 0.5; ahead <= 2.5; ahead += 1.0) {
            Location c = feet.clone().add(fwd.clone().multiply(ahead));
            // Center column: clear the wielder's own path quietly (nothing in front of the face).
            for (int up = 0; up <= 1; up++) {
                quietBreak(c.clone().add(0, up, 0).getBlock(), broken, feetY);
            }
            // Sides + top: a chaotic, powerful blast so the hole looks blown open, not stamped.
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                for (int side = 1; side <= 2; side++) {
                    double chance = side == 1 ? 0.9 : 0.5;
                    for (int up = 0; up <= 2; up++) {
                        double c2 = up == 2 ? chance * 0.75 : chance;
                        if (rng.nextDouble() > c2) continue;
                        int jitter = rng.nextInt(2);              // ragged edges
                        Block b = c.clone().add(right.clone().multiply(side * sgn)).add(0, up + jitter, 0).getBlock();
                        blastBreak(player, b, broken, feetY, rng);
                    }
                }
            }
            if (rng.nextBoolean()) { // a little chaos above the head, too
                blastBreak(player, c.clone().add(0, 2 + rng.nextInt(2), 0).getBlock(), broken, feetY, rng);
            }
        }
    }

    /** Clear a block on the wielder's own path with no VFX (so the front never clouds their vision). */
    private void quietBreak(Block b, Set<Location> broken, int feetY) {
        if (broken.size() >= MAX_CARVE || b.getY() < feetY) return;
        Location key = b.getLocation();
        if (broken.contains(key) || !isTempBreakable(b)) return;
        BlockState st = b.getState();
        broken.add(key);
        b.setType(Material.AIR, false);
        scheduleRestore(b, st);
    }

    /** Blow a side/top block free as a powerful physics burst parting from the wielder. */
    private void blastBreak(Player player, Block b, Set<Location> broken, int feetY, ThreadLocalRandom rng) {
        if (broken.size() >= MAX_CARVE || b.getY() < feetY) return;
        Location key = b.getLocation();
        if (broken.contains(key) || !isTempBreakable(b)) return;
        BlockState st = b.getState();
        broken.add(key);
        b.setType(Material.AIR, false);

        World w = b.getWorld();
        Location ctr = b.getLocation().add(0.5, 0.5, 0.5);
        Vector away = ctr.toVector().subtract(player.getLocation().add(0, 1.0, 0).toVector());
        if (away.lengthSquared() < 1.0e-6) away = new Vector(0, 1, 0);
        away.normalize().multiply(0.6 + rng.nextDouble() * 0.7).setY(0.4 + rng.nextDouble() * 0.5);
        FallingBlock fb = w.spawnFallingBlock(ctr, st.getBlockData());
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.setCancelDrop(true);
        fb.addScoreboardTag(LaevateinnVfx.DEBRIS_TAG);
        fb.setVelocity(away);
        plugin.getServer().getScheduler().runTaskLater(plugin, fb::remove, 50L);
        w.spawnParticle(Particle.BLOCK, ctr, 12, 0.3, 0.3, 0.3, 0.15, st.getBlockData());
        scheduleRestore(b, st);
    }

    /** Slowly restore a temp-broken block after ~7s (jittered), with a soft purple return cue. */
    private void scheduleRestore(Block b, BlockState st) {
        Location key = b.getLocation();
        pendingCarves.put(key, st);                           // track it so a disable can flush it
        long delay = RESTORE_DELAY_TICKS + ThreadLocalRandom.current().nextInt(30);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // If a shutdown flush already restored this block, don't double-restore it.
            if (pendingCarves.remove(key) == null) return;
            if (b.getType() == Material.AIR) {
                st.update(true, false);                           // pop it back, contents intact
                Location ctr = b.getLocation().add(0.5, 0.5, 0.5);
                b.getWorld().spawnParticle(Particle.DUST, ctr, 6, 0.3, 0.3, 0.3, 0,
                        new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.0f));
                LaevateinnVfx.twinkle(b.getWorld(), ctr, 2);
                b.getWorld().playSound(ctr, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.2f);
            }
        }, delay);
    }

    /** True if this block may be temporarily carved (solid, breakable, not a protected/special block). */
    private boolean isTempBreakable(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid()) return false; // skip air, liquids, non-solids
        return switch (m) {
            case BEDROCK, BARRIER, LIGHT, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, JIGSAW, END_PORTAL_FRAME, END_PORTAL, NETHER_PORTAL, REINFORCED_DEEPSLATE,
                 SPAWNER, OBSIDIAN, CRYING_OBSIDIAN -> false;
            default -> true;
        };
    }

    /** Arrival: a mace-break impact, impale + lift, then the improved afterimage-slash flurry. */
    private void impaleAndFlurry(Player player, LivingEntity target, int f) {
        World world = player.getWorld();
        weapon.dealDamage(target, IMPALE_DAMAGE[f], player);
        weapon.addHeat(player.getUniqueId(), LaevateinnWeapon.HEAT_PER_HIT);
        target.setVelocity(new Vector(0, 0.55, 0));
        final LaevateinnVfx.Pal pal = LaevateinnVfx.pal(f);
        LaevateinnVfx.maceSmash(world, player.getLocation(), 2.2, pal);
        LaevateinnVfx.debrisBurst(plugin, world, player.getLocation(), 2.2, 6);
        world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
        world.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.9f);

        final UUID tid = target.getUniqueId();
        final double total = FLURRY_TOTAL[f];
        final double step = total / (FLURRY_TICKS / (double) FLURRY_DMG_EVERY);
        new BukkitRunnable() {
            int t = 0;
            double dealt = 0;
            @Override
            public void run() {
                var e = plugin.getServer().getEntity(tid);
                if (!(e instanceof LivingEntity tgt) || tgt.isDead() || !player.isOnline()) { cancel(); return; }
                if (t >= FLURRY_TICKS) {
                    LaevateinnVfx.blunt(tgt.getWorld(), tgt.getLocation(), 1.0, pal);
                    cancel();
                    return;
                }
                Vector v = tgt.getVelocity();
                tgt.setVelocity(new Vector(v.getX() * 0.4, 0.02, v.getZ() * 0.4)); // hold aloft, stunned
                if (t % FLURRY_DMG_EVERY == 0) {
                    Location at = tgt.getLocation().add(0, 1.0, 0);
                    // One long afterimage slash per beat — a clear sequence, not a ball of short cuts.
                    LaevateinnVfx.afterimage(tgt.getWorld(), at, randomDir(), 4.2, pal);
                    if (dealt < total) {
                        double d = Math.min(step, total - dealt);
                        weapon.dealDamage(tgt, d, player);
                        dealt += d;
                    }
                    tgt.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f,
                            1.3f + ThreadLocalRandom.current().nextFloat() * 0.4f);
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** A random-ish direction to orient a swirling flurry slash around a target. */
    private Vector randomDir() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector d = new Vector(rng.nextDouble() - 0.5, rng.nextDouble() * 0.5 - 0.25, rng.nextDouble() - 0.5);
        return d.lengthSquared() < 1.0e-6 ? new Vector(1, 0, 0) : d.normalize();
    }

    // ---- Complete and Total Extermination: throw the flaming blade, kick off to it, pull it out ----

    private void exterminate(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < weapon.ultReadyAt(id)) {
            onCooldown(player, weapon.ultReadyAt(id) - now, "Complete and Total Extermination");
            return;
        }

        // Admin (Worthy) mode gets a 2s cooldown for debugging; otherwise the full 1:30.
        final long cd = weapon.isWorthy(player.getInventory().getItemInMainHand()) ? 2000L : BULLSEYE_CD_MS;

        // A STRAIGHT throw — no pre-lock. The blade flies dead-ahead where you're facing (Murder-Mystery
        // knife style) and pierces the first body in its path; whiff and it flies home for half the cooldown.
        weapon.setUltReadyAt(id, now + cd);
        // NB: no comboBusy root — abilities stay usable while the blade is out (her call).
        weapon.grantFallGrace(id, 9000L);

        final World world = player.getWorld();
        final Vector dir = player.getEyeLocation().getDirection().normalize();
        final ItemDisplay sword = spawnThrownSword(world,
                player.getEyeLocation().add(dir.clone().multiply(0.6)));
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.0f, 0.8f);
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);

        BukkitRunnable throwTask = new BukkitRunnable() {
            int phase = 0; // 0 = fly straight & pierce, 1 = charge, 2 = leap, 3 = whiff-return
            int t = 0;
            int spinTick = 0; // drives the continuous helicopter spin across flight phases
            UUID tid = null; // the body the straight throw pierced (found in phase 0)
            @Override
            public void run() {
                if (!player.isOnline()) {
                    despawnSword(sword); weapon.setComboBusy(id, 0L); flightTasks.remove(this); cancel(); return;
                }
                spinTick++;

                if (phase == 0) { // ---- FLY STRAIGHT, pierce the first body in the path ----
                    Location sl = sword.getLocation();
                    spinBlade(sword, dir, spinTick);
                    flameTrail(world, sl);
                    RayTraceResult rt = world.rayTraceEntities(sl, dir, THROW_SPEED + 0.6, 0.65,
                            en -> en instanceof LivingEntity && !en.getUniqueId().equals(player.getUniqueId()));
                    if (rt != null && rt.getHitEntity() instanceof LivingEntity struck && !struck.isDead()) {
                        tid = struck.getUniqueId();
                        Location tl = struck.getLocation().add(0, 1.0, 0);
                        sword.teleport(tl);
                        weapon.dealDamage(struck, BULLSEYE_IMPACT, player);
                        LaevateinnVfx.blunt(world, struck.getLocation(), 1.4, LaevateinnVfx.ORANGE_PAL);
                        world.spawnParticle(Particle.FLAME, tl, 24, 0.3, 0.4, 0.3, 0.06);
                        world.playSound(tl, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.7f);
                        phase = 1; t = 0; return;
                    }
                    Location next = sl.clone().add(dir.clone().multiply(THROW_SPEED));
                    if (!next.getBlock().isPassable() || t >= THROW_MAX_TICKS) { // hit a wall / spent -> whiff
                        weapon.setUltReadyAt(id, System.currentTimeMillis() + cd / 2);
                        phase = 3; t = 0; return;
                    }
                    sword.teleport(next);
                    t++;
                    return;
                }

                if (phase == 3) { // ---- WHIFF: the blade sails home, still spinning ----
                    Location sl = sword.getLocation();
                    Vector back = player.getEyeLocation().toVector().subtract(sl.toVector());
                    double d = back.length();
                    spinBlade(sword, dir, spinTick);
                    flameTrail(world, sl);
                    if (d <= 1.4 || t >= THROW_MAX_TICKS * 2) {
                        despawnSword(sword);
                        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.8f, 1.1f);
                        weapon.setComboBusy(id, 0L); flightTasks.remove(this); cancel(); return;
                    }
                    sword.teleport(sl.clone().add(back.multiply(Math.min(THROW_SPEED * 1.4, d) / d)));
                    t++;
                    return;
                }

                // phases 1 & 2 — the pierced body must still be alive
                var e = plugin.getServer().getEntity(tid);
                if (!(e instanceof LivingEntity tgt) || tgt.isDead()) {
                    weapon.setUltReadyAt(id, System.currentTimeMillis() + cd / 2);
                    despawnSword(sword); weapon.setComboBusy(id, 0L); flightTasks.remove(this); cancel(); return;
                }
                Location tl = tgt.getLocation().add(0, 1.0, 0);

                if (phase == 1) { // charge up — fire gathers, then kick off the ground
                    sword.teleport(tl);
                    Location c = player.getLocation().add(0, 1.0, 0);
                    double frac = t / (double) BULLSEYE_CHARGE_TICKS;
                    world.spawnParticle(Particle.FLAME, c, 6, 0.6 * (1 - frac) + 0.2, 0.6, 0.6 * (1 - frac) + 0.2, 0.03);
                    world.spawnParticle(Particle.DUST, c, 2, 0.5, 0.5, 0.5, 0, LaevateinnVfx.PURPLE_FLAKE);
                    if (t % 3 == 0) player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.6f, 0.6f + 0.04f * t);
                    if (t++ < BULLSEYE_CHARGE_TICKS) return;
                    Vector launch = tl.toVector().subtract(player.getLocation().add(0, 1, 0).toVector());
                    double d = launch.length();
                    if (d > 1.0e-3) launch.multiply(LEAP_SPEED / d);
                    player.setVelocity(launch);
                    player.setFallDistance(0f);
                    world.spawnParticle(Particle.EXPLOSION, player.getLocation(), 1, 0, 0, 0, 0);
                    player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.9f);
                    phase = 2; t = 0; return;
                }

                // phase 2: rocket to the target with a fire trail, then pull the blade out
                sword.teleport(tl);
                Location loc = player.getLocation().add(0, 1, 0);
                Vector to = tl.toVector().subtract(loc.toVector());
                double d = to.length();
                world.spawnParticle(Particle.FLAME, loc, 3, 0.1, 0.1, 0.1, 0.02);
                world.spawnParticle(Particle.SMALL_FLAME, loc, 2, 0.1, 0.1, 0.1, 0.01);
                world.spawnParticle(Particle.DUST, loc, 1, 0.1, 0.1, 0.1, 0, LaevateinnVfx.PURPLE_FLAKE);
                if (d <= 2.5 || t >= LEAP_MAX_TICKS) {
                    pullout(player, tgt);
                    despawnSword(sword);
                    weapon.setComboBusy(id, 0L);
                    flightTasks.remove(this);
                    cancel();
                    return;
                }
                player.setVelocity(to.multiply(Math.min(LEAP_SPEED, d) / d));
                player.setFallDistance(0f);
                t++;
            }
        };
        flightTasks.add(throwTask);
        throwTask.runTaskTimer(plugin, 0L, 1L);
    }

    /** Arrival: rip the blade out of the target — a big true-form slash + heavy pull-out damage. */
    private void pullout(Player player, LivingEntity target) {
        World world = target.getWorld();
        Location tl = target.getLocation().add(0, 1.0, 0);
        weapon.dealDamage(target, BULLSEYE_PULLOUT, player);
        LaevateinnVfx.trueFormSlash(plugin, world, tl, player.getEyeLocation().getDirection());
        LaevateinnVfx.blunt(world, target.getLocation(), 1.8, LaevateinnVfx.ORANGE_PAL);
        LaevateinnVfx.burningGround(plugin, world, target.getLocation(), 2.5, 50);
        world.spawnParticle(Particle.FLAME, tl, 40, 0.5, 0.6, 0.5, 0.08);
        world.spawnParticle(Particle.LAVA, tl, 10, 0.4, 0.4, 0.4, 0);
        world.spawnParticle(Particle.DUST, tl, 12, 0.5, 0.5, 0.5, 0, LaevateinnVfx.PURPLE_FLAKE);
        LaevateinnVfx.twinkle(world, tl, 10);
        world.playSound(tl, Sound.ITEM_TRIDENT_RETURN, 1.0f, 0.8f);
        world.playSound(tl, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.6f);
        Vector kb = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 1.0e-6) target.setVelocity(kb.normalize().multiply(0.6).setY(0.4));
    }

    /** Spawn the molten 3D Lævateinn blade (flies straight, flame-wrapped) at a point, tracked for shutdown. */
    private ItemDisplay spawnThrownSword(World world, Location at) {
        ItemDisplay sword = world.spawn(at, ItemDisplay.class, d -> {
            ItemStack model = new ItemStack(Material.NETHERITE_SWORD);
            org.bukkit.inventory.meta.ItemMeta m = model.getItemMeta();
            if (m != null) {
                var cmd = m.getCustomModelDataComponent();
                cmd.setStrings(java.util.List.of("laev3")); // the True-Form Lævateinn model
                m.setCustomModelDataComponent(cmd);
                model.setItemMeta(m);
            }
            d.setItemStack(model);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE); // raw model — only our aim rotation
            d.setPersistent(false);                           // never saved to the world
            d.setBrightness(new Display.Brightness(15, 15)); // glow like it's molten
            d.setInterpolationDuration(1);                    // lerp each spin step over 1 tick — crisp fast spin
            d.setTeleportDuration(2);                         // interpolate the flight between ticks — smooth motion
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf()));
        });
        thrownSwords.add(sword);
        return sword;
    }

    /**
     * Spin the thrown blade FAST end-over-end in the VERTICAL plane of travel — a tumbling helicopter
     * spin, never a flat horizontal one. The spin axis is built explicitly from the horizontal travel
     * direction (its perpendicular), so the roll is well-defined and it looks the same at every yaw (the
     * old {@code rotationTo} left the roll undefined — right only when thrown due south).
     */
    private static void spinBlade(ItemDisplay sword, Vector dir, int spinTick) {
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        if (flat.lengthSquared() < 1.0e-6) flat = new Vector(0, 0, 1);
        flat.normalize();
        Vector axis = new Vector(0, 1, 0).crossProduct(flat); // horizontal, perpendicular to travel
        if (axis.lengthSquared() < 1.0e-6) axis = new Vector(1, 0, 0);
        axis.normalize();
        float angle = (float) (SPIN_RATE * spinTick);
        Quaternionf q = new Quaternionf().fromAxisAngleRad(
                (float) axis.getX(), (float) axis.getY(), (float) axis.getZ(), angle);
        sword.setInterpolationDelay(0);
        sword.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf()));
    }

    /** The molten blade's flame + purple-flake trail. */
    private static void flameTrail(World world, Location sl) {
        Location fl = sl.clone().add(0, 0.3, 0);
        world.spawnParticle(Particle.FLAME, fl, 6, 0.18, 0.18, 0.18, 0.02);
        world.spawnParticle(Particle.SMALL_FLAME, fl, 3, 0.15, 0.15, 0.15, 0.01);
        world.spawnParticle(Particle.DUST, fl, 1, 0.12, 0.12, 0.12, 0, LaevateinnVfx.PURPLE_FLAKE);
    }

    /** Remove a thrown sword on a normal flight path and deregister it from shutdown tracking. */
    private void despawnSword(ItemDisplay sword) {
        thrownSwords.remove(sword);
        sword.remove();
    }

    // ---- shared --------------------------------------------------------------------

    private void onCooldown(Player player, long remainingMs, String name) {
        player.sendActionBar(Component.text(name + " — " + (remainingMs / 1000 + 1) + "s",
                TextColor.color(0x8C8A93)));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_HIT, 0.5f, 0.8f);
    }

    /** No per-player skill state kept here now; nothing to drop. */
    public void clear(UUID id) {
    }

    // ---- shutdown cleanup (plugin disable / reload / crash) -------------------------

    /**
     * Called from {@link LaevateinnWeapon#onDisable()} on plugin disable / {@code /reload}.
     * Restores any temp-carved blocks still awaiting their timed pop-back and removes any
     * thrown swords still in flight — the scheduler cancels those tasks first, so without
     * this the holes would stick and the displays would strand until a full restart.
     */
    void onDisable() {
        flushCarves();
        flushThrownSwords();
    }

    /** Restore every outstanding temp-carved block immediately, then clear the registry. */
    private void flushCarves() {
        for (BlockState st : pendingCarves.values()) {
            st.update(true, false); // pop it back, contents intact — no VFX during shutdown
        }
        pendingCarves.clear();
    }

    /** Stop every in-flight sword task and remove any thrown displays still in the world. */
    private void flushThrownSwords() {
        for (BukkitRunnable task : new ArrayList<>(flightTasks)) {
            try { task.cancel(); } catch (IllegalStateException ignored) { /* already stopped */ }
        }
        flightTasks.clear();
        for (ItemDisplay sword : new ArrayList<>(thrownSwords)) {
            if (sword != null && sword.isValid()) sword.remove();
        }
        thrownSwords.clear();
    }

    private static int clampForm(int form) {
        return Math.max(0, Math.min(LaevateinnWeapon.MAX_FORM, form));
    }
}
