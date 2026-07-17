package com.nyrrine.reliquary.core;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Putting a body somewhere it will actually fit.
 *
 * <p>Any weapon that moves its wielder — a blink, a dash, appearing at someone's back — has to answer the
 * same question first: <b>will a person fit there, or does this drop them inside a wall?</b> Six weapons in
 * this vault teleport a player, and by 2026-07-17 <b>five of them had independently written the same
 * two-line answer</b> under three different names — {@code canStand} in Our Galaxy, Fragments and Discord,
 * {@code isClear} in Justitia, {@code fits} in Mimicry — while the sixth, Life for a Daredevil's execute,
 * had no check at all and would happily bury its wielder in stone. Nobody copied anyone; each author was
 * pointed at Our Galaxy as the reference and each solved it privately. When five strangers derive the
 * identical function, the function was always the framework's job. So it lives here now.
 *
 * <p>Nothing in here moves anybody. These are questions, not actions: they hand back a {@link Location} the
 * caller may teleport to, or {@code null} to say <i>there is nowhere</i>. Refusing is a real answer and the
 * caller should honour it — the house habit is to decline the ability and <b>not</b> burn its cooldown,
 * because a blink that eats your cooldown for standing too near a wall reads as a bug even when it is a
 * rule.
 *
 * <p>A destination carries no facing of its own. Callers set yaw and pitch to say what the move means:
 * keep the wielder's own look for a step they chose, or take the target's for an arrival at someone's back.
 */
public final class Blink {

    private Blink() {}

    /**
     * How far short of a solid face a travelling blink stops. Enough that the wielder lands looking at the
     * wall rather than embedded in the render of it.
     */
    public static final double WALL_MARGIN = 0.6;

    /** Step size when scanning back down a blocked path for the last spot that still holds a body. */
    private static final double SCAN_STEP = 0.5;

    /** Heights tried when shuffling a blocked destination — level, up one, down one, up two. */
    private static final double[] NUDGE_Y = {0.0, 1.0, -1.0, 2.0};

    /** Horizontal nudges tried at each height. A tight lattice: this is a shuffle, never a relocation. */
    private static final double[][] NUDGE_XZ = {{0.0, 0.0}, {0.6, 0.0}, {-0.6, 0.0}, {0.0, 0.6}, {0.0, -0.6}};

    /**
     * True if a player-sized body — feet and head — fits at {@code feet} without clipping into a solid
     * block. The whole of the question, and the two lines five weapons each arrived at alone.
     *
     * <p>Deliberately blind to entities: a body standing inside another body is vanilla's problem and it
     * resolves itself. Only geometry traps anyone.
     */
    public static boolean canStand(Location feet) {
        World world = feet.getWorld();
        if (world == null) return false;
        return feet.getBlock().isPassable()
                && feet.clone().add(0, 1, 0).getBlock().isPassable();
    }

    /**
     * The farthest spot along {@code dir} that will hold a body, up to {@code maxDistance}, stopping
     * {@link #WALL_MARGIN} short of the first solid face — then scanning back until something fits. Null if
     * there is nowhere at all, i.e. the wielder is pressed against a wall.
     *
     * <p>This is the <b>travelling</b> move: a step the wielder chose the direction of. Trace from the eye
     * so the ray sees what they see, but land the feet — {@code from} should be the player's location, not
     * their eye.
     */
    public static Location along(Location from, Location eye, Vector dir, double maxDistance) {
        World world = from.getWorld();
        if (world == null) return null;

        Vector heading = dir.clone().normalize();
        double reach = maxDistance;

        RayTraceResult wall = world.rayTraceBlocks(eye, heading, maxDistance, FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitPosition() != null) {
            reach = eye.toVector().distance(wall.getHitPosition()) - WALL_MARGIN;
        }
        reach = Math.min(maxDistance, Math.max(0.0, reach));

        for (double d = reach; d >= SCAN_STEP; d -= SCAN_STEP) {
            Location candidate = from.clone().add(heading.clone().multiply(d));
            if (canStand(candidate)) return candidate;
        }
        return null;
    }

    /**
     * {@code desired} if a body fits there, else the nearest spot in a tight lattice around it that does,
     * else null.
     *
     * <p>This is the <b>arriving</b> move: a place chosen for its meaning — at someone's back, where you
     * were standing six seconds ago — which the world may have closed over since, or which may have been
     * inside a wall from the start. It shuffles; it will not relocate. If the lattice comes up empty the
     * spot is genuinely buried and the caller should say so rather than put a body in it.
     */
    public static Location near(Location desired) {
        if (desired.getWorld() == null) return null;
        for (double dy : NUDGE_Y) {
            for (double[] off : NUDGE_XZ) {
                Location candidate = desired.clone().add(off[0], dy, off[1]);
                if (canStand(candidate)) return candidate;
            }
        }
        return null;
    }

    /**
     * The spot {@code distance} behind {@code target}, shuffled to somewhere a body fits — the move every
     * weapon in this vault that appears at someone's back is really asking for. Null if there is nowhere.
     *
     * <p>The destination faces the way the target faces, so the wielder arrives at their shoulder rather
     * than nose to nose. Callers wanting their own look back can overwrite the yaw.
     */
    public static Location behind(Location target, double distance) {
        Vector facing = target.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) facing = new Vector(0, 0, 1);
        facing.normalize();

        Location spot = target.clone().subtract(facing.multiply(distance));
        spot.setYaw(target.getYaw());
        spot.setPitch(0f);

        Location safe = near(spot);
        if (safe == null) return null;
        safe.setYaw(spot.getYaw());
        safe.setPitch(0f);
        return safe;
    }
}
