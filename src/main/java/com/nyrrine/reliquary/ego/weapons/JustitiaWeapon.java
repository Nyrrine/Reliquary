package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
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
 *   <li><b>Jurisdiction</b> (left-click, {@link #onSwing}) — every swing tips the scales a little
 *       further, ramping both proc coefficients. The ramp resets the instant a verdict is passed.</li>
 *   <li><b>Persecution</b> (right-click, {@link #onInteract}) — hang the scales behind you and take a
 *       counter-stance. Strike the stance and you are rooted while the bearer appears at your back,
 *       blinded by Indifference.</li>
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
 * <h2>Observing the counter without an onDamaged hook</h2>
 * The {@link Weapon} interface has no "I was struck" hook and the manager only dispatches
 * {@link #onHit} when the wielder is the <em>damager</em>, so Persecution watches from {@link #onTick}
 * instead: while the stance is open it samples the wielder's health each tick, and a drop is the strike
 * signal. Attribution then comes from {@link Player#getLastDamageCause()} — the real damager, not a
 * guess at whoever stood nearest — so no per-tick entity scan is needed at all. See the caveats on
 * {@link #watchForStrike}.
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
    // NOTE (balance): these totals sit deliberately above the netherite band (a plain netherite sword is
    // 8/hit, ~11 with Sharpness V). This is signed off and flagged for playtest — do not quietly rescale.
    // Damage is dealt through victim.damage(), so armour still reduces it; these are pre-mitigation.

    /** The five-hit verdict: 3 damage, five times = 15, on top of the swing that proc'd it. */
    private static final double[] FIVE_HIT_STEPS = {3.0, 3.0, 3.0, 3.0, 3.0};

    /** The ten-hit verdict: 5 damage three times (15), then 2 damage seven times (14) = 29 total. */
    private static final double[] TEN_HIT_STEPS = {5.0, 5.0, 5.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0};

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

    /** How far behind the striker the bearer appears, and the shorter fallbacks if that spot is walled. */
    private static final double[] COUNTER_BEHIND_DISTANCES = {1.6, 1.1, 0.6};

    /** Health drop (in half-hearts) that counts as a real strike rather than float noise. */
    private static final double HEALTH_EPSILON = 0.01;

    // ---- the scales: geometry ------------------------------------------------------

    private static final double SCALES_DISTANCE = 1.9;  // how far behind the wielder they hang
    private static final double SCALES_HEIGHT   = 1.1;  // hover height off the wielder's feet
    private static final double SCALES_BOB      = 0.06; // gentle vertical sway, so they read as suspended
    private static final double POST_HEIGHT     = 1.2;  // the central column
    private static final double BEAM_SPAN       = 1.5;  // the crossbeam, pan to pan
    private static final double PAN_DROP        = 0.42; // how far the pans hang beneath the beam ends

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
     * A swing of the greatsword. The manager only dispatches this while Justitia is in the main hand, so
     * every call is a real swing of it — hit or miss, the balance leans a little further. The ramp keeps
     * accumulating while Judgement sleeps (the spec gates the <em>procs</em> on the cooldown, not the
     * enhancer), which turns the 15s rest into ramp time rather than dead time.
     */
    @Override
    public void onSwing(Player player) {
        Wielder w = wielder(player.getUniqueId());
        if (w.swingStacks < MAX_BONUS_STACKS) w.swingStacks++;
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

            double dmg = steps[step++];
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
            cutFx(victim, dmg);

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
        w.lastHealth = player.getHealth(); // the baseline the strike-watch samples against
        EgoDurability.wearMainHand(player);
        summonFx(player);
    }

    /**
     * Watch for the wielder being struck while the stance is open. There is no onDamaged hook and the
     * manager only dispatches onHit when the wielder is the <em>damager</em>, so the strike signal is a
     * drop in the wielder's health between samples; attribution then comes from
     * {@link Player#getLastDamageCause()}, which names the actual damager rather than guessing at
     * whoever happened to stand nearest. No entity scan is needed at all.
     *
     * <p>Known limits of driving it this way, all of them deliberate: sampling is 2-tick granular, so
     * the counter answers up to 100ms after the blow; damage fully soaked by absorption or a raised
     * shield never moves health and so never answers; and damage that is not an entity's doing (fall,
     * poison, fire) correctly does not answer, but does consume the sample.
     *
     * @return true if the counter fired (and painted its own feedback)
     */
    private boolean watchForStrike(Player player, Wielder w, long now) {
        double hp = player.getHealth();
        if (hp >= w.lastHealth - HEALTH_EPSILON) {
            w.lastHealth = hp; // no strike; track healing too so the baseline never drifts high
            return false;
        }
        w.lastHealth = hp;

        LivingEntity striker = resolveStriker(player);
        if (striker == null) return false; // struck by the world, not a defendant — the stance holds

        fireCounter(player, w, striker, now);
        return true;
    }

    /**
     * Who struck the wielder, per the last damage cause: the damager itself, or a projectile's shooter.
     * Null if the blow was not a living entity's, was the wielder's own, or came from outside the
     * scales' {@link #COUNTER_RANGE jurisdiction}.
     */
    private LivingEntity resolveStriker(Player player) {
        if (!(player.getLastDamageCause() instanceof EntityDamageByEntityEvent e)) return null;

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
        player.teleport(behindOf(striker));
        applyIndifference(striker); // "This counter move triggers Indifference"

        counterFx(from, player, striker);
    }

    /**
     * The spot at the striker's back, facing the way they face. Walked inward through
     * {@link #COUNTER_BEHIND_DISTANCES} until a spot with clear head and feet is found, so the counter
     * never files the bearer into a wall; if every candidate is walled, their own footprint is used.
     */
    private Location behindOf(LivingEntity striker) {
        Location at = striker.getLocation();
        Vector facing = at.getDirection().setY(0);
        facing = facing.lengthSquared() < 1.0e-6 ? new Vector(0, 0, 1) : facing.normalize();

        for (double dist : COUNTER_BEHIND_DISTANCES) {
            Location spot = at.clone().subtract(facing.clone().multiply(dist));
            spot.setYaw(at.getYaw()); // shoulder to shoulder with their facing — you are behind them
            spot.setPitch(0f);
            if (isClear(spot)) return spot;
        }
        Location fallback = at.clone();
        fallback.setPitch(0f);
        return fallback;
    }

    /** True if a body fits here — feet and head both passable. */
    private static boolean isClear(Location l) {
        return l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable();
    }

    /** Root a countered striker for {@link #ROOT_TICKS}, cutting mob AI for the hold. */
    private void rootStriker(LivingEntity striker) {
        UUID id = striker.getUniqueId();
        if (!rooted.add(id)) return; // already held — don't stack tasks or double-touch the AI flag

        boolean hadAi = false;
        if (striker instanceof Mob mob) {
            hadAi = mob.hasAI();
            mob.setAI(false);
        }
        RootTask task = new RootTask(striker, hadAi);
        activeRoots.add(task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The hold. Each tick it zeroes the target's velocity (killing walk, sprint and jump alike) and
     * re-applies a crushing Slowness, painting the gold shackle at their feet.
     *
     * <p><b>Platform limit, flagged:</b> a player cannot be truly frozen server-side without movement
     * packets, so against players this is a best-effort root — per-tick velocity zero + Slowness VII. A
     * determined player can still nudge themselves, barely. Mobs additionally lose AI for the duration,
     * restored on the way out (guarded, so it restores even if they die mid-hold).
     */
    private final class RootTask extends BukkitRunnable {
        private final LivingEntity target;
        private final boolean restoreAi;
        private int ticks = 0;

        private RootTask(LivingEntity target, boolean restoreAi) {
            this.target = target;
            this.restoreAi = restoreAi;
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
            rootVfx(target.getLocation());
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
            if (restoreAi && target instanceof Mob mob && target.isValid()) mob.setAI(true);
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
            w.scales.tick(player, tick);
            if (now >= w.stanceEndsAt) {
                closeStance(w, now, true); // lapsed unanswered — the rest is still spent, so it can't be spammed
                lapseFx(player);
            } else if (watchForStrike(player, w, now)) {
                return true; // the counter fired and painted its own feedback
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

        sweepOrphans(); // belt-and-braces: reap any stray tagged scale anywhere in the world
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
        /** The wielder's health at the last stance sample — a drop is the strike signal. */
        double lastHealth = 0.0;

        boolean stanceOpen() {
            return scales != null;
        }
    }

    // ---- the scales: the visual signature --------------------------------------------

    /**
     * The scales of the Long Bird, hanging in the air behind the wielder: a gold column, a gold crossbeam,
     * and two bone-white pans swaying beneath its ends. Four {@link BlockDisplay} entities — non-persistent
     * and tagged, so a crash can never leave them on disk and the world sweep can always find them.
     *
     * <p>Each display is teleported to its slot every 2 ticks with a matching teleport duration, which the
     * client interpolates into a smooth float. The beam is scaled along its own local X axis and carries
     * the wielder's yaw, so it always lies across their back rather than pointing at it.
     */
    private static final class Scales {

        private final BlockDisplay post;
        private final BlockDisplay beam;
        private final BlockDisplay panLeft;
        private final BlockDisplay panRight;
        private boolean alive = true;

        Scales(Player owner) {
            Location at = owner.getLocation().add(0, SCALES_HEIGHT, 0);
            World world = owner.getWorld();
            post     = spawn(world, at, FRAME_MATERIAL, bar(0.09f, (float) POST_HEIGHT, 0.09f));
            beam     = spawn(world, at, FRAME_MATERIAL, bar((float) BEAM_SPAN, 0.08f, 0.08f));
            panLeft  = spawn(world, at, PAN_MATERIAL,   bar(0.44f, 0.06f, 0.44f));
            panRight = spawn(world, at, PAN_MATERIAL,   bar(0.44f, 0.06f, 0.44f));
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
            return new Transformation(
                    new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f),
                    new Quaternionf(),
                    new Vector3f(sx, sy, sz),
                    new Quaternionf());
        }

        /** Hang the scales behind the wielder, swaying gently, and weep a little gold from the pans. */
        void tick(Player owner, long tick) {
            if (!alive) return;

            Location base = owner.getLocation();
            float yaw = base.getYaw();
            double rad = Math.toRadians(yaw);
            Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector right = new Vector(Math.cos(rad), 0, Math.sin(rad));

            double bob = Math.sin(tick * 0.10) * SCALES_BOB;
            Location centre = base.clone()
                    .subtract(forward.multiply(SCALES_DISTANCE))
                    .add(0, SCALES_HEIGHT + bob, 0);

            place(post, centre, yaw);
            Location beamAt = centre.clone().add(0, POST_HEIGHT / 2.0, 0);
            place(beam, beamAt, yaw);

            Location leftAt = beamAt.clone()
                    .add(right.clone().multiply(-BEAM_SPAN / 2.0)).add(0, -PAN_DROP, 0);
            Location rightAt = beamAt.clone()
                    .add(right.clone().multiply(BEAM_SPAN / 2.0)).add(0, -PAN_DROP, 0);
            place(panLeft, leftAt, yaw);
            place(panRight, rightAt, yaw);

            if ((tick % 4) == 0) {
                // The chains: a thin thread of gold from each beam end down to its pan.
                chain(beamAt.clone().add(right.clone().multiply(-BEAM_SPAN / 2.0)), leftAt);
                chain(beamAt.clone().add(right.clone().multiply(BEAM_SPAN / 2.0)), rightAt);
            }
            if ((tick % 8) == 0) {
                World w = centre.getWorld();
                w.spawnParticle(Particle.DUST, centre, 2, 0.10, 0.35, 0.10, 0, GOLD_FINE);
                w.spawnParticle(Particle.END_ROD, beamAt, 1, 0.30, 0.02, 0.30, 0.004);
            }
        }

        /** A few motes strung between a beam end and the pan below it — the chain, implied. */
        private static void chain(Location top, Location pan) {
            World w = top.getWorld();
            for (int i = 1; i <= 3; i++) {
                Location p = top.clone().add(
                        (pan.getX() - top.getX()) * i / 4.0,
                        (pan.getY() - top.getY()) * i / 4.0,
                        (pan.getZ() - top.getZ()) * i / 4.0);
                w.spawnParticle(Particle.DUST, p, 1, 0.01, 0.01, 0.01, 0, GOLD_FINE);
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
            for (BlockDisplay d : new BlockDisplay[]{post, beam, panLeft, panRight}) {
                if (d != null && d.isValid()) d.remove();
            }
        }
    }

    // ---- presentation ----------------------------------------------------------------

    /** The scales come down: a bell, a chain, and a ring of gold about the wielder. */
    private void summonFx(Player player) {
        World w = player.getWorld();
        Location at = player.getLocation();
        w.playSound(at, Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.7f);
        w.playSound(at, Sound.BLOCK_BELL_USE, 0.7f, 0.6f);
        ring(at.clone().add(0, 0.08, 0), 1.4, GOLD_DUST, 22);
    }

    /**
     * The stance settles unanswered: a single low, disappointed chime. Deliberately no action bar —
     * {@link #showBalance} paints the very same tick and would swallow any message written here.
     */
    private void lapseFx(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.5f, 0.6f);
    }

    /** A verdict opens: the toll of the bell, weighted by how heavy the sentence is. */
    private void verdictOpenFx(Player attacker, LivingEntity victim, int cuts) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        boolean heavy = cuts >= TEN_HIT_STEPS.length;

        w.playSound(at, Sound.BLOCK_BELL_USE, heavy ? 1.0f : 0.7f, heavy ? 0.5f : 0.9f);
        if (heavy) w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, heavy ? 24 : 14,
                0.35, 0.45, 0.35, 0, GOLD_SHIMMER);
        ring(victim.getLocation().add(0, 0.06, 0), heavy ? 1.5 : 1.1, GOLD_DUST, heavy ? 20 : 14);
        attacker.playSound(attacker.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.5f, heavy ? 0.7f : 1.0f);
    }

    /** One cut of a verdict landing — the heavier steps bite louder. */
    private void cutFx(LivingEntity victim, double dmg) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        boolean heavy = dmg >= 5.0;

        w.playSound(at, heavy ? Sound.ENTITY_PLAYER_ATTACK_CRIT : Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                heavy ? 0.8f : 0.5f, 0.8f + jitter());
        w.spawnParticle(Particle.DUST, at, heavy ? 10 : 5, 0.28, 0.32, 0.28, 0, GOLD_DUST);
        w.spawnParticle(Particle.CRIT, at, heavy ? 8 : 4, 0.22, 0.28, 0.22, 0.12);
        if (heavy) w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.10, 0.10, 0.10, 0);
    }

    /** Indifference takes hold: the bone-white blindness, and a bell no one answers. */
    private void indifferenceFx(LivingEntity victim) {
        World w = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.75, 0);
        w.playSound(at, Sound.BLOCK_BELL_RESONATE, 0.7f, 0.5f);
        w.spawnParticle(Particle.DUST, at, 14, 0.30, 0.35, 0.30, 0, BONE_DUST);
        w.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.25, 0.30, 0.25, 0.02);
    }

    /** The counter lands: the bearer gone from where they stood, and arrived at the guilty one's back. */
    private void counterFx(Location from, Player player, LivingEntity striker) {
        World w = striker.getWorld();
        Location at = striker.getLocation().add(0, striker.getHeight() * 0.6, 0);

        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.6f);
        from.getWorld().spawnParticle(Particle.DUST, from.clone().add(0, 1.0, 0), 16,
                0.28, 0.55, 0.28, 0, GOLD_DUST);

        w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f);          // the weight of the sentence
        w.playSound(at, Sound.BLOCK_BELL_USE, 1.0f, 0.5f);            // the toll
        w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.6f, 0.5f);         // the strike, turned aside
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 26, 0.40, 0.50, 0.40, 0, GOLD_SHIMMER);
        w.spawnParticle(Particle.ENCHANTED_HIT, at, 10, 0.30, 0.35, 0.30, 0.10);
        ring(striker.getLocation().add(0, 0.06, 0), 1.6, GOLD_DUST, 24);

        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);
    }

    /** The shackle: a tight gold ring dragging at a rooted target's feet. */
    private void rootVfx(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.DUST, at.clone().add(0, 0.25, 0), 3, 0.26, 0.20, 0.26, 0, GOLD_FINE);
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
    // AUDIT: DUST -> DustOptions, DUST_COLOR_TRANSITION -> DustTransition. Every spawnParticle below
    // passes the data class its particle actually demands (a mismatch is a runtime crash, not a compile
    // error); END_ROD / CRIT / SWEEP_ATTACK / ENCHANTED_HIT / ELECTRIC_SPARK take no data at all.
    private static final Color GOLD_RGB = Color.fromRGB(0xE0, 0xB2, 0x3A);
    private static final Color BONE_RGB = Color.fromRGB(0xF4, 0xF2, 0xEC);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD_RGB, 1.1f);
    private static final Particle.DustOptions GOLD_FINE = new Particle.DustOptions(GOLD_RGB, 0.6f);
    private static final Particle.DustOptions BONE_DUST = new Particle.DustOptions(BONE_RGB, 1.0f);
    private static final Particle.DustTransition GOLD_SHIMMER =
            new Particle.DustTransition(GOLD_RGB, BONE_RGB, 0.9f);

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
                            "20% — a ten-hit verdict, 29 damage.",
                            "Either verdict rests for 15s."),
                    new EgoLore.Ability("[Passive] Indifference",
                            "Every 9th hit blinds the guilty with",
                            "Darkness for 5s. Judgement's own",
                            "cuts each count toward the ninth."),
                    new EgoLore.Ability("[Left-Click] Jurisdiction",
                            "Each swing tips the scales further:",
                            "+1% to the five-cut chance, +0.5% to",
                            "the ten-hit. Resets when Judgement",
                            "passes a verdict."),
                    new EgoLore.Ability("[Right-Click] Persecution",
                            "Hang the scales behind you for 3s.",
                            "Strike the stance and you are rooted",
                            "as the bearer appears at your back,",
                            "blinded by Indifference. Rests 15s.")
            ));
}
