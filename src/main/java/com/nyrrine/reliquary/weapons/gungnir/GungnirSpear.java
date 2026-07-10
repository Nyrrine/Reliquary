package com.nyrrine.reliquary.weapons.gungnir;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A single Gungnir throw, in flight. Runs every tick and marches the spear along its own trajectory
 * in small sub-steps (so a fast spear can't tunnel through walls or targets between ticks).
 *
 * <p>Two modes: a <b>straight bolt</b> (left-click) that homes gently and buries into the first
 * thing it meets, and a <b>ricochet bolt</b> ({@code bouncing}, right-click) that homes onto random
 * nearby mobs and reflects off the world, careening around until the owner recalls it.
 *
 * <p><b>Design language.</b> A disciplined palette — gold light, a thin shadow accent, dark ember
 * sparks — nothing muddy. The trail is a bright bolt sheathed in a tight shadow helix; impacts are a
 * genuine 3D blast (an expanding gold sphere with black crackle), not a flat ring.
 */
final class GungnirSpear extends BukkitRunnable {

    private enum State { OUT, LODGED_BLOCK, LODGED_ENTITY, RETURNING }

    // Flight tuning. Speeds are in blocks/tick; the tick loop sub-steps them by STEP.
    private static final double SPEED_OUT   = 3.5;
    private static final double SPEED_BACK  = 3.4;
    private static final double STEP        = 0.5;
    private static final double MAX_RANGE   = 90.0;
    private static final double HIT_RADIUS  = 1.25; // how close the tip must pass to bury in a target
    private static final double CATCH_DIST  = 1.8;

    // Aim-assist ("never misses"): steer toward a target that's roughly ahead of the tip.
    private static final double AIM_RADIUS    = 4.5;
    private static final double AIM_AHEAD_DOT = 0.55; // within ~57° of the current heading
    private static final double AIM_STRENGTH  = 0.30; // per-tick lerp of heading toward the mark

    private static final double IMPACT_DAMAGE   = 9.0; // burying into a target
    private static final double DISLODGE_DAMAGE = 8.0; // tearing back out on recall

    private static final int LODGE_TIMEOUT_TICKS = 1200; // 60s, then it comes home on its own

    // Ricochet mode (right-click): homes onto mobs and bounces off the world until recalled.
    private static final double BOUNCE_SPEED     = 2.8;  // a touch slower so the bounces read
    private static final double BOUNCE_SEEK      = 8.0;  // radius it looks for a mob to home onto (cheaper AABB; still ample reach)
    private static final double BOUNCE_HOMING     = 0.22; // steering strength toward the current target
    private static final double BOUNCE_DAMAGE     = 2.0;  // 1 heart — it can stay out a long time
    private static final long   BOUNCE_HIT_COOLDOWN_MS = 600L; // per-target damage throttle
    private static final long   BOUNCE_WANDER_MS   = 800L; // after caroming off a lone mob, wander before re-homing
    private static final int    BOUNCE_MAX_TICKS  = 1200; // 60s safety before it auto-returns

    // Palette — the item-name gold (impacts), a shade darker for the thrown bolt, + a shadow accent.
    static final Color GOLD       = Color.fromRGB(0xFF, 0xC1, 0x07); // name gold — impacts
    static final Color THROW_GOLD = Color.fromRGB(0xE8, 0xAE, 0x00); // a wee bit darker — the thrown bolt
    static final Color SHADE      = Color.fromRGB(0x12, 0x0F, 0x0A); // warm near-black
    static final Particle.DustOptions CORE  = new Particle.DustOptions(THROW_GOLD, 0.9f); // the bolt
    static final Particle.DustOptions FLASH = new Particle.DustOptions(GOLD, 1.5f);       // bright flash
    static final Particle.DustOptions ASH   = new Particle.DustOptions(SHADE, 1.0f);      // shadow accent

    private final Reliquary plugin;
    private final GungnirWeapon weapon;
    private final UUID ownerId;
    private final ItemStack item;   // the exact relic that was thrown — handed back verbatim on catch
    private final World world;

    private final boolean bouncing;           // right-click ricochet mode vs. straight left-click bolt
    private final Particle.DustOptions core;  // trail core

    private State state = State.OUT;
    private final Location pos;      // the spear tip
    private Vector dir;              // current heading (unit)
    private double traveled = 0.0;
    private int lodgedTicks = 0;
    private int beamStep = 0;        // drives the helix + glint spacing along the trail

    // ricochet state
    private LivingEntity bounceTarget;
    private int flightTicks = 0;
    private long wanderUntil = 0;                                // homing paused until this time (lone-mob carom)
    private final Map<UUID, Long> hitCooldown = new HashMap<>(); // per-target damage throttle

    /** The body the spear is buried in, while LODGED_ENTITY. */
    private LivingEntity embedded;

    GungnirSpear(Reliquary plugin, GungnirWeapon weapon, Player owner, ItemStack item, boolean bouncing) {
        this.plugin = plugin;
        this.weapon = weapon;
        this.ownerId = owner.getUniqueId();
        this.item = item;
        this.world = owner.getWorld();
        this.bouncing = bouncing;
        this.core = new Particle.DustOptions(THROW_GOLD, 0.9f);
        this.pos = owner.getEyeLocation();
        this.dir = pos.getDirection().normalize();
    }

    @Override
    public void run() {
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            // The quit/disable path owns the item now; just stop drawing.
            cancel();
            return;
        }
        switch (state) {
            case OUT -> { if (bouncing) flyBounce(owner); else flyOut(owner); }
            case LODGED_BLOCK -> idleInWorld();
            case LODGED_ENTITY -> idleInBody();
            case RETURNING -> flyBack(owner);
        }
    }

    // ---- OUT: the launch ----------------------------------------------------------

    private void flyOut(Player owner) {
        // ONE world scan for the whole tick: a box big enough to hold this tick's swept segment
        // (+ the hit radius) and the aim-assist radius. Everything below tests THIS list in memory.
        List<LivingEntity> candidates = nearbyLiving(Math.max(AIM_RADIUS, SPEED_OUT + HIT_RADIUS));
        steerTowardMark(candidates);

        double moved = 0.0;
        while (moved < SPEED_OUT) {
            double step = Math.min(STEP, SPEED_OUT - moved);

            // A block ahead stops it short.
            Location next = pos.clone().add(dir.clone().multiply(step));
            if (next.getBlock().getType().isSolid()) {
                lodgeInWorld();
                return;
            }

            pos.add(dir.clone().multiply(step));
            drawBeam();

            // First body in reach? Bury into it and stop — no pass-through.
            LivingEntity hit = firstHit(candidates);
            if (hit != null) {
                impale(owner, hit);
                return;
            }

            traveled += step;
            moved += step;
            if (traveled >= MAX_RANGE) {
                lodgeInWorld();
                return;
            }
        }
    }

    // ---- OUT (ricochet): homes onto mobs, bounces off the world, until recalled ----

    private void flyBounce(Player owner) {
        if (++flightTicks >= BOUNCE_MAX_TICKS) { beginRecall(); return; } // safety so it can't be lost

        // ONE world scan for the whole tick: a box big enough to hold both the homing seek radius and
        // this tick's swept segment (+ hit radius). Homing AND every sub-step hit-test reuse THIS list.
        List<LivingEntity> candidates = nearbyLiving(BOUNCE_SPEED + BOUNCE_SEEK);

        // Home toward a mob — unless we're briefly wandering after caroming off a lone target.
        if (System.currentTimeMillis() >= wanderUntil) {
            if (bounceTarget == null || bounceTarget.isDead() || !bounceTarget.isValid()
                    || center(bounceTarget).distance(pos.toVector()) > BOUNCE_SEEK) {
                bounceTarget = randomNearbyMob(candidates, pos, null);
            }
            if (bounceTarget != null) {
                Vector to = center(bounceTarget).subtract(pos.toVector());
                if (to.lengthSquared() > 1e-4) {
                    dir = dir.clone().multiply(1 - BOUNCE_HOMING).add(to.normalize().multiply(BOUNCE_HOMING)).normalize();
                }
            }
        }

        double moved = 0.0;
        while (moved < BOUNCE_SPEED) {
            double step = Math.min(STEP, BOUNCE_SPEED - moved);
            Location next = pos.clone().add(dir.clone().multiply(step));
            if (next.getBlock().getType().isSolid()) {
                bounceOffBlock(step); // reflect + fx; resume the flight next tick
                return;
            }
            pos.add(dir.clone().multiply(step));
            drawBeam();

            LivingEntity hit = firstHit(candidates);
            if (hit != null) {
                long now = System.currentTimeMillis();
                Long last = hitCooldown.get(hit.getUniqueId());
                if (last == null || now - last >= BOUNCE_HIT_COOLDOWN_MS) { // throttle damage per target
                    hit.damage(BOUNCE_DAMAGE, owner);
                    hit.setVelocity(hit.getVelocity().add(dir.clone().multiply(0.3)));
                    hitCooldown.put(hit.getUniqueId(), now);
                    bounceHitFx(hit);
                }
                // Bounce to a DIFFERENT mob if one's around (ping-pong the room); if this is the only
                // one, wander off for a beat instead of fixating on it.
                LivingEntity other = randomNearbyMob(candidates, pos, hit);
                if (other != null) {
                    bounceTarget = other;
                    dir = center(other).subtract(pos.toVector()).normalize();
                } else {
                    bounceTarget = null;
                    wanderUntil = now + BOUNCE_WANDER_MS;
                    dir = deflect(dir);
                }
                return;
            }
            moved += step;
        }
    }

    /** Reflect the heading off whichever solid face lies just ahead — a real physics bounce. */
    private void bounceOffBlock(double step) {
        Vector d = dir.clone();
        double probe = step * 2;
        boolean fx = pos.clone().add(d.getX() * probe, 0, 0).getBlock().getType().isSolid();
        boolean fy = pos.clone().add(0, d.getY() * probe, 0).getBlock().getType().isSolid();
        boolean fz = pos.clone().add(0, 0, d.getZ() * probe).getBlock().getType().isSolid();
        if (!fx && !fy && !fz) { fx = fy = fz = true; } // hit a corner -> reverse
        if (fx) d.setX(-d.getX());
        if (fy) d.setY(-d.getY());
        if (fz) d.setZ(-d.getZ());
        dir = d.normalize();
        world.playSound(pos, Sound.ITEM_TRIDENT_HIT, 0.8f, 1.4f);
        world.spawnParticle(Particle.DUST, pos, 8, 0.12, 0.12, 0.12, 0, FLASH);
        sparks(pos, 4, 0.08);
    }

    private void bounceHitFx(LivingEntity target) {
        Location c = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST, c, 8, 0.3, 0.4, 0.3, 0, FLASH);
        sparks(c, 10, 0.14);
        world.playSound(c, Sound.ITEM_TRIDENT_HIT, 1.0f, 1.0f);
    }

    /**
     * A random living body within seek range to home onto — excluding the owner and an optional one
     * to avoid (the just-hit mob, so a ricochet prefers a <em>different</em> target when one exists).
     * Cooldown mobs are still eligible so the bolt keeps ping-ponging between a pair; damage itself is
     * throttled separately.
     */
    private LivingEntity randomNearbyMob(List<LivingEntity> candidates, Location around, LivingEntity avoid) {
        BoundingBox seek = BoundingBox.of(around.toVector(), BOUNCE_SEEK, BOUNCE_SEEK, BOUNCE_SEEK);
        List<LivingEntity> mobs = new ArrayList<>();
        for (LivingEntity le : candidates) {
            if (le == avoid || le.isDead()) continue;
            if (!le.getBoundingBox().overlaps(seek)) continue; // same box test getNearbyEntities did
            mobs.add(le);
        }
        if (mobs.isEmpty()) return null;
        return mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
    }

    /**
     * One world entity scan for the whole tick. Gathers living, non-owner bodies whose hitboxes fall
     * within a box of {@code halfExtent} around the tip — sized so it holds every per-sub-step and
     * homing query this tick makes, so those can be answered in memory instead of re-scanning.
     */
    private List<LivingEntity> nearbyLiving(double halfExtent) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(pos, halfExtent, halfExtent, halfExtent)) {
            if (e.getUniqueId().equals(ownerId)) continue;
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            out.add(le);
        }
        return out;
    }

    /** Bounce off in a semi-random backward direction so the bolt wanders instead of oscillating on one spot. */
    private Vector deflect(Vector d) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return d.clone().multiply(-1)
                .add(new Vector(r.nextDouble(-0.4, 0.4), r.nextDouble(-0.2, 0.4), r.nextDouble(-0.4, 0.4)))
                .normalize();
    }

    /** If a living target sits roughly ahead within reach, curve the heading gently onto it. */
    private void steerTowardMark(List<LivingEntity> candidates) {
        BoundingBox aim = BoundingBox.of(pos.toVector(), AIM_RADIUS, AIM_RADIUS, AIM_RADIUS);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : candidates) {
            if (le.isDead() || !le.getBoundingBox().overlaps(aim)) continue; // same box test getNearbyEntities did
            Vector to = center(le).subtract(pos.toVector());
            double dist = to.length();
            if (dist < 0.01) continue;
            if (to.clone().multiply(1.0 / dist).dot(dir) < AIM_AHEAD_DOT) continue; // behind / off to the side
            if (dist < bestDist) { bestDist = dist; best = le; }
        }
        if (best == null) return;
        Vector to = center(best).subtract(pos.toVector()).normalize();
        dir = dir.clone().multiply(1.0 - AIM_STRENGTH).add(to.multiply(AIM_STRENGTH)).normalize();
    }

    /**
     * The nearest living body (not the owner) within the tip's kill radius at the current sub-step,
     * else null. Tests the per-tick candidate list in memory — no world scan — preserving the original
     * "first body along the swept path this tick" behaviour (this is called at each sub-step tip).
     */
    private LivingEntity firstHit(List<LivingEntity> candidates) {
        BoundingBox reach = BoundingBox.of(pos.toVector(), HIT_RADIUS, HIT_RADIUS, HIT_RADIUS);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : candidates) {
            if (le.isDead() || !le.getBoundingBox().overlaps(reach)) continue; // same box test getNearbyEntities did
            double d = center(le).subtract(pos.toVector()).lengthSquared();
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    /** Bury the spear into a body: impact damage, a harpoon-impalement starburst, then ride inside it. */
    private void impale(Player owner, LivingEntity target) {
        target.damage(IMPACT_DAMAGE, owner);   // the initial hit — not yet "amplified"
        target.setVelocity(target.getVelocity().add(dir.clone().multiply(0.5).setY(0.15)));
        setEmbedded(target);
        state = State.LODGED_ENTITY;
        lodgedTicks = 0;
        snapToBody();

        explode(pos.clone(), 2.0); // a real 3D blast where it buries in
        crackle(target, 4);
        world.playSound(pos, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.85f);
        world.playSound(pos, Sound.ITEM_TRIDENT_HIT, 0.9f, 1.0f);
    }

    /** A big crackle of gold sparks scattering off a struck body, over a few ticks — not literal crits. */
    private void crackle(LivingEntity target, int pulses) {
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= pulses || target.isDead() || !target.isValid()) { cancel(); return; }
                Location c = target.getLocation().add(0, target.getHeight() * 0.5, 0);
                sparks(c, 20, 0.2); // black spark-debris scattering off the body
                world.spawnParticle(Particle.DUST, c, 10, 0.45, 0.6, 0.45, 0, FLASH);
                world.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.2f + t * 0.15f);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---- LODGED: waiting on a recall ----------------------------------------------

    private void idleInWorld() {
        if (++lodgedTicks >= LODGE_TIMEOUT_TICKS) { beginRecall(); return; }
        if (lodgedTicks % 10 == 0) {
            world.spawnParticle(Particle.DUST, pos.clone().add(0, 0.35, 0), 1, 0, 0.05, 0, 0, CORE);
        }
    }

    private void idleInBody() {
        if (embedded == null || embedded.isDead() || !embedded.isValid()) {
            // Its host is gone — leave the spear where it fell so it can still be recalled.
            clearEmbedded();
            state = State.LODGED_BLOCK;
            lodgedTicks = 0;
            return;
        }
        if (++lodgedTicks >= LODGE_TIMEOUT_TICKS) { beginRecall(); return; }
        snapToBody();
        // A quiet buried glimmer so it doesn't clutter a fight.
        if (lodgedTicks % 6 == 0) {
            world.spawnParticle(Particle.DUST, pos, 1, 0.06, 0.12, 0.06, 0, CORE);
        }
    }

    /** Keep the tip glued to the buried body's torso. */
    private void snapToBody() {
        Location l = embedded.getLocation();
        pos.setX(l.getX());
        pos.setY(l.getY() + embedded.getHeight() * 0.6);
        pos.setZ(l.getZ());
    }

    private void setEmbedded(LivingEntity target) {
        embedded = target;
        weapon.markEmbedded(target.getUniqueId()); // its vibrations now amplify strikes on this body
    }

    private void clearEmbedded() {
        if (embedded != null) {
            weapon.unmarkEmbedded(embedded.getUniqueId());
            embedded = null;
        }
    }

    /** Stop this spear and release any vibration mark it holds (used on quit/shutdown). */
    void end() {
        clearEmbedded();
        cancel();
    }

    // ---- RETURNING: homing recall -------------------------------------------------

    private void flyBack(Player owner) {
        Location target = owner.getEyeLocation();
        double moved = 0.0;
        while (moved < SPEED_BACK) {
            double step = Math.min(STEP, SPEED_BACK - moved);
            Vector to = target.toVector().subtract(pos.toVector());
            double remaining = to.length();
            if (remaining <= CATCH_DIST) { catchIt(owner); return; }
            dir = to.multiply(1.0 / remaining);
            pos.add(dir.clone().multiply(step));
            drawBeam();               // clean flight home — no group-piercing
            moved += step;
        }
    }

    // ---- shared helpers -----------------------------------------------------------

    /** A bright gold bolt sheathed in a tight twin-strand shadow helix, with an occasional glint. */
    private void drawBeam() {
        world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, core);
        if ((beamStep & 1) == 0) {
            Vector[] b = perp(dir);
            double ang = beamStep * 1.3;                 // tight coils
            for (int k = 0; k < 2; k++) {
                double a2 = ang + k * Math.PI;
                Location hp = pos.clone()
                        .add(b[0].clone().multiply(Math.cos(a2) * 0.2))
                        .add(b[1].clone().multiply(Math.sin(a2) * 0.2));
                world.spawnParticle(Particle.DUST, hp, 1, 0, 0, 0, 0, ASH);
            }
        }
        if (beamStep % 9 == 0) world.spawnParticle(Particle.SMOKE, pos, 1, 0, 0, 0, 0.01); // dark glint
        beamStep++;
    }

    /** Two unit vectors spanning the plane perpendicular to u. */
    private Vector[] perp(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector a = n.clone().crossProduct(ref).normalize();
        Vector b = n.clone().crossProduct(a).normalize();
        return new Vector[]{a, b};
    }

    /**
     * The signature impact: an actual explosion, not a flat ring. A vanilla explosion puff at the
     * centre, a bright flash, black spark-debris blown outward, and a gold shell that expands as a
     * genuine 3D sphere over a few ticks — so it reads as a blast from any angle.
     */
    private void explode(Location at, double scale) {
        final Location c = at.clone();
        // a gold flash, a scatter of black crackle, and ember-spark debris (no vanilla TNT puff)
        world.spawnParticle(Particle.DUST, c, 14, 0.2, 0.2, 0.2, 0, FLASH);
        world.spawnParticle(Particle.DUST, c, 16, 0.4 * scale, 0.4 * scale, 0.4 * scale, 0, ASH);
        sparks(c, (int) (10 * scale), 0.25);

        final Particle.DustOptions goldShell = new Particle.DustOptions(GOLD, 1.2f);
        final int rings = 4;
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= rings) { cancel(); return; }
                double r = 0.5 + scale * (t / (double) rings);
                int pts = 26;
                for (int i = 0; i < pts; i++) {
                    double y = 1 - (i / (double) (pts - 1)) * 2;   // -1..1, an even spread over the sphere
                    double rad = Math.sqrt(Math.max(0, 1 - y * y));
                    double theta = i * 2.399963;                   // golden angle
                    Location p = c.clone().add(Math.cos(theta) * rad * r, y * r, Math.sin(theta) * rad * r);
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, (i % 3 == 0) ? ASH : goldShell); // black crackle woven in
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Ember-spark debris flung outward — a clean, dark stand-in for firework sparks. */
    static void sparks(Location c, int count, double speed) {
        c.getWorld().spawnParticle(Particle.SMOKE, c, count, 0.1, 0.1, 0.1, speed);
    }

    private void lodgeInWorld() {
        state = State.LODGED_BLOCK;
        lodgedTicks = 0;
        world.playSound(pos, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.85f);
        explode(pos.clone(), 1.2);
    }

    /** Begin the recall (owner pressed F / bare left-click, or the abandon-timeout fired). */
    void beginRecall() {
        if (state == State.RETURNING) return;

        // Tearing free of a living body is the payoff — a heavy hit + a wide starburst.
        LivingEntity torn = embedded;
        clearEmbedded(); // stop amplifying before our own rip-out hit lands
        if (torn != null && !torn.isDead() && torn.isValid()) {
            Player owner = plugin.getServer().getPlayer(ownerId);
            Location at = center(torn).toLocation(world);
            torn.damage(DISLODGE_DAMAGE, owner);
            torn.setVelocity(torn.getVelocity().setY(0.35));
            explode(at, 2.4); // a big rip-out blast
            crackle(torn, 5);
            world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 0.7f);
            world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, 1.2f);
        }
        state = State.RETURNING;

        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner != null) owner.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.8f, 1.1f);
    }

    private void catchIt(Player owner) {
        // No burst on return — just the sound, so it never clutters the shooter's screen.
        owner.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_HIT_GROUND, 0.8f, 1.5f);
        owner.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.6f, 1.5f);
        cancel();
        weapon.finishReturn(owner, item);
    }

    private Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /** Crackle sparks + a resonant ping when a Gungnir-marked body is struck (the amplified hit). */
    static void vibrationHit(LivingEntity target) {
        World w = target.getWorld();
        Location c = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        sparks(c, 8, 0.12);
        w.spawnParticle(Particle.DUST, c, 6, 0.35, 0.5, 0.35, 0, FLASH);
        w.playSound(c, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f); // resonant vibration ping
    }

    /** The exact relic in flight — used by the weapon to hand it back on quit/shutdown. */
    ItemStack item() {
        return item;
    }

    /** A short debug description of where this spear is and what mode it's in (for /reliquary track). */
    String describe() {
        String st = switch (state) {
            case OUT -> bouncing ? "ricochet" : "bolt";
            case LODGED_BLOCK -> "lodged";
            case LODGED_ENTITY -> "buried in a mob";
            case RETURNING -> "returning";
        };
        return st + " @ " + world.getName() + " " + (int) pos.getX() + "," + (int) pos.getY() + "," + (int) pos.getZ();
    }

    // ---- launch presentation (static; called by the weapon) -----------------------

    /** The launch is deliberately quiet on particles — the show is saved for where the spear lands. */
    static void launchFlourish(Player p) {
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.95f);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.2f);
    }
}
