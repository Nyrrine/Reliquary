package com.nyrrine.reliquary.ego;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A reusable, animated melee-swing crescent — the shared "actual attack animation" an E.G.O weapon's M1 (or
 * any single sweep) paints so it reads as a slash, not a dot cloud.
 *
 * <p>What it draws: an arc that <b>sweeps</b> across the swing plane over a few ticks rather than appearing
 * all at once. A short bright band rides the leading edge (a core sweep + a hard {@code SWEEP_ATTACK}/crit
 * edge + a few sparks flung off it) and fades out behind it as the sweep advances, so the eye reads a blade
 * travelling through the arc. Purely cosmetic: it deals no damage, reads no game state, and reaps any
 * {@code ItemDisplay} blade it spawns when it finishes.
 *
 * <p>Fire it through the fluent builder:
 * <pre>{@code
 * SlashVfx.slash(plugin, player.getEyeLocation(), player.getEyeLocation().getDirection())
 *         .arcSpan(150)                                   // fan width in degrees
 *         .reach(3.2)                                     // how far the edge is flung
 *         .colours(Color.fromRGB(0xE7, 0x2A, 0x2A),       // trail colour
 *                  Color.fromRGB(0xFF, 0xF2, 0xC0))       // leading-edge colour
 *         .thickness(1.2f)
 *         .duration(4)                                    // ticks the sweep takes
 *         .tilt(30)                                       // roll the swing plane for a diagonal slash
 *         .blade(Material.NETHERITE_SWORD)                // optional: an item swept along the arc
 *         .play();
 * }</pre>
 *
 * <p>The single-argument convenience {@link #play(Plugin, Location, Vector, double, double, Color)} covers the
 * common case with sensible defaults.
 */
public final class SlashVfx {

    private SlashVfx() {}

    // ---- builder entry ------------------------------------------------------------

    /**
     * Begin a slash from {@code origin} swung along {@code aim} (the look/attack direction). Chain the setters
     * and finish with {@link Builder#play()}.
     */
    public static Builder slash(Plugin plugin, Location origin, Vector aim) {
        return new Builder(plugin, origin, aim);
    }

    /** Convenience: a default crescent (120 deg, single colour, 4-tick sweep) with no item blade. */
    public static void play(Plugin plugin, Location origin, Vector aim, double arcSpanDeg, double reach, Color colour) {
        slash(plugin, origin, aim).arcSpan(arcSpanDeg).reach(reach).colours(colour, colour).play();
    }

    // ---- builder ------------------------------------------------------------------

    public static final class Builder {
        private final Plugin plugin;
        private final Location origin;
        private final Vector aim;

        private double arcSpanDeg = 120.0;
        private double reach      = 3.0;
        private Color  trail      = Color.WHITE;
        private Color  edge       = Color.WHITE;
        private float  thickness  = 1.1f;
        private int    duration   = 4;      // ticks the sweep takes to cross the whole arc
        private double tiltDeg    = 0.0;    // roll of the swing plane about the aim (0 = horizontal sweep)
        private boolean sparks    = true;
        private Material bladeItem = null;  // null = particles only
        private double bladeScale = 1.0;

        private Builder(Plugin plugin, Location origin, Vector aim) {
            this.plugin = plugin;
            this.origin = origin.clone();
            this.aim = aim.clone();
        }

        /** Fan width in degrees (the total angle the sweep covers). */
        public Builder arcSpan(double degrees) { this.arcSpanDeg = degrees; return this; }
        /** How far from the origin the crescent's outer edge is flung, in blocks. */
        public Builder reach(double blocks) { this.reach = blocks; return this; }
        /** The trailing colour (behind the edge) and the leading-edge colour; the sweep gradients between them. */
        public Builder colours(Color trail, Color edge) { this.trail = trail; this.edge = edge; return this; }
        /** Dust size of the core sweep. */
        public Builder thickness(float size) { this.thickness = size; return this; }
        /** How many ticks the leading edge takes to cross the whole arc (the sweep speed). */
        public Builder duration(int ticks) { this.duration = Math.max(1, ticks); return this; }
        /** Roll the swing plane about the aim: 0 sweeps level, positive tilts toward a downward-diagonal slash. */
        public Builder tilt(double degrees) { this.tiltDeg = degrees; return this; }
        /** Whether to fling a few sparks off the leading edge. */
        public Builder sparks(boolean on) { this.sparks = on; return this; }
        /** Sweep an item blade (an {@link ItemDisplay}) along the arc as well as the particles. Null for none. */
        public Builder blade(Material item) { this.bladeItem = item; return this; }
        /** Scale of the optional item blade. */
        public Builder bladeScale(double scale) { this.bladeScale = scale; return this; }

        /** Compute the swing frame, spawn any blade, and schedule the sweep. */
        public void play() {
            World world = origin.getWorld();
            if (world == null) return;

            Vector forward = aim.lengthSquared() < 1.0e-6 ? new Vector(1, 0, 0) : aim.clone().normalize();
            // A stable frame around the aim: 'right' across it, 'up' the swing-plane normal we rotate around.
            Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
            if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0); // looking straight up/down
            right.normalize();
            Vector up = right.clone().crossProduct(forward).normalize();
            // The arc rotates about this axis; tilt rolls it about the aim for a diagonal swing.
            Vector axis = up.clone().rotateAroundAxis(forward, Math.toRadians(tiltDeg)).normalize();

            ItemDisplay blade = bladeItem == null ? null : spawnBlade(world);
            new Sweep(world, forward, axis, blade).runTaskTimer(plugin, 0L, 1L);
        }

        private ItemDisplay spawnBlade(World world) {
            ItemStack item = new ItemStack(bladeItem);
            return world.spawn(origin, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);     // a crash can never leave this render-only blade on disk
                d.setTeleportDuration(1);
                d.setInterpolationDuration(1);
            });
        }

        // ---- the per-tick sweep ---------------------------------------------------

        private final class Sweep extends BukkitRunnable {
            private final World world;
            private final Vector forward;
            private final Vector axis;
            private final ItemDisplay blade;
            private int tick = 0;

            Sweep(World world, Vector forward, Vector axis, ItemDisplay blade) {
                this.world = world;
                this.forward = forward;
                this.axis = axis;
                this.blade = blade;
            }

            @Override
            public void run() {
                if (tick > duration) {
                    if (blade != null && blade.isValid()) blade.remove();
                    cancel();
                    return;
                }
                double half = Math.toRadians(arcSpanDeg) * 0.5;
                double progress = (double) tick / duration;             // 0 -> 1 across the sweep
                double lead = -half + (2 * half) * progress;            // the leading edge advances across the arc
                double band = Math.min(2 * half, Math.max(Math.toRadians(18.0), half)); // trailing band length

                renderBand(half, lead, band);
                renderLeadingEdge(lead);
                if (blade != null) driveBlade(lead);
                tick++;
            }

            /** The core crescent: a bright band riding the lead that fades toward its tail. */
            private void renderBand(double half, double lead, double band) {
                double tail = Math.max(-half, lead - band);
                int samples = Math.max(6, (int) Math.round((lead - tail) / Math.toRadians(4.0)));
                for (int i = 0; i <= samples; i++) {
                    double a = tail + (lead - tail) * (i / (double) samples);
                    double t = (lead - tail) < 1.0e-6 ? 1.0 : (a - tail) / (lead - tail); // 0 tail -> 1 lead
                    if (t < 0.25 && (i & 1) == 0) continue; // thin the far trail so it reads as fading

                    Vector dir = forward.clone().rotateAroundAxis(axis, a);
                    Color colour = lerp(trail, edge, t);
                    Particle.DustOptions dust = new Particle.DustOptions(colour, thickness * (0.7f + 0.5f * (float) t));

                    world.spawnParticle(Particle.DUST, at(dir, reach), 1, 0.0, 0.0, 0.0, 0.0, dust);
                    // A little radial width near the leading portion so the blade reads as a crescent, not a wire.
                    if (t > 0.55) {
                        world.spawnParticle(Particle.DUST, at(dir, reach * 0.82), 1, 0.0, 0.0, 0.0, 0.0, dust);
                    }
                }
            }

            /** The hard leading edge: a sweep-attack flash, a crit sparkle, and a couple of edge-colour motes. */
            private void renderLeadingEdge(double lead) {
                Vector dir = forward.clone().rotateAroundAxis(axis, lead);
                Location p = at(dir, reach);
                world.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0.0, 0.0, 0.0, 0.0);
                world.spawnParticle(Particle.CRIT, p, 3, 0.06, 0.06, 0.06, 0.12);
                world.spawnParticle(Particle.DUST, p, 2, 0.05, 0.05, 0.05, 0.0,
                        new Particle.DustOptions(edge, thickness * 1.2f));
                if (sparks) {
                    // Fling a couple of sparks tangent to the sweep, ahead of the edge.
                    Vector tangent = forward.clone().rotateAroundAxis(axis, lead + Math.toRadians(90));
                    world.spawnParticle(Particle.DUST, p, 2, 0.0, 0.0, 0.0, 0.0,
                            new Particle.DustOptions(edge, thickness));
                    p.getWorld().spawnParticle(Particle.CRIT, p.clone().add(tangent.multiply(0.25)),
                            1, 0.02, 0.02, 0.02, 0.08);
                }
            }

            /** Sweep the optional item blade to the leading edge, laid along the arc's tangent. */
            private void driveBlade(double lead) {
                if (!blade.isValid()) return;
                Vector dir = forward.clone().rotateAroundAxis(axis, lead);
                Vector offset = dir.clone().multiply(reach * 0.9);
                Vector tangent = forward.clone().rotateAroundAxis(axis, lead + Math.toRadians(90)).normalize();
                Quaternionf rot = new Quaternionf().rotationTo(
                        new Vector3f(0, 0, 1), new Vector3f((float) tangent.getX(), (float) tangent.getY(), (float) tangent.getZ()));
                float s = (float) bladeScale;
                blade.setTransformation(new Transformation(
                        new Vector3f((float) offset.getX(), (float) offset.getY(), (float) offset.getZ()),
                        rot,
                        new Vector3f(s, s, s),
                        new Quaternionf()));
            }

            private Location at(Vector dir, double r) {
                return origin.clone().add(dir.clone().multiply(r));
            }
        }
    }

    // ---- colour ------------------------------------------------------------------

    /** Linear blend from {@code a} (t=0) to {@code b} (t=1). */
    private static Color lerp(Color a, Color b, double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * c);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * c);
        int bl = (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * c);
        return Color.fromRGB(r, g, bl);
    }
}
