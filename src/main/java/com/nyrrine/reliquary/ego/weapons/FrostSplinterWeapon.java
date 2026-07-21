package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Frost Splinter — "The Snow Queen" (Lobotomy Corp E.G.O Equipment, HE).
 *
 * <p>A spear of snow, straight and icy and beautiful, with nothing at all where its heart should be. It
 * is a cold, clean, brittle thing: it does not burn or tear, it simply <em>takes the moment away</em> from
 * whoever it touches. Everything it does is a variation on one idea — stopping someone where they stand.
 *
 * <ul>
 *   <li><b>[Passive] The First Kiss</b> — the last foe the spear touched is remembered as the wielder's
 *       <i>dueler</i>. Exactly one at a time: each landed strike marks a new one and forgets the old.
 *       Strikes against the current dueler bite {@link #DUELER_DAMAGE_MULT +5%} harder. The bonus is
 *       applied through {@code event.setDamage(...)} — never a second {@code victim.damage()}, which
 *       would re-enter this dispatch.</li>
 *   <li><b>[Left Click] The Second Kiss</b> — every {@value #STRIKES_PER_STACK}nd strike frosts one
 *       stack onto the blade ({@value #CHARGE_MAX} stacks = {@value #STRIKES_TO_FULL} strikes). Once the
 *       charge is full the <b>next</b> strike is empowered: it seals the victim in ice for
 *       {@value #SECOND_KISS_ROOT_TICKS} ticks and leaves {@value #SECOND_KISS_SLOW_TICKS} ticks of
 *       Slowness behind, then the charge resets to nothing. The stack count reads on the action bar as an
 *       {@value #CHARGE_MAX}-segment {@link EgoHud#gauge gauge}.</li>
 *   <li><b>[Right-click] The Third Kiss</b> — hurl a block of ice on a {@value #THROW_COOLDOWN_MS}ms
 *       cooldown. On a body it seals them for {@value #THIRD_KISS_ROOT_TICKS} ticks; catch someone
 *       <b>airborne</b> and it drags them straight down instead and doubles whatever fall damage the drop
 *       costs them, sealing them where they land.</li>
 * </ul>
 *
 * <p><b>The ice is never real.</b> The hurled block is a render-only {@link BlockDisplay} integrated by
 * hand ({@link IceShardFlight}) and the "sealed in ice" shell is another ({@link IceRoot}) — neither is
 * ever placed, so this weapon cannot edit the world. Frost on the victim is cosmetic
 * {@linkplain Entity#setFreezeTicks(int) freeze ticks} held deliberately <b>under</b> the freeze
 * threshold, so it paints the client's frost vignette without ever dealing vanilla freeze damage, and it
 * thaws on its own (vanilla bleeds it off at 2/tick outside powder snow) rather than needing a cleanup.
 *
 * <p><b>The roots are best-effort.</b> Paper cannot freeze a player server-side without movement packets,
 * so {@link IceRoot} does what the house does for {@link HeavenWeapon}'s stasis: per-tick velocity zero
 * plus a refreshed crushing Slowness, and {@link Mob#setAI(boolean) AI off} for mobs (restored on the way
 * out, even if the target died). Mobs are held hard; a determined player can still nudge themselves a
 * little. This is a platform limit, not a tuning choice.
 *
 * <p>Every scrap of victim-keyed state ({@link #roots}, {@link #drags}, {@link #ticking}, and the dueler
 * <i>values</i> in {@link #dueler}) is pruned inline the moment it resolves, and again on death, on quit
 * and on disable — mobs never fire {@code onQuit}, so nothing here may rely on it.
 */
public final class FrostSplinterWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Frost Splinter. */
    private final NamespacedKey key;

    /** Scoreboard tag on every display we spawn (thrown shard and ice shell alike), for the orphan sweep. */
    private static final String ICE_TAG = "frost_splinter_ice";

    // ---- state ---------------------------------------------------------------------

    /**
     * [The First Kiss] Wielder -> the UUID of their single current dueler. Wielder-keyed, so it is bounded
     * by the online roster and dropped on quit; the victim UUID it holds is pruned on that victim's death
     * so the mark never outlives the foe that earned it.
     */
    private final Map<UUID, UUID> dueler = new HashMap<>();

    /** [The Second Kiss] Wielder -> total strikes landed since the last discharge (every 2nd frosts a stack). */
    private final Map<UUID, Integer> strikes = new HashMap<>();

    /** [The Second Kiss] Wielder -> charge stacks currently frosted onto the blade, 0..{@link #CHARGE_MAX}. */
    private final Map<UUID, Integer> charge = new HashMap<>();

    /** [The Third Kiss] Wielder -> epoch-millis of their last hurled block. */
    private final Map<UUID, Long> lastThrow = new HashMap<>();

    /** Victim UUID -> their single live ice root. Victim-keyed: each root prunes its own key on finish. */
    private final Map<UUID, IceRoot> roots = new HashMap<>();

    /** Victim UUID -> their live slam-to-ground. Victim-keyed: each drag prunes its own key on finish. */
    private final Map<UUID, FrostDrag> drags = new HashMap<>();

    /** Thrown ice blocks currently in the air — reaped by {@link #onDisable} so no display can orphan. */
    private final Set<IceShardFlight> flights = new HashSet<>();

    /**
     * Re-entrancy fence. The fall-damage bonus calls {@code victim.damage(..., thrower)}, which fires a
     * fresh {@link EntityDamageByEntityEvent} and re-enters {@link #onHit}. Victims listed here are taking
     * our own damage, not a spear strike, so onHit refuses to mark a dueler or build charge from inside it.
     */
    private final Set<UUID> ticking = new HashSet<>();

    // ---- tuning --------------------------------------------------------------------

    // [Passive] The First Kiss.
    /** Strikes against the wielder's current dueler bite 5% harder. Base 6.5 -> ~6.8, well under the ceiling. */
    private static final double DUELER_DAMAGE_MULT = 1.05;

    // [Left Click] The Second Kiss.
    /** Every second strike frosts one stack onto the blade. */
    private static final int STRIKES_PER_STACK = 2;
    /** Stacks needed before the next strike is empowered. */
    private static final int CHARGE_MAX = 12;
    /** Strikes to fill the charge from empty — {@value #CHARGE_MAX} x {@value #STRIKES_PER_STACK}. */
    private static final int STRIKES_TO_FULL = CHARGE_MAX * STRIKES_PER_STACK;
    /** The empowered strike seals the victim for 1.5s. */
    private static final int SECOND_KISS_ROOT_TICKS = 30;
    /** ...and leaves 12s of Slowness once the ice lets go. */
    private static final int SECOND_KISS_SLOW_TICKS = 240;
    /** Amplifier of that lingering Slowness — 2 => Slowness III, a real drag but still walkable. */
    private static final int SECOND_KISS_SLOW_AMP = 2;

    // [Right-click] The Third Kiss.
    /** One hurled block of ice every 20 seconds. */
    private static final long THROW_COOLDOWN_MS = 20_000L;
    /** A body caught by the block is sealed for 2.5s. */
    private static final int THIRD_KISS_ROOT_TICKS = 50;

    // The hurled block's flight. Speeds are blocks/tick; the loop sub-steps by STEP so it cannot tunnel
    // through a wall or a body between ticks.
    private static final double SHARD_SPEED    = 0.85;  // a heavy, hurled block — quick but not a bullet
    private static final double SHARD_STEP     = 0.30;
    private static final double SHARD_GRAVITY  = 0.035; // per-tick downward pull; gives it a lobbed arc
    private static final double SHARD_RANGE    = 32.0;
    private static final int    SHARD_LIFE     = 60;    // ~3s aloft, then it shatters on nothing
    private static final double SHARD_HIT_RADIUS = 1.0; // how close the block must pass to catch a body
    private static final double SHARD_SCALE    = 0.70;  // rendered cube edge — a chunky splinter of ice
    private static final float  SHARD_SPIN     = 0.18f; // radians/tick — ice tumbles slowly, it is heavy

    // The root.
    /** Slowness amplifier under the ice — 6 => Slowness VII, a near-total crawl atop the velocity zero. */
    private static final int ROOT_SLOWNESS_AMP = 6;
    /** Re-applied every tick with a few ticks of headroom so the crawl never lapses mid-hold. */
    private static final int ROOT_SLOWNESS_REFRESH_TICKS = 6;
    /**
     * Cosmetic frost on the sealed body, as a fraction of the entity's freeze threshold. Kept under 1.0 on
     * purpose: at the full threshold vanilla starts dealing freeze damage, and this shell is a hold, not a
     * damage source.
     */
    private static final double ROOT_FROST_FRACTION = 0.65;
    /** Padding around the victim's hitbox for the ice shell, so the body reads as sealed inside it. */
    private static final double SHELL_PAD = 0.30;

    // The airborne drag.
    /** Downward speed of the slam, blocks/tick. Fast enough to read as instant; Bukkit still sweeps collisions. */
    private static final double DRAG_SPEED = 2.5;
    /** Safety cap on the slam (~3s) in case they never reach ground (a void drop, a boat, a lava column). */
    private static final int DRAG_MAX_TICKS = 60;
    /** Ticks to let the landing settle so vanilla's fall damage is on the books before we read it. */
    private static final int DRAG_SETTLE_TICKS = 2;
    /**
     * Cap on the doubled half of the fall damage. A drop from build height would otherwise let one 20s
     * cooldown delete anything; the spear is a control tool, not an execution.
     */
    private static final double DRAG_FALL_BONUS_CAP = 20.0;

    public FrostSplinterWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "frost_splinter");
    }

    @Override
    public String id() {
        return "frost_splinter";
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.FROST_SPLINTER.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.FROST_SPLINTER.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.FROST_SPLINTER);

        item.setItemMeta(meta);
        return item;
    }

    // ---- [Passive] The First Kiss + [Left Click] The Second Kiss --------------------

    /**
     * A landed strike. Vanilla netherite damage and its one point of blade wear are left intact — the spear
     * only leans on the blow.
     *
     * <p>First the <b>First Kiss</b>: if this victim is already the wielder's dueler the blow bites +5%
     * harder, and either way they become the dueler now (exactly one, always the most recent). Then the
     * <b>Second Kiss</b>: a full charge discharges into this strike — sealing the victim in ice and leaving
     * a long chill — otherwise every second strike frosts one more stack on.
     *
     * <p>Skipped entirely when the "hit" is our own fall-damage bonus re-entering this dispatch
     * ({@link #ticking}): the drop is the block's doing, not a strike, and must not mark or charge.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        UUID vid = victim.getUniqueId();
        if (ticking.contains(vid)) return; // our own fall bonus re-entering — not a strike

        // [Passive] The First Kiss — the mark is spent on the dueler, then moves to whoever we just touched.
        if (vid.equals(dueler.get(aid))) {
            event.setDamage(event.getDamage() * DUELER_DAMAGE_MULT);
            firstKissFx(victim);
        }
        dueler.put(aid, vid);

        // [Left Click] The Second Kiss — a full blade discharges into this strike; otherwise it frosts over.
        int stacks = charge.getOrDefault(aid, 0);
        if (stacks >= CHARGE_MAX) {
            charge.remove(aid);
            strikes.remove(aid);
            secondKiss(attacker, victim);
        } else {
            int landed = strikes.merge(aid, 1, Integer::sum);
            if (landed % STRIKES_PER_STACK == 0) {
                int next = Math.min(CHARGE_MAX, stacks + 1);
                charge.put(aid, next);
                frostStackFx(attacker, next);
            }
        }
        showGauge(attacker);
    }

    /**
     * The empowered strike: the victim is sealed in ice for 1.5s and, once it lets go, carries 12s of
     * Slowness out of it.
     *
     * <p>The lingering Slowness is handed to the root and applied on the way <b>out</b>, not now: the hold
     * re-applies Slowness VII every tick to pin the body, and Bukkit's {@code addPotionEffect} replaces a
     * weaker effect outright — a 12s Slowness III applied here would simply be eaten by the first tick of
     * the hold. Landing it as the ice releases gives the honest 1.5s seal followed by the full 12s chill.
     */
    private void secondKiss(Player attacker, LivingEntity victim) {
        root(victim, SECOND_KISS_ROOT_TICKS, SECOND_KISS_SLOW_TICKS);
        secondKissFx(attacker, victim);
    }

    // ---- [Right-click] The Third Kiss ----------------------------------------------

    /** Hurl a block of ice, unless the 20s cooldown is still running — in which case just show the clock. */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastThrow.get(id);
        if (last != null) {
            long remaining = THROW_COOLDOWN_MS - (now - last);
            if (remaining > 0) {
                showGauge(player); // the composed line already carries the Third Kiss cooldown beside the charge
                player.playSound(player.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 0.4f, 1.6f);
                return;
            }
        }
        lastThrow.put(id, now);

        hurlIce(player);
        EgoDurability.wearMainHand(player); // mild — one point per hurl; melee wears vanilla on its own
        showGauge(player);
    }

    /**
     * Loose one block of ice along the look line. The block is a render-only {@link BlockDisplay} whose
     * motion we integrate ourselves in {@link IceShardFlight} — a Display has no block behaviour whatsoever,
     * so this can never leave real ice standing in the world.
     */
    private void hurlIce(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.8));

        BlockData ice = Material.PACKED_ICE.createBlockData();
        float half = (float) (SHARD_SCALE * 0.5);
        BlockDisplay display = world.spawn(muzzle, BlockDisplay.class, d -> {
            d.setBlock(ice);
            // Centre the cube on the entity origin so the tumble reads as a spinning splinter, not a wobble.
            d.setTransformation(new Transformation(
                    new Vector3f(-half, -half, -half),
                    new Quaternionf(),
                    new Vector3f((float) SHARD_SCALE, (float) SHARD_SCALE, (float) SHARD_SCALE),
                    new Quaternionf()));
            d.setBrightness(new Display.Brightness(13, 15)); // ice should catch the light even in a cave
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setPersistent(false);   // a hard crash can never save this render-only splinter to disk
            d.addScoreboardTag(ICE_TAG);
        });

        IceShardFlight flight = new IceShardFlight(player, display, muzzle, dir, half);
        flights.add(flight); // tracked so onDisable can reap a splinter still in the air on reload
        flight.runTaskTimer(plugin, 1L, 1L);

        world.playSound(eye, Sound.BLOCK_GLASS_BREAK, 0.7f, 0.6f);
        world.playSound(eye, Sound.ENTITY_SNOWBALL_THROW, 0.9f, 0.5f);
        world.spawnParticle(Particle.SNOWFLAKE, muzzle, 10, 0.15, 0.15, 0.15, 0.02, null, true);
        world.spawnParticle(Particle.DUST, muzzle, 8, 0.2, 0.2, 0.2, 0.0, PALE_DUST, true);
    }

    /**
     * One hurled block of ice in flight. Sub-steps its own trajectory each tick under a light gravity so it
     * arcs like a thrown block and cannot tunnel through a wall or a body between ticks. Meets a body ->
     * {@link #impact}; meets a wall or runs out of range/time -> {@link #shatter}, a harmless burst of frost.
     *
     * <p>Bodies are gathered <b>once per tick</b> with a radius covering the whole tick's sweep, then tested
     * against each sub-step — never a {@code getNearbyEntities} call per sub-step.
     */
    private final class IceShardFlight extends BukkitRunnable {
        private final UUID ownerId;
        private final BlockDisplay display;
        private final World world;
        private final Location pos;
        private final Vector velocity;
        private final float half;
        private final Vector3f scale;
        private double traveled = 0.0;
        private int life = SHARD_LIFE;
        private float angle = 0.0f;

        IceShardFlight(Player owner, BlockDisplay display, Location muzzle, Vector dir, float half) {
            this.ownerId = owner.getUniqueId();
            this.display = display;
            this.world = owner.getWorld();
            this.pos = muzzle.clone();
            this.velocity = dir.clone().multiply(SHARD_SPEED);
            this.half = half;
            this.scale = new Vector3f((float) SHARD_SCALE, (float) SHARD_SCALE, (float) SHARD_SCALE);
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || !display.isValid() || --life < 0) {
                shatter(pos);
                finish();
                return;
            }

            velocity.setY(velocity.getY() - SHARD_GRAVITY);
            double speed = velocity.length();
            if (speed < 1.0e-6) { shatter(pos); finish(); return; }
            Vector dir = velocity.clone().multiply(1.0 / speed);

            // One scan per tick, wide enough to cover this tick's whole forward sweep plus the catch radius.
            List<LivingEntity> candidates = new ArrayList<>();
            double scan = speed + SHARD_HIT_RADIUS + 1.0;
            for (Entity e : world.getNearbyEntities(pos, scan, scan, scan)) {
                if (e.getUniqueId().equals(ownerId)) continue;              // never the thrower
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
                candidates.add(le);
            }

            double moved = 0.0;
            while (moved < speed) {
                double step = Math.min(SHARD_STEP, speed - moved);

                // A wall stops it dead — ice shatters, nothing is placed.
                Location next = pos.clone().add(dir.clone().multiply(step));
                if (next.getBlock().getType().isSolid()) { shatter(next); finish(); return; }

                pos.add(dir.clone().multiply(step));
                display.teleport(pos);

                LivingEntity hit = firstHit(candidates);
                if (hit != null) { impact(owner, hit); finish(); return; }

                traveled += step;
                moved += step;
                if (traveled >= SHARD_RANGE) { shatter(pos); finish(); return; }
            }

            // Tumble it and trail a thin fall of snow so the hurled block reads in flight.
            angle += SHARD_SPIN;
            Quaternionf spin = new Quaternionf().rotateXYZ(angle, angle * 0.6f, angle * 0.25f);
            display.setTransformation(new Transformation(
                    new Vector3f(-half, -half, -half), spin, scale, new Quaternionf()));
            world.spawnParticle(Particle.SNOWFLAKE, pos, 2, 0.06, 0.06, 0.06, 0.01, null, true);
            world.spawnParticle(Particle.DUST, pos, 1, 0.05, 0.05, 0.05, 0.0, PALE_DUST, true);
        }

        /** The nearest candidate within the block's catch radius of the current sub-step, else null. */
        private LivingEntity firstHit(List<LivingEntity> candidates) {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity le : candidates) {
                if (le.isDead() || !le.isValid()) continue;
                double d = center(le).subtract(pos.toVector()).lengthSquared();
                if (d <= SHARD_HIT_RADIUS * SHARD_HIT_RADIUS && d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        /**
         * The block catches a body. Grounded, it simply seals them where they stand. Caught
         * <b>airborne</b>, the drag comes first — they are slammed down and pay double for the drop — and
         * the ice closes over them where they land ({@link FrostDrag} starts the root itself). Ordering
         * matters: a root and a slam both own the victim's velocity every tick, so they must never overlap.
         */
        private void impact(Player owner, LivingEntity victim) {
            shatter(victim.getLocation().add(0, victim.getHeight() * 0.5, 0));
            if (!victim.isOnGround()) {
                dragDown(owner, victim);
            } else {
                root(victim, THIRD_KISS_ROOT_TICKS, 0);
            }
        }

        /** Normal end (impact / wall / timeout): remove the display, cancel, and drop out of the live set. */
        private void finish() {
            display.remove();
            cancel();
            flights.remove(this);
        }

        /** Disable-time reap: remove the display and cancel; the caller clears the live set. */
        void shutdown() {
            display.remove();
            cancel();
        }
    }

    // ---- the drag: down, hard, and twice the price ----------------------------------

    /** Slam an airborne victim to the ground, if they aren't already being slammed. */
    private void dragDown(Player owner, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        if (drags.containsKey(vid)) return; // already falling on our account — don't stack tasks
        FrostDrag drag = new FrostDrag(owner.getUniqueId(), victim);
        drags.put(vid, drag);
        drag.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * The slam. Each tick it drives the victim straight down at {@link #DRAG_SPEED} until they touch ground,
     * then waits {@link #DRAG_SETTLE_TICKS} for vanilla's own fall damage to land before reading it back off
     * the victim and dealing exactly that much again — so the drop costs <b>double</b>, honouring Feather
     * Falling, Jump Boost and any mob's own fall multiplier for free, because we double the real number
     * rather than recomputing one. Once it has paid out, the ice closes over them.
     *
     * <p>Note the drag never inflates the base damage: forcing them down covers the same vertical distance
     * they were going to fall anyway, only sooner and without the choice. Double of nothing is nothing — a
     * one-block hop that costs no fall damage costs no bonus either.
     */
    private final class FrostDrag extends BukkitRunnable {
        private final UUID throwerId;
        private final LivingEntity victim;
        /** The victim's damage record as it stood before the slam — anything else with cause FALL is ours. */
        private final EntityDamageEvent priorCause;
        private int ticks = 0;
        private int settling = -1; // >=0 once they have touched down

        FrostDrag(UUID throwerId, LivingEntity victim) {
            this.throwerId = throwerId;
            this.victim = victim;
            this.priorCause = victim.getLastDamageCause();
        }

        @Override
        public void run() {
            if (victim.isDead() || !victim.isValid() || ticks++ > DRAG_MAX_TICKS) { finish(); return; }

            if (settling < 0) {
                if (!victim.isOnGround()) {
                    victim.setVelocity(new Vector(0, -DRAG_SPEED, 0));
                    plungeFx(victim);
                    return;
                }
                settling = 0; // touched down — let the landing tick resolve before we read the books
            }

            if (settling++ < DRAG_SETTLE_TICKS) return;

            payFallBonus();
            root(victim, THIRD_KISS_ROOT_TICKS, 0); // the ice closes over them where they landed
            finish();
        }

        /**
         * Read the fall damage vanilla just charged them and charge it again, doubling the drop. Only a
         * FALL record newer than the one the victim carried when the slam began counts, so a stale fall from
         * five minutes ago can never be billed twice.
         */
        private void payFallBonus() {
            EntityDamageEvent cause = victim.getLastDamageCause();
            if (cause == null || cause == priorCause) return;
            if (cause.getCause() != EntityDamageEvent.DamageCause.FALL) return;

            double bonus = Math.min(cause.getFinalDamage(), DRAG_FALL_BONUS_CAP);
            if (bonus <= 0.0) return;

            Player thrower = plugin.getServer().getPlayer(throwerId);
            UUID vid = victim.getUniqueId();

            // Vanilla just made them briefly invulnerable for its own hit; the doubled half is meant to land
            // in the same instant, so clear that window rather than let the bonus be silently swallowed.
            victim.setNoDamageTicks(0);

            ticking.add(vid); // fence: this re-enters onHit — it must not mark a dueler or build charge
            try {
                if (thrower != null && !thrower.equals(victim)) {
                    victim.damage(bonus, thrower);
                } else {
                    victim.damage(bonus);
                }
            } finally {
                ticking.remove(vid);
            }
            impactFx(victim.getLocation());
        }

        private void finish() {
            cancel();
            drags.remove(victim.getUniqueId());
        }

        /** Disable-time reap: just cancel — a drag owns no entities. The caller clears the live map. */
        void shutdown() {
            cancel();
        }
    }

    // ---- the root: sealed in ice (best-effort) --------------------------------------

    /**
     * Seal a victim in ice for {@code durationTicks}, leaving {@code afterSlownessTicks} of Slowness behind
     * when it lets go (0 for none). A victim already sealed has their hold extended rather than a second
     * task stacked on top of them.
     */
    private void root(LivingEntity victim, int durationTicks, int afterSlownessTicks) {
        UUID vid = victim.getUniqueId();
        IceRoot existing = roots.get(vid);
        if (existing != null) {
            existing.extend(durationTicks, afterSlownessTicks);
            return;
        }
        IceRoot ice = new IceRoot(victim, durationTicks, afterSlownessTicks);
        roots.put(vid, ice);
        ice.runTaskTimer(plugin, 0L, 1L);
        sealFx(victim.getLocation());
    }

    /**
     * The hold. Each tick it zeroes the victim's velocity (killing walk, sprint and jump alike — nothing
     * survives a reset velocity), re-applies a crushing Slowness with a few ticks of headroom, keeps the
     * cosmetic frost topped up below the damage threshold, and walks the ice shell onto the body.
     *
     * <p>For mobs it also suspends AI for the duration through {@code plugin.weapons().suspendAi} and hands it
     * back with {@code restoreAi} on the way out. The framework owns the safety net: it restores the mark on
     * chunk unload, reload, and disable, so a mob sealed when its chunk unloads is never left mindless.
     *
     * <p><b>Platform limit:</b> a player cannot be truly frozen server-side without movement packets. For
     * players this is a best-effort root — per-tick velocity zero plus Slowness VII — exactly as
     * {@link HeavenWeapon}'s stasis does it. Mobs are held hard; a determined player can still nudge.
     */
    private final class IceRoot extends BukkitRunnable {
        private final LivingEntity target;
        private final BlockDisplay shell;
        private int remaining;
        private int afterSlownessTicks;

        IceRoot(LivingEntity target, int durationTicks, int afterSlownessTicks) {
            this.target = target;
            this.remaining = durationTicks;
            this.afterSlownessTicks = afterSlownessTicks;

            if (target instanceof Mob mob) {
                plugin.weapons().suspendAi(mob);
            }
            this.shell = spawnShell(target);
        }

        /** Extend an existing hold (and its parting chill) rather than stacking a second task on the body. */
        void extend(int durationTicks, int slowTicks) {
            remaining = Math.max(remaining, durationTicks);
            afterSlownessTicks = Math.max(afterSlownessTicks, slowTicks);
        }

        @Override
        public void run() {
            if (remaining-- <= 0 || target.isDead() || !target.isValid()) { finish(); return; }

            // Pin: reset velocity every tick, so walk, sprint and jump all die on the spot.
            target.setVelocity(new Vector(0, 0, 0));
            target.setFallDistance(0f); // sealed, not falling — the ice holds them, it doesn't drop them
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, ROOT_SLOWNESS_REFRESH_TICKS, ROOT_SLOWNESS_AMP,
                    false, false, false));

            // Cosmetic frost only: held under the freeze threshold so vanilla never bills freeze damage,
            // and left to thaw on its own (vanilla bleeds it off at 2/tick outside powder snow).
            int frost = (int) (target.getMaxFreezeTicks() * ROOT_FROST_FRACTION);
            if (target.getFreezeTicks() < frost) target.setFreezeTicks(frost);

            if (shell != null && shell.isValid()) shell.teleport(shellAnchor(target));
            holdFx(target);
        }

        /** Normal end: thaw the shell, give back the mob's mind, land the parting chill, prune our key. */
        private void finish() {
            release();
            if (afterSlownessTicks > 0 && target.isValid() && !target.isDead()) {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS, afterSlownessTicks, SECOND_KISS_SLOW_AMP,
                        false, true, true));
            }
            if (target.isValid() && !target.isDead()) thawFx(target.getLocation());
            roots.remove(target.getUniqueId());
            cancel();
        }

        /** Disable-time reap: thaw the shell and restore AI; the caller clears the live map. */
        void shutdown() {
            release();
            cancel();
        }

        /** Drop the shell and hand a suspended mob its AI back through the framework. Safe to call twice. */
        private void release() {
            if (shell != null && shell.isValid()) shell.remove();
            // The framework restores the AI here on the normal timer, and also on chunk unload, reload, or
            // disable, so an unload mid-hold can no longer leave the mob frozen. restoreAi is a no-op on a mob
            // we never suspended and is guarded on isDead() inside, so this is safe to call unconditionally.
            if (target instanceof Mob mob) {
                plugin.weapons().restoreAi(mob);
            }
        }
    }

    /**
     * The ice shell: a render-only {@link BlockDisplay} of ice scaled to swallow the victim's hitbox. Ice is
     * translucent, so the body reads as sealed <em>inside</em> it rather than hidden by it. It is a Display —
     * it can never become a real block, so a sealed foe never leaves ice behind when they thaw.
     */
    private BlockDisplay spawnShell(LivingEntity victim) {
        float w = (float) (victim.getWidth() + SHELL_PAD * 2.0);
        float h = (float) (victim.getHeight() + SHELL_PAD * 2.0);
        BlockData ice = Material.ICE.createBlockData();
        return victim.getWorld().spawn(shellAnchor(victim), BlockDisplay.class, d -> {
            d.setBlock(ice);
            // Origin sits at the victim's feet: pull the cube half its width on X/Z and a pad down on Y.
            d.setTransformation(new Transformation(
                    new Vector3f(-w * 0.5f, (float) -SHELL_PAD, -w * 0.5f),
                    new Quaternionf(),
                    new Vector3f(w, h, w),
                    new Quaternionf()));
            d.setBrightness(new Display.Brightness(12, 15));
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setTeleportDuration(1);  // smooth the shell onto the body between ticks
            d.setPersistent(false);    // a hard crash can never save this render-only shell to disk
            d.addScoreboardTag(ICE_TAG);
        });
    }

    /** Where the shell sits: the victim's feet, yaw/pitch stripped so the cube never rolls with their look. */
    private Location shellAnchor(LivingEntity victim) {
        Location at = victim.getLocation().clone();
        at.setYaw(0f);
        at.setPitch(0f);
        return at;
    }

    // ---- HUD -----------------------------------------------------------------------

    /**
     * The action bar: the Second Kiss charge as a {@value #CHARGE_MAX}-segment gauge, labelled with the
     * Third Kiss's clock. Cooldowns read in whole seconds via {@link EgoHud} — never milliseconds.
     */
    private void showGauge(Player player) {
        UUID id = player.getUniqueId();
        int stacks = charge.getOrDefault(id, 0);
        boolean full = stacks >= CHARGE_MAX;

        Long last = lastThrow.get(id);
        long remaining = last == null ? 0L : THROW_COOLDOWN_MS - (System.currentTimeMillis() - last);
        Component label = remaining > 0
                ? EgoHud.cooldown("Third Kiss", remaining, FROST_DIM)
                : EgoHud.ready("Third Kiss", FROST);

        player.sendActionBar(EgoHud.gauge(
                full ? GLACIER : FROST, stacks / (double) CHARGE_MAX, CHARGE_MAX, label));
    }

    /** While the spear is in the main hand, keep the charge gauge and the clock on screen. */
    @Override
    public boolean onTick(Player player, long tick) {
        // First and always: the moment it leaves the main hand this player must stop ticking, forever.
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        showGauge(player);
        return true;
    }

    // ---- presentation --------------------------------------------------------------

    /** The First Kiss lands on the dueler: a small, clean bite of frost and a thin glassy chime. */
    private void firstKissFx(LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        victim.getWorld().spawnParticle(Particle.SNOWFLAKE, c, 6, 0.22, 0.3, 0.22, 0.01, null, true);
        victim.getWorld().spawnParticle(Particle.DUST, c, 5, 0.2, 0.28, 0.2, 0.0, PALE_DUST, true);
        victim.getWorld().playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.9f);
    }

    /** A stack frosts onto the blade: a soft crystalline tick, climbing in pitch as the charge fills. */
    private void frostStackFx(Player attacker, int stacks) {
        float pitch = 0.8f + (stacks / (float) CHARGE_MAX) * 1.0f; // 0.8 -> 1.8 as it fills
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 0.35f, pitch);
        if (stacks >= CHARGE_MAX) {
            // The blade is full — an unmistakable clear ring, the spear ready to spend itself.
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.4f);
        }
    }

    /** The Second Kiss discharges: the blade sheds its whole charge into the body in one cold crack. */
    private void secondKissFx(Player attacker, LivingEntity victim) {
        World world = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.SNOWFLAKE, c, 30, 0.4, 0.6, 0.4, 0.05, null, true);
        world.spawnParticle(Particle.ITEM_SNOWBALL, c, 20, 0.35, 0.5, 0.35, 0.08, null, true);
        world.spawnParticle(Particle.DUST, c, 18, 0.4, 0.55, 0.4, 0.0, PALE_DUST, true);
        world.spawnParticle(Particle.DUST, c, 8, 0.4, 0.55, 0.4, 0.0, DEEP_DUST, true);
        world.playSound(c, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f);
        world.playSound(c, Sound.ITEM_TRIDENT_RETURN, 0.6f, 1.9f);
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
    }

    /** The ice closes: a hard glassy snap and a ring of frost thrown out around the sealed body. */
    private void sealFx(Location at) {
        World world = at.getWorld();
        Location feet = at.clone().add(0, 0.05, 0);
        world.playSound(feet, Sound.BLOCK_GLASS_PLACE, 0.9f, 0.5f);
        world.playSound(feet, Sound.BLOCK_POWDER_SNOW_PLACE, 0.8f, 0.6f);

        final int points = 18;
        final double radius = 1.2;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * radius, 0.02, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.01, 0.02, 0.0, PALE_DUST, true);
        }
        world.spawnParticle(Particle.SNOWFLAKE, feet.clone().add(0, 0.8, 0), 16, 0.35, 0.5, 0.35, 0.02, null, true);
    }

    /** The hold, every tick: a slow drift of frost off the shell and the odd brittle creak. */
    private void holdFx(LivingEntity target) {
        World world = target.getWorld();
        Location c = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.SNOWFLAKE, c, 2, 0.3, 0.4, 0.3, 0.005, null, true);
        if (ThreadLocalRandom.current().nextInt(10) == 0) {
            world.spawnParticle(Particle.DUST, c, 2, 0.32, 0.45, 0.32, 0.0, PALE_DUST, true);
            world.playSound(c, Sound.BLOCK_GLASS_STEP, 0.25f, 0.6f);
        }
    }

    /** The thaw: the shell lets go in a quiet fall of meltwater and snow. Forged from snow, it goes. */
    private void thawFx(Location at) {
        World world = at.getWorld();
        Location c = at.clone().add(0, 0.9, 0);
        world.spawnParticle(Particle.SNOWFLAKE, c, 12, 0.3, 0.45, 0.3, 0.02, null, true);
        world.spawnParticle(Particle.DUST, c, 6, 0.3, 0.45, 0.3, 0.0, PALE_DUST, true);
        world.playSound(c, Sound.BLOCK_GLASS_BREAK, 0.4f, 1.5f);
        world.playSound(c, Sound.BLOCK_POWDER_SNOW_BREAK, 0.4f, 1.2f);
    }

    /** The hurled block shatters — on a wall, on nothing, or on the body it just caught. */
    private void shatter(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.SNOWFLAKE, at, 18, 0.25, 0.25, 0.25, 0.06, null, true);
        world.spawnParticle(Particle.ITEM_SNOWBALL, at, 12, 0.2, 0.2, 0.2, 0.1, null, true);
        world.spawnParticle(Particle.BLOCK, at, 14, 0.25, 0.25, 0.25, 0.0, ICE_BLOCK, true);
        world.spawnParticle(Particle.DUST, at, 8, 0.25, 0.25, 0.25, 0.0, PALE_DUST, true);
        world.playSound(at, Sound.BLOCK_GLASS_BREAK, 0.9f, 0.7f);
    }

    /** The plunge: a hard white streak dragged down behind a slammed body. */
    private void plungeFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.SNOWFLAKE, c, 4, 0.2, 0.3, 0.2, 0.0, null, true);
        world.spawnParticle(Particle.DUST, c, 3, 0.2, 0.3, 0.2, 0.0, PALE_DUST, true);
    }

    /** The landing: the ground takes them, and the ice takes the rest. */
    private void impactFx(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.SNOWFLAKE, at, 24, 0.5, 0.15, 0.5, 0.08, null, true);
        world.spawnParticle(Particle.BLOCK, at, 18, 0.45, 0.12, 0.45, 0.0, ICE_BLOCK, true);
        world.spawnParticle(Particle.DUST, at, 10, 0.5, 0.12, 0.5, 0.0, PALE_DUST, true);
        world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f);
        world.playSound(at, Sound.ENTITY_PLAYER_BIG_FALL, 0.7f, 0.6f);
    }

    /** The centre of a body, for hit tests. */
    private Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    // ---- lifecycle: never orphan an entity, never leak a victim key -----------------

    /**
     * A victim is gone for good: thaw anything holding them and drop every key they own. Mobs never fire
     * {@code onQuit}, so this — plus each task's own inline prune — is what keeps the victim-keyed maps from
     * growing forever on a long-running server.
     */
    private void forgetVictim(UUID vid) {
        IceRoot ice = roots.remove(vid);
        if (ice != null) ice.shutdown();
        FrostDrag drag = drags.remove(vid);
        if (drag != null) drag.shutdown();
        ticking.remove(vid);
        dueler.values().removeIf(vid::equals); // the dueler is dead — the mark dies with them
    }

    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        forgetVictim(event.getEntity().getUniqueId());
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        forgetVictim(event.getEntity().getUniqueId());
    }

    @Override
    public void onQuit(UUID id) {
        // As a wielder: drop their mark, their charge and their clock.
        dueler.remove(id);
        strikes.remove(id);
        charge.remove(id);
        lastThrow.remove(id);
        // As a victim: thaw them and drop every key they own.
        forgetVictim(id);
    }

    @Override
    public void onDisable() {
        // Bukkit cancels our tasks on disable/reload before they can clean up after themselves — reap every
        // live hold, drag and flight here so no display orphans and no mob is left AI-disabled across a reload.
        for (IceRoot ice : new ArrayList<>(roots.values())) ice.shutdown();
        roots.clear();
        for (FrostDrag drag : new ArrayList<>(drags.values())) drag.shutdown();
        drags.clear();
        for (IceShardFlight flight : new ArrayList<>(flights)) flight.shutdown();
        flights.clear();

        dueler.clear();
        strikes.clear();
        charge.clear();
        lastThrow.clear();
        ticking.clear();

        sweepOrphans(); // belt-and-braces: reap any stray tagged ice anywhere in the world
    }

    /** Remove every display carrying our ice tag across all loaded worlds — shards and shells alike. */
    private void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(BlockDisplay.class)) {
                if (e.getScoreboardTags().contains(ICE_TAG)) e.remove();
            }
        }
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        if (!flights.isEmpty()) out.add("frost_splinter  " + flights.size() + " hurled ice block(s) in flight");
        if (!roots.isEmpty())   out.add("frost_splinter  " + roots.size() + " foe(s) sealed in ice");
        if (!drags.isEmpty())   out.add("frost_splinter  " + drags.size() + " foe(s) being dragged down");
        return out;
    }

    // ---- colours / particles -------------------------------------------------------

    private static final TextColor FROST     = TextColor.color(0x9CD6E8); // primary — pale glacial blue
    private static final TextColor SLATE     = TextColor.color(0x2A3A4A); // secondary — the cold dark under the ice
    private static final TextColor FROST_DIM = TextColor.color(0x6E9AAB); // primary, dimmed — a cooling clock
    private static final TextColor GLACIER   = TextColor.color(0xE8F6FB); // a full blade — near-white

    private static final Color PALE_RGB = Color.fromRGB(0x9C, 0xD6, 0xE8); // frost motes / trail
    private static final Color DEEP_RGB = Color.fromRGB(0x2A, 0x3A, 0x4A); // the dark inside the ice
    private static final Particle.DustOptions PALE_DUST = new Particle.DustOptions(PALE_RGB, 1.0f);
    private static final Particle.DustOptions DEEP_DUST = new Particle.DustOptions(DEEP_RGB, 1.2f);

    /** BlockData for the shatter burst — Particle.BLOCK takes BlockData, never a Material. */
    private static final BlockData ICE_BLOCK = Material.ICE.createBlockData();

    // ---- lore ----------------------------------------------------------------------

    /** Built once, applied in {@link #createItem()}: the meta is set a single time and never repainted, so
     *  the spear stays a true enchantable alternative to a vanilla sword. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Frost Splinter",   // display name — always the weapon
            "The Snow Queen",   // title line — always the Abnormality
            FROST,
            SLATE,
            List.of(
                    "The Snow Queen was beautiful, but",
                    "where her heart should have been was",
                    "empty and frozen. The edge of the",
                    "spear is both straight and icy.",
                    "Anyone damaged by it will lose",
                    "themselves for a moment. As the",
                    "equipment was forged from snow, it",
                    "shall disappear without a trace",
                    "someday. Someday, when the weather",
                    "warms enough to melt the snow, it may",
                    "thaw the heart as well; if you truly",
                    "believe so."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] The First Kiss",
                            "The last foe you strike becomes your",
                            "dueler: +5% damage to them alone."),
                    new EgoLore.Ability("[Left Click] The Second Kiss",
                            "Every second strike builds a charge.",
                            "At 12, your next blow roots them in",
                            "ice for 1.5s and leaves 12s of",
                            "slowness behind."),
                    new EgoLore.Ability("[Right-click] The Third Kiss",
                            "Hurl a block of ice: it roots what it",
                            "hits for 2.5s. Catch them airborne and",
                            "it drags them down for double fall",
                            "damage. 20s cooldown.")
            ));
}
