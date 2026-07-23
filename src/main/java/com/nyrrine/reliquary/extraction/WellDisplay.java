package com.nyrrine.reliquary.extraction;

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
import java.util.function.Function;

/**
 * The extraction show above Carmen's Brain — a living <b>solar-system nervous system</b>. The Brain sits at the
 * centre and the ticket's reachable weapons float around it on a slowly-orbiting shell, each an {@link ItemDisplay}
 * linked back to the Brain by a curved green synapse tendril with travelling pulses. The whole constellation spins
 * around the Brain; each weapon also drifts on a buoyant water-sway and can be batted around.
 *
 * <p><b>Live collision (hard invariant, moving or still):</b> every frame each weapon's resolved position is
 * checked — its block must be non-solid AND the Brain must have clear line-of-sight to it ({@link #clear}); a
 * blocked position is clamped back along the Brain→item ray to the last clear point, so an item bumps a wall and
 * slides instead of ever entering, hiding behind, or passing through it.
 *
 * <p><b>Hittable "for funsies":</b> each weapon carries an {@link Interaction} hitbox tracking its position; a
 * left-click (polled via {@link Interaction#getLastAttack()}, no listener) bats it — a knockback impulse the item
 * carries as a damped spring back toward its home slot, colliding with blocks per the move-rule. This is purely
 * cosmetic: no grant, no drop, no ticket spend. Right-clicks still run the proximity random-pull.
 *
 * <p>{@link #reveal} builds/keeps the idle preview (a "wow" scale-in that lingers for {@link #DURATION_TICKS});
 * {@link #pull} plays the gacha pull. All entities — displays AND hitboxes — carry {@link #TAG}, are
 * non-persistent, and are reaped on every exit (timeout, new preview, pull resolve, the Brain leaving/unloading,
 * or disable + a cross-world tag sweep), with a preview tag distinct from the idle Brain's so the reaps never fight.
 */
public final class WellDisplay {

    /** Scoreboard tag on every entity this show spawns — distinct from the idle Brain tag, for orphan reaping. */
    public static final String TAG = "reliquary_well_preview";

    /** How long an idle preview lingers before it dissolves (ticks). */
    public static final int DURATION_TICKS = 220;

    private static final int    PERIOD        = 2;     // ticks between physics/collision/particle frames (throttle)
    private static final double SPHERE_RADIUS = 5.5;   // shell radius the weapons float over (placeholder)
    private static final int    SAFETY_CEILING = 64;   // hard ceiling on floating weapons (pathological tickets)
    private static final int    INTRO_TICKS   = 16;    // the scale-in "wow" opening
    private static final int    PULL_TICKS    = 34;    // the sneak-extract pull animation
    private static final double WATCH_RANGE   = 32.0;  // reap the preview if no player stays this near the Brain

    private static final float  PLANET_SCALE  = 0.65f; // base weapon-model scale (varied per planet)
    private static final float  CHOSEN_SCALE  = 1.5f;  // the pulled weapon blooms to this
    private static final double SWAY_AMP      = 0.28;  // buoyant water-drift amplitude
    private static final double ORBIT_RATE    = Math.toRadians(14); // constellation spin (rad/sec)
    private static final double GOLDEN_ANGLE  = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double BEAD_SPEED    = 0.6;   // synapse pulse travel (cycles/sec along a tendril)
    private static final float  HITBOX_SIZE   = 0.7f;

    // Knock physics (a damped spring pulling a batted item back to its home slot).
    private static final double KNOCK_SPRING = 7.0;
    private static final double KNOCK_DAMP   = 3.2;
    private static final double IMPULSE      = 1.3;
    private static final double DT           = PERIOD / 20.0;

