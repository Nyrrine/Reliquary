package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import com.nyrrine.reliquary.ego.SlashVfx;
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
 * <p>A slender cosmic caster-rod cut from a fragment of the night sky. Its shot is a <b>comet</b>: a
 * <b>particle-only</b> homing bolt — no entity rides it — that chases the nearest body ahead of it and
 * bursts on contact. A defender can still turn it: a <b>shield</b> raised into the shot negates it. The rod
 * holds several comets before it must recharge, and its wielder can <b>blink</b> to reposition and <b>lash</b>
 * the sky at close range to brand a foe for the comets to hunt.
 *
 * <ul>
 *   <li><b>Left-click — Star Lash.</b> A quick cosmic slash ({@link SlashVfx}) that cuts every foe it sweeps
 *       for {@value #LASH_DAMAGE} and <b>marks</b> them for {@value #MARK_MS}ms. The mark is a timed buff, not
 *       a charge: for its whole window a marked foe pulls comets in harder and <b>every</b> comet that lands
 *       on it takes {@code ×}{@value #MARK_DAMAGE_MULT} — the mark is never spent on a hit, it just lapses. It
 *       ties the kit together: lash up close, then loose a volley of comets that seek the branded body.</li>
 *   <li><b>Right-click — Comet.</b> Loose a fast homing comet, curving toward the nearest living body ahead of
 *       it (a marked one first). On contact it bursts for {@value #BOLT_DAMAGE} damage in a small starburst; a
 *       wall or a guttered-out light ends it in the same starburst but deals nothing; a raised shield negates
 *       it. A charge magazine shown as {@link EgoHud#pips pips}; when the pool is spent the rod recharges.</li>
 *   <li><b>Sneak + right-click — Blink.</b> Step a short way in the look direction to zone or reposition. It
 *       cannot pass through walls (it raytraces and lands just short of the first solid block). A pool of
 *       {@value #BLINK_MAX_CHARGES} blinks, recharged together when spent — its charges ride the same HUD line
 *       as the comets.</li>
 * </ul>
 *
 * <p>The comet is driven entirely by its own per-tick {@link Comet} runnable — it marches a cheap point, draws
 * a swirl of stars, and tests for a body by proximity ({@code firstHit}), exactly as the roster's other
 * particle projectiles do. Nothing it does spawns an entity, so there is nothing to sweep or orphan.
 *
 * <p>All state is small in-memory UUID maps, cleared on quit. Every runnable is lifetime-capped and cancels
 * itself when its owner goes offline, so nothing leaks and no work runs for non-wielders.
 */
public final class OurGalaxyWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> comets left before a recharge (0..{@link #COMET_MAX_CHARGES} + Constellation). */
    private final Map<UUID, Integer> charges = new HashMap<>();
    /** Wielder -> epoch-millis at which spent comets refill to full (absent = ready / not recharging). */
    private final Map<UUID, Long> rechargeAt = new HashMap<>();

    /** Wielder -> blinks left before a recharge (0..{@link #BLINK_MAX_CHARGES}). */
    private final Map<UUID, Integer> blinkCharges = new HashMap<>();
    /** Wielder -> epoch-millis at which spent blinks refill to full (absent = ready / not recharging). */
    private final Map<UUID, Long> blinkRechargeAt = new HashMap<>();

    /** Wielder -> epoch-millis until which post-blink fall damage is waived. */
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();

    /** Wielder -> epoch-millis of their last Star Lash; throttles the slash so a mashed left-click can't spam it. */
    private final Map<UUID, Long> lastLash = new HashMap<>();

    /** Victim UUID -> epoch-millis at which a Star Lash mark on them lapses. Pruned lazily and on disable. */
    private final Map<UUID, Long> markedUntil = new HashMap<>();

    /** Every comet currently in flight — cancelled on disable so nothing keeps drawing across a reload. */
    private final Set<Comet> liveComets = new HashSet<>();

    // Comet (right-click homing shot) tuning.
    private static final int    COMET_MAX_CHARGES = 4;      // a fuller magazine — comets are meant to be loosed freely
    private static final int    CONSTELLATION_CAP = 2;      // Constellation adds one comet per level, up to +2 (a 6-pool)
    private static final long   COMET_RECHARGE_MS = 3_000L; // once the pool is spent
    private static final double BOLT_DAMAGE       = 6.0;    // 3 hearts on contact

    // Star Lash (left-click) tuning.
    private static final long   LASH_CD_MS        = 500L;   // min gap between slashes so a mashed swing can't spam it
    private static final double LASH_RANGE        = 3.5;    // how far ahead the slash brands bodies
    private static final double LASH_ARC_DOT      = 0.30;   // within ~72° of the look line counts as swept
    private static final int    LASH_MARK_CAP     = 4;      // at most this many foes branded by one slash
    private static final double LASH_DAMAGE       = 4.5;    // the slash itself bites each swept foe
    private static final long   MARK_MS           = 6_000L; // how long a brand lingers — a timed buff, never spent on a hit
    private static final double MARK_DAMAGE_MULT  = 1.5;    // EVERY comet that hits a marked foe in the window bites half again as hard

    // Comet homing.
    private static final double HOMING        = 0.22; // per-tick lerp of heading toward an unmarked mark
    private static final double HOMING_MARKED = 0.38; // ...and harder toward a Star Lash-branded foe

    // Blink (sneak + right-click) tuning.
    private static final int    BLINK_MAX_CHARGES  = 3;      // a pool of blinks, mirroring the comet magazine
    private static final long   BLINK_RECHARGE_MS  = 5_000L; // the pool refills this long after it empties
    private static final double BLINK_DISTANCE     = 8.0;    // max reach in blocks
    private static final double BLINK_WALL_MARGIN  = 0.6;    // land this far short of a solid face
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
    private static final Particle.DustOptions MARK_DUST    = new Particle.DustOptions(C_PURPLE, 1.0f);

    public OurGalaxyWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "our_galaxy");
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
     * Left-click: Star Lash — a quick cosmic slash that brands the foes it sweeps, so the comets seek and
     * bite them harder. Throttled so a mashed swing can't spam the slash.
     */
    @Override
    public void onSwing(Player player) {
        if (!matches(player.getInventory().getItemInMainHand())) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastLash.get(id);
        if (last != null && now - last < LASH_CD_MS) return;
        lastLash.put(id, now);

        starLash(player, now);
    }

    /**
     * The comet pool for the rod held right now: the base charges plus one comet per Constellation level
     * (capped). Constellation is reinterpreted as a bigger magazine of comets before the recharge — exactly
     * the "+charges" fantasy from the enchant doc, correctly placed on the weapon that has a charge pool.
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
        liveComets.add(comet); // tracked so onDisable cancels an in-flight comet on reload
        comet.runTaskTimer(plugin, 0L, 1L);

        renderBar(player); // reflect the spent comet on the composed line at once
    }

    // ---- Star Lash (left-click brand) ---------------------------------------------

    /** Sweep a cosmic slash that bites and brands every foe roughly ahead within reach, so the comets hunt them. */
    private void starLash(Player player, long now) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();

        // The slash itself: a purple-to-starlight crescent swept across the look line. sweepEdge(false) keeps
        // the dust crescent + crit but drops the vanilla white SWEEP_ATTACK arc (Nyrrine: no sword-sweep look).
        SlashVfx.slash(plugin, eye, look)
                .arcSpan(140).reach(3.2)
                .colours(C_PURPLE, C_WHITE)
                .thickness(1.1f).duration(4).tilt(20)
                .sweepEdge(false)
                .play();
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.6f);
        world.playSound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.8f);

        // Cut and brand the bodies the slash swept: ahead of the wielder, within reach. The bite is dealt
        // through the pierce helper with zero armour-ignore — it clears the vanilla left-click swing's hurt
        // immunity (so the slash actually lands rather than being eaten by those i-frames), respects armour,
        // and is fenced/zero-knockback, all in one call.
        UUID id = player.getUniqueId();
        int branded = 0;
        for (Entity e : world.getNearbyEntities(eye, LASH_RANGE, LASH_RANGE, LASH_RANGE)) {
            if (branded >= LASH_MARK_CAP) break;
            if (e.getUniqueId().equals(id) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            Vector to = center(le).subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.01 || dist > LASH_RANGE) continue;
            if (to.multiply(1.0 / dist).dot(look) < LASH_ARC_DOT) continue; // not swept — behind or off to the side
            markedUntil.put(le.getUniqueId(), now + MARK_MS);
            plugin.weapons().pierceDamage(le, LASH_DAMAGE, 0.0, player);
            markFx(le);
            branded++;
        }
        if (branded > 0) {
            world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.4f, 1.9f); // a soft confirming shimmer on a brand
        }
    }

    /** A foe carries a live Star Lash brand right now (pruned lazily as it lapses). */
    private boolean isMarked(UUID victimId) {
        Long until = markedUntil.get(victimId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) { markedUntil.remove(victimId); return false; }
        return true;
    }

    // ---- HUD ----------------------------------------------------------------------

    /**
     * The always-on composed readout: the comet pool (pips, or the recharge counting down while dry) and the
     * blink pool (pips, or its recharge), on ONE line via {@link EgoHud#row}. Every path that used to send a
     * lone pip or cooldown now sends this, so a comet shot and a blink never flash one readout over the other.
     */
    private void renderBar(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        player.sendActionBar(EgoHud.row(cometReadout(player, id, now), blinkReadout(player, id, now)));
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

    /** The blink half of the line: pips while loaded, the recharge cooldown while the pool is dry. */
    private Component blinkReadout(Player player, UUID id, long now) {
        Long refill = blinkRechargeAt.get(id);
        int left = blinkCharges.getOrDefault(id, BLINK_MAX_CHARGES);
        if (left <= 0 && refill != null && now < refill) {
            return EgoHud.cooldown("Blink", refill - now, AZURE);
        }
        if (left <= 0 && refill != null) left = BLINK_MAX_CHARGES; // recharge elapsed; realised on the next blink
        return EgoHud.pips("Blink", STAR, left, BLINK_MAX_CHARGES);
    }

    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        renderBar(player);
        return true;
    }

    // ---- blink --------------------------------------------------------------------

    /** Sneak + right-click: a short line-of-sight blink in the look direction — never through walls. */
    private void blink(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Refill if the recharge has elapsed.
        Long refill = blinkRechargeAt.get(id);
        if (refill != null && now >= refill) {
            blinkCharges.put(id, BLINK_MAX_CHARGES);
            blinkRechargeAt.remove(id);
        }

        int left = blinkCharges.getOrDefault(id, BLINK_MAX_CHARGES);
        if (left <= 0) {
            renderBar(player); // the composed line already shows the blink recharge counting down
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
            // Nowhere to land — pressed against a wall. Don't spend a charge.
            player.sendActionBar(EgoHud.status("No room to blink…", FAINT));
            player.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.5f);
            return;
        }
        dest.setYaw(base.getYaw());
        dest.setPitch(base.getPitch());

        // Spend a blink; when the last one goes, start the recharge clock.
        left--;
        blinkCharges.put(id, left);
        if (left <= 0) blinkRechargeAt.put(id, now + BLINK_RECHARGE_MS);

        // BREEZE_ROD carries no durability, so this is a harmless no-op — kept for roster consistency.
        EgoDurability.wearMainHand(player);

        // Depart / arrive flourish.
        Location from = base.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, from, 12, 0.25, 0.5, 0.25, 0.02);
        world.spawnParticle(Particle.DUST, from, 8, 0.25, 0.5, 0.25, 0, SWIRL_PURPLE);
        world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.6f);

        fallGraceUntil.put(id, now + BLINK_FALL_GRACE_MS);
        player.teleport(dest);

        Location to = dest.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, to, 12, 0.25, 0.5, 0.25, 0.02);
        world.spawnParticle(Particle.DUST, to, 8, 0.25, 0.5, 0.25, 0, SWIRL_BLUE);
        world.spawnParticle(Particle.DUST, to, 5, 0.25, 0.5, 0.25, 0, SPARK_WHITE);
        world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        world.playSound(dest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.3f);

        renderBar(player); // reflect the spent blink on the composed line at once
    }


    // ---- the homing comet ---------------------------------------------------------

    /**
     * A single loosed comet, in flight. It marches one cheap point per tick (a couple of sub-steps so it
     * can't tunnel), curving toward the nearest living body ahead of it — a Star Lash-branded foe first and
     * harder — and bursts on contact, on a wall, or when its brief lifetime runs out. It is entirely
     * particle-driven: no entity rides it, and a body is met by proximity ({@link #firstHit()}).
     */
    private final class Comet extends BukkitRunnable {

        // Flight tuning — a fast, chasing comet. Speeds in blocks/tick.
        private static final double SPEED       = 0.9;  // brisker than a drifting star
        private static final double STEP        = 0.3;  // sub-step so it can't skip through a target/wall
        private static final double HIT_RADIUS  = 1.1;  // contact distance to a body
        private static final double SEEK_RADIUS = 8.0;  // how far ahead it looks for prey to home onto
        private static final double SEEK_DOT    = 0.30; // within ~72° of its heading counts as "ahead"
        private static final int    MAX_TICKS   = 70;   // hard lifetime cap — it always ends

        private final UUID ownerId;
        private final World world;
        private final Location pos;   // the comet's position
        private Vector dir;           // current heading (unit)
        private int ticks = 0;
        private int spin = 0;         // drives the galaxy-swirl trail

        Comet(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            this.pos = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
            this.dir = owner.getEyeLocation().getDirection().normalize();
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { endComet(); return; }

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
        }

        /**
         * Curve the heading toward the nearest living body that lies roughly ahead within seek range. A
         * Star Lash-branded foe wins over an unbranded one and is chased with a stronger lerp, so a marked
         * target is genuinely hunted down.
         */
        private void homeTowardMark() {
            LivingEntity bestMarked = null, bestAny = null;
            double markedDist = Double.MAX_VALUE, anyDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, SEEK_RADIUS, SEEK_RADIUS, SEEK_RADIUS)) {
                if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                Vector to = center(le).subtract(pos.toVector());
                double dist = to.length();
                if (dist < 0.01) continue;
                if (to.clone().multiply(1.0 / dist).dot(dir) < SEEK_DOT) continue; // behind / off to the side
                if (dist < anyDist) { anyDist = dist; bestAny = le; }
                if (isMarked(le.getUniqueId()) && dist < markedDist) { markedDist = dist; bestMarked = le; }
            }
            LivingEntity target = bestMarked != null ? bestMarked : bestAny;
            if (target == null) return;
            double homing = bestMarked != null ? HOMING_MARKED : HOMING;
            Vector to = center(target).subtract(pos.toVector()).normalize();
            dir = dir.clone().multiply(1.0 - homing).add(to.multiply(homing)).normalize();
        }

        /** The nearest living body (not the owner) within contact radius, else null. */
        private LivingEntity firstHit() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
                if (e.getUniqueId().equals(ownerId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                double d = center(le).subtract(pos.toVector()).lengthSquared();
                if (d < bestDist) { bestDist = d; best = le; }
            }
            return best;
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

        /**
         * Contact with a body: shield-negated if it blocks, else modest damage (harder on a branded foe).
         * The brand is a <b>timed buff</b>, not a charge — every comet that lands inside its {@link #MARK_MS}
         * window takes the {@value #MARK_DAMAGE_MULT}× bonus. It is never spent on a hit; it simply lapses.
         */
        private void impact(Player owner, LivingEntity victim) {
            if (blockedByShield(victim)) {
                Location c = center(victim).toLocation(world);
                world.playSound(c, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.9f);
                world.spawnParticle(Particle.CRIT, c, 8, 0.25, 0.35, 0.25, 0.05);
                world.spawnParticle(Particle.DUST, c, 6, 0.25, 0.35, 0.25, 0, SWIRL_BLUE);
                return;
            }
            boolean marked = isMarked(victim.getUniqueId());
            double dmg = marked ? BOLT_DAMAGE * MARK_DAMAGE_MULT : BOLT_DAMAGE;
            victim.damage(dmg, owner); // re-enters onHit dispatch; this weapon doesn't override it
            if (marked) markedStarburst(pos.clone()); else starburst(pos.clone());
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.3f);
            world.playSound(pos, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.5f);
        }

        /** A shield raised into the comet's path negates it (defender facing the incoming shot). */
        private boolean blockedByShield(LivingEntity victim) {
            if (!(victim instanceof Player vp) || !vp.isBlocking()) return false;
            // The comet travels along dir; the shield stops it when the blocker faces into it.
            return vp.getEyeLocation().getDirection().dot(dir) < -0.15;
        }

        /** Ran into a wall — a small starburst, no damage. */
        private void burstOnWall() {
            starburst(pos.clone());
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.6f, 1.4f);
        }

        /** Lifetime ran out with nothing struck — the comet quietly guttered out. */
        private void fizzle() {
            world.spawnParticle(Particle.END_ROD, pos, 5, 0.15, 0.15, 0.15, 0.01);
            world.spawnParticle(Particle.DUST, pos, 4, 0.15, 0.15, 0.15, 0, SWIRL_BLUE);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.6f);
        }

        /** Normal end: cancel the task and drop out of the live set. */
        private void endComet() {
            cancel();
            liveComets.remove(this);
        }

        /** Disable-time reap: cancel; the caller clears the live set. */
        void shutdown() {
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

    /** A brighter burst for a comet answering a brand — the same stars, thrown wider and whiter. */
    private static void markedStarburst(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.END_ROD, at, 16, 0.3, 0.3, 0.3, 0.05);
        w.spawnParticle(Particle.DUST, at, 14, 0.32, 0.32, 0.32, 0, SWIRL_PURPLE);
        w.spawnParticle(Particle.DUST, at, 10, 0.32, 0.32, 0.32, 0, SPARK_WHITE);
    }

    /** A foe is branded by Star Lash: a ring of purple stars settles onto them. */
    private void markFx(LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        victim.getWorld().spawnParticle(Particle.DUST, c, 10, 0.3, 0.4, 0.3, 0, MARK_DUST);
        victim.getWorld().spawnParticle(Particle.END_ROD, c, 3, 0.25, 0.35, 0.25, 0.01);
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
                    new EgoLore.Ability("[Left Click] Star Lash",
                            "A cosmic slash that cuts for 4 and",
                            "marks the foes it sweeps for 6",
                            "seconds. While marked, comets seek",
                            "them first and burst for half again",
                            "the damage."),
                    new EgoLore.Ability("[Right Click] Comet",
                            "Looses a homing comet that bursts",
                            "for 6 damage on contact. A raised",
                            "shield negates it. 4 charges; all",
                            "refill 3 seconds after the last",
                            "is spent."),
                    new EgoLore.Ability("[Shift + Right-click] Blink",
                            "Steps up to 8 blocks the way you look.",
                            "It stops short of walls rather than",
                            "passing through, and is not spent if",
                            "there is nowhere to land. Fall damage",
                            "is waived briefly on arrival. 3 blinks;",
                            "all refill 5 seconds after the last.")
            ));

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        charges.remove(id);
        rechargeAt.remove(id);
        blinkCharges.remove(id);
        blinkRechargeAt.remove(id);
        fallGraceUntil.remove(id);
        lastLash.remove(id);
        markedUntil.remove(id); // as a victim: drop any brand a quitter was carrying
    }

    @Override
    public void onDisable() {
        // Cancel every in-flight comet, then clear all state. No entities are ever spawned, so there is
        // nothing in the world to sweep.
        for (Comet comet : new ArrayList<>(liveComets)) {
            comet.shutdown();
        }
        liveComets.clear();
        charges.clear();
        rechargeAt.clear();
        blinkCharges.clear();
        blinkRechargeAt.clear();
        fallGraceUntil.clear();
        lastLash.clear();
        markedUntil.clear();
    }
}
