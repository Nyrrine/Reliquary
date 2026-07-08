package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Lævateinn's shared visual vocabulary. Every strike reads as one of a few shapes so the
 * blade never looks like the same swoop twice:
 *
 * <ul>
 *   <li>{@link #blunt} — a heavy <em>impact</em>: a shock ring, a core flash, a dust burst
 *       and a few twinkles. No arc. (Cleave, slam.)</li>
 *   <li>{@link #lineStrike} / {@link #afterimage} — straight <em>afterimage cuts</em>.</li>
 *   <li>{@link #heavyArc} — the big sealed sweep; {@link #trueFormSlash} — the True-Form show.</li>
 * </ul>
 *
 * Sealed combat is purple + white; True Form burns fully orange. All counts are single-digit
 * and animations are one capped runnable, so every effect stays O(active wielders).
 */
final class LaevateinnVfx {

    private LaevateinnVfx() {}

    // ---- palette -------------------------------------------------------------------
    // Sealed combat is purple + white; at True Form every effect burns fully orange.
    static final Color PURPLE      = Color.fromRGB(0x9A, 0x3C, 0xE0); // relic purple
    static final Color PURPLE_DEEP = Color.fromRGB(0x5E, 0x1E, 0x9E); // shadow purple
    static final Color WHITE       = Color.fromRGB(0xF3, 0xEC, 0xFF); // soft white edge
    static final Color ORANGE      = Color.fromRGB(0xFF, 0x7A, 0x18); // true-form heat
    static final Color ORANGE_HOT  = Color.fromRGB(0xFF, 0xE6, 0xB0); // white-hot edge
    static final Color ORANGE_DEEP = Color.fromRGB(0xC0, 0x38, 0x06); // deep ember

    /** The colour set an effect draws with — purple/white while sealed, all-orange at True Form. */
    record Pal(Color body, Color edge, Color deep) {}
    static final Pal PURPLE_PAL = new Pal(PURPLE, WHITE, PURPLE_DEEP);
    static final Pal ORANGE_PAL = new Pal(ORANGE, ORANGE_HOT, ORANGE_DEEP);

    static Pal pal(int form) { return form >= 3 ? ORANGE_PAL : PURPLE_PAL; }

    /** Small purple "flakes" — the diamond sparks that ride the true-form fire. */
    static final Particle.DustOptions PURPLE_FLAKE = new Particle.DustOptions(PURPLE, 0.9f);

    /**
     * The True-Form left-click — a big, flashy orange slash: an oversized arc with a white-hot edge,
     * a burning flame afterimage that lingers, purple diamond flakes riding it, and white sparks.
     * The show-stopper. One capped runnable.
     */
    static void trueFormSlash(Reliquary plugin, World world, Location center, Vector look) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector dir = look.clone().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();
        double roll = rng.nextDouble(0, Math.PI * 2);
        Vector u = dir.clone();
        Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

        double radius = 3.4 * (0.92 + rng.nextDouble() * 0.28); // bigger than the sealed slash
        double sweep = Math.toRadians(175 + rng.nextInt(60));
        double aMid = rng.nextDouble(-0.25, 0.25);
        boolean reverse = rng.nextBoolean();

        final int N = 48; // dense points so it reads as a clean slash, not a line of dots
        final int REVEAL = 4, FADE = 6;
        final Location[] pts = new Location[N + 1];
        final Vector[] outv = new Vector[N + 1];
        final int[] birth = new int[N + 1];
        for (int i = 0; i <= N; i++) {
            double a = aMid - sweep / 2.0 + sweep * ((double) i / N);
            Vector radial = u.clone().multiply(Math.cos(a) * radius).add(v.clone().multiply(Math.sin(a) * radius));
            pts[i] = center.clone().add(radial);
            outv[i] = radial.clone().normalize();
            int order = reverse ? (N - i) : i;
            birth[i] = Math.round((float) REVEAL * order / N);
        }
        final Particle.DustOptions body = new Particle.DustOptions(ORANGE, 1.7f);
        final Particle.DustOptions edge = new Particle.DustOptions(ORANGE_HOT, 1.5f);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > REVEAL + FADE) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= FADE) continue;
                    Location p = pts[i];
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, age == 0 ? edge : body);
                    if (age == 0) {
                        // Flame strikes bursting out of the cut — kept sparse so the slash stays clean.
                        Vector o = outv[i];
                        if (i % 2 == 0) world.spawnParticle(Particle.FLAME, p, 0,
                                o.getX() * 0.12, o.getY() * 0.12, o.getZ() * 0.12, 0.05);
                        if (i % 4 == 0) world.spawnParticle(Particle.SMALL_FLAME, p, 1, 0.05, 0.05, 0.05, 0.01);
                        // Purple diamond flakes riding the fire (the reference look).
                        if (i % 6 == 0) world.spawnParticle(Particle.DUST, p, 1, 0.06, 0.06, 0.06, 0, PURPLE_FLAKE);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- BLUNT: a heavy impact -----------------------------------------------------

    /** A heavy impact: a bright core flash, a debris burst, a shock ring, hard sparks. No slash crescent. */
    static void blunt(World world, Location center, double scale, Pal p) {
        // Core flash — a tight punch of light, not a crescent.
        world.spawnParticle(Particle.DUST, center.clone().add(0, 0.25, 0), 6,
                0.12 * scale, 0.12 * scale, 0.12 * scale, 0, new Particle.DustOptions(p.edge(), 1.1f));
        // Debris burst.
        world.spawnParticle(Particle.DUST, center.clone().add(0, 0.2, 0), 9,
                0.35 * scale, 0.22, 0.35 * scale, 0, new Particle.DustOptions(p.body(), 1.3f));
        // Ground shock ring.
        ring(world, center.clone().add(0, 0.12, 0), 0.9 * scale,
                new Particle.DustOptions(p.deep(), 1.5f), (int) Math.round(9 + 3 * scale));
        // Hard crit sparks for the "oomph."
        world.spawnParticle(Particle.CRIT, center.clone().add(0, 0.4, 0), (int) Math.round(6 * scale),
                0.28 * scale, 0.25, 0.28 * scale, 0.10);
        // At True Form the impact throws real fire.
        if (p == ORANGE_PAL) {
            world.spawnParticle(Particle.FLAME, center.clone().add(0, 0.3, 0), (int) Math.round(6 * scale),
                    0.3 * scale, 0.25, 0.3 * scale, 0.03);
        }
        twinkle(world, center.clone().add(0, 0.5, 0), (int) Math.round(3 * scale));
    }

    /**
     * A big, heavy sweep that wraps around the wielder — the sealed cleave. Unlike {@link #slash}
     * it's thick, slower and grittier so it reads as weight, not a fine cut. Give it a low centre
     * so it swings under the eyeline. {@code body}/{@code edge} colour it per form.
     */
    static void heavyArc(Reliquary plugin, World world, Location center, Vector look,
                         Color body, Color edge, double radius) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector dir = look.clone().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();
        double roll = rng.nextDouble(0, Math.PI * 2);
        Vector u = dir.clone();
        Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

        double sweep = Math.toRadians(200 + rng.nextInt(40)); // wraps wide across the view
        double aMid = rng.nextDouble(-0.2, 0.2);
        boolean reverse = rng.nextBoolean();

        final int N = 26;
        final int REVEAL = 4, FADE = 5; // a slower reveal reads heavier than the true-form slash
        final Location[] pts = new Location[N + 1];
        final int[] birth = new int[N + 1];
        for (int i = 0; i <= N; i++) {
            double a = aMid - sweep / 2.0 + sweep * ((double) i / N);
            Vector radial = u.clone().multiply(Math.cos(a) * radius).add(v.clone().multiply(Math.sin(a) * radius));
            Location p = center.clone().add(radial);
            p.add((rng.nextDouble() - 0.5) * 0.10, (rng.nextDouble() - 0.5) * 0.10, (rng.nextDouble() - 0.5) * 0.10);
            pts[i] = p;
            int order = reverse ? (N - i) : i;
            birth[i] = Math.round((float) REVEAL * order / N);
        }
        final Particle.DustOptions dBody = new Particle.DustOptions(body, 2.3f);
        final Particle.DustOptions dEdge = new Particle.DustOptions(edge, 2.0f);
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > REVEAL + FADE) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= FADE) continue;
                    float sz = 2.3f * (1.0f - 0.4f * age / FADE);
                    world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0,
                            age == 0 ? dEdge : new Particle.DustOptions(dBody.getColor(), sz));
                    if (age == 0 && i % 4 == 0) world.spawnParticle(Particle.CRIT, pts[i], 1, 0, 0, 0, 0.02);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * A Mace-style ground smash: the block underfoot's debris blasted outward and a crumbling
     * dome + shock rings. Purely visual — nothing is actually broken.
     */
    static void maceSmash(World world, Location center, double radius, Pal p) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BlockData ground = center.clone().add(0, -0.2, 0).getBlock().getBlockData();
        // Debris blasted outward (count 0 => the offset is a launch vector, extra is its speed).
        int n = (int) Math.round(24 + radius * 4);
        for (int i = 0; i < n; i++) {
            double a = (Math.PI * 2 * i) / n + rng.nextDouble(-0.12, 0.12);
            double vy = 0.5 + rng.nextDouble() * 0.6;
            double speed = 0.6 + rng.nextDouble() * 0.6;
            Location d = center.clone().add(Math.cos(a) * 0.6, 0.1, Math.sin(a) * 0.6);
            world.spawnParticle(Particle.BLOCK, d, 0, Math.cos(a), vy, Math.sin(a), speed, ground);
        }
        // Crumbling dome + a bright double shock ring.
        world.spawnParticle(Particle.DUST, center.clone().add(0, 0.3, 0), (int) Math.round(12 + radius * 3),
                radius * 0.45, 0.3, radius * 0.45, 0, new Particle.DustOptions(p.edge(), 1.6f));
        ring(world, center.clone().add(0, 0.12, 0), radius * 0.7,
                new Particle.DustOptions(p.deep(), 1.6f), (int) Math.round(12 + radius * 2));
        ring(world, center.clone().add(0, 0.12, 0), radius,
                new Particle.DustOptions(p.body(), 1.8f), (int) Math.round(16 + radius * 2));
        if (p == ORANGE_PAL) { // True Form: the smash erupts in fire
            world.spawnParticle(Particle.FLAME, center.clone().add(0, 0.3, 0), (int) Math.round(14 + radius * 4),
                    radius * 0.4, 0.3, radius * 0.4, 0.05);
            world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.3, 0), (int) Math.round(4 + radius),
                    radius * 0.4, 0.2, radius * 0.4, 0);
        }
        twinkle(world, center.clone().add(0, 0.6, 0), 6);
    }

    /**
     * A lingering burning-ground field — embers, smoke and the odd lava mote rising from the dirt for
     * a couple of seconds after a heavy hit. Purely visual (no world fire, no blocks), 2-tick cadence.
     */
    static void burningGround(Reliquary plugin, World world, Location center, double radius, int lifeTicks) {
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= lifeTicks) { cancel(); return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                int puffs = 4 + (int) radius;
                for (int i = 0; i < puffs; i++) {
                    double a = rng.nextDouble(0, Math.PI * 2);
                    double r = Math.sqrt(rng.nextDouble()) * radius;
                    Location p = center.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r);
                    world.spawnParticle(Particle.FLAME, p, 1, 0.05, 0.05, 0.05, 0.012);
                    if (rng.nextInt(3) == 0) world.spawnParticle(Particle.SMOKE, p, 1, 0.05, 0.12, 0.05, 0.02);
                    if (rng.nextInt(6) == 0) world.spawnParticle(Particle.LAVA, p, 1, 0.1, 0.05, 0.1, 0);
                    if (rng.nextInt(8) == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 1, 0.04, 0.06, 0.04, 0.01);
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** A straight afterimage strike — a bright line lancing through a point along {@code dir}. */
    static void lineStrike(World world, Location center, Vector dir, double length, Color body, Color edge) {
        Vector d = dir.clone();
        if (d.lengthSquared() < 1.0e-6) d = new Vector(1, 0, 0);
        d.normalize();
        Location start = center.clone().subtract(d.clone().multiply(length / 2.0));
        int steps = Math.max(6, (int) Math.round(length / 0.16));
        for (int i = 0; i <= steps; i++) {
            Location p = start.clone().add(d.clone().multiply(length * i / steps));
            boolean tip = i <= 1 || i >= steps - 1;
            world.spawnParticle(Particle.DUST, p, 1, 0.01, 0.01, 0.01, 0,
                    new Particle.DustOptions(tip ? edge : body, 1.1f));
        }
    }

    /**
     * A higher-fidelity afterimage slash — a bright edge-cored line with a deep glow offset for
     * thickness and hard sparks at both tips. Reads as a crisp, fast cut. One frame; spawn rapidly.
     */
    static void afterimage(World world, Location center, Vector dir, double length, Pal p) {
        Vector d = dir.clone();
        if (d.lengthSquared() < 1.0e-6) d = new Vector(1, 0, 0);
        d.normalize();
        Vector perp = d.clone().crossProduct(new Vector(0, 1, 0));
        if (perp.lengthSquared() < 1.0e-6) perp = new Vector(0, 1, 0);
        perp.normalize().multiply(0.09);

        Location start = center.clone().subtract(d.clone().multiply(length / 2.0));
        int steps = Math.max(8, (int) Math.round(length / 0.14));
        for (int i = 0; i <= steps; i++) {
            Location pt = start.clone().add(d.clone().multiply(length * i / steps));
            boolean tip = i <= 1 || i >= steps - 1;
            world.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(tip ? p.edge() : p.body(), tip ? 1.3f : 1.0f));
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, pt.clone().add(perp), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(p.deep(), 0.8f));
            }
        }
        world.spawnParticle(Particle.CRIT, center.clone().add(d.clone().multiply(length / 2.0)),
                2, 0.05, 0.05, 0.05, 0.1);
        world.spawnParticle(Particle.CRIT, center.clone().subtract(d.clone().multiply(length / 2.0)),
                2, 0.05, 0.05, 0.05, 0.1);
    }

    /** Scoreboard tag on our debris blocks so the placement-cancel listener can spot them. */
    static final String DEBRIS_TAG = "laev_debris";

    /**
     * Real falling-block debris kicked up from the ground with gravity — the mace "ground shake."
     * Purely visual: the blocks never place (a listener cancels it) and never hurt or drop.
     */
    static void debrisBurst(Reliquary plugin, World world, Location center, double radius, int count) {
        BlockData ground = center.clone().add(0, -0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isSolid()) return; // nothing solid underfoot to kick up
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double a = rng.nextDouble(0, Math.PI * 2);
            double r = rng.nextDouble() * radius * 0.6;
            Location spawn = center.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
            FallingBlock fb = world.spawnFallingBlock(spawn, ground);
            fb.setDropItem(false);
            fb.setHurtEntities(false);
            fb.setCancelDrop(true);
            fb.addScoreboardTag(DEBRIS_TAG);
            double up = 0.55 + rng.nextDouble() * 0.55;
            double out = 0.2 + rng.nextDouble() * 0.3;
            fb.setVelocity(new Vector(Math.cos(a) * out, up, Math.sin(a) * out));
            plugin.getServer().getScheduler().runTaskLater(plugin, fb::remove, 50L);
        }
    }

    // ---- shared helpers ------------------------------------------------------------

    /** A flat ring of dust around a centre. */
    static void ring(World world, Location center, double radius, Particle.DustOptions dust, int points) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
    }

    /** White END_ROD sparkles — the "twinkle" that reads as hot metal catching light. */
    static void twinkle(World world, Location center, int count) {
        if (count <= 0) return;
        world.spawnParticle(Particle.END_ROD, center, count, 0.25, 0.3, 0.25, 0.01);
    }

    /** A Bukkit {@link Color} as an Adventure {@link TextColor}, for matching UI accents. */
    static TextColor toText(Color c) {
        return TextColor.color(c.getRed(), c.getGreen(), c.getBlue());
    }
}
