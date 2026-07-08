package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lævateinn's left-click.
 *
 * <p><b>Sealed (forms 0–2)</b> — a heavy greatsword combo, gated by its own M1 cooldown so it
 * can't be spammed. Three deliberate steps that loop:
 * <ol>
 *   <li><b>Overhead strike</b> — a downward slash trace into a heavy blunt impact.</li>
 *   <li><b>Slashing strike</b> — a big heavy sweep that wraps around the view.</li>
 *   <li><b>Beyblade</b> — a spin-dash dealing damage in a full radius around the wielder.</li>
 * </ol>
 * All purple/white. A double-jump then a left-click in the air becomes a <b>Ground Slam</b>:
 * a dive to exactly where you aim with a Mace-style ground-crumble on landing.
 *
 * <p><b>True Form (3)</b> — a placeholder fast orange slash for now; its bespoke M1s are still
 * to be designed.
 */
public final class LaevateinnCombat {

    private final Reliquary plugin;
    private final LaevateinnWeapon weapon;

    // ---- tuning --------------------------------------------------------------------
    /** Flat strike damage per form (0..3): weak while sealed, full only at True Form. */
    private static final double[] STRIKE_DAMAGE = {5.0, 7.0, 10.0, 16.0};

    // Sealed M1 combo pacing — really slow at form 0, faster as the seals break (per form 0..2).
    private static final long[] M1_STEP_CD_MS   = {1050L, 750L, 480L}; // between steps 1 and 2
    private static final long[] M1_FINISH_CD_MS = {1350L, 1000L, 700L};// recovery after the beyblade finisher
    private static final long COMBO_RESET_MS = 2200L;                   // idle this long and the combo restarts

    private static final double PIERCE_RANGE = 3.4, PIERCE_CONE = 45.0;
    private static final double SLASH_RANGE = 3.6, SLASH_CONE = 110.0;
    private static final double BEYBLADE_RADIUS = 3.3;
    private static final double BEYBLADE_DASH = 0.9;   // sustained per-tick dash — a long spin-dash
    private static final int BEYBLADE_TICKS = 16;      // spin lifetime
    private static final int BEYBLADE_DASH_TICKS = 9;  // how long the dash thrust sustains

    // Ground slam (air left-click after a double-jump; the leap already started the slam cooldown).
    private static final double[] SLAM_DAMAGE = {8.0, 11.0, 14.0, 22.0};
    private static final double[] SLAM_RADIUS = {6.8, 7.4, 8.0, 9.2};   // 2× — a big, heavy crater
    private static final int SLAM_MAX_TARGETS = 20;
    private static final int SLAM_WATCH_TICKS = 45;
    private static final double SLAM_RANGE = 12.0;          // how far the aim can reach
    private static final double DIVE_SPEED = 1.9;           // fast, lethal dive toward the aim
    private static final double FALL_BONUS_PER_BLOCK = 1.2; // mace-like: a farther fall hits harder
    private static final double FALL_BONUS_CAP = 14.0;

