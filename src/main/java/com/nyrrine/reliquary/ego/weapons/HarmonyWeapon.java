package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
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
 * Harmony — "Singing Machine" (Lobotomy Corp E.G.O Equipment, WAW).
 *
 * <p>A cannon that is really an instrument, and an instrument that is really a body. It looks like scrap:
 * a seized, rust-bled machine that rattles when it speaks. But what comes out of the barrel is
 * <b>music</b> — a single sustained note, loud enough to be a weapon, and the audience cannot look away.
 * The price of the performance is the performer. To make the machine sing properly you must open a vein
 * for it, and it will take that toll every time you ask. Art is a devil's gift; the machine simply
 * collects.
 *
 * <ul>
 *   <li><b>Passive — That… was Indeed a Rhythm</b>: the cannon charges slowly. A Note that connects while
 *       <b>Obsession</b> is up earns one stack of <b>Rhythm</b>, up to {@value #MAX_RHYTHM}. Each stack
 *       adds {@value #RHYTHM_DAMAGE_PER_STACK} to the Note, linearly — the machine finding its pitch. The
 *       chord climbs a whole tone per stack, and the seventh stack lands as a discordant shriek. Fall out
 *       of combat for {@value #COMBAT_TIMEOUT_MS}ms and the performance dies: every stack is lost.</li>
 *   <li><b>Left-click — Note</b>: a hitscan beam on a seven-second refresh, or a <b>four</b>-second one
 *       while Obsession burns. It pierces every body along its line (one damage instance each, no matter
 *       how the beam folds) and reaches {@value #NOTE_RANGE} blocks. This is the weapon's entire offence —
 *       there is no melee to speak of. While Obsession is up the beam <b>ricochets</b>, reflecting off
 *       block faces up to {@value #NOTE_RICOCHETS} times and spending the same travel budget around
 *       corners.</li>
 *   <li><b>Right-click — Obsession</b>: the stance toggle. Switching it ON costs
 *       {@code 10%} of the wielder's <b>current</b> HP and is refused unless they hold <b>more than</b>
 *       {@code 10%} of their <b>max</b> HP. While it burns: weapon damage {@code +30%}, the Note refreshes
 *       in four seconds instead of seven, the Note ricochets, and Rhythm can be earned at all. Switching
 *       it off is free and keeps the stacks.</li>
 * </ul>
 *
 * <h2>Balance</h2>
 * {@link EgoModels#HARMONY} is {@code ranged}, so the weapon's damage <i>is</i> the beam.
 * {@value #NOTE_DAMAGE} base, {@code +0.35} per Rhythm, {@code x1.30} under Obsession — so the ceiling is
 * {@code (6.0 + 7 x 0.35) x 1.30 = 10.985}, which lands just inside a Sharpness-V netherite sword's ~11
 * for the beam's single instance. Bare, with no stacks and no stance, it is {@value #NOTE_DAMAGE}.
 *
 * <p><b>The four-second Obsession refresh does not move that ceiling</b> — it is a per-instance number and
 * the instance is unchanged — but it does move the <i>rate</i>, so the sustained figures are worth writing
 * down. Against one body: {@code 6.0 / 7s = 0.86} DPS bare; {@code 8.45 / 7s = 1.21} DPS at seven stacks
 * with the stance dropped; and {@code 10.985 / 4s = 2.75} DPS at seven stacks under Obsession, up from
 * {@code 1.57} at the old seven-second clock — a {@code +75%} sustained increase for the stance, bought
 * with a tenth of the wielder's blood every time they enter it. The beam pierces up to
 * {@value #NOTE_MAX_TARGETS} bodies, so a perfectly-lined-up shot tops out at {@code 10.985 x 8 / 4s =
 * 21.97} DPS spread across eight targets — still one instance each. The faster clock also halves the
 * ramp: seven connecting shots to full Rhythm is now ~28s of Obsession rather than ~49s.
 *
 * <h2>Safety</h2>
 * The weapon <b>spawns no entities at all</b> — the Note is a raytrace and the visuals are particles — so
 * there is no entity set and no scoreboard-tag sweep to run; there is simply nothing that can be orphaned.
 * The only live objects are the short {@link NoteEcho} redraw tasks, which are tracked in {@link #echoes}
 * and cancelled on {@link #onDisable()}. Per-wielder state is keyed by player UUID and dropped in
 * {@link #onQuit(UUID)}; no map is ever keyed by a victim, so nothing can leak behind a mob that never
 * logs out. The beam's damage is fenced by {@link #ticking} (it re-enters this weapon's own
 * {@link #onHit} dispatch) and routed through {@code victim.damage(...)} so protection plugins can still
 * cancel it. The Obsession toll can never be lethal: the gate guarantees the wielder is above 10% max HP,
 * and the toll only ever removes 10% of what they currently hold. No block is ever changed.
 */
public final class HarmonyWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    // ---- per-wielder state ---------------------------------------------------------

    /** Wielder -> their live performance (Rhythm, stance, clocks). Keyed by player, dropped on quit. */
    private final Map<UUID, Performance> performances = new HashMap<>();

    /**
     * Victims currently taking a Note's damage. The beam's {@code victim.damage(...)} re-enters this
     * weapon's own {@link #onHit} dispatch, so this fence makes onHit refuse to treat the beam as a fresh
     * melee strike. Entries are added and removed inside a try/finally around the single damage call, so
     * this set is empty between shots and cannot accumulate a dead mob's UUID.
     */
    private final Set<UUID> ticking = new HashSet<>();

    /** Live note-echo redraw tasks, tracked so {@link #onDisable()} can cancel every one. */
    private final Set<NoteEcho> echoes = new HashSet<>();

    // ---- tuning: Rhythm ------------------------------------------------------------

    private static final int    MAX_RHYTHM               = 7;      // the spec's cap — seven stacks, no more
    /**
     * What one stack of Rhythm is worth on the Note, added linearly. Sized off the ceiling: seven stacks
     * plus Obsession's +30% must stay inside the netherite band, so 0.35 puts the maximum at
     * (6.0 + 7 x 0.35) x 1.30 = 10.985 — just under a Sharpness-V netherite sword's ~11.
     */
    private static final double RHYTHM_DAMAGE_PER_STACK  = 0.35;
    private static final long   COMBAT_TIMEOUT_MS        = 60_000L; // a minute out of combat and the music stops

    // ---- tuning: Note --------------------------------------------------------------

    private static final double NOTE_DAMAGE       = 6.0;    // the bare beam, no stacks, no stance
    private static final long   NOTE_COOLDOWN_MS  = 7000L;  // "refreshes every 7 seconds"
    /**
     * The Note's refresh <b>while Obsession burns</b>. The machine, run on blood, plays 75% more often;
     * the shot itself is untouched, so the per-instance balance band is exactly where it was. See the
     * class Balance note for the sustained figures this buys.
     */
    private static final long   NOTE_COOLDOWN_OBSESSED_MS = 4000L;
    private static final double NOTE_RANGE        = 40.0;   // total travel budget, shared across ricochets
    private static final double NOTE_RADIUS       = 1.1;    // how near the line a body must be to be struck
    private static final int    NOTE_MAX_TARGETS  = 8;      // cap on bodies one shot can pierce
    private static final int    NOTE_RICOCHETS    = 4;      // wall bounces, Obsession only
    private static final double RICOCHET_EPSILON  = 0.05;   // restart offset off the wall, so we don't re-hit it
    private static final double SEGMENT_MIN       = 0.05;   // a segment shorter than this is not worth resolving

    // ---- tuning: Obsession ---------------------------------------------------------

    private static final double OBSESSION_DAMAGE_MULT = 1.30;  // "+30% weapon damage"
    private static final double OBSESSION_HP_COST     = 0.10;  // toll: 10% of CURRENT hp, taken once, on toggle-on
    private static final double OBSESSION_HP_GATE     = 0.10;  // refused at or below 10% of MAX hp
    /**
     * Belt-and-braces floor under the toll. {@link #OBSESSION_HP_GATE} already makes the toll unable to
     * reach zero (it removes a tenth of a number that is itself above a tenth of max), so this never binds
     * today — it is here so no future retune of the gate can quietly make the stance suicidal.
     */
    private static final double OBSESSION_HP_FLOOR    = 0.5;

    // ---- tuning: presentation ------------------------------------------------------

    private static final double NOTE_VFX_START   = 0.9;   // start the beam line off the wielder's face
    private static final double NOTE_VFX_STEP    = 0.5;   // base spacing of the beam's core motes
    private static final int    HUD_PERIOD_TICKS = 3;     // refresh the action bar every 3rd weapon tick
    private static final int    HUM_PERIOD_TICKS = 6;     // Obsession's idle drone cadence
    private static final int    CHORD_ROOT_NOTE  = 6;     // note-block note index the chord starts on
    private static final int    CHORD_STEP       = 2;     // semitones the chord climbs per stack of Rhythm

    // ---- tuning: the echo (how long the note hangs, and what it costs) --------------
    // The ask was "linger longer and look bigger". Both are paid for out of RATE, not out of raw counts:
    // the note now hangs more than twice as long, every mote in it is roughly 70% larger, and the beam's
    // peak per-tick particle spend is LOWER than it was, because the line thins as it decays and a hard
    // ceiling caps the densest frame. See NOTE_ECHO_MAX_MOTES for the arithmetic.

    /** How long the fired note lingers: 16 ticks (0.8s), up from 7. The sustain, then the decay. */
    private static final int    NOTE_ECHO_TICKS      = 16;
    /** How fast the sustain thins out per tick — the mote spacing grows by this fraction each tick. */
    private static final double NOTE_ECHO_THIN       = 0.5;
    /**
     * <b>Hard per-tick ceiling</b> on the beam's core motes, across the whole folded polyline.
     *
     * <p>This is the number that makes a 100-player roster survive a "bigger, lingering" beam. The draw
     * step is {@code max(baseStep x growth, pathLength / this)}, so no matter how long the path is or how
     * young the echo is, one echo can never draw more than this many core motes in a tick. At the full
     * {@value #NOTE_RANGE}-block budget that pins the densest frame (age 0) to a 0.67-block step.
     *
     * <p>Ceilings that follow from it, per echo per tick: {@value} core, ~1/3 that in edge motes, ~1/8 in
     * music notes (and only on the first few ticks), ~1/7 in sparks (age 0 only) — so ~97 particles on the
     * loudest tick, against ~135 on the old 7-tick echo's loudest tick. Whole-shot total is ~526 particles
     * spread over 16 ticks (~33/tick average) against the old ~381 over 7 ticks (~54/tick average): 38%
     * more note, 39% less rate.
     */
    private static final int    NOTE_ECHO_MAX_MOTES  = 60;
    /** Music notes are emitted over the first few ticks of the echo (every other one), not just the attack. */
    private static final int    NOTE_ECHO_NOTE_TICKS = 6;
    /** One music note per this many core motes — the notes garnish the beam, they do not build it. */
    private static final int    NOTE_ECHO_NOTE_EVERY = 8;

    /**
     * Cap on the music notes any single burst may spawn. Coloured notes cost one packet each (see
     * {@link #note}), so every caller must be capped rather than trusted.
     */
    private static final int    NOTE_BURST_CAP       = 12;

    // ---- palette -------------------------------------------------------------------
    // Grey machine and rust-red blood. The tooltip takes the two spec colours verbatim; the action bar and
    // the particles use readable stand-ins where the true rust (#6b0000) would sit too near black to see.

    private static final TextColor STEEL = TextColor.color(0x808080); // primary — the machine's grey
    private static final TextColor RUST  = TextColor.color(0x6b0000); // secondary — dried blood
    private static final TextColor EMBER = TextColor.color(0xB4232B); // readable stand-in for RUST on the HUD
    private static final TextColor FAINT = TextColor.color(0x6E6E6E); // idle / denial text

    private static final Color C_STEEL = Color.fromRGB(0x80, 0x80, 0x80); // the note itself
    private static final Color C_RUST  = Color.fromRGB(0x6b, 0x00, 0x00); // the blood in it
    private static final Color C_BLOOD = Color.fromRGB(0x8E, 0x0B, 0x0B); // a shade up from RUST so it renders

    // Sizes are the "look bigger" half of the ask. DustOptions size is clamped client-side at 4.0; these
    // sit well inside that. Raising size costs nothing — it is one float in a packet we were sending
    // anyway — which is exactly why the ask is paid for here rather than in counts.
    private static final Particle.DustOptions NOTE_CORE = new Particle.DustOptions(C_STEEL, 1.7f);
    private static final Particle.DustOptions NOTE_EDGE = new Particle.DustOptions(C_BLOOD, 1.4f);
    private static final Particle.DustOptions RUST_FINE = new Particle.DustOptions(C_RUST, 1.1f);
    /** Obsession's core: the note bleeds out of the wielder and pales into the machine's grey. */
    private static final Particle.DustTransition NOTE_BLED = new Particle.DustTransition(C_BLOOD, C_STEEL, 2.0f);

    // ---- the three music notes -----------------------------------------------------

    /**
     * The only three colours a music note is ever allowed to be: <b>yellow, red, green</b>.
     *
     * <p><b>How a NOTE particle's colour is actually chosen.</b> {@link Particle#NOTE} takes no data class
     * — there is no {@code DustOptions} to hand it, and passing one is a runtime crash. The client derives
     * the colour from the particle's <b>x velocity</b> ({@code f} below), as a three-phase sine:
     * <pre>
     *   r = max(0, sin((f + 0/3) x 2PI) x 0.65 + 0.35)
     *   g = max(0, sin((f + 1/3) x 2PI) x 0.65 + 0.35)
     *   b = max(0, sin((f + 2/3) x 2PI) x 0.65 + 0.35)
     * </pre>
     * and the only way to set that velocity from the server is the {@code count = 0} form of the particle
     * packet — see {@link #note}. Solving the three phases for the colours asked for gives exactly the
     * note-block indices below:
     * <ul>
     *   <li>{@code 2/24} -> (0.675, 0.675, 0) = <b>RGB(172, 172, 0)</b>, yellow. This is the brightest
     *       yellow the curve can produce: r and g are only equal where the two sines cross, and they cross
     *       at 0.675.</li>
     *   <li>{@code 6/24} -> (1.0, 0.025, 0.025) = <b>RGB(255, 6, 6)</b>, red.</li>
     *   <li>{@code 22/24} -> (0.025, 1.0, 0.025) = <b>RGB(6, 255, 6)</b>, green.</li>
     * </ul>
     * No fourth colour can leak in: the hue is not sampled, it is stated, and it is only ever stated from
     * this array.
     */
    private static final double[] NOTE_HUES = { 2.0 / 24.0, 6.0 / 24.0, 22.0 / 24.0 }; // yellow, red, green

    // ---- the twisted harmony -------------------------------------------------------

    /**
     * The chords the machine fires on, as semitone offsets from whatever root the current Rhythm has
     * climbed to. Every one of them is built to <b>refuse to resolve</b>: tritones, minor seconds and
     * major sevenths, the intervals a tuned instrument is written to avoid. That is the twist — the
     * machine knows the shape of harmony and gets it deliberately wrong.
     *
     * <p>Offsets are folded back into the note-block's 0..24 range by {@link #fold}, so a high Rhythm root
     * drops the offending voice an octave rather than clamping it flat onto the root and quietly losing
     * the dissonance.
     */
    private static final int[][] VOICINGS = {
            { 0, 6, 11 },   // root + tritone + major seventh — the chord straining and never landing
            { 0, 1, 6 },    // root + minor second + tritone — the shriek, in embryo
            { 0, 6, 13 },   // root + tritone + minor ninth — the same clash, opened out an octave
            { 0, 11, 14 },  // root + major seventh + major ninth — top-heavy, hollow underneath
            { 0, 5, 6 },    // root + fourth + tritone — the fourth pulled a semitone out of true
    };

    /** The timbres the machine sings in. Two are drawn per shot, so no two shots share a voice pair. */
    private static final Sound[] VOICES = {
            Sound.BLOCK_NOTE_BLOCK_BASS,
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,
            Sound.BLOCK_NOTE_BLOCK_BIT,
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE,
            Sound.BLOCK_NOTE_BLOCK_PLING,
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,
    };

    /** The body underneath the music: deteriorating parts, complaining. Never the same complaint twice. */
    private static final Sound[] FRAMES = {
            Sound.BLOCK_GRINDSTONE_USE,
            Sound.BLOCK_ANVIL_USE,
            Sound.BLOCK_CHAIN_HIT,
            Sound.BLOCK_PISTON_CONTRACT,
            Sound.ITEM_AXE_SCRAPE,
    };

    /** Five sounds per shot, hard: three voices, the boom, and one frame rattle. */
    private static final int MUZZLE_SOUNDS = 5;

    public HarmonyWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "harmony");
    }

    @Override
    public String id() {
        return "harmony";
    }

    /**
     * One wielder's performance. {@code lastNote} and {@code lastCombat} are epoch millis with {@code 0}
     * meaning "never".
     */
    private static final class Performance {
        int     rhythm      = 0;
        boolean obsession   = false;
        long    lastNote    = 0L;
        long    lastCombat  = 0L;
        /** The voicing this wielder's last shot used, so the next one can refuse to repeat it. */
        int     lastVoicing = -1;
    }

    private Performance performance(Player player) {
        return performances.computeIfAbsent(player.getUniqueId(), k -> new Performance());
    }

    /** The wielder is performing — refresh the clock the out-of-combat decay reads. */
    private static void markCombat(Performance perf) {
        perf.lastCombat = System.currentTimeMillis();
    }

    /**
     * Anything that hurts the wielder is an audience: a mob's teeth, a player's blade, a fall, a fire. The
     * machine only asks whether the performance is still live, so every cause counts and the settled amount
     * is irrelevant — a strike turned aside by a shield still means someone is out there swinging, and the
     * music should not stop because the wielder blocked well.
     *
     * <p>Only damage the wielder truly suffers arrives here, so Obsession's toll — paid with
     * {@code setHealth}, which never enters the damage chain — can't be mistaken for an audience. The
     * machine bleeding you is not a fight.
     */
    @Override
    public void onDamaged(Player victim, EntityDamageEvent event) {
        markCombat(performance(victim));
    }

    /** Milliseconds left on a cooldown whose last use was {@code last} ({@code 0} = never used). */
    private static long remaining(long last, long cooldown, long now) {
        if (last == 0L) return 0L;
        return Math.max(0L, cooldown - (now - last));
    }

    /**
     * The Note's refresh right now: seven seconds, or four while Obsession burns.
     *
     * <p>Read <b>live off the stance</b> rather than snapshotted at the shot, so the gate and the action
     * bar can never disagree with the stance the wielder is actually standing in. The consequence is
     * deliberate and reads correctly in both directions: taking Obsession mid-wait shortens the remaining
     * wait to the four-second clock, and dropping it lengthens the wait back out to seven. The stance is
     * the thing being paid for in blood, so the stance is what the clock answers to.
     */
    private static long noteCooldown(Performance perf) {
        return perf.obsession ? NOTE_COOLDOWN_OBSESSED_MS : NOTE_COOLDOWN_MS;
    }

    // ---- the Note (left-click) -----------------------------------------------------

    /**
     * LEFT-click fires the Note. Only ever driven by a main-hand Harmony.
     *
     * <p>The fallback item is a CROSSBOW, but vanilla's crossbow never gets a look-in: charging a crossbow
     * needs a right-click, and {@code WeaponManager} cancels the {@link org.bukkit.event.player.PlayerInteractEvent}
     * for any registered weapon before dispatching {@link #onInteract} — so the bow can never be drawn, can
     * never be loaded, and can never loose a vanilla bolt. The same pattern Solemn Lament relies on.
     */
    @Override
    public void onSwing(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        Performance perf = performance(player);
        long now = System.currentTimeMillis();
        if (remaining(perf.lastNote, noteCooldown(perf), now) > 0) {
            // Still winding: the machine only clicks. The bar already shows the whole-second remainder.
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.35f, 0.7f);
            hud(player, perf);
            return;
        }

        perf.lastNote = now;
        fireNote(player, perf);
        EgoDurability.wearMainHand(player); // a ranged shot never goes through a vanilla swing — wear it here
        hud(player, perf);
    }

    /** What the Note hits for right now: base, plus Rhythm linearly, times Obsession's multiplier. */
    private static double noteDamage(Performance perf) {
        double dmg = NOTE_DAMAGE + perf.rhythm * RHYTHM_DAMAGE_PER_STACK;
        return perf.obsession ? dmg * OBSESSION_DAMAGE_MULT : dmg;
    }

    /**
     * Resolve and loose one Note.
     *
     * <p>The beam is <b>hitscan</b>: the whole flight path is walked here, this instant, and every body on
     * it takes its damage before the method returns. Under Obsession the path folds — each wall the ray
     * meets reflects it about that block's face normal and the beam carries on with whatever travel budget
     * is left, up to {@value #NOTE_RICOCHETS} bounces. Without Obsession it is a single straight segment
     * that dies on the first wall.
     *
     * <p>A body is struck at most once per shot ({@code struck}), no matter how many times the folded beam
     * crosses it — the balance band is written per damage instance, and a corner that happened to bounce
     * the note back through the same target should not silently double it.
     *
     * <p>The finished polyline is handed to a {@link NoteEcho} so the note is <i>sustained</i> on screen
     * for {@value #NOTE_ECHO_TICKS} ticks rather than flashing for one; the echo is pure visuals and deals
     * nothing.
     */
    private void fireNote(Player player, Performance perf) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        boolean obsession = perf.obsession;
        double damage = noteDamage(perf);

        muzzleFx(world, eye, perf);

        Set<UUID> struck = new HashSet<>();
        List<Segment> path = new ArrayList<>();
        Location origin = eye.clone();
        double budget = NOTE_RANGE;
        int bounces = obsession ? NOTE_RICOCHETS : 0;

        for (int seg = 0; seg <= bounces && budget > SEGMENT_MIN; seg++) {
            // Ignore passable blocks (grass, flowers, fluids) so only a real wall can stop or fold the note.
            RayTraceResult wall = world.rayTraceBlocks(origin, dir, budget, FluidCollisionMode.NEVER, true);

            double length = budget;
            Vector normal = null;
            if (wall != null && wall.getHitPosition() != null) {
                length = Math.max(0.0, origin.toVector().distance(wall.getHitPosition()));
                BlockFace face = wall.getHitBlockFace();
                if (face != null) normal = face.getDirection();
            }

            path.add(new Segment(origin.clone(), dir.clone(), length));
            sweep(player, world, origin, dir, length, damage, struck);
            budget -= length;

            if (normal == null || budget <= SEGMENT_MIN) break; // open air, or the note has spent itself

            Location wallAt = origin.clone().add(dir.clone().multiply(length));
            ricochetFx(world, wallAt);
            dir = reflect(dir, normal);
            origin = wallAt.add(normal.clone().multiply(RICOCHET_EPSILON)); // step off the face we just met
        }

        NoteEcho echo = new NoteEcho(world, path, obsession);
        echoes.add(echo);
        echo.runTaskTimer(plugin, 0L, 1L);

        // A note that touched nothing is not a performance — only a connecting shot feeds the machine.
        if (!struck.isEmpty()) gainRhythm(player, perf);
    }

    /** One straight leg of a Note's path: where it started, where it pointed, and how far it ran. */
    private record Segment(Location from, Vector dir, double length) {}

    /** Mirror {@code dir} about a block face's normal. */
    private static Vector reflect(Vector dir, Vector normal) {
        Vector n = normal.clone().normalize();
        double twiceDot = 2.0 * dir.dot(n);
        return dir.clone().subtract(n.multiply(twiceDot)).normalize();
    }

    /**
     * Damage every living body lying along one segment of the beam.
     *
     * <p>Exactly <b>one</b> {@code getNearbyEntities} per segment — the box is drawn around the segment's
     * midpoint and everything in it is projected onto the line, rather than querying at each step of a walk.
     * A shot is at most {@value #NOTE_RICOCHETS}+1 segments once every four seconds at the very fastest, so
     * this is cheap.
     */
    private void sweep(Player player, World world, Location origin, Vector dir,
                       double length, double damage, Set<UUID> struck) {
        if (length < SEGMENT_MIN) return;

        Location mid = origin.clone().add(dir.clone().multiply(length * 0.5));
        double half = length * 0.5 + NOTE_RADIUS;
        int hits = 0;

        for (Entity e : world.getNearbyEntities(mid, half, half, half)) {
            if (e.getUniqueId().equals(player.getUniqueId())) continue;
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (struck.contains(le.getUniqueId())) continue;

            // Project the body onto the segment: t along the line, and its perpendicular distance from it.
            Vector v = center(le).subtract(origin.toVector());
            double t = v.dot(dir);
            if (t < 0.0 || t > length) continue;
            if (v.clone().subtract(dir.clone().multiply(t)).length() > NOTE_RADIUS) continue;

            struck.add(le.getUniqueId());
            strike(player, le, damage);
            pierceFx(world, le);
            if (++hits >= NOTE_MAX_TARGETS) return;
        }
    }

    /**
     * Land the Note on one body as its own distinct damage instance.
     *
     * <p>The victim's hurt-immunity is cleared first: the beam is a single deliberate hit on a four- to
     * seven-second clock and must never be swallowed because something else grazed the target a few ticks
     * ago. The {@link #ticking} fence stops the resulting damage event — which re-enters this weapon's
     * {@link #onHit} — from being mistaken for a fresh melee strike.
     */
    private void strike(Player player, LivingEntity victim, double damage) {
        if (victim.isDead() || !victim.isValid()) return;
        UUID vid = victim.getUniqueId();
        if (ticking.contains(vid)) return; // already inside this victim's damage call — never re-enter

        ticking.add(vid);
        try {
            victim.setNoDamageTicks(0);      // i-frames must not eat the Note
            victim.damage(damage, player);   // routed normally, so protection plugins can still cancel it
        } finally {
            ticking.remove(vid);
        }
    }

    // ---- Rhythm --------------------------------------------------------------------

    /**
     * A Note connected. That is combat regardless; but harmony is only ever earned with Obsession up —
     * the spec's condition is per strike, so a shot fired out of stance teaches the machine nothing.
     */
    private void gainRhythm(Player player, Performance perf) {
        markCombat(perf);
        if (!perf.obsession) return;
        if (perf.rhythm >= MAX_RHYTHM) return;

        perf.rhythm++;
        chordFx(player, perf.rhythm);
        if (perf.rhythm >= MAX_RHYTHM) shriekFx(player);
    }

    /** The performance has been quiet too long — the machine forgets its pitch and every stack falls off. */
    private void loseRhythm(Player player, Performance perf) {
        perf.rhythm = 0;
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 0.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.55f);
        world.spawnParticle(Particle.DUST, at, 8, 0.35, 0.5, 0.35, 0.0, RUST_FINE, true);
        player.sendActionBar(EgoHud.status("The performance falters — Rhythm lost.", FAINT));
    }

    // ---- Obsession (right-click) ---------------------------------------------------

    /**
     * RIGHT-click toggles the Obsession stance.
     *
     * <p>Switching it ON is the only thing that costs: a tenth of the blood the wielder is currently
     * holding, and only if they are holding <b>more than</b> a tenth of their maximum. At or below that
     * line the machine is refused — it will not take the last of you, however much it wants to. Switching
     * it OFF is free and keeps whatever Rhythm has been earned; only time can take that away.
     *
     * <p>The toll is applied with {@code setHealth} rather than {@code damage}: the spec prices it as an
     * exact fraction of current HP, which armour, resistance or another plugin's damage handling would
     * otherwise mangle — and this way it is arithmetically incapable of killing.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        Performance perf = performance(player);
        if (perf.obsession) {
            perf.obsession = false;
            releaseFx(player);
            hud(player, perf);
            return;
        }

        double health = player.getHealth();
        double gate = maxHealth(player) * OBSESSION_HP_GATE;
        if (health <= gate) {
            // Below the line the stance is simply refused — the wielder has nothing left to give it.
            deniedFx(player);
            player.sendActionBar(EgoHud.status("Not enough blood to play.", FAINT));
            return;
        }

        double next = Math.max(OBSESSION_HP_FLOOR, health - health * OBSESSION_HP_COST);
        player.setHealth(next);
        perf.obsession = true;
        EgoDurability.wearMainHand(player); // an ability use, not a vanilla swing
        obsessionFx(player);
        hud(player, perf);
    }

    private static double maxHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    // ---- melee dispatch ------------------------------------------------------------

    /**
     * The wielder swung Harmony at something close enough to touch. Harmony is a cannon, not a club: the
     * swing notices that the wielder is in a fight, and then the blow is <b>cancelled</b>.
     *
     * <p>Cancelling matters more than it looks. Left-click is the trigger, so a swing at a body in reach
     * used to land a vanilla blow <em>and</em> fire the Note — and the blow, arriving first, stamped
     * hurt-immunity on the victim that swallowed the beam. The machine appeared to stop working at exactly
     * the range where its wielder needed it. Nothing is given up: Harmony is a {@code ranged} model with no
     * melee damage of its own.
     *
     * <p>Rhythm deliberately does <b>not</b> come from here — the machine is charged by its own music, and
     * letting a melee flurry fill the bar would gut the slow-charging fantasy the passive is built on. Our
     * own beam's damage re-enters this method and is dropped by the fence.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (ticking.contains(victim.getUniqueId())) return; // the Note's own damage — not a melee strike
        markCombat(performance(attacker));
        event.setCancelled(true);
    }

    // ---- tick ----------------------------------------------------------------------

    /**
     * Runs every 2 ticks for an engaged wielder.
     *
     * <p>Returns {@code false} the instant Harmony is not in the main hand — a wielder who put it away must
     * stop being ticked, or they are ticked forever. Nothing is lost by disengaging: Rhythm and the stance
     * live on the wall clock, so a wielder who sheathes the machine for two minutes and draws it again is
     * decayed correctly on their first tick back. Damage taken is noticed by {@link #onDamaged} and needs
     * nothing from here.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false; // set down — stop ticking

        Performance perf = performance(player);
        long now = System.currentTimeMillis();

        // Out of combat for over a minute — the audience leaves and the machine forgets.
        if (perf.rhythm > 0 && perf.lastCombat != 0L && now - perf.lastCombat > COMBAT_TIMEOUT_MS) {
            loseRhythm(player, perf);
            return true; // the loss cue owns the bar this tick
        }

        if (perf.obsession && tick % HUM_PERIOD_TICKS == 0) humFx(player, perf);
        if (tick % HUD_PERIOD_TICKS == 0) hud(player, perf);
        return true;
    }

    // ---- HUD -----------------------------------------------------------------------

    /** {@code Rhythm ◆ ◆ ◆ ◇ ◇ ◇ ◇   Obsession   Note — 4s} — pips, stance, and whole seconds. */
    private void hud(Player player, Performance perf) {
        boolean full = perf.rhythm >= MAX_RHYTHM;
        Component pips = EgoHud.pips("Rhythm", full ? EMBER : STEEL, perf.rhythm, MAX_RHYTHM);
        Component stance = perf.obsession
                ? EgoHud.status("Obsession", EMBER)
                : EgoHud.status("Idle", FAINT);

        long rem = remaining(perf.lastNote, noteCooldown(perf), System.currentTimeMillis());
        Component note = rem > 0 ? EgoHud.cooldown("Note", rem, STEEL) : EgoHud.ready("Note", STEEL);

        player.sendActionBar(pips
                .append(EgoHud.status("  ", FAINT))
                .append(stance)
                .append(EgoHud.status("   ", FAINT))
                .append(note));
    }

    // ---- presentation: the music notes ---------------------------------------------

    /**
     * Spawn exactly one music note, in exactly one of {@link #NOTE_HUES}.
     *
     * <p><b>Why this is not an ordinary spawnParticle call.</b> With {@code count > 0} the client rolls the
     * particle's velocity as {@code gaussian x extra} — and for NOTE that velocity <i>is</i> the hue, so a
     * counted spawn either scatters the colour at random ({@code extra > 0}) or pins every note to the one
     * colour at {@code f = 0} ({@code extra = 0}). Neither can be asked for yellow, red and green.
     *
     * <p>With {@code count = 0} the client instead reads the offsets as a literal velocity and multiplies
     * them by {@code extra}, so {@code count = 0, offsetX = f, extra = 1.0} states the hue exactly. The
     * y/z offsets are ignored by the note particle entirely (it hands its own zero velocity to its parent
     * and keeps the x only as a colour), so the note still rises and drifts the way a vanilla note does —
     * the hue never becomes motion.
     *
     * <p>{@code force = true}: the beam runs to {@value #NOTE_RANGE} blocks and a client culls unforced
     * particles at ~32.
     *
     * <p>The cost of exactness is one packet per note, which is why every caller goes through
     * {@link #noteBurst} and its {@value #NOTE_BURST_CAP} cap.
     */
    private static void note(World world, Location at, double hue) {
        world.spawnParticle(Particle.NOTE, at, 0, hue, 0.0, 0.0, 1.0, null, true);
    }

    /**
     * A scatter of music notes around {@code at}, cycling yellow -> red -> green from a random start so
     * every burst shows all three and none of them favours a colour. Never spawns more than
     * {@value #NOTE_BURST_CAP} however many are asked for.
     */
    private static void noteBurst(World world, Location at, double spread, int count) {
        int n = Math.min(count, NOTE_BURST_CAP);
        if (n <= 0) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int start = rng.nextInt(NOTE_HUES.length);
        for (int i = 0; i < n; i++) {
            Location p = at.clone().add(
                    rng.nextDouble(-spread, spread),
                    rng.nextDouble(-spread, spread),
                    rng.nextDouble(-spread, spread));
            note(world, p, NOTE_HUES[(start + i) % NOTE_HUES.length]);
        }
    }

    // ---- presentation: pitch -------------------------------------------------------

    /**
     * Fold a note index back inside the note-block's 0..24 range by octaves. Folding rather than clamping
     * is what keeps a dissonant voicing dissonant at high Rhythm: clamping would slide a tritone flat onto
     * the root and silently resolve the chord, where dropping it an octave keeps the clash intact.
     */
    private static int fold(int note) {
        while (note > 24) note -= 12;
        while (note < 0) note += 12;
        return note;
    }

    /** Vanilla maps note index 0..24 onto pitch 0.5..2.0 as {@code 2^((n-12)/12)}. */
    private static float pitchOf(int note) {
        return (float) Math.pow(2.0, (fold(note) - 12) / 12.0);
    }

    /**
     * The root of the current chord: it climbs a whole tone per stack of Rhythm, so the machine audibly
     * finds its key as the performance builds. Untouched — this is the passive's voice.
     */
    private static float chordPitch(int stacks) {
        return pitchOf(CHORD_ROOT_NOTE + stacks * CHORD_STEP);
    }

    /** One voice of a chord, detuned by {@code mul} and held inside the pitch range the client accepts. */
    private static float voice(int note, float mul) {
        float p = pitchOf(note) * mul;
        return (float) Math.max(0.5, Math.min(2.0, p));
    }

    /**
     * A few cents either side of true. Two layers a hair apart <b>beat</b> against each other rather than
     * blending — that slow wobble is the difference between a chord and a machine trying to play one.
     */
    private static float detune(ThreadLocalRandom rng) {
        return 1.0f + (rng.nextFloat() - 0.5f) * 0.03f; // ±1.5%
    }

    /**
     * The cannon speaks — and it must never speak the same way twice.
     *
     * <p>Exactly {@value #MUZZLE_SOUNDS} sounds, every one of them varied per shot: a voicing drawn from
     * {@link #VOICINGS} that is guaranteed not to repeat the last one, two timbres drawn from
     * {@link #VOICES}, two of the three voices detuned so they beat against the root, the boom's pitch
     * wobbled, and a frame rattle pulled from {@link #FRAMES} at a random pitch. The root itself still
     * climbs with Rhythm, so the machine is finding its key and getting it wrong at the same time.
     */
    private void muzzleFx(World world, Location eye, Performance perf) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Never the same voicing twice running — a repeated chord is the exact thing being fixed here.
        int v = rng.nextInt(VOICINGS.length);
        if (v == perf.lastVoicing) v = (v + 1 + rng.nextInt(VOICINGS.length - 1)) % VOICINGS.length;
        perf.lastVoicing = v;
        int[] voicing = VOICINGS[v];

        int root = CHORD_ROOT_NOTE + perf.rhythm * CHORD_STEP; // the Rhythm climb, unchanged
        Sound lead = VOICES[rng.nextInt(VOICES.length)];
        Sound pad = VOICES[rng.nextInt(VOICES.length)];

        world.playSound(eye, lead, 0.9f, voice(root + voicing[0], 1.0f));
        world.playSound(eye, pad, 0.6f, voice(root + voicing[1], detune(rng)));
        world.playSound(eye, lead, 0.5f, voice(root + voicing[2], detune(rng)));

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f,
                (perf.obsession ? 0.7f : 1.0f) * detune(rng));
        world.playSound(eye, FRAMES[rng.nextInt(FRAMES.length)], 0.3f,
                0.5f + rng.nextFloat() * 0.35f); // the deteriorating parts complaining

        // Three notes off the muzzle — the chord made visible, in the only three colours it comes in.
        noteBurst(world, eye.clone().add(eye.getDirection().multiply(1.2)), 0.3, 3);
    }

    /** One stack earned — a chord swelling a tone higher, and rust shaken loose off the frame. */
    private void chordFx(Player player, int stacks) {
        World world = player.getWorld();
        Location at = player.getEyeLocation();
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, 0.7f, chordPitch(stacks));
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 0.5f, chordPitch(stacks));
        noteBurst(world, at, 0.35, 3);
        world.spawnParticle(Particle.DUST, at, 3, 0.3, 0.3, 0.3, 0.0, RUST_FINE, true);
    }

    /** The seventh stack: the machine stops playing music and starts screaming it. */
    private void shriekFx(Player player) {
        World world = player.getWorld();
        Location at = player.getEyeLocation();
        // Two note-block hits a tritone apart — the chord curdling into a shriek.
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 1.9f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 0.55f);
        world.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.45f, 1.5f);
        noteBurst(world, at, 0.6, NOTE_BURST_CAP);
        world.spawnParticle(Particle.DUST, at, 10, 0.5, 0.5, 0.5, 0.0, NOTE_EDGE, true);
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 8, 0.4, 0.4, 0.4, 0.02, null, true);
    }

    /** Obsession engaged: the wielder opens a vein and the machine spins up on it. */
    private void obsessionFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 0.7f);
        world.playSound(at, Sound.ENTITY_PLAYER_HURT, 0.4f, 0.8f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.7f, 0.5f);
        world.spawnParticle(Particle.DUST, at, 16, 0.35, 0.6, 0.35, 0.0, NOTE_EDGE, true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 10, 0.3, 0.5, 0.3, 0.0, NOTE_BLED, true);
    }

    /** Obsession released: the machine winds down and the blood stops. */
    private void releaseFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_CONDUIT_DEACTIVATE, 0.5f, 1.2f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
        world.spawnParticle(Particle.DUST, at, 8, 0.3, 0.5, 0.3, 0.0, NOTE_CORE, true);
    }

    /** The stance refused — a dead click and a note that will not sound. */
    private void deniedFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.5f, 0.5f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.5f);
        world.spawnParticle(Particle.SMOKE, at, 6, 0.25, 0.3, 0.25, 0.01, null, true);
    }

    /** Obsession's idle drone: the machine running on the wielder, quietly, while it waits. */
    private void humFx(Player player, Performance perf) {
        World world = player.getWorld();
        Location at = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.8));
        // The drone climbs with Rhythm through the clamped voice() helper. The old octave-down (* 0.5f) drove
        // stacks 0-3 under the client's 0.5 pitch floor, where they all flattened to one note — the low half of
        // the climb the drone exists to voice was inaudible. Dropping it lets every stack sound distinct.
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.16f,
                voice(CHORD_ROOT_NOTE + perf.rhythm * CHORD_STEP, 1.0f));
        world.spawnParticle(Particle.DUST, at, 1, 0.14, 0.14, 0.14, 0.0, RUST_FINE, true);
        if (perf.rhythm >= MAX_RHYTHM) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0.12, 0.12, 0.12, 0.0, null, true);
        }
    }

    /** The note folding off a wall — a struck-metal clang and a spray of sparks. */
    private void ricochetFx(World world, Location at) {
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.7f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 1.6f);
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.12, 0.12, 0.12, 0.03, null, true);
        world.spawnParticle(Particle.DUST, at, 4, 0.15, 0.15, 0.15, 0.0, NOTE_EDGE, true);
    }

    /**
     * The note passing through a body. Capped deliberately: a shot may pierce {@value #NOTE_MAX_TARGETS}
     * bodies, and this fires once per body in the same tick, so its ceiling is multiplied by eight before
     * it reaches the network. 16 particles a body -> 128 on the worst shot ever fired.
     */
    private void pierceFx(World world, LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, c, 8, 0.3, 0.42, 0.3, 0.0, NOTE_BLED, true);
        world.spawnParticle(Particle.DUST, c, 5, 0.28, 0.38, 0.28, 0.0, NOTE_EDGE, true);
        noteBurst(world, c, 0.3, 3);
        world.playSound(c, Sound.BLOCK_NOTE_BLOCK_BELL, 0.55f, 1.8f);
    }

    /**
     * The Note held on screen: the fired polyline is redrawn each tick for {@value #NOTE_ECHO_TICKS} ticks,
     * thinning as it goes, so the shot reads as a sustained note decaying rather than a single frame's
     * flash. Purely cosmetic — the damage was all dealt the instant the beam was resolved.
     *
     * <p><b>The lingering is bought out of rate, not out of counts.</b> The step between motes grows every
     * tick ({@link #NOTE_ECHO_THIN}) and is floored by {@link #NOTE_ECHO_MAX_MOTES} against the path's
     * true length, so the echo's densest frame is capped no matter how far the beam ran, and every frame
     * after it is strictly cheaper. The note is on screen for more than twice as long as it was while its
     * peak and average per-tick spend are both below what the old 7-tick echo cost.
     *
     * <p>Every mote is spawned with {@code force = true}: the beam runs out to {@value #NOTE_RANGE} blocks
     * and a client culls unforced particles somewhere around 32, which would cut the note off mid-air for
     * anyone watching from any distance.
     */
    private final class NoteEcho extends BukkitRunnable {
        private final World world;
        private final List<Segment> path;
        private final boolean obsession;
        /** The polyline's true length, so the per-tick mote ceiling can be enforced against it. */
        private final double total;
        private int age = 0;

        NoteEcho(World world, List<Segment> path, boolean obsession) {
            this.world = world;
            this.path = path;
            this.obsession = obsession;
            double t = 0.0;
            for (Segment s : path) t += s.length();
            this.total = Math.max(NOTE_VFX_STEP, t); // never divide by a zero-length path
        }

        @Override
        public void run() {
            if (age >= NOTE_ECHO_TICKS) {
                cancel();
                echoes.remove(this);
                return;
            }
            // The sustain thins as the note decays: the same line, drawn ever more sparsely. The second
            // term is the hard ceiling — however long the path, never more than NOTE_ECHO_MAX_MOTES of it.
            double step = Math.max(NOTE_VFX_STEP * (1.0 + age * NOTE_ECHO_THIN),
                    total / NOTE_ECHO_MAX_MOTES);
            boolean notes = age < NOTE_ECHO_NOTE_TICKS && (age & 1) == 0;
            for (int i = 0; i < path.size(); i++) draw(path.get(i), step, i == 0, notes);
            age++;
        }

        private void draw(Segment s, double step, boolean first, boolean notes) {
            // Only the muzzle leg starts short — the wielder's own metre is left bare so nothing bursts
            // in first person. A ricocheted leg begins right at the wall it came off.
            double start = first ? NOTE_VFX_START : 0.0;
            int idx = 0;
            for (double d = start; d < s.length(); d += step, idx++) {
                Location p = s.from().clone().add(s.dir().clone().multiply(d));
                if (obsession) {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.02, 0.02, 0.02, 0.0, NOTE_BLED, true);
                } else {
                    world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0.0, NOTE_CORE, true);
                }
                if (idx % 3 == 0) {
                    world.spawnParticle(Particle.DUST, p, 1, 0.09, 0.09, 0.09, 0.0, NOTE_EDGE, true);
                }
                if (notes && idx % NOTE_ECHO_NOTE_EVERY == 0) {
                    // The notes hang along the beam for the first few ticks, not just its attack frame —
                    // one at a time, because that is the only way to state their colour. See note().
                    note(world, p, NOTE_HUES[(idx / NOTE_ECHO_NOTE_EVERY + age) % NOTE_HUES.length]);
                }
                if (age == 0 && idx % 7 == 0) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, p, 1, 0.04, 0.04, 0.04, 0.0, null, true);
                }
            }
        }
    }

    // ---- shared helpers ------------------------------------------------------------

    private static Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.HARMONY.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * The item is stamped exactly once, here, and never repainted. That is deliberate and load-bearing:
     * an E.G.O weapon is meant to be an enchantable alternative to a vanilla one, and a per-tick meta
     * rewrite would silently wipe the wielder's enchants. Everything live about Harmony lives in
     * {@link #performances}, not on the stack.
     */
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.HARMONY.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.HARMONY);

        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    /** Built once. Display name is the weapon; the title line is the Abnormality. Never the other way. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Harmony",
            "Singing Machine",
            STEEL,
            RUST,
            List.of(
                    "It may look like a deteriorating",
                    "machine at first glance, but the music",
                    "it makes captures its audience more",
                    "than any other instrument could. The",
                    "wielder must dedicate himself in",
                    "return. After all, art is a devil's",
                    "gift, born from despair and suffering.",
                    "Never stop performing until the body",
                    "crumbles to dust."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] That… was Indeed a Rhythm",
                            "Cannon shots build up to 7 stacks of",
                            "Rhythm. Each stack draws more from",
                            "the machine. Out of combat for a",
                            "minute and the music stops — all",
                            "stacks are lost."),
                    new EgoLore.Ability("[Left Click] Note",
                            "Fire a powerful beam. Refreshes every",
                            "7 seconds — 4 under Obsession, which",
                            "also makes it ricochet off walls."),
                    new EgoLore.Ability("[Right Click] Obsession",
                            "Change stance. Costs 10% of current",
                            "HP, and only above 10% Max HP.",
                            "Weapon damage +30% and the Note",
                            "refreshes in 4s. Rhythm can only be",
                            "gained while it is active.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        performances.remove(id);
        ticking.remove(id); // only ever set if the quitter was themselves shot by a Note
    }

    @Override
    public void onDisable() {
        for (NoteEcho echo : new ArrayList<>(echoes)) echo.cancel();
        echoes.clear();
        performances.clear();
        ticking.clear();
        // No entity sweep: Harmony never spawns one. The Note is a raytrace and its body is particles.
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Performance> e : performances.entrySet()) {
            Performance perf = e.getValue();
            if (perf.rhythm == 0 && !perf.obsession) continue;
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            out.add("harmony  Rhythm " + perf.rhythm + "/" + MAX_RHYTHM
                    + (perf.obsession ? "  Obsession" : "")
                    + "  (" + who + ")");
        }
        return out;
    }
}
