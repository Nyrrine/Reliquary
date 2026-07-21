package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
import com.nyrrine.reliquary.core.EgoWeapon;
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
import org.bukkit.attribute.AttributeModifier;
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
 *   <li><b>Lifesteal</b> ({@link #onHit}, passive) — a landed blow mends the wielder for
 *       {@link #LIFESTEAL_FRACTION} of the damage it dealt, throttled to once per
 *       {@link #LIFESTEAL_THROTTLE_MS}. The four-beat chain's finisher drinks deeper
 *       ({@link #FINISHER_LIFESTEAL_FRACTION}) and ignores the throttle, so its payoff always lands.</li>
 *   <li><b>The reservoir</b>, shown to the wielder as <b>"Hello."</b> ({@link #onDamaged}, passive) — every
 *       point of damage that <em>lands on</em> the wielder pools in the blade. It is the wounds it
 *       remembers, not the wounds it gave. The pool itself is never clamped; what it can <em>buy</em> is
 *       (see below). It evaporates after {@link #RESERVOIR_DECAY_MS} without a fresh wound (whether the
 *       wielder is logged in for that lull or not).</li>
 *   <li><b>Held boons</b> ({@link #applyHold}, passive) — while the blade is held the wielder's max health
 *       doubles by {@link #HOLD_MAX_HEALTH_BONUS} and their melee reach extends by {@link #HOLD_REACH_BONUS},
 *       two keyed attribute modifiers lent on equip and taken back the instant the blade is sheathed, on
 *       death, on join, and on plugin disable — so no wielder outlives the blade at two heart-rows or with a
 *       longer arm than the sword grants.</li>
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
public final class MimicryWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Mimicry. */
    private final NamespacedKey key;

    /** Keys the doubled-max-health modifier Mimicry lays on its wielder while the blade is held. */
    private final NamespacedKey holdHealthKey;
    /** Keys the extended-melee-reach modifier Mimicry lays on its wielder while the blade is held. */
    private final NamespacedKey holdReachKey;

    // ---- tuning: the passive drink -------------------------------------------------

    /**
     * PLACEHOLDER (balance wave): how much harder the regular M1 slices (beats 1-3) hit, and the heavier
     * multiplier the finisher earns so it stays the biggest cut of the chain (Nyrrine §7: bump the finisher
     * too). Both are multipliers on the vanilla swing in {@link #onHit}, so enchants and crits still scale
     * (never a flat set — not the Regret bug). Exact values are in Nyrrine's balance doc.
     */
    private static final double M1_REGULAR_DAMAGE_MULT = 1.35;
    private static final double M1_FINISHER_DAMAGE_MULT = 1.65;

    /** Fraction of a landed blow's damage the weapon drinks back into its wielder. */
    private static final double LIFESTEAL_FRACTION = 0.25;

    /**
     * PLACEHOLDER (feel, not balance): the deeper drink the M1 chain's finisher earns — a bigger fraction
     * than {@link #LIFESTEAL_FRACTION}, and it ignores the throttle so the payoff always lands (Nyrrine's
     * ask: the 4th strike heals a bit more). She tunes the magnitude in her number wave.
     */
    private static final double FINISHER_LIFESTEAL_FRACTION = 0.50;

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
     * Radius of the cut: its floor at an empty pool, and the hard ceiling it climbs to — reached at roughly
     * 750 banked wounds, <em>not</em> at {@link #RESERVOIR_FULL}, where the cut is only ~8.2. One scan, one
     * strike per body inside it. See {@link #cleaveRadius}.
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

    /**
     * The curve {@link #cleaveRadius} grows the reach along — mechanical inputs to the kill radius and the
     * entity scan, not cosmetics, which is why they live here beside the ceiling they answer to and not in
     * the "show" block. {@code SLASH_BASE_RADIUS} is the bare reach the {@code sqrt} growth scales from;
     * {@code SLASH_REF} is the pool at which that growth has doubled it.
     */
    private static final double SLASH_BASE_RADIUS = 3.0;
    private static final double SLASH_REF = 40.0;

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
    private static final long ONRUSH_COOLDOWN_MS = 45_000L; // 45 seconds

    /** How far in front of / behind the target the wielder lands. */
    private static final double ONRUSH_GAP = 1.4;

    /**
     * Bosses. "Abnormalities" and "NPC distortions" from the design have no entity type in this codebase —
     * see {@link #thresholdFor}.
     */
    private static final Set<EntityType> BOSS_TYPES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);

    // ---- tuning: the hold ----------------------------------------------------------

    /**
     * Max-health the blade lends its wielder while it is held — a keyed {@link Attribute#MAX_HEALTH}
     * modifier that doubles a vanilla 20 to two full heart-rows, then drops away the instant the blade is
     * sheathed. It is the spec'd mechanic itself, not a duplicate of any balance dial, so a held modifier is
     * its correct home (unlike attack speed, whose one dial is {@code EgoModels.MIMICRY.spd}). The modifier
     * is keyed and remove-before-add, cleared on release, on death, on join, and on plugin disable, so no
     * wielder is ever left carrying a heart-row the blade no longer lends.
     */
    private static final double HOLD_MAX_HEALTH_BONUS = 20.0;

    /**
     * PLACEHOLDER (balance wave): extra melee reach the blade lends while held — a keyed
     * {@link Attribute#ENTITY_INTERACTION_RANGE} modifier on top of the vanilla 3.0, extending the swing "a
     * little" (Nyrrine's tweak). No existing dial sets this attribute, so a held modifier is its sole and
     * correct home. Managed with the heart-row modifier: applied on hold, stripped on release/death/join/
     * disable, so no wielder keeps reach the blade no longer lends.
     */
    private static final double HOLD_REACH_BONUS = 0.75;

    // ---- tuning: the show ----------------------------------------------------------
    //
    // This block is the cosmetic half of the weapon, and every number in it is a ceiling on COST rather than
    // on SIZE — the trick that keeps a large spectacle affordable at ~100 players and ~13 TPS. The rule: the
    // pool scales the arc's THICKNESS and how far its points spread, its POINT COUNT not at all past
    // SLASH_POINTS_MAX. The biggest cleave and a small one cost the server the same packets; the big one just
    // spaces them further apart and draws them fatter, which is exactly where the drama lives.
    //
    // Everything below is inert: no number here feeds a damage figure or an entity scan, so any of it is safe
    // to make more absurd. The two constants that DO feed the kill radius — SLASH_BASE_RADIUS and SLASH_REF —
    // deliberately live up in the reservoir block beside the RELEASE_RADIUS_* ceiling they answer to, NOT
    // here, so that bumping a show number can never silently widen what the blade actually cuts. It could
    // once: they used to live in this block, under this very comment, and that is how the two radii drifted.

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

    /** Ticks after the downswing gash opens before its ground-impact rings out. */
    private static final int DOWNSWING_REVEAL = 4;

    /** The impact ring: a fixed budget, pushed out to the reach of the cut but never past this. */
    private static final int SHOCKWAVE_POINTS = 48;
    private static final double SHOCKWAVE_RADIUS_MAX = 24.0;
    private static final int IMPACT_SWEEPS = 6;

    /** Points in an M1 gash, and in the heavier one that ends the four-beat chain. */
    private static final int M1_POINTS = 18;
    private static final int M1_POINTS_HEAVY = 26;

    /**
     * PLACEHOLDER (feel, not balance): the forward step the finisher beat throws the wielder into its cut.
     * A velocity nudge only — vanilla collision keeps it wall-safe. Tune with the rest of the numbers next
     * wave.
     */
    private static final double FINISHER_LUNGE = 0.55;

    /**
     * PLACEHOLDER (feel, not balance): the least a swing may follow the last <em>drawn</em> one before the
     * blade is too heavy to leave a mark — the anti-spam cadence gate (Nyrrine §1.2). Deliberately an
     * independent feel value, <b>not</b> derived from {@code EgoModels.MIMICRY.spd}, so retuning her one
     * speed dial next wave never silently drags this with it (rail #3). Raise it for more commitment.
     */
    private static final long MIN_SWING_INTERVAL_MS = 300L;

    /** How rarely the swallowed-swing strain cue may sound, so mashing can't machine-gun it. */
    private static final long RESIST_CUE_THROTTLE_MS = 220L;

    /**
     * A gash can open two ways. An <b>impact</b> gash snaps the whole wound open at once ({@link
     * #GASH_REVEAL} ticks) — the on-hit splash, the downswing landing, the execute. A <b>swing</b> gash
     * travels end to end over {@link #SLICE_REVEAL} ticks, so the sweep itself reads as the blade moving —
     * the forward M1 slices. Both bleed dark over {@link #GASH_FADE}.
     */
    private static final int GASH_REVEAL = 2;
    private static final int SLICE_REVEAL = 5;
    private static final int GASH_FADE = 5;

    /**
     * How far in front of the wielder the M1 slice is thrown. Enough to still read apart from the on-hit
     * splash that lands on the struck target (Nyrrine round-3), but pulled in close so the regular slices
     * connect near the player rather than way out front (her tweak). PLACEHOLDER — feel value for her wave.
     */
    private static final double FORWARD_SLICE_OFFSET = 1.0;

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

    /** Wielder -> where they are in the four-beat M1 chain. Cosmetic only. */
    private final Map<UUID, Combo> combos = new HashMap<>();

    /** Wielders currently inside their own ability damage — the fence against re-entrant hooks. */
    private final Set<UUID> striking = new HashSet<>();

    /** Wielders currently carrying the held max-health modifier, so plugin-disable can strip every one. */
    private final Set<UUID> holders = new HashSet<>();

    /** When {@link #prune} last swept. */
    private long lastPruneMs = 0L;

    public MimicryWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mimicry");
        this.holdHealthKey = new NamespacedKey(plugin, "mimicry_hold_health");
        this.holdReachKey = new NamespacedKey(plugin, "mimicry_hold_reach");
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

    /** Where a wielder is in the M1 chain, when they last swung, and when the heavy-resist cue last fired. */
    private static final class Combo {
        private int beat;
        private long lastMs;
        private long lastResistMs;
        /** True when the last drawn swing was the finisher — the next landing hit drinks deeper, once. */
        private boolean finisherPrimed;
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

        // Which M1 beat this swing is: the finisher was primed on its draw. Every landed slice hits harder
        // through a multiplier (so enchants and crits still scale), and the finisher hits the hardest of all
        // — it stays the biggest cut of the chain on top of its deeper drink, lunge and reach.
        Combo combo = combos.get(aid);
        boolean finisher = combo != null && combo.finisherPrimed;
        event.setDamage(event.getDamage() * (finisher ? M1_FINISHER_DAMAGE_MULT : M1_REGULAR_DAMAGE_MULT));

        // The damage the blow actually lands, after armour and resistances — what the weapon really took.
        double dealt = event.getFinalDamage();
        if (dealt <= 0.0) return;

        hitSplashFx(attacker, victim);   // the cut is felt where it lands, not only drawn in the air
        mark(aid, victim, now);

        // The finisher's landing blow drinks deeper, once. A normal swing takes the ordinary throttled sip.
        if (finisher) {
            combo.finisherPrimed = false;
            drink(attacker, dealt, now, FINISHER_LIFESTEAL_FRACTION, true);
        } else {
            drink(attacker, dealt, now);
        }
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
     * Lifesteal — mend the wielder for a quarter of the wound, at most once per
     * {@link #LIFESTEAL_THROTTLE_MS}, and never past their own max health.
     */
    private void drink(Player attacker, double dealt, long now) {
        drink(attacker, dealt, now, LIFESTEAL_FRACTION, false);
    }

    /**
     * The drink, with the fraction and throttle spelled out. The finisher's deeper draw ({@link
     * #FINISHER_LIFESTEAL_FRACTION}) passes {@code bypassThrottle} so its payoff always lands; it still
     * stamps the throttle, so the swings around it cannot also drink.
     */
    private void drink(Player attacker, double dealt, long now, double fraction, boolean bypassThrottle) {
        UUID aid = attacker.getUniqueId();
        if (!bypassThrottle) {
            Long last = lastDrinkAt.get(aid);
            if (last != null && now - last < LIFESTEAL_THROTTLE_MS) return;
        }

        AttributeInstance maxAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        double health = attacker.getHealth();
        if (health >= max) return;                       // already whole — don't burn the throttle on nothing

        double healed = Math.min(max, health + dealt * fraction);
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
     * The M1 chain: a real slash on every swing, a bespoke four-beat combo carrying the weight of the Red
     * Mist. Two wide crescent sweeps rolled opposite ways, a rising uppercut, then a heavy fleshy finisher
     * that drops the blade overhead, bursts blood and a watching eye, and steps the wielder into it — a
     * small rhyme with the downswing the reservoir eventually buys. Modelled on Gebura's heavy red
     * crescents rather than Lævateinn's lighter language.
     *
     * <p>This is animation only. The vanilla swing under it is untouched and is what deals the damage;
     * adding a blow here would double-hit and is not what "m1 animations" asks for. The finisher's forward
     * step is a velocity nudge, never a teleport, so vanilla collision keeps it wall-safe for free.
     */
    @Override
    public void onSwing(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Combo c = combos.get(id);
        long sinceLast = c == null ? Long.MAX_VALUE : now - c.lastMs;

        // The blade is heavy: spam-clicking cannot force a flurry of light slashes out of it. A swing that
        // arrives before the cadence has recovered is swallowed to a grunt of strain — no new cut, no beat
        // advanced, so reaching the finisher takes four committed swings, not four mashed ones. This is the
        // in-file weight lever (Nyrrine §1.2); the real attack rate stays EgoModels.MIMICRY.spd, untouched.
        if (sinceLast < MIN_SWING_INTERVAL_MS) {
            heavyResistFx(player, c);
            return;
        }

        if (c == null || sinceLast > COMBO_RESET_MS) {
            c = new Combo();
            combos.put(id, c);
        }
        c.lastMs = now;
        int beat = c.beat;
        c.beat = (c.beat + 1) % 4;
        c.finisherPrimed = beat == 3; // the finisher swing earns the deeper drink on the blow it lands

        m1Fx(player, beat);
    }

    // ---- Nothing There: give back every wound at once --------------------------------

    /**
     * Bring the blade down. Everything living inside {@link #cleaveRadius} takes {@link #RELEASE_FRACTION}
     * of the pooled total, capped at {@link #RELEASE_CAP}. One {@code getNearbyEntities} scan for the whole
     * cast, one blow per body.
     *
     * <p>Both mechanical numbers are read here, and they saturate at different pools: the damage
     * ({@code perTarget}) caps at {@link #RELEASE_CAP} once the pool reaches {@link #RESERVOIR_FULL}, while the
     * reach ({@code radius}) keeps climbing well past that to its own ceiling ({@link #RELEASE_RADIUS_MAX},
     * ~750 banked). Past both, a bigger pool buys only a bigger <em>picture</em> ({@link #nothingThereFx},
     * which reads the raw amount and has no ceiling). This is the only method where all three are visible side
     * by side — {@code perTarget} and {@code radius} are clamped, {@code amount} is handed to the FX untouched.
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
            renderBar(player); // the composed line already shows Onrush's rest beside the reservoir gauge
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

    /** The blow a foe that was not faltering takes: devastating, bought with 45 seconds of silence. */
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
     * is the whole tell.</b> It saturates exactly when the damage does, so a full bar reads "this is as hard
     * as it will ever hit" — while the honest, unclamped count beside it goes on climbing to say the reach
     * will nonetheless keep growing past it. The gauge teaches the split; no tooltip has to.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) {
            releaseHold(player);                          // sheathed — hand back the lent heart-row at once
            return false;
        }
        applyHold(player);                               // held — lend the second heart-row (idempotent)

        long now = System.currentTimeMillis();
        if (now - lastPruneMs >= PRUNE_PERIOD_MS) prune(now);

        renderBar(player);
        return true;
    }

    /**
     * The reservoir and Onrush, composed onto ONE line via {@link EgoHud#row} — both states at once, so a
     * spent Onrush never flashes its cooldown in over the "Hello." gauge. The gauge is scaled to
     * {@link #RESERVOIR_FULL} (see the tick docs), while the count beside it climbs on unclamped. Every path
     * that used to send a lone Onrush cooldown now sends this.
     */
    private void renderBar(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        double amount = pooled(id, now);
        player.sendActionBar(EgoHud.row(reservoirReadout(amount), onrushReadout(id, now)));
    }

    /** The reservoir half: the "Hello." gauge, scaled to {@link #RESERVOIR_FULL}, and its unclamped count. */
    private Component reservoirReadout(double amount) {
        Component label = plain("Hello.  " + (long) Math.round(amount));
        return EgoHud.gauge(RUST, amount / RESERVOIR_FULL, label);
    }

    /** The Onrush half: its cooldown while resting (whole seconds, never millis), else ready. */
    private Component onrushReadout(UUID id, long now) {
        Long readyAt = onrushReadyAt.get(id);
        return (readyAt != null && now < readyAt)
                ? EgoHud.cooldown("Onrush", readyAt - now, RUST)
                : EgoHud.ready("Onrush", BONE);
    }

    // ---- the held heart-row ------------------------------------------------------------

    /**
     * Lend the wielder the second heart-row and the extended reach while the blade is held. Idempotent and
     * remove-before-add per modifier, so calling it every tick can neither stack a bonus nor leave a foreign
     * copy behind; it also re-heals a modifier if something else stripped it out from under a live holder.
     */
    private void applyHold(Player player) {
        holders.add(player.getUniqueId());
        ensureHoldModifier(player, Attribute.MAX_HEALTH, holdHealthKey, HOLD_MAX_HEALTH_BONUS);
        ensureHoldModifier(player, Attribute.ENTITY_INTERACTION_RANGE, holdReachKey, HOLD_REACH_BONUS);
    }

    /** Add a keyed hold modifier if it is not already present — never stacks, re-heals if stripped. */
    private void ensureHoldModifier(Player player, Attribute attr, NamespacedKey holdKey, double amount) {
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (holdKey.equals(m.getKey())) return;          // already lent — nothing to do
        }
        inst.addModifier(new AttributeModifier(holdKey, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    /** Take the lent heart-row and reach back; clamp the wielder down into the smaller max HP it leaves. */
    private void releaseHold(Player player) {
        holders.remove(player.getUniqueId());
        removeHoldModifier(player, Attribute.ENTITY_INTERACTION_RANGE, holdReachKey, false);
        removeHoldModifier(player, Attribute.MAX_HEALTH, holdHealthKey, true);
    }

    /** Strip a keyed hold modifier if present; when {@code clampHealth}, pull the wielder into the new max. */
    private void removeHoldModifier(Player player, Attribute attr, NamespacedKey holdKey, boolean clampHealth) {
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) return;
        boolean removed = false;
        for (AttributeModifier m : new java.util.ArrayList<>(inst.getModifiers())) {
            if (holdKey.equals(m.getKey())) { inst.removeModifier(m); removed = true; }
        }
        if (clampHealth && removed && player.getHealth() > inst.getValue()) {
            player.setHealth(Math.max(0.0, inst.getValue()));    // don't leave health hanging above the new max
        }
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
        releaseHold(event.getEntity());                  // don't carry the lent heart-row through respawn
    }

    /** Defensive on login: shed any held heart-row saved on a player by an unclean shutdown. */
    @Override
    public void onJoin(Player player) {
        releaseHold(player);
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
        holders.remove(id);   // the lent heart-row is saved on the departing body; onJoin sheds it on return
        prune(System.currentTimeMillis());
    }

    /**
     * Nothing of Mimicry's is out in the world — only its bookkeeping. But the held heart-row is a live
     * modifier on every current holder, so return it to each before the plugin lets go: no one is left at
     * two heart-rows the moment Mimicry stops running.
     */
    @Override
    public void onDisable() {
        for (UUID id : new java.util.ArrayList<>(holders)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) releaseHold(p);
        }
        holders.clear();
        reservoirs.clear();
        marks.clear();
        onrushReadyAt.clear();
        lastDrinkAt.clear();
        combos.clear();
        striking.clear();
    }

    // ---- the movement wake -----------------------------------------------------------------
    //
    // Mimicry's SLICES are the gash above — its own deep-red language, not Arayashiki's. What is left here is
    // only the Onrush wake ({@link #rushFx}): a travelling streak whose points are given staggered birth
    // ticks so the eye follows something crossing the gap, each point lingering a few ticks and shrinking as
    // it goes. It is recoloured to Mimicry red like everything else, with a crimson leading edge, no white.

    /**
     * Reveal a travelling streak point by point and let it fade. Holds only the point array and the world —
     * never an entity — so a wake still hanging in the air when its wielder dies or logs out pins nothing,
     * and cancels itself on a tick count rather than on anything's liveness.
     */
    private void animateArc(World world, Location[] pts, int[] birth, float thickness,
                            int reveal, int fade, boolean tip) {
        // One dust object per fade step instead of one per point per tick: the size is a pure function of
        // age, and at up to SLASH_POINTS_MAX points a tick the difference is real garbage.
        final Particle.DustTransition[] byAge = new Particle.DustTransition[fade];
        for (int a = 0; a < fade; a++) {
            byAge[a] = new Particle.DustTransition(MIM_CORE, MIM_DARK, thickness * (1.0f - 0.5f * a / fade));
        }
        final Particle.DustOptions edge = new Particle.DustOptions(MIM_CORE, thickness * 0.7f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > reveal + fade) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= fade) continue;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pts[i], 1, 0, 0, 0, 0, byAge[age], true);
                    if (tip && age == 0 && i % 5 == 0) {   // the hot leading edge, crimson not white, sparse
                        world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0, edge, true);
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

    // ---- Mimicry's own cut: the gash -----------------------------------------------------
    //
    // Arayashiki draws a thin white line that travels its arc; Mimicry does NOT (Nyrrine §1.5, greenfield).
    // Its cut is a WOUND: a thick, deep-red crescent that snaps open all at once, bleeds to dark, carries a
    // hot crimson leading edge, and blinks watching eyes open along its length. Same per-point budget as the
    // arc — everything that makes it Mimicry's own is colour, timing, width, and eyes, not particle count.

    /**
     * Lay one gash. The crescent lives in the plane spanned by {@code u} and {@code v}, opening {@code
     * sweep} radians about {@code aMid}; {@code eyes} watching eyes blink open evenly along it.
     */
    private void gash(World world, Location pivot, Vector u, Vector v,
                      double radius, double sweep, double aMid, boolean reverse,
                      float thickness, int points, int eyes, int reveal) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        final Location[] pts = new Location[points + 1];
        final int[] birth = new int[points + 1];
        double jitter = Math.min(0.28, 0.02 * radius);
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
            birth[i] = reveal <= 0 ? 0 : Math.round((float) reveal * order / points);
        }
        animateGash(world, pts, birth, thickness, reveal);

        for (int k = 0; k < eyes && points > 0; k++) {
            int idx = (int) Math.round((k + 1.0) / (eyes + 1.0) * points);
            mimEye(world, pts[Math.min(points, idx)], Math.max(1.0f, thickness * 0.6f));
        }
    }

    /**
     * Reveal a gash: deep red, thick, bleeding to dark. A small {@code reveal} snaps the whole wound open at
     * once (an impact); a larger one lets it travel end to end (a swing sweeping through). Like
     * {@link #animateArc} it holds only the point array and the world — never an entity — so a cut still
     * hanging when its wielder dies pins nothing, and it cancels on a tick count rather than on liveness.
     */
    private void animateGash(World world, Location[] pts, int[] birth, float thickness, int reveal) {
        final Particle.DustTransition[] byAge = new Particle.DustTransition[GASH_FADE];
        for (int a = 0; a < GASH_FADE; a++) {
            byAge[a] = new Particle.DustTransition(MIM_CORE, MIM_DARK, thickness * (1.0f - 0.45f * a / GASH_FADE));
        }
        final Particle.DustOptions core = new Particle.DustOptions(MIM_CORE, thickness * 0.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t > reveal + GASH_FADE) { cancel(); return; }
                for (int i = 0; i < pts.length; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= GASH_FADE) continue;
                    // Count two with a little spread so the wound reads filled and fleshy, not a dotted line.
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pts[i], 2, 0.05, 0.05, 0.05, 0.0, byAge[age], true);
                    if (age == 0) world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0, core, true);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A single watching eye: a white sclera cluster around one black pupil (Nyrrine §1.5). */
    private void mimEye(World world, Location at, float size) {
        world.spawnParticle(Particle.DUST, at, 5, 0.07, 0.07, 0.07, 0.0,
                new Particle.DustOptions(EYE_SCLERA, size), true);
        world.spawnParticle(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0,
                new Particle.DustOptions(EYE_PUPIL, size * 0.55f), true);
    }

    /**
     * The splash felt where a swing lands (Nyrrine §1.2) — not just a trail in the air. A short red gash is
     * torn across the struck body with a visceral spray of blood; the vanilla swing under it is untouched.
     * Fenced out of ability damage by {@link #onHit}'s {@link #striking} guard, so only real swings splash.
     */
    private void hitSplashFx(Player attacker, LivingEntity victim) {
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, Math.max(0.6, victim.getHeight()) * 0.55, 0);

        Vector across = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (across.lengthSquared() < 1.0e-6) across = new Vector(0, 0, 1);
        across.normalize();
        Vector side = across.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
        side.normalize();
        gash(world, body, side, new Vector(0, 1, 0), 1.15, Math.toRadians(150),
                Math.toRadians(20), ThreadLocalRandom.current().nextBoolean(), 2.3f, 12, 1, GASH_REVEAL);

        world.spawnParticle(Particle.DUST, body, 16, 0.28, 0.32, 0.28, 0.0,
                new Particle.DustOptions(BLOOD_C, 1.5f), true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 8, 0.3, 0.32, 0.3, 0.0,
                new Particle.DustTransition(MIM_CORE, MIM_DARK, 1.6f), true);
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0.0, 0.0, 0.0, 0.0);

        // One clean impact — no creature grunt (Nyrrine round-3).
        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.7f);
    }

    /**
     * A swing swallowed by the cadence gate: only a wisp of dark red, so the weight is felt without adding
     * noise. The grunt of strain is gone — nothing living, nothing to pollute (Nyrrine round-3).
     */
    private void heavyResistFx(Player player, Combo c) {
        long now = System.currentTimeMillis();
        if (now - c.lastResistMs < RESIST_CUE_THROTTLE_MS) return;
        c.lastResistMs = now;
        Location at = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.9));
        player.getWorld().spawnParticle(Particle.DUST, at, 3, 0.12, 0.12, 0.12, 0.0,
                new Particle.DustOptions(MIM_DARK, 1.0f), true);
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
     * The M1 chain: four custom beats. Beats one to three are forward Gebura slices — deep-red crescents
     * thrown out in FRONT of the wielder and sweeping through over {@link #SLICE_REVEAL} ticks, so the swing
     * reads as its own forward motion, apart from the on-hit splash that lands back on the struck body
     * (Nyrrine round-3: the slash must read separately from the impact). Beat four is the heavy finisher.
     * Animation only — the vanilla swing under it deals the damage.
     *
     * <p>The sound is one clean blade slash per beat and nothing else. The roars and grunts are gone
     * (Nyrrine round-3: "sound like a slash, not alive").
     */
    private void m1Fx(Player player, int beat) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        if (beat == 3) {
            m1Finisher(player, world, eye, player.getLocation().add(0, 1.1, 0), dir);
            return;
        }

        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();

        // A forward slice: the crescent is thrown ahead of the eye and bows forward along the aim, spanning a
        // diagonal that alternates beat to beat so the chain reads as a rhythm of forward cuts.
        Location pivot = eye.clone().add(dir.clone().multiply(FORWARD_SLICE_OFFSET));
        double roll = (switch (beat) {
            case 0 -> 0.9;
            case 1 -> -0.9;
            default -> 0.0;   // beat 2: a slice straight up the middle
        }) + rng.nextDouble(-0.12, 0.12);
        Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

        gash(world, pivot, dir.clone(), v, 2.6 + rng.nextDouble() * 0.3,
                Math.toRadians(150), 0.0, beat == 1, 2.4f, M1_POINTS, 1, SLICE_REVEAL);

        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.9f + rng.nextFloat() * 0.2f);
    }

    /**
     * The fourth beat: a heavy overhead crescent brought straight down, a fleshy burst thrown out where the
     * edge lands, and a short forward step into the blow. The weight lives in a slower reveal, not in more
     * particles, so it stays within the same perf budget as the lighter beats.
     */
    private void m1Finisher(Player player, World world, Location eye, Location pivot, Vector dir) {
        Vector fwd = dir.clone().setY(0);
        if (fwd.lengthSquared() < 1.0e-6) fwd = new Vector(0, 0, 1);
        fwd.normalize();

        gash(world, pivot, new Vector(0, 1, 0), fwd,
                3.2, Math.toRadians(195), Math.toRadians(55), false,
                3.0f, M1_POINTS_HEAVY, 3, GASH_REVEAL);

        // A heavy but clean slash — the strong sweep, nothing living. (Nyrrine round-3.)
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.65f);
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f);

        // The fleshy signature: a spray of blood and a watching eye, out ahead where the edge comes down.
        fleshyBurst(world, eye.clone().add(dir.clone().multiply(2.0)), 1.0f);

        // Step into the swing — a velocity nudge, wall-safe by vanilla collision, never a teleport.
        Vector step = fwd.clone().multiply(FINISHER_LUNGE).setY(0.16);
        player.setVelocity(player.getVelocity().add(step));
    }

    /**
     * The ALEPH accent shared by Mimicry's heaviest moments: a thick spray of blood bleeding hot-crimson to
     * dark, and a single watching eye (white sclera, black pupil) opening in the middle of it. All red, no
     * light (Nyrrine §1.2). A fixed budget scaled by {@code scale}, so even the largest cast cannot let it
     * run away with the frame.
     */
    private void fleshyBurst(World world, Location at, float scale) {
        int blood = Math.max(8, Math.round(18 * scale));
        world.spawnParticle(Particle.DUST, at, blood, 0.38, 0.42, 0.38, 0.0,
                new Particle.DustOptions(BLOOD_C, 1.6f), true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Math.max(4, Math.round(6 * scale)), 0.34, 0.36, 0.34, 0.0,
                new Particle.DustTransition(MIM_CORE, MIM_DARK, 1.5f), true);
        mimEye(world, at, 1.8f);   // the eye set in the flesh, watching from the wound
    }

    /**
     * Nothing There: the blade going up and coming down, drawn to the size of everything the wielder has
     * been made to swallow.
     *
     * <p><b>This is the uncapped half of the weapon, and it is uncapped precisely because it is inert.</b>
     * Nothing in here scans, spawns, or damages; it is one arc and one impact. The {@code amount} it is
     * handed is the raw pool, never the clamped release, so the picture goes on growing after
     * {@link #RELEASE_CAP} has stopped the damage — its reach climbing on to {@link #RELEASE_RADIUS_MAX} and
     * its blade drawing ever thicker — which is the entire ask. The cost of that is bounded by
     * {@link #slashPoints} no matter how absurd the number gets.
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

        // Up and back, over the top, down through the front: one vast red wound tearing open, watched by a
        // row of eyes that grows with the cut. -40 degrees to +150.
        int eyes = Math.min(6, 2 + Math.round(bigness * 4));
        gash(world, pivot, new Vector(0, 1, 0), fwd, radius,
                Math.toRadians(190), Math.toRadians(55), false,
                thick, points, eyes, GASH_REVEAL);

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
        fleshyBurst(world, chest, 1.0f + bigness * 1.5f);   // blood, fangs, and a watching eye at the landing

        // The shockwave. Fixed point count, so its cost never tracks the size of the cut.
        double ring = Math.min(radius, SHOCKWAVE_RADIUS_MAX);
        Particle.DustTransition bleed = new Particle.DustTransition(MIM_CORE, MIM_DARK, 2.0f + bigness * 3.0f);
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

        // A red wound torn across the body, in Mimicry's own grammar.
        Vector across = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (across.lengthSquared() < 1.0e-6) across = new Vector(0, 0, 1);
        across.normalize();
        Vector side = across.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
        side.normalize();
        gash(world, body, side, new Vector(0, 1, 0), 1.9, Math.toRadians(150),
                Math.toRadians(20), rng.nextBoolean(), 2.5f, 16, 1, GASH_REVEAL);

        world.spawnParticle(Particle.SWEEP_ATTACK, body, 2, 0.3, 0.2, 0.3, 0.0);
        world.spawnParticle(Particle.DUST, body, 18, 0.3, 0.3, 0.3, 0.0,
                new Particle.DustOptions(BLOOD_C, 1.5f), true);
    }

    /** The finish: a crisp clean sweep at the nape and a spray of red — a slash, nothing living. */
    private void executeFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location neck = victim.getLocation().add(0, victim.getHeight() * 0.85, 0);

        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f);
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.6f + (rng.nextFloat() - 0.5f) * 0.1f);

        // One short, fast cut straight across the nape — the blow it never saw.
        Vector side = victim.getLocation().getDirection().setY(0);
        if (side.lengthSquared() < 1.0e-6) side = new Vector(0, 0, 1);
        side = side.crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
        side.normalize();
        gash(world, neck, side, new Vector(0, 1, 0), 1.3, Math.toRadians(120), 0.0,
                false, 2.1f, 12, 0, GASH_REVEAL);

        world.spawnParticle(Particle.CRIT, neck, 12, 0.2, 0.2, 0.2, 0.3);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, neck, 24, 0.25, 0.2, 0.25, 0.0,
                new Particle.DustTransition(MIM_CORE, MIM_DARK, 1.4f), true);
        mimEye(world, neck, 1.6f);   // one last eye, watching from the nape
    }

    // ---- lore --------------------------------------------------------------------------

    /** Bone-white — the shape the thing wears. The UI text stays light; only the VFX are red (Nyrrine: vfx only). */
    private static final TextColor BONE = TextColor.color(0xD6D2C8);
    /** Dried blood — what is underneath it. */
    private static final TextColor RUST = TextColor.color(0x8E2B27);

    // Mimicry cuts in deep red end to end — no bone, no pale, nothing light anywhere in a slash (Nyrrine
    // §1.2: "mimicry red and not light at all all throughout"). The lone exception is the eye set in the
    // flesh, which watches in white with a black pupil (§1.5).
    /** The hot leading edge / core of a fresh cut. */
    private static final Color MIM_CORE   = Color.fromRGB(0xE0, 0x14, 0x22);
    /** The body of the wound — Mimicry red. */
    private static final Color BLOOD_C    = Color.fromRGB(0x9A, 0x16, 0x20);
    /** The dark it bleeds down to as it fades. */
    private static final Color MIM_DARK   = Color.fromRGB(0x3E, 0x06, 0x0A);
    /** The watching eye: white sclera... */
    private static final Color EYE_SCLERA = Color.fromRGB(0xF2, 0xEC, 0xE4);
    /** ...and a black pupil at its centre. */
    private static final Color EYE_PUPIL  = Color.fromRGB(0x0A, 0x05, 0x06);

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
                    new EgoLore.Ability("[Passive] While Held",
                            "Your max health doubles to 40, two",
                            "heart-rows that fall away the moment",
                            "you sheathe, and your melee reach",
                            "extends a little. Landing a hit heals",
                            "you for 25% of the damage dealt, once",
                            "every 5s; the finisher heals more."),
                    new EgoLore.Ability("[Passive] Hello.",
                            "Damage dealt TO you pools in the",
                            "blade, and the pool itself has no",
                            "ceiling. The gauge reads full at",
                            "120, the point past which it can",
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
                            "strike hard, then a 45 second cooldown.")
            ));
}
