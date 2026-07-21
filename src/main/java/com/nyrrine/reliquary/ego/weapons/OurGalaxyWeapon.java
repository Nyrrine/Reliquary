package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Our Galaxy — "Child of the Galaxy" (Lobotomy Corp E.G.O, HE).
 *
 * <p>A slender cosmic caster-rod cut from a fragment of the night sky. Its shot is a <b>comet</b>: a real
 * {@link ShulkerBullet} rides the head of a swirling star-trail and chases the marked target down, homing
 * as it goes. But a comet can be swatted out of the air — if a player <b>strikes the bullet with a blade</b>
 * before it lands, the comet fizzles and deals no damage (a PvP parry). A defender can also raise a
 * <b>shield</b> into the shot to negate it. The rod holds <b>three comets</b> before it must recharge.
 *
 * <ul>
 *   <li><b>Right-click</b> — loose a fast homing comet led by a shulker bullet, curving toward the nearest
 *       living body ahead of it. On contact it bursts for {@value #BOLT_DAMAGE} damage in a small starburst;
 *       a wall or a guttered-out light ends it in the same starburst but deals nothing. Three charges, shown as
 *       {@link EgoHud#pips pips}; when all three are spent the rod recharges.</li>
 *   <li><b>Sneak + right-click</b> — <b>blink</b> a short step in the look direction to zone or reposition.
 *       It cannot pass through walls (it raytraces and lands just short of the first solid block).
 *       Cooldown shown via {@link EgoHud#cooldown}.</li>
 * </ul>
 *
 * <p><b>"Struck by a blade" detection is self-contained, no listener required.</b> Each comet is driven
 * entirely by its own per-tick {@link Comet} runnable — the shulker bullet is a pinned visual/hit marker,
 * teleported to the trail head every tick with its velocity zeroed so vanilla physics never move or collide
 * it. The runnable owns every way the comet can end (wall, contact, lifetime), and sets a {@code consumed}
 * flag before it removes the marker. So the parry test is simply: at the top of each tick, if the marker
 * has died and we did <em>not</em> consume it ourselves, someone knocked it out of the air — the payload is
 * cancelled and no damage lands. (Any strike counts as a parry, which is the intent; a shulker bullet we
 * hold stationary has no other way to disappear inside its brief lifetime.)
 *
 * <p>All state is small in-memory UUID maps, cleared on quit. Every runnable is lifetime-capped and cancels
 * itself when its owner goes offline (removing its marker), so nothing leaks and no work runs for non-wielders.
 */
public final class OurGalaxyWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;
    private final NamespacedKey cometKey; // stamped on our shulker bullets so they're identifiable

    /** Wielder -> comets left before a recharge (0..{@link #COMET_MAX_CHARGES}). */
    private final Map<UUID, Integer> charges = new HashMap<>();
    /** Wielder -> epoch-millis at which spent charges refill to full (absent = ready / not recharging). */
    private final Map<UUID, Long> rechargeAt = new HashMap<>();
    /** Wielder -> epoch-millis at which the blink is off cooldown. */
    private final Map<UUID, Long> blinkReadyAt = new HashMap<>();
    /** Wielder -> epoch-millis until which post-blink fall damage is waived. */
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();

    /** Every comet currently in flight — reaped on disable so an in-flight shulker bullet can't orphan. */
    private final Set<Comet> liveComets = new HashSet<>();

    // Comet (right-click homing shot) tuning.
    private static final int    COMET_MAX_CHARGES = 3;
    private static final int    CONSTELLATION_CAP = 2;      // Constellation adds one comet per level, up to +2 (a 5-pool)
    private static final long   COMET_RECHARGE_MS = 6_000L; // once all three are spent
    private static final double BOLT_DAMAGE       = 6.0;    // 3 hearts on contact

    // Blink (sneak + right-click) tuning.
    private static final long   BLINK_COOLDOWN_MS  = 25_000L; // 25s
    private static final double BLINK_DISTANCE     = 8.0;     // max reach in blocks
    private static final double BLINK_WALL_MARGIN  = 0.6;     // land this far short of a solid face
    private static final long   BLINK_FALL_GRACE_MS = 1_500L; // brief fall-damage waiver after a blink

    // Palette — cosmic purple / void-blue / starlight white.
    private static final TextColor NAME   = TextColor.color(0xB388FF); // nebula purple — lore primary
    private static final TextColor AZURE  = TextColor.color(0x6C8CFF); // void-blue accent / cooldowns / lore secondary
    private static final TextColor STAR   = TextColor.color(0xEDEBFF); // starlight highlight / pips
    private static final TextColor FAINT  = TextColor.color(0x7A7A96); // conditions / controls

    // Particle dusts — small, cosmic.
    private static final Color C_PURPLE = Color.fromRGB(0xB3, 0x88, 0xFF);
    private static final Color C_BLUE   = Color.fromRGB(0x6C, 0x8C, 0xFF);
    private static final Color C_WHITE  = Color.fromRGB(0xED, 0xEB, 0xFF);
    private static final Particle.DustOptions SWIRL_PURPLE = new Particle.DustOptions(C_PURPLE, 0.8f);
    private static final Particle.DustOptions SWIRL_BLUE   = new Particle.DustOptions(C_BLUE, 0.7f);
    private static final Particle.DustOptions SPARK_WHITE  = new Particle.DustOptions(C_WHITE, 0.6f);

    public OurGalaxyWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "our_galaxy");
        this.cometKey = new NamespacedKey(plugin, "our_galaxy_comet");
    }

    @Override
    public String id() {
        return "our_galaxy";
    }

    // ---- cast ---------------------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) blink(player);
        else fireComet(player);
    }

    /**
     * The comet pool for the rod held right now: the base three plus one comet per Constellation level (capped).
     * Constellation is reinterpreted as a bigger magazine of comets before the recharge — exactly the "+charges"
     * fantasy from the enchant doc, correctly placed on the weapon that actually has a charge pool.
     */
    private int maxComets(Player player) {
        int extra = Math.min(CONSTELLATION_CAP,
                EgoEnchants.level(player.getInventory().getItemInMainHand(), "constellation"));
        return COMET_MAX_CHARGES + Math.max(0, extra);
    }

    /** Right-click: loose a homing comet, gated by a charge magazine (grown by Constellation) that recharges when emptied. */
    private void fireComet(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        int max = maxComets(player);

        // Refill if the recharge has elapsed.
        Long refill = rechargeAt.get(id);
        if (refill != null && now >= refill) {
            charges.put(id, max);
            rechargeAt.remove(id);
            refill = null;
        }

        int left = charges.getOrDefault(id, max);
        if (left <= 0) {
            renderBar(player); // the composed line already shows the comet recharge counting down
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.7f);
            return;
        }

        // Spend a charge; when the last one goes, start the recharge clock.
        left--;
        charges.put(id, left);
        if (left <= 0) rechargeAt.put(id, now + COMET_RECHARGE_MS);

        // BREEZE_ROD carries no durability, so this is a harmless no-op — kept for roster consistency.
        EgoDurability.wearMainHand(player);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location muzzle = player.getEyeLocation();

        // A twinkling chime on cast + a puff of drifting stars at the rod tip.
        world.playSound(muzzle, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f + rng.nextFloat() * 0.3f);
        world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.5f, 1.4f + rng.nextFloat() * 0.3f);
        Location tip = muzzle.clone().add(muzzle.getDirection().multiply(0.6));
        world.spawnParticle(Particle.END_ROD, tip, 4, 0.08, 0.08, 0.08, 0.01);
        world.spawnParticle(Particle.DUST, tip, 3, 0.08, 0.08, 0.08, 0, SWIRL_PURPLE);

        Comet comet = new Comet(player);
        liveComets.add(comet); // tracked so onDisable can reap an in-flight comet's marker on reload
        comet.runTaskTimer(plugin, 0L, 1L);

        renderBar(player); // reflect the spent comet on the composed line at once
    }

    /**
     * The always-on composed readout: the comet pool (pips, or the recharge counting down while dry) and the
     * blink state (ready, or its cooldown), on ONE line via {@link EgoHud#row}. Every path that used to send a
     * lone pip or cooldown now sends this, so a comet shot and a blink never flash one readout over the other.
     */
    private void renderBar(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        player.sendActionBar(EgoHud.row(cometReadout(player, id, now), blinkReadout(id, now)));
    }

    /** The comet half of the line: pips while loaded, the recharge cooldown while the pool is dry. */
    private Component cometReadout(Player player, UUID id, long now) {
        int max = maxComets(player);
        Long refill = rechargeAt.get(id);
        int left = charges.getOrDefault(id, max);
        if (left <= 0 && refill != null && now < refill) {
            return EgoHud.cooldown("Comet", refill - now, AZURE);
        }
        if (left <= 0 && refill != null) left = max; // recharge elapsed; realised for real on the next fire
        return EgoHud.pips("Comet", STAR, left, max);
    }

    /** The blink half of the line: its cooldown while recharging, else ready. */
    private Component blinkReadout(UUID id, long now) {
        Long ready = blinkReadyAt.get(id);
        if (ready != null && now < ready) return EgoHud.cooldown("Blink", ready - now, AZURE);
        return EgoHud.ready("Blink", STAR);
    }

    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        renderBar(player);
        return true;
    }

    /** Sneak + right-click: a short line-of-sight blink in the look direction — never through walls. */
    private void blink(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long ready = blinkReadyAt.get(id);
        if (ready != null && now < ready) {
            renderBar(player); // composed line already shows the blink cooldown alongside the comet pool
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.3f);
            return;
        }

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Raytrace so a wall stops the blink; land just short of the first solid face.
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, BLINK_DISTANCE, FluidCollisionMode.NEVER, true);
        double maxTravel = BLINK_DISTANCE;
        if (rt != null) {
            maxTravel = eye.toVector().distance(rt.getHitPosition()) - BLINK_WALL_MARGIN;
        }
        maxTravel = Math.min(BLINK_DISTANCE, Math.max(0.0, maxTravel));

        // Scan back from the farthest reach to the first spot that can actually hold the player.
        Location base = player.getLocation();
        Location dest = null;
        for (double d = maxTravel; d >= 0.5; d -= 0.5) {
            Location cand = base.clone().add(dir.clone().multiply(d));
            if (Blink.canStand(cand)) { dest = cand; break; }
        }
        if (dest == null) {
            // Nowhere to land — pressed against a wall. Don't burn the cooldown.
            player.sendActionBar(EgoHud.status("No room to blink…", FAINT));
            player.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.5f);
            return;
        }
        dest.setYaw(base.getYaw());
        dest.setPitch(base.getPitch());

        // BREEZE_ROD carries no durability, so this is a harmless no-op — kept for roster consistency.
        EgoDurability.wearMainHand(player);

        // Depart / arrive flourish.
        Location from = base.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, from, 12, 0.25, 0.5, 0.25, 0.02);
        world.spawnParticle(Particle.DUST, from, 8, 0.25, 0.5, 0.25, 0, SWIRL_PURPLE);
        world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.6f);

        blinkReadyAt.put(id, now + BLINK_COOLDOWN_MS);
        fallGraceUntil.put(id, now + BLINK_FALL_GRACE_MS);
        player.teleport(dest);

        Location to = dest.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, to, 12, 0.25, 0.5, 0.25, 0.02);
        world.spawnParticle(Particle.DUST, to, 8, 0.25, 0.5, 0.25, 0, SWIRL_BLUE);
        world.spawnParticle(Particle.DUST, to, 5, 0.25, 0.5, 0.25, 0, SPARK_WHITE);
        world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        world.playSound(dest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.3f);

        player.sendActionBar(EgoHud.cooldown("Blink", BLINK_COOLDOWN_MS, AZURE));
    }


    // ---- the homing comet ---------------------------------------------------------

    /**
     * A single loosed comet, in flight. It marches one cheap point per tick (a couple of sub-steps so it
     * can't tunnel), curving toward the nearest living body ahead of it, and bursts on contact, on a wall,
     * or when its brief lifetime runs out. A real {@link ShulkerBullet} rides the trail head as its comet
     * body and its hit-marker: swat that bullet with a blade and the shot fizzles (see class docs).
     */
    private final class Comet extends BukkitRunnable {

        // Flight tuning — a fast, chasing comet. Speeds in blocks/tick.
        private static final double SPEED       = 0.9;  // brisker than a drifting star
        private static final double STEP        = 0.3;  // sub-step so it can't skip through a target/wall
        private static final double HIT_RADIUS  = 1.1;  // contact distance to a body
        private static final double SEEK_RADIUS = 8.0;  // how far ahead it looks for prey to home onto
        private static final double SEEK_DOT    = 0.30; // within ~72° of its heading counts as "ahead"
        private static final double HOMING      = 0.22; // per-tick lerp of heading toward the mark
        private static final int    MAX_TICKS   = 70;   // hard lifetime cap — it always ends

        private final UUID ownerId;
        private final World world;
        private final Location pos;   // the comet's position
        private Vector dir;           // current heading (unit)
        private int ticks = 0;
        private int spin = 0;         // drives the galaxy-swirl trail

        private final ShulkerBullet marker; // the comet body + strike target
        private boolean consumed = false;   // set true before WE remove the marker (see class docs)

        Comet(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            this.pos = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
            this.dir = owner.getEyeLocation().getDirection().normalize();

            // A pinned visual/hit marker — we drive its position; vanilla never moves or collides it.
            ShulkerBullet b = world.spawn(pos, ShulkerBullet.class, sb -> {
                sb.setGravity(false);
                sb.setSilent(true);
                sb.setTarget(null); // no native homing — we own the flight
                sb.setVelocity(new Vector(0, 0, 0));
                sb.setPersistent(false); // a hard crash can never save this marker bullet to disk
                sb.getPersistentDataContainer().set(cometKey, PersistentDataType.BYTE, (byte) 1);
                sb.addScoreboardTag("our_galaxy_comet");
            });
            this.marker = b;
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { cleanup(); endComet(); return; }

            // Parry test: the marker vanished and it wasn't us — a blade (or any strike) knocked it down.
            if (!consumed && (marker == null || marker.isDead() || !marker.isValid())) {
                parried();
                endComet();
                return;
            }

            if (++ticks > MAX_TICKS) { fizzle(); endComet(); return; }

            homeTowardMark();

            double moved = 0.0;
            while (moved < SPEED) {
                double step = Math.min(STEP, SPEED - moved);

                Location next = pos.clone().add(dir.clone().multiply(step));
                if (next.getBlock().getType().isSolid()) { burstOnWall(); endComet(); return; }

                pos.add(dir.clone().multiply(step));
                drawTrail();

                LivingEntity hit = firstHit();
                if (hit != null) { impact(owner, hit); endComet(); return; }

                moved += step;
            }

            syncMarker();
        }

        /** Curve the heading toward the nearest living body that lies roughly ahead within seek range. */
        private void homeTowardMark() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, SEEK_RADIUS, SEEK_RADIUS, SEEK_RADIUS)) {
                if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                if (e == marker) continue;
                Vector to = center(le).subtract(pos.toVector());
                double dist = to.length();
                if (dist < 0.01) continue;
                if (to.clone().multiply(1.0 / dist).dot(dir) < SEEK_DOT) continue; // behind / off to the side
                if (dist < bestDist) { bestDist = dist; best = le; }
            }
            if (best == null) return;
            Vector to = center(best).subtract(pos.toVector()).normalize();
            dir = dir.clone().multiply(1.0 - HOMING).add(to.multiply(HOMING)).normalize();
        }

        /** The nearest living body (not the owner, not our own marker) within contact radius, else null. */
        private LivingEntity firstHit() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
                if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                if (e == marker) continue;
                double d = center(le).subtract(pos.toVector()).lengthSquared();
                if (d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        /** Keep the shulker-bullet comet body glued to the trail head, motionless. */
        private void syncMarker() {
            if (marker != null && marker.isValid()) {
                marker.teleport(pos);
                marker.setVelocity(new Vector(0, 0, 0));
            }
        }

        /** One drifting star + a small galaxy swirl of dust trailing behind the comet. */
        private void drawTrail() {
            world.spawnParticle(Particle.END_ROD, pos, 1, 0, 0, 0, 0);
            Vector[] b = perp(dir);
            double ang = spin * 0.9;
            double rad = 0.22;
            Location sp = pos.clone()
                    .add(b[0].clone().multiply(Math.cos(ang) * rad))
                    .add(b[1].clone().multiply(Math.sin(ang) * rad));
            world.spawnParticle(Particle.DUST, sp, 1, 0, 0, 0, 0, (spin % 2 == 0) ? SWIRL_PURPLE : SWIRL_BLUE);
            if (spin % 5 == 0) {
                world.playSound(pos, Sound.BLOCK_BEACON_AMBIENT, 0.25f, 1.8f); // soft cosmic hum
            }
            spin++;
        }

        /** Contact with a body: shield-negated if it blocks, else modest damage + a small starburst. */
        private void impact(Player owner, LivingEntity victim) {
            consumed = true;
            if (blockedByShield(victim)) {
                Location c = center(victim).toLocation(world).add(0, 0, 0);
                world.playSound(c, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.9f);
                world.spawnParticle(Particle.CRIT, c, 8, 0.25, 0.35, 0.25, 0.05);
                world.spawnParticle(Particle.DUST, c, 6, 0.25, 0.35, 0.25, 0, SWIRL_BLUE);
                removeMarker();
                return;
            }
            victim.damage(BOLT_DAMAGE, owner); // re-enters onHit dispatch; this weapon doesn't override it
            starburst(pos.clone());
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.3f);
            world.playSound(pos, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.5f);
            removeMarker();
        }

        /** A shield raised into the comet's path negates it (defender facing the incoming shot). */
        private boolean blockedByShield(LivingEntity victim) {
            if (!(victim instanceof Player vp) || !vp.isBlocking()) return false;
            // The comet travels along dir; the shield stops it when the blocker faces into it.
            return vp.getEyeLocation().getDirection().dot(dir) < -0.15;
        }

        /** Ran into a wall — a small starburst, no damage. */
        private void burstOnWall() {
            consumed = true;
            starburst(pos.clone());
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.6f, 1.4f);
            removeMarker();
        }

        /** Lifetime ran out with nothing struck — the comet quietly guttered out. */
        private void fizzle() {
            consumed = true;
            world.spawnParticle(Particle.END_ROD, pos, 5, 0.15, 0.15, 0.15, 0.01);
            world.spawnParticle(Particle.DUST, pos, 4, 0.15, 0.15, 0.15, 0, SWIRL_BLUE);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.6f);
            removeMarker();
        }

        /** A blade knocked the comet out of the air — it bursts harmlessly, dealing no damage. */
        private void parried() {
            // marker is already gone; render the fizzle where it fell.
            world.spawnParticle(Particle.END_ROD, pos, 6, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticle(Particle.DUST, pos, 5, 0.2, 0.2, 0.2, 0, SWIRL_PURPLE);
            world.playSound(pos, Sound.ENTITY_SHULKER_BULLET_HURT, 0.7f, 1.2f);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.5f, 1.5f);
        }

        private void removeMarker() {
            if (marker != null && marker.isValid()) marker.remove();
        }

        /** Owner-offline shutdown: drop the marker so nothing lingers. */
        private void cleanup() {
            consumed = true;
            removeMarker();
        }

        /** Normal end: cancel the task and drop out of the live set. */
        private void endComet() {
            cancel();
            liveComets.remove(this);
        }

        /** Disable-time reap: drop the marker and cancel; the caller clears the live set. */
        void shutdown() {
            consumed = true;
            removeMarker();
            cancel();
        }
    }

    /** A small burst of stars — an END_ROD scatter woven with cosmic dust. */
    private static void starburst(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.END_ROD, at, 10, 0.2, 0.2, 0.2, 0.03);
        w.spawnParticle(Particle.DUST, at, 10, 0.25, 0.25, 0.25, 0, SWIRL_PURPLE);
        w.spawnParticle(Particle.DUST, at, 6, 0.25, 0.25, 0.25, 0, SPARK_WHITE);
    }

    private static Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /** Two unit vectors spanning the plane perpendicular to u (for the swirl trail). */
    private static Vector[] perp(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector a = n.clone().crossProduct(ref).normalize();
        Vector b = n.clone().crossProduct(a).normalize();
        return new Vector[]{a, b};
    }

    // ---- fall-damage waiver (brief, after a blink) --------------------------------

    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long until = fallGraceUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    // ---- item ---------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.OUR_GALAXY.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.OUR_GALAXY.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.OUR_GALAXY);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ---------------------------------------------------------------------

    // The Abnormality title line takes AZURE rather than the name's nebula purple: the shared tooltip needs
    // the weapon (primary) and the Abnormality (secondary) to read apart, and the rod's own void-blue accent
    // is the only colour in this palette far enough from NAME to do it — VOID sat a hair off the name, STAR
    // is the flavour's own off-white, and FAINT is the footer's grey. It is the colour the rod's cooldowns
    // already speak in, so the item and its action bar stay one voice.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Our Galaxy",
            "Child of the Galaxy",
            NAME,
            AZURE,
            List.of(
                    "There's a universe in a pebble.",
                    "Its light becomes the stars."
            ),
            List.of(
                    new EgoLore.Ability("[Right Click] Comet",
                            "Looses a homing comet that bursts",
                            "for 6 damage on contact. A blade can",
                            "strike it out of the air, and a raised",
                            "shield negates it. 3 charges; all",
                            "three refill 6 seconds after the",
                            "last is spent."),
                    new EgoLore.Ability("[Shift + Right-click] Blink",
                            "Steps up to 8 blocks the way you look.",
                            "It stops short of walls rather than",
                            "passing through, and is not spent if",
                            "there is nowhere to land. Fall damage",
                            "is waived briefly on arrival.",
                            "25 second cooldown.")
            ));

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        charges.remove(id);
        rechargeAt.remove(id);
        blinkReadyAt.remove(id);
        fallGraceUntil.remove(id);
    }

    @Override
    public void onDisable() {
        // Cancel every in-flight comet (dropping its marker), then belt-and-braces sweep every world for
        // any stray comet bullet carrying our tag/PDC so a shutdown can never orphan a shulker bullet.
        for (Comet comet : new ArrayList<>(liveComets)) {
            comet.shutdown();
        }
        liveComets.clear();
        sweepCometBullets();
    }

    /** Remove every comet shulker bullet carrying our tag or PDC across all loaded worlds. */
    private void sweepCometBullets() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ShulkerBullet.class)) {
                if (e.getScoreboardTags().contains("our_galaxy_comet")
                        || e.getPersistentDataContainer().has(cometKey, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }
}
