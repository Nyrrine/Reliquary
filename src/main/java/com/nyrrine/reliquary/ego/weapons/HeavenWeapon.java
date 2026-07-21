package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
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
 * Heaven — "The Burrowing Heaven" (Lobotomy Corp E.G.O, WAW). A macabre blade in crimson and dark red,
 * marked by the single large sickly-yellow eye that stares from its heart. Its heaven is not a light
 * above but a thing that watches, and burrows.
 *
 * <p>Primarily a melee weapon: it rides the vanilla NETHERITE_SWORD swing (never cancelled), which deals
 * its normal damage and wears the blade one point per hit — nothing extra is needed for durability. The
 * soul of the weapon is the gaze gimmick in {@link #onHit}: the abnormality feeds on being <b>seen</b>. When a
 * struck victim is roughly facing their attacker — looking straight into the eye — the blow bites
 * {@link #DAMAGE_MULT harder} and, on a {@link #STUN_CHANCE small chance}, the heaven opens beneath them:
 * a brief stasis that pins the victim in place while crimson rises from the ground and a great
 * eye-yellow ring stares up around them.
 *
 * <p>The stasis is driven by a self-cancelling {@link StasisTask}: each tick it zeroes the target's
 * velocity (killing walk, sprint <b>and</b> jump — nothing survives a reset velocity) and re-applies a
 * crushing Slowness, then paints the eye. For mobs it suspends {@link Mob#setAI(boolean) AI} for the
 * duration through the framework's {@code suspendAi}/{@code restoreAi}, which always undoes the hold — on
 * completion, on death, and on a chunk unload or plugin disable, so a mob can never be left saved mindless.
 * Players cannot be fully frozen server-side without movement packets, so for players the stasis is a
 * best-effort root: per-tick velocity zero + Slowness 6 + the same heavy VFX. A determined player can
 * still nudge themselves, but only barely.
 *
 * <p>Damage bonuses are applied via {@code event.setDamage(...)} on the vanilla swing; the armour-pierce of
 * <b>Held in Heaven</b> scales that same value through the framework's {@code pierceInput} so the blow ignores
 * part of the target's armour while keeping its knockback, sweep, on-hit enchant procs and durability. Per-
 * victim stasis and eye-mark state is tracked so overlapping procs don't stack tasks or leak a mob's AI-off
 * flag; all of it is cleared on quit and on disable.
 *
 * <p>Right-click calls the second power: it summons the <b>Burrowing Heaven</b> itself as a watching
 * eye-tree ({@link EyeTree}) rooted a few blocks ahead. The tree throws homing eyes ({@link HeavenBolt}) at
 * whatever the wielder aims at (or last struck), on a placeholder timer; killing a player hangs their skull
 * on it for more life and faster volleys, up to {@link #MAX_STACKS}. The trunk, branches and eyes are drawn
 * entirely in particles; only the hung skulls are {@code setPersistent(false)} display entities, tracked and
 * reaped on unequip, quit, timeout and disable, with a tag-sweep backstop. All summon numbers are
 * first-pass, flagged for the balance wave.
 */
public final class HeavenWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Heaven. */
    private final NamespacedKey key;

    /** Entities currently held in stasis — guards against stacking tasks / leaking a mob's AI flag. */
    private final Set<UUID> stunned = new HashSet<>();

    /** Live stasis holds — reaped on disable so a mob frozen at reload-time gets its AI restored. */
    private final Set<StasisTask> activeStasis = new HashSet<>();

    /** One live eye-tree per wielder — reaped on unequip, quit, timeout and disable. */
    private final Map<UUID, EyeTree> trees = new HashMap<>();

    /** Wielder -> the last living body they struck; a fallback target when they are not aiming at one. */
    private final Map<UUID, UUID> lastHit = new HashMap<>();

    /** The Unblinking Eye: bodies currently eye-marked, victim UUID -> its mark task. Reaped on quit/disable. */
    private final Map<UUID, EyeMark> marks = new HashMap<>();

    // Tuning — the gaze rewards striking a victim who is looking at you.
    /** Dot of the victim's facing vs. the direction to the attacker above which they count as "looking". */
    private static final double LOOK_THRESHOLD = 0.4;
    /** Bonus damage multiplier when the victim is looking at the attacker (+10%). */
    private static final double DAMAGE_MULT    = 1.1;
    /** Chance, on a looking hit, that the heaven opens and pins the victim. */
    private static final double STUN_CHANCE    = 0.25;
    /** How long the stasis holds — ~1.5s. */
    private static final int    STASIS_TICKS   = 30;
    /** Slowness amplifier during stasis — 6 → Slowness VII, a near-total crawl on top of the velocity zero. */
    private static final int    SLOWNESS_AMP   = 6;

    // ---- skill tuning: Held in Heaven + The Unblinking Eye ------------------------
    // Balance-approved shape (§5-A / §5-C, 2026-07-21); magnitudes flagged for the balance wave.
    /** Held in Heaven: fraction of armour a hit on a pinned or looking body ignores (via pierceInput). */
    private static final double HELD_PIERCE      = 0.40;
    /** The Unblinking Eye: extra Heaven damage a hit on an already eye-marked body deals (+15%). */
    private static final double UNBLINKING_MULT  = 1.15;
    /** How long an eye-mark lasts, in ticks (~6s); refreshed by each hit. */
    private static final int    UNBLINKING_TICKS = 120;

    // ---- summon tuning ------------------------------------------------------------
    // PLACEHOLDER NUMBERS — every value below is a first-pass guess, flagged for Nyrrine's balance wave.
    /** Blocks in front of the wielder the tree roots itself. */
    private static final double SUMMON_DISTANCE      = 3.0;
    /** Base life of a summoned tree, in ticks (~20s). */
    private static final int    BASE_LIFETIME        = 20 * 20;
    /** Extra life each hung player-skull grants (~30s). */
    private static final int    KILL_LIFETIME        = 30 * 20;
    /** Most skulls a tree carries — the on-kill effect stacks this far and no further. */
    private static final int    MAX_STACKS           = 5;
    /** Damage a thrown eye deals on contact. */
    private static final double BOLT_DAMAGE          = 4.0;
    /** Ticks between volleys at zero skulls; each skull shortens it toward the floor. */
    private static final int    BASE_FIRE_TICKS      = 30;
    private static final int    FIRE_TICKS_PER_STACK = 4;
    private static final int    MIN_FIRE_TICKS       = 8;
    /** How far the tree looks for the wielder's target (aim raytrace, then last-hit fallback). */
    private static final double TARGET_RANGE         = 24.0;
    /** A thrown eye's step per tick, its fizzle-out age, and its contact radius. */
    private static final double BOLT_SPEED           = 0.9;
    private static final int    BOLT_MAX_TICKS       = 60;
    private static final double BOLT_HIT_RADIUS      = 1.2;
    /** How the eye-tree stands: fork height and how far the branch-eyes spread. */
    private static final double TREE_FORK_Y          = 1.5;
    private static final double TREE_SPREAD          = 1.6;

    /** Eye positions relative to the anchor (x, y, z): the great central eye first, then scattered ones, in 3D. */
    private static final double[][] EYE_OFFSETS = {
            { 0.00, TREE_FORK_Y + 0.00,  0.00},   // the great central eye (drawn larger)
            {-0.95, TREE_FORK_Y + 0.85,  0.35},
            { 1.00, TREE_FORK_Y + 0.75, -0.25},
            {-0.55, TREE_FORK_Y + 1.45, -0.40},
            { 0.65, TREE_FORK_Y + 1.35,  0.45},
            { 0.05, TREE_FORK_Y + 1.75,  0.05},
    };
    /** Branch tip offsets relative to the fork (x, y, z) — fanned in x for the wings, varied in z for depth. */
    private static final double[][] BRANCH_TIPS = {
            {-1.55, 1.15,  0.45}, { 1.55, 1.05, -0.35},
            {-1.10, 1.55, -0.55}, { 1.20, 1.45,  0.55},
            {-0.55, 1.80,  0.25}, { 0.60, 1.70, -0.20},
            { 0.00, 2.00,  0.00},
    };

    /** Scoreboard tag on every display the tree owns — the belt-and-braces reap key. */
    private static final String TREE_TAG = "reliquary_heaven_tree";

    public HeavenWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "heaven");
    }

    @Override
    public String id() {
        return "heaven";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.HEAVEN.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.HEAVEN.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.HEAVEN);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: the gaze rewards being seen --------------------------------------

    /**
     * A landed blow, carrying Heaven's melee passives:
     * <ul>
     *   <li><b>Eye Contact</b> — a hit on a victim facing the attacker bites +10% harder.</li>
     *   <li><b>Held in Heaven</b> — a hit on a pinned or looking body ignores ~40% of its armour, the vanilla
     *       swing scaled through {@code pierceInput} so it keeps its knockback and enchant procs.</li>
     *   <li><b>The Unblinking Eye</b> — every hit eye-marks the body for ~6s; a marked body cannot turn
     *       invisible and takes +15% from Heaven.</li>
     * </ul>
     * A looking hit still rolls {@link #STUN_CHANCE} to open the stasis beneath the victim.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID vid = victim.getUniqueId();
        lastHit.put(attacker.getUniqueId(), vid); // remembered as the tree's fallback target

        boolean marked = marks.containsKey(vid); // Unblinking Eye: already eye-marked before this blow?
        markTarget(victim);                       // (re)mark the body for the watch

        boolean looking = isLookingAt(victim, attacker);
        boolean pinned  = stunned.contains(vid);

        double dmg = event.getDamage();
        if (marked)  dmg *= UNBLINKING_MULT; // +15% on an eye-marked body
        if (looking) dmg *= DAMAGE_MULT;     // +10% for meeting the eye

        if (looking || pinned) {
            // Held in Heaven: scale the swing so it ignores ~40% of the target's armour, keeping the vanilla
            // blow's knockback, sweep, on-hit enchant procs and durability (no cancel, no re-deal).
            event.setDamage(plugin.weapons().pierceInput(victim, dmg, HELD_PIERCE));
        } else if (marked) {
            event.setDamage(dmg); // not pinned or looking: just the marked bonus, armour applies as normal
        }

        if (looking && ThreadLocalRandom.current().nextDouble() < STUN_CHANCE) {
            openHeaven(victim);
        }
    }

    /**
     * The Unblinking Eye: mark (or refresh) a body for {@link #UNBLINKING_TICKS}. While marked it cannot hold
     * an invisibility effect and wears a small eye; a hit on an already-marked body deals {@link #UNBLINKING_MULT}.
     */
    private void markTarget(LivingEntity victim) {
        EyeMark m = marks.get(victim.getUniqueId());
        if (m != null) {
            m.refresh();
        } else {
            m = new EyeMark(victim.getUniqueId());
            marks.put(victim.getUniqueId(), m);
            m.runTaskTimer(plugin, 2L, 2L);
        }
    }

    /** A live eye-mark: every 2 ticks it strips invisibility and paints a small eye, for {@link #UNBLINKING_TICKS}. */
    private final class EyeMark extends BukkitRunnable {
        private final UUID victimId;
        private int ticksLeft = UNBLINKING_TICKS;

        EyeMark(UUID victimId) { this.victimId = victimId; }

        void refresh() { ticksLeft = UNBLINKING_TICKS; }

        @Override
        public void run() {
            ticksLeft -= 2;
            if (!(plugin.getServer().getEntity(victimId) instanceof LivingEntity victim)
                    || victim.isDead() || ticksLeft <= 0) {
                end();
                return;
            }
            victim.removePotionEffect(PotionEffectType.INVISIBILITY); // the watched cannot hide
            victim.getWorld().spawnParticle(Particle.DUST,
                    victim.getLocation().add(0, victim.getHeight() + 0.4, 0), 1, 0.05, 0.05, 0.05, 0, EYE_DUST);
        }

        void end() {
            marks.remove(victimId, this);
            cancel();
        }
    }

    /**
     * True if {@code victim} is roughly facing {@code attacker}: the victim's facing direction dotted
     * against the (attacker - victim) direction clears {@link #LOOK_THRESHOLD}.
     */
    private boolean isLookingAt(LivingEntity victim, LivingEntity attacker) {
        Vector facing = victim.getLocation().getDirection();
        if (facing.lengthSquared() < 1.0e-6) return false;
        Vector toAttacker = attacker.getLocation().toVector().subtract(victim.getLocation().toVector());
        if (toAttacker.lengthSquared() < 1.0e-6) return false;
        return facing.normalize().dot(toAttacker.normalize()) >= LOOK_THRESHOLD;
    }

    // ---- the stasis: heaven burrows and holds --------------------------------------

    /** Open the heaven beneath a victim: pin them for {@link #STASIS_TICKS}, cutting mob AI for the hold. */
    private void openHeaven(LivingEntity victim) {
        UUID id = victim.getUniqueId();
        if (!stunned.add(id)) return; // already held — don't stack tasks or double-touch the AI flag

        // Cut AI for the duration so mobs don't path/attack while pinned. Restored on task completion.
        if (victim instanceof Mob mob) {
            plugin.weapons().suspendAi(mob);
        }

        openSfx(victim.getLocation());
        StasisTask task = new StasisTask(victim);
        activeStasis.add(task); // tracked so onDisable can restore a frozen mob's AI on reload
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The per-tick hold. Zeroes velocity (killing walk/sprint/jump alike), re-applies crushing Slowness,
     * and paints the crimson-and-eye VFX. Self-cancels after {@link #STASIS_TICKS} ticks or once the
     * target is gone, handing a mob's AI back through the framework's {@code restoreAi} — which restores it
     * whether the target died, unloaded, or the plugin disabled.
     */
    private final class StasisTask extends BukkitRunnable {
        private final LivingEntity target;
        private int ticks = 0;

        StasisTask(LivingEntity target) {
            this.target = target;
        }

        @Override
        public void run() {
            if (ticks++ >= STASIS_TICKS || !target.isValid() || target.isDead()) {
                finish();
                return;
            }

            // Pin: reset velocity every tick so nothing — walk, sprint, or jump — can carry them.
            target.setVelocity(new Vector(0, 0, 0));
            // Crushing crawl on top of the velocity zero; refreshed so it never lapses mid-hold.
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 6, SLOWNESS_AMP, false, false, false));

            stasisVfx(target.getLocation());
        }

        private void finish() {
            stunned.remove(target.getUniqueId());
            if (target instanceof Mob mob) {
                plugin.weapons().restoreAi(mob);
            }
            activeStasis.remove(this);
            cancel();
        }

        /** Disable-time reap: restore a still-frozen mob's AI and cancel; the caller clears the tracking set. */
        void shutdown() {
            if (target instanceof Mob mob) {
                plugin.weapons().restoreAi(mob);
            }
            cancel();
        }
    }

    // ---- the summon: the Burrowing Heaven eye-tree --------------------------------

    /**
     * Right-click summons the eye-tree; sneak-right-click is left free for Heaven's other skills, and the
     * onHit gaze/stasis above is untouched.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) return;
        summon(player);
    }

    /**
     * Root a watching eye-tree a few blocks ahead of the wielder. One per wielder: a second call while a
     * tree still stands is refused. The tree throws eyes at whatever the wielder aims at (or last struck),
     * lives {@link #BASE_LIFETIME} ticks, and on killing a player hangs their skull for more life and faster
     * volleys, up to {@link #MAX_STACKS}.
     */
    private void summon(Player wielder) {
        UUID id = wielder.getUniqueId();
        if (trees.containsKey(id)) {
            wielder.sendActionBar(EgoHud.status("Burrowing Heaven already stands.", EYE));
            return;
        }

        Location anchor = summonAnchor(wielder);
        EyeTree tree = new EyeTree(id, anchor);
        trees.put(id, tree);
        tree.runTaskTimer(plugin, 1L, 1L);

        wielder.sendActionBar(EgoHud.status("Burrowing Heaven", CRIMSON));
        // Soft and low: the Warden's emerge-roar read as too loud/harsh in playtest. A quiet sculk burrow
        // under a faint gaze instead. (Volumes placeholder, flagged for the balance wave.)
        World world = anchor.getWorld();
        world.playSound(anchor, Sound.BLOCK_SCULK_SPREAD, 0.30f, 0.7f);
        world.playSound(anchor, Sound.ENTITY_ENDERMAN_STARE, 0.25f, 0.6f);
    }

    /** The tree's root spot: {@link #SUMMON_DISTANCE} blocks ahead of the wielder along their flat facing. */
    private Location summonAnchor(Player wielder) {
        Vector flat = wielder.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1.0e-6) flat = new Vector(1, 0, 0);
        flat.normalize().multiply(SUMMON_DISTANCE);
        Location at = wielder.getLocation().add(flat);
        at.setYaw(0f);
        at.setPitch(0f);
        return at;
    }

    /**
     * The wielder's current target: whatever living body their eye-line first meets within
     * {@link #TARGET_RANGE}, or, if they are not aiming at one, the last body they struck (still valid, same
     * world, in range). Null if neither resolves.
     */
    private LivingEntity currentTarget(Player wielder) {
        World world = wielder.getWorld();
        Location eye = wielder.getEyeLocation();
        RayTraceResult rt = world.rayTraceEntities(eye, eye.getDirection(), TARGET_RANGE, 0.6,
                e -> e instanceof LivingEntity && !e.equals(wielder) && !e.isDead());
        if (rt != null && rt.getHitEntity() instanceof LivingEntity aimed) return aimed;

        UUID lastId = lastHit.get(wielder.getUniqueId());
        if (lastId != null && plugin.getServer().getEntity(lastId) instanceof LivingEntity last
                && !last.isDead() && last.getWorld().equals(world)
                && last.getLocation().distanceSquared(eye) <= TARGET_RANGE * TARGET_RANGE) {
            return last;
        }
        return null;
    }

    /** A stationary eye-tree: throws eyes at the wielder's target, and grows when it kills a player. */
    private final class EyeTree extends BukkitRunnable {
        private final UUID ownerId;
        private final Location anchor;
        private final List<ItemDisplay> skulls = new ArrayList<>();
        private int age = 0;
        private int lifetime = BASE_LIFETIME;
        private int stacks = 0;

        EyeTree(UUID ownerId, Location anchor) {
            this.ownerId = ownerId;
            this.anchor = anchor;
        }

        @Override
        public void run() {
            age++;
            Player owner = plugin.getServer().getPlayer(ownerId);
            // Dismiss the instant the wielder can no longer sustain it: gone, unequipped, world-changed, or timed out.
            if (owner == null || !owner.isValid()
                    || !owner.getWorld().equals(anchor.getWorld())
                    || !matches(owner.getInventory().getItemInMainHand())
                    || age > lifetime) {
                dismiss();
                return;
            }

            if (age % 3 == 0) drawTree();

            if (age % fireTicks() == 0) {
                LivingEntity target = currentTarget(owner);
                if (target != null) {
                    int shots = shotsPerVolley();
                    for (int i = 0; i < shots; i++) throwEye(owner, target);
                }
            }
        }

        private int fireTicks() {
            return Math.max(MIN_FIRE_TICKS, BASE_FIRE_TICKS - stacks * FIRE_TICKS_PER_STACK);
        }

        private int shotsPerVolley() {
            return 1 + stacks / 2; // 0-1 skulls -> 1, 2-3 -> 2, 4-5 -> 3
        }

        /** Launch one eye from a random eye position on the tree toward the target. */
        private void throwEye(Player owner, LivingEntity target) {
            double[] o = EYE_OFFSETS[ThreadLocalRandom.current().nextInt(EYE_OFFSETS.length)];
            Location origin = anchor.clone().add(o[0], o[1], o[2]);
            new HeavenBolt(this, owner.getUniqueId(), origin, target).runTaskTimer(plugin, 1L, 1L);
        }

        /** A player fell to the tree or its eyes: hang their skull, extend the hold, and quicken the volley. */
        void onKilledPlayer(Player victim) {
            if (stacks >= MAX_STACKS) return;
            stacks++;
            lifetime += KILL_LIFETIME;
            hangSkull(victim);
            anchor.getWorld().playSound(anchor, Sound.ENTITY_WARDEN_HEARTBEAT, 0.40f, 0.6f); // softened with the summon
        }

        /** Hang the killed player's head on the next open branch slot. */
        private void hangSkull(Player victim) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(victim);
                head.setItemMeta(sm);
            }
            double side = (skulls.size() % 2 == 0) ? -1 : 1;
            double up = TREE_FORK_Y + 0.4 + skulls.size() * 0.25;
            Location at = anchor.clone().add(side * TREE_SPREAD * 0.5, up, 0);
            ItemDisplay d = anchor.getWorld().spawn(at, ItemDisplay.class, disp -> {
                disp.setItemStack(head);
                disp.setBillboard(Display.Billboard.CENTER);
                disp.setBrightness(new Display.Brightness(12, 15));
                disp.setTransformation(new Transformation(
                        new Vector3f(), new Quaternionf(),
                        new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                disp.setPersistent(false);
                disp.addScoreboardTag(TREE_TAG);
            });
            skulls.add(d);
        }

        /** Take the tree down: remove the skulls it hung, drop it from the registry, and stop ticking. */
        void dismiss() {
            anchor.getWorld().playSound(anchor, Sound.BLOCK_SCULK_SPREAD, 0.25f, 0.5f); // a soft fade, not the Warden's death cry
            reap();
            trees.remove(ownerId, this);
        }

        /** Remove the hung skull displays and stop ticking, without touching the registry map (for a bulk sweep). */
        void reap() {
            for (ItemDisplay d : skulls) if (d != null && d.isValid()) d.remove();
            skulls.clear();
            cancel();
        }

        /**
         * The eye-tree, drawn in particles: a dark-red trunk, a 3D fan of crimson branches with real depth
         * (fanned in x, varied in z, bowed upward), and eyes of a golden sclera sphere around a crimson pupil.
         */
        private void drawTree() {
            World world = anchor.getWorld();

            // Trunk: a dark-red column up to the fork.
            for (double y = 0.0; y <= TREE_FORK_Y; y += 0.25) {
                world.spawnParticle(Particle.DUST, anchor.clone().add(0, y, 0), 1, 0.02, 0.02, 0.02, 0, DARKRED_DUST);
            }
            // Branches: from the fork out to each tip, bowed so they arc rather than run straight.
            Location fork = anchor.clone().add(0, TREE_FORK_Y, 0);
            for (double[] tip : BRANCH_TIPS) {
                for (double s = 0.15; s <= 1.0; s += 0.15) {
                    double bow = Math.sin(s * Math.PI) * 0.20;
                    Location p = fork.clone().add(tip[0] * s, tip[1] * s + bow, tip[2] * s);
                    world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, CRIMSON_DUST);
                }
            }
            // Eyes: the central one larger, the rest smaller, each a golden sphere around a crimson pupil.
            for (int i = 0; i < EYE_OFFSETS.length; i++) {
                double[] o = EYE_OFFSETS[i];
                drawEye(world, anchor.clone().add(o[0], o[1], o[2]), i == 0 ? 0.34 : 0.20);
            }
        }

        /** One eye: two crossed rings of golden sclera motes (a little sphere) around a crimson pupil. */
        private void drawEye(World world, Location centre, double radius) {
            final int points = 6;
            for (int i = 0; i < points; i++) {
                double a = (Math.PI * 2 * i) / points;
                double c = Math.cos(a) * radius, s = Math.sin(a) * radius;
                world.spawnParticle(Particle.DUST, centre.clone().add(c, s, 0), 1, 0, 0, 0, 0, EYE_DUST);
                world.spawnParticle(Particle.DUST, centre.clone().add(c, 0, s), 1, 0, 0, 0, 0, EYE_DUST);
            }
            world.spawnParticle(Particle.DUST, centre, 1, 0.01, 0.01, 0.01, 0, CRIMSON_DUST); // the pupil
        }
    }

    /**
     * A thrown eye: a homing mote that steps toward its target each tick and, on contact, bites for
     * {@link #BOLT_DAMAGE}. The damage is routed through the framework's {@code dealing} fence so it is not
     * handed back to {@link #onHit} as a fresh swing. If it kills a player, its parent tree grows. It spawns
     * no entity — only particles — so nothing here needs reaping.
     */
    private final class HeavenBolt extends BukkitRunnable {
        private final EyeTree tree;
        private final UUID ownerId;
        private final Location pos;
        private final LivingEntity target;
        private int age = 0;

        HeavenBolt(EyeTree tree, UUID ownerId, Location origin, LivingEntity target) {
            this.tree = tree;
            this.ownerId = ownerId;
            this.pos = origin.clone();
            this.target = target;
        }

        @Override
        public void run() {
            if (age++ > BOLT_MAX_TICKS || !target.isValid() || target.isDead()
                    || !target.getWorld().equals(pos.getWorld())) {
                cancel();
                return;
            }

            Location aim = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            Vector step = aim.toVector().subtract(pos.toVector());
            double dist = step.length();
            if (dist <= BOLT_HIT_RADIUS) {
                strike();
                cancel();
                return;
            }
            pos.add(step.normalize().multiply(Math.min(BOLT_SPEED, dist)));
            World world = pos.getWorld();
            world.spawnParticle(Particle.DUST, pos, 2, 0.05, 0.05, 0.05, 0, EYE_DUST);
            world.spawnParticle(Particle.DUST, pos, 1, 0.04, 0.04, 0.04, 0, CRIMSON_DUST);
        }

        private void strike() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            boolean wasAlive = !target.isDead();
            // Fence the follow-up so it isn't re-dispatched into onHit as a fresh swing.
            plugin.weapons().dealing(ownerId, () -> {
                if (owner != null) target.damage(BOLT_DAMAGE, owner);
                else               target.damage(BOLT_DAMAGE);
            });
            Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            pos.getWorld().spawnParticle(Particle.DUST, at, 8, 0.2, 0.2, 0.2, 0, CRIMSON_DUST);
            pos.getWorld().playSound(at, Sound.ENTITY_ENDERMAN_HURT, 0.6f, 0.8f);
            if (wasAlive && target.isDead() && target instanceof Player victim) {
                tree.onKilledPlayer(victim);
            }
        }
    }

    // ---- presentation --------------------------------------------------------------

    /** The eye opening: a low burrowing rumble under an unblinking stare. Pitch-jittered per proc. */
    private void openSfx(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(at, Sound.ENTITY_WARDEN_DIG, 0.9f, 0.5f + rng.nextFloat() * 0.10f);      // the burrow
        world.playSound(at, Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.7f + rng.nextFloat() * 0.10f);  // the gaze
    }

    /**
     * The stasis mark: crimson motes rising from the ground beneath the pinned target, and a large
     * sickly eye-yellow ring staring up around their feet with a crimson pupil at its heart.
     */
    private void stasisVfx(Location at) {
        World world = at.getWorld();
        Location feet = at.clone().add(0, 0.05, 0);

        // Crimson / dark-red rising from the ground beneath them.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.3, 0), 6, 0.28, 0.45, 0.28, 0, CRIMSON_DUST);
        world.spawnParticle(Particle.DUST, feet, 3, 0.30, 0.05, 0.30, 0, DARKRED_DUST);

        // The eye: a large eye-yellow ring around the feet...
        final int points = 20;
        final double radius = 1.6;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * radius, 0.02, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.01, 0.02, 0, EYE_DUST);
        }
        // ...with a crimson pupil at its heart.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.05, 0), 4, 0.18, 0.02, 0.18, 0, CRIMSON_DUST);
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        stunned.remove(id);
        lastHit.remove(id);
        EyeTree tree = trees.remove(id);
        if (tree != null) tree.reap(); // the wielder left — take their tree down with them
        EyeMark mark = marks.remove(id);
        if (mark != null) mark.cancel(); // the quitting body was eye-marked — drop the mark
    }

    @Override
    public void onDisable() {
        // A mob frozen at disable/reload-time would stay AI-disabled after reload — cancel every live hold
        // and restore any still-valid frozen mob's AI before clearing state.
        for (StasisTask task : new ArrayList<>(activeStasis)) {
            task.shutdown();
        }
        activeStasis.clear();
        stunned.clear();

        // Take down every live eye-tree, then sweep the worlds for any stray tagged display it may have left.
        for (EyeTree tree : new ArrayList<>(trees.values())) {
            tree.reap();
        }
        trees.clear();
        lastHit.clear();
        sweepTreeOrphans();

        // Cancel every live eye-mark so no strip-invisibility task survives a reload.
        for (EyeMark mark : marks.values()) mark.cancel();
        marks.clear();
    }

    /** Belt-and-braces: reap any display carrying the tree tag across all loaded worlds. */
    private void sweepTreeOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(TREE_TAG)) e.remove();
            }
        }
    }

    // ---- colours / particles ------------------------------------------------------

    /** Primary — the blade's crimson. Display name, "How to use:", ability headers. */
    private static final TextColor CRIMSON = TextColor.color(0xDC143C);
    /**
     * Secondary — the sickly yellow of the eye at the blade's heart. The Abnormality title line.
     *
     * <p>This colour has always been in Heaven's palette; it used to pick out the single word "eye" inside
     * the flavour line. The shared tooltip paints the whole flavour block in one off-white, so the yellow
     * moves up to the title line, where the Abnormality the eye belongs to now carries it.
     */
    private static final TextColor EYE     = TextColor.color(0xE6C74C);

    private static final Color CRIMSON_RGB = Color.fromRGB(0xDC, 0x14, 0x3C); // rising motes / pupil
    private static final Color DARKRED_RGB = Color.fromRGB(0x8B, 0x00, 0x00); // ground accent
    private static final Color EYE_RGB     = Color.fromRGB(0xE6, 0xC7, 0x4C); // the staring ring
    private static final Particle.DustOptions CRIMSON_DUST = new Particle.DustOptions(CRIMSON_RGB, 1.1f);
    private static final Particle.DustOptions DARKRED_DUST = new Particle.DustOptions(DARKRED_RGB, 1.3f);
    private static final Particle.DustOptions EYE_DUST     = new Particle.DustOptions(EYE_RGB, 1.2f);

    // ---- lore ---------------------------------------------------------------------

    // The passive entries are the melee gaze (onHit): Eye Contact / Held in Heaven, the Stasis Pin, and the
    // Unblinking Eye mark. The [Right Click] entry is the Burrowing Heaven summon (onInteract); sneak-right-
    // click does nothing yet, held for Heaven's later skills. The old how-to read "Its gaze may pin them in
    // stasis", which suggested the pin was its own thing; the roll in onHit sits INSIDE the isLookingAt guard,
    // so a foe who is not facing you can never be pinned at all. The moveset follows the code.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Heaven",
            "The Burrowing Heaven",
            CRIMSON,
            EYE,
            List.of(
                    "Just contain it in your sight.",
                    "A great yellow eye watches within."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Eye Contact",
                            "Hits on a foe facing you deal +10%.",
                            "A hit on a facing or pinned foe also",
                            "ignores about 40% of their armor."),
                    new EgoLore.Ability("[Passive] Stasis Pin",
                            "Each hit that lands the eye contact",
                            "bonus has a 25% chance to open the",
                            "heaven: the foe is pinned in place",
                            "for 1.5 seconds, crushed to a crawl.",
                            "Mobs also lose their AI for the hold."),
                    new EgoLore.Ability("[Passive] The Unblinking Eye",
                            "Your hits mark a foe for 6s. Marked",
                            "foes cannot turn invisible and take",
                            "+15% damage from Heaven."),
                    new EgoLore.Ability("[Right Click] Burrowing Heaven",
                            "Summon a watching eye-tree ahead of",
                            "you. It throws eyes at whatever you",
                            "aim at or last struck. When it kills",
                            "a player it hangs their skull for",
                            "more life and more eyes, up to 5.")
            ));
}
