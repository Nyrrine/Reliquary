package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Magic Bullet — "Der Freischütz" (Lobotomy Corp E.G.O, WAW).
 *
 * <p>A long blue marksman's musket whose whole theme is <b>a huge blue arcane magic circle inscribed in
 * mid-air</b>, its rim lit with light-blue glow, summoned down the aim as the shot charges. It does not
 * fire on the pull — it fires on the <em>vow</em>. Each left-click begins a slow, deliberate charge; the
 * sigil is <i>hand-drawn</i> across the charge (an arc sweeps closed, spokes fill in, runes settle last)
 * and at full charge the musket looses a single dead-straight blue shot that <b>never misses</b>.
 *
 * <ul>
 *   <li><b>Left-click (swing)</b> — begin a charge. It advances in {@link #onTick}. At full charge the
 *       musket FIRES a hitscan that never misses; a real {@value #SHOT_COOLDOWN_MS}ms cooldown then gates
 *       the next charge, so the cadence is deliberately musket-slow. Only the first {@value #NORMAL_SHOTS}
 *       bullets can be fired this way.</li>
 *   <li><b>Right-click</b> — <i>mark</i> the enemy you are looking at (or the most-recently-struck one) as
 *       the locked target. While a mark holds, every shot (and the ball) homes onto it.</li>
 *   <li><b>Sneak + right-click</b> — "The Seventh Bullet" ultimate. Once {@value #NORMAL_SHOTS} shots are
 *       spent the wielder is <i>on the seventh bullet</i>: the normal shot is disabled and this is forced.
 *       A dramatic wind-up (circle behind + triple-large circle in front) summons a massive black-and-blue
 *       orb that homes, tears a temporary hole through everything it passes, and devastates on contact.</li>
 * </ul>
 *
 * <p>Every Magic Bullet attack — the hitscan shots and the orb — is <b>shield-blockable</b>: a Player who
 * is blocking and facing into the shot negates it. After the ultimate dissipates the musket enters a
 * {@value #DOWNTIME_MS}ms downtime (cannot be used at all), then the Bullet Counter resets.
 *
 * <p>Hygiene: one in-memory UUID map cleared on quit; the recoil comet is pure particle/math (no entity);
 * the orb is pure particle/math (no entity); its temp-carved blocks are tracked in a pending-restore
 * registry and flushed in {@link #onDisable()} (and on the wielder's quit if the orb is mid-flight), so a
 * {@code /reload} mid-flight never leaves a permanent hole.
 */
public final class MagicBulletWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** The one per-wielder state bag: charge, six-shot cycle, bullet counter, marked target, ult phase. */
    private final Map<UUID, State> states = new HashMap<>();

    /**
     * Bodies currently taking something this musket threw — a shot, the orb's cleave, or the orb's kickback
     * onto the wielder themselves. The manager dispatches {@link #onHit} for any blow whose damager is a
     * player holding this weapon, and all three are exactly that, so this fence is what tells them from a
     * swing. Held only across each single {@code damage()} call, in a try/finally, so it is empty between
     * blows and can never accumulate a dead mob's id.
     */
    private final Set<UUID> shooting = new HashSet<>();

    // ---- ult shutdown-safety registries (main-thread only) -------------------------
    /** Outstanding temp-carved blocks awaiting their timed restore, keyed by location (no double-restore). */
    private final Map<Location, BlockState> pendingCarves = new LinkedHashMap<>();
    /** Every live orb runnable, so a plugin disable can stop them all. */
    private final Set<SeventhBulletBall> activeBalls = new HashSet<>();

    // ---- tuning --------------------------------------------------------------------
    private static final int    CHARGE_STEPS   = 24;    // onTick steps to full charge → ~2.4s (weighty, dramatic)

    // Swift Vow (a vanilla enchant — the musket's crossbow base holds Quick Charge at an anvil, so this needs
    // no catalogue entry): a swifter vow inscribes the sigil faster, cutting only the arming charge 15% per
    // level, up to 45% at Quick Charge III (~1.3s to arm from ~2.4s). It never touches the post-shot reload
    // gate (SHOT_COOLDOWN_MS) or the Seventh Bullet's downtime lockout, and never the shot itself — cadence
    // to the vow only. See chargeSteps, which the charge HUD reads so its gauge fills in step with the cut.
    private static final double SWIFT_VOW_PER_LEVEL = 0.15;
    private static final int    SWIFT_VOW_CAP       = 3;
    private static final long   SHOT_COOLDOWN_MS = 6500L;  // real post-shot reload before the next charge (~6.5s; halved from 13s in playtest)
    private static final double RANGE          = 48.0;  // hitscan reach
    private static final double RAY_SIZE       = 0.6;   // entity ray fatness (forgiving aim) when unmarked
    // The six normal shots are 10. The old hub note recorded a "cap 16→10" retune that this file never
    // received — the note was right about the intent and the code simply never got it. Landed 2026-07-17
    // on Nyrrine's ruling: ten for the normal shots, and the seventh stays devastating (ULT_DAMAGE).
    private static final double SHOT_DAMAGE    = 10.0;  // per shot — slow, charged, never-miss, self-costing
    private static final int    NORMAL_SHOTS   = 6;     // normal shots allowed before the ult is forced
    private static final int    CYCLE          = 6;     // magic-circle cycle length
    private static final long   DOWNTIME_MS    = 15_000L; // post-ult lockout before the counter resets

    /** Half a heart — the cursed-recoil self-toll. Named so it is trivially retunable. */
    private static final double SELF_DAMAGE     = 1.0;

    // The Seventh Bullet's ON-KILL strike-back — a crap-ton of self-damage if the orb kills anyone.
    private static final double ULT_SELF_DAMAGE  = 16.0; // 8 hearts — tunable "crap ton"
    private static final double STRIKEBACK_SPEED = 0.9;  // blocks/tick, heavy/ominous → ~1s flight
    private static final int    STRIKEBACK_LIFE  = 60;   // ticks hard cap
    private static final double STRIKEBACK_HIT   = 1.3;  // within this of the head = slam

    // Physics block debris flung from the orb's carve (real FallingBlocks, gravity, auto-removed, never place).
    private static final String DEBRIS_TAG   = "magic_bullet_debris"; // onDisable sweep + placement guard
    private static final int    MAX_DEBRIS   = 140;  // hard per-orb cap on live debris entities (TPS)
    private static final int    DEBRIS_LIFE  = 30;   // ticks before each debris auto-removes (never settles)

    // The gun tip: kept well forward of the eye so a big circle never clips first-person.
    private static final double GUN_TIP        = 3.2;   // circle plane sits ~3.2 blocks down the aim
    private static final double MUZZLE         = 1.0;   // tracer/flare origin

    // Magic-circle radii by size — dramatically bigger than the old clumped sigils.
    private static final double R_SMALL        = 1.6;
    private static final double R_MED          = 2.8;
    private static final double R_LARGE        = 4.2;

    // Marked-target lifecycle.
    private static final int    MARK_RANGE     = 48;
    private static final long   MARK_TIMEOUT_MS = 60_000L;  // a comfortable hold — no need to re-mark constantly
    private static final double MARK_LOSE_DIST = 80.0;      // mark drops only if the target strays this far
    private static final long   MARK_MSG_HOLD_MS = 2500L;   // keep a lock/lost message up this long before the meter resumes

    // Cursed recoil bullet — pure particle/math, spawns NO entity (leak-free, self-cancelling).
    private static final double RECOIL_SPEED   = 1.1;   // blocks/tick back toward the shooter's head
    private static final int    RECOIL_LIFE    = 40;    // ticks hard cap
    private static final double RECOIL_HIT     = 1.1;   // within this of the head = strike

    // ---- ultimate: "The Seventh Bullet" --------------------------------------------
    private static final int    ULT_WINDUP_TICKS = 300; // 15s — a dramatic cast; the sigils summon one by one
    private static final double ULT_DAMAGE     = 35.0;  // devastating AoE on contact
    private static final int    BALL_LIFE      = 225;   // ~11.25s lifetime cap (2.5x the old 90t, to match the long cast)

    /**
     * The four wind-up sigils summon ONE BY ONE across the 15s cast: each {@code {startFrac, endFrac}} is the
     * slice of the global wind-up during which that circle inscribes itself (via {@link #drawMagicCircle}'s
     * written-formation animation). Once inscribed a circle stays — fully drawn and spinning — to the end, so
     * by the launch all four hang together in the air.
     */
    private static final double[][] ULT_CIRCLE_SCHEDULE = {
            {0.00, 0.22}, // 1 — the great sigil summoned at the wielder's back
            {0.24, 0.46}, // 2 — the first circle at the muzzle
            {0.48, 0.70}, // 3 — the second, larger, marching down the aim
            {0.72, 0.94}, // 4 — the third, largest, farthest down the aim
    };
    private static final double BALL_SPEED     = 0.45;  // blocks/tick — moderate, watchable
    private static final double BALL_VIS_R     = 3.4;   // visual radius → ~7-block diameter (really big)
    private static final double BALL_CARVE_R   = 3.4;   // temp-destroy radius — gouges a wide trench in its path
    private static final double BALL_HIT_R     = 3.6;   // damage radius
    private static final double BALL_HOMING    = 0.12;  // per-tick curve toward a marked target
    private static final int    ULT_RESTORE_TICKS = 250; // temp-broken blocks pop back after ~12.5s — in step with the longer orb
    private static final int    MAX_ULT_CARVE  = 1400;  // hard cap on temp-broken blocks per orb (a big trench)
    private static final double FOOTING_R      = 2.6;   // never carve within this of the wielder's feet — don't drop them

    // ---- palette --------------------------------------------------------------------
    // The sigil itself reads BLUE: DEEP-BLUE rings/spokes with LIGHT-BLUE highlights where light catches the
    // glyph. BLACK is the supporting tone — Magic Bullet's signature "black flame": near-black DUST + SMOKE
    // wisps curling around the sigil, off the muzzle, and shrouding the ult orb (a black-flame shell around a
    // blue core). Black is never the ring material. NO white (no END_ROD).
    private static final TextColor NAME    = TextColor.color(0x2E6BFF); // deep blue — primary (name, headers)
    private static final TextColor GLOW    = TextColor.color(0x8FB8FF); // light-blue accent — secondary (title line)
    private static final TextColor FAINT   = TextColor.color(0x76788C); // conditions / controls
    private static final TextColor FRAME   = NamedTextColor.DARK_GRAY;  // meter brackets / empty
    private static final TextColor SEVENTH = TextColor.color(0xADD8FF); // "The Seventh Bullet"
    private static final TextColor VOICE   = TextColor.color(0x9BB8FF); // light-blue voiceline quote

    private static final Color C_DEEP  = Color.fromRGB(0x2E, 0x6B, 0xFF); // deep-blue ring/spoke base
    private static final Color C_LIGHT = Color.fromRGB(0x8F, 0xB8, 0xFF); // light-blue highlight
    private static final Color C_BRITE = Color.fromRGB(0xAD, 0xD8, 0xFF); // brightest light-blue (the "pen")
    private static final Color C_FLAME = Color.fromRGB(0x0A, 0x0A, 0x12); // near-black flame wisp accent

    private static final Particle.DustOptions DEEP_DUST  = new Particle.DustOptions(C_DEEP, 1.1f);  // ring base
    private static final Particle.DustOptions LIGHT_DUST = new Particle.DustOptions(C_LIGHT, 1.0f); // highlight
    private static final Particle.DustOptions BRITE_DUST = new Particle.DustOptions(C_BRITE, 1.1f); // the "pen"
    private static final Particle.DustOptions FLAME_DUST = new Particle.DustOptions(C_FLAME, 1.3f); // black flame
    private static final Particle.DustOptions TRACER     = new Particle.DustOptions(C_LIGHT, 1.0f);
    private static final Particle.DustOptions COMET      = new Particle.DustOptions(C_DEEP, 1.0f);

    // Orb palette — bigger dust so the ball reads as a dense churning mass (blue core, black-flame shell).
    private static final Particle.DustOptions BALL_DEEP  = new Particle.DustOptions(C_DEEP, 2.6f);  // blue core
    private static final Particle.DustOptions BALL_LIGHT = new Particle.DustOptions(C_LIGHT, 2.0f); // core glow
    private static final Particle.DustOptions BALL_FLAME = new Particle.DustOptions(C_FLAME, 3.0f); // flame shell

    private static final String SEG = "▮"; // ▮ — the shared EgoHud meter glyph

    public MagicBulletWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "magic_bullet");
    }

    @Override
    public String id() {
        return "magic_bullet";
    }

    /** The one per-wielder state bag. */
    private static final class State {
        boolean charging   = false;
        int     chargeStep = 0;
        double  spin       = 0.0;   // magic-circle rotation phase, advanced each tick while charging
        long    gateUntil  = 0L;    // post-shot cooldown before another charge can arm
        int     cycle      = 0;     // 0..5 → shot-in-cycle = cycle+1, drives the circle config
        int     bullets    = 0;     // 0..NORMAL_SHOTS, the Bullet Counter
        UUID    marked     = null;  // locked "never-miss" target
        long    markedAt   = 0L;    // when the mark was set (for the timeout)
        String  markedName = null;  // display name of the marked target — for the lock/lost messages
        UUID    lastHit    = null;  // most-recently-struck enemy (mark fallback)
        long    holdMsgUntil = 0L;  // hold a mark/lost message on the action bar until this epoch-ms

        // Ultimate phase.
        boolean casting    = false;  // true through the ult wind-up AND while the orb is in flight
        long    downtimeUntil = 0L;  // post-ult lockout; counter resets when it elapses
        BukkitRunnable   ultWindup = null; // the wind-up task (cancel on quit/disable)
        SeventhBulletBall ultBall  = null; // the orb task (flush on quit/disable)
    }

    // ---- input routing -------------------------------------------------------------

    /**
     * The musket fires on the vow, not on being swung at someone. Left-click begins a charge, so pointing
     * it at a body within arm's reach would otherwise land a vanilla blow as well — and that blow, arriving
     * first, stamps hurt-immunity that swallows the shot the charge was for. Cancelling costs nothing: Magic
     * Bullet is a {@code ranged} model with no melee damage of its own.
     *
     * <p><b>The fence is not optional.</b> The manager hands this every blow whose damager is a player
     * holding the musket — which is the shot, the orb's cleave, and the orb's kickback onto the wielder
     * alike. Without {@link #shooting} the cancel would eat all three: the gun would deal nothing and the
     * vow would cost nothing.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (shooting.contains(victim.getUniqueId())) return; // our own shot/orb/toll, not a swing
        event.setCancelled(true);
    }

    @Override
    public void onSwing(Player player) {
        // LEFT-click begins a charge. Only when the musket is truly in the main hand.
        if (!matches(player.getInventory().getItemInMainHand())) return;
        State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();

        if (st.downtimeUntil > now) { downtimeCue(player, st.downtimeUntil - now); return; }
        if (st.casting) return;                  // ult wind-up / orb in flight — musket is committed
        if (st.bullets >= NORMAL_SHOTS) {        // on the seventh bullet — normal shot is disabled
            player.sendActionBar(EgoHud.status("Only the Seventh Bullet remains — Shift-right-click", SEVENTH));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 0.6f);
            return;
        }
        if (st.charging) return;                 // already charging — the tick drives it
        if (now < st.gateUntil) return;          // still in the post-shot cooldown
        st.charging = true;
        st.chargeStep = 0;
        chargeStartFx(player, st.cycle + 1);
    }

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;
        State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();

        if (st.downtimeUntil > now) { downtimeCue(player, st.downtimeUntil - now); return; }
        if (st.casting) return;                  // already committed to the ult

        if (sneaking) {
            // Shift-right-click = "The Seventh Bullet" ultimate — only once the counter is full.
            if (st.bullets >= NORMAL_SHOTS) {
                seventhBullet(player, st);
            } else {
                player.sendActionBar(EgoHud.status(
                        "The Seventh Bullet — not yet (" + st.bullets + "/" + NORMAL_SHOTS + ")", FAINT));
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 0.7f);
            }
            return;
        }

        // Right-click = mark / lock the target for "never miss".
        markTarget(player, st);
    }

    // ---- charge & fire loop --------------------------------------------------------

    /** The steps to a full arming charge for the musket held now: the base cut by its Swift Vow bonus (Quick
     *  Charge, capped). Floored at one step so a fully-enchanted musket still takes a tick to arm. */
    private int chargeSteps(Player player) {
        int qc = Math.min(SWIFT_VOW_CAP,
                player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.QUICK_CHARGE));
        int steps = (int) Math.round(CHARGE_STEPS * (1.0 - SWIFT_VOW_PER_LEVEL * qc));
        return Math.max(1, steps);
    }

    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            // Not held in the main hand — reset any in-progress charge and disengage. State persists.
            State st = states.get(id);
            if (st != null) { st.charging = false; st.chargeStep = 0; }
            return false;
        }

        State st = states.computeIfAbsent(id, k -> new State());
        validateMark(player, st);
        long now = System.currentTimeMillis();

        // Downtime: the musket is locked; when it lapses, the Bullet Counter resets.
        if (st.downtimeUntil > 0L) {
            if (now >= st.downtimeUntil) {
                st.downtimeUntil = 0L;
                st.bullets = 0;
                st.cycle = 0;
                player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.7f, 0.8f); // chamber ready
                player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_BURN, 0.4f, 0.5f);
                player.sendActionBar(bulletMeter(st.bullets));
            } else {
                long rem = st.downtimeUntil - now;
                // Keep the Bullet Counter on the line and append the reload state, rather than replacing it.
                player.sendActionBar(bulletMeter(st.bullets)
                        .append(plain("  reloading in " + secs(rem) + "s", NAME)));
                return true;
            }
        }

        if (st.casting) return true; // the ult wind-up / orb drives its own action bar

        if (st.charging) {
            int steps = chargeSteps(player);       // Swift Vow shortens the arming charge; the HUD reads it too
            st.chargeStep++;
            st.spin += 0.35;
            double frac = Math.min(1.0, (double) st.chargeStep / steps);
            renderCircles(player, st, frac);
            chargeTickFx(player, frac);
            player.sendActionBar(chargeBar(frac, st.bullets));
            if (st.chargeStep >= steps) {
                st.charging = false;
                fire(player, st);
            }
        } else if (now < st.gateUntil) {
            // Keep the base meter and append the between-shots reload countdown, not a lone gauge.
            player.sendActionBar(bulletMeter(st.bullets)
                    .append(plain("  reloading in " + secs(st.gateUntil - now) + "s", NamedTextColor.GRAY)));
        } else if (now < st.holdMsgUntil) {
            return true; // keep the "Target locked / lost" message up for a beat before the meter resumes
        } else {
            player.sendActionBar(bulletMeter(st.bullets));
        }
        return true;
    }

    /** Loose the charged musket shot: a never-miss blue hitscan (shield-blockable) + cursed recoil. */
    private void fire(Player player, State st) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location muzzle = eye.clone().add(dir.clone().multiply(MUZZLE));

        // The shot's voiceline — the bullet being fired is st.bullets+1 (1..6), before the counter ticks.
        speakVoiceline(player, st.bullets + 1);

        // A dramatic bloom at the muzzle on release — the sigil snaps to full and blows off the tip.
        fireBloom(player, muzzle, dir, st.cycle + 1);

        LivingEntity victim;
        Location impact;
        LivingEntity marked = markedEntity(player, st);
        if (marked != null) {
            // MARKED: aim straight at the target's centre — guaranteed hit regardless of aim, through cover.
            victim = marked;
            impact = marked.getBoundingBox().getCenter().toLocation(world);
        } else {
            // UNMARKED: grass-ignoring wall clip, then the first living body along the eye line.
            double maxDist = RANGE;
            RayTraceResult block = world.rayTraceBlocks(eye, dir, RANGE, FluidCollisionMode.NEVER, true);
            if (block != null && block.getHitPosition() != null) {
                maxDist = eye.toVector().distance(block.getHitPosition());
            }
            RayTraceResult ent = world.rayTraceEntities(eye, dir, maxDist, RAY_SIZE,
                    e -> e instanceof LivingEntity && !e.getUniqueId().equals(player.getUniqueId()));
            if (ent != null && ent.getHitEntity() instanceof LivingEntity le) {
                victim = le;
                impact = ent.getHitPosition().toLocation(world);
            } else {
                victim = null;
                impact = eye.clone().add(dir.clone().multiply(maxDist));
            }
        }

        drawTracer(world, muzzle, impact);

        if (victim != null) {
            Vector incoming = impact.toVector().subtract(muzzle.toVector());
            if (incoming.lengthSquared() < 1.0e-6) incoming = dir.clone();
            incoming.normalize();
            if (shieldBlocks(victim, incoming)) {
                shieldBlockFx(victim, incoming);          // the shield eats the shot — no damage, no recoil
            } else {
                shooting.add(victim.getUniqueId());
                try {
                    victim.damage(SHOT_DAMAGE, player);
                } finally {
                    shooting.remove(victim.getUniqueId());
                }
                impactFx(world, impact);
                st.lastHit = victim.getUniqueId();
                // No self-recoil on shots 1–6 — only the Seventh Bullet's strike-back can wound the wielder.
            }
        }

        EgoDurability.wearMainHand(player);              // the musket wears with each shot fired
        st.cycle = (st.cycle + 1) % CYCLE;               // advance the six-shot circle cycle
        st.bullets = Math.min(NORMAL_SHOTS, st.bullets + 1); // tick the Bullet Counter (caps at the 7th)
        st.gateUntil = System.currentTimeMillis() + SHOT_COOLDOWN_MS; // real between-shots cooldown

        player.sendActionBar(bulletMeter(st.bullets));
    }

    // ---- shield block --------------------------------------------------------------

    /** True if {@code victim} is a blocking Player facing INTO an incoming shot travelling {@code incoming}. */
    private boolean shieldBlocks(LivingEntity victim, Vector incoming) {
        if (!(victim instanceof Player p) || !p.isBlocking()) return false;
        Vector look = p.getEyeLocation().getDirection();
        if (look.lengthSquared() < 1.0e-6) return false;
        // Facing into the shot: the look-dir opposes the shot's travel direction (hemisphere check).
        return look.normalize().dot(incoming) < 0.0;
    }

    /** A shield-block cue: the vanilla shield clang + a small light-blue spark burst at the guard. */
    private void shieldBlockFx(LivingEntity victim, Vector incoming) {
        World w = victim.getWorld();
        Location at = victim.getEyeLocation().add(incoming.clone().multiply(-0.5)).add(0, -0.2, 0);
        w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.9f);
        w.spawnParticle(Particle.CRIT, at, 8, 0.2, 0.2, 0.2, 0.1);
        w.spawnParticle(Particle.DUST, at, 8, 0.2, 0.2, 0.2, 0, LIGHT_DUST);
    }

    /**
     * Deal damage without the usual knockback — capture velocity and restore it after the hit.
     *
     * <p>Fenced through {@link #shooting}: the manager hands {@link #onHit} every blow whose damager is a
     * player holding this musket, and everything this weapon throws is exactly that. Without the fence the
     * cancel in onHit would eat the orb's own damage.
     */
    private void damageNoKb(LivingEntity le, double dmg, Player src) {
        Vector before = le.getVelocity();
        shooting.add(le.getUniqueId());
        try {
            le.damage(dmg, src);
        } finally {
            shooting.remove(le.getUniqueId());
        }
        le.setVelocity(before);
    }

    // ---- mark ----------------------------------------------------------------------

    /** Lock the looked-at enemy (or the most-recently-struck one) as the never-miss target. */
    private void markTarget(Player player, State st) {
        LivingEntity target = null;
        Entity looked = player.getTargetEntity(MARK_RANGE);
        if (looked instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId()) && !le.isDead()) {
            target = le;
        } else if (st.lastHit != null) {
            Entity e = plugin.getServer().getEntity(st.lastHit);
            if (e instanceof LivingEntity le && !le.isDead()) target = le;
        }

        if (target == null) {
            player.sendActionBar(EgoHud.status("No target to mark — look at an enemy.", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.2f);
            return;
        }

        st.marked = target.getUniqueId();
        st.markedAt = System.currentTimeMillis();
        st.markedName = target.getName();
        st.holdMsgUntil = st.markedAt + MARK_MSG_HOLD_MS;
        lockedCue(player, target);
    }

    /** Resolve the currently-valid marked entity, or null. */
    private LivingEntity markedEntity(Player player, State st) {
        validateMark(player, st);
        if (st.marked == null) return null;
        Entity e = plugin.getServer().getEntity(st.marked);
        return (e instanceof LivingEntity le && !le.isDead()) ? le : null;
    }

    /** Drop the mark if the target died, strayed too far, changed worlds, or the lock timed out. */
    private void validateMark(Player player, State st) {
        if (st.marked == null) return;
        long now = System.currentTimeMillis();
        String name = st.markedName != null ? st.markedName : "the target";
        String lost = null;
        if (now - st.markedAt > MARK_TIMEOUT_MS) {
            lost = "Target lost — the lock on " + name + " faded.";
        } else {
            Entity e = plugin.getServer().getEntity(st.marked);
            if (!(e instanceof LivingEntity le) || le.isDead()) {
                lost = "Target lost — " + name + " is gone.";
            } else if (!le.getWorld().equals(player.getWorld())
                    || le.getLocation().distanceSquared(player.getLocation()) > MARK_LOSE_DIST * MARK_LOSE_DIST) {
                lost = "Target lost — " + name + " slipped out of range.";
            }
        }
        if (lost != null) {
            player.sendActionBar(EgoHud.status(lost, FAINT));     // tell the wielder the mark dropped, and why
            st.holdMsgUntil = now + MARK_MSG_HOLD_MS;
            st.marked = null; st.markedAt = 0L; st.markedName = null;
        }
    }

    // ---- "The Seventh Bullet" ultimate ---------------------------------------------

    /**
     * The showpiece. A dramatic {@value #ULT_WINDUP_TICKS}-tick (15s) cast: the four sigils — a great
     * circle BEHIND the wielder and a triple-large formation marching down the aim — are summoned ONE BY ONE
     * (see {@link #ULT_CIRCLE_SCHEDULE}), each inscribing itself and then hanging, spinning, as the next is
     * drawn. A rising roar and a resonant toll on every new circle build to the launch of a massive
     * black-and-blue orb, which then lives {@value #BALL_LIFE} ticks on the field.
     */
    private void seventhBullet(Player player, State st) {
        st.casting = true;
        speakVoiceline(player, 7); // the Seventh Bullet's line, loosed as the ult is committed
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.5f);  // a demonic roar
        world.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.5f);          // hellfire ignites
        world.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.6f);         // the barrel roars alight

        BukkitRunnable windup = new BukkitRunnable() {
            int t = 0;
            double spin = 0.0;
            int summoned = 0; // how many circles have been announced with a toll so far

            @Override
            public void run() {
                if (!player.isOnline() || !matches(player.getInventory().getItemInMainHand())) {
                    // Unequipped / left mid-cast — abort cleanly, leaving the counter as it was.
                    st.casting = false;
                    st.ultWindup = null;
                    cancel();
                    return;
                }
                t++;
                spin += 0.40;
                double frac = Math.min(1.0, (double) t / ULT_WINDUP_TICKS);
                renderUltCharge(player, spin, frac);

                // A resonant toll each time a new sigil begins to summon — the drama of the one-by-one build.
                int nowSummoned = circlesSummoned(frac);
                if (nowSummoned > summoned) {
                    summoned = nowSummoned;
                    float pitch = 0.5f + 0.16f * summoned;
                    world.playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.0f, pitch);
                    world.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.8f, 0.7f);
                    world.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.7f, 0.6f);
                }

                // A low, terrible demonic growl building under the cast — the weapon's black-flame nature.
                if (t % 34 == 0) {
                    float pitch = 0.35f + (float) (frac * 0.15);                   // stays deep, barely rising
                    world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, pitch);
                }
                if (t % 6 == 0) {
                    world.playSound(player.getLocation(), Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE,
                            0.6f, 0.6f);                                            // the growing hellfire — black flame
                }
                player.sendActionBar(EgoHud.gauge(NAME, frac,
                        plain("THE SEVENTH BULLET — inscribing " + summoned + "/4", NAME)));
                if (t >= ULT_WINDUP_TICKS) {
                    st.ultWindup = null;
                    cancel();
                    launchSeventhBall(player, st);
                }
            }
        };
        st.ultWindup = windup;
        windup.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The wind-up sigils, summoned one by one: a large circle behind the wielder, then a triple-large nested
     * formation down the aim. Each circle is drawn only once its {@link #ULT_CIRCLE_SCHEDULE} window opens,
     * inscribing on its own local fraction and staying complete thereafter.
     */
    private void renderUltCharge(Player player, double spin, double frac) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Location behind = eye.clone().add(dir.clone().multiply(-2.4));
        Location front  = eye.clone().add(dir.clone().multiply(GUN_TIP + 1.0));

        // BEHIND — a large sigil at the wielder's back (summons first).
        double f0 = circleFrac(frac, 0);
        if (f0 >= 0.0) drawMagicCircle(world, behind, dir, R_LARGE, spin, f0);

        // IN FRONT — three nested large circles marching down the aim, each summoning after the last.
        double f1 = circleFrac(frac, 1);
        if (f1 >= 0.0) drawMagicCircle(world, front, dir, R_LARGE, spin, f1);
        double f2 = circleFrac(frac, 2);
        if (f2 >= 0.0) drawMagicCircle(world, front.clone().add(dir.clone().multiply(1.6)), dir, R_LARGE * 1.25, -spin, f2);
        double f3 = circleFrac(frac, 3);
        if (f3 >= 0.0) drawMagicCircle(world, front.clone().add(dir.clone().multiply(3.2)), dir, R_LARGE * 1.55, spin, f3);
    }

    /** Local 0..1 inscription fraction for wind-up circle {@code i}, or -1 if its summon window hasn't opened. */
    private double circleFrac(double globalFrac, int i) {
        double start = ULT_CIRCLE_SCHEDULE[i][0], end = ULT_CIRCLE_SCHEDULE[i][1];
        if (globalFrac < start) return -1.0;
        if (globalFrac >= end) return 1.0;
        return (globalFrac - start) / (end - start);
    }

    /** How many wind-up circles have begun summoning at this global fraction (drives the one-by-one toll). */
    private int circlesSummoned(double globalFrac) {
        int n = 0;
        for (double[] window : ULT_CIRCLE_SCHEDULE) if (globalFrac >= window[0]) n++;
        return n;
    }

    /** Launch the massive homing orb down the aim; it self-cancels at its lifetime cap or on wielder loss. */
    private void launchSeventhBall(Player player, State st) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector start = eye.clone().add(dir.clone().multiply(GUN_TIP + 1.5)).toVector();

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.8f);
        world.playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.4f);

        SeventhBulletBall ball = new SeventhBulletBall(player, st, world, start, dir.clone(), st.marked);
        activeBalls.add(ball);
        st.ultBall = ball;
        ball.runTaskTimer(plugin, 0L, 1L);

        EgoDurability.wearMainHand(player);
    }

    /**
     * The Seventh Bullet orb — pure particle/math (spawns NO entity). Homes toward a marked target, tears a
     * temporary hole through everything in its radius (tracked for restore + shutdown flush), and deals a
     * devastating shield-blockable, knockback-fenced AoE hit to anything it touches. Self-cancels at its
     * lifetime cap; then it dissipates and the {@value #DOWNTIME_MS}ms downtime begins.
     */
    private final class SeventhBulletBall extends BukkitRunnable {
        private final Player wielder;
        private final State state;
        private final World world;
        private final Vector pos;
        private Vector dir;
        private final UUID markedId;
        private final List<Location> myCarves = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private int age = 0;
        private boolean finished = false;
        private int debrisCount = 0;         // live FallingBlock debris spawned by this orb (capped)
        private boolean struckBack = false;  // the on-kill strike-back has fired (once per ult)

        SeventhBulletBall(Player wielder, State state, World world, Vector start, Vector dir, UUID markedId) {
            this.wielder = wielder;
            this.state = state;
            this.world = world;
            this.pos = start.clone();
            this.dir = dir.lengthSquared() < 1.0e-6 ? new Vector(0, 0, 1) : dir.normalize();
            this.markedId = markedId;
        }

        @Override
        public void run() {
            if (finished) { cancel(); return; }
            if (age++ >= BALL_LIFE || !wielder.isOnline() || !wielder.getWorld().equals(world)) {
                dissipate();
                return;
            }

            // Homing: curve toward a still-valid marked target; otherwise fly straight.
            LivingEntity target = homingTarget();
            if (target != null) {
                Vector to = target.getBoundingBox().getCenter().subtract(pos);
                if (to.lengthSquared() > 1.0e-6) {
                    Vector desired = to.normalize();
                    dir = dir.multiply(1.0 - BALL_HOMING).add(desired.multiply(BALL_HOMING));
                    if (dir.lengthSquared() > 1.0e-6) dir.normalize();
                }
            }

            pos.add(dir.clone().multiply(BALL_SPEED));
            Location loc = pos.toLocation(world);

            carveSphere(loc);
            drawBall(loc);
            damageSphere(loc);

            // Big explosion SOUNDS along the path — the drama is the flung block debris (carveBlock), not
            // particles. No Particle.EXPLOSION here (removed) and no real blast (no grief, no dropped items).
            if (age % 4 == 0) {
                world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.5f);
                world.playSound(loc, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 1.0f, 0.5f);
            }
            if (wielder.isOnline()) wielder.sendActionBar(EgoHud.status("THE SEVENTH BULLET", NAME));
        }

        private LivingEntity homingTarget() {
            if (markedId == null) return null;
            Entity e = plugin.getServer().getEntity(markedId);
            return (e instanceof LivingEntity le && !le.isDead() && le.getWorld().equals(world)) ? le : null;
        }

        /**
         * Temp-destroy every breakable block within the carve radius — gouging a wide trench along the orb's
         * path. Only the wielder's immediate FOOTING is spared (so the trench never drops them), and blocks
         * holding item frames / paintings are spared (so the ult can't knock them off). No global Y cutoff.
         */
        private void carveSphere(Location center) {
            if (myCarves.size() >= MAX_ULT_CARVE) return;
            int r = (int) Math.ceil(BALL_CARVE_R);
            double r2 = BALL_CARVE_R * BALL_CARVE_R;
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Location feet = wielder.isOnline() ? wielder.getLocation() : null;
            double footR2 = FOOTING_R * FOOTING_R;
            Set<Location> spared = hangingBlocks(center, r); // item frames / paintings — one scan per tick
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz > r2) continue;
                        if (myCarves.size() >= MAX_ULT_CARVE) return;
                        Block b = center.clone().add(dx, dy, dz).getBlock();
                        if (feet != null && b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(feet) < footR2) continue;
                        if (spared.contains(b.getLocation())) continue;
                        carveBlock(b, rng);
                    }
                }
            }
        }

        /** Blocks holding an item frame or painting near the orb — spared from carving so nothing pops off. */
        private Set<Location> hangingBlocks(Location center, int r) {
            Set<Location> out = new HashSet<>();
            for (Entity e : world.getNearbyEntities(center, r + 1.0, r + 1.0, r + 1.0)) {
                if (e instanceof org.bukkit.entity.Hanging h) {
                    Block on = h.getLocation().getBlock();
                    out.add(on.getLocation());
                    out.add(on.getRelative(h.getFacing().getOppositeFace()).getLocation());
                }
            }
            return out;
        }

        private void carveBlock(Block b, ThreadLocalRandom rng) {
            if (!isTempBreakable(b)) return;
            Location keyLoc = b.getLocation();
            if (pendingCarves.containsKey(keyLoc)) return;         // already carved (this orb or another)
            BlockState saved = b.getState();
            pendingCarves.put(keyLoc, saved);
            myCarves.add(keyLoc);
            // Explosive break VFX — a cheap dust puff of the broken block…
            Location ctr = keyLoc.clone().add(0.5, 0.5, 0.5);
            // force=true: the orb carves far from the wielder — see dustF's note on the 32-block cull.
            world.spawnParticle(Particle.BLOCK, ctr, 10, 0.3, 0.3, 0.3, 0.12, saved.getBlockData(), true);
            // …plus real PHYSICS DEBRIS: a fraction of carved chunks are flung outward as FallingBlocks that
            // tumble with gravity then vanish (never drop, never place, never settle).
            spawnDebris(saved, ctr, rng);
            b.setType(Material.AIR, false);                         // no drops
            scheduleUltRestore(keyLoc, saved);
        }

        /**
         * Fling a carved block outward as a real {@link FallingBlock}: launched away from the orb centre + up,
         * gravity-driven, auto-removed after {@value #DEBRIS_LIFE} ticks so it never settles/places. Only a
         * fraction of carves fly (and a hard {@value #MAX_DEBRIS} per-orb cap) to protect TPS. It never drops
         * an item and {@code setCancelDrop(true)} guarantees it never places even if it lands first.
         */
        private void spawnDebris(BlockState saved, Location ctr, ThreadLocalRandom rng) {
            if (debrisCount >= MAX_DEBRIS) return;
            if (rng.nextInt(3) != 0) return;                       // ~1/3 of carves become debris (TPS)
            BlockData data = saved.getBlockData();
            Vector out = ctr.toVector().subtract(pos);             // away from the orb centre
            if (out.lengthSquared() < 1.0e-6) {
                out = new Vector(rng.nextDouble(-1, 1), 0.5, rng.nextDouble(-1, 1));
            }
            out.normalize();
            Vector vel = out.multiply(0.4)
                    .add(new Vector(0, 0.35, 0))                   // upward kick
                    .add(new Vector((rng.nextDouble() - 0.5) * 0.16,
                                    rng.nextDouble() * 0.12,
                                    (rng.nextDouble() - 0.5) * 0.16)); // jitter
            FallingBlock fb = world.spawnFallingBlock(ctr, data);
            fb.setDropItem(false);
            fb.setHurtEntities(false);
            fb.setCancelDrop(true);
            fb.setPersistent(false);
            fb.addScoreboardTag(DEBRIS_TAG);
            fb.setVelocity(vel);
            debrisCount++;
            plugin.getServer().getScheduler().runTaskLater(plugin, fb::remove, DEBRIS_LIFE);
        }

        /** Damage anything the orb touches — devastating, shield-blockable, knockback-fenced, once each. */
        private void damageSphere(Location center) {
            for (Entity e : world.getNearbyEntities(center, BALL_HIT_R, BALL_HIT_R, BALL_HIT_R)) {
                if (!(e instanceof LivingEntity le)) continue;
                if (le.getUniqueId().equals(wielder.getUniqueId())) continue;
                if (struck.contains(le.getUniqueId())) continue;
                if (le.getLocation().add(0, le.getHeight() * 0.5, 0).distanceSquared(center) > BALL_HIT_R * BALL_HIT_R) {
                    continue;
                }
                if (shieldBlocks(le, dir)) {
                    shieldBlockFx(le, dir);
                    struck.add(le.getUniqueId());
                    continue;
                }
                damageNoKb(le, ULT_DAMAGE, wielder);
                struck.add(le.getUniqueId());
                world.playSound(le.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.7f);
                // ON-KILL STRIKE-BACK: if the orb killed anyone, it turns on its wielder — once per ult.
                if (!struckBack && (le.isDead() || le.getHealth() <= 0.0) && wielder.isOnline()) {
                    struckBack = true;
                    Location from = le.getBoundingBox().getCenter().toLocation(world);
                    new StrikeBackComet(wielder, world, from).runTaskTimer(plugin, 1L, 1L);
                }
            }
        }

        /**
         * FORCE-send a dust particle (long-distance). The orb flies far from the wielder, and this Paper
         * build's no-force {@code spawnParticle} default is {@code force=false} — the server then transmits
         * particles only to players within 32 blocks and the client culls them past 32, so the ball would
         * vanish the moment the viewer is more than ~32 blocks from it. Forcing sends/renders it out to 512.
         */
        private void dustF(Location p, int n, double s, Particle.DustOptions o) {
            world.spawnParticle(Particle.DUST, p, n, s, s, s, 0.0, o, true);
        }

        /** FORCE-send a data-less particle (SMOKE / LARGE_SMOKE) long-distance — same reason as {@link #dustF}. */
        private void smokeF(Particle particle, Location p, int n, double s, double extra) {
            world.spawnParticle(particle, p, n, s, s, s, extra, null, true);
        }

        /** A dense BLUE core wrapped in a churning BLACK-FLAME shell (smoke + near-black dust), plus a trail. */
        private void drawBall(Location loc) {
            double swirl = age * 0.5;
            // Black-flame shell — a swirling sphere of smoke + near-black dust shrouding the core.
            int shell = 64;
            for (int i = 0; i < shell; i++) {
                double y = 1.0 - (i / (double) (shell - 1)) * 2.0;   // -1..1
                double rr = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double ang = i * 2.399963229728653 + swirl;          // golden angle + rotation = churn
                double x = Math.cos(ang) * rr, z = Math.sin(ang) * rr;
                Location p = loc.clone().add(x * BALL_VIS_R, y * BALL_VIS_R, z * BALL_VIS_R);
                dustF(p, 1, 0, BALL_FLAME);
                if (i % 3 == 0) smokeF(Particle.SMOKE, p, 1, 0.06, 0.01);
                if (i % 5 == 0) smokeF(Particle.LARGE_SMOKE, p, 1, 0.05, 0.0);
            }
            // Blue churning core.
            dustF(loc, 26, BALL_VIS_R * 0.45, BALL_DEEP);
            dustF(loc, 14, BALL_VIS_R * 0.25, BALL_LIGHT);
            // A thick black-flame trail dropped behind the orb, laced with blue.
            Location tail = loc.clone().subtract(dir.clone().multiply(BALL_VIS_R * 0.9));
            smokeF(Particle.SMOKE, tail, 14, BALL_VIS_R * 0.5, 0.01);
            dustF(tail, 14, BALL_VIS_R * 0.5, BALL_FLAME);
            dustF(tail, 8, BALL_VIS_R * 0.4, BALL_DEEP);
        }

        /** Normal end-of-life: a dissipation burst, then the downtime begins. */
        private void dissipate() {
            if (finished) return;
            finished = true;
            Location loc = pos.toLocation(world);
            dustF(loc, 50, BALL_VIS_R, BALL_DEEP);
            dustF(loc, 30, BALL_VIS_R, BALL_FLAME);
            smokeF(Particle.SMOKE, loc, 40, BALL_VIS_R, 0.02);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.5f);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.4f);
            activeBalls.remove(this);
            if (state.ultBall == this) {
                state.ultBall = null;
                state.casting = false;
                state.downtimeUntil = System.currentTimeMillis() + DOWNTIME_MS; // then the counter resets
            }
            cancel();
            // Carved blocks restore on their own ~12.5s timers (they should linger visibly for a beat).
        }

        /**
         * Shutdown / quit-mid-flight cleanup: restore THIS orb's outstanding carves immediately, clear the
         * casting flag, and stop the task. Called from {@link #onQuit(UUID)}.
         */
        void flushOwn() {
            finished = true;
            for (Location keyLoc : myCarves) {
                BlockState bs = pendingCarves.remove(keyLoc);
                if (bs != null) bs.update(true, false); // pop it back, contents intact — no VFX
            }
            myCarves.clear();
            activeBalls.remove(this);
            if (state.ultBall == this) {
                state.ultBall = null;
                state.casting = false;
            }
            try { cancel(); } catch (IllegalStateException ignored) { /* already stopped */ }
        }
    }

    /** Restore a temp-carved block after ~12.5s, unless a shutdown flush already handled it. */
    private void scheduleUltRestore(Location keyLoc, BlockState saved) {
        long delay = ULT_RESTORE_TICKS + ThreadLocalRandom.current().nextInt(30);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingCarves.remove(keyLoc) == null) return;     // a flush/quit already restored it
            Block b = keyLoc.getBlock();
            if (b.getType() == Material.AIR) {
                saved.update(true, false);                         // pop it back, contents intact
                Location ctr = keyLoc.clone().add(0.5, 0.5, 0.5);
                b.getWorld().spawnParticle(Particle.DUST, ctr, 3, 0.3, 0.3, 0.3, 0, DEEP_DUST);
            }
        }, delay);
    }

    /** True if this block may be temporarily carved (solid, breakable, not protected/special). */
    private boolean isTempBreakable(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid()) return false; // skip air, liquids, non-solids
        // Never carve containers — restoring the block wouldn't guarantee their contents, so leave
        // chests/barrels/furnaces/hoppers/shulkers/etc. untouched (no grief).
        if (b.getState() instanceof org.bukkit.inventory.InventoryHolder) return false;
        return switch (m) {
            case BEDROCK, BARRIER, LIGHT, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, JIGSAW, END_PORTAL_FRAME, END_PORTAL, END_GATEWAY, NETHER_PORTAL,
                 REINFORCED_DEEPSLATE, SPAWNER, OBSIDIAN, CRYING_OBSIDIAN -> false;
            default -> true;
        };
    }

    // ---- magic circle VFX ----------------------------------------------------------

    /** The circle sizes for the current shot-in-cycle (1..6), in draw order (tip → forward). */
    private double[] circlesForShot(int shot) {
        return switch (shot) {
            case 2 -> new double[]{R_SMALL};
            case 3 -> new double[]{R_SMALL, R_SMALL};
            case 4 -> new double[]{R_SMALL, R_MED};
            case 5 -> new double[]{R_MED, R_MED, R_SMALL};
            case 6 -> new double[]{R_MED, R_LARGE, R_SMALL};
            default -> new double[]{}; // shot 1 → no circle
        };
    }

    /** Render this shot's nested, rotating magic circles at the gun tip, inscribed by the charge fraction. */
    private void renderCircles(Player player, State st, double frac) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location tip = eye.clone().add(dir.clone().multiply(GUN_TIP));

        double[] radii = circlesForShot(st.cycle + 1);
        for (int i = 0; i < radii.length; i++) {
            // Stagger each disc well forward along the aim so the big circles nest with real depth.
            Location center = tip.clone().add(dir.clone().multiply(i * 1.1));
            double spin = st.spin * ((i % 2 == 0) ? 1.0 : -1.0); // neighbours counter-rotate for depth
            drawMagicCircle(world, center, dir, radii[i], spin, frac);
        }
    }

    /**
     * A reusable ornate arcane sigil in BLUE: deep-blue rings and spokes, light-blue highlights where the
     * light catches the glyph, a brighter light-blue "pen" at the drawing edge. Magic Bullet's signature
     * BLACK FLAME is woven in as an atmospheric accent — near-black DUST + SMOKE wisps curling around the
     * rim — never as the ring material. No white.
     *
     * <p><b>Written-formation animation</b> — {@code frac} (0..1) is the fraction of the sigil inscribed so
     * far: the outer/inner rings are drawn as an <i>arc</i> sweeping from angle 0 up to {@code frac·2π} (the
     * "pen" is the brightest light-blue point at the leading edge); the radial spokes appear one-by-one as
     * {@code frac} crosses evenly-spaced thresholds; and the tangential rune ticks fill in last (only in the
     * final quarter). At {@code frac≥1} the whole sigil is complete and, driven by {@code spinPhase},
     * rotates.
     *
     * @param facingAxis the aim direction the circle faces down (the plane normal)
     * @param spinPhase  rotation phase — advance it each tick for a spinning glyph
     * @param frac       0..1 charge fraction driving both the sweep and which features are drawn
     */
    private void drawMagicCircle(World world, Location center, Vector facingAxis,
                                 double radius, double spinPhase, double frac) {
        double f = Math.max(0.0, Math.min(1.0, frac));
        if (f <= 0.001) return;
        double eff = radius * (0.9 + 0.1 * f);   // essentially full size — it is drawn, not grown
        if (eff < 0.05) return;
        Vector[] b = perp(facingAxis);
        Vector u = b[0], v = b[1];
        double inner = eff * 0.6;
        double sweep = 2 * Math.PI * f;          // how much of the ring is inscribed so far

        // Outer ring — DEEP-BLUE base drawn as a sweeping arc, point count capped for TPS.
        int outPts = Math.max(24, Math.min(64, (int) Math.round(eff * 16)));
        double aStep = (2 * Math.PI) / outPts;
        int idx = 0;
        for (double a = 0.0; a <= sweep + 1.0e-9; a += aStep, idx++) {
            double ang = spinPhase + a;
            world.spawnParticle(Particle.DUST, ringPoint(center, u, v, ang, eff), 1, 0, 0, 0, 0, DEEP_DUST);
            if (idx % 6 == 0) { // light-blue highlight where the light catches the rim
                world.spawnParticle(Particle.DUST, ringPoint(center, u, v, ang, eff), 1, 0, 0, 0, 0, LIGHT_DUST);
            }
        }
        // The inscribing "pen tip" — the brightest light-blue point, at the leading edge of the sweep.
        world.spawnParticle(Particle.DUST, ringPoint(center, u, v, spinPhase + sweep, eff), 1, 0, 0, 0, 0, BRITE_DUST);

        // Inner ring — light-blue accent, counter-swept.
        int inPts = Math.max(18, Math.min(48, (int) Math.round(inner * 16)));
        double inStep = (2 * Math.PI) / inPts;
        for (double a = 0.0; a <= sweep + 1.0e-9; a += inStep) {
            world.spawnParticle(Particle.DUST, ringPoint(center, u, v, -spinPhase + a, inner), 1, 0, 0, 0, 0, LIGHT_DUST);
        }

        // Radial rune spokes — appear one-by-one as the charge crosses evenly-spaced thresholds.
        int spokes = 8;
        for (int s = 0; s < spokes; s++) {
            if (f < (s + 1) / (double) (spokes + 2)) continue; // the last spokes finish near full charge
            double a = spinPhase + (2 * Math.PI * s) / spokes;
            double cos = Math.cos(a), sin = Math.sin(a);
            for (double t = inner; t <= eff + 1.0e-6; t += 0.22) {
                Location p = center.clone()
                        .add(u.clone().multiply(cos * t))
                        .add(v.clone().multiply(sin * t));
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, DEEP_DUST);
            }
            // A light-blue cap where the spoke meets the outer ring.
            world.spawnParticle(Particle.DUST, ringPoint(center, u, v, a, eff), 1, 0, 0, 0, 0, LIGHT_DUST);
        }

        // Tangential rune ticks — the last flourish, filling in only across the final quarter of the draw.
        if (f > 0.72) {
            double tf = (f - 0.72) / 0.28;                 // 0..1 across the closing quarter
            int ticks = 12;
            int show = (int) Math.round(ticks * Math.min(1.0, tf));
            double tickR = eff * 1.08;
            for (int i = 0; i < show; i++) {
                double a = -spinPhase * 0.5 + (2 * Math.PI * i) / ticks;
                Vector radial = u.clone().multiply(Math.cos(a)).add(v.clone().multiply(Math.sin(a)));
                Vector tang = u.clone().multiply(-Math.sin(a)).add(v.clone().multiply(Math.cos(a)));
                if (tang.lengthSquared() > 1.0e-9) tang.normalize();
                Location base = center.clone().add(radial.multiply(tickR));
                for (double d = -0.12; d <= 0.12 + 1.0e-6; d += 0.12) {
                    world.spawnParticle(Particle.DUST, base.clone().add(tang.clone().multiply(d)),
                            1, 0, 0, 0, 0, LIGHT_DUST);
                }
            }
        }

        // BLACK FLAME accent — a few near-black wisps licking off the drawn rim, curling upward. Sparse so it
        // atmospheres the sigil without ever reading as the ring itself; only once the glyph is taking shape.
        if (f > 0.25) {
            int wisps = 3;
            for (int i = 0; i < wisps; i++) {
                double a = spinPhase * 1.3 + (2 * Math.PI * i) / wisps + Math.sin(spinPhase + i) * 0.4;
                if (a - spinPhase > sweep + 0.3) continue;  // only where the ring has been inscribed
                Location p = ringPoint(center, u, v, a, eff * (1.0 + 0.06 * Math.sin(spinPhase * 2 + i)));
                world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, FLAME_DUST);
                world.spawnParticle(Particle.SMOKE, p, 1, 0.04, 0.08, 0.04, 0.01);
            }
        }
    }

    private Location ringPoint(Location c, Vector u, Vector v, double a, double r) {
        return c.clone()
                .add(u.clone().multiply(Math.cos(a) * r))
                .add(v.clone().multiply(Math.sin(a) * r));
    }

    /** The dramatic release: the sigil snaps to full, a light-blue flash-ring blooms off the muzzle. */
    private void fireBloom(Player player, Location muzzle, Vector dir, int shot) {
        World world = player.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // One last full, complete circle-bloom at the tip.
        double[] radii = circlesForShot(shot);
        Location tip = player.getEyeLocation().add(dir.clone().multiply(GUN_TIP));
        for (int i = 0; i < radii.length; i++) {
            Location center = tip.clone().add(dir.clone().multiply(i * 1.1));
            drawMagicCircle(world, center, dir, radii[i] * 1.1, rng.nextDouble(0, Math.PI * 2), 1.0);
        }

        // A bright light-blue flash-ring + a deep-blue dust burst blowing off the muzzle.
        Vector[] basis = perp(dir);
        int flashPts = 22;
        for (int i = 0; i < flashPts; i++) {
            double a = (2 * Math.PI * i) / flashPts;
            Location p = muzzle.clone()
                    .add(basis[0].clone().multiply(Math.cos(a) * 0.5))
                    .add(basis[1].clone().multiply(Math.sin(a) * 0.5));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, BRITE_DUST);
        }
        world.spawnParticle(Particle.DUST, muzzle, 14, 0.15, 0.15, 0.15, 0, TRACER);
        // Black flame belching off the muzzle on the shot.
        world.spawnParticle(Particle.SMOKE, muzzle, 12, 0.18, 0.18, 0.18, 0.03);
        world.spawnParticle(Particle.DUST, muzzle, 8, 0.2, 0.2, 0.2, 0, FLAME_DUST);

        // A deep magical BOOM layered with a musket crack.
        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.55f + rng.nextFloat() * 0.08f);
        world.playSound(muzzle, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.65f + rng.nextFloat() * 0.12f);
        world.playSound(muzzle, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 0.7f);
        world.playSound(muzzle, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5f, 1.1f);
    }

    /** A crisp light-blue tracer beam from muzzle to impact. */
    private void drawTracer(World world, Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);
        int idx = 0;
        for (double d = 0.0; d < length; d += 0.4, idx++) {
            Location p = from.clone().add(step.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, TRACER);
            if ((idx % 4) == 0) world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, DEEP_DUST);
        }
    }

    /** A small blue flare where the shot lands. */
    private void impactFx(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 8, 0.18, 0.18, 0.18, 0, DEEP_DUST);
        world.spawnParticle(Particle.DUST, at, 6, 0.12, 0.12, 0.12, 0, LIGHT_DUST);
        world.playSound(at, Sound.ENTITY_ARROW_HIT, 0.6f, 1.4f);
    }

    /** The charge start — a demonic musket: the mechanism cranks back as hellfire catches in the barrel. */
    private void chargeStartFx(Player player, int shot) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location tip = eye.clone().add(dir.clone().multiply(GUN_TIP));
        world.playSound(tip, Sound.ITEM_CROSSBOW_LOADING_START, 0.6f, 0.6f);         // the mechanism draws back (soft)
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.4f); // a low demonic growl, sustained
        world.playSound(tip, Sound.ENTITY_BLAZE_BURN, 0.6f, 0.5f);                   // black flame catches in the barrel
        world.spawnParticle(Particle.DUST, tip, 4, 0.08, 0.08, 0.08, 0, LIGHT_DUST);
    }

    /** The charge building — no note-block, no chimes: just the barrel burning hotter under the sustained growl. */
    private void chargeTickFx(Player player, double frac) {
        World world = player.getWorld();
        if (world == null) return;
        Location tip = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(GUN_TIP));
        world.playSound(tip, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE,
                0.4f + (float) (frac * 0.3), 0.5f + (float) (frac * 0.2));           // hotter as it winds up
    }

    /** Two unit vectors spanning the plane perpendicular to u (the circle plane basis). */
    private Vector[] perp(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector a = n.clone().crossProduct(ref).normalize();
        Vector bb = n.clone().crossProduct(a).normalize();
        return new Vector[]{a, bb};
    }

    /** A small blue "locked" cue when a target is marked. */
    private void lockedCue(Player player, LivingEntity target) {
        World world = target.getWorld();
        Location c = target.getBoundingBox().getCenter().toLocation(world);
        Vector up = new Vector(0, 1, 0);
        drawMagicCircle(world, c, up, R_SMALL, 0.0, 1.0); // a flat rune ring on the target
        world.spawnParticle(Particle.DUST, c, 6, 0.25, 0.4, 0.25, 0, LIGHT_DUST);
        world.playSound(c, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
        world.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.6f, 1.4f);
        player.sendActionBar(EgoHud.status("Target locked — " + target.getName() + " · the bullet cannot miss.", NAME));
    }

    /** A short cue when the wielder tries to act during the post-ult downtime. */
    private void downtimeCue(Player player, long remainingMs) {
        player.sendActionBar(EgoHud.status("Magic Bullet - reloading in " + secs(remainingMs) + "s", FAINT));
        player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 0.5f);
    }

    private static long secs(long ms) {
        return ms / 1000 + 1;
    }

    // ---- cursed recoil bullet ------------------------------------------------------

    /**
     * The cursed recoil: a small blue comet that auto-aims BACK to the wielder's head and strikes it for a
     * token {@link #SELF_DAMAGE}-HP toll. PURE particle/math — it spawns NO entity, re-targets the head each
     * tick so it always lands, and self-cancels on the hit or at its {@link #RECOIL_LIFE} lifetime cap.
     */
    private final class RecoilBullet extends BukkitRunnable {
        private final Player shooter;
        private final World world;
        private double x, y, z;
        private int age = 0;

        RecoilBullet(Player shooter, World world, Location start) {
            this.shooter = shooter;
            this.world = world;
            this.x = start.getX();
            this.y = start.getY();
            this.z = start.getZ();
        }

        @Override
        public void run() {
            if (age++ >= RECOIL_LIFE || !shooter.isOnline() || shooter.isDead()) { cancel(); return; }
            Location head = shooter.getEyeLocation();
            Vector to = head.toVector().subtract(new Vector(x, y, z));
            double dist = to.length();

            if (dist <= RECOIL_HIT) {
                // Strike the head: a soft headshot burst + a small self-toll.
                world.spawnParticle(Particle.CRIT, head, 8, 0.12, 0.12, 0.12, 0.05);
                world.spawnParticle(Particle.DUST, head, 6, 0.12, 0.12, 0.12, 0, COMET);
                world.playSound(head, Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f, 1.2f);
                world.playSound(head, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.7f);
                shooter.damage(SELF_DAMAGE, shooter);
                cancel();
                return;
            }

            Vector velv = to.multiply(RECOIL_SPEED / Math.max(1e-6, dist));
            double stepLen = velv.length();
            Location from = new Location(world, x, y, z);
            x += velv.getX();
            y += velv.getY();
            z += velv.getZ();
            // A clean little blue comet trail.
            for (double d = 0.0; d < stepLen; d += 0.3) {
                Location p = from.clone().add(velv.clone().normalize().multiply(d));
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, COMET);
            }
            world.spawnParticle(Particle.DUST, new Location(world, x, y, z), 1, 0, 0, 0, 0, LIGHT_DUST);
        }
    }

    // ---- Seventh-Bullet on-kill strike-back ----------------------------------------

    /**
     * The Seventh Bullet's ON-KILL strike-back: a big, bright, ominous version of the cursed-recoil comet.
     * If the orb's AoE kills anyone, this launches from the kill site and homes back to the SHOOTER's head
     * over ~1s, then slams it for {@link #ULT_SELF_DAMAGE} with a heavy blue burst + boom. PURE particle/math
     * (spawns NO entity); re-targets the head each tick so it always lands; self-cancels on the hit or at its
     * {@link #STRIKEBACK_LIFE} cap. Fired at most once per ult (guarded by the ball's {@code struckBack}).
     */
    private final class StrikeBackComet extends BukkitRunnable {
        private final Player shooter;
        private final World world;
        private double x, y, z;
        private int age = 0;

        StrikeBackComet(Player shooter, World world, Location start) {
            this.shooter = shooter;
            this.world = world;
            this.x = start.getX();
            this.y = start.getY();
            this.z = start.getZ();
            world.playSound(start, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.6f); // an ominous launch
            world.spawnParticle(Particle.DUST, start, 30, 0.4, 0.4, 0.4, 0, BALL_DEEP);
        }

        @Override
        public void run() {
            if (age++ >= STRIKEBACK_LIFE || !shooter.isOnline() || shooter.isDead()) { cancel(); return; }
            Location head = shooter.getEyeLocation();
            Vector to = head.toVector().subtract(new Vector(x, y, z));
            double dist = to.length();

            if (dist <= STRIKEBACK_HIT) {
                // Slam the head: a heavy blue burst + an ominous wither/explode boom, and a crap-ton self-toll.
                world.spawnParticle(Particle.DUST, head, 40, 0.5, 0.5, 0.5, 0, BALL_DEEP);
                world.spawnParticle(Particle.DUST, head, 24, 0.4, 0.4, 0.4, 0, BALL_LIGHT);
                world.spawnParticle(Particle.SMOKE, head, 20, 0.4, 0.4, 0.4, 0.03);
                world.playSound(head, Sound.ENTITY_WITHER_HURT, 1.0f, 0.6f);
                world.playSound(head, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.5f);
                // Fenced like every other blow this musket throws: the wielder is the damager AND the
                // victim here, so onHit sees it too, and an unfenced cancel would quietly forgive the
                // whole toll — the vow's price is the point of the Seventh Bullet.
                shooting.add(shooter.getUniqueId());
                try {
                    shooter.damage(ULT_SELF_DAMAGE, shooter);
                } finally {
                    shooting.remove(shooter.getUniqueId());
                }
                cancel();
                return;
            }

            Vector velv = to.multiply(STRIKEBACK_SPEED / Math.max(1e-6, dist));
            double stepLen = velv.length();
            Location from = new Location(world, x, y, z);
            x += velv.getX();
            y += velv.getY();
            z += velv.getZ();
            // A thick, ominous blue comet trail with a black-flame shroud.
            Vector n = velv.clone().normalize();
            for (double d = 0.0; d < stepLen; d += 0.2) {
                Location p = from.clone().add(n.clone().multiply(d));
                world.spawnParticle(Particle.DUST, p, 2, 0.12, 0.12, 0.12, 0, BALL_DEEP);
                world.spawnParticle(Particle.DUST, p, 1, 0.1, 0.1, 0.1, 0, BALL_LIGHT);
                world.spawnParticle(Particle.SMOKE, p, 1, 0.08, 0.08, 0.08, 0.0);
            }
            world.spawnParticle(Particle.DUST, new Location(world, x, y, z), 3, 0.15, 0.15, 0.15, 0, BALL_FLAME);
        }
    }

    // ---- per-shot voicelines -------------------------------------------------------

    /** The seven verbatim voicelines, index 0..6 → the 1st..7th Magic Bullet (the 7th = the ult). */
    private static final String[] VOICELINES = {
        "It really is as you say; this is a magic bullet that will never miss.",
        "My bullet inevitably flies in the same direction, its trajectory preordained. There are no coincidences.",
        "There's no going back when I've already come this far by firing the bullet. Even if this road I walk is an inevitable path to inferno.",
        "Do not seek my mercy, for only desolation awaits those who stand in my path.",
        "Remain unshaken. Grant silence to all that stands before you. Follow the land horizon.",
        "The despairing heart is burnt black, never to fade away. Only the shearing cold floods within.",
        "Though it was despair that I sought, the bullet's trajectory… … is predetermined!"
    };

    /** Ordinal words for the bracket-title, index 0..6. */
    private static final String[] ORDINALS = {
        "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh"
    };

    /**
     * Broadcast the shot's voiceline to every player within 100 blocks of {@code shooter}, chat-line style:
     * {@code [The Nth Magic Bullet] <username>: "<line>"} — bracket-title + ": " in deep blue, the username
     * in bold white, the quoted line in light blue, italics off.
     */
    private void speakVoiceline(Player shooter, int shotNumber) {
        if (shotNumber < 1 || shotNumber > VOICELINES.length) return;
        Component msg = plain("[The " + ORDINALS[shotNumber - 1] + " Magic Bullet] ", NAME)
                .append(Component.text(shooter.getName(), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false))
                .append(plain(": ", NAME))
                .append(plain("\"" + VOICELINES[shotNumber - 1] + "\"", VOICE))
                .decoration(TextDecoration.ITALIC, false);
        double r2 = 100.0 * 100.0;
        Location at = shooter.getLocation();
        for (Player p : shooter.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= r2) {
                p.sendMessage(msg);
            }
        }
    }

    // ---- action-bar HUD ------------------------------------------------------------

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** The bracketed Bullet Counter meter — six normal slots + the Seventh-Bullet slot. */
    private Component bulletMeter(int bullets) {
        int filled = Math.max(0, Math.min(NORMAL_SHOTS, bullets));
        boolean ready = bullets >= NORMAL_SHOTS;
        Component bar = plain("[", FRAME)
                .append(plain(SEG.repeat(filled), NAME))
                .append(plain(SEG.repeat(NORMAL_SHOTS - filled), FRAME))
                .append(plain(SEG, ready ? NAME : FRAME))   // the 7th slot = the Seventh Bullet
                .append(plain("]  ", FRAME));
        if (ready) {
            bar = bar.append(plain("The Seventh Bullet — Shift-right-click", NAME));
        } else {
            bar = bar.append(plain("Bullet Counter ", NAME))
                    .append(plain(bullets + "/" + NORMAL_SHOTS, NamedTextColor.GRAY));
        }
        return bar.decoration(TextDecoration.ITALIC, false);
    }

    /** The charge readout while charging, with the Bullet Counter kept visible alongside it. */
    private Component chargeBar(double frac, int bullets) {
        Component label = plain(frac >= 1.0 ? "FIRE" : "Charging", NAME)
                .append(plain("   " + bullets + "/" + NORMAL_SHOTS + " bullets", NamedTextColor.GRAY));
        return EgoHud.gauge(NAME, frac, label);
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.MAGIC_BULLET.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.MAGIC_BULLET.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.MAGIC_BULLET);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Magic Bullet",     // display name — always the weapon
            "Der Freischütz",   // title line — always the Abnormality
            NAME,
            GLOW,
            // Nyrrine's wording, 2026-07-17, verbatim — only the line breaks are mine.
            List.of(
                    "Though the original's power couldn't be",
                    "fully extracted, the magic this holds is",
                    "still potent.",
                    "",
                    "The weapon's bullets travel across the",
                    "corridor, along the horizon."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Bullet Counter",
                            "Every shot fired is counted. After the",
                            "sixth, the normal shot is locked out and",
                            "only the Seventh Bullet remains."),
                    // "Shield Block" was listed here as a [Passive] of its own. It is not a passive and it
                    // is not the musket's — it is what someone else does to survive it. Nyrrine, 2026-07-17:
                    // "Do not mention shield block passives since it's a counter not a passive." The fact
                    // still matters to whoever is aiming, so it rides the shot it applies to.
                    new EgoLore.Ability("[Left Click] Charged Shot",
                            "Begins a 2.4 second charge, then looses",
                            "one shot down the aim at full charge.",
                            "13 second reload before the next. A",
                            "shield raised into it turns the bullet."),
                    new EgoLore.Ability("[Right Click] Target Lock",
                            "Marks the enemy you are looking at, or",
                            "the last one you struck. While the lock",
                            "holds the bullet cannot miss, hitting",
                            "through cover, and the orb homes onto",
                            "it. The lock lasts 60 seconds."),
                    new EgoLore.Ability("[Shift + Right-click] The Seventh Bullet",
                            "Forced once six shots are spent. A 15",
                            "second wind-up inscribes four circles,",
                            "then looses a huge homing orb: it tears",
                            "a temporary hole through all it passes",
                            "and devastates what it touches. A",
                            "shield raised into it turns the orb.",
                            "If the orb kills, it turns back on the",
                            "wielder for 8 hearts. Then 15 seconds",
                            "of downtime, and the counter resets.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // Clear ALL per-player state; cancel any in-flight ult and restore its carves so nothing leaks.
        State st = states.remove(id);
        if (st != null) {
            if (st.ultWindup != null) {
                try { st.ultWindup.cancel(); } catch (IllegalStateException ignored) { /* already stopped */ }
                st.ultWindup = null;
            }
            if (st.ultBall != null) {
                st.ultBall.flushOwn(); // restores its outstanding carves + cancels the task
            }
        }
    }

    @Override
    public void onDisable() {
        // Cancel every in-flight ult wind-up and orb, then restore ALL outstanding temp-carved blocks so a
        // /reload mid-flight never leaves a permanent hole. (This is the leak class fixed elsewhere.)
        for (State st : states.values()) {
            if (st.ultWindup != null) {
                try { st.ultWindup.cancel(); } catch (IllegalStateException ignored) { /* already stopped */ }
                st.ultWindup = null;
            }
            st.ultBall = null;
            st.casting = false;
        }
        for (SeventhBulletBall ball : new ArrayList<>(activeBalls)) {
            try { ball.cancel(); } catch (IllegalStateException ignored) { /* already stopped */ }
        }
        activeBalls.clear();
        for (BlockState bs : pendingCarves.values()) {
            bs.update(true, false); // pop it back, contents intact — no VFX during shutdown
        }
        pendingCarves.clear();
        // Sweep any leftover physics debris across all worlds so no FallingBlock leaks past a /reload.
        for (World w : plugin.getServer().getWorlds()) {
            for (FallingBlock fb : w.getEntitiesByClass(FallingBlock.class)) {
                if (fb.getScoreboardTags().contains(DEBRIS_TAG)) fb.remove();
            }
        }
    }
}
