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
 * The extraction show above Carmen's Brain — a living <b>aquarium</b>. The ticket's reachable weapons drift
 * around the Brain like fish in a tank: each floats on its own independent layered-noise path (no lockstep, no
 * schools), gently contained to a volume around the Brain, softly pushing off any neighbour it drifts too close
 * to. A player can bat one with a left-click and it gets flung, then settles back into the drift.
 *
 * <p><b>Block-safety (hard invariant, moving or still):</b> a resolved position is accepted only if its block is
 * non-solid, the Brain has clear line-of-sight to it, AND it clears any floor below by {@link #FLOOR_CLEARANCE}
 * (a downward probe lifts it off the top face); otherwise the item holds its last good spot. Items always float
 * clear in open air — never resting on, kissing, or under a block surface.
 *
 * <p><b>Show arc:</b> a summon (items grow out from the Brain with green synapse tendrils), a tendril-despawn
 * flourish at the hand-off, a long tendril-free free-float ({@link #DURATION_TICKS}), then a staggered pop-out
 * outro (items wink away one by one) on the natural timeout. Any other exit (out of range, new preview, pull,
 * unload, disable) hard-reaps at once.
 *
 * <p><b>Hittable is cosmetic only:</b> the bat adds velocity + particles, never a grant/drop/spend, and item↔item
 * pushes are cosmetic too. Right-clicks run the proximity random-pull. All entities — displays AND hitboxes —
 * carry {@link #TAG}, are non-persistent, and are reaped on every exit + a cross-world sweep.
 */
public final class WellDisplay {

    /** Scoreboard tag on every entity this show spawns — distinct from the idle Brain tag, for orphan reaping. */
    public static final String TAG = "reliquary_well_preview";

    /** How long the tendril-free free-float lingers before the pop-out outro (ticks ≈ 45s). */
    public static final int DURATION_TICKS = 900;

    private static final int    PERIOD        = 2;     // ticks between physics/collision/particle frames (throttle)
    private static final double SPHERE_RADIUS = 7.5;   // the tank radius items wander within (placeholder)
    private static final double MIN_RADIUS    = 2.0;   // items are nudged out of this inner bubble around the Brain
    private static final int    SAFETY_CEILING = 64;   // hard ceiling on floating weapons (pathological tickets)
    private static final int    INTRO_TICKS   = 20;    // the summon (grow-out) length
    private static final int    OUTRO_LEN     = 160;   // the staggered pop-out window
    private static final int    OUTRO_START   = DURATION_TICKS - OUTRO_LEN;
    private static final int    PULL_TICKS    = 34;    // the sneak-extract pull animation
    private static final double WATCH_RANGE   = 40.0;  // reap the preview if no player stays this near the Brain

    private static final float  PLANET_SCALE  = 0.65f; // base weapon-model scale (varied per planet)
    private static final float  CHOSEN_SCALE  = 1.5f;  // the pulled weapon blooms to this
    private static final double GOLDEN_ANGLE  = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double BEAD_SPEED    = 0.6;   // synapse pulse travel (cycles/sec) during the summon
    private static final float  HITBOX_SIZE   = 0.7f;

    // Aquarium drift physics (all placeholders for the live tune).
    private static final double DT           = PERIOD / 20.0;
    private static final double WANDER_ACCEL = 2.4;    // per-item layered-noise steering
    private static final double ORBIT_BIAS   = 0.55;   // a faint slow whole-tank circulation on top of the wander
    private static final double CONTAIN      = 3.2;    // spring keeping items inside [MIN_RADIUS, SPHERE_RADIUS]
    private static final double SEP_DIST     = 1.7;    // item↔item personal space
    private static final double SEP_STRENGTH = 4.0;    // how hard they push apart
    private static final double DAMP         = 0.88;   // velocity damping per frame
    private static final double MAX_SPEED    = 3.0;    // blocks/sec speed cap
    private static final double IMPULSE      = 2.8;    // a bat's knockback punch (juicy)
    private static final double FLOOR_CLEARANCE = 0.7; // minimum gap above the top face of any block below
    private static final double FLOOR_PROBE  = FLOOR_CLEARANCE + 1.6;

    private static final Vector DOWN = new Vector(0, -1, 0);

    private static final Particle.DustOptions TENDRIL = new Particle.DustOptions(CarmenBrainVfx.GREEN, 0.6f);
    private static final Particle.DustOptions BEAD    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.95f);
    private static final Particle.DustOptions HAZE    = new Particle.DustOptions(CarmenBrainVfx.GREEN_SOFT, 0.7f);

    private final Plugin plugin;
    private BukkitRunnable active;
    private final List<Entity> current = new ArrayList<>();  // every entity this scene spawned (displays + hitboxes)
    private final List<Planet> planets = new ArrayList<>();

    private Location sceneCentre;                            // the Brain hover centre this scene is built around
    private String sceneKey;                                 // centre + candidate ids, for idempotent re-reveal
    private Function<WeaponSpec, ItemStack> lastItemFor;     // cached so pull() can resolve a chosen not on-shell

    public WellDisplay(Plugin plugin) { this.plugin = plugin; }

    /** One drifting weapon: its display + hitbox, spec, and its live aquarium motion state. */
    private static final class Planet {
        final ItemDisplay body;
        final Interaction hitbox;
        final WeaponSpec spec;
        final Vector anchor;        // its spread home the summon grows it out to
        final float scale;
        final float spin;
        final double[] wf;          // per-axis wander frequencies (independent per item)
        final double[] wp;          // per-axis wander phases
        final int order;
        final Vector vel = new Vector();
        final Vector pos = new Vector();
        final Vector lastPos = new Vector();
        long lastAttack = 0L;
        boolean popped = false;

        Planet(ItemDisplay body, Interaction hitbox, WeaponSpec spec, Vector anchor,
               float scale, float spin, int order) {
            this.body = body; this.hitbox = hitbox; this.spec = spec; this.anchor = anchor;
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

    /** Cancel any running scene and hard-reap its entities (new preview / pull start / out-of-range / disable). */
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
     * Show (or keep) the aquarium around the Brain hover centre {@code brainCentre} for the reachable
     * {@code candidates}. Idempotent: a right-click that asks for the same scene already on screen keeps it
     * drifting instead of restarting it. Empty pool → a small puff. {@code itemFor} resolves a spec to its weapon
     * model (null → a Nether Star placeholder).
     */
    public void reveal(Location brainCentre, List<WeaponSpec> candidates,
                       Function<WeaponSpec, ItemStack> itemFor) {
        String key = keyFor(brainCentre, candidates);
        if (active != null && key.equals(sceneKey)) return; // same scene already up — let it keep drifting

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

    // ---- the gacha pull ------------------------------------------------------------

    /**
     * Play the pull animation for a successful sneak-extract from whatever state the aquarium is in: the non-chosen
     * weapons dissolve inward to the Brain while {@code chosenSpec} blooms out above it, then the scene reaps. If
     * the chosen weapon isn't a live floating body (off-cap, popped, or never shown) it is spawned to bloom.
     */
    public void pull(Location brainCentre, WeaponSpec chosenSpec) {
        if (active != null) { active.cancel(); active = null; }
        if (planets.isEmpty() || sceneCentre == null) return; // nothing to pull from (command reveals first)

        final Location centre = sceneCentre;
        final World world = centre.getWorld();
        final Vector bloomAt = centre.toVector().add(new Vector(0, 1.15, 0));

        ItemDisplay chosen = null;
        Vector chosenStart = null;
        float chosenStartScale = CHOSEN_SCALE;
        for (Planet pl : planets) {
            if (pl.spec.id().equals(chosenSpec.id()) && pl.body.isValid() && !pl.popped) {
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
                    reapScene(); cancel(); if (active == this) active = null; return;
                }
                double time = t / 20.0;
                double p = Math.min(1.0, t / (double) PULL_TICKS);

                for (Planet pl : planets) {
                    if (pl.popped || pl.spec.id().equals(chosenId) || !pl.body.isValid()) continue;
                    Vector start = pl.lastPos.lengthSquared() > 0 ? pl.lastPos : centre.toVector();
                    Vector pos = lerp(start, centre.toVector(), easeIn(p));
                    float s = (float) (pl.scale * (1.0 - easeIn(p)));
                    place(pl.body, centre, pos, yaw((float) (time * pl.spin)), s);
                    if (pl.hitbox.isValid()) pl.hitbox.remove(); // no batting mid-pull
                    drawTendrilPts(centre, pos.toLocation(world), 1.0 - p);
                }

                Vector cpos = lerp(startPos, bloomAt, easeOut(p));
                float cs = (float) lerp(startScale, CHOSEN_SCALE, easeOut(p));
                place(chosenBody, centre, cpos, yaw((float) (time * 3.2)), cs);
                Location cloc = cpos.toLocation(world);
                drawBrightTendril(centre, cloc, time);
                if (t % PERIOD == 0) world.spawnParticle(Particle.END_ROD, cloc, 2, 0.12, 0.12, 0.12, 0.01);

                if (t >= PULL_TICKS) {
                    burst(cloc);
                    reapScene(); cancel(); if (active == this) active = null; return;
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
            double radius = SPHERE_RADIUS * (0.55 + 0.35 * frac01(i * 0.618f));
            Vector anchor = brainCentre.toVector().add(new Vector(dir.x * radius, dir.y * radius, dir.z * radius));
            ItemDisplay body = spawnBody(brainCentre, resolve(spec, itemFor), 0.01f); // scales in
            Interaction hitbox = spawnHitbox(brainCentre);
            current.add(body);
            current.add(hitbox);
            float scale = PLANET_SCALE * (0.85f + 0.35f * frac01(i * 0.399f));
            float spin  = 0.6f + 0.9f * frac01(i * 0.222f);
            planets.add(new Planet(body, hitbox, spec, anchor, scale, spin, i));
        }
    }

    /** Even golden-angle direction on the unit sphere for planet {@code i} of {@code n}. */
    private Vector3f shellDirection(int i, int n) {
        double y = 1.0 - (i + 0.5) / n * 2.0;
        double rXZ = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = i * GOLDEN_ANGLE;
        return new Vector3f((float) (Math.cos(theta) * rXZ), (float) y, (float) (Math.sin(theta) * rXZ));
    }

    // ---- the summon (grow-out) -----------------------------------------------------

    /** Items grow out from the Brain to their anchor with a green synapse tendril trailing each. */
    private void summon(Location centre, double time, int t) {
        World world = centre.getWorld();
        Vector c = centre.toVector();
        for (Planet pl : planets) {
            if (!pl.body.isValid()) continue;
            double grow = introGrow(t, pl.order);
            // Grow along the Brain→anchor ray, but never past the clear/floor-safe span (invariant holds while moving).
            Location cand = floorClear(world, clampToClear(world, centre, lerp(c, pl.anchor, grow).toLocation(world)));
            Vector pos = clear(world, centre, cand) ? cand.toVector() : pl.pos.clone();
            pl.pos.copy(pos);
            pl.lastPos.copy(pos);
            place(pl.body, centre, pos, yaw((float) (time * pl.spin)), (float) (pl.scale * grow));
            moveHitbox(pl, pos);
            drawTendril(centre, pos.toLocation(world), pl, time, grow, t);
        }
    }

    /** A one-shot dissolve of every summon tendril as the show hands off to the free-float. */
    private void tendrilFlourish(Location centre) {
        World world = centre.getWorld();
        Vector a = centre.toVector();
        for (Planet pl : planets) {
            Vector b = pl.lastPos.clone();
            Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.4, 0));
            for (int s = 1; s <= 8; s++) {
                Vector pt = bezier(a, control, b, s / 8.0);
                world.spawnParticle(Particle.DUST, pt.toLocation(world), 1, 0.02, 0.02, 0.02, 0, BEAD);
                if (s % 3 == 0) world.spawnParticle(Particle.END_ROD, pt.toLocation(world), 1, 0.02, 0.02, 0.02, 0.01);
            }
        }
        world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.9f);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.7f);
    }

    // ---- the free-float swarm ------------------------------------------------------

    /** One aquarium frame: independent wander + containment + item↔item separation, then collision/floor clamp. */
    private void updateSwarm(Location centre, double time) {
        int n = planets.size();
        Vector cc = centre.toVector();
        Vector[] accel = new Vector[n];

        // Pass 1 — per-item steering: layered-noise wander, a faint whole-tank orbit, soft containment.
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
                Vector tangent = new Vector(-rel.getZ(), 0, rel.getX()); // faint whole-tank circulation
                if (tangent.lengthSquared() > 1.0e-6) a.add(tangent.normalize().multiply(ORBIT_BIAS));
            }
            accel[i] = a;
        }

        // Pass 2 — separation: push apart any pair inside personal space (cheap, skips far pairs by dist²).
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

        // Pass 3 — integrate + collision/floor clamp (accept only a fully-clear spot, else hold).
        World world = centre.getWorld();
        for (int i = 0; i < n; i++) {
            Planet pl = planets.get(i);
            if (pl.popped || !pl.body.isValid()) continue;
            pl.vel.add(accel[i].multiply(DT)).multiply(DAMP);
            capSpeed(pl.vel);
            Vector desired = pl.pos.clone().add(pl.vel.clone().multiply(DT));
            Location cand = floorClear(world, clampToClear(world, centre, desired.toLocation(world)));
            if (clear(world, centre, cand)) {
                pl.pos.copy(cand.toVector());
            } else {
                pl.vel.multiply(0.3); // blocked — hold last good spot, bleed momentum
            }
            pl.lastPos.copy(pl.pos);
            place(pl.body, centre, pl.pos, yaw((float) (time * pl.spin)), pl.scale);
            moveHitbox(pl, pl.pos);
            pollHit(centre, pl);
        }
    }

    /** Bat detection: a new left-click on the hitbox flings the item with an outward impulse (cosmetic only). */
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
        world.spawnParticle(Particle.DUST, at, 16, 0.3, 0.3, 0.3, 0, BEAD);
        world.spawnParticle(Particle.END_ROD, at, 8, 0.25, 0.25, 0.25, 0.06);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.9f, 1.3f);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.2f);
    }

    // ---- the pop-out outro ---------------------------------------------------------

    /** Wink away any item whose staggered pop time has arrived (natural-timeout ending only). */
    private void popDue(Location centre, int t) {
        int n = planets.size();
        World world = centre.getWorld();
        for (Planet pl : planets) {
            if (pl.popped) continue;
            int due = OUTRO_START + (int) (pl.order / (double) Math.max(1, n) * (OUTRO_LEN * 0.85));
            if (t < due) continue;
            pl.popped = true;
            Location at = pl.lastPos.toLocation(world);
            world.spawnParticle(Particle.DUST, at, 8, 0.15, 0.15, 0.15, 0, BEAD);
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
        double clamped = Math.max(0.6, reach - 0.4);
        return centre.clone().add(dir.multiply(clamped));
    }

    /** Lift {@code loc} so it clears the top face of any block below it by {@link #FLOOR_CLEARANCE}. */
    private Location floorClear(World world, Location loc) {
        RayTraceResult down = world.rayTraceBlocks(loc, DOWN, FLOOR_PROBE, FluidCollisionMode.NEVER, true);
        if (down != null && down.getHitPosition() != null) {
            double minY = down.getHitPosition().getY() + FLOOR_CLEARANCE;
            if (loc.getY() < minY) { Location up = loc.clone(); up.setY(minY); return up; }
        }
        return loc;
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

    private void moveHitbox(Planet pl, Vector pos) {
        if (pl.hitbox.isValid()) {
            pl.hitbox.teleport(new Location(pl.hitbox.getWorld(), pos.getX(),
                    pos.getY() - HITBOX_SIZE / 2.0, pos.getZ()));
        }
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

    /** A curved green synapse tendril Brain→item with a travelling pulse bead — summon only. */
    private void drawTendril(Location centre, Location end, Planet pl, double time, double grow, int t) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.4, 0));
        for (int s = 1; s <= 5; s++) spawnAt(world, bezier(a, control, b, (s / 5.0) * grow), TENDRIL);
        double bu = frac01((float) (time * BEAD_SPEED + pl.wp[0])) * grow;
        spawnAt(world, bezier(a, control, b, bu), BEAD);
    }

    /** Straight retracting tendril for the pull (no per-planet phase). */
    private void drawTendrilPts(Location centre, Location end, double grow) {
        if (grow <= 0.02) return;
        World world = centre.getWorld();
        Vector a = centre.toVector();
        Vector b = end.toVector();
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, 0.35, 0));
        for (int s = 1; s <= 5; s++) spawnAt(world, bezier(a, control, b, (s / 5.0) * grow), TENDRIL);
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

    private void ambientHaze(Location centre, double time) {
        World world = centre.getWorld();
        double bob = Math.sin(time * 1.3) * 0.1;
        world.spawnParticle(Particle.DUST, centre.clone().add(0, bob, 0), 2, 0.6, 0.5, 0.6, 0, HAZE);
        world.spawnParticle(Particle.GLOW, centre.clone().add(0, 0.2, 0), 1, 1.6, 1.2, 1.6, 0);
        world.playSound(centre, Sound.BLOCK_BEACON_AMBIENT, 0.18f, 1.5f);
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
        double p = Math.max(0.0, Math.min(1.0, (t - order * 0.4) / (double) Math.max(1, INTRO_TICKS - 4)));
        return easeOut(p);
    }

    private void capSpeed(Vector v) {
        double s2 = v.lengthSquared();
        if (s2 > MAX_SPEED * MAX_SPEED) v.multiply(MAX_SPEED / Math.sqrt(s2));
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
