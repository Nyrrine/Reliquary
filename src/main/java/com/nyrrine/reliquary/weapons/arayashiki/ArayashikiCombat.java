package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The physical act of slashing with Arayashiki.
 *
 * Each swing is one big sword swoop: a large arc centred on the wielder that
 * sweeps AROUND them, animated so the blade travels through its arc over a few
 * ticks — glowing leading tip, trailing white fade. Orientation, width and size
 * are randomized every swing so no two swoops are alike.
 */
public final class ArayashikiCombat {

    private final Reliquary plugin;
    private final ArayashikiWeapon weapon;

    // Tuning.
    private static final double BASE_RADIUS = 3.0;   // how far the swoop reaches out
    private static final double HIT_RANGE = 3.4;     // damage radius
    private static final double HIT_CONE_DEG = 120.0;// generous, since the arc wraps around
    private static final double DAMAGE = 7.0;

    // Swoop animation timing.
    private static final int REVEAL_TICKS = 5;       // ticks for the blade to travel the arc
    private static final int FADE_TICKS = 4;         // ticks each segment lingers after being cut

    public ArayashikiCombat(Reliquary plugin, ArayashikiWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    public void onSwing(Player player) {
        UUID id = player.getUniqueId();

        // No use-time left -> the blade is erased and can't cut until it regenerates.
        if (!weapon.hasCharge(id)) {
            fizzle(player);
            return;
        }

        swoop(player);
    }

    /** A hollow, powerless swing when the blade has been fully erased. */
    private void fizzle(Player player) {
        Location eye = player.getEyeLocation();
        player.getWorld().spawnParticle(Particle.DUST, eye.add(eye.getDirection().multiply(0.8)),
                6, 0.15, 0.15, 0.15, 0,
                new Particle.DustOptions(Color.fromRGB(90, 90, 90), 0.8f));
        player.getWorld().playSound(player.getLocation(),
                Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.5f, 0.6f);
    }

    /** One big randomized sword swoop that arcs around the player. */
    private void swoop(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location eye = player.getEyeLocation();
        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1.0, 0); // swoop pivots on the body
        Vector dir = eye.getDirection().normalize();

        // Base frame around the look direction.
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();

        // The swoop lives in the plane spanned by u (forward) and v (a randomly
        // rolled perpendicular) -> overhead, horizontal or any diagonal swing.
        double roll = rng.nextDouble(0, Math.PI * 2);
        Vector u = dir.clone();
        Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

        double radius = BASE_RADIUS * (0.9 + rng.nextDouble() * 0.5);     // big: ~2.7 - 4.2
        double sweep = Math.toRadians(170 + rng.nextInt(90));             // 170-260 deg wrap
        double aMid = rng.nextDouble(-0.35, 0.35);                        // slight aim offset
        boolean reverse = rng.nextBoolean();                             // swing L->R or R->L
        float thickness = 1.1f + rng.nextFloat() * 0.5f;

        final int N = 46;
        final Location[] pts = new Location[N + 1];
        final int[] birth = new int[N + 1];
        for (int i = 0; i <= N; i++) {
            double f = (double) i / N;                    // 0..1 along the cut
            double a = aMid - sweep / 2.0 + sweep * f;    // angle around the pivot
            Vector radial = u.clone().multiply(Math.cos(a) * radius)
                    .add(v.clone().multiply(Math.sin(a) * radius));
            Location p = center.clone().add(radial);
            p.add((rng.nextDouble() - 0.5) * 0.08,
                  (rng.nextDouble() - 0.5) * 0.08,
                  (rng.nextDouble() - 0.5) * 0.08);
            pts[i] = p;
            int order = reverse ? (N - i) : i;
            birth[i] = Math.round((float) REVEAL_TICKS * order / N);
        }

        animateSwoop(world, pts, birth, thickness);

        // Sound — pitch jitter keeps the rhythm alive.
        float pitch = 1.0f + rng.nextFloat() * 0.4f;
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, pitch);
        world.playSound(eye, Sound.ITEM_TRIDENT_RETURN, 0.55f, 1.4f + rng.nextFloat() * 0.3f);

        applyConeDamage(player, eye, dir);
    }

    /** Reveals the arc progressively (the blade travelling) with a trailing fade. */
    private void animateSwoop(World world, Location[] pts, int[] birth, float thickness) {
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > REVEAL_TICKS + FADE_TICKS) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= FADE_TICKS) continue;
                    float sz = thickness * (1.0f - 0.55f * age / FADE_TICKS);
                    world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.WHITE, sz));
                    if (age == 0) { // the glowing leading tip of the blade
                        world.spawnParticle(Particle.END_ROD, pts[i], 1, 0, 0, 0, 0);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Wide forward hit (the arc wraps, so the cone is generous) + knockback. */
    private void applyConeDamage(Player player, Location eye, Vector dir) {
        double cosLimit = Math.cos(Math.toRadians(HIT_CONE_DEG));
        for (var entity : player.getNearbyEntities(HIT_RANGE, HIT_RANGE, HIT_RANGE)) {
            if (entity == player || !(entity instanceof LivingEntity target)) continue;

            Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0e-6) continue;
            if (to.clone().normalize().dot(dir) < cosLimit) continue;

            weapon.markErased(target, player);
            target.damage(DAMAGE, player);
            Vector kb = dir.clone().setY(0).normalize().multiply(0.55).setY(0.28);
            target.setVelocity(target.getVelocity().add(kb));
            target.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    target.getLocation().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0);
        }
    }
}
