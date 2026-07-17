package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
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
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
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
 * tuning — {@link EgoModels#MIMICRY} sets 6.5 damage at 1.8 speed. The weapon sets its meta exactly once
 * in {@link #createItem()} and never repaints, so it takes vanilla enchants like any other E.G.O piece.
 * Everything below is state kept beside the item, never on it.
 *
 * <ul>
 *   <li><b>Is That the Red Mist?!?</b> ({@link #onHit}, passive) — a landed blow mends the wielder for
 *       {@link #LIFESTEAL_FRACTION} of the damage it dealt, throttled to once per
 *       {@link #LIFESTEAL_THROTTLE_MS}.</li>
 *   <li><b>The reservoir</b> ({@link #onDamaged}, passive) — every point of damage that <em>lands on</em>
 *       the wielder pools in the blade. It is the wounds it remembers, not the wounds it gave. The pool
 *       itself is never clamped; what it can <em>buy</em> is (see below). It evaporates after
 *       {@link #RESERVOIR_DECAY_MS} without a fresh wound (whether the wielder is logged in for that lull
 *       or not).</li>
 *   <li><b>Nothing There</b> ({@link #onInteract}, shift + right-click) — the downswing. Empties the
 *       reservoir as one cleave around the wielder, dealing {@link #RELEASE_FRACTION} of the pooled total
 *       — never more than {@link #RELEASE_CAP} — to everything inside {@link #cleaveRadius}. Past
 *       {@link #RESERVOIR_FULL} it stops hitting harder; it keeps reaching further, and keeps looking it,
 *       until {@link #RELEASE_RADIUS_MAX}.</li>
 *   <li><b>Onrush</b> ({@link #onInteract}, right-click) — a rush onto the foe the wielder last struck.
 *       If that foe is already faltering (a player under {@link #THRESH_PLAYER}, a mob under
 *       {@link #THRESH_MOB}, a boss under {@link #THRESH_BOSS}) Mimicry arrives at its back and finishes
 *       it, and — resetting on the kill — costs no cooldown at all. If the foe is <em>not</em> executable
 *       the wielder still lands: a rush to the target's face, a devastating {@link #ONRUSH_DAMAGE}, and
 *       {@link #ONRUSH_COOLDOWN_MS} of silence.</li>
 * </ul>
 *
 * <h2>The reservoir takes wounds, not kills</h2>
 * {@link #onDamaged} is the only thing that fills the pool, and it banks {@code getFinalDamage()} — the
 * settled, post-armour figure for damage that actually landed. A strike a shield ate arrives here with a
 * final damage of zero and banks nothing: there is no wound to remember. The hook is read-only (it runs at
 * monitor priority, so writing the event would lie to every listener that already read its final values).
 *
 * <h2>How hard it hits and how far it reaches are different questions. What it cuts and what it draws are not.</h2>
 * Two caps, one radius, and the difference between those sentences is the design:
 *
 * <ul>
 *   <li><b>Weight is capped early.</b> Per body, one cast deals at most {@link #RELEASE_CAP}, saturating at
 *       {@link #RESERVOIR_FULL} pooled and never moving again. Bank ten times that and every body still
 *       takes the same.</li>
 *   <li><b>Reach keeps growing, honestly, and the picture grows with it</b> — one {@link #cleaveRadius},
 *       asked by the scan and by the drawing, so they cannot disagree. It stops at
 *       {@link #RELEASE_RADIUS_MAX}, because {@link #RELEASE_CAP} is per body and capped damage across an
 *       uncapped body count is not a capped ability, and because the scan is the one cost here that tracks
 *       reach.</li>
 * </ul>
 *
 * <p>It was built the other way first — the cut bounded, the arc unbounded — and it read as cheating,
 * because it was: past a big pool the blade was drawn eighteen blocks and bit eight, and no amount of
 * arguing that the lie ran toward mercy made it look like anything but a weapon that missed. Two numbers
 * describing one thing drift; one number cannot.
 *
 * <p><b>The clamp lives at the release, not at the intake</b> — {@link #bank} takes every wound and
 * {@link #nothingThere} clamps what it spends. Clamping the pool would throw away the honest record of what
 * the wielder survived, which is the one thing this weapon is actually about.
 *
 * <p>The gauge is built to teach exactly this: the bar is scaled to {@link #RESERVOIR_FULL}, so a full bar
 * means "this is as hard as it will ever hit", while the number beside it goes on climbing to say "…and it
 * will keep looking worse".
 *
 * <h2>Armour, and why nothing here launders it</h2>
 * Vanilla charges armour durability off the damage <em>before</em> reduction, roughly {@code damage/4}
 * <b>per piece</b>. That is why an uncapped release was a gear shredder, and why an earlier build carried a
 * snapshot/restore that read every piece's durability before the blow and wrote it back after. <b>The cap
 * retires that machinery.</b> At {@link #RELEASE_CAP} the toll is {@code 60/4 = 15} per piece — about 2.5% of
 * a netherite chestplate, a shade more than a point-blank creeper (~12) and well under a charged one (~24).
 * That is ordinary wear from an ordinary big hit, and ordinary wear is exactly what every other weapon on
 * this roster pays. Restoring it would have made Mimicry the only blow in the game whose damage armour
 * absorbs for free — armour mattering <em>more</em> than vanilla, not less — at the cost of ~20 item-meta
 * deep copies per victim per cast and a documented hole it could never close. Every blow this class deals is
 * now a plain {@code damage()} that armour reduces normally and bills normally.
 *
 * <p><b>The execute is a modest blow plus {@code setHealth(0)}, never an overkill number.</b>
 * {@link #ONRUSH_EXECUTE_DAMAGE} is normal-sized and kill-credited, and {@code setHealth(0.0)} — which
 * never runs the damage pipeline and so takes no toll on armour — guarantees the finish through armour or
 * Resistance. An overkill damage figure would cost the victim ~{@code damage/4} per piece and delete a set.
 *
 * <h2>Re-entrancy</h2>
 * The release deals damage with {@link LivingEntity#damage(double, Entity)}, which fires its own
 * {@code EntityDamageByEntityEvent} and re-enters {@link #onHit}. The {@link #striking} fence makes that
 * re-entrant call a complete no-op, so ability damage neither pools nor lifesteals. The same fence guards
 * {@link #onDamaged}: a victim's Thorns answering the wielder's own cleave is the wielder's own effect
 * coming back at them, and banking it would refill — from a single cast — the very pool that cast just
 * spent. {@link #selfInflicted} closes the same door for the wielder's own projectiles.
 *
 * <p>Mimicry spawns no entities and edits no blocks — it is particles, sound, and arithmetic — so there is
 * nothing for {@link #onDisable()} to reap from the world, and its animations hold only {@link Location}s
 * and a {@link World}, never an entity, so a mid-flight swing cannot pin a corpse in memory. Its
 * per-wielder state is timestamped and swept ({@link #prune}) rather than merely dropped on quit, because
 * the reservoir's five-minute grace and the Onrush cooldown must both survive a logout: the first is the
 * design, the second is so that logging out is never a free cooldown reset.
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

    /**
     * The pool at which this weapon stops growing teeth — <b>the "certain point"</b>. Damage and reach both
     * saturate here and never move again; everything past it is show. Nothing clamps the pool <em>to</em>
     * this value (see the class docs on why that would kill the spectacle) — it is the value the mechanical
     * numbers stop reading past, and the mark at which the gauge reads full.
     */
    private static final double RESERVOIR_FULL = 120.0;

    /**
     * The hardest one body can be cut by one cast, ever. This is the old {@code RESERVOIR_CAP = 120} the
     * uncapped rework deleted, restored at the only place it can live without taking the drama with it:
     * {@code RESERVOIR_FULL * RELEASE_FRACTION}, the same 60 that build allowed, now clamped at the release
     * instead of at the intake.
     *
     * <p>Sixty is chosen and not merely inherited. It is three unarmoured hearts short of nothing — a kill
     * on any player not wearing a set — roughly four times {@link #ONRUSH_DAMAGE}, and it costs a full
     * {@link #RESERVOIR_FULL} of <em>post-armour</em> damage actually suffered to buy, which is a whole fight
     * survived rather than a combo. It is also, deliberately, the largest number whose armour toll
     * ({@code 60/4 = 15} a piece) is still ordinary — see the class docs. Raising it past ~80 would put the
     * bill back into gear-shredder territory and the restore back on the table.
     */
    private static final double RELEASE_CAP = RESERVOIR_FULL * RELEASE_FRACTION;

    /**
     * Radius of the cut, at an empty pool and at {@link #RESERVOIR_FULL} respectively. One scan, one strike
     * per body inside it. See {@link #cleaveRadius}.
     *
     * <p>The reach grows well past the point the damage stops — that is the "aoe and slash keep getting
     * bigger" half of the brief, and both halves of it, since one curve now serves the cut and the drawing
     * alike. It reaches sixteen: three times the bare five, thirty-three times the volume, a room rather
     * than a duel, and it takes roughly 750 banked wounds to get there.
     *
     * <p>It is a real ceiling, and the ceiling is the deliberate part. An unbounded hit radius is a power
     * increase wearing a cosmetic's clothes: a capped 60 per body means nothing if the body count is
     * unbounded, and a 50-block cleave catching forty players is 2,400 damage from one keypress however
     * modest each slice reads. It would break the perf promise too, since the entity scan is the one cost
     * here that genuinely tracks the radius — at sixteen the scan box is fixed at its worst and cheap.
     * Growth that never stops is not drama, it is an exploit that takes a while.
     */
    private static final double RELEASE_RADIUS_BASE = 5.0;
    private static final double RELEASE_RADIUS_MAX = 16.0;

    /** Below this the pool isn't worth a cast — the wielder keeps it rather than spending nothing. */
    private static final double RELEASE_MIN = 1.0;

    /** The pool evaporates after this long without the wielder taking a fresh wound (online or logged out). */
    private static final long RESERVOIR_DECAY_MS = 300_000L; // 5 minutes out of combat

    // ---- tuning: Onrush ------------------------------------------------------------

    /** How far the wielder will rush to reach the foe they last struck. */
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

    // ---- tuning: the show ----------------------------------------------------------
    //
    // This block is where the uncapped half of the weapon lives, and every number in it is a ceiling on
    // COST rather than on SIZE — that is the trick that makes an unbounded spectacle affordable at ~100
    // players and ~13 TPS. The rule throughout: the pool scales the arc's SIZE and THICKNESS without limit,
    // and its POINT COUNT not at all past SLASH_POINTS_MAX. A 153-block cleave and a 12-block one cost the
    // server the same packets; the big one just spreads them further apart and draws them fatter, which is
    // exactly where the drama lives anyway.
    //
    // Nothing in this block feeds a damage number or an entity scan. It is safe to make any of it more
    // absurd; it is not safe to let anything above read from it.

    /** The reach a bare pool has, before {@link #cleaveRadius} grows it. */
    private static final double SLASH_BASE_RADIUS = 3.0;

    /** The pool at which the downswing has doubled its base reach. Growth is {@code sqrt} and never stops. */
    private static final double SLASH_REF = 40.0;

    /** Starting blade width — already thicker than Arayashiki's 1.1-1.6, which is the brief. */
    private static final float SLASH_THICK_BASE = 2.0f;

    /**
     * Widest the blade is drawn. Unlike the radius this one is clamped, because dust size is a client-side
     * scalar rather than real geometry: past ~7 the strand stops reading as an edge and starts reading as a
     * row of boxes. Size carries the drama past here, not thickness.
     */
    private static final float SLASH_THICK_MAX = 7.0f;

    /** Fewest / most points the arc is ever drawn with. The ceiling is the perf promise — see above. */
    private static final int SLASH_POINTS_MIN = 40;
    private static final int SLASH_POINTS_MAX = 120;

    /** Ticks for the blade to fall, and ticks each point of it lingers after being cut. */
    private static final int DOWNSWING_REVEAL = 4;
    private static final int DOWNSWING_FADE = 3;

    /** The impact ring: a fixed budget, pushed out to the reach of the cut but never past this. */
    private static final int SHOCKWAVE_POINTS = 48;
    private static final double SHOCKWAVE_RADIUS_MAX = 24.0;
    private static final int IMPACT_SWEEPS = 6;

    /** An M1 arc, and the heavier one that ends the three-beat chain. */
    private static final int M1_POINTS = 18;
    private static final int M1_POINTS_HEAVY = 26;
    private static final int M1_REVEAL = 3;
    private static final int M1_FADE = 3;

    /** The chain forgets itself after this long, and the next swing starts from the first beat again. */
    private static final long COMBO_RESET_MS = 1_200L;

    /** Onrush's trail: sampling along the path, and the hard ceiling on how many samples that can become. */
    private static final double RUSH_STEP = 0.45;
    private static final int RUSH_POINTS_MAX = 32;
    private static final int RUSH_REVEAL = 3;
    private static final int RUSH_FADE = 4;

    // ---- housekeeping --------------------------------------------------------------

    /** How often the timestamped state maps are swept, at most. */
    private static final long PRUNE_PERIOD_MS = 1_000L;

    /** Wielder -> the damage pooled in their blade, and when it last took a wound. */
    private final Map<UUID, Reservoir> reservoirs = new HashMap<>();

    /** Wielder -> the foe they last struck. Holds a UUID, never the entity, so a corpse can't be pinned in memory. */
    private final Map<UUID, Mark> marks = new HashMap<>();

    /** Wielder -> epoch ms at which Onrush comes back. Absent = ready. */
    private final Map<UUID, Long> onrushReadyAt = new HashMap<>();

    /** Wielder -> epoch ms of their last drink, for the throttle. */
    private final Map<UUID, Long> lastDrinkAt = new HashMap<>();

    /** Wielder -> where they are in the three-beat M1 chain. Cosmetic only. */
    private final Map<UUID, Combo> combos = new HashMap<>();

    /** Wielders currently inside their own ability damage — the fence against re-entrant hooks. */
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
        private long lastWoundMs;

        private Reservoir(long nowMs) {
            this.lastWoundMs = nowMs;
        }
    }

    /** The foe a wielder last struck, by id (never a hard entity reference) and when. */
    private record Mark(UUID victimId, long atMs) {}

    /** Where a wielder is in the M1 chain, and when they last swung. Purely an animation. */
    private static final class Combo {
        private int beat;
        private long lastMs;
    }

    /**
     * Sweep the timestamped state. Every map here is wielder-keyed, but that alone is not enough: a mob
     * never fires {@code onQuit}, so {@link #marks} — whose <em>values</em> name a victim — would pin a dead
     * mob's id forever if it were only cleaned on the victim's own exit. Everything is therefore dropped the
     * moment its clock says it no longer matters, and {@link #onEntityDeath} clears marks eagerly on top.
     */
    private void prune(long now) {
        lastPruneMs = now;
        reservoirs.entrySet().removeIf(e -> now - e.getValue().lastWoundMs > RESERVOIR_DECAY_MS);
        marks.entrySet().removeIf(e -> now - e.getValue().atMs() > MARK_TTL_MS);
        onrushReadyAt.entrySet().removeIf(e -> now >= e.getValue());
        lastDrinkAt.entrySet().removeIf(e -> now - e.getValue() > LIFESTEAL_THROTTLE_MS);
        combos.entrySet().removeIf(e -> now - e.getValue().lastMs > COMBO_RESET_MS);
    }

    /** The wielder's live pool, or 0 if it has evaporated / never existed. */
    private double pooled(UUID id, long now) {
        Reservoir r = reservoirs.get(id);
        if (r == null) return 0.0;
        if (now - r.lastWoundMs > RESERVOIR_DECAY_MS) {   // out of combat too long — it is already gone
            reservoirs.remove(id);
            return 0.0;
        }
        return r.amount;
    }

    // ---- passive: the drink ---------------------------------------------------------

    /**
     * A landed blow. The vanilla cleaver damage is left exactly as it is — the drink is read off it, never
     * written back onto it, and the pool is not fed from here at all any more: what the wielder deals is
     * the weapon's business, what the wielder <em>takes</em> is what it remembers. See {@link #onDamaged}.
     *
     * <p>When this fires from inside our own ability damage ({@link #striking}) it does nothing at all: the
     * drink must not compound off the release's own output.
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
        drink(attacker, dealt, now);
    }

    /**
     * A wound on the wielder — the reservoir's only intake.
     *
     * <p>What gets banked is {@code getFinalDamage()}: the settled figure, after the victim's own armour and
     * resistances, for a blow that truly landed (the dispatch is monitor priority and ignores cancelled
     * events, so a hit some other plugin vetoed never reaches here). A strike a shield or absorption ate
     * outright arrives with a final damage of {@code 0} and is dropped rather than banked as a zero: the
     * pool is a memory of wounds, and a blow that drew nothing left no wound to remember. The event is read
     * and never written — at monitor priority a {@code setDamage} here would contradict every listener that
     * has already read its final values.
     *
     * <p>Two doors are held shut so the wielder cannot fill their own pool. {@link #striking} rejects
     * anything that arrives while the wielder is inside their own cast — a victim's Thorns answering the
     * cleave is the cast hitting the caster, and banking it would let one release refill the pool it just
     * spent. {@link #selfInflicted} rejects the wielder's own projectiles for the same reason.
     *
     * <p><b>Only wounds something gave you.</b> Environmental damage — a fall, a fire, a mouthful of poison
     * — is damage the wielder received, and the literal reading would bank it. It doesn't. A pool you can
     * fill by standing in a campfire somewhere quiet is not the memory of a fight, it is a farm, and it is
     * the same shape as swinging a greatsword at empty air to stack its passive — which Nyrrine called out
     * as abuse in the same breath she asked for this. She meant taking a beating, not hurting yourself on
     * purpose. The blade drinks what is done to it. (One line if that is ever wanted back: drop the
     * {@code EntityDamageByEntityEvent} narrowing below.)
     */
    @Override
    public void onDamaged(Player victim, EntityDamageEvent event) {
        UUID id = victim.getUniqueId();
        if (striking.contains(id)) return;               // inside our own cast — never feed the loop
        if (!(event instanceof EntityDamageByEntityEvent)) return; // a fire is not a foe; see the docs above
        if (selfInflicted(victim, event)) return;        // the wielder's own effects are not a foe's wound

        double taken = event.getFinalDamage();
        if (taken <= 0.0) return;                        // blocked or absorbed outright — no wound to bank

        bank(id, taken, System.currentTimeMillis());
    }

    /** True if this damage came from the wielder themselves, directly or by way of their own projectile. */
    private boolean selfInflicted(Player victim, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent e)) return false;
        Entity damager = e.getDamager();
        if (victim.equals(damager)) return true;
        return damager instanceof Projectile p && victim.equals(p.getShooter());
    }

    /** Remember this foe as the one to chase, and for how long. */
    private void mark(UUID attackerId, LivingEntity victim, long now) {
        marks.put(attackerId, new Mark(victim.getUniqueId(), now));
    }

    /**
     * Swallow a wound into the wielder's pool and stamp them in combat.
     *
     * <p><b>Nothing clamps this, and that is load-bearing rather than an oversight.</b> The pool is the
     * honest running total of everything the wielder has been made to swallow, and it is what the spectacle
     * is drawn from — so clamping it here would silently cap the arc, the one thing the design wants
     * unbounded. The bound on what the pool can <em>buy</em> lives at the release ({@link #RELEASE_CAP}),
     * which is the only site where it costs the show nothing. See the class docs.
     */
    private void bank(UUID wielderId, double taken, long now) {
        Reservoir r = reservoirs.get(wielderId);
        if (r == null) {
            r = new Reservoir(now);
            reservoirs.put(wielderId, r);
        } else if (now - r.lastWoundMs > RESERVOIR_DECAY_MS) {
            r.amount = 0.0;                              // the lull already emptied it — start clean
        }
        r.amount += taken;
        r.lastWoundMs = now;
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

    /** Shift + right-click brings the blade down; a bare right-click runs the foe down. */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) {
            nothingThere(player);
        } else {
            onrush(player);
        }
    }

    /**
     * The M1 chain: a real slash on every swing, three beats that alternate their roll and then land a
     * heavier overhead — a small rhyme with the downswing the reservoir eventually buys. This is animation
     * only. The vanilla swing under it is untouched and is what deals the damage; adding a blow here would
     * double-hit and is not what "m1 animations" asks for.
     */
    @Override
    public void onSwing(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Combo c = combos.get(id);
        if (c == null || now - c.lastMs > COMBO_RESET_MS) {
            c = new Combo();
            combos.put(id, c);
        }
        c.lastMs = now;
        int beat = c.beat;
        c.beat = (c.beat + 1) % 3;

        m1Fx(player, beat);
    }

    // ---- Nothing There: give back every wound at once --------------------------------

    /**
     * Bring the blade down. Everything living inside {@link #cleaveRadius} takes {@link #RELEASE_FRACTION}
     * of the pooled total, capped at {@link #RELEASE_CAP}. One {@code getNearbyEntities} scan for the whole
     * cast, one blow per body.
     *
     * <p>Both mechanical numbers are read here and both saturate at {@link #RESERVOIR_FULL}: past that, a
     * bigger pool buys a bigger <em>picture</em> ({@link #nothingThereFx}, which reads the raw amount and has
     * no ceiling) and nothing else. This is the only method where the two halves are visible side by side —
     * {@code perTarget} and {@code radius} are clamped, {@code amount} is handed to the FX untouched.
     *
     * <p>The pool is spent whether or not anything was standing there — the weapon gives it back either way,
     * and the wielder does not get to bank a fortune and cast it repeatedly by finding an empty field.
     * Damage is routed through {@code victim.damage(...)} so armour reduces it, bills the victim's gear the
     * ordinary amount, and other plugins can veto it. Each blow is fenced ({@link #striking}) so it cannot
     * pool straight back into the reservoir it just came out of.
     */
    private void nothingThere(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        double amount = pooled(id, now);
        if (amount < RELEASE_MIN) {
            player.sendActionBar(EgoHud.status("Nothing There — no wound to give back", RUST));
            return;
        }

        // The two clamped reads. Everything the victim actually feels is decided on these two lines.
        double perTarget = Math.min(amount * RELEASE_FRACTION, RELEASE_CAP);
        double radius = cleaveRadius(amount);

        // Spend the pool first: whatever happens below, this cast owns it and nothing can double-dip it.
        reservoirs.remove(id);

        // The FX get the *raw* pool, not the clamped one — this is the uncapped half of the weapon.
        nothingThereFx(player, amount);

        // One scan for the whole cast, and its box is bounded by RELEASE_RADIUS_MAX however big the pool got.
        List<Entity> nearby = player.getNearbyEntities(radius, radius, radius);
        double radiusSq = radius * radius;

        striking.add(id);
        try {
            for (Entity e : nearby) {
                if (!(e instanceof LivingEntity victim)) continue;
                if (victim.equals(player) || victim instanceof ArmorStand) continue;
                if (victim.isDead() || !victim.isValid()) continue;
                if (victim.getLocation().distanceSquared(player.getLocation()) > radiusSq) continue;

                // A body the wielder just cut is still inside its i-frames; without this the release would
                // silently no-op on exactly the foe it was aimed at.
                victim.setNoDamageTicks(0);
                victim.damage(perTarget, player);
            }
        } finally {
            striking.remove(id);
        }

        // A cast is not a vanilla swing, so it wears the blade a point of its own.
        EgoDurability.wearMainHand(player);
    }

    /**
     * How far the cleave reaches — <b>and how far it is drawn.</b> One curve, asked by both, which is the
     * whole point of it.
     *
     * <p>There were two. The cut was bounded and the picture was not, so past a big enough pool the blade
     * was drawn eighteen blocks and bit eight, and Nyrrine looked at it and said it looked like cheating.
     * She was right, and the earlier reading of her words was wrong: <i>"let the size just make the weapons
     * aoe <b>and</b> slash keep getting bigger"</i> asked for both to grow. Capping one and letting the
     * other run inverted her sentence, and the lie — even aimed at mercy — was still a lie a player could
     * see. Two numbers describing one thing will always drift; there is one number now and nothing to
     * reconcile. <b>The cleave cuts exactly what it draws, at every pool, forever.</b>
     *
     * <p>It grows from {@link #RELEASE_RADIUS_BASE} and stops at {@link #RELEASE_RADIUS_MAX}, which is a
     * real ceiling and is here for two reasons that agree. The scan is the one cost in this weapon that
     * genuinely tracks reach, so an unbounded radius is an unbounded {@code getNearbyEntities} on a
     * hundred-player box. And {@link #RELEASE_CAP} is per <em>body</em> — capped damage across an uncapped
     * body count is not a capped ability, it is the same absurdity through a side door. {@code sqrt} so the
     * first wounds are felt immediately and the last ones taper.
     */
    private static double cleaveRadius(double amount) {
        double grown = SLASH_BASE_RADIUS * (1.0 + Math.sqrt(Math.max(0.0, amount) / SLASH_REF));
        return Math.min(RELEASE_RADIUS_MAX, Math.max(RELEASE_RADIUS_BASE, grown));
    }

    // ---- Onrush: the rush you don't see coming --------------------------------------

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
            rush(player, target, true);                   // behind it: the blow it never sees
            execute(player, target);
            marks.remove(id);                            // the mark died with it
            onrushReadyAt.remove(id);                    // resets on the kill — no cooldown paid
        } else {
            rush(player, target, false);                 // to its face
            strike(player, target);
            onrushReadyAt.put(id, now + ONRUSH_COOLDOWN_MS);
        }

        // Rush + blow is a non-vanilla use, so it wears the blade beyond the swing it never made.
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

    // ---- the rush ---------------------------------------------------------------------

    /**
     * Put the wielder at the target's back ({@code behind}) or in its face, looking at it.
     *
     * <p>The arrival is instant — it has to be, or the blow that follows would be aimed at where the target
     * used to be — but nothing about it is allowed to read as a teleport. There is no enderman chime and no
     * puff at either end; instead {@link #rushFx} lays a body-height streak down the whole path, revealed
     * from where the wielder stood toward where they now are, so the eye is handed a wake to follow and
     * reads it as something that crossed the gap far too quickly rather than something that blinked out.
     *
     * <p>Wall-safe: the landing spot is probed for a clear two-block standing space and walked back toward
     * the target — which is by definition standing somewhere legal — until one is found. If nothing along
     * that line is clear the wielder simply doesn't move; the blow still lands, since the range check
     * already passed.
     */
    private void rush(Player player, LivingEntity target, boolean behind) {
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

        rushFx(player.getLocation(), landing);
        player.teleport(landing);
    }

    /**
     * The first spot from {@code want} back toward {@code anchor} where a player actually fits, or null.
     */
    private Location wallSafe(Location want, Location anchor) {
        if (Blink.canStand(want)) return want;

        Vector back = anchor.toVector().subtract(want.toVector());
        double span = back.length();
        if (span > 1.0e-6) {
            back.normalize();
            for (double d = 0.35; d <= span; d += 0.35) {
                Location probe = want.clone().add(back.clone().multiply(d));
                probe.setDirection(want.getDirection());
                if (Blink.canStand(probe)) return probe;
            }
        }

        Location last = anchor.clone();
        last.setDirection(want.getDirection());
        return Blink.canStand(last) ? last : null;
    }

    // ---- tick ------------------------------------------------------------------------

    /**
     * The reservoir gauge and Onrush's state, while Mimicry is in the main hand. Returns false the instant
     * it is not — a wielder who sheathes the blade must stop costing ticks immediately.
     *
     * <p>Note the pool itself is <em>not</em> dropped here: sheathing the weapon is not "out of combat", and
     * only {@link #RESERVOIR_DECAY_MS} without a fresh wound empties it.
     *
     * <p><b>The bar is scaled to {@link #RESERVOIR_FULL} rather than to some larger display number, and that
     * is the whole tell.</b> It saturates exactly when the damage and the reach do, so a full bar reads "this
     * is as hard as it will ever hit" — while the honest, unclamped count beside it goes on climbing to say
     * the blade will nonetheless keep getting bigger. The gauge teaches the split; no tooltip has to.
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

        Component label = plain("Nothing There  " + (long) Math.round(amount))
                .append(plain("  ")).append(onrush);
        player.sendActionBar(EgoHud.gauge(RUST, amount / RESERVOIR_FULL, label));
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
     * A wielder left. Their fence entry goes — it is per-call state and can only be stale — and so does
     * their M1 chain, which is an animation and means nothing across a session. Their reservoir and Onrush
     * cooldown deliberately stay: the design gives the pool a five-minute grace across a logout, and
     * dropping the cooldown here would make quitting a free reset. Both are timestamped and swept by
     * {@link #prune}, so neither can outlive its meaning.
     */
    @Override
    public void onQuit(UUID id) {
        striking.remove(id);
        marks.remove(id);
        lastDrinkAt.remove(id);
        combos.remove(id);
        prune(System.currentTimeMillis());
    }

    /** Nothing of Mimicry's is out in the world — only its bookkeeping, which goes here. */
    @Override
    public void onDisable() {
        reservoirs.clear();
        marks.clear();
        onrushReadyAt.clear();
        lastDrinkAt.clear();
        combos.clear();
        striking.clear();
    }

    // ---- the slash vocabulary ------------------------------------------------------------
    //
    // Arayashiki is the house's slash language and the named reference, so this is its grammar rather than
    // a second one invented alongside it: a parametric arc whose points are given staggered birth ticks so
    // the blade visibly travels its cut, each point lingering a few ticks and shrinking as it goes, with a
    // sparse END_ROD leading tip. What differs is the accent — Arayashiki cuts in white, Mimicry cuts in
    // bone bleeding to dried red — and the weight: every arc here starts thicker than Arayashiki's widest.

    /**
     * Lay down one arc. The cut lives in the plane spanned by {@code u} and {@code v}, swinging {@code
     * sweep} radians about {@code aMid}; {@code reverse} flips which end the blade starts from.
     *
     * <p>{@code force} is passed on every particle here, unconditionally: a large enough reservoir throws an
     * arc a hundred blocks across, and everything past ~32 blocks is culled client-side without it. The cost
     * is paid only by casts big enough to need it, because a small arc's points are all inside the radius
     * anyway.
     */
    private void arc(World world, Location pivot, Vector u, Vector v,
                     double radius, double sweep, double aMid, boolean reverse,
                     float thickness, int points, int reveal, int fade, boolean tip) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        final Location[] pts = new Location[points + 1];
        final int[] birth = new int[points + 1];

        // Hand-jitter, proportional to the cut so a huge one doesn't look laser-ruled, capped so it stays a
        // waver rather than a cloud.
        double jitter = Math.min(0.30, 0.02 * radius);

        for (int i = 0; i <= points; i++) {
            double f = (double) i / points;
            double a = aMid - sweep / 2.0 + sweep * f;
            Vector radial = u.clone().multiply(Math.cos(a) * radius)
                    .add(v.clone().multiply(Math.sin(a) * radius));
            Location p = pivot.clone().add(radial);
            p.add((rng.nextDouble() - 0.5) * jitter,
                  (rng.nextDouble() - 0.5) * jitter,
                  (rng.nextDouble() - 0.5) * jitter);
            pts[i] = p;
            int order = reverse ? (points - i) : i;
            birth[i] = Math.round((float) reveal * order / points);
        }

        animateArc(world, pts, birth, thickness, reveal, fade, tip);
    }

    /**
     * Reveal an arc point by point and let it fade. Holds only the point array and the world — never an
     * entity — so a cut still hanging in the air when its wielder dies or logs out pins nothing, and cancels
     * itself on a tick count rather than on anything's liveness.
     */
    private void animateArc(World world, Location[] pts, int[] birth, float thickness,
                            int reveal, int fade, boolean tip) {
        // One dust object per fade step instead of one per point per tick: the size is a pure function of
        // age, and at up to SLASH_POINTS_MAX points a tick the difference is real garbage.
        final Particle.DustTransition[] byAge = new Particle.DustTransition[fade];
        for (int a = 0; a < fade; a++) {
            byAge[a] = new Particle.DustTransition(BONE_C, BLOOD_C, thickness * (1.0f - 0.5f * a / fade));
        }

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > reveal + fade) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= fade) continue;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pts[i], 1, 0, 0, 0, 0, byAge[age], true);
                    if (tip && age == 0 && i % 5 == 0) {   // the glowing leading edge, sparse on purpose
                        world.spawnParticle(Particle.END_ROD, pts[i], 1, 0, 0, 0, 0, (Object) null, true);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** How wide the blade is drawn. Clamped, and {@link #SLASH_THICK_MAX} says why. */
    private static float slashThickness(double amount) {
        return (float) Math.min(SLASH_THICK_MAX,
                SLASH_THICK_BASE + Math.sqrt(Math.max(0.0, amount) / 50.0));
    }

    /**
     * How many points the arc is drawn with — <b>the perf ceiling, and the one thing the pool does not get
     * to grow.</b> Density tracks size until {@link #SLASH_POINTS_MAX}, past which a bigger cleave spends
     * exactly the same packets and simply spaces them further apart. At that scale each point is also being
     * drawn several blocks wide, so the arc still reads as an edge rather than a dotted line.
     */
    private static int slashPoints(double radius) {
        return Math.max(SLASH_POINTS_MIN, Math.min(SLASH_POINTS_MAX, (int) Math.round(radius * 8.0)));
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
     * The M1 chain. Beats one and two are wide swoops rolled opposite ways around the look direction; beat
     * three drops the blade overhead, a small quotation of the downswing the reservoir eventually buys, and
     * carries the weight in its sound rather than in more particles.
     */
    private void m1Fx(Player player, int beat) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Location pivot = player.getLocation().add(0, 1.1, 0);
        Vector dir = eye.getDirection().normalize();

        boolean heavy = beat == 2;

        if (heavy) {
            // Overhead: the plane of the cut is vertical, straight down through the facing.
            Vector fwd = dir.clone().setY(0);
            if (fwd.lengthSquared() < 1.0e-6) fwd = new Vector(0, 0, 1);
            fwd.normalize();
            arc(world, pivot, new Vector(0, 1, 0), fwd,
                    2.9, Math.toRadians(175), Math.toRadians(50), false,
                    2.2f, M1_POINTS_HEAVY, M1_REVEAL + 1, M1_FADE, true);

            world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.75f);
            world.playSound(eye, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.6f, 1.1f);
            world.playSound(eye, Sound.BLOCK_ANVIL_LAND, 0.22f, 1.7f);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye.clone().add(dir.clone().multiply(1.6)),
                    8, 0.3, 0.3, 0.3, 0.0, new Particle.DustTransition(BONE_C, BLOOD_C, 1.6f));
        } else {
            // A swoop rolled around the facing, alternating side to side so the chain reads as a rhythm.
            Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
            if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
            right.normalize();
            Vector up = right.clone().crossProduct(dir).normalize();

            double roll = (beat == 0 ? 0.7 : -0.7) + rng.nextDouble(-0.18, 0.18);
            Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

            arc(world, pivot, dir.clone(), v, 2.3 + rng.nextDouble() * 0.35,
                    Math.toRadians(165), rng.nextDouble(-0.2, 0.2), beat == 1,
                    1.9f, M1_POINTS, M1_REVEAL, M1_FADE, true);

            world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.85f, 1.25f + rng.nextFloat() * 0.3f);
            world.playSound(eye, Sound.ITEM_TRIDENT_RETURN, 0.4f, 1.6f + rng.nextFloat() * 0.25f);
        }
    }

    /**
     * Nothing There: the blade going up and coming down, drawn to the size of everything the wielder has
     * been made to swallow.
     *
     * <p><b>This is the uncapped half of the weapon, and it is uncapped precisely because it is inert.</b>
     * Nothing in here scans, spawns, or damages; it is one arc and one impact. The {@code amount} it is
     * handed is the raw pool, never the clamped release, so the picture goes on growing long after
     * {@link #RELEASE_CAP} and {@link #RELEASE_RADIUS_MAX} have stopped moving — which is the entire ask.
     * The cost of that is bounded by {@link #slashPoints} no matter how absurd the number gets.
     *
     * <p>The blow itself has already landed by the time the blade finishes falling. That is deliberate: the
     * alternative is holding the damage for {@link #DOWNSWING_REVEAL} ticks, which means re-validating every
     * victim afterwards and carrying entity references across ticks to do it. Four ticks is 200ms and the
     * impact reads as the hit; correctness is worth more than the frame.
     */
    private void nothingThereFx(Player player, double amount) {
        World world = player.getWorld();
        Location feet = player.getLocation();
        Location pivot = feet.clone().add(0, 1.2, 0);

        final double radius = cleaveRadius(amount);
        float thick = slashThickness(amount);
        int points = slashPoints(radius);

        // 0..1: how far past "big" this one is, used to lean on the sound rather than the particle count.
        final float bigness = (float) Math.min(1.0, radius / 24.0);

        // The plane of the cleave: straight down through the way the wielder is facing.
        Vector fwd = feet.getDirection().setY(0);
        if (fwd.lengthSquared() < 1.0e-6) fwd = new Vector(0, 0, 1);
        fwd.normalize();

        // The wind-up and the cut. Deep, and deeper the more it is carrying.
        world.playSound(feet, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.5f);
        world.playSound(feet, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 0.6f - bigness * 0.1f);
        world.playSound(feet, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.5f - bigness * 0.7f);
        if (bigness > 0.35f) {                              // only the genuinely absurd ones get the bass
            world.playSound(feet, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 0.5f);
            world.playSound(feet, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.55f * bigness, 0.65f);
        }

        // Up and back, over the top, down through the front: -40 degrees to +150.
        arc(world, pivot, new Vector(0, 1, 0), fwd, radius,
                Math.toRadians(190), Math.toRadians(55), false,
                thick, points, DOWNSWING_REVEAL, DOWNSWING_FADE, true);

        // The impact, on the frame the edge reaches the ground.
        new BukkitRunnable() {
            @Override
            public void run() {
                impactFx(world, feet, radius, bigness);
            }
        }.runTaskLater(plugin, DOWNSWING_REVEAL);
    }

    /** Where the downswing lands: a flash, a crack, and one fixed-budget ring pushed out to the cut's reach. */
    private void impactFx(World world, Location feet, double radius, float bigness) {
        world.playSound(feet, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.6f);
        world.playSound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.8f + bigness * 0.2f, 0.6f);
        world.playSound(feet, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 0.9f, 0.7f);

        Location chest = feet.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.EXPLOSION, chest, 1, 0, 0, 0, 0, (Object) null, true);
        world.spawnParticle(Particle.SWEEP_ATTACK, chest, IMPACT_SWEEPS, 1.0, 0.5, 1.0, 0.0, (Object) null, true);
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, BLOOD_C, true);  // FLASH takes a Color here

        // The shockwave. Fixed point count, so its cost never tracks the size of the cut.
        double ring = Math.min(radius, SHOCKWAVE_RADIUS_MAX);
        Particle.DustTransition bleed = new Particle.DustTransition(BONE_C, BLOOD_C, 2.0f + bigness * 3.0f);
        for (int i = 0; i < SHOCKWAVE_POINTS; i++) {
            double a = (Math.PI * 2 * i) / SHOCKWAVE_POINTS;
            Location p = feet.clone().add(Math.cos(a) * ring, 0.3, Math.sin(a) * ring);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.05, 0.05, 0.05, 0.0, bleed, true);
        }
    }

    /**
     * The rush: a body-height wake laid down the whole path and revealed from the wielder's old ground
     * toward their new, so what the eye follows is something that crossed the gap rather than something that
     * stopped existing in one place and started in another.
     *
     * <p>There is deliberately no {@code ENTITY_ENDERMAN_TELEPORT} here. It was the old cue and it is the
     * literal sound of a teleport, which is precisely the read this is meant to kill; the departure is a
     * riptide crack, the crossing is a sweep at the midpoint, and the arrival is a body's worth of weight
     * hitting the ground.
     */
    private void rushFx(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) return;

        Vector step = to.toVector().subtract(from.toVector());
        double span = step.length();
        if (span < 1.0e-6) return;
        step.normalize();

        // Departure, crossing, arrival — three points in space, not one chime at both ends.
        world.playSound(from, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.9f, 1.5f);
        world.playSound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f);
        world.playSound(from.clone().add(step.clone().multiply(span * 0.5)),
                Sound.ITEM_TRIDENT_THROW, 0.7f, 1.9f);
        world.playSound(to, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 1.4f);
        world.playSound(to, Sound.ENTITY_GENERIC_BIG_FALL, 0.5f, 1.6f);

        // Sample the path, capped: fourteen blocks at RUSH_STEP is ~31 samples, and the cap holds it there
        // however the range is retuned.
        int n = Math.min(RUSH_POINTS_MAX, Math.max(2, (int) Math.round(span / RUSH_STEP)));
        final Location[] pts = new Location[(n + 1) * 2];
        final int[] birth = new int[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double d = span * i / n;
            Location base = from.clone().add(step.clone().multiply(d));
            int b = Math.round((float) RUSH_REVEAL * i / n);
            // Two strands, knee and shoulder: a person went through here, not a thread.
            pts[i * 2] = base.clone().add(0, 0.6, 0);
            pts[i * 2 + 1] = base.clone().add(0, 1.5, 0);
            birth[i * 2] = b;
            birth[i * 2 + 1] = b;
        }

        animateArc(world, pts, birth, 1.7f, RUSH_REVEAL, RUSH_FADE, true);
    }

    /** The devastating blow to a foe that stood its ground: a heavy cleave and a burst of red. */
    private void strikeFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);

        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.65f);
        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
        world.playSound(body, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.5f);
        world.playSound(body, Sound.ENTITY_HOGLIN_ATTACK, 0.4f, 0.6f + (rng.nextFloat() - 0.5f) * 0.1f);

        // A real cut across the body, in the same grammar as everything else this weapon does.
        Vector across = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (across.lengthSquared() < 1.0e-6) across = new Vector(0, 0, 1);
        across.normalize();
        Vector side = across.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
        side.normalize();
        arc(world, body, side, new Vector(0, 1, 0), 1.9, Math.toRadians(150),
                Math.toRadians(20), rng.nextBoolean(), 2.1f, 16, 2, 3, true);

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
        world.playSound(neck, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 0.35f, 1.9f);

        // One short, fast cut straight across the nape — the blow it never saw.
        Vector side = victim.getLocation().getDirection().setY(0);
        if (side.lengthSquared() < 1.0e-6) side = new Vector(0, 0, 1);
        side = side.crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
        side.normalize();
        arc(world, neck, side, new Vector(0, 1, 0), 1.3, Math.toRadians(120), 0.0,
                false, 1.8f, 12, 1, 3, true);

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
                    "The yearning to imitate the human",
                    "form is sloppily reflected on the",
                    "E.G.O, as if it were a reminder that",
                    "it should remain a mere desire.",
                    "When the unfamiliar and otherworldly",
                    "eyes stare at you, you will feel a",
                    "chill up your spine. If pushed to the",
                    "limit, one can wield it.",
                    "",
                    "It can deliver a powerful downswing",
                    "that should be impossible for a",
                    "human."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Is That the Red Mist?!?",
                            "Heal 25% of the damage you deal,",
                            "at most once every 5 seconds."),
                    new EgoLore.Ability("[Passive] The reservoir",
                            "Damage dealt TO you pools in the",
                            "blade, and the pool itself has no",
                            "ceiling. The gauge reads full at",
                            "120 — the point past which it can",
                            "buy no more. The pool empties",
                            "after 5 minutes without a wound."),
                    new EgoLore.Ability("[Shift + Right-click] Nothing There",
                            "Bring the blade down. Everything",
                            "nearby takes half the pooled total,",
                            "up to 60, then the pool is spent.",
                            "Reach grows with the pool, 5 to 8",
                            "blocks, and stops at a full gauge.",
                            "The blade you swing does not: the",
                            "more it swallowed, the more absurd",
                            "the cut looks. It just won't cut",
                            "any deeper."),
                    new EgoLore.Ability("[Right-click] Onrush",
                            "Rush the foe you last struck and",
                            "finish it: player <25%, mob <50%,",
                            "boss <10% HP. Free if it lands.",
                            "Otherwise rush to its face and",
                            "strike hard — 3 minute cooldown.")
            ));
}
