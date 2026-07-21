package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
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
 * <p>The bonus damage is applied via {@code event.setDamage(...)} (never {@code victim.damage()} — that
 * would re-enter this dispatch). Per-victim stasis state is tracked so overlapping procs don't stack tasks
 * or leak a mob's AI-off flag; it is cleared on quit and on disable.
 *
 * <p>Right-click calls the second power: it summons the <b>Burrowing Heaven</b> itself as a watching
 * eye-tree ({@link EyeTree}) rooted a few blocks ahead. The tree throws homing eyes ({@link HeavenBolt}) at
 * whatever the wielder aims at (or last struck), on a placeholder timer; killing a player hangs their skull
 * on it for more life and faster volleys, up to {@link #MAX_STACKS}. Its eyes and skulls are
 * {@code setPersistent(false)} display entities, tracked and reaped on unequip, quit, timeout and disable,
 * with a tag-sweep backstop. All summon numbers are first-pass, flagged for the balance wave.
 */
public final class HeavenWeapon implements Weapon {

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
     * A landed blow. Vanilla netherite damage (and its one point of blade wear) is left intact. If the
     * victim is roughly facing the attacker — looking into the eye — the blow bites +10% harder, and on a
     * {@link #STUN_CHANCE} roll the heaven opens beneath them: a brief stasis that pins them in place.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        lastHit.put(attacker.getUniqueId(), victim.getUniqueId()); // remembered as the tree's fallback target

        if (!isLookingAt(victim, attacker)) return;

        // Struck while staring into the eye — the wound bites harder.
        event.setDamage(event.getDamage() * DAMAGE_MULT);

        if (ThreadLocalRandom.current().nextDouble() < STUN_CHANCE) {
            openHeaven(victim);
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
            wielder.sendActionBar(EgoHud.status("The heaven already watches.", EYE));
            return;
        }

        Location anchor = summonAnchor(wielder);
        EyeTree tree = new EyeTree(id, anchor);
        tree.raise();
        trees.put(id, tree);
        tree.runTaskTimer(plugin, 1L, 1L);

        wielder.sendActionBar(EgoHud.status("The heaven opens its eyes.", CRIMSON));
        World world = anchor.getWorld();
        world.playSound(anchor, Sound.ENTITY_WARDEN_EMERGE, 0.9f, 0.7f);
        world.playSound(anchor, Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.6f);
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
        private final List<ItemDisplay> eyes = new ArrayList<>();
        private final List<ItemDisplay> skulls = new ArrayList<>();
        private int age = 0;
        private int lifetime = BASE_LIFETIME;
        private int stacks = 0;

        EyeTree(UUID ownerId, Location anchor) {
            this.ownerId = ownerId;
            this.anchor = anchor;
        }

        /** Spawn the eye displays: one great eye at the fork, a scatter of smaller ones out along the branches. */
        void raise() {
            eyes.add(spawnEye(anchor.clone().add(0, TREE_FORK_Y, 0), 0.9f)); // the great central eye
            double[][] slots = {
                    {-TREE_SPREAD,        TREE_FORK_Y + 0.7},
                    { TREE_SPREAD,        TREE_FORK_Y + 0.6},
                    {-TREE_SPREAD * 0.6,  TREE_FORK_Y + 1.2},
                    { TREE_SPREAD * 0.7,  TREE_FORK_Y + 1.1},
                    { 0.0,                TREE_FORK_Y + 1.6},
            };
            for (double[] s : slots) {
                eyes.add(spawnEye(anchor.clone().add(s[0], s[1], 0), 0.4f));
            }
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

            if (age % 4 == 0) drawBranches();

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

        /** Launch one eye from a random point on the tree toward the target. */
        private void throwEye(Player owner, LivingEntity target) {
            Location origin = eyes.isEmpty()
                    ? anchor.clone().add(0, TREE_FORK_Y, 0)
                    : eyes.get(ThreadLocalRandom.current().nextInt(eyes.size())).getLocation();
            new HeavenBolt(this, owner.getUniqueId(), origin, target).runTaskTimer(plugin, 1L, 1L);
        }

        /** A player fell to the tree or its eyes: hang their skull, extend the hold, and quicken the volley. */
        void onKilledPlayer(Player victim) {
            if (stacks >= MAX_STACKS) return;
            stacks++;
            lifetime += KILL_LIFETIME;
            hangSkull(victim);
            anchor.getWorld().playSound(anchor, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f);
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

        /** Take the tree down: remove every display it owns, drop it from the registry, and stop ticking. */
        void dismiss() {
            anchor.getWorld().playSound(anchor, Sound.ENTITY_WARDEN_DEATH, 0.7f, 1.2f);
            reap();
            trees.remove(ownerId, this);
        }

        /** Remove every display and stop ticking, without touching the registry map (for a bulk sweep). */
        void reap() {
            for (ItemDisplay d : eyes)   if (d != null && d.isValid()) d.remove();
            for (ItemDisplay d : skulls) if (d != null && d.isValid()) d.remove();
            eyes.clear();
            skulls.clear();
            cancel();
        }

        /** The crimson branch silhouette: a trunk and two fanned wings of dark-red motes around the anchor. */
        private void drawBranches() {
            World world = anchor.getWorld();
            for (double y = 0.0; y <= TREE_FORK_Y; y += 0.3) {
                world.spawnParticle(Particle.DUST, anchor.clone().add(0, y, 0), 1, 0.03, 0.03, 0.03, 0, DARKRED_DUST);
            }
            final int branches = 5;
            for (int side = -1; side <= 1; side += 2) {
                for (int b = 0; b < branches; b++) {
                    double t = (b + 1) / (double) branches;
                    double outX = side * TREE_SPREAD * t;
                    double topY = TREE_FORK_Y + t * 1.4;
                    for (double s = 0.0; s <= 1.0; s += 0.34) {
                        Location p = anchor.clone().add(outX * s, TREE_FORK_Y + (topY - TREE_FORK_Y) * s, 0);
                        world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, CRIMSON_DUST);
                    }
                }
            }
        }
    }

    /** Spawn one eye display (an ender eye, billboarded flat) at {@code at}, scaled to {@code scale}. */
    private ItemDisplay spawnEye(Location at, float scale) {
        ItemStack eye = new ItemStack(Material.ENDER_EYE);
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(eye);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setPersistent(false);
            d.addScoreboardTag(TREE_TAG);
        });
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

    // The two [Left Click] entries are the melee gaze (onHit); the [Right Click] entry is the Burrowing
    // Heaven summon (onInteract). Sneak-right-click does nothing yet, held for Heaven's later skills. The
    // old how-to read "Its gaze may pin them in stasis", which suggested the pin was its own thing; the
    // roll in onHit sits INSIDE the isLookingAt guard, so a foe who is not facing you can never be
    // pinned at all. The moveset follows the code.

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
                    new EgoLore.Ability("[Left Click] Eye Contact Bonus",
                            "Melee hits on a foe who is facing",
                            "you deal +10% damage. A foe looking",
                            "away takes no bonus."),
                    new EgoLore.Ability("[Left Click] Stasis Pin",
                            "Each hit that lands the eye contact",
                            "bonus has a 25% chance to open the",
                            "heaven: the foe is pinned in place",
                            "for 1.5 seconds, crushed to a crawl.",
                            "Mobs also lose their AI for the hold."),
                    new EgoLore.Ability("[Right Click] Burrowing Heaven",
                            "Summon a watching eye-tree ahead of",
                            "you. It throws eyes at whatever you",
                            "aim at or last struck. When it kills",
                            "a player it hangs their skull for",
                            "more life and more eyes, up to 5.")
            ));
}
