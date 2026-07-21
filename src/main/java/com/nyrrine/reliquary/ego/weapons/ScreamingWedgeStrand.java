package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A single strand-of-hair bolt fired by the {@link ScreamingWedgeWeapon}, in flight.
 *
 * <p>A slow, black tress of the Lady's hair loosed into the air. It creeps forward and, if an enemy
 * strays roughly ahead of it, curls gently onto them — a subtle auto-aim, never a hard lock. The
 * strand sub-steps its own trajectory each tick (so it can't tunnel through a wall or a body between
 * ticks), trailing a black hair-strand smear and hissing like air being split.
 *
 * <ul>
 *   <li>On a living body it lands a small bite of damage; roughly a third of the time the hair
 *       {@linkplain #ROOT_CHANCE tangles} the victim into a near-root — a strong Slowness for a
 *       couple of seconds.</li>
 *   <li>Meeting a wall, or finding no one after several seconds aloft, it simply
 *       {@linkplain #dissipate dissipates} — a soft fizzle of dark smoke, no impact.</li>
 * </ul>
 *
 * <p>Holds no shared state; it is spawned per shot and cancels itself the moment it resolves.
 */
final class ScreamingWedgeStrand extends BukkitRunnable {

    // Flight tuning. Speeds are in blocks/tick; the loop sub-steps them by STEP so nothing tunnels.
    private static final double SPEED      = 0.55;  // deliberately slow — a creeping tress
    private static final double STEP       = 0.275;
    private static final double MAX_RANGE  = 40.0;
    private static final int    MAX_TICKS  = 70;    // ~3.5s aloft, then it dissipates if it found no one
    private static final double HIT_RADIUS = 1.1;   // how close the tip must pass to catch a body

    // Subtle auto-aim: curl toward a body that sits roughly ahead of the tip, gently.
    private static final double AIM_RADIUS    = 6.0;
    private static final double AIM_AHEAD_DOT = 0.35; // within ~70° of the current heading counts as "in front"
    private static final double AIM_STRENGTH  = 0.12; // small per-tick lerp — a nudge, not a lock

    // On-hit payload.
    private static final double ROOT_CHANCE    = 0.30; // chance the hair tangles the victim
    private static final int    ROOT_TICKS     = 45;   // ~2.25s — a couple of seconds
    private static final int    ROOT_AMPLIFIER = 5;    // Slowness VI — a near-root drag

    // Palette — the Lady's black hair, with a bruised violet-black undertone.
    private static final Color HAIR_BLACK  = Color.fromRGB(0x0B, 0x0B, 0x0D);
    private static final Color HAIR_VIOLET = Color.fromRGB(0x1E, 0x16, 0x24);
    private static final Particle.DustOptions STRAND = new Particle.DustOptions(HAIR_BLACK, 1.0f);
    private static final Particle.DustOptions WISP   = new Particle.DustOptions(HAIR_VIOLET, 0.8f);

    private final Reliquary plugin;
    private final UUID ownerId;
    private final World world;
    private final double damage;
    private final double reachMult;  // Long Hair: scales travel range and acquisition radius — reach only, never damage
    private final double tangleMult; // Tangle: scales the on-hit root duration — crowd control only, never damage

    private final Location pos;   // the strand tip
    private Vector dir;           // current heading (unit)
    private double traveled = 0.0;
    private int flightTicks = 0;
    private int weaveStep = 0;    // drives the thin waver of the trail

    ScreamingWedgeStrand(Reliquary plugin, Player owner, double damage, double reachMult, double tangleMult) {
        this.plugin = plugin;
        this.ownerId = owner.getUniqueId();
        this.world = owner.getWorld();
        this.damage = damage;
        this.reachMult = reachMult;
        this.tangleMult = tangleMult;
        this.pos = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
        this.dir = owner.getEyeLocation().getDirection().normalize();
    }

    /** Launch the strand into the world on the per-tick scheduler. */
    void launch() {
        runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) { cancel(); return; }

        if (++flightTicks > MAX_TICKS) { dissipate(); return; } // found no one — fade away

        steerTowardMark(); // subtle homing onto whatever's roughly ahead

        double moved = 0.0;
        while (moved < SPEED) {
            double step = Math.min(STEP, SPEED - moved);

            // A wall ahead stops it — no impact, just a fizzle.
            Location next = pos.clone().add(dir.clone().multiply(step));
            if (next.getBlock().getType().isSolid()) { dissipate(); return; }

            pos.add(dir.clone().multiply(step));
            drawStrand();

            // First body in reach? Bite it and stop.
            LivingEntity hit = firstHit();
            if (hit != null) { strike(owner, hit); return; }

            traveled += step;
            moved += step;
            if (traveled >= MAX_RANGE * reachMult) { dissipate(); return; }
        }

        // A recurring hiss of air being split — sparse so it doesn't drone.
        if (flightTicks % 6 == 0) {
            world.playSound(pos, Sound.ITEM_TRIDENT_THROW, 0.35f, 1.9f);
        }
    }

    /** If a living body sits roughly ahead within reach, curl the heading gently onto it. */
    private void steerTowardMark() {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        double aim = AIM_RADIUS * reachMult;
        for (Entity e : world.getNearbyEntities(pos, aim, aim, aim)) {
            if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
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

    /** The nearest living body (not the owner) within the tip's catch radius, else null. */
    private LivingEntity firstHit() {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(pos, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
            if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            double d = center(le).subtract(pos.toVector()).lengthSquared();
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    /** Bite a body: small damage, then a chance the hair tangles it into a near-root. */
    private void strike(Player owner, LivingEntity victim) {
        victim.damage(damage, owner);

        if (ThreadLocalRandom.current().nextDouble() < ROOT_CHANCE) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) (ROOT_TICKS * tangleMult), ROOT_AMPLIFIER, false, true, true));
            tangleFx(victim);
            // A clear bell ding to the wielder — the tangle landed, the root is implanted.
            owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f);
        } else {
            impactFx(victim);
        }
        cancel();
    }

    // ---- presentation --------------------------------------------------------------

    /** A black hair-strand smear along the flight line, with a thin violet-black waver. */
    private void drawStrand() {
        world.spawnParticle(Particle.DUST, pos, 1, 0.0, 0.0, 0.0, 0.0, STRAND);
        Vector[] b = perp(dir);
        double ang = weaveStep * 0.9;
        Location wisp = pos.clone()
                .add(b[0].clone().multiply(Math.cos(ang) * 0.12))
                .add(b[1].clone().multiply(Math.sin(ang) * 0.12));
        world.spawnParticle(Particle.DUST, wisp, 1, 0.0, 0.0, 0.0, 0.0, WISP);
        if (weaveStep % 5 == 0) world.spawnParticle(Particle.SMOKE, pos, 1, 0.02, 0.02, 0.02, 0.0);
        weaveStep++;
    }

    /** A plain bite — a small burst of black hair scattering off the struck body. */
    private void impactFx(LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST, c, 8, 0.25, 0.35, 0.25, 0.0, STRAND);
        world.spawnParticle(Particle.SMOKE, c, 4, 0.2, 0.3, 0.2, 0.01);
        world.playSound(c, Sound.ENTITY_PHANTOM_BITE, 0.6f, 0.7f);
    }

    /** The tangle proc — the hair winds around the victim in a knot of black strands under a low wail. */
    private void tangleFx(LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST, c, 16, 0.35, 0.5, 0.35, 0.0, STRAND);
        world.spawnParticle(Particle.DUST, c, 8, 0.4, 0.55, 0.4, 0.0, WISP);
        world.spawnParticle(Particle.SMOKE, c, 6, 0.3, 0.4, 0.3, 0.01);
        world.playSound(c, Sound.ENTITY_ENDERMAN_SCREAM, 0.2f, 0.5f);
        world.playSound(c, Sound.BLOCK_SCULK_VEIN_PLACE, 0.5f, 0.6f);
    }

    /** No target found (wall or timeout): a soft fizzle of dark smoke and a quiet hiss. */
    private void dissipate() {
        world.spawnParticle(Particle.SMOKE, pos, 6, 0.15, 0.15, 0.15, 0.01);
        world.spawnParticle(Particle.DUST, pos, 4, 0.15, 0.15, 0.15, 0.0, WISP);
        world.playSound(pos, Sound.BLOCK_FIRE_EXTINGUISH, 0.3f, 1.4f);
        cancel();
    }

    // ---- geometry helpers ----------------------------------------------------------

    private Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /** Two unit vectors spanning the plane perpendicular to u. */
    private Vector[] perp(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector a = n.clone().crossProduct(ref).normalize();
        Vector b = n.clone().crossProduct(a).normalize();
        return new Vector[]{a, b};
    }
}
