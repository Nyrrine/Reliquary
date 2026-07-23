package com.nyrrine.reliquary.extraction;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
 * centre and the ticket's reachable weapons float around it like planets on a spread shell ({@link #SPHERE_RADIUS}),
 * each an {@link ItemDisplay} linked back to the Brain by a curved green synapse tendril with travelling pulses —
 * "the possibilities, linked." There are no odds and no ranked hub; every reachable weapon just floats there.
 *
 * <p>Every candidate spot is <b>collision-validated</b> before it spawns: the block must be non-solid and the
 * Brain must have clear line-of-sight to it, so no item ever sits inside, behind, or drifts through a wall; a
 * blocked candidate is retried at a few angles/radii and skipped if it still can't find air. Motion is kept
 * small so an item never leaves its validated cell.
 *
 * <p>{@link #reveal} builds/keeps the idle preview (a "wow" scale-in + ambient synapse pulses that linger for
 * {@link #DURATION_TICKS}); {@link #pull} plays the gacha pull — the non-chosen weapons dissolve inward to the
 * Brain while the chosen one blooms out. All entities are {@link #TAG}-tagged + non-persistent and reaped on
 * every exit (timeout, a new preview, pull resolve, the Brain leaving/unloading, or disable + a cross-world tag
 * sweep), with a preview tag distinct from the idle Brain's so the two reaps never fight.
 */
public final class WellDisplay {

    /** Scoreboard tag on every entity this show spawns — distinct from the idle Brain tag, for orphan reaping. */
    public static final String TAG = "reliquary_well_preview";

    /** How long an idle preview lingers before it dissolves (ticks). */
    public static final int DURATION_TICKS = 220;

    private static final double SPHERE_RADIUS = 2.5;   // the "5x5" shell the planets spread over (placeholder)
    private static final int    MAX_PLANETS   = 8;     // cap the floating weapons for readability + perf
    private static final int    INTRO_TICKS   = 16;    // the scale-in "wow" opening
    private static final int    PULL_TICKS    = 30;    // the sneak-extract pull animation
    private static final double WATCH_RANGE   = 32.0;  // reap the preview if no player stays this near the Brain

    private static final float  PLANET_SCALE  = 0.42f; // base weapon-model scale (varied per planet)
    private static final float  CHOSEN_SCALE  = 0.95f; // the pulled weapon blooms to this
    private static final double BOB_AMP       = 0.05;  // tiny — stays well inside the validated air cell
    private static final double GOLDEN_ANGLE  = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double BEAD_SPEED    = 0.6;   // synapse pulse travel (cycles/sec along a tendril)

    private static final Particle.DustOptions TENDRIL = new Particle.DustOptions(CarmenBrainVfx.GREEN, 0.6f);
    private static final Particle.DustOptions BEAD    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.95f);
    private static final Particle.DustOptions HAZE    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.7f);

    private final Plugin plugin;
    private BukkitRunnable active;
    private final List<Display> current = new ArrayList<>();  // this scene's entities, so reap() cleans them all
    private final List<Planet> planets = new ArrayList<>();   // the validated floating weapons this scene

    private Location sceneCentre;                             // the Brain hover centre this scene is built around
    private String sceneKey;                                  // centre + candidate ids, for idempotent re-reveal
    private Function<WeaponSpec, ItemStack> lastItemFor;      // cached so pull() can resolve a chosen not on-shell

    public WellDisplay(Plugin plugin) { this.plugin = plugin; }

    /** One floating weapon: its display, spec, validated offset from the Brain centre, and its gentle motion. */
    private record Planet(ItemDisplay body, WeaponSpec spec, Vector3f base,
                          float fullScale, float spinSpeed, float bobRate, float phase, int order) {}

    // ---- lifecycle -----------------------------------------------------------------

    /** Cancel any running scene and reap its entities (new preview / pull start / out-of-range / disable). */
    public void stop() {
        if (active != null) { active.cancel(); active = null; }
        reapScene();
    }

    /** Plugin disable: stop the live scene, then sweep any tagged orphan across every world. */
    public void disable() {
        stop();
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
        }
    }

    private void reapScene() {
        for (Display d : current) if (d.isValid()) d.remove();
        current.clear();
        planets.clear();
        sceneCentre = null;
        sceneKey = null;
    }

    // ---- the idle preview ----------------------------------------------------------

    /**
     * Show (or keep) the idle solar-system preview around the Brain hover centre {@code brainCentre} for the
     * reachable {@code candidates} (best-effort collision-validated). Idempotent: a right-click that asks for the
     * same scene already on screen keeps it animating instead of restarting it. Empty pool → a small puff.
     * {@code itemFor} resolves a spec to its weapon model (null → a Nether Star placeholder).
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

        // The "wow" opening SFX — the show grows out over the first INTRO_TICKS.
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
                boolean intro = t < INTRO_TICKS;
                for (Planet pl : planets) {
                    if (!pl.body.isValid()) continue;
                    double grow = intro ? introGrow(t, pl.order) : 1.0;
                    animatePlanet(pl, time, grow);
                    drawTendril(centre, pl, time, grow, t);
                }
                if (t % 3 == 0) ambientHaze(centre, time);
                t++;
            }
        };
        active.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- the gacha pull ------------------------------------------------------------

    /**
     * Play the pull animation for a successful sneak-extract: the non-chosen weapons dissolve inward to the Brain
     * while {@code chosenSpec} blooms out, then the whole scene reaps. Runs on whatever scene is live at
     * {@code brainCentre} (the command reveals/keeps it right before calling this); if the chosen weapon was
     * never on the shell (skipped or off-cap) it is spawned fresh at the Brain to bloom.
     */
    public void pull(Location brainCentre, WeaponSpec chosenSpec) {
        // Take over the live idle loop but keep its entities to transition them.
        if (active != null) { active.cancel(); active = null; }
        if (planets.isEmpty() || sceneCentre == null) return; // nothing to pull from (shouldn't happen — reveal first)

        final Location centre = sceneCentre;
        final World world = centre.getWorld();

        // Resolve the chosen body: reuse its floating planet, or spawn it fresh at the Brain if it wasn't shown.
        ItemDisplay chosen = null;
        Vector3f chosenBase = null;
        float chosenStart = CHOSEN_SCALE;
        for (Planet pl : planets) {
            if (pl.spec.id().equals(chosenSpec.id())) { chosen = pl.body; chosenBase = new Vector3f(pl.base); chosenStart = pl.fullScale; }
        }
        if (chosen == null) {
            ItemStack it = resolve(chosenSpec, lastItemFor);
            chosenBase = new Vector3f(0f, 0.15f, 0f);
            chosenStart = 0.05f;
            chosen = spawnBody(centre, it, chosenStart);
            current.add(chosen);
        }
        final ItemDisplay chosenBody = chosen;
        final Vector3f chosenAt = chosenBase;
        final float startScale = chosenStart;
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

                // Non-chosen weapons dissolve inward toward the Brain, tendrils retracting behind them.
                for (Planet pl : planets) {
                    if (pl.spec.id().equals(chosenId) || !pl.body.isValid()) continue;
                    float s = (float) (pl.fullScale * (1.0 - easeIn(p)));
                    Vector3f pos = new Vector3f(pl.base).mul((float) (1.0 - easeIn(p)));
                    applyTransform(pl.body, pos, yaw((float) (time * pl.spinSpeed)), s);
                    drawTendril(centre, pl, time, 1.0 - p, t);
                }

                // The chosen weapon blooms: scales up, rises, spins faster, sparks brighter.
                float cs = (float) lerp(startScale, CHOSEN_SCALE * 1.05, easeOut(p));
                float rise = (float) (0.55 * easeOut(p));
                applyTransform(chosenBody, new Vector3f(chosenAt.x, chosenAt.y + rise, chosenAt.z),
                        yaw((float) (time * 3.2)), cs);
                Location cloc = centre.clone().add(chosenAt.x, chosenAt.y + rise, chosenAt.z);
                drawBrightTendril(centre, cloc, time);
                if (t % 2 == 0) world.spawnParticle(Particle.END_ROD, cloc, 2, 0.12, 0.12, 0.12, 0.01);

                if (t >= PULL_TICKS) {
                    burst(cloc);
                    reapScene(); cancel();
                    if (active == this) active = null;
                    return;
                }
                t++;
            }
        };
        active.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- scene construction --------------------------------------------------------

    /** Build the validated floating weapons around the Brain centre. Populates {@link #planets}/{@link #current}. */
    private void buildScene(Location brainCentre, List<WeaponSpec> candidates,
                            Function<WeaponSpec, ItemStack> itemFor) {
        this.sceneCentre = brainCentre.clone();
        this.lastItemFor = itemFor;
        World world = brainCentre.getWorld();
        int n = Math.min(candidates.size(), MAX_PLANETS);
        for (int i = 0; i < n; i++) {
            WeaponSpec spec = candidates.get(i);
            Vector3f base = validatedSpot(world, brainCentre, i, n);
            if (base == null) continue; // no clear air/line-of-sight for this one — skip it, never clip a wall
            ItemDisplay body = spawnBody(brainCentre, resolve(spec, itemFor), 0.01f); // starts tiny, scales in
            current.add(body);
            float scale = PLANET_SCALE * (0.9f + 0.32f * frac01(i * 0.399f));
            float spin  = 0.6f + 0.9f * frac01(i * 0.618f);
            float bob   = 0.09f + 0.06f * frac01(i * 0.211f);
            planets.add(new Planet(body, spec, base, scale, spin, bob, (float) (i * 1.7), i));
        }
    }

    /**
     * A collision-clear offset from the Brain centre for planet {@code i} of {@code n}, spread by golden angle over
     * a shell. Returns the first spot (of a few radius/angle fallbacks) whose block is non-solid AND has clear
     * line-of-sight from the Brain; {@code null} if none is clear (caller skips the weapon).
     */
    private Vector3f validatedSpot(World world, Location centre, int i, int n) {
        double t = (i + 0.5) / n;
        double y = 1.0 - t * 2.0;                       // even spread top→bottom
        double rXZ = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = i * GOLDEN_ANGLE;
        double baseRadius = SPHERE_RADIUS * (0.74 + 0.26 * frac01(i * 0.618f));

        double[][] tries = {{1.0, 0.0}, {0.85, 0.0}, {0.7, 0.0}, {1.0, 0.55}, {0.85, -0.55}, {0.7, 1.1}};
        for (double[] adj : tries) {
            double r = baseRadius * adj[0];
            double a = theta + adj[1];
            double dx = Math.cos(a) * rXZ * r;
            double dz = Math.sin(a) * rXZ * r;
            double dy = y * r;
            Location spot = centre.clone().add(dx, dy, dz);
            if (clear(world, centre, spot)) return new Vector3f((float) dx, (float) dy, (float) dz);
        }
        return null;
    }

    /** A spot is usable if its block is non-solid AND the Brain has an unobstructed line to it. */
    private boolean clear(World world, Location centre, Location spot) {
        if (spot.getBlock().getType().isSolid()) return false;
        Vector dir = spot.toVector().subtract(centre.toVector());
        double dist = dir.length();
        if (dist < 1.0e-4) return false;
        RayTraceResult hit = world.rayTraceBlocks(centre, dir.normalize(), dist,
                FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null; // any solid before the spot blocks it
    }

    private ItemDisplay spawnBody(Location at, ItemStack stack, float scale) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowColorOverride(CarmenBrainVfx.GREEN);
            d.setGlowing(true);
            d.setInterpolationDuration(2);
            d.addScoreboardTag(TAG);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    // ---- per-frame motion + links --------------------------------------------------

    /** A planet's idle motion: self-spin + a tiny bob, scaled by {@code grow} during the intro. */
    private void animatePlanet(Planet pl, double time, double grow) {
        double bob = Math.sin(time * pl.bobRate * Math.PI + pl.phase) * BOB_AMP;
        Vector3f pos = new Vector3f(pl.base.x, (float) (pl.base.y + bob), pl.base.z);
        applyTransform(pl.body, pos, yaw((float) (time * pl.spinSpeed)), (float) (pl.fullScale * grow));
    }

    /** A curved green synapse tendril Brain→planet, with a travelling pulse bead and an occasional end spark. */
    private void drawTendril(Location centre, Planet pl, double time, double grow, int t) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        double bob = Math.sin(time * pl.bobRate * Math.PI + pl.phase) * BOB_AMP;
        Vector a = centre.toVector();
        Vector b = centre.clone().add(pl.base.x, pl.base.y + bob, pl.base.z).toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.28, 0));

        int steps = 7;
        for (int s = 1; s <= steps; s++) {
            double u = (s / (double) steps) * grow;
            spawnAt(world, bezier(a, control, b, u), TENDRIL);
        }
        // Bright pulse bead travelling out from the Brain (phase-offset per planet).
        double bu = frac01((float) (time * BEAD_SPEED + pl.phase)) * grow;
        spawnAt(world, bezier(a, control, b, bu), BEAD);
        // Occasional END_ROD spark at the item end.
        if (grow > 0.75 && (t + pl.order) % 11 == 0) {
            world.spawnParticle(Particle.END_ROD, b.toLocation(world), 1, 0.03, 0.03, 0.03, 0.0);
        }
    }

    /** A brighter, denser tendril to the chosen weapon during the pull. */
    private void drawBrightTendril(Location centre, Location end, double time) {
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.32, 0));
        int steps = 9;
        for (int s = 1; s <= steps; s++) {
            spawnAt(world, bezier(a, control, b, s / (double) steps), BEAD);
        }
        double bu = frac01((float) (time * BEAD_SPEED * 2));
        world.spawnParticle(Particle.END_ROD, bezier(a, control, b, bu).toLocation(world), 1, 0, 0, 0, 0.0);
    }

    private void ambientHaze(Location centre, double time) {
        World world = centre.getWorld();
        double bob = Math.sin(time * 1.3) * 0.1;
        world.spawnParticle(Particle.DUST, centre.clone().add(0, bob, 0), 2, 0.4, 0.35, 0.4, 0, HAZE);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.25f, 1.5f);
    }

    private void burst(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, 30, 0.4, 0.4, 0.4, 0, BEAD);
        for (int i = 0; i < 20; i++) {
            double a = (2 * Math.PI * i) / 20;
            world.spawnParticle(Particle.END_ROD, at.clone().add(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9),
                    1, 0, 0, 0, 0.02);
        }
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.3f);
        world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.6f);
    }

    // ---- small helpers -------------------------------------------------------------

    /** The Brain centre's chunk is loaded and a player is still near enough to watch the show. */
    private boolean alive(Location centre) {
        World w = centre.getWorld();
        if (w == null || !w.isChunkLoaded(centre.getBlockX() >> 4, centre.getBlockZ() >> 4)) return false;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(centre) <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    /** Staggered ease-in scale for planet {@code order} during the intro. */
    private double introGrow(int t, int order) {
        double start = order; // one tick of stagger per planet
        double p = Math.max(0.0, Math.min(1.0, (t - start) / (double) (INTRO_TICKS - order)));
        return easeOut(p);
    }

    private ItemStack resolve(WeaponSpec spec, Function<WeaponSpec, ItemStack> itemFor) {
        ItemStack it = itemFor != null ? itemFor.apply(spec) : null;
        return it != null ? it : new ItemStack(Material.NETHER_STAR);
    }

    private void applyTransform(Display d, Vector3f translation, Quaternionf rot, float scale) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(translation, rot, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private Quaternionf yaw(float radians) {
        return new Quaternionf(new AxisAngle4f(radians, 0f, 1f, 0f));
    }

    private void spawnAt(World world, Vector v, Particle.DustOptions dust) {
        world.spawnParticle(Particle.DUST, v.toLocation(world), 1, 0, 0, 0, 0, dust);
    }

    /** Quadratic Bézier a→control→b at parameter {@code u}. */
    private Vector bezier(Vector a, Vector c, Vector b, double u) {
        double mu = 1.0 - u;
        double x = mu * mu * a.getX() + 2 * mu * u * c.getX() + u * u * b.getX();
        double y = mu * mu * a.getY() + 2 * mu * u * c.getY() + u * u * b.getY();
        double z = mu * mu * a.getZ() + 2 * mu * u * c.getZ() + u * u * b.getZ();
        return new Vector(x, y, z);
    }

    private static double easeOut(double x) { double m = 1 - x; return 1 - m * m * m; }
    private static double easeIn(double x)  { return x * x * x; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Fractional part, always in [0,1). */
    private static float frac01(float x) { return (float) (x - Math.floor(x)); }

    private String keyFor(Location centre, List<WeaponSpec> candidates) {
        StringBuilder sb = new StringBuilder(centre.getWorld().getName())
                .append(',').append(centre.getBlockX()).append(',').append(centre.getBlockY())
                .append(',').append(centre.getBlockZ()).append('|');
        for (WeaponSpec s : candidates) sb.append(s.id()).append(',');
        return sb.toString();
    }
}