    private static final Particle.DustOptions TENDRIL = new Particle.DustOptions(CarmenBrainVfx.GREEN, 0.6f);
    private static final Particle.DustOptions BEAD    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.95f);
    private static final Particle.DustOptions HAZE    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.7f);
    private static final Particle.DustOptions ARC     = new Particle.DustOptions(CarmenBrainVfx.GREEN, 0.5f);

    private final Plugin plugin;
    private BukkitRunnable active;
    private final List<Entity> current = new ArrayList<>();  // every entity this scene spawned (displays + hitboxes)
    private final List<Planet> planets = new ArrayList<>();

    private Location sceneCentre;                            // the Brain hover centre this scene is built around
    private String sceneKey;                                 // centre + candidate ids, for idempotent re-reveal
    private Function<WeaponSpec, ItemStack> lastItemFor;     // cached so pull() can resolve a chosen not on-shell

    public WellDisplay(Plugin plugin) { this.plugin = plugin; }

    /** One floating weapon: its display + hitbox, spec, home slot on the shell, and its live motion state. */
    private static final class Planet {
        final ItemDisplay body;
        final Interaction hitbox;
        final WeaponSpec spec;
        final Vector3f dir;         // unit home direction on the shell (pre-orbit)
        final double radius;
        final float scale;
        final float spin;
        final double p1, p2, p3;    // sway phase seeds
        final int order;
        final Vector vel = new Vector();        // knock velocity
        final Vector disp = new Vector();       // knock displacement from the home+sway point
        final Vector lastPos = new Vector();    // last resolved world position (pull starts from here)
        long lastAttack = 0L;

        Planet(ItemDisplay body, Interaction hitbox, WeaponSpec spec, Vector3f dir, double radius,
               float scale, float spin, int order) {
            this.body = body; this.hitbox = hitbox; this.spec = spec; this.dir = dir; this.radius = radius;
            this.scale = scale; this.spin = spin; this.order = order;
            this.p1 = order * 1.7; this.p2 = order * 2.3 + 0.5; this.p3 = order * 0.9 + 1.1;
        }
    }

    // ---- lifecycle -----------------------------------------------------------------

    /** Cancel any running scene and reap its entities (new preview / pull start / out-of-range / disable). */
    public void stop() {
        if (active != null) { active.cancel(); active = null; }
        reapScene();
    }

    /** Plugin disable: stop the live scene, then sweep any tagged orphan (display OR hitbox) across every world. */
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
     * Show (or keep) the idle orbiting preview around the Brain hover centre {@code brainCentre} for the reachable
     * {@code candidates}. Idempotent: a right-click that asks for the same scene already on screen keeps it
     * animating instead of restarting it. Empty pool → a small puff. {@code itemFor} resolves a spec to its weapon
     * model (null → a Nether Star placeholder).
     */
    public void reveal(Location brainCentre, List<WeaponSpec> candidates,
                       Function<WeaponSpec, ItemStack> itemFor) {
        String key = keyFor(brainCentre, candidates);
        if (active != null && key.equals(sceneKey)) return; // same scene already up — let it keep breathing

        stop();
        World world = brainCentre.getWorld();
        if (candidates.isEmpty()) {
            world.spawnParticle(Particle.SMOKE, brainCentre, 20, 0.25, 0.25, 0.25, 0.01);
            world.playSound(brainCentre, Sound.BLOCK_CONDUIT_DEACTIVATE, 0.6f, 0.8f);
            return;
        }

        buildScene(brainCentre, candidates, itemFor);
        sceneKey = key;

        world.playSound(brainCentre, Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.1f);
        world.playSound(brainCentre, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.5f);
        world.playSound(brainCentre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);

        final Location centre = sceneCentre;
        active = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= DURATION_TICKS || !alive(centre)) {
                    reapScene(); cancel();
                    if (active == this) active = null;
                    return;
                }
                double time = t / 20.0;
                double orbit = time * ORBIT_RATE;
                boolean intro = t < INTRO_TICKS;
                for (Planet pl : planets) {
                    if (!pl.body.isValid()) continue;
                    double grow = intro ? introGrow(t, pl.order) : 1.0;
                    Location resolved = stepPlanet(centre, pl, time, orbit, grow);
                    drawTendril(centre, resolved, pl, time, grow, t);
                    if (!intro) pollHit(centre, pl, resolved);
                }
                if (t % (PERIOD * 3) == 0) neighbourArcs(centre);
                ambientHaze(centre, time);
                t += PERIOD;
            }
        };
        active.runTaskTimer(plugin, 0L, PERIOD);
    }

    // ---- the gacha pull ------------------------------------------------------------

    /**
     * Play the pull animation for a successful sneak-extract: the non-chosen weapons dissolve inward to the Brain
     * while {@code chosenSpec} blooms out above it, then the whole scene reaps. Transitions whatever scene is live
     * at {@code brainCentre} (the command reveals/keeps it right before calling this); if the chosen weapon was
     * never on the shell (off-cap or collision-skipped) it is spawned at the Brain to bloom.
     */
    public void pull(Location brainCentre, WeaponSpec chosenSpec) {
        if (active != null) { active.cancel(); active = null; }
        if (planets.isEmpty() || sceneCentre == null) return; // nothing to pull from (command reveals first)

        final Location centre = sceneCentre;
        final World world = centre.getWorld();
        final Vector bloomAt = centre.toVector().add(new Vector(0, 1.15, 0));

        // Resolve the chosen body: reuse its floating planet, or spawn it fresh at the Brain.
        ItemDisplay chosen = null;
        Vector chosenStart = null;
        float chosenStartScale = CHOSEN_SCALE;
        for (Planet pl : planets) {
            if (pl.spec.id().equals(chosenSpec.id())) {
                chosen = pl.body;
                chosenStart = pl.lastPos.lengthSquared() > 0 ? pl.lastPos.clone() : bloomAt.clone();
                chosenStartScale = pl.scale;
            }
        }
        if (chosen == null) {
            chosen = spawnBody(centre, resolve(chosenSpec, lastItemFor), 0.05f);
            current.add(chosen);
            chosenStart = centre.toVector().clone();
            chosenStartScale = 0.05f;
        }
        final ItemDisplay chosenBody = chosen;
        final Vector startPos = chosenStart;
        final float startScale = chosenStartScale;
        final String chosenId = chosenSpec.id();

        world.playSound(centre, Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.6f);
        world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.1f);

        active = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!chosenBody.isValid() || !alive(centre)) {
                    reapScene(); cancel();
                    if (active == this) active = null;
                    return;
                }
                double time = t / 20.0;
                double p = Math.min(1.0, t / (double) PULL_TICKS);

                // Non-chosen weapons dissolve inward to the Brain, tendrils retracting behind them.
                for (Planet pl : planets) {
                    if (pl.spec.id().equals(chosenId) || !pl.body.isValid()) continue;
                    Vector start = pl.lastPos.lengthSquared() > 0 ? pl.lastPos : centre.toVector();
                    Vector pos = lerp(start, centre.toVector(), easeIn(p));
                    float s = (float) (pl.scale * (1.0 - easeIn(p)));
                    place(pl.body, centre, pos, yaw((float) (time * pl.spin)), s);
                    if (pl.hitbox.isValid()) pl.hitbox.remove(); // no batting mid-pull
                    drawTendrilPts(centre, pos.toLocation(world), 1.0 - p, TENDRIL);
                }

                // The chosen weapon blooms: rises to the bloom point, scales up, spins, sparks.
                Vector cpos = lerp(startPos, bloomAt, easeOut(p));
                float cs = (float) lerp(startScale, CHOSEN_SCALE, easeOut(p));
                place(chosenBody, centre, cpos, yaw((float) (time * 3.2)), cs);
                Location cloc = cpos.toLocation(world);
                drawBrightTendril(centre, cloc, time);
                if (t % PERIOD == 0) world.spawnParticle(Particle.END_ROD, cloc, 2, 0.12, 0.12, 0.12, 0.01);

                if (t >= PULL_TICKS) {
                    burst(cloc);
                    reapScene(); cancel();
                    if (active == this) active = null;
                    return;
                }
                t += PERIOD;
            }
        };
        active.runTaskTimer(plugin, 0L, PERIOD);
    }

    // ---- scene construction --------------------------------------------------------

    private void buildScene(Location brainCentre, List<WeaponSpec> candidates,
                            Function<WeaponSpec, ItemStack> itemFor) {
        this.sceneCentre = brainCentre.clone();
        this.lastItemFor = itemFor;
        int n = Math.min(candidates.size(), SAFETY_CEILING);
        for (int i = 0; i < n; i++) {
            WeaponSpec spec = candidates.get(i);
            Vector3f dir = shellDirection(i, n);
            double radius = SPHERE_RADIUS * (0.74 + 0.26 * frac01(i * 0.618f));
            ItemDisplay body = spawnBody(brainCentre, resolve(spec, itemFor), 0.01f); // scales in
            Interaction hitbox = spawnHitbox(brainCentre);
            current.add(body);
            current.add(hitbox);
            float scale = PLANET_SCALE * (0.85f + 0.35f * frac01(i * 0.399f));
            float spin  = 0.6f + 0.9f * frac01(i * 0.222f);
            planets.add(new Planet(body, hitbox, spec, dir, radius, scale, spin, i));
        }
    }

    /** Even golden-angle direction on the unit sphere for planet {@code i} of {@code n}. */
    private Vector3f shellDirection(int i, int n) {
        double y = 1.0 - (i + 0.5) / n * 2.0;                 // top → bottom
        double rXZ = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = i * GOLDEN_ANGLE;
        return new Vector3f((float) (Math.cos(theta) * rXZ), (float) y, (float) (Math.sin(theta) * rXZ));
    }

    // ---- per-frame motion + collision ----------------------------------------------

    /**
     * Advance one weapon a frame: orbit the home slot, add water-sway + the damped knock spring, collision-clamp
     * the result, apply it to the display, and move its hitbox. Returns the resolved world location.
     */
    private Location stepPlanet(Location centre, Planet pl, double time, double orbit, double grow) {
        World world = centre.getWorld();

        // Home slot = orbited shell direction * radius.
        double lx = pl.dir.x * pl.radius, ly = pl.dir.y * pl.radius, lz = pl.dir.z * pl.radius;
        double cos = Math.cos(orbit), sin = Math.sin(orbit);
        double hx = lx * cos - lz * sin, hz = lx * sin + lz * cos;
        Vector home = centre.toVector().add(new Vector(hx, ly, hz));

        // Buoyant water-sway on all axes.
        Vector sway = new Vector(
                SWAY_AMP * Math.sin(time * 0.7 + pl.p1),
                SWAY_AMP * 0.8 * Math.sin(time * 0.9 + pl.p2),
                SWAY_AMP * Math.sin(time * 0.6 + pl.p3));

        // Damped spring on the knock displacement (pulls a batted item home).
        Vector springAccel = pl.disp.clone().multiply(-KNOCK_SPRING).add(pl.vel.clone().multiply(-KNOCK_DAMP));
        pl.vel.add(springAccel.multiply(DT));
        pl.disp.add(pl.vel.clone().multiply(DT));

        Vector desired = home.add(sway).add(pl.disp);
        Location resolved = clampToClear(world, centre, desired.toLocation(world));
        // If we had to clamp, fold the correction into disp and stop the outward push (it "bumps" the wall).
        Vector corrected = resolved.toVector();
        if (corrected.distanceSquared(desired) > 1.0e-4) {
            pl.disp.add(corrected.clone().subtract(desired));
            pl.vel.multiply(0.2);
        }
        pl.lastPos.copy(corrected);

        place(pl.body, centre, corrected, yaw((float) (time * pl.spin)), (float) (pl.scale * grow));
        if (pl.hitbox.isValid()) {
            pl.hitbox.teleport(resolved.clone().subtract(0, HITBOX_SIZE / 2.0, 0));
        }
        return resolved;
    }

    /** Bat detection: a new left-click on the hitbox imparts an outward knockback impulse (cosmetic only). */
    private void pollHit(Location centre, Planet pl, Location resolved) {
        if (!pl.hitbox.isValid()) return;
        Interaction.PreviousInteraction atk = pl.hitbox.getLastAttack();
        if (atk == null || atk.getTimestamp() <= pl.lastAttack) return;
        pl.lastAttack = atk.getTimestamp();
        Vector out = resolved.toVector().subtract(centre.toVector());
        if (out.lengthSquared() < 1.0e-4) out = new Vector(0, 1, 0); else out.normalize();
        pl.vel.add(out.multiply(IMPULSE).add(new Vector(0, 0.4, 0)));
        World world = centre.getWorld();
        world.spawnParticle(Particle.DUST, resolved, 6, 0.15, 0.15, 0.15, 0, BEAD);
        world.playSound(resolved, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.4f);
    }

    /** Clamp {@code desired} back along the Brain→item ray to the last clear point; returns a usable location. */
    private Location clampToClear(World world, Location centre, Location desired) {
        if (clear(world, centre, desired)) return desired;
        Vector dir = desired.toVector().subtract(centre.toVector());
        double dist = dir.length();
        if (dist < 1.0e-4) return centre.clone().add(0, 0.5, 0);
        dir.normalize();
        RayTraceResult hit = world.rayTraceBlocks(centre, dir, dist, FluidCollisionMode.NEVER, true);
        double reach = hit != null && hit.getHitPosition() != null
                ? hit.getHitPosition().distance(centre.toVector()) : dist;
        double clamped = Math.max(0.6, reach - 0.4); // stop short of the wall, never inside the Brain
        return centre.clone().add(dir.multiply(clamped));
    }

    /** A spot is usable if its block is non-solid AND the Brain has an unobstructed line to it. */
    private boolean clear(World world, Location centre, Location spot) {
        if (spot.getBlock().getType().isSolid()) return false;
        Vector dir = spot.toVector().subtract(centre.toVector());
        double dist = dir.length();
        if (dist < 1.0e-4) return false;
        RayTraceResult hit = world.rayTraceBlocks(centre, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    // ---- spawning ------------------------------------------------------------------

    private ItemDisplay spawnBody(Location at, ItemStack stack, float scale) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowColorOverride(CarmenBrainVfx.GREEN);
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

    /** Apply a world position + rotation + scale to a display whose spawn origin is the Brain centre. */
    private void place(Display d, Location centre, Vector worldPos, Quaternionf rot, float scale) {
        Vector3f rel = new Vector3f(
                (float) (worldPos.getX() - centre.getX()),
                (float) (worldPos.getY() - centre.getY()),
                (float) (worldPos.getZ() - centre.getZ()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(PERIOD);
        d.setTransformation(new Transformation(rel, rot, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    // ---- links + ambiance ----------------------------------------------------------

    /** A curved green synapse tendril Brain→item with a travelling pulse bead + an occasional end spark. */
    private void drawTendril(Location centre, Location end, Planet pl, double time, double grow, int t) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.4, 0));
        int steps = 5;
        for (int s = 1; s <= steps; s++) spawnAt(world, bezier(a, control, b, (s / (double) steps) * grow), TENDRIL);
        double bu = frac01((float) (time * BEAD_SPEED + pl.p1)) * grow;
        spawnAt(world, bezier(a, control, b, bu), BEAD);
        if (grow > 0.75 && (t + pl.order) % (PERIOD * 6) == 0) {
            world.spawnParticle(Particle.END_ROD, b.toLocation(world), 1, 0.03, 0.03, 0.03, 0.0);
        }
    }

    /** Straight retracting tendril for the pull (no per-planet phase). */
    private void drawTendrilPts(Location centre, Location end, double grow, Particle.DustOptions dust) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.35, 0));
        for (int s = 1; s <= 5; s++) spawnAt(world, bezier(a, control, b, (s / 5.0) * grow), dust);
    }

    private void drawBrightTendril(Location centre, Location end, double time) {
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.32, 0));
        for (int s = 1; s <= 9; s++) spawnAt(world, bezier(a, control, b, s / 9.0), BEAD);
        double bu = frac01((float) (time * BEAD_SPEED * 2));
        world.spawnParticle(Particle.END_ROD, bezier(a, control, b, bu).toLocation(world), 1, 0, 0, 0, 0.0);
    }

    /** Faint arcs between neighbouring floating weapons — the "linked possibilities" web. */
    private void neighbourArcs(Location centre) {
        World world = centre.getWorld();
        for (int i = 0; i + 1 < planets.size(); i++) {
            Planet a = planets.get(i), b = planets.get(i + 1);
            if (a.lastPos.lengthSquared() == 0 || b.lastPos.lengthSquared() == 0) continue;
            for (int s = 1; s <= 3; s++) spawnAt(world, lerp(a.lastPos, b.lastPos, s / 4.0), ARC);
        }
    }

    private void ambientHaze(Location centre, double time) {
        World world = centre.getWorld();
        double bob = Math.sin(time * 1.3) * 0.1;
        world.spawnParticle(Particle.DUST, centre.clone().add(0, bob, 0), 3, 0.5, 0.4, 0.5, 0, HAZE);
        world.spawnParticle(Particle.GLOW, centre.clone().add(0, 0.2, 0), 1, 1.2, 0.9, 1.2, 0);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.22f, 1.5f);
    }

    private void burst(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, 40, 0.5, 0.5, 0.5, 0, BEAD);
        for (int i = 0; i < 24; i++) {
            double a = (2 * Math.PI * i) / 24;
            world.spawnParticle(Particle.END_ROD, at.clone().add(Math.cos(a), 0, Math.sin(a)), 1, 0, 0, 0, 0.02);
        }
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.3f);
        world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.6f);
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
        double p = Math.max(0.0, Math.min(1.0, (t - order) / (double) Math.max(1, INTRO_TICKS - order)));
        return easeOut(p);
    }

    private ItemStack resolve(WeaponSpec spec, Function<WeaponSpec, ItemStack> itemFor) {
        ItemStack it = itemFor != null ? itemFor.apply(spec) : null;
        return it != null ? it : new ItemStack(Material.NETHER_STAR);
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

    private String keyFor(Location centre, List<WeaponSpec> candidates) {
        StringBuilder sb = new StringBuilder(centre.getWorld().getName())
                .append(',').append(centre.getBlockX()).append(',').append(centre.getBlockY())
                .append(',').append(centre.getBlockZ()).append('|');
        for (WeaponSpec s : candidates) sb.append(s.id()).append(',');
        return sb.toString();
    }
}
