package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mimicry — the abnormality <b>Nothing There</b>. The blade of the Red Mist: a thing wearing the shape of
 * a weapon, bone-white and dried-blood red, that is <em>fast</em> and that <em>drinks</em>.
 *
 * <p>A melee E.G.O Equipment riding the vanilla NETHERITE_SWORD swing (never cancelled) at a cleaver's
 * tuning — {@link EgoModels#MIMICRY} sets 6.5 damage at 1.8 speed, faster than the eye can follow. The
 * weapon sets its meta exactly once in {@link #createItem()} and never repaints, so it takes vanilla
 * enchants like any other E.G.O piece. Everything below is state kept beside the item, never on it.
 *
 * <ul>
 *   <li><b>Is That the Red Mist?!?</b> ({@link #onHit}, passive) — a landed blow mends the wielder for
 *       {@link #LIFESTEAL_FRACTION} of the damage it dealt, throttled to once per
 *       {@link #LIFESTEAL_THROTTLE_MS}. The drink never carries the wielder past their own
 *       {@link Attribute#MAX_HEALTH}.</li>
 *   <li><b>Nothing There</b> ({@link #onInteract}, shift + right-click) — every point of damage the
 *       wielder deals pools in a <b>reservoir</b>. Shift-right-click empties it as one strike around the
 *       wielder, dealing {@link #RELEASE_FRACTION} of the pooled total to everything inside
 *       {@link #RELEASE_RADIUS}: the weapon giving back what it swallowed. The reservoir evaporates after
 *       {@link #RESERVOIR_DECAY_MS} without the wielder dealing a blow (whether they are logged in for
 *       that lull or not), and is hard-capped at {@link #RESERVOIR_CAP} — see that constant.</li>
 *   <li><b>Onrush</b> ({@link #onInteract}, right-click) — a blink onto the foe the wielder last struck.
 *       If that foe is already faltering (a player under {@link #THRESH_PLAYER}, a mob under
 *       {@link #THRESH_MOB}, a boss under {@link #THRESH_BOSS}) Mimicry appears at its back and finishes
 *       it, and — resetting on the kill — costs no cooldown at all. If the foe is <em>not</em> executable
 *       the wielder still lands: a blink to the target's face, a devastating {@link #ONRUSH_DAMAGE}, and
 *       {@link #ONRUSH_COOLDOWN_MS} of silence.</li>
 * </ul>
 *
 * <p><b>The execute is a modest blow plus {@code setHealth(0)}, never an overkill number.</b> True
 * armour-bypass ({@code DamageType}/{@code DamageSource}) is not on this compile classpath, and buying a
 * kill with a huge damage figure is not a substitute for it: vanilla armour durability loss scales with
 * damage dealt (~damage/4 <em>per piece</em>), so a four-figure execute deletes a victim's whole armour set
 * in one hit. That was a real, shipped bug on this project. {@link #ONRUSH_EXECUTE_DAMAGE} is therefore
 * normal-sized and kill-credited, and {@code setHealth(0.0)} — which never runs the damage pipeline and so
 * takes no toll on armour — guarantees the finish through armour or Resistance. The same reasoning caps the
 * reservoir: {@link #RELEASE_FRACTION} of an <em>uncapped</em> pool would be both a one-shot-the-server
 * button and a second armour shredder wearing a different hat.
 *
 * <p><b>Re-entrancy.</b> Both abilities deal their damage with {@link LivingEntity#damage(double,
 * Entity)}, which fires its own {@code EntityDamageByEntityEvent} and re-enters {@link #onHit}. The
 * {@link #striking} fence makes that re-entrant call a complete no-op — no lifesteal, and critically no
 * pooling. Without it the reservoir would feed on its own release and every cast would be larger than the
 * last; the drink would compound off the same loop. Ability damage is therefore deliberately dry: it
 * neither heals the wielder nor refills the pool.
 *
 * <p>Mimicry spawns no entities and edits no blocks — it is particles, sound, and arithmetic — so there is
 * nothing for {@link #onDisable()} to reap from the world. Its per-wielder state is timestamped and swept
 * ({@link #prune}) rather than merely dropped on quit, because the reservoir's five-minute grace and the
 * Onrush cooldown must both survive a logout: the first is the design, the second is so that logging out is
 * never a free cooldown reset.
 */
public final class MimicryWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Mimicry. */
    private final NamespacedKey key;

    // ---- tuning: the passive drink -------------------------------------------------

    /** Fraction of a landed blow's damage the weapon drinks back into its wielder. */
    private static final double LIFESTEAL_FRACTION = 0.25;

    /** The drink is throttled to once per this window — it mends a quarter of a wound, not of every wound. */
    private static final long LIFESTEAL_THROTTLE_MS = 5_000L;

    // ---- tuning: Nothing There (the reservoir) -------------------------------------

    /** Share of the pooled total the release deals to each body caught in it. */
    private static final double RELEASE_FRACTION = 0.5;

    /** Radius of the release. One scan, one strike per body inside it. */
    private static final double RELEASE_RADIUS = 5.0;

    /** Below this the pool isn't worth a cast — the wielder keeps it rather than spending nothing. */
    private static final double RELEASE_MIN = 1.0;

    /** The pool evaporates after this long without the wielder landing a blow (online or logged out). */
    private static final long RESERVOIR_DECAY_MS = 300_000L; // 5 minutes out of combat

    /**
     * Hard ceiling on the pool, and the reason it exists: {@link #RELEASE_FRACTION} of an unbounded
     * "everything you have ever dealt" is a one-shot-the-server button on a busy box, and — exactly like the
     * old overkill execute — a four-figure damage number would strip every victim's armour set through
     * vanilla's damage/4-per-piece durability toll. Capped here, the worst release is
     * {@code RESERVOIR_CAP * RELEASE_FRACTION} = 60 raw: lethal to anything unarmoured, a serious but
     * survivable hit through good armour, and ~15 durability per piece — a scratch, not a deletion. 120
     * pooled is roughly a dozen landed cleaver blows, so the button still has to be earned.
     * <p><b>Deviation:</b> the brief specifies no cap. This number wants a designer's sign-off.
     */
    private static final double RESERVOIR_CAP = 120.0;

    // ---- tuning: Onrush ------------------------------------------------------------

    /** How far the wielder will blink to reach the foe they last struck. */
    private static final double ONRUSH_RANGE = 14.0;
    private static final double ONRUSH_RANGE_SQ = ONRUSH_RANGE * ONRUSH_RANGE;

    /** How long a struck foe stays "the one you last struck" and remains chaseable. */
    private static final long MARK_TTL_MS = 12_000L;

    /** HP fractions below which a marked foe can be finished outright. */
    private static final double THRESH_PLAYER = 0.25; // a player under a quarter
    private static final double THRESH_MOB    = 0.50; // an ordinary mob under a half
    private static final double THRESH_BOSS   = 0.10; // a boss under a tenth

    /**
     * The credited finishing blow. Modest on purpose — see the class docs. {@link #execute} guarantees the
     * kill with {@code setHealth(0.0)} afterwards, so this number never needs to be large, and must not be:
     * damage dealt is what vanilla charges the victim's armour for.
     */
    private static final double ONRUSH_EXECUTE_DAMAGE = 10.0;

    /**
     * The blow a foe that was <em>not</em> faltering takes instead. Above the practical single-hit ceiling
     * (a Sharpness V netherite sword lands ~11) because it is bought with {@link #ONRUSH_COOLDOWN_MS}, but
     * still a normal-sized number: ~4 durability per armour piece.
     */
    private static final double ONRUSH_DAMAGE = 16.0;

    /** Silence after an Onrush that failed to find an executable foe. */
    private static final long ONRUSH_COOLDOWN_MS = 180_000L; // 3 minutes

    /** How far in front of / behind the target the wielder lands. */
    private static final double ONRUSH_GAP = 1.4;

    /**
     * Bosses. "Abnormalities" and "NPC distortions" from the design have no entity type in this codebase —
     * see {@link #thresholdFor}.
     */
    private static final Set<EntityType> BOSS_TYPES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);

    // ---- housekeeping --------------------------------------------------------------

    /** How often the timestamped state maps are swept, at most. */
    private static final long PRUNE_PERIOD_MS = 1_000L;

    /** Wielder -> the damage they have pooled, and when they last dealt a blow. */
    private final Map<UUID, Reservoir> reservoirs = new HashMap<>();

    /** Wielder -> the foe they last struck. Holds a UUID, never the entity, so a corpse can't be pinned in memory. */
    private final Map<UUID, Mark> marks = new HashMap<>();

    /** Wielder -> epoch ms at which Onrush comes back. Absent = ready. */
    private final Map<UUID, Long> onrushReadyAt = new HashMap<>();

    /** Wielder -> epoch ms of their last drink, for the throttle. */
    private final Map<UUID, Long> lastDrinkAt = new HashMap<>();

    /** Wielders currently inside their own ability damage — the fence against re-entrant {@link #onHit}. */
    private final Set<UUID> striking = new HashSet<>();

    /** When {@link #prune} last swept. */
    private long lastPruneMs = 0L;

    public MimicryWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mimicry");
    }

    @Override
    public String id() {
        return "mimicry";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.MIMICRY.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.MIMICRY.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.MIMICRY);

        item.setItemMeta(meta);
        return item;
    }

    // ---- state ---------------------------------------------------------------------

    /** A wielder's pooled damage and the clock that decides when it evaporates. */
    private static final class Reservoir {
        private double amount;
        private long lastBlowMs;

        private Reservoir(long nowMs) {
            this.lastBlowMs = nowMs;
        }
    }

    /** The foe a wielder last struck, by id (never a hard entity reference) and when. */
    private record Mark(UUID victimId, long atMs) {}

    /**
     * Sweep the timestamped state. Every map here is wielder-keyed, but that alone is not enough: a mob
     * never fires {@code onQuit}, so {@link #marks} — whose <em>values</em> name a victim — would pin a dead
     * mob's id forever if it were only cleaned on the victim's own exit. Everything is therefore dropped the
     * moment its clock says it no longer matters, and {@link #onEntityDeath} clears marks eagerly on top.
     */
    private void prune(long now) {
        lastPruneMs = now;
        reservoirs.entrySet().removeIf(e -> now - e.getValue().lastBlowMs > RESERVOIR_DECAY_MS);
        marks.entrySet().removeIf(e -> now - e.getValue().atMs() > MARK_TTL_MS);
        onrushReadyAt.entrySet().removeIf(e -> now >= e.getValue());
        lastDrinkAt.entrySet().removeIf(e -> now - e.getValue() > LIFESTEAL_THROTTLE_MS);
    }

    /** The wielder's live pool, or 0 if it has evaporated / never existed. */
    private double pooled(UUID id, long now) {
        Reservoir r = reservoirs.get(id);
        if (r == null) return 0.0;
        if (now - r.lastBlowMs > RESERVOIR_DECAY_MS) {   // out of combat too long — it is already gone
            reservoirs.remove(id);
            return 0.0;
        }
        return r.amount;
    }

    // ---- passive: the pool and the drink -------------------------------------------

    /**
     * A landed blow. The vanilla cleaver damage is left exactly as it is — the pool and the drink are read
     * off it, never written back onto it.
     *
     * <p>When this fires from inside our own ability damage ({@link #striking}) it does nothing at all: the
     * release must not pool its own output (each cast would then dwarf the last) and the drink must not
     * compound off the same loop.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        if (striking.contains(aid)) return;              // our own ability re-entering — never feed the loop
        if (attacker.equals(victim)) return;
        if (victim.isDead() || !victim.isValid()) return;

        long now = System.currentTimeMillis();

        // The damage the blow actually lands, after armour and resistances — what the weapon really took.
        double dealt = event.getFinalDamage();
        if (dealt <= 0.0) return;

        mark(aid, victim, now);
        pool(aid, dealt, now);
        drink(attacker, dealt, now);
    }

    /** Remember this foe as the one to chase, and for how long. */
    private void mark(UUID attackerId, LivingEntity victim, long now) {
        marks.put(attackerId, new Mark(victim.getUniqueId(), now));
    }

    /** Swallow a blow's damage into the wielder's pool, clamped to the cap, and stamp them in combat. */
    private void pool(UUID attackerId, double dealt, long now) {
        Reservoir r = reservoirs.get(attackerId);
        if (r == null) {
            r = new Reservoir(now);
            reservoirs.put(attackerId, r);
        } else if (now - r.lastBlowMs > RESERVOIR_DECAY_MS) {
            r.amount = 0.0;                              // the lull already emptied it — start clean
        }
        r.amount = Math.min(RESERVOIR_CAP, r.amount + dealt);
        r.lastBlowMs = now;
    }

    /**
     * Is That the Red Mist?!? — mend the wielder for a quarter of the wound, at most once per
     * {@link #LIFESTEAL_THROTTLE_MS}, and never past their own max health.
     */
    private void drink(Player attacker, double dealt, long now) {
        UUID aid = attacker.getUniqueId();
        Long last = lastDrinkAt.get(aid);
        if (last != null && now - last < LIFESTEAL_THROTTLE_MS) return;

        AttributeInstance maxAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        double health = attacker.getHealth();
        if (health >= max) return;                       // already whole — don't burn the throttle on nothing

        double healed = Math.min(max, health + dealt * LIFESTEAL_FRACTION);
        if (healed <= health) return;
        attacker.setHealth(healed);
        lastDrinkAt.put(aid, now);
        drinkFx(attacker);
    }

    // ---- input ---------------------------------------------------------------------

    /** Shift + right-click releases the pool; a bare right-click runs the foe down. */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) {
            nothingThere(player);
        } else {
            onrush(player);
        }
    }

    // ---- Nothing There: give back what was swallowed --------------------------------

    /**
     * Empty the reservoir as a single strike around the wielder: everything living inside
     * {@link #RELEASE_RADIUS} takes {@link #RELEASE_FRACTION} of the pooled total. One
     * {@code getNearbyEntities} scan for the whole cast, one blow per body.
     *
     * <p>The pool is spent whether or not anything was standing there — the weapon gives it back either
     * way. Damage is routed through {@code victim.damage(...)} so other plugins can veto it, and each blow
     * is fenced so it cannot pool itself straight back into the reservoir it just came out of.
     */
    private void nothingThere(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        double amount = pooled(id, now);
        if (amount < RELEASE_MIN) {
            player.sendActionBar(EgoHud.status("Nothing There — nothing swallowed yet", RUST));
            return;
        }

        double perTarget = amount * RELEASE_FRACTION;

        // Spend the pool first: whatever happens below, this cast owns it and nothing can double-dip it.
        reservoirs.remove(id);

        releaseFx(player, amount);

        // One scan for the whole cast.
        List<Entity> nearby = player.getNearbyEntities(RELEASE_RADIUS, RELEASE_RADIUS, RELEASE_RADIUS);

        striking.add(id);
        try {
            for (Entity e : nearby) {
                if (!(e instanceof LivingEntity victim)) continue;
                if (victim.equals(player) || victim instanceof ArmorStand) continue;
                if (victim.isDead() || !victim.isValid()) continue;
                if (victim.getLocation().distanceSquared(player.getLocation()) > RELEASE_RADIUS * RELEASE_RADIUS) continue;

                // A body the wielder just cut is still inside its i-frames; without this the release
                // would silently no-op on exactly the foe it was aimed at.
                victim.setNoDamageTicks(0);
                victim.damage(perTarget, player);
            }
        } finally {
            striking.remove(id);
        }

        // A cast is not a vanilla swing, so it wears the blade a point of its own.
        EgoDurability.wearMainHand(player);
    }

    // ---- Onrush: the blink you don't see coming --------------------------------------

    /**
     * Run down the foe the wielder last struck. If it is faltering past its threshold, Mimicry arrives at
     * its back and finishes it, free of charge — Onrush resets on the kill. If it is not, the wielder still
     * arrives, in its face, and hits it {@link #ONRUSH_DAMAGE} hard for {@link #ONRUSH_COOLDOWN_MS} of
     * silence afterwards.
     */
    private void onrush(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long readyAt = onrushReadyAt.get(id);
        if (readyAt != null && now < readyAt) {
            player.sendActionBar(EgoHud.cooldown("Onrush", readyAt - now, RUST));
            return;
        }
        onrushReadyAt.remove(id);

        LivingEntity target = markedTarget(id, now);
        if (target == null) {
            player.sendActionBar(EgoHud.status("Onrush — no one to chase", RUST));
            return;
        }
        if (target.getWorld() != player.getWorld()
                || player.getLocation().distanceSquared(target.getLocation()) > ONRUSH_RANGE_SQ) {
            player.sendActionBar(EgoHud.status("Onrush — too far", RUST));
            return;
        }

        if (executable(target)) {
            blink(player, target, true);                 // behind it: the blow it never sees
            execute(player, target);
            marks.remove(id);                            // the mark died with it
            onrushReadyAt.remove(id);                    // resets on the kill — no cooldown paid
        } else {
            blink(player, target, false);                // to its face
            strike(player, target);
            onrushReadyAt.put(id, now + ONRUSH_COOLDOWN_MS);
        }

        // Blink + blow is a non-vanilla use, so it wears the blade beyond the swing it never made.
        EgoDurability.wearMainHand(player);
    }

    /** The live foe behind this wielder's mark, or null if it is stale, gone, dead, or not a living thing. */
    private LivingEntity markedTarget(UUID attackerId, long now) {
        Mark m = marks.get(attackerId);
        if (m == null) return null;
        if (now - m.atMs() > MARK_TTL_MS) {              // prune inline: mobs never fire onQuit
            marks.remove(attackerId);
            return null;
        }
        Entity e = plugin.getServer().getEntity(m.victimId());
        if (!(e instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) {
            marks.remove(attackerId);
            return null;
        }
        return victim;
    }

    /** True if this foe is already faltering past the threshold its kind is held to. */
    private boolean executable(LivingEntity victim) {
        AttributeInstance maxAttr = victim.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        if (max <= 0.0) return false;
        return victim.getHealth() / max < thresholdFor(victim);
    }

    /**
     * The HP fraction below which this foe can be finished.
     *
     * <p>The design also names <b>abnormalities</b> and <b>NPC distortions</b>. Neither is an entity in this
     * codebase: abnormalities are folded into the boss bucket below (same threshold, which is what the
     * design asks of them), and distortion NPCs are left alone rather than guessed at.
     */
    private double thresholdFor(LivingEntity victim) {
        // TODO: distortion NPCs share the boss threshold once that system exists — there is nothing to
        // test for here yet, so they currently fall through to the mob threshold like any other entity.
        if (BOSS_TYPES.contains(victim.getType())) return THRESH_BOSS;
        if (victim instanceof Player) return THRESH_PLAYER;
        return THRESH_MOB;
    }

    /**
     * Finish a faltering foe: a modest, kill-credited blow, then {@code setHealth(0.0)} to guarantee it
     * through armour or Resistance.
     *
     * <p><b>Read the class docs before touching the number.</b> The kill comes from {@code setHealth(0)},
     * which never runs the damage pipeline and so costs the victim's armour nothing. It does not come from
     * an overkill damage figure, which would cost their armour ~damage/4 <em>per piece</em> and delete a
     * full set in a single hit.
     */
    private void execute(Player attacker, LivingEntity victim) {
        UUID aid = attacker.getUniqueId();
        executeFx(attacker, victim);

        striking.add(aid);                               // this damage() re-enters onHit — fence it
        try {
            victim.setNoDamageTicks(0);
            victim.damage(ONRUSH_EXECUTE_DAMAGE, attacker);
        } finally {
            striking.remove(aid);
        }
        if (!victim.isDead() && victim.isValid() && victim.getHealth() > 0.0) {
            victim.setHealth(0.0);
        }
    }

    /** The blow a foe that was not faltering takes: devastating, bought with three minutes of silence. */
    private void strike(Player attacker, LivingEntity victim) {
        UUID aid = attacker.getUniqueId();
        strikeFx(attacker, victim);

        striking.add(aid);
        try {
            victim.setNoDamageTicks(0);
            victim.damage(ONRUSH_DAMAGE, attacker);
        } finally {
            striking.remove(aid);
        }
    }

    // ---- the blink -------------------------------------------------------------------

    /**
     * Put the wielder at the target's back ({@code behind}) or in its face, looking at it. Wall-safe: the
     * landing spot is probed for a clear two-block standing space and walked back toward the target — which
     * is by definition standing somewhere legal — until one is found. If nothing along that line is clear
     * the wielder simply doesn't move; the blow still lands, since the range check already passed.
     */
    private void blink(Player player, LivingEntity target, boolean behind) {
        Location tLoc = target.getLocation();

        Vector facing = tLoc.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) {
            facing = player.getLocation().toVector().subtract(tLoc.toVector()).setY(0);
        }
        if (facing.lengthSquared() < 1.0e-6) facing = new Vector(0, 0, 1);
        facing.normalize();

        // In front = out along the way it looks; behind = one step past its back.
        Vector offset = behind ? facing.clone().multiply(-ONRUSH_GAP) : facing.clone().multiply(ONRUSH_GAP);
        Location want = tLoc.clone().add(offset);

        // Land looking at it either way — from behind that means facing its nape.
        Vector look = tLoc.toVector().subtract(want.toVector());
        if (look.lengthSquared() < 1.0e-6) look = facing.clone();   // never hand setDirection a zero vector
        want.setDirection(look);

        Location landing = wallSafe(want, tLoc);
        if (landing == null) return;                     // nowhere legal to stand — stay put, still swing

        blinkFx(player.getLocation(), landing);
        player.teleport(landing);
    }

    /**
     * The first spot from {@code want} back toward {@code anchor} where a player actually fits, or null.
     */
    private Location wallSafe(Location want, Location anchor) {
        if (fits(want)) return want;

        Vector back = anchor.toVector().subtract(want.toVector());
        double span = back.length();
        if (span > 1.0e-6) {
            back.normalize();
            for (double d = 0.35; d <= span; d += 0.35) {
                Location probe = want.clone().add(back.clone().multiply(d));
                probe.setDirection(want.getDirection());
                if (fits(probe)) return probe;
            }
        }

        Location last = anchor.clone();
        last.setDirection(want.getDirection());
        return fits(last) ? last : null;
    }

    /** True if a player-sized body stands clear here — feet and head both in passable blocks. */
    private boolean fits(Location at) {
        World world = at.getWorld();
        if (world == null) return false;
        return world.getBlockAt(at).isPassable()
                && world.getBlockAt(at.clone().add(0, 1, 0)).isPassable();
    }

    // ---- tick ------------------------------------------------------------------------

    /**
     * The reservoir gauge and Onrush's state, while Mimicry is in the main hand. Returns false the instant
     * it is not — a wielder who sheathes the blade must stop costing ticks immediately.
     *
     * <p>Note the pool itself is <em>not</em> dropped here: sheathing the weapon is not "out of combat", and
     * only {@link #RESERVOIR_DECAY_MS} of no blows empties it.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;

        long now = System.currentTimeMillis();
        if (now - lastPruneMs >= PRUNE_PERIOD_MS) prune(now);

        UUID id = player.getUniqueId();
        double amount = pooled(id, now);

        Long readyAt = onrushReadyAt.get(id);
        Component onrush = (readyAt != null && now < readyAt)
                ? EgoHud.cooldown("Onrush", readyAt - now, RUST)     // whole seconds, never milliseconds
                : EgoHud.ready("Onrush", BONE);

        Component label = plain("Nothing There  " + (int) Math.round(amount))
                .append(plain("  ")).append(onrush);
        player.sendActionBar(EgoHud.gauge(RUST, amount / RESERVOIR_CAP, label));
        return true;
    }

    // ---- lifecycle ---------------------------------------------------------------------

    /** A marked foe that dies stops being chaseable — clear it eagerly rather than waiting for the sweep. */
    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        clearMarksOn(event.getEntity().getUniqueId());
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        clearMarksOn(event.getEntity().getUniqueId());
    }

    /** Drop every wielder's mark on this now-dead foe. */
    private void clearMarksOn(UUID victimId) {
        marks.values().removeIf(m -> m.victimId().equals(victimId));
    }

    /**
     * A wielder left. Their fence entry goes — it is per-call state and can only be stale. Their reservoir
     * and Onrush cooldown deliberately stay: the design gives the pool a five-minute grace across a logout,
     * and dropping the cooldown here would make quitting a free reset. Both are timestamped and swept by
     * {@link #prune}, so neither can outlive its meaning.
     */
    @Override
    public void onQuit(UUID id) {
        striking.remove(id);
        marks.remove(id);
        lastDrinkAt.remove(id);
        prune(System.currentTimeMillis());
    }

    /** Nothing of Mimicry's is out in the world — only its bookkeeping, which goes here. */
    @Override
    public void onDisable() {
        reservoirs.clear();
        marks.clear();
        onrushReadyAt.clear();
        lastDrinkAt.clear();
        striking.clear();
    }

    // ---- SFX / VFX -----------------------------------------------------------------------

    /** The drink: a quiet wet pull and a few beads of red drawn up the wielder's body. */
    private void drinkFx(Player player) {
        Location at = player.getLocation().add(0, 1.0, 0);
        player.getWorld().playSound(at, Sound.ENTITY_GENERIC_DRINK, 0.35f, 0.6f);
        player.getWorld().spawnParticle(Particle.DUST, at, 6, 0.28, 0.45, 0.28, 0.0,
                new Particle.DustOptions(BLOOD_C, 0.9f));
    }

    /**
     * Nothing There: the weapon giving back everything it swallowed. A ring of dust that bleeds from
     * bone-white to dried red as it goes out, sized by how much was in the pool, over a sonic crack.
     */
    private void releaseFx(Player player, double amount) {
        World world = player.getWorld();
        Location feet = player.getLocation();
        double fill = Math.max(0.0, Math.min(1.0, amount / RESERVOIR_CAP));

        world.playSound(feet, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 1.4f);
        world.playSound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.7f + (float) fill * 0.3f, 1.6f);
        world.spawnParticle(Particle.EXPLOSION, feet.clone().add(0, 0.8, 0), 1);

        // The ring: bone-white at the wielder, dried blood by the time it reaches the edge.
        Particle.DustTransition bleed = new Particle.DustTransition(BONE_C, BLOOD_C, 1.5f);
        final int rings = 3;
        final int points = 26;
        for (int r = 1; r <= rings; r++) {
            double radius = RELEASE_RADIUS * ((double) r / rings);
            for (int i = 0; i < points; i++) {
                double a = (Math.PI * 2 * i) / points;
                Location p = feet.clone().add(Math.cos(a) * radius, 0.25 + r * 0.2, Math.sin(a) * radius);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.04, 0.04, 0.04, 0.0, bleed);
            }
        }
        world.spawnParticle(Particle.SWEEP_ATTACK, feet.clone().add(0, 1.0, 0), 4, 0.8, 0.4, 0.8, 0.0);
    }

    /** The blink itself: the wielder unmaking where they were and reassembling where they are. */
    private void blinkFx(Location from, Location to) {
        World world = from.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.5f + (rng.nextFloat() - 0.5f) * 0.2f);

        Particle.DustTransition trail = new Particle.DustTransition(BONE_C, BLOOD_C, 1.1f);
        Vector step = to.toVector().subtract(from.toVector());
        double span = step.length();
        if (span < 1.0e-6) return;
        step.normalize();
        for (double d = 0; d < span; d += 0.4) {
            Location p = from.clone().add(step.clone().multiply(d)).add(0, 1.0, 0);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.06, 0.06, 0.06, 0.0, trail);
        }
    }

    /** The devastating blow to a foe that stood its ground: a heavy cleave and a burst of red. */
    private void strikeFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);

        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
        world.playSound(body, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.5f);

        world.spawnParticle(Particle.SWEEP_ATTACK, body, 2, 0.3, 0.2, 0.3, 0.0);
        world.spawnParticle(Particle.DUST, body, 18, 0.3, 0.3, 0.3, 0.0,
                new Particle.DustOptions(BLOOD_C, 1.3f));
    }

    /** The finish: a crisp sweep at the nape, a wet crunch, and a spray that fades from bone to blood. */
    private void executeFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location neck = victim.getLocation().add(0, victim.getHeight() * 0.85, 0);

        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f);
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.6f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 0.5f);

        world.spawnParticle(Particle.CRIT, neck, 12, 0.2, 0.2, 0.2, 0.3);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, neck, 24, 0.25, 0.2, 0.25, 0.0,
                new Particle.DustTransition(BONE_C, BLOOD_C, 1.4f));
    }

    // ---- lore --------------------------------------------------------------------------

    /** Bone-white — the shape the thing wears. */
    private static final TextColor BONE = TextColor.color(0xD6D2C8);
    /** Dried blood — what is underneath it. */
    private static final TextColor RUST = TextColor.color(0x8E2B27);

    private static final Color BONE_C  = Color.fromRGB(0xD6, 0xD2, 0xC8);
    private static final Color BLOOD_C = Color.fromRGB(0x8E, 0x2B, 0x27);

    /** A small non-italic action-bar fragment in the blade's bone tone. */
    private static Component plain(String text) {
        return Component.text(text).color(BONE).decoration(TextDecoration.ITALIC, false);
    }

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Mimicry",
            "Nothing There",
            BONE,
            RUST,
            List.of(
                    "The blade of the Red Mist — a thing",
                    "wearing the shape of a weapon. It",
                    "strikes faster than the eye can",
                    "follow and drinks a quarter of every",
                    "wound to mend its wielder into a",
                    "wall of flesh."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Is That the Red Mist?!?",
                            "Heal 25% of the damage you deal,",
                            "at most once every 5 seconds."),
                    new EgoLore.Ability("[Shift + Right-click] Nothing There",
                            "Release a strike around you dealing",
                            "half of the damage you have pooled,",
                            "then spend it. The pool empties after",
                            "5 minutes out of combat."),
                    new EgoLore.Ability("[Right-click] Onrush",
                            "Blink onto the foe you last struck",
                            "and finish it: player <25%, mob <50%,",
                            "boss <10% HP. Free if it lands.",
                            "Otherwise blink to its face and strike",
                            "hard — 3 minute cooldown.")
            ));
}
