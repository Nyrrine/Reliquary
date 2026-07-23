package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grinder Mk.4 — All-Around Helper. An HE-tier Lobotomy Corp E.G.O Equipment: a squat industrial power
 * tool, all whining motor and spinning sawteeth, "repurposed" from a maintenance rig into a weapon. It
 * is predominantly light silver with hints of red — a bare-metal body flecked with warning paint.
 *
 * <p><b>Left-click — the sawing burst.</b> Every landed strike doesn't hit once, it <em>grinds</em>: the
 * vanilla connect is swallowed (so its lone blow and its knockback never land) and in its place the motor
 * drives {@link #STRIKES} small grinding bites into the same body, a few ticks apart, over a short window
 * ({@link #STRIKE_PERIOD_TICKS}-spaced) — a slower cadence than a sword. The whole burst tops out at
 * {@code STRIKES * STRIKE_DAMAGE} (~6.6) — a measured burst rather than a big single blow — and each bite
 * ignores {@link #GRIND_ARMOR_IGNORE} of the body's armour: a grinder's teeth shred plate where a sword
 * glances off it. A low, sawing grindstone note rides every left-click.
 *
 * <p><b>Shift-right-click — the sustained grind (a TOGGLE).</b> True right-mouse-hold isn't detectable in
 * Bukkit, so the sustained grind is a toggle: shift-right-click spins it up, shift-right-click again — or
 * unequipping, or releasing sneak — spins it down. While it runs (driven from {@link #onTick}) a high,
 * constant saw whines, anything in a short cone in front takes fair, throttled grinding damage
 * ({@link #GRIND_DAMAGE} every half-second, no knockback, ~{@code GRIND_DAMAGE*2} DPS), and the wielder
 * chews a 3x3 face of blocks in front of them — no instant-break, real accumulated progress per block at
 * a fixed, deliberately slow rate that only the block's own hardness slows further (stone ~1.5s), dropping
 * blocks normally. (Efficiency / Haste scaling is intentionally not wired yet — see the mining TODO.)
 *
 * <p><b>Re-entrancy fence</b> (the Wrist Cutter pattern): every grinding bite calls {@code victim.damage},
 * which re-enters {@link #onHit}. A {@link #ticking} set wraps each such call; {@link #onHit} bails the
 * moment its victim is in it, so a bite can never re-seed the attack it came from. A separate
 * {@link #bursting} set holds a body for the whole left-click burst so a fresh swing can't stack a second.
 *
 * <p>No longer unbreakable — the grind, the burst and the mining are all non-vanilla uses, so the tool
 * wears mildly via {@link EgoDurability}: once per left-click burst and about once per second of grinding.
 */
public final class GrinderMk4Weapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Grinder Mk.4. */
    private final NamespacedKey key;

    /** Re-entrancy fence: a body is in here only for the instant of a {@code victim.damage} call. */
    private final Set<UUID> ticking = new HashSet<>();

    /** Bodies mid left-click burst — held for the whole flurry so a fresh swing can't stack another. */
    private final Set<UUID> bursting = new HashSet<>();

    /** Wielders currently running the sustained grind (shift-right-click toggle). */
    private final Map<UUID, GrindSession> sessions = new HashMap<>();

    /** Live burst tasks, so a shutdown can cancel every motor still spinning. */
    private final Set<BukkitTask> burstTasks = new HashSet<>();

    // ---- left-click sawing burst (netherite band) ---------------------------------
    /** Grinding bites seeded by one landed strike — the whole left-click attack. */
    private static final int    STRIKES             = 6;
    /** Per-bite damage. 6 * 1.1 = ~6.6 total — a measured burst, not a big hit; armour still applies. */
    private static final double STRIKE_DAMAGE       = 1.1;
    /** Ticks between bites — 3t apart, six bites span ~15t (~0.75s): slower than a sword's swing. */
    private static final long   STRIKE_PERIOD_TICKS = 3L;

    // ---- sustained grind: entity damage -------------------------------------------
    /** Short cone reach in front of the wielder that the grind bites. */
    private static final double GRIND_RANGE   = 3.5;
    /** Cone tightness — dot(look, toTarget) must clear this (~0.6 ≈ a 53° half-cone). */
    private static final double GRIND_DOT     = 0.60;
    /** Per-application grind damage. Applied every {@link #GRIND_INTERVAL} ticks → ~4 DPS. */
    private static final double GRIND_DAMAGE  = 2.0;

    /** Fraction of a body's armour every grinding bite ignores — a grinder's teeth shred plate (balance wave). */
    private static final double GRIND_ARMOR_IGNORE = 0.50;
    /** onTick runs every 2 game ticks and {@code tick} counts those runs; grind entities every 5th
     *  run = every ~10 game ticks (0.5s). Aligns with vanilla i-frames so every bite lands full. */
    private static final long   GRIND_INTERVAL = 5L;

    // ---- sustained grind: 3x3 mining ----------------------------------------------
    /** How far the mining raytrace reaches for a block face. */
    private static final double MINE_RANGE     = 5.0;
    /** Fixed break-progress accrued per game tick, before the block's own hardness divides it down.
     *  Per run = {@code GRIND_RATE / hardness * TICKS_PER_RUN}. Stone (hardness 1.5) → 0.0667/run →
     *  ~15 runs (~1.5s) to break. Deliberately slow and steady, identical for every wielder. */
    private static final double GRIND_RATE      = 0.05;
    /** Game ticks each onTick run represents — progress accrues per game tick, applied 2 at a time. */
    private static final double TICKS_PER_RUN   = 2.0;
    // Overclock (Efficiency): speeds the 3x3 DIG only — never a combat number. Each level adds this much
    // dig rate, capped, so a maxed grinder mines fast without touching its per-bite or per-second damage.
    private static final double OVERCLOCK_PER_LEVEL = 0.35;
    private static final int    OVERCLOCK_MAX_LEVELS = 5;

    public GrinderMk4Weapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "grinder_mk4");
    }

    @Override
    public String id() {
        return "grinder_mk4";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.GRINDER_MK4.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.GRINDER_MK4.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.GRINDER_MK4);
        // Grinder's swing is cancelled in onHit, so its stamped ATTACK_DAMAGE modifier never lands. Hide the
        // attribute lines so the item stops rendering "5 Attack Damage" beside a tooltip that says it deals none.
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        item.setItemMeta(meta);
        return item;
    }

    // ---- left-click: the sawing burst ---------------------------------------------

    /**
     * Left-click swung. The vanilla hit hasn't been resolved yet — this only voices the saw. The actual
     * six-bite burst is seeded in {@link #onHit} when the swing lands on a body, so it fires exactly when
     * the wielder is striking a target in reach.
     */
    @Override
    public void onSwing(Player player) {
        if (!matches(player.getInventory().getItemInMainHand())) return;
        // A low, sawing grindstone note under a coarse tooth-tick — the left-click "saw".
        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, 0.7f, 0.5f);
        world.playSound(at, Sound.BLOCK_STONE_HIT, 0.4f, 0.7f);
    }

    /**
     * Melee hit landed. If it's one of our own grinding bites re-entering (in {@link #ticking}), we let it
     * through untouched — that's the damage doing its job. Otherwise it's a fresh swing: we cancel the
     * vanilla blow (killing its lone hit and its knockback) and, unless a burst is already chewing this
     * body, seed the six-bite grinding flurry in its place.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID vid = victim.getUniqueId();
        if (ticking.contains(vid)) return;      // our own bite — must land, leave it alone
        event.setCancelled(true);               // the grind IS the attack — no vanilla blow, no knockback
        if (bursting.contains(vid)) return;     // already grinding this body — don't stack a second burst
        startBurst(attacker, victim);
    }

    /** Mark the body bursting for the whole flurry, start the motor, and wear the tool once. */
    private void startBurst(Player attacker, LivingEntity victim) {
        bursting.add(victim.getUniqueId());
        BurstTask task = new BurstTask(attacker.getUniqueId(), victim);
        BukkitTask handle = task.runTaskTimer(plugin, 0L, STRIKE_PERIOD_TICKS);
        task.self = handle;
        burstTasks.add(handle);
        EgoDurability.wearMainHand(attacker);   // one wear per left-click burst — not per bite
    }

    /**
     * The left-click motor: {@link #STRIKES} small no-knockback bites into one body, a few ticks apart.
     * Each bite's {@code victim.damage} re-enters {@link #onHit}, but the fence in {@link #grindDamage}
     * keeps it from re-seeding. Ends (releasing {@link #bursting}) when spent or the body dies.
     */
    private final class BurstTask extends BukkitRunnable {
        private final UUID attackerId;
        private final LivingEntity victim;
        private int step = 0;
        private BukkitTask self;

        private BurstTask(UUID attackerId, LivingEntity victim) {
            this.attackerId = attackerId;
            this.victim = victim;
        }

        @Override
        public void run() {
            Player attacker = plugin.getServer().getPlayer(attackerId);
            if (attacker == null || step >= STRIKES || victim.isDead() || !victim.isValid()) {
                finish();
                return;
            }
            grindDamage(attacker, victim, STRIKE_DAMAGE);
            biteFx(victim, step);
            step++;
            if (step >= STRIKES) finish();
        }

        private void finish() {
            cancel();
            bursting.remove(victim.getUniqueId());
            if (self != null) burstTasks.remove(self);
        }
    }

    // ---- shift-right-click: the sustained grind toggle ----------------------------

    /**
     * Right-click. Only the sneaking press does anything — it TOGGLES the sustained grind (true
     * right-mouse-hold can't be detected in Bukkit). Press once to spin up, press again to spin down.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!sneaking) return; // bare right-click is inert — this is a grinder, not a wand

        UUID pid = player.getUniqueId();
        GrindSession live = sessions.get(pid);
        if (live != null) {
            stopGrind(player, "winding down");
            return;
        }
        sessions.put(pid, new GrindSession());
        // The manager engages the presser, so onTick will start driving the grind on the next run.
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.7f, 0.8f);
        world.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.6f, 0.6f);
        player.sendActionBar(EgoHud.status("Grinder Mk.4 — spinning up", SILVER));
    }

    /** Spin the grind down: drop the session, clear any block-crack overlays, and voice it. */
    private void stopGrind(Player player, String reason) {
        GrindSession s = sessions.remove(player.getUniqueId());
        if (s != null) s.clearCracks(player);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 0.6f, 0.7f);
        world.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.5f, 0.45f);
        player.sendActionBar(EgoHud.status("Grinder Mk.4 — " + reason, STEEL));
    }

    /**
     * Drives the sustained grind while it's toggled on. Stops (returning false to disengage) the moment
     * the wielder unequips the tool or releases sneak. Otherwise: loops the high saw, bites entities in
     * the cone (throttled), grinds the 3x3 face in front, wears the tool ~once a second, and shows the
     * grind status on the action bar — never in milliseconds.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        GrindSession s = sessions.get(player.getUniqueId());
        if (s == null) return false; // not grinding — nothing to drive

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!matches(held) || !player.isSneaking()) {
            stopGrind(player, "winding down");
            return false;
        }

        if (tick % 2 == 0) sawLoop(player);                 // the constant high whine

        boolean bitEntity = false;
        if (tick % GRIND_INTERVAL == 0) bitEntity = grindEntities(player);

        double bestBlock = grindBlocks(player, held, s);    // -1 if no block in front

        if (tick % 10 == 0) EgoDurability.wearMainHand(player); // ~once per second of grinding

        if (bestBlock >= 0.0) {
            player.sendActionBar(EgoHud.gauge(SILVER, bestBlock, EgoHud.status("Grinding", SILVER)));
        } else if (bitEntity) {
            player.sendActionBar(EgoHud.status("Grinder Mk.4 — grinding", SILVER));
        } else {
            player.sendActionBar(EgoHud.status("Grinder Mk.4 — no target", STEEL));
        }
        return true;
    }

    // ---- sustained grind: entities -------------------------------------------------

    /** Bite every living body in the short cone in front. No knockback; returns true if anything got bit. */
    private boolean grindEntities(Player player) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        boolean any = false;
        for (Entity e : player.getNearbyEntities(GRIND_RANGE, GRIND_RANGE, GRIND_RANGE)) {
            if (!(e instanceof LivingEntity le) || e.equals(player)) continue;
            Vector to = le.getLocation().add(0, 1.0, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > GRIND_RANGE) continue;
            if (dist > 1.0e-4 && look.dot(to.multiply(1.0 / dist)) < GRIND_DOT) continue; // outside the cone
            grindDamage(player, le, GRIND_DAMAGE);
            grindEntityFx(le);
            any = true;
        }
        return any;
    }

    // ---- sustained grind: 3x3 mining ----------------------------------------------

    /** Overclock (Efficiency): the dig-rate multiplier, capped. Read here so it only ever scales mining. */
    private static double overclockFactor(ItemStack held) {
        int eff = held == null ? 0 : held.getEnchantmentLevel(Enchantment.EFFICIENCY);
        return 1.0 + OVERCLOCK_PER_LEVEL * Math.min(eff, OVERCLOCK_MAX_LEVELS);
    }

    /**
     * Chew the 3x3 face of blocks the wielder is looking at. Progress accrues per block at a slow, steady
     * base rate ({@link #GRIND_RATE}) that only the block's own hardness slows further — never instant — and
     * a block is guaranteed to reach 1.0 and break (dropping with the tool, so a netherite pickaxe harvests
     * stone into cobblestone) once enough runs accumulate. Overclock (Efficiency) speeds the dig, mining
     * only. Returns the highest live progress fraction across the face (for the action-bar gauge), or -1 if
     * there's no block in front.
     */
    private double grindBlocks(Player player, ItemStack held, GrindSession s) {
        RayTraceResult ray = player.rayTraceBlocks(MINE_RANGE);
        if (ray == null || ray.getHitBlock() == null) {
            s.clearCracks(player); // looking at open air — abandon any half-cut blocks (progress resets)
            return -1.0;
        }
        Block center = ray.getHitBlock();
        BlockFace face = ray.getHitBlockFace();
        int[][] axes = planeAxes(face);
        World world = player.getWorld();

        Set<Long> nowCut = new HashSet<>();
        double best = 0.0;

        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                Block b = center.getRelative(
                        du * axes[0][0] + dv * axes[1][0],
                        du * axes[0][1] + dv * axes[1][1],
                        du * axes[0][2] + dv * axes[1][2]);
                Material mat = b.getType();
                if (mat.isAir() || mat.isEmpty()) continue;
                float hardness = mat.getHardness();
                if (hardness < 0.0f) continue;                 // bedrock / unbreakable — skip

                long bk = b.getBlockKey();
                if (hardness == 0.0f) {                          // flowers, tall grass — pop instantly
                    breakBlock(world, b, held);
                    s.progress.remove(bk);
                    continue;
                }

                // Steady accumulation slowed only by the block's own hardness — stone (1.5) → ~15 runs
                // (~1.5s) unenchanted. Overclock (Efficiency) speeds the dig, and the dig ONLY: this is the
                // mining path, so it never touches a combat number.
                double perRun = GRIND_RATE / hardness * TICKS_PER_RUN * overclockFactor(held);
                double prog = s.progress.getOrDefault(bk, 0.0) + perRun;

                if (prog >= 1.0) {
                    breakBlock(world, b, held);                  // breaks with the tool → normal drops
                    s.progress.remove(bk);
                    player.sendBlockDamage(b.getLocation(), 0.0f); // clear the crack overlay on completion
                } else {
                    s.progress.put(bk, prog);
                    player.sendBlockDamage(b.getLocation(), (float) prog);
                    nowCut.add(bk);
                    best = Math.max(best, prog);
                }
            }
        }

        s.reconcile(player, nowCut); // clear crack overlays (and reset progress) on blocks we left this run
        return best;
    }

    /** Two perpendicular unit axes spanning the 3x3 plane whose normal is {@code face}. */
    private static int[][] planeAxes(BlockFace face) {
        int ny = face == null ? 1 : face.getModY();
        int nx = face == null ? 0 : face.getModX();
        if (ny != 0) return new int[][]{{1, 0, 0}, {0, 0, 1}}; // up/down → horizontal slab
        if (nx != 0) return new int[][]{{0, 1, 0}, {0, 0, 1}}; // east/west → Y-Z wall
        return new int[][]{{1, 0, 0}, {0, 1, 0}};              // north/south → X-Y wall
    }

    /** Break a block with the tool so drops honour Fortune/Silk Touch, plus a little grinding debris. */
    private void breakBlock(World world, Block b, ItemStack tool) {
        BlockData data = b.getBlockData();
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        b.breakNaturally(tool);
        world.spawnParticle(Particle.BLOCK, center, 12, 0.28, 0.28, 0.28, 0.0, data);
        world.spawnParticle(Particle.CRIT, center, 4, 0.25, 0.25, 0.25, 0.05);
        world.playSound(center, Sound.BLOCK_STONE_BREAK, 0.55f, 1.6f);
    }

    // ---- the shared no-knockback grinding bite ------------------------------------

    /**
     * Deal one grinding bite. A grinder shreds: each bite ignores {@link #GRIND_ARMOR_IGNORE} of the body's
     * armour, dealt through the framework's {@code pierceDamage} — which fences the blow (so the re-entering
     * {@link #onHit} leaves it alone), clears i-frames so rapid bites all land full, and captures/restores
     * the victim's velocity so the grind never knocks anything back. The self-grind corner (a reflected
     * bite onto the wielder) keeps the plain fenced form: piercing your own armour is meaningless.
     */
    private void grindDamage(Player attacker, LivingEntity victim, double dmg) {
        if (attacker.equals(victim)) {
            UUID vid = victim.getUniqueId();
            Vector before = victim.getVelocity();
            ticking.add(vid);
            try {
                victim.setNoDamageTicks(0);
                victim.damage(dmg);
            } catch (Throwable ignored) {
                // A bite on a vanishing body shouldn't propagate — swallow and move on.
            } finally {
                ticking.remove(vid);
            }
            victim.setVelocity(before);
            return;
        }
        plugin.weapons().pierceDamage(victim, dmg, GRIND_ARMOR_IGNORE, attacker);
    }

    // ---- SFX / VFX -----------------------------------------------------------------

    /** The constant high saw whine while the sustained grind runs. */
    private void sawLoop(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, 0.4f, 1.9f);
        world.playSound(at, Sound.BLOCK_STONE_HIT, 0.22f, 2.0f);
    }

    /** A grinding tooth-bite from the left-click burst: silver debris flecked red, rising mechanical tick. */
    private void biteFx(LivingEntity victim, int step) {
        World world = victim.getWorld();
        Location at = victim.getLocation().add(0, 1.0, 0);
        float climb = 0.9f + 0.05f * step;
        world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, 0.45f, climb);
        world.playSound(at, Sound.BLOCK_STONE_HIT, 0.3f, 1.5f + 0.04f * step);
        world.spawnParticle(Particle.CRIT, at, 3, 0.25, 0.25, 0.25, 0.05);
        world.spawnParticle(Particle.DUST, at, 3, 0.22, 0.22, 0.22, 0,
                new Particle.DustOptions(STEEL_DUST, 1.0f));
        world.spawnParticle(Particle.DUST, at, 2, 0.2, 0.2, 0.2, 0,
                new Particle.DustOptions(RED_SPARK, 0.9f));
    }

    /** Sparks and swarf off a body caught in the sustained grind. */
    private void grindEntityFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location at = victim.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 4, 0.22, 0.22, 0.22, 0.0);
        world.spawnParticle(Particle.CRIT, at, 3, 0.22, 0.22, 0.22, 0.05);
        world.spawnParticle(Particle.DUST, at, 3, 0.22, 0.22, 0.22, 0,
                new Particle.DustOptions(STEEL_DUST, 1.0f));
        world.spawnParticle(Particle.DUST, at, 2, 0.2, 0.2, 0.2, 0,
                new Particle.DustOptions(RED_SPARK, 0.9f));
    }

    // ---- sustained-grind per-wielder state ----------------------------------------

    /** One wielder's live grind: the per-block break progress they've accumulated on the 3x3 face. */
    private static final class GrindSession {
        /** Block key -> accumulated break progress in [0,1). */
        private final Map<Long, Double> progress = new HashMap<>();

        /** Clear crack overlays (and reset progress) on any block no longer under the cutter this run. */
        private void reconcile(Player player, Set<Long> keep) {
            if (progress.isEmpty()) return;
            World world = player.getWorld();
            var it = progress.keySet().iterator();
            while (it.hasNext()) {
                long bk = it.next();
                if (keep.contains(bk)) continue;
                player.sendBlockDamage(new Location(world,
                        Block.getBlockKeyX(bk), Block.getBlockKeyY(bk), Block.getBlockKeyZ(bk)), 0.0f);
                it.remove();
            }
        }

        /** Wipe every overlay and forget all progress — on spin-down or looking away. */
        private void clearCracks(Player player) {
            if (progress.isEmpty()) return;
            World world = player.getWorld();
            for (long bk : progress.keySet()) {
                player.sendBlockDamage(new Location(world,
                        Block.getBlockKeyX(bk), Block.getBlockKeyY(bk), Block.getBlockKeyZ(bk)), 0.0f);
            }
            progress.clear();
        }
    }

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        sessions.remove(id);   // if they were grinding (overlays die with the client anyway)
        bursting.remove(id);   // if they were a body mid-burst
        ticking.remove(id);
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : burstTasks) task.cancel();
        burstTasks.clear();
        bursting.clear();
        ticking.clear();
        // Clear each grinder's client-side block-crack overlays before dropping the sessions, else the
        // cracks linger on the client as cosmetic residue until the player relogs.
        for (Map.Entry<UUID, GrindSession> e : sessions.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null) e.getValue().clearCracks(p);
        }
        sessions.clear();
    }

    // ---- lore ----------------------------------------------------------------------

    /** Primary — the bare-metal body. Display name, "How to use:", ability headers. */
    private static final TextColor NAME       = TextColor.color(0xD9DCE2); // name — light silver
    /** Secondary — the warning paint. The Abnormality title line. */
    private static final TextColor RED        = TextColor.color(0xC0342B); // sawtooth / dicing accent

    // Action-bar palette, kept apart from the lore palette so tuning one never disturbs the other.
    private static final TextColor SILVER     = TextColor.color(0xC2C7CF); // action-bar / gauge silver
    private static final TextColor STEEL      = TextColor.color(0x8A8F98); // spin-down / no-target gray

    // Particle colours (kept apart from both palettes so tuning one never disturbs the others).
    private static final Color     STEEL_DUST = Color.fromRGB(0xC6, 0xCA, 0xD2); // silver swarf / debris (dust)
    private static final Color     RED_SPARK  = Color.fromRGB(0xD0, 0x3A, 0x2E); // red warning-paint spark (dust)

    // The moveset below is read off the code, not off the old hand-rolled block it replaces: the burst is
    // six bites that stand in for the cancelled vanilla blow, and the sustained grind is a TOGGLE, because
    // right-mouse-hold isn't detectable here. Ability names are descriptive placeholders — this weapon's
    // spec never named them.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Grinder Mk.4",
            "All-Around Helper",
            NAME,
            RED,
            List.of(
                    "The sharp sawtooth of the grinder",
                    "makes a clean cut through its foe.",
                    "",
                    "Machines have no morals of their own."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Grinding Burst",
                            "The swing itself lands no blow — in",
                            "its place the motor drives 6 grinding",
                            "bites (1.1 each) into one body over",
                            "~0.75s. No knockback; armour applies."),
                    new EgoLore.Ability("[Shift + Right-click] Sustained Grind",
                            "A toggle — press again, release sneak,",
                            "or unequip to spin down. Grinds bodies",
                            "in a 3.5 block cone for 2 damage every",
                            "0.5s, no knockback, and chews the 3x3",
                            "block face you look at — stone in",
                            "~1.5s — dropping blocks normally.")
            ));
}
