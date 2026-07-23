package com.nyrrine.reliquary.extraction;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The extraction show above Carmen's Brain — a living <b>aquarium</b>. The ticket's opportunities drift around
 * the Brain like fish: each floats on its own independent layered-noise path (no schools), gently contained to a
 * volume, softly pushing off neighbours, colour-coded to what it is (a weapon by its grade, a pouch by its
 * rarity, the Daughters Bag by its own hue). A player can bat one with a left-click and it gets flung.
 *
 * <p><b>Block-safety (hard invariant, moving or still):</b> a position is accepted only if its block is non-solid,
 * the Brain has line-of-sight to it, AND it clears any floor below by {@link #FLOOR_CLEARANCE}; else the item
 * holds its last good spot. Items never rest on, kiss, or slip under a block.
 *
 * <p><b>Show arc:</b> a summon (items grow out with grade-coloured synapse tendrils), a tendril-despawn flourish,
 * a long tendril-free free-float, then a staggered pop-out outro on the natural timeout. A sneak-pull runs the
 * <b>hype reel</b> instead: the floaters rush into the Brain, a slot-machine reel flashes random outcomes fast
 * then decelerating, and <b>slams</b> onto the predetermined result with a tiered burst. The reel is purely
 * cosmetic and never alters the grant.
 *
 * <p>All entities — displays AND hitboxes — carry {@link #TAG}, are non-persistent, and are reaped on every exit
 * plus a cross-world sweep.
 */
public final class WellDisplay {

    /** One floating opportunity: the item to show, its show colour (tendril/glow/burst), and a stable id. */
    public record FloatItem(ItemStack display, Color color, String id) {}

    /** The predetermined result the reel slams onto: the item, its burst colour + tier, bag flag, and reel pool
     *  (each reel entry carries its own colour so the central glow tracks the flashed item, not the result). */
    public record PullShow(ItemStack result, Color color, int tier, boolean bag, List<FloatItem> reel) {}

    /** Scoreboard tag on every entity this show spawns — distinct from the idle Brain tag, for orphan reaping. */
    public static final String TAG = "reliquary_well_preview";

    /** How long the tendril-free free-float lingers before the pop-out outro (ticks ≈ 45s). */
    public static final int DURATION_TICKS = 900;

    private static final int    PERIOD        = 2;
    private static final double SPHERE_RADIUS = 7.5;
    private static final double MIN_RADIUS    = 2.0;
    private static final int    SAFETY_CEILING = 64;
    private static final int    INTRO_TICKS   = 20;
    private static final int    OUTRO_LEN     = 160;
    private static final int    OUTRO_START   = DURATION_TICKS - OUTRO_LEN;
    private static final double WATCH_RANGE   = 40.0;

    // The hype reel timings (longer, decelerating, built for someone watching).
    private static final int    LEAD_TICKS = 8;    // floaters turn to face centre + the reel scales in
    private static final int    REEL_TICKS = 74;   // slot-machine flashing (~3.7s), decelerating enticingly
    private static final int    REEL_END   = LEAD_TICKS + REEL_TICKS;
    private static final int    FINALE_BASE = 14;  // slam finale length + tier * FINALE_PER (bag longest)
    private static final int    FINALE_PER  = 6;
    private static final float  REEL_SCALE = 1.05f;

    private static final float  PLANET_SCALE  = 0.65f;
    private static final double GOLDEN_ANGLE  = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double BEAD_SPEED    = 0.6;
    private static final float  HITBOX_SIZE   = 0.7f;

    private static final double DT           = PERIOD / 20.0;
    private static final double WANDER_ACCEL = 2.4;
    private static final double ORBIT_BIAS   = 0.55;
    private static final double CONTAIN      = 3.2;
    private static final double SEP_DIST     = 1.7;
    private static final double SEP_STRENGTH = 4.0;
    private static final double DAMP         = 0.88;
    private static final double MAX_SPEED    = 3.0;
    private static final double IMPULSE      = 2.8;
    private static final double FLOOR_CLEARANCE = 0.7;
    private static final double FLOOR_PROBE  = FLOOR_CLEARANCE + 1.6;

    private static final Vector DOWN = new Vector(0, -1, 0);
    private static final Color  BAG_RED  = Color.fromRGB(0xFF3B3B);
    private static final Color  BAG_BLUE = Color.fromRGB(0x3B5DC9);
    private static final Color  BAG_GOLD = Color.fromRGB(0xFFC94D);
    private static final Particle.DustOptions HAZE = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.7f);

    private final Plugin plugin;
    private BukkitRunnable active;
    private final List<Entity> current = new ArrayList<>();
    private final List<Planet> planets = new ArrayList<>();

    private Location sceneCentre;
    private String sceneKey;
    private Runnable onPullDone; // the exactly-once completion (delivery + brain restore) for a running pull

    public WellDisplay(Plugin plugin) { this.plugin = plugin; }

    /** Fire the pending pull completion exactly once (delivery + brain restore); no-op if none/already fired. */
    private void firePullDone() {
        Runnable r = onPullDone;
        onPullDone = null;
        if (r != null) r.run();
    }

    /** One drifting opportunity: its display + hitbox, colour, and its live aquarium motion state. */
    private static final class Planet {
        final ItemDisplay body;
        final Interaction hitbox;
        final String id;
        final Color color;
        final Vector anchor;
        final float scale;
        final float spin;
        final double[] wf;
        final double[] wp;
        final int order;
        final Vector vel = new Vector();
        final Vector pos = new Vector();
        final Vector lastPos = new Vector();
        long lastAttack = 0L;
        boolean popped = false;

        Planet(ItemDisplay body, Interaction hitbox, String id, Color color, Vector anchor,
               float scale, float spin, int order) {
            this.body = body; this.hitbox = hitbox; this.id = id; this.color = color; this.anchor = anchor;
            this.scale = scale; this.spin = spin; this.order = order;
            this.wf = new double[]{0.26 + 0.16 * frac01(order * 0.37f),
                                   0.21 + 0.15 * frac01(order * 0.61f),
                                   0.24 + 0.16 * frac01(order * 0.79f)};
            this.wp = new double[]{order * 1.3, order * 2.1 + 0.6, order * 0.7 + 1.4};
            this.pos.copy(anchor);
            this.lastPos.copy(anchor);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------

    public void stop() {
        if (active != null) { active.cancel(); active = null; }
        reapScene();
        firePullDone(); // a spent ticket always pays out, even on an interrupt (new preview/pull, disable)
    }

    public void disable() {
        stop();
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class))  if (tagged(e)) e.remove();
            for (Entity e : w.getEntitiesByClass(Interaction.class))  if (tagged(e)) e.remove();
        }
    }

    private static boolean tagged(Entity e) { return e.getScoreboardTags().contains(TAG); }

    private void reapScene() {
        for (Entity e : current) if (e.isValid()) e.remove();
        current.clear();
        planets.clear();
        sceneCentre = null;
        sceneKey = null;
    }

    // ---- the idle preview ----------------------------------------------------------

    /**
     * Show (or keep) the aquarium of {@code items} around the Brain hover centre {@code brainCentre}. Idempotent:
     * a right-click asking for the same board already up keeps it drifting. Empty list → a small puff.
     */
    public void reveal(Location brainCentre, List<FloatItem> items) {
        String key = keyFor(brainCentre, items);
        if (active != null && key.equals(sceneKey)) return;

        stop();
        World world = brainCentre.getWorld();
        if (items.isEmpty()) {
            world.spawnParticle(Particle.SMOKE, brainCentre, 20, 0.25, 0.25, 0.25, 0.01);
            world.playSound(brainCentre, Sound.BLOCK_CONDUIT_DEACTIVATE, 0.6f, 0.8f);
            return;
        }

        buildScene(brainCentre, items);
        sceneKey = key;

        world.playSound(brainCentre, Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.1f);
        world.playSound(brainCentre, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.5f);
        world.playSound(brainCentre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);

        final Location centre = sceneCentre;
        active = new BukkitRunnable() {
            int t = 0;
            boolean handoff = false;
            @Override public void run() {
                if (!alive(centre)) { reapScene(); cancel(); if (active == this) active = null; return; }
                double time = t / 20.0;
                if (t < INTRO_TICKS) {
                    summon(centre, time, t);
                } else {
                    if (!handoff) { tendrilFlourish(centre); handoff = true; }
                    updateSwarm(centre, time);
                    ambientHaze(centre, time);
                    if (t >= OUTRO_START) {
                        popDue(centre, t);
                        if (allPopped() || t >= DURATION_TICKS + OUTRO_LEN) {
                            reapScene(); cancel(); if (active == this) active = null; return;
                        }
                    }
                }
                t += PERIOD;
            }
        };
        active.runTaskTimer(plugin, 0L, PERIOD);
    }

    // ---- the hype reel pull --------------------------------------------------------

    /**
     * Play the sneak-pull hype reel. The floaters freeze and stream their colour inward to feed a central reel
     * that flashes {@code show.reel()} (its glow tracking each flashed item) fast then decelerating, then slams
     * onto {@code show.result()} with a per-tier finale. Purely cosmetic. {@code onComplete} is the delivery +
     * brain-restore callback, fired <b>exactly once</b> on any termination (natural end, interrupt, disable).
     */
    public void pull(Location brainCentre, PullShow show, Runnable onComplete) {
        if (active != null) { active.cancel(); active = null; }
        firePullDone();            // deliver any prior pending pull first (one pull at a time; defensive)
        onPullDone = onComplete;   // guarded: fires exactly once via firePullDone() on every path below

        final Location centre = sceneCentre != null ? sceneCentre : brainCentre.clone();
        if (sceneCentre == null) sceneCentre = centre;
        final World world = centre.getWorld();
        final Vector cc = centre.toVector();

        for (Planet pl : planets) if (pl.hitbox.isValid()) pl.hitbox.remove(); // no batting mid-pull

        final List<FloatItem> pool = show.reel().isEmpty()
                ? List.of(new FloatItem(show.result(), show.color(), "result")) : show.reel();
        final ItemDisplay reel = spawnBody(centre, resolve(pool.get(0).display()), 0.01f, pool.get(0).color());
        current.add(reel);
        final int finaleLen = FINALE_BASE + (show.bag() ? 5 : Math.max(1, Math.min(5, show.tier()))) * FINALE_PER;

        world.playSound(centre, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);

        active = new BukkitRunnable() {
            int t = 0;
            int nextSwap = LEAD_TICKS;
            boolean slammed = false;
            @Override public void run() {
                if (!alive(centre)) {
                    reapScene(); cancel(); if (active == this) active = null; firePullDone(); return;
                }
                double time = t / 20.0;

                if (t < REEL_END) {
                    double reelScale = t < LEAD_TICKS ? REEL_SCALE * (t / (double) LEAD_TICKS) : REEL_SCALE;
                    place(reel, centre, cc.clone().add(new Vector(0, 0.35, 0)), yaw((float) (time * 5)), (float) reelScale);
                    // The floaters hold frozen through the spin (the idle is cancelled); no inward feed particles.
                    if (t >= LEAD_TICKS && t >= nextSwap) {
                        double rp = (t - LEAD_TICKS) / (double) REEL_TICKS;
                        FloatItem fi = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                        reel.setItemStack(resolve(fi.display()));
                        reel.setGlowColorOverride(fi.color());          // glow tracks the flashed item, no telegraph
                        nextSwap = t + 2 + (int) Math.round(rp * rp * 16); // decelerate enticingly toward the slam
                        world.playSound(centre, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, (float) (0.6 + rp * 1.7));
                        world.spawnParticle(Particle.CRIT, cc.clone().add(new Vector(0, 0.35, 0)).toLocation(world),
                                4, 0.25, 0.25, 0.25, 0.03);
                    }
                } else {
                    if (!slammed) {
                        reel.setItemStack(resolve(show.result()));
                        reel.setGlowColorOverride(show.color());
                        fadeFloaters(centre); // the floaters wink out as the result lands
                        slammed = true;
                    }
                    int ft = t - REEL_END;
                    Location at = cc.clone().add(new Vector(0, 0.45, 0)).toLocation(world);
                    double gp = Math.min(1.0, ft / 8.0);
                    place(reel, centre, cc.clone().add(new Vector(0, 0.45, 0)), yaw((float) (time * 2)),
                            (float) lerp(REEL_SCALE, slamScale(show.tier(), show.bag()), easeOut(gp)));
                    finale(at, show.color(), show.tier(), show.bag(), ft, finaleLen);
                    if (ft >= finaleLen) {
                        reapScene(); cancel(); if (active == this) active = null; firePullDone(); return;
                    }
                }
                t += PERIOD;
            }
        };
        active.runTaskTimer(plugin, 0L, PERIOD);
    }

    /** Pop each floater out (a small colour puff) as the reel slams. */
    private void fadeFloaters(Location centre) {
        World world = centre.getWorld();
        for (Planet pl : planets) {
            if (pl.popped || !pl.body.isValid()) continue;
            pl.popped = true;
            world.spawnParticle(Particle.DUST, pl.lastPos.toLocation(world), 6, 0.15, 0.15, 0.15, 0,
                    new Particle.DustOptions(pl.color, 0.85f));
            pl.body.remove();
        }
    }

    private static float slamScale(int tier, boolean bag) {
        if (bag) return 1.9f;
        return 1.0f + Math.max(0, Math.min(5, tier)) * 0.16f;
    }

    // ---- scene construction --------------------------------------------------------

    private void buildScene(Location brainCentre, List<FloatItem> items) {
        this.sceneCentre = brainCentre.clone();
        int n = Math.min(items.size(), SAFETY_CEILING);
        for (int i = 0; i < n; i++) {
            FloatItem it = items.get(i);
            Vector3f dir = shellDirection(i, n);
            double radius = SPHERE_RADIUS * (0.55 + 0.35 * frac01(i * 0.618f));
            Vector anchor = brainCentre.toVector().add(new Vector(dir.x * radius, dir.y * radius, dir.z * radius));
            ItemDisplay body = spawnBody(brainCentre, resolve(it.display()), 0.01f, it.color());
            Interaction hitbox = spawnHitbox(brainCentre);
            current.add(body);
            current.add(hitbox);
            float scale = PLANET_SCALE * (0.85f + 0.35f * frac01(i * 0.399f));
            float spin  = 0.6f + 0.9f * frac01(i * 0.222f);
            planets.add(new Planet(body, hitbox, it.id(), it.color(), anchor, scale, spin, i));
        }
    }

    private Vector3f shellDirection(int i, int n) {
        double y = 1.0 - (i + 0.5) / n * 2.0;
        double rXZ = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = i * GOLDEN_ANGLE;
        return new Vector3f((float) (Math.cos(theta) * rXZ), (float) y, (float) (Math.sin(theta) * rXZ));
    }

    // ---- the summon (grow-out) -----------------------------------------------------

    private void summon(Location centre, double time, int t) {
        World world = centre.getWorld();
        Vector c = centre.toVector();
        for (Planet pl : planets) {
            if (!pl.body.isValid()) continue;
            double grow = introGrow(t, pl.order);
            Location cand = floorClear(world, clampToClear(world, centre, lerp(c, pl.anchor, grow).toLocation(world)));
            Vector pos = clear(world, centre, cand) ? cand.toVector() : pl.pos.clone();
            pl.pos.copy(pos);
            pl.lastPos.copy(pos);
            place(pl.body, centre, pos, yaw((float) (time * pl.spin)), (float) (pl.scale * grow));
            moveHitbox(pl, pos);
            drawTendril(centre, pos.toLocation(world), pl, time, grow);
        }
    }

    private void tendrilFlourish(Location centre) {
        World world = centre.getWorld();
        Vector a = centre.toVector();
        for (Planet pl : planets) {
            Particle.DustOptions dust = new Particle.DustOptions(pl.color, 0.95f);
            Vector b = pl.lastPos.clone();
            Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.4, 0));
            for (int s = 1; s <= 8; s++) {
                Vector pt = bezier(a, control, b, s / 8.0);
                world.spawnParticle(Particle.DUST, pt.toLocation(world), 1, 0.02, 0.02, 0.02, 0, dust);
                if (s % 3 == 0) world.spawnParticle(Particle.END_ROD, pt.toLocation(world), 1, 0.02, 0.02, 0.02, 0.01);
            }
        }
        world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.9f);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.7f);
    }

    // ---- the free-float swarm ------------------------------------------------------

    private void updateSwarm(Location centre, double time) {
        int n = planets.size();
        Vector cc = centre.toVector();
        Vector[] accel = new Vector[n];

        for (int i = 0; i < n; i++) {
            Planet pl = planets.get(i);
            if (pl.popped || !pl.body.isValid()) { accel[i] = new Vector(); continue; }
            Vector a = new Vector(
                    Math.sin(time * pl.wf[0] + pl.wp[0]) + 0.5 * Math.sin(time * pl.wf[0] * 2.3 + pl.wp[1]),
                    0.7 * Math.sin(time * pl.wf[1] + pl.wp[1]),
                    Math.sin(time * pl.wf[2] + pl.wp[2]) + 0.5 * Math.sin(time * pl.wf[2] * 1.9 + pl.wp[0]));
            a.multiply(WANDER_ACCEL);

            Vector rel = pl.pos.clone().subtract(cc);
            double d = rel.length();
            if (d > 1.0e-4) {
                Vector radial = rel.clone().multiply(1.0 / d);
                if (d > SPHERE_RADIUS) a.add(radial.clone().multiply(-(d - SPHERE_RADIUS) * CONTAIN));
                else if (d < MIN_RADIUS) a.add(radial.clone().multiply((MIN_RADIUS - d) * CONTAIN));
                Vector tangent = new Vector(-rel.getZ(), 0, rel.getX());
                if (tangent.lengthSquared() > 1.0e-6) a.add(tangent.normalize().multiply(ORBIT_BIAS));
            }
            accel[i] = a;
        }

        double sep2 = SEP_DIST * SEP_DIST;
        for (int i = 0; i < n; i++) {
            Planet pi = planets.get(i);
            if (pi.popped || !pi.body.isValid()) continue;
            for (int j = i + 1; j < n; j++) {
                Planet pj = planets.get(j);
                if (pj.popped || !pj.body.isValid()) continue;
                double d2 = pi.pos.distanceSquared(pj.pos);
                if (d2 >= sep2 || d2 < 1.0e-6) continue;
                double d = Math.sqrt(d2);
                Vector push = pi.pos.clone().subtract(pj.pos).multiply(1.0 / d)
                        .multiply((SEP_DIST - d) / SEP_DIST * SEP_STRENGTH);
                accel[i].add(push);
                accel[j].subtract(push);
            }
        }

        World world = centre.getWorld();
        for (int i = 0; i < n; i++) {
            Planet pl = planets.get(i);
            if (pl.popped || !pl.body.isValid()) continue;
            pl.vel.add(accel[i].multiply(DT)).multiply(DAMP);
            capSpeed(pl.vel);
            Vector desired = pl.pos.clone().add(pl.vel.clone().multiply(DT));
            Location cand = floorClear(world, clampToClear(world, centre, desired.toLocation(world)));
            if (clear(world, centre, cand)) pl.pos.copy(cand.toVector());
            else pl.vel.multiply(0.3);
            pl.lastPos.copy(pl.pos);
            place(pl.body, centre, pl.pos, yaw((float) (time * pl.spin)), pl.scale);
            moveHitbox(pl, pl.pos);
            pollHit(centre, pl);
        }
    }

    private void pollHit(Location centre, Planet pl) {
        if (!pl.hitbox.isValid()) return;
        Interaction.PreviousInteraction atk = pl.hitbox.getLastAttack();
        if (atk == null || atk.getTimestamp() <= pl.lastAttack) return;
        pl.lastAttack = atk.getTimestamp();
        Vector out = pl.pos.clone().subtract(centre.toVector());
        if (out.lengthSquared() < 1.0e-4) out = new Vector(0, 1, 0); else out.normalize();
        pl.vel.add(out.multiply(IMPULSE).add(new Vector(0, 0.5, 0)));
        World world = centre.getWorld();
        Location at = pl.pos.toLocation(world);
        world.spawnParticle(Particle.DUST, at, 16, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(pl.color, 1.0f));
        world.spawnParticle(Particle.END_ROD, at, 8, 0.25, 0.25, 0.25, 0.06);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.9f, 1.3f);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.2f);
    }

    // ---- the pop-out outro ---------------------------------------------------------

    private void popDue(Location centre, int t) {
        int n = planets.size();
        World world = centre.getWorld();
        for (Planet pl : planets) {
            if (pl.popped) continue;
            int due = OUTRO_START + (int) (pl.order / (double) Math.max(1, n) * (OUTRO_LEN * 0.85));
            if (t < due) continue;
            pl.popped = true;
            Location at = pl.lastPos.toLocation(world);
            world.spawnParticle(Particle.DUST, at, 8, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(pl.color, 0.9f));
            world.spawnParticle(Particle.END_ROD, at, 3, 0.05, 0.05, 0.05, 0.02);
            world.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
            if (pl.body.isValid()) pl.body.remove();
            if (pl.hitbox.isValid()) pl.hitbox.remove();
        }
    }

    private boolean allPopped() {
        for (Planet pl : planets) if (!pl.popped) return false;
        return true;
    }

    // ---- collision -----------------------------------------------------------------

    private Location clampToClear(World world, Location centre, Location desired) {
        if (clear(world, centre, desired)) return desired;
        Vector dir = desired.toVector().subtract(centre.toVector());
        double dist = dir.length();
        if (dist < 1.0e-4) return centre.clone().add(0, 0.5, 0);
        dir.normalize();
        RayTraceResult hit = world.rayTraceBlocks(centre, dir, dist, FluidCollisionMode.NEVER, true);
        double reach = hit != null && hit.getHitPosition() != null
                ? hit.getHitPosition().distance(centre.toVector()) : dist;
        double clamped = Math.max(0.6, reach - 0.4);
        return centre.clone().add(dir.multiply(clamped));
    }

    private Location floorClear(World world, Location loc) {
        RayTraceResult down = world.rayTraceBlocks(loc, DOWN, FLOOR_PROBE, FluidCollisionMode.NEVER, true);
        if (down != null && down.getHitPosition() != null) {
            double minY = down.getHitPosition().getY() + FLOOR_CLEARANCE;
            if (loc.getY() < minY) { Location up = loc.clone(); up.setY(minY); return up; }
        }
        return loc;
    }

    private boolean clear(World world, Location centre, Location spot) {
        if (spot.getBlock().getType().isSolid()) return false;
        Vector dir = spot.toVector().subtract(centre.toVector());
        double dist = dir.length();
        if (dist < 1.0e-4) return false;
        RayTraceResult hit = world.rayTraceBlocks(centre, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    // ---- spawning ------------------------------------------------------------------

    private ItemDisplay spawnBody(Location at, ItemStack stack, float scale, Color glow) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowColorOverride(glow);
            d.setGlowing(true);
            d.setInterpolationDuration(PERIOD);
            d.addScoreboardTag(TAG);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    private Interaction spawnHitbox(Location at) {
        return at.getWorld().spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(HITBOX_SIZE);
            i.setInteractionHeight(HITBOX_SIZE);
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(TAG);
        });
    }

    private void moveHitbox(Planet pl, Vector pos) {
        if (pl.hitbox.isValid()) {
            pl.hitbox.teleport(new Location(pl.hitbox.getWorld(), pos.getX(),
                    pos.getY() - HITBOX_SIZE / 2.0, pos.getZ()));
        }
    }

    private void place(Display d, Location centre, Vector worldPos, Quaternionf rot, float scale) {
        Vector3f rel = new Vector3f(
                (float) (worldPos.getX() - centre.getX()),
                (float) (worldPos.getY() - centre.getY()),
                (float) (worldPos.getZ() - centre.getZ()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(PERIOD);
        d.setTransformation(new Transformation(rel, rot, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    // ---- links + bursts ------------------------------------------------------------

    /** A curved grade-coloured synapse tendril Brain→item with a travelling pulse bead — summon only. */
    private void drawTendril(Location centre, Location end, Planet pl, double time, double grow) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(pl.color, 0.6f);
        Particle.DustOptions bead = new Particle.DustOptions(pl.color, 0.95f);
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.4, 0));
        for (int s = 1; s <= 5; s++) spawnAt(world, bezier(a, control, b, (s / 5.0) * grow), dust);
        double bu = frac01((float) (time * BEAD_SPEED + pl.wp[0])) * grow;
        spawnAt(world, bezier(a, control, b, bu), bead);
    }

    /**
     * The per-tier slam finale: firework-style crackling bursts spread across {@code [0, len]}, escalating with
     * tier (Common = a single modest crackle, ALEPH/Fabled = several bigger, longer, layered crackles), the bag
     * getting its own red/blue/gold multi-firework spectacle. <b>Damage-free by construction:</b> it uses
     * {@link Particle#FIREWORK} sparks + a coloured dust star + the firework crackle SFX, so no {@code Firework}
     * entity is spawned and nothing can ever hurt a player (the sanctioned no-damage path).
     */
    private void finale(Location at, Color color, int tier, boolean bag, int ft, int len) {
        World world = at.getWorld();
        if (bag) { bagFinale(world, at, ft, len); return; }
        int tt = Math.max(1, Math.min(5, tier));
        int frames = Math.max(1, len / PERIOD);
        int frame = ft / PERIOD;
        int bursts = tt;                              // Common 1 ... ALEPH 5 crackles across the window
        int step = Math.max(1, frames / bursts);
        if (frame % step == 0 && frame / step < bursts) {
            crackle(world, at.clone().add(burstOffset(frame / step, tt)), color, 0.8 + tt * 0.32, tt);
        }
    }

    /** The Daughters Bag's own spectacle: a run of red/blue/gold firework crackles across the window. */
    private void bagFinale(World world, Location at, int ft, int len) {
        if (ft == 0) {
            world.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 0.9f);
            world.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        }
        int frames = Math.max(1, len / PERIOD);
        int frame = ft / PERIOD;
        int bursts = 6;
        int step = Math.max(1, frames / bursts);
        if (frame % step == 0 && frame / step < bursts) {
            int k = frame / step;
            Color c = switch (k % 3) { case 0 -> BAG_RED; case 1 -> BAG_BLUE; default -> BAG_GOLD; };
            crackle(world, at.clone().add(burstOffset(k, 5)), c, 1.7, 5);
        }
    }

    /** One damage-free firework crackle: a coloured star + firework sparks + the firework blast/twinkle SFX. */
    private void crackle(World world, Location at, Color color, double power, int tt) {
        double spread = 0.25 * power;
        world.spawnParticle(Particle.DUST, at, (int) (16 * power), spread, spread, spread, 0,
                new Particle.DustOptions(color, 1.5f));
        world.spawnParticle(Particle.FIREWORK, at, (int) (24 * power), 0.35 * power, 0.35 * power, 0.35 * power, 0.09);
        world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, (float) (1.3 - tt * 0.06));
        world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.7f, 1.0f);
        if (tt >= 4) world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.9f, 1.0f);
    }

    /** A deterministic offset so multi-crackle finales spread out instead of stacking dead-centre. */
    private Vector burstOffset(int k, int tt) {
        if (tt <= 1 || k == 0) return new Vector(0, 0, 0);
        double a = k * 2.399963; // golden-angle spread
        double r = 0.5 + (k % 3) * 0.35;
        return new Vector(Math.cos(a) * r, (k % 2) * 0.4, Math.sin(a) * r);
    }

    private void ambientHaze(Location centre, double time) {
        World world = centre.getWorld();
        double bob = Math.sin(time * 1.3) * 0.1;
        world.spawnParticle(Particle.DUST, centre.clone().add(0, bob, 0), 2, 0.6, 0.5, 0.6, 0, HAZE);
        world.spawnParticle(Particle.GLOW, centre.clone().add(0, 0.2, 0), 1, 1.6, 1.2, 1.6, 0);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.18f, 1.5f);
    }

    // ---- small helpers -------------------------------------------------------------

    private boolean alive(Location centre) {
        World w = centre.getWorld();
        if (w == null || !w.isChunkLoaded(centre.getBlockX() >> 4, centre.getBlockZ() >> 4)) return false;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(centre) <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    private double introGrow(int t, int order) {
        double p = Math.max(0.0, Math.min(1.0, (t - order * 0.4) / (double) Math.max(1, INTRO_TICKS - 4)));
        return easeOut(p);
    }

    private void capSpeed(Vector v) {
        double s2 = v.lengthSquared();
        if (s2 > MAX_SPEED * MAX_SPEED) v.multiply(MAX_SPEED / Math.sqrt(s2));
    }

    private ItemStack resolve(ItemStack it) {
        return it != null && it.getType() != Material.AIR ? it : new ItemStack(Material.NETHER_STAR);
    }

    private Quaternionf yaw(float radians) { return new Quaternionf(new AxisAngle4f(radians, 0f, 1f, 0f)); }

    private void spawnAt(World world, Vector v, Particle.DustOptions dust) {
        world.spawnParticle(Particle.DUST, v.toLocation(world), 1, 0, 0, 0, 0, dust);
    }

    private Vector bezier(Vector a, Vector c, Vector b, double u) {
        double mu = 1.0 - u;
        return new Vector(
                mu * mu * a.getX() + 2 * mu * u * c.getX() + u * u * b.getX(),
                mu * mu * a.getY() + 2 * mu * u * c.getY() + u * u * b.getY(),
                mu * mu * a.getZ() + 2 * mu * u * c.getZ() + u * u * b.getZ());
    }

    private static Vector lerp(Vector a, Vector b, double t) {
        return new Vector(a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t);
    }

    private static double easeOut(double x) { double m = 1 - x; return 1 - m * m * m; }
    private static double easeIn(double x)  { return x * x * x; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static float frac01(float x) { return (float) (x - Math.floor(x)); }

    private String keyFor(Location centre, List<FloatItem> items) {
        StringBuilder sb = new StringBuilder(centre.getWorld().getName())
                .append(',').append(centre.getBlockX()).append(',').append(centre.getBlockY())
                .append(',').append(centre.getBlockZ()).append('|');
        for (FloatItem it : items) sb.append(it.id()).append(',');
        return sb.toString();
    }
}
