package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
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
 * Faint Aroma — "Alriune" (Lobotomy Corp E.G.O Equipment, WAW).
 *
 * <p>A crossbow that smells of a forest that isn't there. The extraction took the Abnormality but left
 * the scent behind, so the thing hums with pollen and cold water light: lavender and cyan, all bloom and
 * no blade. Its arrowhead is <b>dull</b> — it does not pierce so much as <em>open</em>. Vivid flowers
 * sprout wherever it lands, on stone, on bark, on people. It is a beautiful weapon right up until the
 * moment it isn't.
 *
 * <ul>
 *   <li><b>[Passive] Unwithering Flower</b> — every strike mends the wielder
 *       ({@link #UNWITHERING_HEAL_PER_STRIKE}) and grows one petal. Each petal is
 *       +{@value #PETAL_DAMAGE_PER_STACK_PCT}% weapon damage, capped at {@value #PETAL_CAP} petals
 *       (= +{@value #PETAL_CAP}% at full bloom). At {@value #PETAL_CAP} petals, [Magnificent End]
 *       unlocks. The petal charge reads on the action bar.</li>
 *   <li><b>[Left Click] Blossoming Fragrance</b> — a lavender arrow every
 *       {@value #BLOSSOM_CADENCE_MS}ms, loosed the instant you click (the crossbow never draws or
 *       charges). A raised shield facing the shot swallows it whole. Every arrow that lands gathers the
 *       aroma charge; on the {@value #AROMA_CHARGE_STRIKES}th the scent catches and saps whoever took
 *       it ({@value #FAINT_AROMA_WEAKNESS_SECONDS}s of Weakness), then the charge empties and gathers
 *       again. It reads on the action bar beside the petals.</li>
 *   <li><b>[Right-Click] Full Bloom</b> — {@value #FULL_BLOOM_ARROWS} heavier, faster arrows in rapid
 *       succession. Each one triggers the same passives as a Blossoming arrow. Rather than being stopped
 *       by a raised guard, Full Bloom <em>breaks</em> it: a blocking victim's shield is knocked out of
 *       action for {@value #SHIELD_DISABLE_TICKS} ticks.</li>
 *   <li><b>[Shift+Right-Click] Magnificent End</b> — refused below {@value #PETAL_CAP} petals. At full
 *       bloom, a single heavy bolt that opens on contact into a flower the size of a house, and spends
 *       every petal doing it.</li>
 * </ul>
 *
 * <h2>The cadence, and why the first shot is not eaten</h2>
 * Left-click is the trigger, and the first click of a burst fires <b>synchronously, on the swing</b>:
 * {@link #onSwing} calls {@link #fireBlossom} directly, the {@value #BLOSSOM_CADENCE_MS}ms gate is checked
 * <em>before</em> the arrow is loosed rather than after, and {@link Bloom#lastBlossom} starts at {@code 0}
 * so that gate is wide open on the first click of a life. There is no pre-roll, no draw, no charge-up and
 * no scheduled delay anywhere on the path from click to arrow. The cadence is a delay <b>between</b>
 * shots, never before the first one.
 *
 * <p>What used to <em>read</em> as a delay before the first shot was {@link #onHit}: clicking a body in
 * arm's reach landed a vanilla melee blow as well as loosing the arrow, and the blow — arriving first —
 * stamped ten ticks of hurt-immunity on the target that swallowed the arrow's damage outright. The first
 * shot at a close target therefore did nothing at all and the weapon only appeared to "start" from the
 * second. {@link #onHit} cancels that blow, and a cancelled damage event never stamps the i-frames, so
 * the arrow lands. Nothing is given up: this is a {@code ranged} model with no melee damage of its own.
 *
 * <p><b>Non-grief by construction.</b> [Magnificent End] "explodes upon contact", but nothing here ever
 * touches the world: the blast is a hand-rolled radius sweep ({@link Bolt#detonate}), never
 * {@code World.createExplosion}. No blocks are broken, no fire is lit, and the flowers it sprouts are
 * {@link Particle#ITEM} particles rather than placed blocks. Every point of damage goes through
 * {@code victim.damage(...)} so other plugins can cancel it.
 *
 * <p><b>Lifecycle.</b> This weapon spawns <b>no entities at all</b> — each arrow is a lightweight
 * {@link Bolt} runnable drawing particles along its own flight, the same trick Laetitia uses, and
 * [Magnificent End]'s bloom is a {@link BloomShow} runnable drawing particles where it went off. There is
 * therefore nothing that can orphan into the world and nothing to sweep; the only cleanup is cancelling
 * the in-flight bolts ({@link #inFlight}) and the open blooms ({@link #shows}) on disable. Per-player
 * state is one UUID-keyed {@link Bloom} map, dropped on quit. {@link #onTick} bails the moment the
 * crossbow leaves the main hand.
 */
public final class FaintAromaWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their petals, cadence gates and cooldowns. The only per-player state kept. */
    private final Map<UUID, Bloom> blooms = new HashMap<>();

    /** Every arrow currently in flight, so {@link #onDisable} can cancel the lot. No entities involved. */
    private final Set<Bolt> inFlight = new HashSet<>();

    /** Every [Magnificent End] bloom currently open, so {@link #onDisable} can cancel those too. */
    private final Set<BloomShow> shows = new HashSet<>();

    /**
     * Victims currently taking one of this weapon's own arrow or blast hits — the re-entrancy fence, and
     * the reason {@link #onHit} does not cancel the weapon into uselessness.
     *
     * <p>{@link #onHit} cancels the wielder's melee blow. But a {@link Bolt}'s
     * {@code victim.damage(amount, caster)} raises an {@code EntityDamageByEntityEvent} whose damager is
     * that same caster, still holding this same crossbow — and {@code WeaponManager} dispatches that event
     * back into {@link #onHit} with no cause filter of any kind. Without this fence every arrow cancels
     * itself and the weapon deals <b>zero damage</b>, silently, while its flowers, petals, heal and
     * Weakness all keep working: the failure is invisible to everything except a health bar.
     *
     * <p>Every weapon on this roster that both hooks {@code onHit} and calls {@code damage()} carries the
     * same fence — Harmony's {@code ticking}, Wrist Cutter's, Justitia's {@code comboing}, Mimicry's
     * {@code striking}. Entries live only for the duration of a single damage call, inside a try/finally,
     * so this set is empty between hits and cannot retain a dead mob's UUID.
     */
    private final Set<UUID> ticking = new HashSet<>();

    // ---- [Passive] Unwithering Flower ----------------------------------------------

    /**
     * The mend laid on the wielder by every strike of [Passive] Unwithering Flower.
     *
     * <p>"Half a bar of HP" reads as half a <b>heart</b> — one point — not half the health bar. On a
     * passive that fires on every single arrow the other reading would full-heal the wielder several
     * times over inside one Full Bloom burst; this is a light, constant trickle instead, which is what
     * an unwithering flower should feel like.
     */
    private static final double UNWITHERING_HEAL_PER_STRIKE = 1.0;

    /** Petals cap here; this is also the price of admission for [Magnificent End]. */
    private static final int PETAL_CAP = 30;

    /** Each petal is worth this much weapon damage: +1% per petal, so +30% at full bloom. */
    private static final double PETAL_DAMAGE_PER_STACK = 0.01;
    private static final int PETAL_DAMAGE_PER_STACK_PCT = 1; // the same number, for docs and the HUD

    // ---- enchants ------------------------------------------------------------------
    // Quicker Scent (a vanilla enchant — a crossbow holds Quick Charge at an anvil, so this needs no
    // catalogue entry): a faster-blooming bird cuts its Full Bloom rest by 15% per level, up to 45% at
    // Quick Charge III. Cadence only — it never touches a petal, a heal, or an arrow's blow.
    private static final double QUICKER_SCENT_PER_LEVEL = 0.15;
    private static final int    QUICKER_SCENT_CAP       = 3;

    // Rampant Bloom (a custom enchant — id "rampant_bloom"): a wider bloom raises the petal ceiling by
    // RAMPANT_BLOOM_PER_LEVEL per level, up to +9 at level 3. Gated hard by construction: petals grow one per
    // strike, so a higher ceiling only means a slower, LATER full bloom — [Magnificent End] unlocks at the
    // raised cap, never sooner. It buys a deeper bloom, not a faster one.
    private static final int RAMPANT_BLOOM_PER_LEVEL = 3;
    private static final int RAMPANT_BLOOM_CAP       = 3;

    /** The Full Bloom cooldown for the bird held right now: the base rest cut by its Quicker Scent bonus. */
    private long fullBloomCooldownMs(Player player) {
        return fullBloomCooldownMs(player.getInventory().getItemInMainHand());
    }

    /** As above, for a specific stack — used by the Duet path, where Faint Aroma is carried in the OFF hand. */
    private long fullBloomCooldownMs(ItemStack faintAroma) {
        int qc = Math.min(QUICKER_SCENT_CAP,
                faintAroma == null ? 0 : faintAroma.getEnchantmentLevel(Enchantment.QUICK_CHARGE));
        return (long) (FULL_BLOOM_COOLDOWN_MS * (1.0 - QUICKER_SCENT_PER_LEVEL * qc));
    }

    /** The petal cap for the bird held right now: {@link #PETAL_CAP} plus Rampant Bloom's per-level bonus. */
    private int petalCap(Player player) {
        return petalCap(player.getInventory().getItemInMainHand());
    }

    /** As above, for a specific stack — used by the Duet path, where Faint Aroma is carried in the OFF hand. */
    private int petalCap(ItemStack item) {
        int lvl = Math.min(RAMPANT_BLOOM_CAP, EgoEnchants.level(item, "rampant_bloom"));
        return PETAL_CAP + lvl * RAMPANT_BLOOM_PER_LEVEL;
    }

    // ---- faint aroma (the charge meter + its Weakness payload) ---------------------

    /**
     * Arrow strikes needed to fill the aroma charge. Every ninth strike the scent finally catches and
     * lays its Weakness — the meter then empties and starts gathering again. <b>The ninth arrow that
     * lands is the weakness shot</b>, which is the confirmed-correct reading: the counter increments
     * first and triggers on reaching nine, so the arrow that carries the scent is the ninth itself and
     * not the tenth. The bar therefore reads 8/9 when the next arrow will catch.
     *
     * <p>The count is the <b>wielder's</b>, not any one victim's, and it runs across every opponent they
     * hit: this is a charge meter like Logging's or Regret's, not a per-target debuff timer. Blossoming
     * Fragrance and Full Bloom arrows both feed it (the spec has Full Bloom triggering "the same passives
     * as blossoming fragrance"); Magnificent End does not, since it spends the passive rather than feeds
     * it. Keeping the meter wielder-keyed is also what keeps this weapon free of victim-keyed state —
     * there is no per-mob map here to leak.
     */
    private static final int AROMA_CHARGE_STRIKES = 9;

    /** The scent clings for 5 seconds. */
    private static final int FAINT_AROMA_WEAKNESS_TICKS = 100;
    private static final int FAINT_AROMA_WEAKNESS_SECONDS = 5; // the same number, for docs
    private static final int FAINT_AROMA_WEAKNESS_AMP = 0;     // Weakness I — a sapping, not a crippling

    // Cloying Scent (a custom enchant — id "cloying_scent"): the scent clings longer. +2s of Weakness per
    // level, up to +6s at level 3 (an 11s sap). Duration only — never the amplifier, an arrow's blow, or a petal.
    private static final int CLOYING_SCENT_PER_LEVEL_TICKS = 40;
    private static final int CLOYING_SCENT_CAP             = 3;

    // ---- [Left Click] Blossoming Fragrance -----------------------------------------

    /**
     * The cadence: one lavender arrow every ~0.9 seconds (PLACEHOLDER — Nyrrine's live tune; was 1.5s,
     * tightened to cut the downtime between clicks).
     *
     * <p>Checked <b>before</b> the shot, against a {@link Bloom#lastBlossom} that starts at {@code 0} — so
     * this is a floor on the gap <em>between</em> arrows and never a wait in front of the first one. See
     * the class docs.
     */
    private static final long BLOSSOM_CADENCE_MS = 900L;

    /** Per arrow, before petals. Modest on purpose — at this cadence this is a steady poke, not burst. */
    private static final double BLOSSOM_DAMAGE = 6.0;

    /** Blocks/tick. Fast enough to read as instant in a fight, slow enough that Full Bloom can beat it. */
    private static final double BLOSSOM_SPEED = 2.2;

    /** Ticks of flight before the arrow gives up — about 35 blocks of reach. */
    private static final int BLOSSOM_LIFE_TICKS = 16;

    /**
     * Each ARM_SWING arms this much sustained fire, and {@link #onTick} drives the cadence while it lasts.
     * Deliberately <b>shorter</b> than {@link #BLOSSOM_CADENCE_MS}: a single tap therefore looses exactly
     * one arrow (the window lapses long before the next is due), while a held left-click — whose swings
     * keep re-arming it — sustains the 1.5s stream. The window exists because holding left-click in air
     * does not reliably repeat ARM_SWING, so onSwing alone can never sustain fire. It is <b>not</b> a gate
     * on the first shot: onSwing fires the arrow itself and only arms this for the ones after it.
     */
    private static final long HOLD_WINDOW_MS = 800L;

    // ---- [Right-Click] Full Bloom ---------------------------------------------------

    /** "Three powerful lavender arrows in rapid succession." */
    private static final int FULL_BLOOM_ARROWS = 3;

    /** Ticks between the arrows of a burst — ~0.15s apart, i.e. rapid succession. */
    private static final long FULL_BLOOM_GAP_TICKS = 3L;

    /**
     * What one Full Bloom arrow carries. Was 7.0, and only ever delivered once: the volley's other two
     * arrows died against the first one's hurt-immunity, so the ability quietly dealt a third of itself
     * and was balanced by an accident. Now that all three land, the number has to be honest — a fixed bug
     * must never be left doing a balance mechanism's job, or the next person to "fix" it triples her
     * signature and nobody knows why.
     *
     * <p>At 5.0 the volley is {@code 3 x 5.0 x 1.30 = 19.5} at full petals — two and a half Blossoming
     * arrows in one press, on an eight-second rest, and no single arrow anywhere near the netherite band's
     * ceiling. Strong, not oppressive.
     *
     * <p><b>One tension worth knowing about:</b> the spec calls these arrows "higher in impact" than
     * Blossoming's, and at 5.0 a single one (6.5 at full petals) lands under a single Blossoming arrow
     * (7.8). Three of them at Blossoming's weight would be 27.3 in a third of a second, which one-shots an
     * unarmoured player — the volley's weight is the volley. The impact the spec asks for is real and it
     * lives in the speed and in the broken guard; what a Full Bloom arrow buys over a Blossoming one is
     * that it arrives faster, it cannot be blocked, and it brings two friends.
     */
    private static final double FULL_BLOOM_DAMAGE = 5.0;

    /** "…and velocity" — visibly faster than {@link #BLOSSOM_SPEED}. */
    private static final double FULL_BLOOM_SPEED = 3.2;

    /** About 42 blocks of reach. */
    private static final int FULL_BLOOM_LIFE_TICKS = 13;

    /** The burst is the payoff, so it has to rest. */
    private static final long FULL_BLOOM_COOLDOWN_MS = 8_000L;

    /** How long a guard broken by Full Bloom / Magnificent End stays down — 4s, the vanilla axe-disable feel. */
    private static final int SHIELD_DISABLE_TICKS = 80;

    // ---- [Shift+Right-Click] Magnificent End ---------------------------------------

    /**
     * The bolt's direct hit. <b>FLAGGED:</b> this is the one number on the weapon that leaves the
     * balance band on purpose. A single Sharpness-V netherite hit is ~11; this is 24.0 raw, and since
     * [Magnificent End] can only ever fire at exactly {@value #PETAL_CAP} petals it always lands at
     * x1.30 — <b>31.2 on the body it strikes</b>. That buys: ~30 strikes of build-up (about 45s of
     * Blossoming Fragrance), the total loss of every petal, and the +30% damage that came with them.
     * It is a finisher that costs a whole fight's worth of accrual, so it is allowed to hurt.
     */
    private static final double MAGNIFICENT_END_IMPACT = 24.0;

    /**
     * The bloom around the impact, at the epicentre, falling off linearly to zero at
     * {@link #MAGNIFICENT_END_RADIUS}. Splash for bystanders only — see {@link Bolt#detonate} for why
     * the directly-struck body is deliberately exempt.
     */
    private static final double MAGNIFICENT_END_BLAST = 14.0;

    /** How wide the flower opens. */
    private static final double MAGNIFICENT_END_RADIUS = 4.5;

    /** Heavy and deliberate — it does not need to be the fastest thing it fires. */
    private static final double MAGNIFICENT_END_SPEED = 2.6;

    /** About 47 blocks of reach. */
    private static final int MAGNIFICENT_END_LIFE_TICKS = 18;

    // ---- [Shift+Right-Click] Magnificent End: the bloom itself -----------------------
    // This is the payoff for banking thirty petals and it spends every one of them, so it is allowed to
    // be a show. It is NOT allowed to be a show by brute force: the whole thing is built out of lifetime
    // and shape rather than raw counts, and every layer of it has a fixed per-tick ceiling that does not
    // grow with the blast radius. See BloomShow for the arithmetic.

    /** How long the bloom stays open: 24 ticks, 1.2s. The old version was one frame of 263 particles. */
    private static final int MAG_SHOW_TICKS = 24;

    /**
     * Points on the sweeping shockwave ring, per tick. A <b>hard</b> ceiling rather than a density: the
     * ring is drawn with this many motes whether its radius is 0.6 blocks or the full 4.5, so the cost of
     * the widest frame is identical to the cost of the narrowest.
     */
    private static final int MAG_RING_POINTS = 24;

    /** The petals that open out of the bud, and the motes drawn along each one: 30 a tick, capped. */
    private static final int MAG_PETALS = 6;
    private static final int MAG_PETAL_MOTES = 5;

    /** How high the bud stands before its petals lie over. */
    private static final double MAG_PETAL_HEIGHT = 2.4;

    /** The petals are done opening this far into the show; after it, only the ring and the falling flowers. */
    private static final double MAG_PETAL_PHASE = 0.5;

    /** Fragrance haze at the epicentre — one packet a tick, thinning from this to 2 as the bloom settles. */
    private static final int MAG_HAZE_MAX = 10;

    // ---- flight ---------------------------------------------------------------------

    /** Sub-step per movement slice — the anti-tunnel granularity, well under a block. */
    private static final double BOLT_SUBSTEP = 0.4;

    /** How near an arrow's line a living body must be to be struck. The dull head is forgiving. */
    private static final double HIT_RADIUS = 1.0;

    /** Shot heading vs. a blocker's look: below this, they count as roughly facing the incoming arrow. */
    private static final double BLOCK_FACING_DOT = 0.2;

    // Gentle aim assist (PLACEHOLDER magnitudes) — soft help, never a lock-on. At launch a bolt acquires the
    // living body nearest the wielder's crosshair inside a modest cone + range; each tick it turns toward that
    // body by at most a capped angle, so it curves in rather than snapping. No body in the cone -> it flies
    // dead straight, exactly as before.
    private static final double AIM_ASSIST_CONE_DEG = 18.0; // half-angle from the crosshair a body may sit in to be acquired
    private static final double AIM_ASSIST_RANGE    = 24.0; // how far out a body can be acquired, in blocks
    private static final double AIM_ASSIST_TURN_DEG = 9.0;  // max heading turn per tick — the softness of the curve

    /** Beyond ~32 blocks the client culls unforced particles, and these arrows outrange that easily. */
    private static final boolean FORCE_PARTICLES = true;

    // ---- palette: lavender and cyan, the colours of a forest that isn't there --------

    private static final TextColor LAVENDER = TextColor.color(0xB67ACE); // primary — name, ability headers
    private static final TextColor CYAN     = TextColor.color(0x6CF9F7); // secondary — the Abnormality line
    private static final TextColor FAINT    = TextColor.color(0x8A7A94); // muted readouts
    private static final TextColor WILT     = TextColor.color(0x6E5F76); // a refused Magnificent End
    private static final TextColor COUNT    = NamedTextColor.GRAY;       // counts, matching EgoHud

    private static final Color C_LAVENDER = Color.fromRGB(0xB6, 0x7A, 0xCE); // the arrow itself
    private static final Color C_CYAN     = Color.fromRGB(0x6C, 0xF9, 0xF7); // cold water light
    private static final Color C_PETAL    = Color.fromRGB(0xE7, 0xCB, 0xF5); // pale petal highlight
    private static final Color C_STEM     = Color.fromRGB(0x7A, 0x4E, 0x8E); // deeper violet, the shaft's core

    private static final Particle.DustOptions LAVENDER_DUST = new Particle.DustOptions(C_LAVENDER, 0.9f);
    private static final Particle.DustOptions CYAN_DUST     = new Particle.DustOptions(C_CYAN, 0.7f);
    private static final Particle.DustOptions PETAL_DUST    = new Particle.DustOptions(C_PETAL, 0.8f);
    private static final Particle.DustOptions STEM_DUST     = new Particle.DustOptions(C_STEM, 1.0f);

    /**
     * The signature: dust that starts lavender and fades to cyan as it hangs — the fragrance turning
     * colour in the air. DUST_COLOR_TRANSITION takes a {@link Particle.DustTransition}, never a plain
     * DustOptions; handing it the wrong data class is a runtime crash, not a compile error.
     */
    private static final Particle.DustTransition BLOOM_FADE =
            new Particle.DustTransition(C_LAVENDER, C_CYAN, 1.1f);

    // The bloom's own palette. Same colours, drawn large: size is the one way to make a show read bigger
    // that costs nothing at all — it is a float in a packet that was being sent anyway. Client clamps
    // DustOptions size at 4.0, so these sit well inside it.
    private static final Particle.DustOptions MAG_PETAL_DUST = new Particle.DustOptions(C_PETAL, 1.8f);
    private static final Particle.DustOptions MAG_CYAN_DUST  = new Particle.DustOptions(C_CYAN, 1.6f);
    private static final Particle.DustTransition MAG_FADE =
            new Particle.DustTransition(C_LAVENDER, C_CYAN, 2.2f);

    /**
     * Vivid flowers sprout where the dull arrowhead lands. These are {@link Particle#ITEM} data — thrown
     * as particles, <b>never placed as blocks</b>: the weapon makes no permanent edit to the world.
     */
    private static final ItemStack[] FLOWERS = {
            new ItemStack(Material.ALLIUM),      // lavender
            new ItemStack(Material.CORNFLOWER),  // cyan
            new ItemStack(Material.PINK_TULIP),  // pale petal
            new ItemStack(Material.LILAC),       // a fuller bloom
    };

    public FaintAromaWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "faint_aroma");
    }

    @Override
    public String id() {
        return "faint_aroma";
    }

    /** Per-wielder bloom state: petals grown, plus the gates that pace the three inputs. */
    private static final class Bloom {
        int  petals      = 0;   // [Passive] Unwithering Flower — 0..PETAL_CAP
        int  aroma       = 0;   // the faint-aroma charge — 0..AROMA_CHARGE_STRIKES, empties on trigger
        long lastBlossom = 0L;  // epoch-ms of the last Blossoming Fragrance arrow (the 1.5s cadence gate)
        long holdUntil   = 0L;  // swing-refreshed auto-fire window; onTick looses arrows while it is live
        long fullBloomCd = 0L;  // epoch-ms when Full Bloom is ready again
    }

    /**
     * The three arrows Faint Aroma looses. One {@link Bolt} serves all three — the arrowhead is always
     * the same dull thing, only the weight, the speed and the bloom differ.
     */
    private enum Shot {
        /** [Left Click] Blossoming Fragrance — the light, constant shot. A raised shield swallows it. */
        BLOSSOM(BLOSSOM_DAMAGE, BLOSSOM_SPEED, BLOSSOM_LIFE_TICKS),
        /** [Right-Click] Full Bloom — heavier and faster; breaks a raised guard instead of being stopped. */
        FULL_BLOOM(FULL_BLOOM_DAMAGE, FULL_BLOOM_SPEED, FULL_BLOOM_LIFE_TICKS),
        /** [Shift+Right-Click] Magnificent End — the finisher. Opens on contact. */
        MAGNIFICENT(MAGNIFICENT_END_IMPACT, MAGNIFICENT_END_SPEED, MAGNIFICENT_END_LIFE_TICKS);

        private final double damage;    // before the petal multiplier
        private final double speed;     // blocks/tick
        private final int    lifeTicks; // ticks before it gives up

        Shot(double damage, double speed, int lifeTicks) {
            this.damage = damage;
            this.speed = speed;
            this.lifeTicks = lifeTicks;
        }
    }

    // ---- damage ---------------------------------------------------------------------

    /**
     * Land one of this weapon's own hits on a body: fenced against {@code onHit} re-entry, and routed through
     * {@code damage()} so protection plugins can still cancel it.
     *
     * <p><b>The fence must be the manager's, not just this weapon's.</b> The damage is wrapped in
     * {@code WeaponManager.dealing(...)}, which marks the blow as the wielder's own so the dispatch never
     * hands it to the MAIN-hand weapon's {@code onHit}. Solo, the main hand is this weapon, so the local
     * {@link #ticking} set would have sufficed. But in a Duet the main hand is <b>Solitude</b>, whose onHit
     * cancels every hit that is not one of its own bullets — which silently ate every Faint Aroma arrow (the
     * v1 damage bug). Fencing through the manager makes the arrow land whichever weapon is in the main hand.
     * The {@link #ticking} set is kept too: it still guards a re-entrant {@code deal()} on the same victim.
     *
     * <p>Deliberately does <b>not</b> clear the victim's hurt-immunity. Blossoming Fragrance is spaced well
     * apart and can never collide with its own i-frames, and [Magnificent End]'s impact and blast are held
     * apart on purpose (see {@link Bolt#detonate}) rather than being made to stack.
     */
    private void deal(LivingEntity victim, double amount, Player caster) {
        if (victim.isDead() || !victim.isValid()) return;
        UUID vid = victim.getUniqueId();
        if (ticking.contains(vid)) return; // already inside this victim's damage call — never re-enter

        ticking.add(vid);
        try {
            plugin.weapons().dealing(caster.getUniqueId(), () -> victim.damage(amount, caster));
        } finally {
            ticking.remove(vid);
        }
    }

    // ---- [Left Click] Blossoming Fragrance ------------------------------------------

    /**
     * The arrowhead is dull and it is not for hitting people with. Left-click is the trigger, so loosing
     * an arrow at a body within arm's reach would otherwise land a vanilla blow too — and the blow,
     * arriving first, stamps hurt-immunity that swallows the arrow. That is the whole of the "the first
     * shot gets eaten" symptom, and cancelling the blow is the whole of the fix: a cancelled damage event
     * never stamps the i-frames in the first place. Cancelling costs nothing — Faint Aroma is a
     * {@code ranged} model with no melee damage of its own.
     *
     * <p>Our own arrows and blasts re-enter this method through the manager's dispatch and are dropped by
     * the {@link #ticking} fence; without that check this hook would cancel the weapon's entire offence.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (ticking.contains(victim.getUniqueId())) return; // our own arrow or blast — not a melee blow
        event.setCancelled(true);
    }

    /**
     * Left-click looses a Blossoming Fragrance arrow, subject to the {@value #BLOSSOM_CADENCE_MS}ms
     * cadence, and arms the hold window so {@link #onTick} can sustain the stream (see
     * {@link #HOLD_WINDOW_MS} for why the window is needed at all).
     *
     * <p>The arrow leaves from inside this method, on the swing, in the same tick as the click. Nothing
     * is scheduled and nothing is waited on.
     */
    @Override
    public void onSwing(Player player) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        long now = System.currentTimeMillis();
        bloom.holdUntil = now + HOLD_WINDOW_MS; // armed on EVERY swing, even a cadence-suppressed one
        fireBlossom(player, bloom, now);
    }

    /**
     * Loose exactly one Blossoming Fragrance arrow IF the 1.5s cadence gate allows. Shared by the
     * responsive onSwing click and the onTick hold continuation. True if an arrow actually left.
     */
    private boolean fireBlossom(Player player, Bloom bloom, long now) {
        if (now - bloom.lastBlossom < BLOSSOM_CADENCE_MS) return false;
        bloom.lastBlossom = now;

        launch(player, bloom, Shot.BLOSSOM);
        EgoDurability.wearMainHand(player); // a ranged shot never goes through a vanilla swing — wear it here
        blossomSfx(player);
        return true;
    }

    // ---- right-click routing --------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        if (sneaking) magnificentEnd(player, bloom);
        else fullBloom(player, bloom);
    }

    // ---- [Right-Click] Full Bloom ---------------------------------------------------

    /**
     * Three heavier, faster arrows a few ticks apart. Each is an ordinary {@link Shot#FULL_BLOOM} bolt,
     * so each one feeds [Passive] Unwithering Flower exactly like a Blossoming arrow does — the arrows
     * later in the burst therefore ride the petals the earlier ones just grew.
     */
    private void fullBloom(Player player, Bloom bloom) {
        long now = System.currentTimeMillis();
        if (now < bloom.fullBloomCd) {
            renderBar(player, bloom); // the composed line already shows the Full Bloom rest counting down
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.7f);
            return;
        }
        bloom.fullBloomCd = now + fullBloomCooldownMs(player);

        for (int i = 0; i < FULL_BLOOM_ARROWS; i++) {
            scheduleBloomArrow(player, i * FULL_BLOOM_GAP_TICKS);
        }
        EgoDurability.wearMainHand(player); // one point for the burst, not one per arrow
    }

    /**
     * Queue one arrow of the burst. The first goes now; the rest a few ticks out, each re-checking that
     * the crossbow is still in hand so a burst can't keep firing out of an empty grip.
     */
    private void scheduleBloomArrow(Player player, long delayTicks) {
        if (delayTicks <= 0L) {
            looseBloomArrow(player);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && matches(player.getInventory().getItemInMainHand())) {
                looseBloomArrow(player);
            }
        }, delayTicks);
    }

    /** One Full Bloom arrow, aimed down whatever line the wielder holds at the moment it leaves. */
    private void looseBloomArrow(Player player) {
        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        launch(player, bloom, Shot.FULL_BLOOM);

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.2f);
        world.playSound(eye, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
    }

    // ---- [Shift+Right-Click] Magnificent End ---------------------------------------

    /**
     * The finisher. Refused below {@value #PETAL_CAP} petals with a wilting cue; at full bloom it looses
     * one heavy bolt and spends every petal in the same breath.
     *
     * <p>The multiplier is snapshotted into the bolt at launch precisely because the petals are gone the
     * instant it leaves — the shot is paid for up front and carries what it bought.
     */
    private void magnificentEnd(Player player, Bloom bloom) {
        int cap = petalCap(player);
        if (bloom.petals < cap) {
            player.sendActionBar(EgoHud.status(
                    "Magnificent End — " + bloom.petals + "/" + cap + " petals", WILT));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.45f, 1.4f);
            return;
        }

        launch(player, bloom, Shot.MAGNIFICENT);
        bloom.petals = 0; // "Using this skill resets all stacks of your passive [Unwithering Flower]."
        EgoDurability.wearMainHand(player);

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.7f);
        world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.3f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 14, 0.3, 0.3, 0.3, 0, BLOOM_FADE, FORCE_PARTICLES);
        player.sendActionBar(EgoHud.status("Magnificent End", CYAN));
    }

    // ---- Duet (dual-wield: Solitude main hand + Faint Aroma off hand) -----------------
    // Faint Aroma carried in the OFF hand while Solitude is wielded. Only Solitude ticks, so it drives the
    // merged HUD and calls these hooks; each stays fully functional solo. The integration is woven into
    // Solitude's FIRE, not its clicks: every 2nd aimed Bang auto-follows a REAL blossom, Solitude's reload
    // becomes Faint-Aroma fire, its aimed Bangs feed the aroma charge and ride the petal bonus, and a loaded
    // right-click at full bloom detonates Magnificent End. Solitude fetches this instance from the registry.
    // Real damage on every shot comes from deal() routing through the manager's dealing() fence — without it
    // Solitude's onHit would cancel every Faint Aroma arrow (the v1 damage bug).

    /** Petals a Duet blossom grows when it LANDS on a body — the only way petals charge in a Duet, so full
     *  bloom is a reward for landing shots, not a free on-fire charge. Grown via {@link #feedUnwitheringFlower}
     *  on the real hit path. PLACEHOLDER for Nyrrine's tune — bump if full bloom comes too slow. */
    private static final int DUET_PETALS_PER_HIT = 1;

    /** Duet: fire one REAL Blossoming arrow from the wielder, ignoring cadence — the every-2nd-Bang follow-up. */
    public void duetAutoBlossom(Player wielder) {
        if (!matches(wielder.getInventory().getItemInOffHand())) return; // stale schedule / partner swapped away
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        bloom.lastBlossom = System.currentTimeMillis(); // share one cadence clock with the empty/reload stream
        launch(wielder, bloom, Shot.BLOSSOM);           // petals grow only if this LANDS (feedUnwitheringFlower)
        blossomSfx(wielder);
        wearOffHand(wielder);
    }

    /**
     * Duet: a Blossoming arrow fired from Solitude's left-click while the cylinder is empty or reloading,
     * respecting the normal cadence. Infinite (no ammo), so an empty Solitude never goes dead — it keeps
     * firing through Faint Aroma. Petals charge only on a landed hit. True if one actually left.
     */
    public boolean duetBlossom(Player wielder) {
        if (!matches(wielder.getInventory().getItemInOffHand())) return false;
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        long now = System.currentTimeMillis();
        if (now - bloom.lastBlossom < BLOSSOM_CADENCE_MS) return false;
        bloom.lastBlossom = now;
        launch(wielder, bloom, Shot.BLOSSOM);           // petals grow only if this LANDS (feedUnwitheringFlower)
        blossomSfx(wielder);
        wearOffHand(wielder);
        return true;
    }

    /** Duet: a Solitude aimed Bang that lands feeds this weapon's aroma charge (its ninth catches the Weakness). */
    public void duetFeedAroma(Player wielder, LivingEntity victim) {
        if (victim == null || victim.isDead() || !victim.isValid()) return;
        if (!matches(wielder.getInventory().getItemInOffHand())) return;
        feedAroma(wielder, victim);
        // a light lavender mote at the hit — gunfire stirring the scent
        World world = victim.getWorld();
        world.spawnParticle(Particle.DUST, victim.getLocation().add(0, victim.getHeight() * 0.6, 0),
                3, 0.2, 0.25, 0.2, 0, LAVENDER_DUST, FORCE_PARTICLES);
    }

    /** Duet: the petal damage multiplier the wielder's blooms are worth now — Solitude's aimed Bang rides it too. */
    public double duetPetalMultiplier(Player wielder) {
        Bloom bloom = blooms.get(wielder.getUniqueId());
        return bloom == null ? 1.0 : petalMultiplier(bloom.petals);
    }

    /** Duet: true if petals stand at the (off-hand) cap, so a loaded Solitude RC can detonate Magnificent End. */
    public boolean duetMagnificentReady(Player wielder) {
        Bloom bloom = blooms.get(wielder.getUniqueId());
        return bloom != null && bloom.petals >= petalCap(wielder.getInventory().getItemInOffHand());
    }

    /**
     * Duet: detonate Magnificent End from the wielder via the real path (spends every petal), fired from
     * Solitude's loaded right-click. Returns false — doing nothing — below full bloom, so Solitude shows the
     * "petals not ready" cue.
     */
    public boolean duetMagnificentEnd(Player wielder) {
        ItemStack off = wielder.getInventory().getItemInOffHand();
        if (!matches(off)) return false;
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        if (bloom.petals < petalCap(off)) return false;

        launch(wielder, bloom, Shot.MAGNIFICENT);
        bloom.petals = 0; // the finisher spends the passive, exactly as it does solo
        wearOffHand(wielder);

        World world = wielder.getWorld();
        Location eye = wielder.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.7f);
        world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.3f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 14, 0.3, 0.3, 0.3, 0, BLOOM_FADE, FORCE_PARTICLES);
        return true;
    }

    /**
     * Duet: fire Faint Aroma's real Full Bloom burst (the 3 heavier arrows) from the OFF hand, when Solitude's
     * right-click is pressed and Magnificent End is not yet charged. Respects Full Bloom's own cooldown and
     * returns false — firing nothing — while it is still resting, so Solitude can fall through to Stories. The
     * arrows deal damage through the same {@link #deal} dealing()-fence every Duet shot uses, so Solitude's
     * onHit never cancels them (the v1 bug); the delayed arrows re-check the OFF hand.
     */
    public boolean duetFullBloom(Player wielder) {
        ItemStack off = wielder.getInventory().getItemInOffHand();
        if (!matches(off)) return false;
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        long now = System.currentTimeMillis();
        if (now < bloom.fullBloomCd) return false; // still resting — caller falls through to Stories
        bloom.fullBloomCd = now + fullBloomCooldownMs(off);
        for (int i = 0; i < FULL_BLOOM_ARROWS; i++) {
            scheduleDuetBloomArrow(wielder, i * FULL_BLOOM_GAP_TICKS);
        }
        wearOffHand(wielder);
        return true;
    }

    /** Like {@link #scheduleBloomArrow} but the delayed shots re-check the OFF hand — Faint Aroma sits there. */
    private void scheduleDuetBloomArrow(Player player, long delayTicks) {
        if (delayTicks <= 0L) { looseBloomArrow(player); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && matches(player.getInventory().getItemInOffHand())) {
                looseBloomArrow(player);
            }
        }, delayTicks);
    }

    /** Duet: ms until Full Bloom is ready again (0 if ready) — for Solitude's right-click HUD cue. */
    public long duetFullBloomRemaining(Player wielder) {
        Bloom bloom = blooms.get(wielder.getUniqueId());
        return bloom == null ? 0L : Math.max(0L, bloom.fullBloomCd - System.currentTimeMillis());
    }

    /**
     * Duet: Faint Aroma's slice of the merged HUD, for Solitude to fold onto its one always-on line — the
     * petals gauge (turning cyan with a "full bloom" cue at the cap) and the Blossoming Fragrance charge state
     * (ready to loose vs cycling its cadence). Reads the OFF-hand cap (Faint Aroma sits there), so a Rampant
     * Bloom cap shows true. The right-click cue (Magnificent End / Full Bloom / Stories) is composed by
     * Solitude, which alone knows the cylinder state that decides the Stories fallthrough.
     *
     * <p>The aroma/Weakness meter is deliberately not on this line: with chambers, the LC-mode tag, petals,
     * the BF state and the RC cue already sharing one action bar, a fourth gauge overruns it. Aroma still
     * builds and fires; it just is not painted here.
     */
    public Component duetReadout(Player wielder) {
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        int cap = petalCap(wielder.getInventory().getItemInOffHand());
        boolean bloomed = bloom.petals >= cap;

        Component petalLabel = plain("Petals " + bloom.petals + "/" + cap, COUNT)
                .append(plain("  +" + (bloom.petals * PETAL_DAMAGE_PER_STACK_PCT) + "%", bloomed ? CYAN : FAINT));
        if (bloomed) petalLabel = petalLabel.append(plain("  full bloom", CYAN));
        Component petals = EgoHud.gauge(bloomed ? CYAN : LAVENDER, (double) bloom.petals / cap, petalLabel);

        // Blossoming Fragrance: online (ready to loose now) vs still cycling its cadence. The gap is under a
        // second, so a ready/cycling word reads cleaner than a seconds countdown that would always round to 1.
        boolean bfReady = System.currentTimeMillis() - bloom.lastBlossom >= BLOSSOM_CADENCE_MS;
        Component bf = plain(bfReady ? "Blossom ready" : "Blossom cycling", bfReady ? LAVENDER : FAINT);

        return EgoHud.row(petals, bf);
    }

    /** The Blossoming Fragrance shot SFX, shared by the solo click and the Duet blossom paths. */
    private void blossomSfx(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.5f, 1.6f);
        world.playSound(eye, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3f, 1.9f); // a soft floral chime, not a crack
    }

    /** Wear a point off the OFF-hand Faint Aroma and write it back — the Duet blossoms fire from that hand. */
    private void wearOffHand(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        EgoDurability.wear(player, off);
        player.getInventory().setItemInOffHand(off);
    }

    // ---- [Passive] Unwithering Flower ------------------------------------------------

    /**
     * Every strike mends the wielder and grows one more petal (to {@link #PETAL_CAP}).
     *
     * <p>Fed by Blossoming Fragrance and Full Bloom strikes. Magnificent End deliberately does <b>not</b>
     * feed it: that skill is the passive's consumption, not its accrual — it resets the petals at cast, so
     * letting its own impact grow a fresh one (and heal) would be reading the spec against itself.
     */
    private void feedUnwitheringFlower(Player wielder) {
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        healWielder(wielder);
        // Duet: a blossom that lands while Faint Aroma is carried OFF-hand (Solitude in the main hand) grows a
        // tunable number of petals per landed hit, so Magnificent End is a reward for landing shots rather than
        // a free on-fire charge. Solo (Faint Aroma main-hand), the normal one petal per strike. The cap tracks
        // the same hand so the off-hand's Rampant Bloom is honoured during a Duet.
        boolean duet = matches(wielder.getInventory().getItemInOffHand());
        int cap  = duet ? petalCap(wielder.getInventory().getItemInOffHand()) : petalCap(wielder);
        int gain = duet ? DUET_PETALS_PER_HIT : 1;
        for (int i = 0; i < gain && bloom.petals < cap; i++) {
            bloom.petals++;
            if (bloom.petals == cap) fullBloomChime(wielder); // the moment Magnificent End unlocks
        }
    }

    /**
     * Mend the wielder by {@link #UNWITHERING_HEAL_PER_STRIKE}, <b>never past their max health</b> — the
     * cap is read off the live MAX_HEALTH attribute, so any other plugin's max-HP change is respected.
     */
    private void healWielder(Player player) {
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = player.getHealth();
        double healed = Math.min(maxHp, current + UNWITHERING_HEAL_PER_STRIKE);
        if (healed <= current) return; // already brimming — nothing to give

        player.setHealth(healed);
        World world = player.getWorld();
        Location chest = player.getLocation().add(0, 1.1, 0);
        world.spawnParticle(Particle.HEART, chest, 1, 0.25, 0.25, 0.25, 0);
        world.spawnParticle(Particle.DUST, chest, 3, 0.3, 0.35, 0.3, 0, PETAL_DUST);
    }

    /**
     * Gather the scent by one strike and, on every {@value #AROMA_CHARGE_STRIKES}th, let it catch on the
     * body that took the arrow — {@link #applyFaintAroma}, then the meter empties and begins again.
     *
     * <p>The increment happens first, so the trigger lands on the <b>ninth</b> arrow itself: the count
     * reaches nine, the scent catches on the body holding that ninth arrow, and only then does the meter
     * empty. Confirmed correct; do not re-index it.
     *
     * <p>The charge belongs to the wielder and carries across targets, so a skirmisher who spreads their
     * arrows around still earns the scent; it simply lands on whoever happens to take the ninth.
     */
    private void feedAroma(Player wielder, LivingEntity victim) {
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        if (++bloom.aroma < AROMA_CHARGE_STRIKES) return;
        bloom.aroma = 0;
        applyFaintAroma(wielder, victim);
    }

    /** The petal multiplier a shot rides: +1% weapon damage per petal (petals are gain-capped at the bloom cap). */
    private static double petalMultiplier(int petals) {
        return 1.0 + petals * PETAL_DAMAGE_PER_STACK;
    }

    /**
     * Faint aroma: lay {@value #FAINT_AROMA_WEAKNESS_SECONDS} seconds of WEAKNESS on a struck body — the
     * scent of a forest that isn't there, sapping the strength out of whoever breathes it in.
     *
     * <p>Fired by {@link #feedAroma} once the wielder's charge has gathered
     * {@value #AROMA_CHARGE_STRIKES} arrow strikes.
     */
    private void applyFaintAroma(Player wielder, LivingEntity victim) {
        int lvl = Math.min(CLOYING_SCENT_CAP,
                EgoEnchants.level(wielder.getInventory().getItemInMainHand(), "cloying_scent"));
        int ticks = FAINT_AROMA_WEAKNESS_TICKS + lvl * CLOYING_SCENT_PER_LEVEL_TICKS;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks,
                FAINT_AROMA_WEAKNESS_AMP, false, true, true));

        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, 12, 0.35, 0.45, 0.35, 0,
                BLOOM_FADE, FORCE_PARTICLES);
        world.playSound(chest, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.6f, 1.5f);
        world.playSound(chest, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 0.8f);
    }

    // ---- the lavender arrow ---------------------------------------------------------

    /** Loose one arrow of {@code shot}'s kind down the wielder's aim line, carrying the petals it bought. */
    private void launch(Player caster, Bloom bloom, Shot shot) {
        Bolt bolt = new Bolt(caster, shot, petalMultiplier(bloom.petals));
        bolt.runTaskTimer(plugin, 0L, 1L);
        inFlight.add(bolt);
    }

    /**
     * One lavender arrow in flight — a lightweight moving point, not an Arrow entity, so nothing can ever
     * orphan into the world. It advances along its heading each tick in {@value #BOLT_SUBSTEP}-block
     * sub-steps (anti-tunnel), drawing a lavender-to-cyan trail as it goes, and resolves on the first
     * solid block or the first living body it reaches. Always caps its lifetime and cancels itself.
     *
     * <p>The petal multiplier is a <b>snapshot taken at launch</b>, not a lookup at impact: Magnificent
     * End spends every petal the instant it fires, so a shot has to carry what it paid for.
     */
    private final class Bolt extends BukkitRunnable {

        private final UUID casterId;
        private final World world;
        private final Shot shot;
        private final Location point;      // the arrow's live position
        private Vector dir;                // heading (unit) — curved a touch each tick by the aim assist
        private final UUID assistId;       // the body this bolt gently homes toward, or null (fly straight)
        private final double multiplier;   // the petals this shot was bought with
        private int ticks = 0;
        private boolean done = false;

        Bolt(Player caster, Shot shot, double multiplier) {
            this.casterId = caster.getUniqueId();
            this.world = caster.getWorld();
            this.shot = shot;
            this.multiplier = multiplier;
            this.dir = caster.getEyeLocation().getDirection().normalize();
            this.point = caster.getEyeLocation().add(this.dir.clone().multiply(0.7)); // clear of the face
            this.assistId = acquireAssist(caster.getEyeLocation(), this.dir);         // gentle aim assist target, or null
        }

        @Override
        public void run() {
            Player caster = plugin.getServer().getPlayer(casterId);
            if (caster == null || !caster.isOnline()) { stop(); return; }
            if (++ticks > shot.lifeTicks) { fizzle(); stop(); return; }

            homeToward(); // curve the heading a capped amount toward the acquired body, if any

            // ONE broad-phase query per tick, then every sub-step tests against this snapshot. Querying
            // inside the sub-step loop would multiply the cost of the whole flight by the sub-step count.
            // The box is centred on this tick's start point and reaches everywhere the arrow can get to
            // during it: its full step, plus the contact radius, plus a block of slack.
            double reach = shot.speed + HIT_RADIUS + 1.0;
            List<LivingEntity> candidates = new ArrayList<>();
            for (Entity e : world.getNearbyEntities(point, reach, reach, reach)) {
                if (e.getUniqueId().equals(casterId)) continue;                 // never its own wielder
                if (e instanceof LivingEntity le && !le.isDead() && le.isValid()) candidates.add(le);
            }

            double moved = 0.0;
            while (moved < shot.speed) {
                double step = Math.min(BOLT_SUBSTEP, shot.speed - moved);
                point.add(dir.clone().multiply(step));

                // Only real walls stop it. Grass, flowers and fluids aren't solid, so a shot that grazes a
                // tuft flies on through rather than dying on it.
                if (point.getBlock().getType().isSolid()) {
                    land(caster);
                    stop();
                    return;
                }

                LivingEntity hit = nearest(candidates);
                if (hit != null) {
                    strike(caster, hit);
                    stop();
                    return;
                }

                trail();
                moved += step;
            }
        }

        /** The nearest live candidate within contact reach of the arrow's current point, else null. */
        private LivingEntity nearest(List<LivingEntity> candidates) {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity le : candidates) {
                if (le.isDead() || !le.isValid()) continue;
                double d = centre(le).subtract(point.toVector()).lengthSquared();
                if (d <= HIT_RADIUS * HIT_RADIUS && d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        /**
         * At launch, pick the aim-assist target: the living body sitting nearest the wielder's crosshair inside
         * a {@link #AIM_ASSIST_CONE_DEG} cone and {@link #AIM_ASSIST_RANGE} blocks — "nearest" measured by the
         * tightest angle to the aim line (largest dot). No body qualifies -> null, and the bolt homes toward
         * nothing (flies straight). This is a soft assist: it only ever curves the shot toward a body the wielder
         * was already roughly aiming at.
         */
        private UUID acquireAssist(Location eye, Vector aim) {
            double bestDot = Math.cos(Math.toRadians(AIM_ASSIST_CONE_DEG)); // must beat the cone to be acquired
            LivingEntity best = null;
            for (Entity e : world.getNearbyEntities(eye, AIM_ASSIST_RANGE, AIM_ASSIST_RANGE, AIM_ASSIST_RANGE)) {
                if (e.getUniqueId().equals(casterId)) continue;                  // never the wielder
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
                Vector to = centre(le).subtract(eye.toVector());
                double dist = to.length();
                if (dist < 1.0e-3 || dist > AIM_ASSIST_RANGE) continue;
                double dot = aim.dot(to.multiply(1.0 / dist));                   // cos of the angle off the crosshair
                if (dot > bestDot) { bestDot = dot; best = le; }                 // tightest to the crosshair wins
            }
            return best == null ? null : best.getUniqueId();
        }

        /** Turn the heading toward the acquired body by at most {@link #AIM_ASSIST_TURN_DEG} — the gentle curve. */
        private void homeToward() {
            if (assistId == null) return;
            Entity e = plugin.getServer().getEntity(assistId);
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid() || !le.getWorld().equals(world)) {
                return; // the target is gone — stop homing, keep the current heading (fly on straight)
            }
            Vector desired = centre(le).subtract(point.toVector());
            double dist = desired.length();
            if (dist < 1.0e-3) return;
            desired.multiply(1.0 / dist);
            double dot = Math.max(-1.0, Math.min(1.0, dir.dot(desired)));
            double ang = Math.acos(dot);
            double maxTurn = Math.toRadians(AIM_ASSIST_TURN_DEG);
            if (ang <= maxTurn || ang < 1.0e-6) { dir = desired; return; }       // within a step — settle on it
            Vector axis = dir.getCrossProduct(desired);
            if (axis.lengthSquared() < 1.0e-9) return;                            // exactly opposed — don't spin
            dir = dir.rotateAroundAxis(axis.normalize(), maxTurn).normalize();
        }

        /**
         * The arrow reaches a living body. The dull head doesn't pierce — it opens, and flowers come out.
         *
         * <p>Blossoming Fragrance can be stopped by a raised shield. Full Bloom and Magnificent End
         * instead break the guard: a blocking victim's shield is knocked out of action, and the arrow
         * lands anyway.
         */
        private void strike(Player caster, LivingEntity victim) {
            Location at = centre(victim).toLocation(world);

            // "[Blossoming Fragrance] Can be blocked by shields." A raised guard swallows it whole —
            // no damage, no petal, no heal: nothing was struck.
            if (shot == Shot.BLOSSOM && shieldFacing(victim)) {
                shieldClang(victim);
                return;
            }

            // "Any strike from Full Bloom & Magnificent end temporarily disables enemy shields if they
            // are blocking." The guard breaks; the arrow lands regardless.
            if (shot != Shot.BLOSSOM && victim instanceof Player blocker && blocker.isBlocking()) {
                breakGuard(blocker);
            }

            // Full Bloom is three arrows three ticks apart, and a body may only be hurt once every ten —
            // so the second and third used to die against the first one's immunity and the volley landed
            // one arrow's worth. Only this shot needs the window cleared: Blossoming's arrows are a second
            // and a half apart, and Magnificent End's blast already spares the body its bolt struck.
            if (shot == Shot.FULL_BLOOM) victim.setNoDamageTicks(0);

            deal(victim, shot.damage * multiplier, caster); // fenced, and still cancellable by other plugins
            flowerBurst(at);

            if (shot == Shot.MAGNIFICENT) {
                detonate(caster, at, victim); // "explodes upon contact"
                return;                       // the finisher consumes the passive — it never feeds it
            }

            feedUnwitheringFlower(caster);
            feedAroma(caster, victim);
        }

        /** The arrow buries itself in a wall: flowers open out of the stone, and Magnificent End blooms. */
        private void land(Player caster) {
            Location at = point.clone();
            flowerBurst(at);
            if (shot == Shot.MAGNIFICENT) detonate(caster, at, null); // "explodes upon contact"
        }

        /**
         * [Magnificent End]'s bloom.
         *
         * <p><b>NON-GRIEF BY CONSTRUCTION.</b> This is a hand-rolled radius sweep, never
         * {@code World.createExplosion}: it breaks no blocks, lights no fire, and makes no permanent edit
         * of any kind. Every living body inside {@link #MAGNIFICENT_END_RADIUS} takes a linear-falloff
         * share of {@link #MAGNIFICENT_END_BLAST}, scaled by the petals that bought the shot, and every
         * point of it goes through {@code damage()} so it stays cancellable. The show it puts on
         * ({@link #explodeFx}) is particles and sound and nothing else.
         *
         * <p>{@code directHit} — the body the arrow physically struck, or null on a wall hit — is
         * <b>exempt</b>. It has already eaten the far larger {@link #MAGNIFICENT_END_IMPACT}, and vanilla
         * invulnerability frames would swallow a second, smaller hit landed in the same tick anyway. The
         * blast is splash for everyone standing near it, and the exemption is honesty about that rather
         * than pretending the two numbers stack.
         */
        private void detonate(Player caster, Location at, LivingEntity directHit) {
            explodeFx(at);

            double r = MAGNIFICENT_END_RADIUS;
            for (Entity e : world.getNearbyEntities(at, r, r, r)) {
                if (e.getUniqueId().equals(casterId)) continue;                     // never its own wielder
                if (directHit != null && e.getUniqueId().equals(directHit.getUniqueId())) continue;
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

                double dist = centre(le).toLocation(world).distance(at);
                if (dist > r) continue;                                             // the box is square, the bloom isn't
                double dmg = MAGNIFICENT_END_BLAST * (1.0 - dist / r) * multiplier; // linear falloff to zero
                if (dmg <= 0.0) continue;

                if (le instanceof Player blocker && blocker.isBlocking()) breakGuard(blocker);
                deal(le, dmg, caster);
            }
        }

        /** True when {@code victim} is a Player with a shield raised, roughly turned into the arrow. */
        private boolean shieldFacing(LivingEntity victim) {
            if (!(victim instanceof Player p) || !p.isBlocking()) return false;
            Vector look = p.getEyeLocation().getDirection();
            if (look.lengthSquared() < 1.0e-6) return false;
            // The arrow travels along dir; a blocker facing it looks back down that heading, so their look
            // opposes it. Perpendicular still counts as a rough face, hence a threshold just above zero.
            return look.normalize().dot(dir) < BLOCK_FACING_DOT;
        }

        // ---- presentation -----------------------------------------------------------

        /**
         * The arrow's trail: a violet core threaded with lavender, a cyan shimmer every other step, and the
         * odd mote of fragrance turning lavender-to-cyan as it hangs. Forced, because these arrows outrange
         * the client's ~32-block particle cull with room to spare.
         */
        private void trail() {
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0, STEM_DUST, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, point, 1, 0.06, 0.06, 0.06, 0, LAVENDER_DUST, FORCE_PARTICLES);
            if ((ticks & 1) == 0) {
                world.spawnParticle(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0, CYAN_DUST, FORCE_PARTICLES);
            }
            if (ticks % 3 == 0) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0.05, 0.05, 0.05, 0,
                        BLOOM_FADE, FORCE_PARTICLES);
            }
            // The heavier shots carry more of the forest with them.
            if (shot != Shot.BLOSSOM && ticks % 2 == 0) {
                world.spawnParticle(Particle.END_ROD, point, 1, 0.03, 0.03, 0.03, 0.0, null, FORCE_PARTICLES);
            }
        }

        /** A shield swallows a Blossoming arrow: a clang and a puff of scattered pollen, no damage. */
        private void shieldClang(LivingEntity victim) {
            Location at = victim.getEyeLocation().add(dir.clone().multiply(-0.5)).add(0, -0.2, 0);
            world.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.1f);
            world.spawnParticle(Particle.CRIT, at, 8, 0.2, 0.2, 0.2, 0.08, null, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, at, 6, 0.2, 0.25, 0.2, 0, LAVENDER_DUST, FORCE_PARTICLES);
        }

        /** Out of reach with nothing struck — the fragrance just thins out and is gone. */
        private void fizzle() {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 5, 0.2, 0.2, 0.2, 0,
                    BLOOM_FADE, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, point, 3, 0.15, 0.15, 0.15, 0, CYAN_DUST, FORCE_PARTICLES);
        }

        /** End this arrow exactly once: untrack it, then cancel its task. Safe to call twice. */
        private void stop() {
            if (done) return;
            done = true;
            inFlight.remove(this);
            cancel();
        }
    }

    // ---- shared presentation ---------------------------------------------------------

    /**
     * Vivid flowers sprout wherever the dull head strikes: a scatter of real flower items thrown as
     * {@link Particle#ITEM} data, a wash of lavender-to-cyan fragrance, and a soft green rustle. Nothing
     * is placed — the world is untouched the moment the particles fade.
     */
    private void flowerBurst(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // ITEM takes an ItemStack as its data — a DustOptions here would be a runtime crash.
        for (int i = 0; i < 3; i++) {
            ItemStack flower = FLOWERS[rng.nextInt(FLOWERS.length)];
            world.spawnParticle(Particle.ITEM, at, 3, 0.25, 0.3, 0.25, 0.06, flower, FORCE_PARTICLES);
        }
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 10, 0.3, 0.35, 0.3, 0,
                BLOOM_FADE, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 6, 0.3, 0.35, 0.3, 0, PETAL_DUST, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 4, 0.25, 0.3, 0.25, 0, CYAN_DUST, FORCE_PARTICLES);

        world.playSound(at, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.5f, 1.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 1.8f);
    }

    /**
     * [Magnificent End] opening: the instant of contact, and then the bloom.
     *
     * <p>This is the flash and the core burst only — one tick of it. Everything after is handed to a
     * {@link BloomShow}, which is where the actual show lives.
     *
     * <p>{@link Particle#FLASH} takes a {@link Color} on 26.1.2 — not a DustOptions, and not null. Handing
     * it the wrong data class is a runtime crash rather than a compile error.
     *
     * <p>EXPLOSION_EMITTER and EXPLOSION are the vanilla <b>particles</b>, not an explosion: nothing here
     * calls {@code createExplosion}, so no block is broken and no fire is lit.
     */
    private void explodeFx(Location at) {
        World world = at.getWorld();

        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.EXPLOSION, at, 6, 0.8, 0.8, 0.8, 0, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, C_PETAL, FORCE_PARTICLES);

        BloomShow show = new BloomShow(at);
        shows.add(show);
        show.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * [Magnificent End]'s bloom: a flower the size of a house, opening over {@value #MAG_SHOW_TICKS} ticks.
     *
     * <p>A bud of six petals stands straight up out of the impact, lies over as it opens, and settles into
     * a full flower while a ring of pollen sweeps outward to the true blast radius and cut flowers drift
     * down through it — the whole thing washed lavender-to-cyan, ringing rather than booming.
     *
     * <p><b>The show is bought with time and shape, not with counts.</b> The old bloom spent ~263 particles
     * across ~175 packets in a <b>single tick</b>; this one peaks at ~76 particles across ~60 packets on
     * its loudest tick and averages ~41 packets a tick, because every layer has a fixed ceiling:
     * {@value #MAG_RING_POINTS} ring motes a tick (the ring never densifies as it widens — that is the
     * whole trick), {@value #MAG_PETALS} x {@value #MAG_PETAL_MOTES} = 30 petal motes a tick and only for
     * the first half, one haze packet a tick, and two flower packets every third tick. Whole show: ~1,130
     * particles over 1.2s. It is the payoff for ~45s of accrual and can only fire at exactly
     * {@value #PETAL_CAP} petals, so its frequency is self-limiting in a way its peak must not rely on.
     *
     * <p>Purely cosmetic. The damage was all dealt by {@link Bolt#detonate} before this ever started, and
     * this class touches no block and spawns no entity.
     */
    private final class BloomShow extends BukkitRunnable {

        private final World world;
        private final Location at;
        private int age = 0;
        private boolean done = false;

        BloomShow(Location at) {
            this.world = at.getWorld();
            this.at = at.clone();
        }

        @Override
        public void run() {
            if (age >= MAG_SHOW_TICKS) { stop(); return; }

            double t = age / (double) MAG_SHOW_TICKS;
            double ease = 1.0 - Math.pow(1.0 - t, 3.0);            // snaps open, then settles
            double r = 0.6 + (MAGNIFICENT_END_RADIUS - 0.6) * ease;

            ring(r);
            if (t < MAG_PETAL_PHASE) petals(r, Math.min(1.0, ease * 1.6));
            haze();
            if (age % 3 == 0) fall(r);
            chime();

            age++;
        }

        /**
         * The pollen shockwave: a ring sweeping out to the true blast radius, so the AoE reads honestly on
         * screen. Fixed point count — a wider ring is a sparser ring, never a dearer one.
         */
        private void ring(double r) {
            double spin = age * 0.09; // the bloom turns as it opens
            for (int i = 0; i < MAG_RING_POINTS; i++) {
                double a = (Math.PI * 2.0 * i) / MAG_RING_POINTS + spin;
                Location p = at.clone().add(Math.cos(a) * r, 0.15 + r * 0.10, Math.sin(a) * r);
                world.spawnParticle(Particle.DUST, p, 1, 0.06, 0.06, 0.06, 0,
                        (i & 1) == 0 ? MAG_PETAL_DUST : MAG_CYAN_DUST, FORCE_PARTICLES);
            }
        }

        /**
         * The petals themselves. At {@code open = 0} they stand straight up out of the impact as a closed
         * bud; as it climbs they lie over and out, arcing up mid-petal and dipping at the tip, until at
         * {@code open = 1} they are a flower laid flat to the blast radius.
         */
        private void petals(double r, double open) {
            double spin = age * 0.09;
            for (int a = 0; a < MAG_PETALS; a++) {
                double ang = (Math.PI * 2.0 * a) / MAG_PETALS + spin;
                for (int j = 1; j <= MAG_PETAL_MOTES; j++) {
                    double u = j / (double) MAG_PETAL_MOTES;                 // 0..1 out along the petal
                    double reach = r * u * open;                             // how far it has laid over
                    double lift = (1.0 - open) * u * MAG_PETAL_HEIGHT        // still stood up, as a bud
                            + Math.sin(u * Math.PI) * 0.9 * open;            // arced over, once open
                    Location p = at.clone().add(Math.cos(ang) * reach, 0.2 + lift, Math.sin(ang) * reach);
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.05, 0.05, 0.05, 0,
                            MAG_FADE, FORCE_PARTICLES);
                }
            }
        }

        /** The fragrance hanging at the heart of it, thinning as the bloom settles. One packet a tick. */
        private void haze() {
            int n = Math.max(2, MAG_HAZE_MAX - age / 2);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, n, 0.6, 0.5, 0.6, 0,
                    MAG_FADE, FORCE_PARTICLES);
        }

        /** Cut flowers drifting down through the bloom. Two packets, every third tick, and no more. */
        private void fall(double r) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < 2; i++) {
                double a = rng.nextDouble(Math.PI * 2.0);
                double d = rng.nextDouble(0.5, Math.max(0.6, r));
                Location p = at.clone().add(Math.cos(a) * d, 0.4 + rng.nextDouble(1.4), Math.sin(a) * d);
                world.spawnParticle(Particle.ITEM, p, 2, 0.15, 0.15, 0.15, 0.05,
                        FLOWERS[rng.nextInt(FLOWERS.length)], FORCE_PARTICLES);
            }
        }

        /** It rings rather than booms — a chime climbing as the flower widens, so the ear hears it open. */
        private void chime() {
            if (age == 0) {
                world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.4f); // pitched up: floral, not military
                world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
                world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.6f);
                return;
            }
            if (age % 4 != 0) return;
            float p = Math.min(2.0f, 0.7f + (age / (float) MAG_SHOW_TICKS) * 1.1f);
            world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, p);
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, Math.max(0.5f, p * 0.8f));
        }

        /** End this bloom exactly once: untrack it, then cancel its task. Safe to call twice. */
        private void stop() {
            if (done) return;
            done = true;
            shows.remove(this);
            cancel();
        }
    }

    /**
     * A guard broken by Full Bloom / Magnificent End. Putting the shield on cooldown is the Bukkit-level
     * equivalent of vanilla's shield-disable: it drops the raised block and locks the item out for
     * {@value #SHIELD_DISABLE_TICKS} ticks. Temporary — nothing is taken from them.
     */
    private void breakGuard(Player blocker) {
        blocker.setCooldown(Material.SHIELD, SHIELD_DISABLE_TICKS);

        World world = blocker.getWorld();
        Location at = blocker.getEyeLocation().add(0, -0.2, 0);
        world.playSound(at, Sound.ITEM_SHIELD_BREAK, 0.9f, 1.2f);
        world.spawnParticle(Particle.CRIT, at, 10, 0.25, 0.25, 0.25, 0.12, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 8, 0.25, 0.3, 0.25, 0, LAVENDER_DUST, FORCE_PARTICLES);
    }

    /** The chime as the thirtieth petal opens and [Magnificent End] comes off the leash. */
    private void fullBloomChime(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 2.0f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 16, 0.4, 0.6, 0.4, 0,
                BLOOM_FADE, FORCE_PARTICLES);
    }

    private static Vector centre(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    // ---- tick: hold-to-fire + the petal charge ---------------------------------------

    /**
     * Runs every 2 ticks while the wielder is engaged. Returns false the instant the crossbow is not in
     * the main hand — otherwise this player would tick forever.
     *
     * <p>While the swing-armed hold window is live, this drives the {@value #BLOSSOM_CADENCE_MS}ms
     * Blossoming Fragrance cadence; see {@link #HOLD_WINDOW_MS} for why the stream is driven from here
     * rather than from onSwing. The first arrow of a burst never comes from here — onSwing has already
     * loosed it by the time this runs.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false; // sheathed -> disengage

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());

        long now = System.currentTimeMillis();
        if (now <= bloom.holdUntil) fireBlossom(player, bloom, now);

        renderBar(player, bloom);
        return true;
    }

    /**
     * The weapon's full readout on ONE line via {@link EgoHud#row}: the petals gauge with the live +N% it
     * is worth and the unlock cue once [Magnificent End] is off the leash, the aroma charge gathering
     * toward its next Weakness, and — while it is cooling — the Full Bloom rest. Composing them here rather
     * than flashing each on its own event is the whole standard: pressing Full Bloom on cooldown now
     * repaints this same line instead of stomping the two gauges with a lone timer.
     *
     * <p>The aroma reads 0/9 through 8/9 and never 9/9 — that is correct, not an off-by-one: the ninth
     * arrow triggers the scent and empties the meter in the same instant, so 8/9 means "the next one
     * catches".
     */
    private void renderBar(Player player, Bloom bloom) {
        player.sendActionBar(EgoHud.row(petalReadout(player, bloom), aromaReadout(bloom), fullBloomReadout(bloom)));
    }

    /** The petals gauge: the live +N% it is worth, and the Magnificent End unlock cue once at full bloom. */
    private Component petalReadout(Player player, Bloom bloom) {
        int cap = petalCap(player);
        boolean bloomed = bloom.petals >= cap;
        TextColor fill = bloomed ? CYAN : LAVENDER;
        Component label = plain("Petals  " + bloom.petals + "/" + cap, COUNT)
                .append(plain("  +" + (bloom.petals * PETAL_DAMAGE_PER_STACK_PCT) + "%",
                        bloomed ? CYAN : FAINT));
        if (bloomed) {
            label = label.append(plain("  ", COUNT)).append(EgoHud.ready("Magnificent End", CYAN));
        }
        return EgoHud.gauge(fill, (double) bloom.petals / cap, label);
    }

    /** The aroma charge gathering toward its next Weakness. */
    private Component aromaReadout(Bloom bloom) {
        return EgoHud.gauge(CYAN, (double) bloom.aroma / AROMA_CHARGE_STRIKES, AROMA_CHARGE_STRIKES,
                plain("Aroma  " + bloom.aroma + "/" + AROMA_CHARGE_STRIKES, COUNT));
    }

    /** The Full Bloom rest, shown only while it is cooling; row() drops it once the burst is ready again. */
    private Component fullBloomReadout(Bloom bloom) {
        long now = System.currentTimeMillis();
        if (now < bloom.fullBloomCd) return EgoHud.cooldown("Full Bloom", bloom.fullBloomCd - now, FAINT);
        return null;
    }

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---- item ------------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.FAINT_AROMA.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * The meta is stamped once, here, and never repainted — that is exactly why E.G.O weapons stay
     * enchantable. Nothing in this class touches item meta again after creation.
     */
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.FAINT_AROMA.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.FAINT_AROMA);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore --------------------------------------------------------------------------

    /**
     * Built once at class-load and stamped in {@link #createItem}. The display name is the WEAPON
     * ("Faint Aroma"); the title line is the ABNORMALITY ("Alriune"). Never the other way round, and
     * never both.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Faint Aroma",
            "Alriune",
            LAVENDER,
            CYAN,
            List.of(
                    "Even after the E.G.O was extracted, it",
                    "still carried the fragrance of the",
                    "archetype. Simply carrying it gives",
                    "the illusion that you're standing in a",
                    "forest in the middle of nowhere. The",
                    "arrowhead is dull and sprouts flowers",
                    "of vivid color wherever it strikes.",
                    "When flowers substitute all the desire",
                    "in everyone's hearts, this E.G.O. will",
                    "no longer be needed."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Unwithering Flower",
                            "Every strike mends you and grows a",
                            "petal. Each petal adds +1% damage, up",
                            "to 30. At 30 petals, Magnificent End",
                            "unlocks."),
                    new EgoLore.Ability("[Left Click] Blossoming Fragrance",
                            "A lavender arrow every 1.5s. A raised",
                            "shield blocks it. Every 9th arrow to",
                            "land saps its target: weakness, 5s."),
                    // Not "heavier" any more — one Full Bloom arrow lands under one Blossoming arrow now
                    // that all three of them arrive. The tooltip says what the volley actually is.
                    new EgoLore.Ability("[Right-Click] Full Bloom",
                            "Three faster arrows at once, and a",
                            "raised guard does not stop them.",
                            "Rests 8 seconds."),
                    new EgoLore.Ability("[Shift+Right-Click] Magnificent End",
                            "A single bolt that blooms on contact",
                            "for devastating damage. Spends every",
                            "petal.")
            ));

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        blooms.remove(id); // petals, cadence gates and cooldowns all go with them
        ticking.remove(id); // only ever set if the quitter was themselves struck by an arrow
        // In-flight arrows need no reaping here: each Bolt checks its caster is still online every tick
        // and stops itself. Nothing of this weapon exists in the world for them to leave behind.
    }

    @Override
    public void onDisable() {
        // Cancel every arrow still in the air and every bloom still open. Iterate copies — stop()
        // untracks as it goes.
        for (Bolt bolt : new ArrayList<>(inFlight)) bolt.stop();
        inFlight.clear();
        for (BloomShow show : new ArrayList<>(shows)) show.stop();
        shows.clear();
        blooms.clear();
        ticking.clear();
        // No entity sweep: this weapon spawns no entities at all. Its arrows and its blooms are runnables
        // drawing particles, so there is nothing that can survive a reload or litter the world.
    }
}