    public LaevateinnCombat(Reliquary plugin, LaevateinnWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    public void onSwing(Player player) {
        UUID id = player.getUniqueId();
        int form = weapon.formOf(id);

        // Airborne after a double-jump leap: convert the swing into a ground slam (ungated by M1).
        if (weapon.airSlamArmed(id) && !player.isOnGround()) {
            weapon.disarmAirSlam(id);
            groundSlam(player, form);
            return;
        }

        if (form >= LaevateinnWeapon.MAX_FORM) { trueFormM1(player, id); return; }
        runCombo(player, id, form);
    }

    // ---- True Form left-click: one big flashy slash with a lingering burn -----------

    private static final long TRUEFORM_M1_CD = 380L;          // fast, but not spammable
    private static final double TRUEFORM_SLASH_RANGE = 4.0;
    private static final double TRUEFORM_SLASH_CONE = 130.0;
    private static final double TRUEFORM_LINGER_TOTAL = 7.0; // spread over ~1s per hit — fair vs netherite
    private static final int TRUEFORM_LINGER_TICKS = 20;
    private final Map<UUID, BukkitTask> lingering = new HashMap<>();

    private void trueFormM1(Player player, UUID id) {
        long now = System.currentTimeMillis();
        if (now < weapon.m1ReadyAt(id)) return; // fast, gated so it isn't machine-gunned
        weapon.setM1ReadyAt(id, now + TRUEFORM_M1_CD);

        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1.0, 0);
        LaevateinnVfx.trueFormSlash(plugin, world, center, player.getEyeLocation().getDirection());
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.8f, 1.2f);
        world.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.3f);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double cos = Math.cos(Math.toRadians(TRUEFORM_SLASH_CONE));
        int hits = 0;
        for (var entity : player.getNearbyEntities(TRUEFORM_SLASH_RANGE, TRUEFORM_SLASH_RANGE, TRUEFORM_SLASH_RANGE)) {
            if (entity == player || !(entity instanceof LivingEntity target)) continue;
            Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0e-6 || to.clone().normalize().dot(dir) < cos) continue;
            if (!weapon.canHarm(player, target)) continue;
            Vector kb = dir.clone().setY(0);
            if (kb.lengthSquared() < 1.0e-6) kb = dir.clone();
            target.setVelocity(target.getVelocity().add(kb.normalize().multiply(0.3).setY(0.1)));
            applyLingering(player, target); // the slash keeps burning for ~1s
            hits++;
        }
        weapon.addHeatForHits(id, hits);
    }

    /** Start/refresh a ~1s lingering burn on a target (11 spread across the ticks — no stacking). */
    private void applyLingering(Player src, LivingEntity target) {
        UUID tid = target.getUniqueId();
        BukkitTask old = lingering.remove(tid);
        if (old != null) old.cancel();
        final double per = TRUEFORM_LINGER_TOTAL / TRUEFORM_LINGER_TICKS;
        BukkitTask task = new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                var e = plugin.getServer().getEntity(tid);
                if (t++ >= TRUEFORM_LINGER_TICKS || !(e instanceof LivingEntity le) || le.isDead() || !src.isOnline()) {
                    lingering.remove(tid);
                    cancel();
                    return;
                }
                weapon.dealDamage(le, per, src);
                Location tl = le.getLocation().add(0, 1, 0);
                le.getWorld().spawnParticle(Particle.SMALL_FLAME, tl, 3, 0.3, 0.4, 0.3, 0.01);
                le.getWorld().spawnParticle(Particle.DUST, tl, 1, 0.2, 0.3, 0.2, 0, LaevateinnVfx.PURPLE_FLAKE);
            }
        }.runTaskTimer(plugin, 1L, 1L);
        lingering.put(tid, task);
    }

    // ---- 3-step combo (sealed forms) -----------------------------------------------

    /**
     * The three-step combo (overhead → slash → beyblade). Sealed forms are cooldown-gated (the sword
     * is unswingable while it cools); True Form is fully spammable — 1.8-PvP style — and burns orange.
     */
    private void runCombo(Player player, UUID id, int form) {
        boolean gated = form < LaevateinnWeapon.MAX_FORM;
        long now = System.currentTimeMillis();
        if (gated && now < weapon.m1ReadyAt(id)) return; // sealed: can't swing until the M1 cooldown is up

        int step = weapon.comboStep(id);
        if (now - weapon.lastM1At(id) > COMBO_RESET_MS) step = 0; // stale -> restart the chain
        weapon.setLastM1At(id, now);

        int f = Math.min(clampForm(form), 2); // per-form cooldown index
        switch (step) {
            case 0 -> { overheadStrike(player, form); if (gated) weapon.setM1ReadyAt(id, now + M1_STEP_CD_MS[f]); weapon.setComboStep(id, 1); }
            case 1 -> { slashStrike(player, form);   if (gated) weapon.setM1ReadyAt(id, now + M1_STEP_CD_MS[f]); weapon.setComboStep(id, 2); }
            default -> { beyblade(player, form);     if (gated) weapon.setM1ReadyAt(id, now + M1_FINISH_CD_MS[f]); weapon.setComboStep(id, 0); }
        }
    }

    /** Step 1 — an overhead blunt strike: a downward slash trace, then a heavy blunt impact low in front. */
    private void overheadStrike(Player player, int form) {
        World world = player.getWorld();
        Vector fwd = player.getEyeLocation().getDirection().setY(0);
        if (fwd.lengthSquared() < 1.0e-6) fwd = new Vector(0, 0, 1);
        fwd.normalize();

        // A downward overhead slash trace — from above the head down to the ground just in front.
        Location top = player.getEyeLocation().add(0, 1.0, 0).add(fwd.clone().multiply(0.3));
        Location bottom = player.getLocation().add(fwd.clone().multiply(1.9)).add(0, 0.25, 0);
        Vector down = bottom.toVector().subtract(top.toVector());
        double len = down.length();
        Vector dnorm = down.clone().normalize();
        Location mid = top.clone().add(down.clone().multiply(0.5));
        LaevateinnVfx.Pal p = LaevateinnVfx.pal(form);
        LaevateinnVfx.afterimage(world, mid, dnorm, len, p);
        LaevateinnVfx.afterimage(world, mid.clone().add(fwd.clone().multiply(0.12)), dnorm, len * 0.85, p);

        // The heavy blunt impact, low and in front.
        LaevateinnVfx.blunt(world, bottom, 1.2 + 0.12 * form, p);
        player.setVelocity(player.getVelocity().add(fwd.clone().multiply(0.14))); // step into the swing

        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.6f);
        world.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.5f);

        strike(player, form, PIERCE_RANGE, PIERCE_CONE, 0.35, 0.05); // overhead drives them down — low lift
    }

    /** Step 2 — a big heavy sweep that wraps around the view (slashing blunt strike). */
    private void slashStrike(Player player, int form) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector fwd = dir.clone().setY(0);
        if (fwd.lengthSquared() < 1.0e-6) fwd = new Vector(0, 0, 1);
        fwd.normalize();

        Location arcCenter = player.getEyeLocation().add(0, -0.55, 0); // swung low, under the eyeline
        LaevateinnVfx.Pal p = LaevateinnVfx.pal(form);
        Color body = form == 0 ? p.deep() : p.body();
        LaevateinnVfx.heavyArc(plugin, world, arcCenter, dir, body, p.edge(), 2.9 + 0.18 * form);
        player.setVelocity(player.getVelocity().add(fwd.clone().multiply(-0.08))); // recoil

        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.95f, 0.7f + rng.nextFloat() * 0.1f);

        strike(player, form, SLASH_RANGE, SLASH_CONE, 0.5, 0.30);
    }

    /** Step 3 — a beyblade: a long spin-dash that cuts each enemy it passes over once. */
    private void beyblade(Player player, int form) {
        UUID id = player.getUniqueId();
        Vector look = player.getEyeLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1.0e-6) look = new Vector(0, 0, 1);
        look.normalize();
        final Vector dash = look.clone();
        final int f = clampForm(form);

        weapon.grantFallGrace(id, 2500L); // the dash shouldn't punish you on landing
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 1.1f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.7f);

        final java.util.Set<UUID> struck = new java.util.HashSet<>();
        new BukkitRunnable() {
            int t = 0;
            int mobs = 0;
            @Override
            public void run() {
                if (t >= BEYBLADE_TICKS || !player.isOnline()) {
                    weapon.addHeatForHits(id, mobs); // capped heat for the whole spin
                    LaevateinnVfx.blunt(player.getWorld(), player.getLocation(), 0.9, LaevateinnVfx.pal(form));
                    cancel();
                    return;
                }
                World world = player.getWorld();
                LaevateinnVfx.Pal p = LaevateinnVfx.pal(form);
                // Sustained spin-dash for the first stretch (a long travel).
                if (t < BEYBLADE_DASH_TICKS) {
                    double y = player.getVelocity().getY();
                    player.setVelocity(dash.clone().multiply(BEYBLADE_DASH).setY(y > 0 ? y : 0.02));
                    player.setFallDistance(0f);
                }
                // A fast spinning sweep — one bright bar whipping round with a faint trailing blur,
                // rotating quickly so it reads as a spin, not a solid disc.
                Location c = player.getLocation().add(0, 0.9, 0);
                double a = t * 2.6; // fast rotation
                LaevateinnVfx.lineStrike(world, c, new Vector(Math.cos(a), 0, Math.sin(a)),
                        BEYBLADE_RADIUS * 1.4, p.body(), p.edge());
                double a2 = a - 0.55; // a trailing spoke = motion blur
                LaevateinnVfx.lineStrike(world, c, new Vector(Math.cos(a2), 0, Math.sin(a2)),
                        BEYBLADE_RADIUS * 1.2, p.deep(), p.body());
                // Cut each enemy the spin passes over — once.
                for (var e : player.getNearbyEntities(BEYBLADE_RADIUS, BEYBLADE_RADIUS, BEYBLADE_RADIUS)) {
                    if (e == player || !(e instanceof LivingEntity target)) continue;
                    if (!weapon.canHarm(player, target)) continue;
                    if (!struck.add(e.getUniqueId())) continue;
                    weapon.dealDamage(target, STRIKE_DAMAGE[f], player);
                    mobs++;
                    Vector away = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() < 1.0e-6) away = dash.clone();
                    away.normalize().multiply(0.5).setY(0.25);
                    target.setVelocity(target.getVelocity().add(away));
                    target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0),
                            4, 0.2, 0.25, 0.2, 0.08);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Shared forward-cone hit: flat per-form damage + knockback; stokes Heat per victim. */
    private void strike(Player player, int form, double range, double coneDeg, double knockback, double lift) {
        UUID id = player.getUniqueId();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double damage = STRIKE_DAMAGE[clampForm(form)];
        double cosLimit = Math.cos(Math.toRadians(coneDeg));
        LaevateinnVfx.Pal p = LaevateinnVfx.pal(form);

        int hits = 0;
        for (var entity : player.getNearbyEntities(range, range, range)) {
            if (entity == player || !(entity instanceof LivingEntity target)) continue;
            Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0e-6) continue;
            if (to.clone().normalize().dot(dir) < cosLimit) continue;
            if (!weapon.canHarm(player, target)) continue;

            weapon.dealDamage(target, damage, player); // plugin damage — passes the melee guard
            hits++;

            Vector kb = dir.clone().setY(0);
            if (kb.lengthSquared() < 1.0e-6) kb = dir.clone();
            kb.normalize().multiply(knockback).setY(lift);
            target.setVelocity(target.getVelocity().add(kb));

            Location tl = target.getLocation().add(0, 1, 0);
            target.getWorld().spawnParticle(Particle.DUST, tl, 5, 0.25, 0.3, 0.25, 0,
                    new Particle.DustOptions(p.body(), 1.1f));
            target.getWorld().spawnParticle(Particle.CRIT, tl, 4, 0.2, 0.25, 0.2, 0.08);
            if (p == LaevateinnVfx.ORANGE_PAL) {
                target.getWorld().spawnParticle(Particle.FLAME, tl, 4, 0.25, 0.3, 0.25, 0.02);
            }
        }
        weapon.addHeatForHits(id, hits); // capped so cleaving a crowd can't over-stoke
    }

    // ---- ground slam (air left-click) ----------------------------------------------

    private void groundSlam(Player player, int form) {
        UUID id = player.getUniqueId();
        // The double-jump that armed this already started the slam cooldown.
        weapon.grantFallGrace(id, 6000L); // no fall damage through the whole dive

        World world = player.getWorld();
        final Location aim = aimGround(player, SLAM_RANGE);
        final double startY = player.getY();
        world.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.6f);

        new BukkitRunnable() {
            int t = 0;
            int stuck = 0;
            Location prev = player.getLocation();
            @Override
            public void run() {
                if (!player.isOnline()) { cancel(); return; }
                Location loc = player.getLocation();

                // Only ever impact on real ground contact (or wedged/timed-out) — never mid-air.
                boolean onGround = player.isOnGround() && t > 2;
                double horiz = square(loc.getX() - aim.getX()) + square(loc.getZ() - aim.getZ());
                boolean atAim = horiz < 1.5 * 1.5 && loc.getY() <= aim.getY() + 0.6;
                if (loc.distanceSquared(prev) < 0.0025) stuck++; else stuck = 0;
                prev = loc.clone();

                if (onGround || atAim || stuck >= 4 || t >= SLAM_WATCH_TICKS) {
                    impactSlam(player, loc, form, startY);
                    cancel();
                    return;
                }
                // Dive toward the aimed ground point, always biting hard downward.
                Vector to = aim.toVector().subtract(loc.toVector());
                double d = to.length();
                Vector vel = d < 1.0e-3 ? new Vector(0, -DIVE_SPEED, 0)
                        : to.multiply(Math.min(DIVE_SPEED, d) / d);
                if (vel.getY() > -0.35) vel.setY(-0.35);
                player.setVelocity(vel);
                player.setFallDistance(0f);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.4, 0), 1, 0.05, 0.05, 0.05, 0,
                        new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.0f));
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static double square(double x) { return x * x; }

    private void impactSlam(Player player, Location center, int form, double startY) {
        UUID id = player.getUniqueId();
        World world = player.getWorld();
        player.setFallDistance(0f);

        double fall = Math.max(0, startY - center.getY());
        double bonus = Math.min(fall * FALL_BONUS_PER_BLOCK, FALL_BONUS_CAP);
        double radius = SLAM_RADIUS[clampForm(form)];
        double damage = SLAM_DAMAGE[clampForm(form)] + bonus;

        LaevateinnVfx.maceSmash(world, center, radius, LaevateinnVfx.pal(form));
        LaevateinnVfx.debrisBurst(plugin, world, center, radius, (int) Math.round(radius * 4)); // more block-shake
        LaevateinnVfx.burningGround(plugin, world, center, radius, form >= LaevateinnWeapon.MAX_FORM ? 70 : 45);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.5f);
        world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.6f);
        world.playSound(center, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.5f, 0.5f);
        player.setVelocity(new Vector(0, 0.14, 0)); // a small rebound reads as the shockwave

        int hit = 0;
        for (var e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (hit >= SLAM_MAX_TARGETS) break;
            if (e == player || !(e instanceof LivingEntity target)) continue;
            if (!weapon.canHarm(player, target)) continue;
            weapon.dealDamage(target, damage, player);
            Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-6) away = new Vector(0, 0, 1);
            away.normalize().multiply(0.6).setY(0.6); // knock up + out
            target.setVelocity(target.getVelocity().add(away));
            hit++;
        }
        weapon.addHeatForHits(id, hit); // capped
    }

    /** A real GROUND point at the column the wielder is aiming at — never a mid-air spot. */
    private Location aimGround(Player player, double range) {
        RayTraceResult r = player.rayTraceBlocks(range);
        Location look;
        if (r != null && r.getHitPosition() != null) {
            look = r.getHitPosition().toLocation(player.getWorld());
        } else {
            Location eye = player.getEyeLocation();
            look = eye.clone().add(eye.getDirection().multiply(range));
        }
        return groundBelow(look);
    }

    /** Scan down the aimed column for the first solid block and return the point just above it. */
    private Location groundBelow(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int startY = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, loc.getBlockY() + 1));
        for (int y = startY; y > w.getMinHeight(); y--) {
            if (w.getBlockAt(x, y, z).getType().isSolid()) {
                return new Location(w, x + 0.5, y + 1.0, z + 0.5);
            }
        }
        return loc; // open void — fall back to the aimed point
    }

    private static int clampForm(int form) {
        return Math.max(0, Math.min(LaevateinnWeapon.MAX_FORM, form));
    }
}
