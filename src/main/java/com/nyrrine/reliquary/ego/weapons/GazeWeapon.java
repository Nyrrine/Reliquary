package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gaze — Schadenfreude. A WAW-tier Lobotomy Corp E.G.O weapon: a blade carried by something that watches
 * through a keyhole and is <em>delighted</em> by what it sees. It never blinks, so nothing gets behind
 * you; it never looks away, so the longer you keep hurting things the happier it gets.
 *
 * <p><b>It is a poor sword and a superb one, depending entirely on what you have fed it.</b>
 * {@link EgoModels#GAZE} is stamped at a feeble 2.4 atk / 1.6 spd, and <b>Constant Surveillance</b> lands
 * every attack twice — but only the first cut carries your enchantments; the second is the bare blade
 * (see {@link #BARE_CUT}). Bare, a swing is 4.8 against a netherite sword's 8, and it should feel like
 * it. Sharpened, and only once Delight is full, it reaches 10.92 — level with the band, never past it.
 * The doubling <em>is</em> the damage: nothing here adds flat bonus damage on top of the base, and
 * nothing should be added later to "fix" the low number on the tooltip.
 *
 * <ul>
 *   <li><b>[Passive] Schadenfreude</b> — every attack feeds the watcher a stack of <b>Delight</b>
 *       ({@link #DAMAGE_PER_STACK} more damage each, capped at {@link #MAX_DELIGHT} = +40%). Go
 *       {@link #DECAY_GRACE_MS} without landing a hit and it loses interest: stacks then <em>drain</em>
 *       steadily, one per {@link #DECAY_INTERVAL_MS}, rather than vanishing all at once (see the decay
 *       note below). Delight is per-wielder, never per-victim.</li>
 *   <li><b>[Left Click] Constant Surveillance</b> — attacks hit 2 times. The vanilla swing is hit one
 *       (scaled by Delight via {@code event.setDamage}); hit two is a scripted follow-up
 *       {@link #FOLLOW_UP_DELAY_TICKS} ticks later carrying {@link #BARE_CUT} — the blade alone, lifted
 *       by Delight but never by an enchantment. One stack per <em>attack</em>, not per hit — the
 *       follow-up feeds nothing.</li>
 *   <li><b>[Right Click] Fixed Stare</b> — for {@link #FIXED_STARE_MS} the watcher refuses to look away:
 *       Delight cannot decay and builds at {@link #STARE_STACK_MULT}x. {@link #FIXED_STARE_COOLDOWN_MS}
 *       cooldown, read on the action bar in whole seconds.</li>
 *   <li><b>[Shift + Right Click] Lingering Gaze</b> — marks the enemy in the crosshair; for
 *       {@link #LINGER_TICKS}s it takes {@link #LINGER_DAMAGE} a second and feeds the wielder
 *       {@link #LINGER_DELIGHT_PER_TICK} Delight a tick. The tick damage is fenced through the framework's
 *       {@code dealing()}, so it neither scripts a hit two nor feeds a second stack. {@link
 *       #LINGER_COOLDOWN_MS} cooldown.</li>
 * </ul>
 *
 * <p><b>Two mechanics carry this whole weapon and both are easy to get silently wrong:</b>
 *
 * <p><i>i-frames.</i> Vanilla stamps the victim with 20 ticks of hurt-immunity <em>before</em> the damage
 * event fires, and a same-size follow-up inside that window is swallowed whole (the "multi-hit that only
 * lands once"). {@link #strikeAgain} therefore zeroes {@code noDamageTicks} immediately before hit two —
 * and, just as importantly, <em>puts them back</em> to where a single swing would have left them
 * ({@link #VANILLA_HIT_IFRAMES} minus the follow-up's delay). Without that restore the follow-up's own
 * fresh 20 ticks would outlast the wielder's 1.6-speed swing timer (12.5 ticks) and silently eat every
 * other swing. The victim ends up exactly as protected as one ordinary hit would have left them.
 *
 * <p><i>Re-entrancy.</i> Hit two is dealt with {@code victim.damage(..., attacker)}, which is a melee hit
 * and re-enters {@link #onHit} through the manager's dispatch. The {@link #reentry} fence (keyed by
 * wielder, held only across the damage call) makes that re-entry a no-op — otherwise every swing doubles
 * into a doubling into a doubling and the server locks up on the first mob.
 *
 * <p>Costs nothing when idle: no entities are ever spawned (the watcher is only ever particles and sound),
 * state is a single per-wielder record plus at most a couple of two-tick follow-up tasks, decay is
 * computed lazily from a timestamp rather than by a ticking task, and {@link #onTick} disengages the
 * moment the sword leaves the main hand.
 */
public final class GazeWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Gaze. */
    private final NamespacedKey key;

    // ---- Delight tuning -----------------------------------------------------------

    /** Damage added per stack of Delight. 0.02 -> +2% each, +40% at the cap. */
    private static final double DAMAGE_PER_STACK = 0.02;
    /** Hard cap on Delight. 20 stacks -> +40%. */
    private static final int MAX_DELIGHT = 20;

    /**
     * What hit two carries: the blade's own stamped damage ({@link EgoModels#GAZE}), with no enchantment
     * on it. Delight still lifts it; Sharpness never does.
     *
     * <p>This is the load-bearing number of the whole weapon, so it is worth saying why. An enchantment
     * adds a flat amount to <em>each</em> instance of damage, and Gaze deals two — so a follow-up that
     * copied hit one would pay Sharpness V's +3 twice, and a swing at full Delight would reach 18 against
     * a netherite sword's 11. Paying it once is what lets the base stay honest: bare, a swing is
     * {@code 2 x 2.4 = 4.8}, feeble next to a netherite sword's 8, which is the point — the thing behind
     * the keyhole is not much of a swordsman. Sharpened and fully delighted it reaches
     * {@code 1.4 x (5.4 + 2.4) = 10.92}, level with the band and never past it. The weapon is weak in the
     * hand and dangerous only once you have fed it.
     */
    private static final double BARE_CUT = EgoModels.GAZE.atk();
    /** Stacks fed to the watcher by one attack. One per ATTACK — the follow-up hit feeds nothing. */
    private static final int STACKS_PER_ATTACK = 1;

    /** Go this long without LANDING a hit and the watcher starts losing interest. Spec: 3 seconds. */
    private static final long DECAY_GRACE_MS = 3_000L;
    /**
     * Once the grace has lapsed, Delight drains one stack per this long — a steady bleed-off rather than
     * an all-at-once wipe. A full 20 stacks takes 10s to drain, so a wielder who breaks off to reposition
     * comes back with most of their Delight intact; the spec left the shape open and this is the kinder,
     * more readable of the two (the gauge visibly slides instead of blinking to empty).
     */
    private static final long DECAY_INTERVAL_MS = 500L;

    /**
     * ENCHANT — Fixation (custom id {@code "fixation"}): retention, NOT a bigger ceiling. Each level adds
     * {@value #FIXATION_GRACE_PER_LEVEL} ms to the decay grace (capped at {@link #FIXATION_CAP} levels), so
     * the watcher holds its Delight longer between hits and reaches/keeps +40% more easily. Full Delight is
     * still {@code MAX_DELIGHT} × +2% = +40%, untouched — Gaze never moves past the netherite band.
     * PLACEHOLDER values for the balance wave.
     */
    private static final long FIXATION_GRACE_PER_LEVEL = 1_500L;
    private static final int  FIXATION_CAP = 2;

    /**
     * ENCHANT — Gloat (vanilla {@link Enchantment#FIRE_ASPECT}): the watcher savours the wound. Each Fire
     * Aspect level lays {@value #GLOAT_PER_LEVEL_TICKS} ticks (1s) of fire on the struck body, capped at
     * {@link #GLOAT_CAP} levels — a 3s burn at most. Duration only: it extends how long they burn and adds
     * no weapon damage, so it can't lift Gaze past the netherite band. Mirrors GreenStem's Rot exactly, the
     * same read-and-set pattern QA already blessed; applied once per attack, never on hit two.
     * PLACEHOLDER values for the balance wave.
     */
    private static final int GLOAT_PER_LEVEL_TICKS = 20;
    private static final int GLOAT_CAP = 3;

    // ---- Constant Surveillance tuning ---------------------------------------------

    /**
     * How long after the vanilla swing hit two lands. Two ticks reads as a double-tap rather than one
     * mushed hit, and must stay under ~2.5: hit two re-stamps the victim's i-frames, and the wielder's
     * next swing at 1.6 attack speed arrives at ~12.5 ticks — see {@link #VANILLA_HIT_IFRAMES}.
     */
    private static final long FOLLOW_UP_DELAY_TICKS = 2L;
    /**
     * The hurt-immunity vanilla stamps on a struck entity. After the follow-up we restore the victim to
     * this minus the follow-up's delay, i.e. exactly the i-frame curve a single swing would have left —
     * the second hit must not buy the victim extra invulnerability against anyone.
     */
    private static final int VANILLA_HIT_IFRAMES = 20;

    // ---- Fixed Stare tuning -------------------------------------------------------

    /** The window during which the watcher refuses to blink: no decay, double stacking. Spec: 5 seconds. */
    private static final long FIXED_STARE_MS = 5_000L;
    /** Fixed Stare cooldown. Spec: 15 seconds. Displayed in whole seconds, never milliseconds. */
    private static final long FIXED_STARE_COOLDOWN_MS = 15_000L;
    /** Stack rate multiplier inside the Fixed Stare window — "builds twice as fast". */
    private static final int STARE_STACK_MULT = 2;

    // ---- Lingering Gaze tuning (PLACEHOLDERS — flagged for Nyrrine's balance wave) --

    /** PLACEHOLDER: damage each one-second tick of the mark deals to the watched enemy. */
    private static final double LINGER_DAMAGE = 1.0;
    /** PLACEHOLDER: Delight the wielder gains per tick of the mark. */
    private static final int LINGER_DELIGHT_PER_TICK = 1;
    /** PLACEHOLDER: how many one-second ticks the mark lingers (5s). */
    private static final int LINGER_TICKS = 5;
    /** One second between ticks. */
    private static final long LINGER_INTERVAL_TICKS = 20L;
    /** PLACEHOLDER: cooldown between casts of Lingering Gaze. */
    private static final long LINGER_COOLDOWN_MS = 10_000L;
    /** PLACEHOLDER: how far the crosshair reaches to catch a target, in blocks. */
    private static final int LINGER_RANGE = 20;

    // ---- state (O(wielders); no victim-keyed maps to leak) ------------------------

    /** Wielder -> their watcher's state. Players only, so quit/disable prune it completely. */
    private final Map<UUID, Delight> states = new HashMap<>();

    /**
     * Wielders whose follow-up hit is resolving right now. That {@code victim.damage} re-enters
     * {@link #onHit}; while the id sits in here onHit is a no-op, so a swing can never double itself
     * again or feed a second stack. Held only across the damage call, in a try/finally.
     */
    private final Set<UUID> reentry = new HashSet<>();

    /** Follow-up strikes in flight (each lives {@link #FOLLOW_UP_DELAY_TICKS}); cancelled on disable. */
    private final Set<FollowUp> followUps = new HashSet<>();

    /**
     * One wielder's watcher.
     *
     * <p>Decay is lazy: nothing ticks it down. {@link #decayAnchor} is the moment the watcher last had
     * its appetite fed (a landed hit), and {@link #delight} works out how much has drained since on every
     * read. That keeps the weapon free when sheathed and keeps stacks correct across a hotbar swap, with
     * no task and no clock of our own.
     */
    private static final class Delight {
        /** Current stacks, already drained up to the last {@link #delight} read. */
        private int stacks;
        /** Epoch-ms the decay grace is measured from — bumped on every landed attack. */
        private long decayAnchor;
        /** Epoch-ms the Fixed Stare window ends. Decay is frozen until then. */
        private long stareUntil;
        /** Epoch-ms Fixed Stare may be cast again. */
        private long cooldownReadyAt;
        /** Epoch-ms Lingering Gaze may be cast again. */
        private long lingerReadyAt;
        /** Extra decay-grace ms lent by the Fixation enchant, refreshed each tick from the held item. */
        private long fixationGraceMs;
    }

    public GazeWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "gaze");
    }

    @Override
    public String id() {
        return "gaze";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.GAZE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.GAZE.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.GAZE);

        item.setItemMeta(meta);
        return item;
    }

    // ---- the Delight ledger --------------------------------------------------------

    /** This wielder's watcher, created on first sight of them. */
    private Delight state(UUID id) {
        return states.computeIfAbsent(id, k -> {
            Delight d = new Delight();
            d.decayAnchor = System.currentTimeMillis();
            return d;
        });
    }

    /**
     * The wielder's Delight right now, draining anything the clock has taken since the last read.
     *
     * <p>Decay is measured from the later of {@link Delight#decayAnchor} (their last landed hit) and
     * {@link Delight#stareUntil} (the end of a Fixed Stare) — which is what makes "cannot decay" during
     * the stare fall out for free, with no task and no special-casing: while the window is open, the
     * point decay would start from is still in the future.
     *
     * <p>The anchor is advanced by whole drained steps only, so the leftover fraction of an interval
     * carries into the next read and the drain stays smooth instead of double-charging or stalling.
     */
    private int delight(Delight d, long now) {
        long from = Math.max(d.decayAnchor, d.stareUntil);
        if (now <= from) return d.stacks;               // inside the stare window — frozen

        long grace = DECAY_GRACE_MS + d.fixationGraceMs; // Fixation holds the interest longer
        long idle = now - from;
        if (idle <= grace) return d.stacks;             // still within the grace — the watcher waits

        long steps = (idle - grace) / DECAY_INTERVAL_MS;
        if (steps <= 0) return d.stacks;

        d.stacks = (int) Math.max(0L, d.stacks - steps);
        d.decayAnchor = d.stacks == 0 ? now : from + steps * DECAY_INTERVAL_MS;
        return d.stacks;
    }

    /** Feed the watcher this attack's stacks (double inside a Fixed Stare) and reset the decay grace. */
    private void feed(Delight d, long now) {
        int gain = now < d.stareUntil ? STACKS_PER_ATTACK * STARE_STACK_MULT : STACKS_PER_ATTACK;
        d.stacks = Math.min(MAX_DELIGHT, d.stacks + gain);
        d.decayAnchor = now; // a landed hit — the watcher is fed, the 3s grace restarts
    }

    // ---- [Left Click] Constant Surveillance -----------------------------------------

    /**
     * A melee hit landed. The vanilla swing is hit one — we only scale it by the wielder's Delight (the base
     * is the E.G.O's own stamped damage, deliberately below a sword's; see the class docs) — and hit two is
     * scheduled a couple of ticks behind it as a scripted {@link #BARE_CUT} strike: the blade's own damage,
     * carrying no enchantment, <em>not</em> a copy of hit one. The attack then feeds the watcher one stack.
     *
     * <p>The multiplier is snapshotted <em>before</em> this attack's own stack lands, so both halves of a
     * swing hit for the same number and a stack earned here pays out from the next attack on. Our own
     * follow-up re-enters here through the manager's dispatch and is fenced out by {@link #reentry}: it
     * neither doubles again nor feeds a stack.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        if (reentry.contains(aid)) return; // our own follow-up — never doubles or feeds from within

        long now = System.currentTimeMillis();
        Delight d = state(aid);

        int stacks = delight(d, now);
        double rate = 1.0 + stacks * DAMAGE_PER_STACK;
        event.setDamage(event.getDamage() * rate);   // hit one — the vanilla swing, enchants and all

        // Hit two is the watcher's own cut and knows nothing of your enchantments — it carries the bare
        // blade, never what you laid on it. That asymmetry is the whole balance; see BARE_CUT.
        scheduleFollowUp(attacker, victim, BARE_CUT * rate);

        boolean wasCapped = stacks >= MAX_DELIGHT;
        feed(d, now);
        if (!wasCapped && d.stacks >= MAX_DELIGHT) delightedCue(attacker);

        applyGloat(attacker, victim); // once per attack — the follow-up is fenced out at the top of onHit
        watchedFx(victim);
        sendDelightBar(attacker, now);
    }

    /**
     * ENCHANT — Gloat (vanilla {@link Enchantment#FIRE_ASPECT}): lay a short fire on the struck body for the
     * blade held right now — one second per Fire Aspect level, capped at {@link #GLOAT_CAP}. Only extends how
     * long they burn (never weapon damage), and only when the blade carries Fire Aspect; off the enchant it
     * does nothing. Delight and the two-cut balance are left wholly untouched.
     */
    private void applyGloat(Player attacker, LivingEntity victim) {
        int fa = Math.min(GLOAT_CAP,
                attacker.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.FIRE_ASPECT));
        if (fa <= 0) return;
        int ticks = GLOAT_PER_LEVEL_TICKS * fa;
        if (victim.getFireTicks() < ticks) victim.setFireTicks(ticks);
    }

    /** Queue hit two. Kept in {@link #followUps} so a shutdown mid-flight can cancel it. */
    private void scheduleFollowUp(Player attacker, LivingEntity victim, double damage) {
        FollowUp strike = new FollowUp(attacker.getUniqueId(), victim, damage);
        followUps.add(strike);
        strike.runTaskLater(plugin, FOLLOW_UP_DELAY_TICKS);
    }

    /** Hit two of one attack: the same strike again, a breath later, from the thing behind the keyhole. */
    private final class FollowUp extends BukkitRunnable {
        private final UUID attackerId;
        private final LivingEntity victim;
        private final double damage;

        private FollowUp(UUID attackerId, LivingEntity victim, double damage) {
            this.attackerId = attackerId;
            this.victim = victim;
            this.damage = damage;
        }

        @Override
        public void run() {
            followUps.remove(this);
            Player attacker = plugin.getServer().getPlayer(attackerId);
            if (attacker == null || victim.isDead() || !victim.isValid()) return; // hit one already finished it
            strikeAgain(attacker, victim, damage);
        }
    }

    /**
     * Land hit two.
     *
     * <p>Hit one left the victim stamped with {@link #VANILLA_HIT_IFRAMES} ticks of hurt-immunity, and an
     * equal-size hit inside that window is swallowed entirely — so the i-frames are zeroed immediately
     * before the strike. They are then restored to the curve a <em>single</em> swing would have left
     * (20 minus the ticks we waited), which is the part that is easy to miss: leaving the follow-up's own
     * fresh 20 ticks in place would run past the wielder's ~12.5-tick swing timer and silently swallow
     * every other swing, and would hand the victim free invulnerability against everyone else too.
     *
     * <p>Knockback is undone: the swing already shoved them once, and Constant Surveillance is a second
     * <em>cut</em>, not a second shove. Damage goes through {@code victim.damage(...)} so other plugins
     * can still cancel it, inside the {@link #reentry} fence so it cannot recurse.
     *
     * <p>No durability is taken here on purpose. The follow-up is part of an ordinary melee swing, not an
     * ability use, and vanilla already charged the swing its point of wear — charging again would wear
     * Gaze at twice the rate of every other sword in the vault.
     */
    private void strikeAgain(Player attacker, LivingEntity victim, double damage) {
        UUID aid = attacker.getUniqueId();

        victim.setNoDamageTicks(0);            // or hit one's i-frames eat this strike whole
        Vector preVel = victim.getVelocity();  // a second cut, not a second shove

        reentry.add(aid); // fence: this damage re-enters onHit through the manager's dispatch
        try {
            if (!attacker.equals(victim)) {
                victim.damage(damage, attacker);
            } else {
                victim.damage(damage);
            }
        } finally {
            reentry.remove(aid);
            victim.setVelocity(preVel);
            // Leave the victim exactly as protected as one swing would have: the follow-up must not
            // extend their invulnerability past the wielder's next swing (or anyone else's).
            victim.setNoDamageTicks(Math.max(0, VANILLA_HIT_IFRAMES - (int) FOLLOW_UP_DELAY_TICKS));
        }

        secondCutFx(victim);
    }

    // ---- [Right Click] Fixed Stare --------------------------------------------------

    /**
     * Right-click: for {@link #FIXED_STARE_MS} the watcher fixes on you and will not look away — Delight
     * cannot decay and every attack feeds it double. On cooldown this is a quiet no-op with the remaining
     * wait in whole seconds. Opening the stare is an ability use, so it wears the main hand.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        if (sneaking) {
            lingeringGaze(player);
            return;
        }

        long now = System.currentTimeMillis();
        Delight d = state(player.getUniqueId());

        if (now < d.cooldownReadyAt) {
            sendDelightBar(player, now); // the composed line already carries the Fixed Stare rest
            player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.35f, 0.6f);
            return;
        }

        delight(d, now);                    // settle any pending drain before the window freezes it
        d.stareUntil = now + FIXED_STARE_MS;
        d.cooldownReadyAt = now + FIXED_STARE_COOLDOWN_MS;

        EgoDurability.wearMainHand(player); // an ability use — vanilla swings wear on their own

        openKeyholeFx(player);
        sendDelightBar(player, now);
    }

    // ---- [Shift + Right Click] Lingering Gaze ---------------------------------------

    /**
     * Shift-right-click marks the enemy in the wielder's crosshair. For {@link #LINGER_TICKS} seconds the
     * mark deals {@link #LINGER_DAMAGE} a second and feeds the wielder {@link #LINGER_DELIGHT_PER_TICK}
     * Delight a tick, on its own {@link #LINGER_COOLDOWN_MS} cooldown. A quiet no-op with nothing in sight or
     * on cooldown.
     *
     * <p>The tick damage is routed through the framework's {@code dealing()} fence, so it never re-enters
     * {@link #onHit} as a swing — no scripted hit two, and no second Delight stack from the passive. The
     * tick's own +1 is the only Delight it grants.
     */
    private void lingeringGaze(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Delight d = state(id);

        if (now < d.lingerReadyAt) {
            sendDelightBar(player, now); // the composed line already carries the Lingering Gaze rest
            player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.35f, 0.6f);
            return;
        }

        LivingEntity target = crosshairTarget(player);
        if (target == null) {
            player.sendActionBar(EgoHud.status("Lingering Gaze: no one in sight", FAINT_HUD));
            player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.35f, 0.6f);
            return;
        }

        d.lingerReadyAt = now + LINGER_COOLDOWN_MS;
        EgoDurability.wearMainHand(player); // an ability use — vanilla swings wear on their own
        markFx(player, target);
        startLinger(player, target);
    }

    /** The living thing in the wielder's crosshair within {@link #LINGER_RANGE}, line-of-sight aware, else null. */
    private LivingEntity crosshairTarget(Player player) {
        if (player.getTargetEntity(LINGER_RANGE) instanceof LivingEntity le
                && !le.equals(player) && !le.isDead() && le.isValid()) {
            return le;
        }
        return null;
    }

    /**
     * Run the mark: one tick a second for {@link #LINGER_TICKS} seconds. Each tick deals fenced damage and
     * grants a stack; it ends early if the target dies or leaves the world, or the wielder logs off. Holds
     * only the two entities for its short life, and the scheduler cancels it on plugin disable.
     */
    private void startLinger(Player player, LivingEntity target) {
        UUID id = player.getUniqueId();
        new BukkitRunnable() {
            int done = 0;

            @Override
            public void run() {
                if (done >= LINGER_TICKS || !player.isOnline() || target.isDead() || !target.isValid()) {
                    cancel();
                    return;
                }
                done++;

                // Fenced: onHit does not see this, so it neither scripts a hit two nor feeds a second stack.
                plugin.weapons().dealing(id, () -> target.damage(LINGER_DAMAGE, player));

                long now = System.currentTimeMillis();
                Delight d = state(id);
                delight(d, now);                                              // settle pending drain first
                d.stacks = Math.min(MAX_DELIGHT, d.stacks + LINGER_DELIGHT_PER_TICK);
                d.decayAnchor = now;                                          // the mark is landing — keep fed
                lingerTickFx(target);
            }
        }.runTaskTimer(plugin, LINGER_INTERVAL_TICKS, LINGER_INTERVAL_TICKS);
    }

    /** The watcher fixing on a fresh mark: an enderman's stare and a grey iris blooming over the target. */
    private void markFx(Player player, LivingEntity target) {
        World world = target.getWorld();
        Location eyes = target.getLocation().add(0, target.getHeight() * 0.9, 0);
        world.playSound(eyes, Sound.ENTITY_ENDERMAN_STARE, 0.7f, 0.9f);
        world.playSound(player.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.4f, 0.7f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eyes, 12, 0.25, 0.3, 0.25, 0.0,
                new Particle.DustTransition(IRIS_GREY, KEYHOLE_DARK, 1.3f));
    }

    /** A quiet tick of the lingering mark: a wet grey glint and a wisp of the keyhole's shadow. */
    private void lingerTickFx(LivingEntity target) {
        World world = target.getWorld();
        Location body = target.getLocation().add(0, target.getHeight() * 0.7, 0);
        world.spawnParticle(Particle.DUST, body, 5, 0.24, 0.3, 0.24, 0.0,
                new Particle.DustOptions(IRIS_GREY, 1.0f));
        world.spawnParticle(Particle.SMOKE, body, 2, 0.15, 0.2, 0.15, 0.0);
        world.playSound(body, Sound.ENTITY_ENDERMAN_STARE, 0.3f, 1.2f);
    }

    // ---- action bar -----------------------------------------------------------------

    /**
     * While Gaze is held, keep the Delight gauge and the Fixed Stare state on the action bar. Returns
     * false the instant the sword leaves the main hand — anything else would tick this player forever.
     * On the way out, a wielder whose watcher has nothing left to remember is forgotten entirely.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (!matches(player.getInventory().getItemInMainHand())) {
            Delight d = states.get(id);
            if (d != null && delight(d, now) <= 0 && now >= d.stareUntil
                    && now >= d.cooldownReadyAt && now >= d.lingerReadyAt) {
                states.remove(id); // fully idle — don't hold a record for a sheathed sword
            }
            return false;
        }

        // Refresh the Fixation retention from the held item each tick — capped, and read once here.
        int fixation = Math.min(FIXATION_CAP,
                EgoEnchants.level(player.getInventory().getItemInMainHand(), "fixation"));
        state(id).fixationGraceMs = Math.max(0, fixation) * FIXATION_GRACE_PER_LEVEL;

        sendDelightBar(player, now);
        return true;
    }

    /**
     * The watcher's whole readout on ONE line via {@link EgoHud#row}: the Delight gauge with its stack count
     * and bonus, then the Fixed Stare state, then the Lingering Gaze state — every cooldown at once, so none
     * of them ever flashes in over the gauge as the wielder acts.
     * <p>{@code [▮▮▮▮▯▯▯▯▯▯]  Delight 8  +16%   Fixed Stare — ready   Lingering Gaze — ready}
     */
    private void sendDelightBar(Player player, long now) {
        Delight d = states.get(player.getUniqueId());
        if (d == null) return;

        int stacks = delight(d, now);
        player.sendActionBar(EgoHud.row(
                delightReadout(stacks),
                stareReadout(d, now),
                lingerReadout(d, now)));
    }

    /** The Delight half: the gauge, its stack count, and the bonus it is currently paying. */
    private Component delightReadout(int stacks) {
        int pct = (int) Math.round(stacks * DAMAGE_PER_STACK * 100.0);
        Component label = EgoHud.status("Delight " + stacks + "  +" + pct + "%", DELIGHT_HUD);
        return EgoHud.gauge(DELIGHT_HUD, stacks / (double) MAX_DELIGHT, label);
    }

    /** The Fixed Stare half: the open window counting down, else its rest, else ready. */
    private Component stareReadout(Delight d, long now) {
        if (now < d.stareUntil) {
            return EgoHud.status("Watching — " + upSeconds(d.stareUntil - now) + "s", STARE_HUD);
        }
        if (now < d.cooldownReadyAt) {
            return EgoHud.cooldown("Fixed Stare", d.cooldownReadyAt - now, FAINT_HUD);
        }
        return EgoHud.ready("Fixed Stare", FAINT_HUD);
    }

    /** The Lingering Gaze half: its rest while cooling, else ready. */
    private Component lingerReadout(Delight d, long now) {
        if (now < d.lingerReadyAt) {
            return EgoHud.cooldown("Lingering Gaze", d.lingerReadyAt - now, FAINT_HUD);
        }
        return EgoHud.ready("Lingering Gaze", FAINT_HUD);
    }

    /** Whole seconds, rounded up and never zero — the house rule for anything with a clock on it. */
    private static long upSeconds(long ms) {
        return Math.max(1L, (ms + 999L) / 1000L);
    }

    // ---- SFX / VFX ------------------------------------------------------------------
    // Quiet and voyeuristic, never loud: a dark palette, low counts, and sounds that sit under the fight
    // rather than on top of it. Every spawnParticle below is DUST (DustOptions), DUST_COLOR_TRANSITION
    // (DustTransition), or a data-less particle — the data class is a runtime crash, not a compile error.

    /** Hit one: a soft prickle of being looked at — dark motes gathering on the struck body. */
    private void watchedFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST, body, 6, 0.22, 0.28, 0.22, 0.0,
                new Particle.DustOptions(KEYHOLE_DARK, 1.0f));
        world.spawnParticle(Particle.SMOKE, body, 2, 0.15, 0.2, 0.15, 0.0);
        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.35f, 0.7f + jitter());
    }

    /** Hit two: the watcher's own cut — a dry second strike and a pupil narrowing on the wound. */
    private void secondCutFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 8, 0.20, 0.26, 0.20, 0.0,
                new Particle.DustTransition(IRIS_GREY, KEYHOLE_DARK, 1.1f));
        world.spawnParticle(Particle.CRIT, body, 3, 0.18, 0.22, 0.18, 0.06);
        world.playSound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.45f, 0.62f + jitter());
    }

    /** Fixed Stare opens: a keyhole swinging wide, and something on the other side leaning in. */
    private void openKeyholeFx(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.45f, 0.55f);
        world.playSound(eye, Sound.ENTITY_ENDERMAN_STARE, 0.4f, 0.6f);

        // A slow dark ring closing around the wielder — the aperture of an eye, not a flash.
        Location around = player.getLocation().add(0, 1.0, 0);
        for (int i = 0; i < STARE_RING_POINTS; i++) {
            double a = (Math.PI * 2.0 / STARE_RING_POINTS) * i;
            Location p = around.clone().add(Math.cos(a) * STARE_RING_RADIUS, 0.0, Math.sin(a) * STARE_RING_RADIUS);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.02, 0.06, 0.02, 0.0,
                    new Particle.DustTransition(IRIS_GREY, KEYHOLE_DARK, 1.2f));
        }
        world.spawnParticle(Particle.SMOKE, around, 6, 0.25, 0.35, 0.25, 0.005);
    }

    /** Delight hits the cap: the watcher is as happy as it gets. Quiet, low, and slightly wrong. */
    private void delightedCue(Player player) {
        World world = player.getWorld();
        Location chest = player.getLocation().add(0, 1.0, 0);
        world.playSound(chest, Sound.ENTITY_ENDERMAN_STARE, 0.35f, 0.5f);
        world.spawnParticle(Particle.DUST, chest, 10, 0.28, 0.4, 0.28, 0.0,
                new Particle.DustOptions(IRIS_GREY, 1.0f));
    }

    /** Points in the Fixed Stare ring, and its radius — a full aperture, drawn once, cheaply. */
    private static final int STARE_RING_POINTS = 14;
    private static final double STARE_RING_RADIUS = 1.1;

    private static float jitter() {
        return (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.12f;
    }

    // ---- lifecycle ------------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // Wielder state only — Gaze keeps nothing keyed to a victim, so there is nothing here to leak.
        states.remove(id);
        reentry.remove(id);
    }

    @Override
    public void onDisable() {
        for (FollowUp strike : followUps) strike.cancel(); // hits still in flight never land after shutdown
        followUps.clear();
        states.clear();
        reentry.clear();
    }

    // ---- lore -------------------------------------------------------------------------

    // Gaze's colours are the keyhole and the dark behind it, and they were specified as #3A3636 and
    // #242424 — near-black, which a tooltip's own near-black background swallows whole. Both are lifted
    // here to the ash the action bar already reads in, so the item and its gauge speak with one voice.
    // The hues and their order are untouched: the shadow still sits above the dark behind it, and the
    // pair still reads as something watching from a place with no light in it.

    /** Primary — the keyhole's shadow. Display name, "How to use:", ability headers. */
    private static final TextColor PRIMARY = TextColor.color(0x9C9494);
    /** Secondary — the dark behind it. The Abnormality title line. */
    private static final TextColor SECONDARY = TextColor.color(0x6E6666);

    // Action-bar palette, kept apart from the lore palette so tuning one never disturbs the other.
    private static final TextColor DELIGHT_HUD = TextColor.color(0x9C9494); // the gauge + stack count
    private static final TextColor STARE_HUD   = TextColor.color(0xD8D0D0); // the eye, open
    private static final TextColor FAINT_HUD   = TextColor.color(0x6E6666); // cooldown / ready

    // Particle colours (kept apart from both palettes so tuning one never disturbs the others).
    private static final Color KEYHOLE_DARK = Color.fromRGB(0x3A, 0x36, 0x36); // the shadow in the keyhole
    private static final Color IRIS_GREY    = Color.fromRGB(0x6E, 0x66, 0x66); // the wet glint of an eye

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Gaze",
            "Schadenfreude",
            PRIMARY,
            SECONDARY,
            List.of(
                    "The gaze from the keyhole is fixed on",
                    "its target without ever stopping. No",
                    "one knows what it wanted to peep at",
                    "so dearly. As long as this is",
                    "equipped, ambush won't be a concern."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Schadenfreude",
                            "Attacking stacks Delight (+2% damage",
                            "per stack, up to 20). Decays if you go",
                            "3 seconds without landing a hit."),
                    new EgoLore.Ability("[Left Click] Constant Surveillance",
                            "Attacks hit 2 times — the second is",
                            "the blade's bare cut, unenchanted.",
                            "Each attack stacks Delight."),
                    new EgoLore.Ability("[Right Click] Fixed Stare",
                            "For 5 seconds, your Delight cannot",
                            "decay and builds twice as fast.",
                            "15 second cooldown."),
                    new EgoLore.Ability("[Shift + Right Click] Lingering Gaze",
                            "Mark the enemy in your crosshair.",
                            "For 5s it takes 1 damage a second,",
                            "and each tick gives you 1 Delight.",
                            "10 second cooldown.")
            ));
}
