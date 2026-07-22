package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The idle animation for a placed Carmen's Brain: a floating brain-skinned {@link ItemDisplay} that bobs and
 * slowly rotates over a thin spine, with a symmetric nervous system fanning out in green particle traces and
 * periodic electric pulses running down the branches — all from vanilla materials, no tank.
 *
 * <p>One manager drives every placed Brain. Each tick it walks the placed Brains ({@link Stations#wells()})
 * and, for each in a loaded chunk with a player within {@link #WATCH_RANGE}, spawns (once) and animates its
 * display set; Brains that are unwatched or in an unloaded chunk are despawned, so the show costs nothing when
 * nobody is looking and survives restarts (it re-grows lazily). Every display is {@code reliquary}-tagged and
 * non-persistent; break/unregister/disable reap them, backed by a tag sweep.
 *
 * <p>The gacha pull animation is a later pass; each {@link Node} carries an {@code interrupted} flag so a
 * future carousel can suspend the idle and hand back to it — the seam is here now.
 */
public final class CarmenBrainVfx {

    /** Scoreboard tag on every display this show spawns — the belt-and-braces orphan-reap key. */
    public static final String TAG = "reliquary_carmen_brain";

    private static final double WATCH_RANGE   = 16.0;   // a player within this (blocks) keeps a Brain animated
    private static final long   PERIOD        = 2L;     // ticks between animation frames
    private static final double HOVER         = 1.35;   // brain height above the block's base
    private static final float  BRAIN_SCALE   = 1.0f;
    private static final double BOB_AMP       = 0.12;   // bob amplitude (blocks)
    private static final double BOB_RATE      = 0.12;   // radians of bob phase per frame
    private static final double YAW_RATE      = 0.05;   // radians of yaw per frame
    private static final int    PULSE_PERIOD  = 40;     // frames between neural pulses (each runs down one branch)
    private static final int    PULSE_LENGTH  = 12;     // frames a pulse takes to run root-ward
    private static final int    BRANCHES      = 6;      // symmetric nerve branches fanning off the spine

    private static final Color GREEN      = Color.fromRGB(0x5B, 0xE8, 0x7A);
    private static final Color GREEN_SOFT = Color.fromRGB(0x8F, 0xF0, 0xA8);
    private static final Particle.DustOptions NERVE_DUST   = new Particle.DustOptions(GREEN, 0.7f);
    private static final Particle.DustOptions PULSE_DUST   = new Particle.DustOptions(GREEN_SOFT, 1.2f);
    private static final Particle.DustOptions AMBIENT_DUST = new Particle.DustOptions(GREEN_SOFT, 0.6f);

    private static final BlockData SPINE_BLOCK = Material.BONE_BLOCK.createBlockData();
    private static final BlockData NERVE_BLOCK = Material.GLOW_LICHEN.createBlockData();

    private final Reliquary plugin;
    private final Stations stations;
    private final Map<String, Node> live = new HashMap<>(); // keyed by block-location string
    private BukkitRunnable task;

    public CarmenBrainVfx(Reliquary plugin, Stations stations) {
        this.plugin = plugin;
        this.stations = stations;
    }

    /** Start the single idle-driver task. */
    public void start() {
        task = new BukkitRunnable() {
            @Override public void run() { tick(); }
        };
        task.runTaskTimer(plugin, 20L, PERIOD);
    }

    private void tick() {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Location well : stations.wells()) {
            World w = well.getWorld();
            if (w == null || !w.isChunkLoaded(well.getBlockX() >> 4, well.getBlockZ() >> 4)) continue;
            if (!playerNear(well)) continue;
            String k = key(well);
            wanted.add(k);
            Node node = live.get(k);
            if (node == null || !node.valid()) { node = new Node(well); live.put(k, node); }
            node.animate();
        }
        // Despawn any live show that is no longer wanted (unwatched, unloaded, or removed).
        live.entrySet().removeIf(e -> {
            if (wanted.contains(e.getKey())) return false;
            e.getValue().reap();
            return true;
        });
    }

    private boolean playerNear(Location well) {
        World w = well.getWorld();
        Location c = well.clone().add(0.5, 0.5, 0.5);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    /** Reap the show at a specific Brain immediately (called on break/unregister). */
    public void onRemoved(Location well) {
        Node node = live.remove(key(well));
        if (node != null) node.reap();
    }

    /** Stop the driver, reap every live show, and sweep any tagged orphan display across worlds. */
    public void disable() {
        if (task != null) { task.cancel(); task = null; }
        for (Node n : live.values()) n.reap();
        live.clear();
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(BlockDisplay.class)) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
        }
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    // ---- one Brain's live show ----------------------------------------------------

    private final class Node {
        private final World world;
        private final Location centre;   // block centre
        private final Location brainAt;  // hover centre the brain floats around
        private final ItemDisplay brain;
        private final BlockDisplay spine;
        private final List<BlockDisplay> nerves = new ArrayList<>();
        private int frame = 0;
        boolean interrupted = false;     // the seam a future gacha pull sets to suspend the idle

        Node(Location well) {
            this.world = well.getWorld();
            this.centre = well.clone().add(0.5, 0.5, 0.5);
            this.brainAt = centre.clone().add(0, HOVER, 0);

            this.brain = world.spawn(brainAt, ItemDisplay.class, d -> {
                d.setItemStack(StationType.brainHead());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED); // true 3D — never billboarded flat
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowColorOverride(GREEN);
                d.setGlowing(true);
                d.setInterpolationDuration((int) PERIOD);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            });

            // A thin pale spine hanging from just under the brain down toward the block.
            float sw = 0.16f, sh = (float) HOVER * 0.8f;
            Location spineAt = centre.clone().add(0.5, 0.35, 0.5);
            this.spine = world.spawn(spineAt, BlockDisplay.class, d -> {
                d.setBlock(SPINE_BLOCK);
                d.setTransformation(new Transformation(
                        new Vector3f(-sw / 2f, 0f, -sw / 2f), new Quaternionf(),
                        new Vector3f(sw, sh, sw), new Quaternionf()));
                d.setBrightness(new Display.Brightness(8, 12));
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            });

            // A couple of glow-lichen nerve accents clinging at the spine base (a little 3D against the traces).
            for (int i = 0; i < 2; i++) {
                double a = Math.PI * i;
                Location at = centre.clone().add(0.5 + Math.cos(a) * 0.3, 0.5, 0.5 + Math.sin(a) * 0.3);
                float ns = 0.5f;
                BlockDisplay nerve = world.spawn(at, BlockDisplay.class, d -> {
                    d.setBlock(NERVE_BLOCK);
                    d.setTransformation(new Transformation(
                            new Vector3f(-ns / 2f, -ns / 2f, -ns / 2f), new Quaternionf(),
                            new Vector3f(ns, ns, ns), new Quaternionf()));
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setPersistent(false);
                    d.addScoreboardTag(TAG);
                });
                nerves.add(nerve);
            }
        }

        boolean valid() {
            return brain.isValid() && spine.isValid();
        }

        void animate() {
            if (interrupted) return; // a future gacha pull owns the display while this is set
            frame++;
            floatBrain();
            nerveTraces();
            neuralPulse();
            ambiance();
        }

        /** Bob on a slow sine + rotate slowly on yaw, interpolated between frames. */
        private void floatBrain() {
            if (!brain.isValid()) return;
            double bob = Math.sin(frame * BOB_RATE) * BOB_AMP;
            float yaw = (float) (frame * YAW_RATE);
            float s = BRAIN_SCALE;
            brain.setInterpolationDelay(0);
            brain.setTransformation(new Transformation(
                    new Vector3f(0f, (float) bob, 0f), new Quaternionf().rotateY(yaw),
                    new Vector3f(s, s, s), new Quaternionf()));
        }

        /** Green traces along the symmetric nerve branches fanning down off the spine base. */
        private void nerveTraces() {
            Location base = centre.clone().add(0, 0.5, 0);
            for (int b = 0; b < BRANCHES; b++) {
                double a = (Math.PI * 2 * b) / BRANCHES;
                double dx = Math.cos(a), dz = Math.sin(a);
                for (double t = 0.25; t <= 1.0; t += 0.25) {
                    // a gentle downward, outward arc with a slight per-frame waver, so it reads organic
                    double wav = Math.sin(frame * 0.15 + b) * 0.06 * t;
                    Location p = base.clone().add(dx * t + dz * wav, -0.35 * t, dz * t - dx * wav);
                    world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, NERVE_DUST);
                }
            }
        }

        /** Every {@link #PULSE_PERIOD} frames, a bright mote runs root-ward down one branch — the electric life. */
        private void neuralPulse() {
            int inCycle = frame % PULSE_PERIOD;
            if (inCycle >= PULSE_LENGTH) return;
            int b = (frame / PULSE_PERIOD) % BRANCHES;
            double a = (Math.PI * 2 * b) / BRANCHES;
            double f = inCycle / (double) PULSE_LENGTH; // 0 at spine → 1 at tip
            Location base = centre.clone().add(0, 0.5, 0);
            Location p = base.clone().add(Math.cos(a) * f, -0.35 * f, Math.sin(a) * f);
            world.spawnParticle(Particle.DUST, p, 2, 0.03, 0.03, 0.03, 0, PULSE_DUST);
        }

        /** A light green haze breathing around the brain — the "alive" ambiance, no container. */
        private void ambiance() {
            if (frame % 3 != 0) return;
            double bob = Math.sin(frame * BOB_RATE) * BOB_AMP;
            world.spawnParticle(Particle.DUST, brainAt.clone().add(0, bob, 0),
                    2, 0.35, 0.3, 0.35, 0, AMBIENT_DUST);
        }

        void reap() {
            if (brain.isValid()) brain.remove();
            if (spine.isValid()) spine.remove();
            for (BlockDisplay n : nerves) if (n.isValid()) n.remove();
        }
    }
}
