package com.nyrrine.reliquary.ego.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Coke-and-Mentos fountain for Soda's fizz shot — a short, self-cancelling particle simulation.
 *
 * <p>On fire we seed a few dozen purple soda droplets at the muzzle, each launched in a fat forward +
 * strongly-upward cone. Every tick the droplets advance under a little gravity and drag, drawn as tinted
 * dust so the eruption arcs and rains back down like a shaken can venting. A curl of foam, bubbles and
 * splash rides the base for the first few frames. Everything tears itself down after {@value #FRAMES}
 * ticks — no lingering task, no world edits.
 */
final class SodaEruption extends BukkitRunnable {

    private static final int FRAMES = 16;      // ~0.8s of fountain
    private static final int DROPS  = 54;      // droplets in the burst
    private static final double GRAVITY = 0.055;
    private static final double DRAG    = 0.965;

    // Grape-soda palette — deep grape, bright grape, carbonated blue, and pale foam.
    private static final Particle.DustOptions[] PALETTE = {
        new Particle.DustOptions(Color.fromRGB(0x9B, 0x6B, 0xE8), 1.1f), // grape purple
        new Particle.DustOptions(Color.fromRGB(0xC9, 0xA6, 0xFF), 0.9f), // light grape
        new Particle.DustOptions(Color.fromRGB(0x7A, 0x45, 0xC8), 1.2f), // deep grape
        new Particle.DustOptions(Color.fromRGB(0x5F, 0xB8, 0xFF), 0.8f), // carbonated blue
        new Particle.DustOptions(Color.fromRGB(0xF0, 0xEA, 0xFF), 0.7f), // pale foam highlight
    };

    private final World world;
    private final Location origin;
    /** Each droplet is {@code [x, y, z, vx, vy, vz, paletteIndex]}. */
    private final List<double[]> drops;
    private final Plugin plugin;
    private int frame = 0;

    SodaEruption(Plugin plugin, World world, Location muzzle, Vector dir) {
        this.plugin = plugin;
        this.world = world;
        this.origin = muzzle.clone();
        this.drops = new ArrayList<>(DROPS);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector forward = dir.clone().normalize();
        for (int i = 0; i < DROPS; i++) {
            // Forward push + a strong upward fountain bias + a wide random scatter.
            Vector v = forward.clone().multiply(0.30 + rng.nextDouble() * 0.55);
            v.setY(v.getY() + 0.32 + rng.nextDouble() * 0.42);
            v.add(new Vector(rng.nextDouble(-0.26, 0.26),
                             rng.nextDouble(-0.10, 0.28),
                             rng.nextDouble(-0.26, 0.26)));
            drops.add(new double[]{
                origin.getX(), origin.getY(), origin.getZ(),
                v.getX(), v.getY(), v.getZ(),
                rng.nextInt(PALETTE.length)
            });
        }
    }

    /** Kick off the fountain — a 1-tick timer that cancels itself after {@link #FRAMES}. */
    void start() {
        runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // A curl of foam, bubbles and gush riding the base for the first few frames — the "vent".
        if (frame < 5) {
            world.spawnParticle(Particle.SPLASH, origin, 8, 0.18, 0.14, 0.18, 0.06);
            world.spawnParticle(Particle.BUBBLE_POP, origin, 6, 0.16, 0.12, 0.16, 0.02);
            world.spawnParticle(Particle.DUST, origin, 5, 0.16, 0.16, 0.16, 0, PALETTE[4]);
        }

        for (double[] d : drops) {
            // Integrate: advance, apply drag + gravity for the next step.
            d[0] += d[3]; d[1] += d[4]; d[2] += d[5];
            d[3] *= DRAG;  d[4] = d[4] * DRAG - GRAVITY;  d[5] *= DRAG;

            Particle.DustOptions dust = PALETTE[(int) d[6]];
            world.spawnParticle(Particle.DUST, d[0], d[1], d[2], 1, 0, 0, 0, 0, dust);

            // A sparse trail of carbonation riding along with the droplets.
            if (rng.nextInt(6) == 0) {
                world.spawnParticle(Particle.BUBBLE_POP, d[0], d[1], d[2], 1, 0.02, 0.02, 0.02, 0.0);
            }
        }

        if (++frame >= FRAMES) cancel();
    }
}
