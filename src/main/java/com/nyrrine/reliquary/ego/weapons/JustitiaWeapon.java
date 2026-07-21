package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
import com.nyrrine.reliquary.core.Weapon;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Justitia — "Judgement Bird". A bandaged greatsword in gold and bone-white, heavy and slow, that
 * remembers the balance of the Long Bird that never forgot others' sins. It does not duel. It weighs,
 * and then it is final.
 *
 * <p>A plain melee weapon at heart: the vanilla NETHERITE_SWORD swing lands its stamped 7.5 damage at a
 * ponderous 1.0 attack speed ({@link EgoModels#stampWeapon}), never cancelled, wearing the blade a point
 * per swing exactly as vanilla does. Everything else is the balance.
 *
 * <ul>
 *   <li><b>Judgement</b> (passive, {@link #onHit}) — a landed blow is a sin weighed. One uniform roll
 *       decides the verdict: the rarer <b>ten-hit</b> combo is checked first, then the <b>five-hit</b>.
 *       Either verdict puts Judgement to sleep for {@link #JUDGEMENT_COOLDOWN_MS 15s}.</li>
 *   <li><b>Indifference</b> (passive) — every {@link #INDIFFERENCE_PERIOD ninth} landed hit blinds the
 *       guilty with Darkness. A Judgement combo's own cuts each count, so a ten-hit verdict walks the
 *       counter ten places forward.</li>
 *   <li><b>Jurisdiction</b> (left-click, {@link #onHit}) — every <em>landed</em> blow tips the scales a
 *       little further, ramping both proc coefficients; the ramp resets the instant a verdict is passed.
 *       A blow struck at the top of the swing arc also hurries Judgement's rest along by
 *       {@link #HURRY_MS}. Swinging at nothing earns neither — the scales weigh sins, not exercise.</li>
 *   <li><b>Persecution</b> (right-click, {@link #onInteract}) — hang the scales behind you and take a
 *       counter-stance. While the scales hang the bearer is <b>totally immune</b> ({@link #onIncomingDamage}).
 *       Strike the stance and you are rooted while the bearer appears at your back, blinded by
 *       Indifference.</li>
 * </ul>
 *
 * <h2>How the verdict is rolled</h2>
 * The two chances are resolved with a <b>single</b> uniform roll rather than two independent ones, so
 * they cannot fight over the same hit and both nominal rates survive intact: {@code roll < p10} passes
 * the ten-hit verdict, {@code roll < p10 + p5} the five-hit, anything above is a clean miss. At base
 * that is exactly 20% / 40% / 40%. Jurisdiction's ramp is clamped at {@link #MAX_BONUS_STACKS} swings
 * precisely so {@code p10 + p5} can never cross 1.0 and silently eat the miss band.
 *
 * <h2>The two fences that keep it honest</h2>
 * A combo re-deals damage through {@code victim.damage(..., attacker)}, which re-enters
 * {@link #onHit} — so every sub-hit runs inside the {@link #comboing} fence, or a five-hit proc would
 * roll Judgement five more times and recursively proc itself into oblivion. Conversely the opening
 * swing stamps hurt-immunity on the victim, which would swallow every follow-up cut, so each sub-hit
 * clears {@code setNoDamageTicks(0)} first — without it a "ten-hit combo" lands exactly once.
 *
 * <h2>How the counter hears the blow, and why immunity zeroes rather than cancels</h2>
 * Persecution answers from {@link #onDamaged}, which hands it the strike itself — so the defendant is
 * read off the blow rather than guessed at from whoever stood nearest, and no per-tick entity scan is
 * needed at all. The stance answers a <em>strike</em>, not a wound: a blow a shield turns aside still
 * gets a verdict.
 *
 * <p>That is exactly why {@link #onIncomingDamage} <em>zeroes</em> the blow instead of cancelling it. {@code
 * onDamaged} is dispatched at monitor priority with {@code ignoreCancelled = true} — a cancelled blow would
 * never reach it, and the stance would go silent. A zeroed-but-live blow lands for nothing and still reaches
 * the counter, so the bearer is both immune and answered from the one strike.
 *
 * <h2>What it looks like</h2>
 * The house's ALEPH melee weapons cut with their own hand-drawn arc rather than vanilla's sweep, and
 * Justitia's is the <b>Verdict Arc</b> ({@link #judgementArc}) — the Arayashiki/Lævateinn swoop, slowed
 * and fattened until it reads as a greatsword. See the presentation section for the shape, and for the
 * particle ceiling every effect in this file is held to.
 *
 * <p>Every scale display is non-persistent, tagged {@link #SCALES_TAG}, and reaped on unequip / quit /
 * disable (plus a world sweep) so no orphan is ever left behind. Player roots are best-effort — see
 * {@link RootTask}.
 */
public final class JustitiaWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Justitia. */
    private final NamespacedKey key;

    /** Wielder UUID -> their balance: proc ramp, cooldowns, hit counter, live stance. Pruned on quit. */
    private final Map<UUID, Wielder> wielders = new HashMap<>();

    /**
     * Wielders currently dealing a Judgement combo's sub-hit — the re-entrancy fence for {@link #onHit}.
     * The sub-hit's {@code victim.damage(..., attacker)} re-enters the manager's melee dispatch; without
     * this, a proc would roll Judgement again on its own cuts and recurse away.
     */
    private final Set<UUID> comboing = new HashSet<>();

    /** Live combos, so {@link #onDisable} can cancel a verdict caught mid-swing. Self-pruning. */
    private final Set<JudgementCombo> activeCombos = new HashSet<>();

    /** Entities currently rooted by a counter — guards against stacking tasks / leaking a mob's AI flag. */
    private final Set<UUID> rooted = new HashSet<>();

    /** Live roots, so {@link #onDisable} can restore a mob rooted at reload-time. Self-pruning. */
    private final Set<RootTask> activeRoots = new HashSet<>();

    // ---- Judgement: the verdicts ---------------------------------------------------
    // These still land above the netherite band (a plain netherite sword is 8/hit, ~11 with Sharpness V),
    // and they are meant to — a verdict is supposed to be a sentence, not a hit. Damage is dealt through
    // victim.damage(), so armour still reduces it; these are pre-mitigation.
    //
    // The ten-hit was 29 (5x3 then 2x7) as originally specified. It was built to that number, flagged, and
    // playtested at it; the verdict was "too much" (Nyrrine, 2026-07-17), so it came down to 19.5. The
    // shape is what matters and it is preserved: three heavy opening cuts, then a long light flurry, and
    // the rare verdict still out-hits the common one. If it ever drops below the five-hit's 15 the jackpot
    // becomes a downgrade wearing a jackpot's odds.

    /** The five-hit verdict: 3 damage, five times = 15, on top of the swing that proc'd it. */
    private static final double[] FIVE_HIT_STEPS = {3.0, 3.0, 3.0, 3.0, 3.0};

    /** The ten-hit verdict: 3 damage three times (9), then 1.5 damage seven times (10.5) = 19.5 total. */
    private static final double[] TEN_HIT_STEPS = {3.0, 3.0, 3.0, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5};

    /** Ticks between a verdict's cuts — 2 ticks reads as a flurry (five-hit ~0.5s, ten-hit ~1s). */
    private static final long COMBO_STEP_TICKS = 2L;

    /** A verdict abandons a quarry that gets this far away mid-combo (blocks, squared). */
    private static final double COMBO_MAX_RANGE_SQ = 8.0 * 8.0;

    /** Base chance a landed hit passes the five-hit verdict. */
    private static final double FIVE_HIT_BASE = 0.40;
    /** Base chance a landed hit passes the ten-hit verdict — rarer, and rolled first. */
    private static final double TEN_HIT_BASE = 0.20;

    /** Judgement rests this long after EITHER verdict — no procs at all while it sleeps. */
    private static final long JUDGEMENT_COOLDOWN_MS = 15_000L;

    /**
     * How much of Judgement's rest a fully-charged landed blow burns off. The rest is not a timer you wait
     * out; it is a debt you work off, one honest swing at a time.
     */
    private static final long HURRY_MS = 1_000L;

    /**
     * How drawn the swing must be to count as fully charged. {@code getAttackCooldown()} runs 0 → 1 as the
     * vanilla attack bar refills; 0.9 rather than a strict 1.0 because the bar is sampled a tick or two
     * late and a perfectly-timed swing should never be told it wasn't.
     */
    private static final float FULL_SWING = 0.9f;

    // ---- Jurisdiction: the ramp ----------------------------------------------------

    /** Each swing tips the five-hit coefficient by +1%. */
    private static final double FIVE_HIT_PER_SWING = 0.01;
    /** Each swing tips the ten-hit coefficient by +0.5%. */
    private static final double TEN_HIT_PER_SWING = 0.005;

    /**
     * Ramp cap. The bases sum to 0.60 and each swing adds 0.015, so 26 swings reach 0.99 — one step short
     * of the point where the combined coefficients would swallow the miss band whole and the single roll
     * could no longer honour both stated rates. Clamping here keeps the maths truthful.
     */
    private static final int MAX_BONUS_STACKS = 26;

    // ---- Indifference: the blindness -----------------------------------------------

    /** Every ninth landed hit — swings and verdict cuts alike — blinds the guilty. */
    private static final int INDIFFERENCE_PERIOD = 9;
    /** How long Darkness clings — 5 seconds. */
    private static final int INDIFFERENCE_TICKS = 100;

    // ---- Persecution: the counter-stance --------------------------------------------

    /**
     * How long the scales hang before they settle. The spec says only "during this moment" with no
     * number — FLAGGED: 3s is a chosen value. Long enough to bait a committed swing, short enough that
     * it is a read rather than a toggle.
     */
    private static final long PERSECUTION_WINDOW_MS = 3_000L;

    /** Persecution rests this long once the stance closes — by counter or by lapse. */
    private static final long PERSECUTION_COOLDOWN_MS = 15_000L;

    /** How long a countered striker is rooted — ~2s. Best-effort against players; see {@link RootTask}. */
    private static final int ROOT_TICKS = 40;

    /** Slowness amplifier during a root — 6 → Slowness VII, a near-total crawl atop the velocity zero. */
    private static final int ROOT_SLOWNESS_AMP = 6;

    /** A striker further than this is out of the scales' jurisdiction (blocks) — no cross-map counters. */
    private static final double COUNTER_RANGE = 16.0;

    /** How far behind the striker the bearer appears; {@link Blink#behind} shuffles it to somewhere it fits. */
    private static final double COUNTER_BEHIND_DISTANCE = 1.6;

    // ---- the scales: geometry ------------------------------------------------------

    private static final double SCALES_DISTANCE = 1.9;  // how far behind the wielder they hang
    private static final double SCALES_HEIGHT   = 1.1;  // hover height off the wielder's feet
    private static final double SCALES_BOB      = 0.06; // gentle vertical sway, so they read as suspended
    private static final double POST_HEIGHT     = 1.2;  // the central column
    private static final double BEAM_SPAN       = 1.5;  // the crossbeam, pan to pan
    private static final double PAN_DROP        = 0.42; // how far the pans hang beneath the beam ends

    private static final double BASE_WIDTH  = 0.52;  // the plinth the column stands on
    private static final double BASE_THICK  = 0.07;  // ...kept a slab, not a block
    private static final double PAN_WIDTH   = 0.50;  // pans read as shallow dishes: wide and thin
    private static final double PAN_THICK   = 0.05;
    private static final double FINIAL_SIZE = 0.16;  // the bone-white jewel at the pivot
    private static final double CHAIN_THICK = 0.035; // the two links the pans hang on

    /** How far the beam tips when Jurisdiction has banked every sin it can hold. */
    private static final double MAX_TILT_DEG = 14.0;
    /** A hanging balance is never perfectly still — the beam breathes by this much either way. */
    private static final double TILT_SWAY = Math.toRadians(1.4);
    /** How quickly the beam eases toward the tilt its load asks for — it settles, it does not snap. */
    private static final double TILT_LERP = 0.16;
    /** Don't re-cut the beam's transform for a movement smaller than this — the client can't see it. */
    private static final double TILT_RECUT = Math.toRadians(0.5);

    /** Scoreboard tag on every scale display, for the belt-and-braces world sweep on shutdown. */
    private static final String SCALES_TAG = "reliquary_justitia_scales";

    public JustitiaWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "justitia");
    }

    @Override
    public String id() {
        return "justitia";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.JUSTITIA.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.JUSTITIA.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.JUSTITIA);

        item.setItemMeta(meta);
        return item;
    }

    /** This wielder's balance, opened the first time they are seen. */
    private Wielder wielder(UUID id) {
        return wielders.computeIfAbsent(id, k -> new Wielder());
    }

    // ---- Jurisdiction: every swing tips the scales -----------------------------------

    /**
     * A swing of the greatsword — and deliberately, nothing happens here.
     *
     * <p>Jurisdiction used to tip the scales from this hook, on every swing, hit or miss. That let a
     * wielder stand in an empty field beating the air until the ramp was maxed, then walk into a fight
     * with the proc coefficient already paid for. The scales weigh sins, not exercise: the ramp now lives
     * in {@link #onHit} and only a landed blow moves it.
     *
     * <p>The Verdict Arc is not drawn here either, for the same reason and one more: a slash effect on a
     * whiffed swing is a slash effect a bored player can spray at the horizon all day. It is drawn from
     * {@link #onHit}, on a blow that actually landed.
     */
    @Override
    public void onSwing(Player player) {
        // Intentionally empty — see the javadoc. Jurisdiction ramps on landed hits, in onHit.
    }

    /** The current five-hit proc coefficient, ramp included. */
    private static double fiveHitChance(Wielder w) {
        return FIVE_HIT_BASE + w.swingStacks * FIVE_HIT_PER_SWING;
    }

    /** The current ten-hit proc coefficient, ramp included. */
    private static double tenHitChance(Wielder w) {
        return TEN_HIT_BASE + w.swingStacks * TEN_HIT_PER_SWING;
    }

    // ---- Judgement + Indifference: the sin weighed ----------------------------------

    /**
     * A blow landed. Vanilla greatsword damage is left untouched underneath. The hit counts toward
     * Indifference, then Judgement weighs it. If this is one of our own verdict's cuts re-entering the
     * dispatch, the fence drops it on the floor — the combo advances its own Indifference counter
     * directly, so nothing is lost by refusing here.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();
        if (comboing.contains(id)) return; // our own verdict's cut — a proc must never proc itself

        Wielder w = wielder(id);
        if (w.swingStacks < MAX_BONUS_STACKS) w.swingStacks++; // Jurisdiction: only a landed blow tips it

        // Read the swing bar once: the hurry below and the arc's weight both want the same number.
        float draw = attacker.getAttackCooldown();

        // A blow struck at the top of the swing arc is worth more than a flurry of half-drawn ones: each
        // one hurries Judgement back by a second. Spamming light hits earns nothing here, which is the
        // point — the greatsword rewards the wielder who waits for it.
        if (draw >= FULL_SWING) {
            w.judgementReadyAt = Math.max(System.currentTimeMillis(), w.judgementReadyAt - HURRY_MS);
        }

        slashFx(attacker, w, draw);
        registerHit(victim, w);
        rollJudgement(attacker, victim, w);
    }

    /**
     * Count one landed hit toward Indifference; every ninth blinds the victim. Called for the wielder's
     * own swings AND for each cut of a Judgement verdict — a ten-hit combo walks the counter ten places,
     * which is what "stacks with hit-combos from Judgement" asks for.
     */
    private void registerHit(LivingEntity victim, Wielder w) {
        if (++w.hitCount < INDIFFERENCE_PERIOD) return;
        w.hitCount = 0;
        applyIndifference(victim);
    }

    /** Blind the guilty: Darkness for 5s, with the bell that goes with it. Skipped on a corpse. */
    private void applyIndifference(LivingEntity victim) {
        if (victim.isDead() || !victim.isValid()) return;
        victim.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS, INDIFFERENCE_TICKS, 0, false, false, true));
        indifferenceFx(victim);
    }

    /**
     * Weigh the sin. One uniform roll decides between the two verdicts so they cannot fight over the
     * same hit: the rarer ten-hit is checked first, then the five-hit takes the next slice of the roll,
     * and the remainder is a clean miss. At base that is exactly 20% / 40% / 40% — both stated rates
     * preserved, which two independent rolls could not have managed.
     */
    private void rollJudgement(Player attacker, LivingEntity victim, Wielder w) {
        long now = System.currentTimeMillis();
        if (now < w.judgementReadyAt) return;              // the balance is still resting
        if (victim.isDead() || !victim.isValid()) return;  // nothing left to weigh

        double p10 = tenHitChance(w);
        double p5 = fiveHitChance(w);
        double roll = ThreadLocalRandom.current().nextDouble();

        if (roll < p10) {
            passVerdict(attacker, victim, w, now, TEN_HIT_STEPS);
        } else if (roll < p10 + p5) {
            passVerdict(attacker, victim, w, now, FIVE_HIT_STEPS);
        }
    }

    /** A verdict passes: the ramp resets, Judgement sleeps 15s, and the cuts begin. */
    private void passVerdict(Player attacker, LivingEntity victim, Wielder w, long now, double[] steps) {
        w.swingStacks = 0;                                  // the scales reset the moment a verdict lands
        w.judgementReadyAt = now + JUDGEMENT_COOLDOWN_MS;

        // The verdict's cuts are not vanilla swings, so they wear the blade themselves — one point per
        // proc, not one per cut (ten points for one combo would eat a greatsword alive).
        EgoDurability.wearMainHand(attacker);

        JudgementCombo combo = new JudgementCombo(attacker.getUniqueId(), victim, steps, w);
        activeCombos.add(combo);
        combo.runTaskTimer(plugin, COMBO_STEP_TICKS, COMBO_STEP_TICKS);
        verdictOpenFx(attacker, victim, steps.length);
    }

    /**
     * A passed verdict, cut by cut. Each step clears the victim's hurt-immunity before striking — vanilla
     * stamped i-frames on the opening swing and every follow-up would otherwise be swallowed, turning the
     * combo into a single hit — and runs the damage inside the {@link #comboing} fence so it cannot roll
     * Judgement on itself. Velocity is captured and restored around each cut so the flurry stays a
     * flurry rather than punting the quarry across the field, exactly as the bleed weapons do.
     */
    private final class JudgementCombo extends BukkitRunnable {
        private final UUID attackerId;
        private final LivingEntity victim;
        private final double[] steps;
        private final Wielder wielder;
        private int step = 0;

        private JudgementCombo(UUID attackerId, LivingEntity victim, double[] steps, Wielder wielder) {
            this.attackerId = attackerId;
            this.victim = victim;
            this.steps = steps;
            this.wielder = wielder;
        }

        @Override
        public void run() {
            Player attacker = plugin.getServer().getPlayer(attackerId);
            if (step >= steps.length
                    || attacker == null || !attacker.isOnline()
                    || victim.isDead() || !victim.isValid()
                    || !victim.getWorld().equals(attacker.getWorld())
                    || victim.getLocation().distanceSquared(attacker.getLocation()) > COMBO_MAX_RANGE_SQ) {
                finish();
                return;
            }

            int index = step++;          // which stroke of the sentence this is — the cut's VFX wants it
            double dmg = steps[index];
            Vector preVel = victim.getVelocity();
            UUID aid = attacker.getUniqueId();

            comboing.add(aid); // fence: the damage below re-enters onHit — don't let the proc proc itself
            try {
                victim.setNoDamageTicks(0); // strip the i-frames, or this cut simply never lands
                victim.damage(dmg, attacker);
            } finally {
                comboing.remove(aid);
                victim.setVelocity(preVel); // undo this cut's knockback impulse
            }

            registerHit(victim, wielder); // each cut of the verdict counts toward Indifference
            cutFx(attacker, victim, dmg, index);

            if (step >= steps.length) finish();
        }

        private void finish() {
            activeCombos.remove(this);
            cancel();
        }
    }

    // ---- Persecution: the scales, and the counter ------------------------------------

    /**
     * Right-click: hang the scales behind you and take the counter-stance. Refused (with the wait shown
     * in whole seconds) while Persecution rests. The cast is not a vanilla swing, so it wears the blade.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        Wielder w = wielder(player.getUniqueId());
        long now = System.currentTimeMillis();

        if (w.stanceOpen()) {
            player.sendActionBar(EgoHud.status("The scales already hang.", BONE_HUD));
            return;
        }
        if (now < w.persecutionReadyAt) {
            player.sendActionBar(EgoHud.cooldown("Persecution", w.persecutionReadyAt - now, BONE_HUD));
            return;
        }

        w.scales = new Scales(player);
        w.stanceEndsAt = now + PERSECUTION_WINDOW_MS;
        EgoDurability.wearMainHand(player);
        summonFx(player);
    }

    /**
     * Persecution's immunity. While the scales hang, the bearer is <b>totally immune</b>: this HIGH-priority
     * guard zeroes any blow they take before it lands. It <em>zeroes</em> the damage rather than cancelling
     * the event, deliberately — the counter in {@link #onDamaged} is a MONITOR handler with {@code
     * ignoreCancelled = true}, so a cancelled blow would never reach it and the stance would never answer. A
     * zeroed-but-live event still reaches {@code onDamaged}, so the bearer takes nothing <b>and</b> the strike
     * is judged.
     *
     * <p>Gated on the live stance ({@link Wielder#stanceOpen()}), so immunity ends the instant the stance
     * closes — by counter, by lapse, or by sheathing — with nothing to unwind and no potion to collide with.
     * The manager's own monitor {@code onDamaged} is read-only and cannot do this, so Justitia zeroes the blow
     * from the framework's writable {@link Weapon#onIncomingDamage} hook (HIGH, before that monitor pass).
     */
    @Override
    public void onIncomingDamage(Player wielder, EntityDamageEvent event) {
        Wielder w = wielders.get(wielder.getUniqueId());
        if (w == null || !w.stanceOpen()) return;
        event.setDamage(0.0); // base to zero -> final zero after every reduction modifier; not cancelled
    }

    /**
     * Someone struck the bearer. If the scales are hanging, that is a case, and the stance answers it.
     *
     * <p>The blow is judged, not its result: a strike turned aside by a shield still answers, because
     * Persecution is a reply to being <em>struck</em>, not to losing health — a defendant who swings and
     * is merely blocked has still swung. Damage that is nobody's doing (a fall, poison, fire) is not a
     * case at all; {@link #resolveStriker} returns null and the scales keep hanging, waiting for someone
     * to actually raise a hand.
     */
    @Override
    public void onDamaged(Player victim, EntityDamageEvent event) {
        Wielder w = wielders.get(victim.getUniqueId());
        if (w == null || !w.stanceOpen()) return; // no scales hanging — nothing to answer

        LivingEntity striker = resolveStriker(victim, event);
        if (striker == null) return; // struck by the world, not a defendant — the stance holds

        fireCounter(victim, w, striker, System.currentTimeMillis());
    }

    /**
     * Who struck the bearer, read straight off the blow: the damager itself, or a projectile's shooter.
     * Null if the blow was not a living entity's, was the bearer's own, or came from outside the scales'
     * {@link #COUNTER_RANGE jurisdiction}.
     */
    private LivingEntity resolveStriker(Player player, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent e)) return null;

        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity shooter) {
            damager = shooter; // the arrow is the instrument; the archer is the defendant
        }
        if (!(damager instanceof LivingEntity striker)) return null;
        if (striker.equals(player) || striker.isDead() || !striker.isValid()) return null;
        if (!striker.getWorld().equals(player.getWorld())) return null;
        if (striker.getLocation().distanceSquared(player.getLocation()) > COUNTER_RANGE * COUNTER_RANGE) {
            return null;
        }
        return striker;
    }

    /**
     * The verdict of the stance: the scales vanish, the striker is rooted and blinded, and the bearer
     * appears at their back. Persecution then rests for 15s.
     */
    private void fireCounter(Player player, Wielder w, LivingEntity striker, long now) {
        closeStance(w, now, true); // the scales have spoken — they don't linger

        Location from = player.getLocation();
        rootStriker(striker);
        // At the striker's back if a body fits there; if every spot is walled, Blink declines and the bearer
        // stays put rather than being filed into geometry — the counter still lands from where they are.
        Location behind = Blink.behind(striker.getLocation(), COUNTER_BEHIND_DISTANCE);
        if (behind != null) player.teleport(behind);
        applyIndifference(striker); // "This counter move triggers Indifference"

        counterFx(from, player, striker);
    }



    /** Root a countered striker for {@link #ROOT_TICKS}, cutting mob AI for the hold. */
    private void rootStriker(LivingEntity striker) {
        UUID id = striker.getUniqueId();
        if (!rooted.add(id)) return; // already held — don't stack tasks or double-touch the AI flag

        if (striker instanceof Mob mob) {
            plugin.weapons().suspendAi(mob);
        }
        RootTask task = new RootTask(striker);
        activeRoots.add(task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The hold. Each tick it zeroes the target's velocity (killing walk, sprint and jump alike) and
     * re-applies a crushing Slowness, painting the gold shackle at their feet.
     *
     * <p><b>Platform limit, flagged:</b> a player cannot be truly frozen server-side without movement
     * packets, so against players this is a best-effort root — per-tick velocity zero + Slowness VII. A
     * determined player can still nudge themselves, barely. Mobs additionally have their AI suspended for the
     * duration through {@code plugin.weapons().suspendAi} and restored on the way out; the framework restores
     * it on chunk unload, reload, and disable too, so an unload mid-hold can never leave them mindless.
     */
    private final class RootTask extends BukkitRunnable {
        private final LivingEntity target;
        private int ticks = 0;

        private RootTask(LivingEntity target) {
            this.target = target;
        }

        @Override
        public void run() {
            if (ticks++ >= ROOT_TICKS || !target.isValid() || target.isDead()) {
                finish();
                return;
            }
            target.setVelocity(new Vector(0, 0, 0));
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, 6, ROOT_SLOWNESS_AMP, false, false, false));
            // The hold is every tick; the shackle is drawn every fourth. A 2s root that painted a ring
            // 40 times over would spend more particles than the whole verdict that earned it.
            if ((ticks & 3) == 0) rootVfx(target.getLocation());
        }

        private void finish() {
            rooted.remove(target.getUniqueId());
            restoreAi();
            activeRoots.remove(this);
            cancel();
        }

        /** Disable-time reap: hand a still-held mob its AI back and cancel. Caller clears the tracking set. */
        void shutdown() {
            restoreAi();
            cancel();
        }

        private void restoreAi() {
            if (target instanceof Mob mob) plugin.weapons().restoreAi(mob);
        }
    }

    /** Take the scales down. {@code spendCooldown} — a stance that ran its course pays; a sheathed one doesn't. */
    private void closeStance(Wielder w, long now, boolean spendCooldown) {
        if (w.scales != null) {
            w.scales.dispose();
            w.scales = null;
        }
        w.stanceEndsAt = 0L;
        if (spendCooldown) w.persecutionReadyAt = now + PERSECUTION_COOLDOWN_MS;
    }

    // ---- tick: drive the stance, speak the balance ----------------------------------

    /**
     * Called every 2 ticks while the player is an active wielder. Drives the hanging scales, samples for
     * the counter, and speaks the balance on the action bar. The instant the greatsword leaves the main
     * hand this reaps the scales and returns {@code false} — or the player would tick forever.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (!matches(player.getInventory().getItemInMainHand())) {
            Wielder gone = wielders.get(id);
            // Sheathed: the stance dies with it, and costs nothing — you gave up the counter to put it away.
            if (gone != null) closeStance(gone, now, false);
            return false; // STOP TICKING
        }

        Wielder w = wielder(id);

        if (w.stanceOpen()) {
            // The beam is shown the load Jurisdiction has banked, so the scales physically weigh what the
            // action bar reports. A reading of state that already exists — it decides nothing.
            w.scales.tick(player, tick, w.swingStacks / (double) MAX_BONUS_STACKS);
            if (now >= w.stanceEndsAt) {
                closeStance(w, now, true); // lapsed unanswered — the rest is still spent, so it can't be spammed
                lapseFx(player);
            }
        }

        showBalance(player, w, now);
        return true;
    }

    /**
     * The balance, on the action bar: the open stance's remaining moment, else Judgement's rest, else the
     * live proc coefficients with the Jurisdiction ramp as the gauge. Always whole seconds, never
     * milliseconds.
     */
    private void showBalance(Player player, Wielder w, long now) {
        if (w.stanceOpen()) {
            // "Stance", not "Persecution" — this is the window counting down, and it must not read as
            // the rest that onInteract reports under the ability's own name.
            player.sendActionBar(EgoHud.cooldown("Stance", w.stanceEndsAt - now, GOLD_HUD));
            return;
        }
        if (now < w.judgementReadyAt) {
            player.sendActionBar(EgoHud.cooldown("Judgement", w.judgementReadyAt - now, BONE_HUD));
            return;
        }
        String label = String.format(Locale.ROOT, "Judgement  5× %.1f%%  10× %.1f%%",
                fiveHitChance(w) * 100.0, tenHitChance(w) * 100.0);
        player.sendActionBar(EgoHud.gauge(GOLD_HUD,
                w.swingStacks / (double) MAX_BONUS_STACKS,
                EgoHud.status(label, BONE_HUD)));
    }

    // ---- lifecycle: never orphan an entity ------------------------------------------

    @Override
    public void onQuit(UUID id) {
        Wielder w = wielders.remove(id);
        if (w != null && w.scales != null) w.scales.dispose();
        comboing.remove(id);
        rooted.remove(id); // the quitter may have been a rooted striker rather than a wielder
    }

    @Override
    public void onDisable() {
        for (JudgementCombo combo : new ArrayList<>(activeCombos)) combo.cancel();
        activeCombos.clear();

        // A mob rooted at disable/reload-time would stay AI-disabled forever — restore before clearing.
        for (RootTask task : new ArrayList<>(activeRoots)) task.shutdown();
        activeRoots.clear();
        rooted.clear();

        for (Wielder w : wielders.values()) {
            if (w.scales != null) w.scales.dispose();
        }
        wielders.clear();
        comboing.clear();

        sweepOrphans(); // belt-and-braces: reap any stray scale piece anywhere in the world
    }

    /** Remove every scale display carrying our tag across all loaded worlds. */
    private void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(BlockDisplay.class)) {
                if (e.getScoreboardTags().contains(SCALES_TAG)) e.remove();
            }
        }
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Wielder> e : wielders.entrySet()) {
            if (!e.getValue().stanceOpen()) continue;
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            out.add("justitia  scales hanging (" + who + ")");
        }
        return out;
    }

    // ---- the wielder's balance -------------------------------------------------------

    /** One wielder's state: the Jurisdiction ramp, both rests, the Indifference count, the live stance. */
    private static final class Wielder {
        /** Epoch-millis when Judgement may weigh again. */
        long judgementReadyAt = 0L;
        /** Epoch-millis when Persecution may be taken again. */
        long persecutionReadyAt = 0L;
        /** Swings banked by Jurisdiction, clamped to {@link #MAX_BONUS_STACKS}. Reset by every verdict. */
        int swingStacks = 0;
        /** Landed hits since the last Indifference, counting verdict cuts. Wraps at {@link #INDIFFERENCE_PERIOD}. */
        int hitCount = 0;
        /** The hanging scales — non-null exactly while the counter-stance is open. */
        Scales scales;
        /** Epoch-millis when the open stance settles. */
        long stanceEndsAt = 0L;
        /**
         * Epoch-millis when the Verdict Arc may be drawn again. Purely a VFX rate gate — see
         * {@link #slashFx}. Nothing Judgement reads ever looks at this.
         */
        long arcVfxAt = 0L;
        boolean stanceOpen() {
            return scales != null;
        }
    }

    // ---- the scales: the visual signature --------------------------------------------

    /**
     * The scales of the Long Bird, hanging in the air behind the wielder — and built to read as a real
     * object rather than a suggestion of one: a plinth, a gold column standing on it, a bone-white jewel
     * at the pivot, the crossbeam turning on that jewel, and two shallow bone-white dishes hanging off
     * the beam's ends on their own gold links. Eight {@link BlockDisplay} entities — every one
     * non-persistent and tagged, so a crash can never leave them on disk and the world sweep can always
     * find them.
     *
     * <p><b>It is a balance, so it balances.</b> The beam rolls under the load Jurisdiction has banked —
     * level with a clean slate, tipped hard at the ramp's cap — easing toward it rather than snapping, and
     * breathing slightly even at rest, the way a suspended beam never quite stops. The pans hang from the
     * beam's ends on fixed links and stay level as it tips, rising and dropping with the end they hang
     * from; that is what a real balance does, and it is why the chains are solid displays now instead of
     * the three dust motes that used to imply them. Cheaper and truer at once.
     *
     * <p>Each piece is teleported to its slot every 2 ticks with a matching teleport duration, which the
     * client interpolates into a smooth float. The beam is scaled along its own local X axis and carries
     * the wielder's yaw, so it always lies across their back rather than pointing at it.
     */
    private static final class Scales {

        private final BlockDisplay post;
        private final BlockDisplay base;
        private final BlockDisplay beam;
        private final BlockDisplay finial;
        private final BlockDisplay chainLeft;
        private final BlockDisplay chainRight;
        private final BlockDisplay panLeft;
        private final BlockDisplay panRight;
        /** Every piece, for the reap — so adding one to the constructor can't leave one behind. */
        private final BlockDisplay[] pieces;

        private boolean alive = true;
        /** The beam's live roll, radians. Positive lifts the {@code +right} end. */
        private double tilt = 0.0;
        /** The roll the beam's transform was last cut to — see {@link #TILT_RECUT}. */
        private double drawnTilt = 0.0;

        Scales(Player owner) {
            Location at = owner.getLocation().add(0, SCALES_HEIGHT, 0);
            World world = owner.getWorld();
            post       = spawn(world, at, FRAME_MATERIAL, bar(0.09f, (float) POST_HEIGHT, 0.09f));
            base       = spawn(world, at, FRAME_MATERIAL,
                    bar((float) BASE_WIDTH, (float) BASE_THICK, (float) BASE_WIDTH));
            beam       = spawn(world, at, FRAME_MATERIAL, beamShape(0.0));
            finial     = spawn(world, at, PAN_MATERIAL,
                    bar((float) FINIAL_SIZE, (float) FINIAL_SIZE, (float) FINIAL_SIZE));
            chainLeft  = spawn(world, at, FRAME_MATERIAL,
                    bar((float) CHAIN_THICK, (float) PAN_DROP, (float) CHAIN_THICK));
            chainRight = spawn(world, at, FRAME_MATERIAL,
                    bar((float) CHAIN_THICK, (float) PAN_DROP, (float) CHAIN_THICK));
            panLeft    = spawn(world, at, PAN_MATERIAL,
                    bar((float) PAN_WIDTH, (float) PAN_THICK, (float) PAN_WIDTH));
            panRight   = spawn(world, at, PAN_MATERIAL,
                    bar((float) PAN_WIDTH, (float) PAN_THICK, (float) PAN_WIDTH));
            pieces = new BlockDisplay[]{post, base, beam, finial, chainLeft, chainRight, panLeft, panRight};
        }

        /** One piece of the scales: a coloured block, shaped by its transform, non-persistent and tagged. */
        private static BlockDisplay spawn(World world, Location at, Material material, Transformation shape) {
            return world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(material.createBlockData());
                d.setTransformation(shape);
                d.setBrightness(new Display.Brightness(14, 15)); // gold should gleam even at midnight
                d.setPersistent(false);                          // a crash can never leave these on disk
                d.setInterpolationDuration(3);
                d.setTeleportDuration(3);                        // smooth the float between 2-tick samples
                d.addScoreboardTag(SCALES_TAG);
            });
        }

        /**
         * A bar of the given local dimensions, centred on its display's own position. A BlockDisplay draws
         * its block across the unit cube, scaled first and then translated — so a translation of -s/2 per
         * axis lands the shape centred rather than hanging off one corner.
         */
        private static Transformation bar(float sx, float sy, float sz) {
            return bar(sx, sy, sz, new Quaternionf());
        }

        /**
         * The same bar, rolled by {@code leftRot} about its own middle.
         *
         * <p>The subtlety worth writing down: a display applies
         * {@code translation + leftRotation * (scale * vertex)} — the translation lands <em>outside</em>
         * the rotation. So the -s/2 centring offset has to be rotated along with the shape, or the bar
         * swings around the display's origin like a thrown stick instead of turning on its own middle like
         * a beam on a pivot. Rotating the offset by the same quaternion puts the pivot back in the centre.
         */
        private static Transformation bar(float sx, float sy, float sz, Quaternionf leftRot) {
            Vector3f centring = new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f);
            leftRot.transform(centring); // in-place: the offset now turns with the shape
            return new Transformation(
                    centring,
                    new Quaternionf(leftRot), // copied — the caller's quaternion is not ours to keep
                    new Vector3f(sx, sy, sz),
                    new Quaternionf());
        }

        /**
         * The crossbeam at a given roll. The bar is scaled along its own local X — which, because the
         * display carries the wielder's yaw, is the wielder's {@code right} — so rolling it about local Z
         * lifts the {@code +right} end and drops the other by the same amount.
         */
        private static Transformation beamShape(double roll) {
            return bar((float) BEAM_SPAN, 0.08f, 0.08f, new Quaternionf().rotateZ((float) roll));
        }

        /**
         * Hang the scales behind the wielder, tip the beam to its load, and let the low pan weep a little
         * gold.
         *
         * @param load Jurisdiction's ramp as 0..1 — how much the balance is being asked to carry
         */
        void tick(Player owner, long tick, double load) {
            if (!alive) return;

            Location stand = owner.getLocation();
            float yaw = stand.getYaw();
            double rad = Math.toRadians(yaw);
            Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector right = new Vector(Math.cos(rad), 0, Math.sin(rad));

            double bob = Math.sin(tick * 0.10) * SCALES_BOB;
            Location centre = stand.clone()
                    .subtract(forward.clone().multiply(SCALES_DISTANCE))
                    .add(0, SCALES_HEIGHT + bob, 0);

            // The balance answers its load, and never quite stops moving even when it doesn't.
            double target = Math.toRadians(MAX_TILT_DEG) * Math.max(0.0, Math.min(1.0, load))
                    + Math.sin(tick * 0.07) * TILT_SWAY;
            tilt += (target - tilt) * TILT_LERP;

            place(post, centre, yaw);
            place(base, centre.clone().add(0, -POST_HEIGHT / 2.0, 0), yaw);

            Location pivot = centre.clone().add(0, POST_HEIGHT / 2.0, 0);
            place(beam, pivot, yaw);
            place(finial, pivot, yaw);
            if (Math.abs(tilt - drawnTilt) > TILT_RECUT && beam != null && beam.isValid()) {
                drawnTilt = tilt;
                beam.setTransformation(beamShape(tilt)); // the client interpolates the roll for us
            }

            // The beam's ends, lifted and dropped by the roll. The pans hang beneath them on fixed links
            // and stay level — a balance tips its beam, not its dishes.
            double halfSpan = BEAM_SPAN / 2.0;
            double reach = halfSpan * Math.cos(tilt);
            double lift = halfSpan * Math.sin(tilt);
            Location endLeft = pivot.clone().add(right.clone().multiply(-reach)).add(0, -lift, 0);
            Location endRight = pivot.clone().add(right.clone().multiply(reach)).add(0, lift, 0);

            place(chainLeft, endLeft.clone().add(0, -PAN_DROP / 2.0, 0), yaw);
            place(chainRight, endRight.clone().add(0, -PAN_DROP / 2.0, 0), yaw);

            Location panL = endLeft.clone().add(0, -PAN_DROP, 0);
            Location panR = endRight.clone().add(0, -PAN_DROP, 0);
            place(panLeft, panL, yaw);
            place(panRight, panR, yaw);

            if ((tick % 8) == 0) { // once every 16 game ticks — the scales are an object, not a firework
                World w = centre.getWorld();
                w.spawnParticle(Particle.DUST, pivot, 1, 0.06, 0.04, 0.06, 0, GOLD_FINE);
                w.spawnParticle(Particle.END_ROD, pivot, 1, 0.10, 0.02, 0.10, 0.002);
                // Whichever dish is carrying the weight runs a little gold off its lip.
                Location low = tilt >= 0 ? panL : panR;
                w.spawnParticle(Particle.DUST, low.clone().add(0, -0.05, 0), 2, 0.10, 0.02, 0.10, 0, GOLD_FINE);
            }
        }

        /** Move one piece to its slot, carrying the wielder's yaw. Skipped if something removed it. */
        private static void place(BlockDisplay d, Location at, float yaw) {
            if (d == null || d.isDead() || !d.isValid()) return;
            Location slot = at.clone();
            slot.setYaw(yaw);
            slot.setPitch(0f);
            d.teleport(slot);
        }

        /** Reap every piece and mark the scales dead so a late tick bails out. */
        void dispose() {
            alive = false;
            for (BlockDisplay d : pieces) {
                if (d != null && d.isValid()) d.remove();
            }
        }
    }

    // ---- presentation ----------------------------------------------------------------
    //
    // THE PARTICLE BUDGET. This roster runs ~100 players at ~13 TPS, so every burst below is a fixed
    // compile-time count — not one of them scales with players, entities, distance or damage. Drama is
    // bought with SIZE, SPREAD and LIFETIME (Justitia's dust is 2.4-2.8f where Arayashiki's is 1.1f);
    // it is never bought with more motes.
    //
    //   Verdict Arc (per landed swing) .. <=  104   rate-gated to 1 per ARC_VFX_GAP_MS
    //   one verdict cut ................. <=   17   (heavy: 16 + a SWEEP_ATTACK; light: 16)
    //   verdict opening ................. <=   55   (ten-hit, column included) / 27 (five-hit)
    //   -> whole five-hit verdict ....... <=  112 = 27 + 5x17
    //   -> whole ten-hit verdict ........ <=  218 = 55 + 3x17 + 7x16, over ~20 ticks
    //   -> worst swing a wielder can buy   <=  322 = arc 104 + ten-hit 218, spread over ~24 ticks
    //   counter ......................... <=   82   once per 15s per wielder
    //   Indifference .................... <=   20   once per 9 landed hits
    //   root shackle .................... <=   10 a frame, drawn every 4th tick => <= 100 per 2s root
    //   scales .......................... <=    4 per 16 game ticks, per hanging stance
    //
    // Nothing here forces its particles. Every mote is spawned within ~3 blocks of the wielder or the
    // guilty, so the people the effect is FOR are never near vanilla's ~32-block cull; forcing would only
    // buy spectators at the back a slash they don't need, at 100 players' worth of packets. That is a
    // deliberate call, not an oversight — a forced burst is exactly how a roster kills a server.
    //
    // AUDIT: DUST -> DustOptions, DUST_COLOR_TRANSITION -> DustTransition. Every spawnParticle in this
    // file passes the data class its particle actually demands (a mismatch is a runtime crash, not a
    // compile error); END_ROD / CRIT / SWEEP_ATTACK / ENCHANTED_HIT / ELECTRIC_SPARK take no data at all.
    // Nothing here uses BLOCK / FALLING_DUST / DUST_PILLAR / ITEM / ENTITY_EFFECT / FLASH, so no
    // BlockData, ItemStack or Color is ever handed to a particle that wants something else.

    // ---- the Verdict Arc: Justitia's M1 ----------------------------------------------

    /**
     * Points along the cut. Deliberately far fewer than Arayashiki's 46: a katana's edge is a fine line,
     * a bandaged greatsword's is a slab. The weight comes from how big each mote is, never from how many.
     */
    private static final int ARC_POINTS = 24;

    /** Ticks the blade takes to travel the arc. The slowest reveal in the house, and the point of it. */
    private static final int ARC_REVEAL = 7;

    /** Ticks a point of the cut hangs in the air once the blade has passed it. */
    private static final int ARC_FADE = 6;

    /** How far the cut reaches at a full draw. */
    private static final double ARC_RADIUS = 2.6;

    /**
     * The arc's rate gate. At the greatsword's 1.0 attack speed an honest swing is a second apart, so a
     * wielder never meets this; it exists only so a click-spammer landing five half-draws a second can't
     * paint five arcs a second on top of them.
     */
    private static final long ARC_VFX_GAP_MS = 300L;

    /**
     * Draw the Verdict Arc for a landed blow, at most once per {@link #ARC_VFX_GAP_MS}.
     *
     * <p>The gate is <em>presentation only</em>: it lives in its own field, nothing Judgement, Jurisdiction
     * or Persecution reads ever looks at it, and skipping the arc skips exactly the arc.
     */
    private void slashFx(Player attacker, Wielder w, float draw) {
        long now = System.currentTimeMillis();
        if (now < w.arcVfxAt) return;
        w.arcVfxAt = now + ARC_VFX_GAP_MS;
        judgementArc(attacker, draw);
    }

    /**
     * <b>The Verdict Arc.</b> Arayashiki's travelling swoop and Lævateinn's heavy sealed sweep, spoken in
     * gold and bone — the same construction the house uses for every big cut, tuned until it reads as a
     * greatsword rather than a katana:
     *
     * <ul>
     *   <li><b>The frame</b> is theirs exactly: an orthonormal basis off the look direction, the arc laid
     *       in the plane of {@code u} (forward) and a rolled perpendicular {@code v}, sampled as
     *       {@code u·cos a + v·sin a} about a pivot on the body, with a little per-point jitter so the
     *       edge isn't a ruled line.</li>
     *   <li><b>The travel</b> is theirs: each point carries a {@code birth} tick, the runnable reveals a
     *       point when its age hits 0 and fades it out over {@link #ARC_FADE}, so the blade visibly walks
     *       its own arc instead of the whole crescent flashing at once.</li>
     *   <li><b>The roll is where it parts company.</b> Arayashiki rolls the plane the full circle, so no
     *       two of its swoops share an orientation; Justitia rolls it only a little either side of
     *       vertical. The plane stays a standing wheel and the cut always arrives as an axe-stroke — the
     *       variety lives in the sweep, the reach and the tilt, never in "which way did the die say".</li>
     *   <li><b>The blade always falls.</b> Arayashiki flips its reveal direction at random. Justitia never
     *       does: the top of the wheel is cut first and the ground last. A verdict comes down.</li>
     *   <li><b>The weight</b> is timing and size. Arayashiki reveals in 5 ticks and fades in 4; Lævateinn's
     *       heavy sweep in 4 and 5. This is 7 and 6 — 0.65s of blade, the slowest cut in the house, and at
     *       1.0 attack speed it has just faded as the next swing lands. The dust is 2.4-2.8f against
     *       Arayashiki's 1.1f, and there are half as many points behind it.</li>
     *   <li><b>The colours</b> are Lævateinn's body/edge split: a bone-white leading edge — the blade
     *       itself, mid-fall — trailing gold behind it, with END_ROD on the edge for Arayashiki's glowing
     *       tip and a few CRIT for the bite.</li>
     * </ul>
     *
     * <p>One capped runnable, O(active wielders). 104 particles at the ceiling, spread across 13 ticks:
     * the edge is drawn at full density because it is the thing you actually look at, and the trailing
     * wake at half, because a wake nobody reads shouldn't cost what the blade costs.
     *
     * @param draw the swing bar, 0..1 — a half-drawn blow cuts a smaller, thinner arc than a committed one
     */
    private void judgementArc(Player player, float draw) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location centre = player.getLocation().add(0, 1.0, 0); // the swoop pivots on the body
        Vector dir = player.getEyeLocation().getDirection().normalize();

        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();

        // The roll, kept near vertical: v stays close to `up`, so the wheel stands.
        double roll = rng.nextDouble(-0.55, 0.55);
        final Vector u = dir.clone();
        final Vector v = up.clone().multiply(Math.cos(roll)).add(right.clone().multiply(Math.sin(roll)));

        double weight = 0.75 + 0.25 * Math.max(0f, Math.min(1f, draw)); // the greatsword rewards the wait
        double radius = ARC_RADIUS * weight * (0.95 + rng.nextDouble() * 0.20);
        double sweep = Math.toRadians(190 + rng.nextInt(50)); // wraps wide across the view, like the cleave
        double aMid = rng.nextDouble(-0.18, 0.18);
        final float thick = (float) (2.4 * weight);

        final Location[] pts = new Location[ARC_POINTS + 1];
        final int[] birth = new int[ARC_POINTS + 1];
        for (int i = 0; i <= ARC_POINTS; i++) {
            double f = (double) i / ARC_POINTS;
            double a = aMid - sweep / 2.0 + sweep * f;   // i = 0 is the low end of the wheel, i = N the high
            Vector radial = u.clone().multiply(Math.cos(a) * radius)
                    .add(v.clone().multiply(Math.sin(a) * radius));
            Location p = centre.clone().add(radial);
            p.add((rng.nextDouble() - 0.5) * 0.10,
                  (rng.nextDouble() - 0.5) * 0.10,
                  (rng.nextDouble() - 0.5) * 0.10);
            pts[i] = p;
            // Cut the top first and the ground last — never the other way about.
            birth[i] = Math.round((float) ARC_REVEAL * (ARC_POINTS - i) / ARC_POINTS);
        }

        final Particle.DustOptions edge = new Particle.DustOptions(BONE_RGB, thick + 0.4f);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > ARC_REVEAL + ARC_FADE) { cancel(); return; }
                for (int i = 0; i <= ARC_POINTS; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= ARC_FADE) continue;
                    if (age == 0) {
                        // The bone-white leading edge: the blade, caught mid-fall.
                        world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0, edge);
                        if (i % 3 == 0) world.spawnParticle(Particle.END_ROD, pts[i], 1, 0, 0, 0, 0);
                        if (i % 6 == 0) world.spawnParticle(Particle.CRIT, pts[i], 1, 0, 0, 0, 0.02);
                    } else if ((i & 1) == 0) {
                        // The gold wake, at half the edge's density — and that halving is the whole
                        // reason a cut this big is affordable at 100 players.
                        float sz = thick * (1.0f - 0.45f * age / ARC_FADE);
                        world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0,
                                new Particle.DustOptions(GOLD_RGB, sz));
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // Low and slow: the same two voices Arayashiki swings with, pitched down until they read as mass.
        world.playSound(centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.55f + jitter());
        world.playSound(centre, Sound.ITEM_TRIDENT_RETURN, 0.45f, 0.60f + jitter());
    }

    // ---- the verdict's strokes -------------------------------------------------------

    /** Points in one stroke of a verdict. Fixed — ten of these land inside a second. */
    private static final int CUT_POINTS = 9;

    /** How wide a stroke is cut across the guilty. */
    private static final double CUT_LENGTH = 1.7;

    /**
     * The angles the strokes are cut at, in order — not rolled. A verdict is a sentence being written, so
     * the cuts cross each other into a tally instead of scribbling, and the ten-hit's last word is the
     * upright at the end. The five-hit takes the first five; the ten-hit walks all ten.
     */
    private static final double[] CUT_ANGLES = {
            Math.toRadians(-52), Math.toRadians(52), Math.toRadians(-24), Math.toRadians(24),
            Math.toRadians(-72), Math.toRadians(72), Math.toRadians(0), Math.toRadians(-40),
            Math.toRadians(40), Math.toRadians(90),
    };

    /**
     * One cut of a verdict landing. The heavier steps bite louder — and note the threshold: with the
     * settled steps ({@code 3,3,3,1.5×7}) nothing reaches 5, so the old {@code >= 5.0} test had quietly
     * become dead code after the rebalance and every cut of every verdict was reading light. At 3.0 it
     * lives again exactly as it was meant to: the five-hit is five full-weight cuts, the ten-hit opens on
     * three and then flurries.
     */
    private static void cutFx(Player attacker, LivingEntity victim, double dmg, int index) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        boolean heavy = dmg >= 3.0;

        w.playSound(at, heavy ? Sound.ENTITY_PLAYER_ATTACK_CRIT : Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                heavy ? 0.8f : 0.45f, 0.75f + jitter());
        verdictCut(attacker, victim, index);
        if (heavy) w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.10, 0.10, 0.10, 0);
    }

    /**
     * One stroke, carved across the guilty — Lævateinn's {@code afterimage}: a straight, bright,
     * edge-cored line with a deep glow offset behind it for thickness and hard sparks where the blade
     * left. One frame, no runnable, exactly as Lævateinn built it ("spawn rapidly") — which is precisely
     * what a ten-cut verdict needs, because whatever hangs off a cut here runs ten times inside a second.
     *
     * <p>The stroke is laid in the plane square to the attacker's eye, so a verdict always presents its
     * face to the person passing it rather than edge-on.
     */
    private static void verdictCut(Player attacker, LivingEntity victim, int index) {
        World w = victim.getWorld();
        Location centre = victim.getLocation().add(0, victim.getHeight() * 0.55, 0);

        Vector view = centre.toVector().subtract(attacker.getEyeLocation().toVector());
        if (view.lengthSquared() < 1.0e-6) view = new Vector(0, 0, 1);
        view.normalize();
        Vector right = view.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(view).normalize();

        double angle = CUT_ANGLES[Math.floorMod(index, CUT_ANGLES.length)];
        Vector cut = right.clone().multiply(Math.cos(angle)).add(up.clone().multiply(Math.sin(angle)));
        Vector perp = cut.clone().crossProduct(view).normalize().multiply(0.07); // the stroke's thickness

        Location start = centre.clone().subtract(cut.clone().multiply(CUT_LENGTH / 2.0));
        for (int i = 0; i < CUT_POINTS; i++) {
            double f = i / (double) (CUT_POINTS - 1);
            Location p = start.clone().add(cut.clone().multiply(CUT_LENGTH * f));
            boolean tip = i == 0 || i == CUT_POINTS - 1;
            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, tip ? CUT_TIP : CUT_BODY);
            if ((i & 1) == 0) w.spawnParticle(Particle.DUST, p.clone().add(perp), 1, 0, 0, 0, 0, CUT_DEEP);
        }
        w.spawnParticle(Particle.CRIT, centre, 2, 0.10, 0.10, 0.10, 0.08);
    }

    // ---- the rest of the show --------------------------------------------------------

    /** The scales come down: a bell, a chain, and a ring of gold about the wielder. */
    private void summonFx(Player player) {
        World w = player.getWorld();
        Location at = player.getLocation();
        w.playSound(at, Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.7f);
        w.playSound(at, Sound.BLOCK_BELL_USE, 0.7f, 0.6f);
        ring(at.clone().add(0, 0.08, 0), 1.4, GOLD_DUST, 22);
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1.2, 0), 4, 0.35, 0.30, 0.35, 0.01);
    }

    /**
     * The stance settles unanswered: a single low, disappointed chime. Deliberately no action bar —
     * {@link #showBalance} paints the very same tick and would swallow any message written here.
     */
    private void lapseFx(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.5f, 0.6f);
    }

    /**
     * A verdict opens — the sentence, read out. The toll of the bell weighted by how heavy it is, gold
     * turning to bone in the air, the scales' mark struck on the ground, and for the ten-hit the
     * {@link #column} standing on the guilty before a single cut has landed.
     */
    private void verdictOpenFx(Player attacker, LivingEntity victim, int cuts) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        boolean heavy = cuts >= TEN_HIT_STEPS.length;

        w.playSound(at, Sound.BLOCK_BELL_USE, heavy ? 1.0f : 0.7f, heavy ? 0.5f : 0.9f);
        if (heavy) w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);

        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, heavy ? 18 : 12,
                0.35, 0.45, 0.35, 0, GOLD_SHIMMER);
        ring(victim.getLocation().add(0, 0.06, 0), heavy ? 1.5 : 1.1, GOLD_DUST, heavy ? 18 : 12);
        w.spawnParticle(Particle.END_ROD, at, heavy ? 5 : 3, 0.30, 0.40, 0.30, 0.01);
        if (heavy) column(victim);
        attacker.playSound(attacker.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.5f, heavy ? 0.7f : 1.0f);
    }

    /** How tall the sentence stands, and how many motes it is allowed to stand in. */
    private static final double COLUMN_HEIGHT = 3.0;
    private static final int COLUMN_POINTS = 12;

    /**
     * The sentence, stood on the guilty: a shaft of gold tapering as it rises, and a bone-white spark at
     * the top of it. One frame, twelve motes and a pair of sparks — the drama is that it is three blocks
     * tall, not that it is expensive.
     */
    private static void column(LivingEntity victim) {
        World w = victim.getWorld();
        Location foot = victim.getLocation();
        for (int i = 0; i < COLUMN_POINTS; i++) {
            double f = i / (double) (COLUMN_POINTS - 1);
            Location p = foot.clone().add(0, 0.1 + f * COLUMN_HEIGHT, 0);
            w.spawnParticle(Particle.DUST, p, 1, 0.06, 0.02, 0.06, 0,
                    new Particle.DustOptions(GOLD_RGB, (float) (1.7 - 1.0 * f)));
        }
        w.spawnParticle(Particle.END_ROD, foot.clone().add(0, COLUMN_HEIGHT, 0), 2, 0.05, 0.05, 0.05, 0.01);
    }

    /** Indifference takes hold: the bone-white blindness, and a bell no one answers. */
    private void indifferenceFx(LivingEntity victim) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.75, 0);
        w.playSound(at, Sound.BLOCK_BELL_RESONATE, 0.7f, 0.5f);
        w.spawnParticle(Particle.DUST, at, 14, 0.30, 0.35, 0.30, 0, BONE_DUST);
        w.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.25, 0.30, 0.25, 0.02);
    }

    /** How many motes the bearer's passage is drawn in, however far it was. */
    private static final int PASSAGE_POINTS = 18;

    /**
     * The bearer's passage: a thread of bone and gold from where they stood to where they arrived.
     *
     * <p>The count is fixed, so a counter thrown across the whole 16-block jurisdiction costs exactly what
     * a counter at arm's length costs — only the spacing stretches. Anything that draws a line between two
     * points has to decide this, and "one mote every 0.2 blocks" is how a burst quietly becomes unbounded.
     */
    private static void passage(Location from, Location to) {
        if (!from.getWorld().equals(to.getWorld())) return;
        World w = from.getWorld();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        for (int i = 0; i < PASSAGE_POINTS; i++) {
            double f = i / (double) (PASSAGE_POINTS - 1);
            Location p = from.clone().add(dx * f, dy * f, dz * f);
            w.spawnParticle(Particle.DUST, p, 1, 0.03, 0.03, 0.03, 0, (i & 1) == 0 ? BONE_FINE : GOLD_FINE);
        }
    }

    /**
     * The counter lands: the bearer gone from where they stood, the thread of their passage still hanging
     * in the air behind them, and the sentence standing on the guilty one at the other end of it.
     */
    private void counterFx(Location from, Player player, LivingEntity striker) {
        World w = striker.getWorld();
        Location at = striker.getLocation().add(0, striker.getHeight() * 0.6, 0);

        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.6f);
        w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f);          // the weight of the sentence
        w.playSound(at, Sound.BLOCK_BELL_USE, 1.0f, 0.5f);            // the toll
        w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.6f, 0.5f);         // the strike, turned aside

        passage(from.clone().add(0, 1.0, 0), player.getLocation().add(0, 1.0, 0));
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 20, 0.40, 0.50, 0.40, 0, GOLD_SHIMMER);
        w.spawnParticle(Particle.ENCHANTED_HIT, at, 8, 0.30, 0.35, 0.30, 0.10);
        ring(striker.getLocation().add(0, 0.06, 0), 1.6, GOLD_DUST, 20);
        column(striker);

        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);
    }

    /** The shackle: a tight gold ring dragging at a rooted target's feet. Drawn every 4th tick of a hold. */
    private void rootVfx(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.DUST, at.clone().add(0, 0.25, 0), 2, 0.26, 0.20, 0.26, 0, GOLD_FINE);
        ring(at.clone().add(0, 0.04, 0), 0.75, BONE_DUST, 8);
    }

    /** A flat ring of dust on the ground — the scales' mark. */
    private static void ring(Location centre, double radius, Particle.DustOptions dust, int points) {
        World w = centre.getWorld();
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = centre.clone().add(Math.cos(a) * radius, 0.0, Math.sin(a) * radius);
            w.spawnParticle(Particle.DUST, p, 1, 0.02, 0.01, 0.02, 0, dust);
        }
    }

    private static float jitter() {
        return (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.12f;
    }

    // ---- palette ---------------------------------------------------------------------
    // Gold and bone-white: the balance, and the bandage over it.

    private static final TextColor GOLD = TextColor.color(0xE0B23A); // primary — the scales
    private static final TextColor BONE = TextColor.color(0xF4F2EC); // secondary — the bandage

    private static final TextColor GOLD_HUD = TextColor.color(0xE0B23A); // action-bar accent
    private static final TextColor BONE_HUD = TextColor.color(0xF4F2EC); // action-bar status

    // Particle colours, kept apart from the lore palette so tuning one never disturbs the other.
    private static final Color GOLD_RGB = Color.fromRGB(0xE0, 0xB2, 0x3A);
    private static final Color GOLD_DEEP_RGB = Color.fromRGB(0x8A, 0x6A, 0x18); // the shadow under the gold
    private static final Color BONE_RGB = Color.fromRGB(0xF4, 0xF2, 0xEC);

    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD_RGB, 1.1f);
    private static final Particle.DustOptions GOLD_FINE = new Particle.DustOptions(GOLD_RGB, 0.6f);
    private static final Particle.DustOptions BONE_DUST = new Particle.DustOptions(BONE_RGB, 1.0f);
    private static final Particle.DustOptions BONE_FINE = new Particle.DustOptions(BONE_RGB, 0.7f);
    private static final Particle.DustTransition GOLD_SHIMMER =
            new Particle.DustTransition(GOLD_RGB, BONE_RGB, 0.9f);

    // A verdict's stroke: bone-white where the blade entered and left, gold along the cut, and a deep
    // gold offset behind it so the stroke has a near side and a far one.
    private static final Particle.DustOptions CUT_TIP = new Particle.DustOptions(BONE_RGB, 1.8f);
    private static final Particle.DustOptions CUT_BODY = new Particle.DustOptions(GOLD_RGB, 1.5f);
    private static final Particle.DustOptions CUT_DEEP = new Particle.DustOptions(GOLD_DEEP_RGB, 0.9f);

    /** The scales' materials: a gold frame, bone-white pans. */
    private static final Material FRAME_MATERIAL = Material.GOLD_BLOCK;
    private static final Material PAN_MATERIAL = Material.QUARTZ_BLOCK;

    // ---- lore --------------------------------------------------------------------------

    /**
     * Built once and stamped in {@link #createItem}. The display name is the weapon (Justitia); the title
     * line is the Abnormality (Judgement Bird) — the house rule, and never the other way about.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Justitia",
            "Judgement Bird",
            GOLD,
            BONE,
            List.of(
                    "It remembers the balance of the Long",
                    "Bird that never forgot others' sins.",
                    "This weapon may be able to not only",
                    "cut flesh but trace of sins as well.",
                    "The employee who extracted this E.G.O",
                    "weapon was the most just person in the",
                    "company. Do not try to take off the",
                    "bandage. It wants to hide the sad",
                    "memories of the past. Like its",
                    "previous form, it seeks to bring peace."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Judgement",
                            "A landed hit weighs the sin.",
                            "40% — five cuts of 3 damage.",
                            "20% — a ten-hit verdict, 19.5 damage.",
                            "Either verdict rests for 15s. A hit",
                            "struck at full draw hurries the rest",
                            "along by a second."),
                    new EgoLore.Ability("[Passive] Indifference",
                            "Every 9th hit blinds the guilty with",
                            "Darkness for 5s. Judgement's own",
                            "cuts each count toward the ninth."),
                    new EgoLore.Ability("[Left-Click] Jurisdiction",
                            "Each landed blow tips the scales:",
                            "+1% to the five-cut chance, +0.5% to",
                            "the ten-hit. Resets when Judgement",
                            "passes a verdict. Swinging at nothing",
                            "weighs nothing."),
                    new EgoLore.Ability("[Right-Click] Persecution",
                            "Hang the scales behind you for 3s.",
                            "Strike the stance and you are rooted",
                            "as the bearer appears at your back,",
                            "blinded by Indifference. Rests 15s.")
            ));
}
