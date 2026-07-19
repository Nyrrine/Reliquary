package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 * Hornet【Alteration】 — "Queen Bee" (Lobotomy Corp E.G.O Equipment, WAW).
 *
 * <p>A hornet rifle that did not stay the shape its makers cast it in. The extraction was clean; the gear
 * mutated anyway — the barrel splits, the chamber grows a second gut, and what comes out is half fungus and
 * half brutality. The research team declined to call that a defect. They called it <b>Alteration</b>: a gear
 * finishing itself, the way real E.G.O does. Yellow where the spores bloom, red where the buckshot lands.
 *
 * <p><b>It is one gun with two throats</b>, and the wielder chooses which is open:
 *
 * <ul>
 *   <li><b>Right-click — Alternate</b> — snap between <b>rifle</b> and <b>shotgun</b>. The item never
 *       changes; only the open chamber does. Each mode draws from its <b>own</b> magazine, and the two
 *       magazines never share a round.</li>
 *   <li><b>Left-click — Hornet [Rifle]</b> — one hitscan <b>spore round</b> down the eye line
 *       ({@value #RIFLE_DAMAGE} damage, {@value #RIFLE_RANGE}-block reach), {@value #RIFLE_COOLDOWN_MS}ms
 *       between shots. {@value #SPORE_MAG} in the magazine. Fungal, precise, patient.</li>
 *   <li><b>Left-click — Hornet [Shotgun]</b> — a {@value #BUCKSHOT_PELLETS}-pellet <b>buckshot</b> cone
 *       ({@value #BUCKSHOT_PELLET_DAMAGE} a pellet, {@value #SHOTGUN_RANGE}-block reach),
 *       {@value #SHOTGUN_COOLDOWN_MS}ms between shots. {@value #BUCKSHOT_MAG} in the magazine. Every pellet
 *       is its own hitscan and punches through up to {@value #BUCKSHOT_PIERCE} bodies, so point-blank
 *       lands the lot and a blast into a crowd reaches everything in the line.</li>
 * </ul>
 *
 * <p><b>Which gun is for what.</b> The rifle is the single-target weapon and is meant to win there — 6/s
 * sustained against the shotgun's 3.6/s. The shotgun's case is bodies: its pellets bite through, so one
 * blast into a press at close range is worth several times what it is worth against one man, while no
 * single body ever takes more than the same 10.8. That is a trade, not a shortfall. It matters because
 * neither magazine is optional — nothing reloads until both are spent, so buckshot has to be worth
 * firing rather than something to be emptied into a wall to get the rifle back.
 *
 * <p><b>The tension — and it is the whole weapon.</b> Hornet reloads only when <b>BOTH</b> magazines are
 * empty, and at that instant a {@value #RELOAD_MS}ms reload fires <em>on its own</em>. There is no manual
 * reload. Sitting on 4 buckshot and 0 spore means the rifle stays dry until the buckshot is spent too — you
 * do not get to top up the barrel you like. Spend everything, or carry a dead half.
 *
 * <ul>
 *   <li><b>Spore Diffusion</b> — every {@value #SPORE_PROC_EVERY}th connecting spore round and every
 *       {@value #BUCKSHOT_PROC_EVERY}th connecting buckshot blast inflicts Slowness on what it hits. The
 *       counters are the magazine sizes on purpose: land a full magazine clean and its <em>last</em> round
 *       is the one that procs.</li>
 *   <li><b>Loyalty Pheromone</b> — a kill made holding Hornet grants the wielder Regeneration I for
 *       {@value #REGEN_SECONDS}s. The hive pays its debts.</li>
 * </ul>
 *
 * <p><b>Cost model.</b> Both hitscan modes are pure raytraces — Hornet spawns <b>no entities at all</b>, so
 * there is nothing to track, nothing to sweep, and nothing that can orphan on a crash. Per-wielder state is
 * one UUID-&gt;{@link Hive} map (magazines, stance, strike tallies, reload clock), dropped on quit. Nothing
 * is keyed by victim, so no mob can leak a map entry. The tick loop only runs while a wielder holds the gun
 * — with the one deliberate exception that a reload is driven to completion even if the gun is stowed, so
 * pocketing it mid-reload can't strand the magazines empty forever.
 *
 * <p><b>Enchantability.</b> The item's meta is written exactly once in {@link #createItem()} and never
 * repainted. The stance lives in memory, not on the item — which is precisely why Hornet keeps vanilla
 * enchants where a per-tick repaint would silently strip them.
 */
public final class HornetWeapon implements Weapon {

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their twin magazines, stance, strike tallies and reload clock. The only state this weapon keeps. */
    private final Map<UUID, Hive> hives = new HashMap<>();

    /**
     * Bodies currently taking one of our own rounds. The manager dispatches {@link #onHit} for any hit
     * whose damager is a player holding this weapon, which every spore round and every pellet is — so this
     * fence is what tells a shot apart from a swing. Held only across the single {@code damage()} call, in
     * a try/finally, so it is empty between rounds and can never accumulate a dead mob's id.
     */
    private final Set<UUID> shooting = new HashSet<>();

    // ---- tuning: magazines & the shared reload ------------------------------------
    private static final int  SPORE_MAG    = 10;     // spore rounds per magazine (rifle)
    private static final int  BUCKSHOT_MAG = 6;      // buckshot rounds per magazine (shotgun)
    private static final long RELOAD_MS    = 5000L;  // auto-reload, fires only when BOTH magazines are dry
    private static final long DRY_HINT_MS  = 2000L;  // how long the spent-chamber hint holds the bar's tail

    // ---- tuning: rifle (spore rounds) ----------------------------------------------
    // A single deliberate round a second. 6.0 x 10 rounds = 60 damage a magazine, and 6.0/s sustained sits
    // well under a netherite sword's melee DPS — a rifle should trade rate for reach, not beat steel.
    private static final double RIFLE_DAMAGE      = 6.0;    // 3 hearts per spore round
    private static final double RIFLE_RANGE       = 40.0;   // hitscan reach — a real rifle's line
    private static final double RIFLE_RAY_SIZE    = 0.45;   // entity ray fatness (forgiving aim)
    private static final double RIFLE_SPREAD      = 0.012;  // a hair of scatter so it isn't a laser
    private static final long   RIFLE_COOLDOWN_MS = 1000L;  // 1s between shots

    // ---- tuning: shotgun (buckshot rounds) -----------------------------------------
    // 6 pellets x 1.8 = 10.8 if every pellet lands point-blank — under the ~11 single-instance ceiling
    // (netherite + Sharpness V). Range scatters the cone, so the honest average is well below that. Six
    // pellets to match the six-round magazine, because Hornet's numbers rhyme on purpose.
    private static final int    BUCKSHOT_PELLETS       = 6;     // pellets per blast
    private static final double BUCKSHOT_PELLET_DAMAGE = 1.8;   // per pellet — 10.8 total point-blank
    private static final double SHOTGUN_RANGE          = 12.0;  // close-quarters reach
    private static final double SHOTGUN_RAY_SIZE       = 0.5;   // entity ray fatness
    private static final double SHOTGUN_CONE           = 0.13;  // spread scatter on each pellet
    private static final long   SHOTGUN_COOLDOWN_MS    = 3000L; // 3s between shots

    /**
     * How many bodies one buckshot pellet bites through. This is the shotgun's niche in a single number:
     * it will never out-damage the rifle on one target and is not meant to, but fired into a press of
     * bodies at close range the same blast reaches everything in the line. Raising it widens the crowd
     * payoff without ever lifting what a single body can take (still 10.8 a blast, whatever it stands
     * behind), which is why the ceiling stays honest no matter how this is tuned.
     */
    private static final int    BUCKSHOT_PIERCE        = 3;     // bodies one pellet punches through

    // ---- tuning: Spore Diffusion (the Slowness proc) --------------------------------
    // Deliberately equal to each magazine's size: a clean full magazine procs on its last round.
    private static final int SPORE_PROC_EVERY    = 10;   // every 10th connecting spore round
    private static final int BUCKSHOT_PROC_EVERY = 6;    // every 6th connecting buckshot blast
    private static final int SLOWNESS_TICKS      = 80;   // 4s of Slowness on the proc
    private static final int SLOWNESS_AMP        = 0;    // amplifier 0 = Slowness I

    // ---- tuning: Loyalty Pheromone (the on-kill reward) -----------------------------
    private static final int REGEN_SECONDS = 15;                    // for the tooltip/javadoc readout
    private static final int REGEN_TICKS   = REGEN_SECONDS * 20;    // 15s of Regeneration
    private static final int REGEN_AMP     = 0;                     // amplifier 0 = Regeneration I

    // ---- palette — mutated yellow and brutal red -----------------------------------
    private static final TextColor SPORE    = TextColor.color(0xE8C23A); // primary — fungal hornet yellow
    private static final TextColor BUCKSHOT = TextColor.color(0xFF0000); // secondary — buckshot red
    private static final TextColor FRAME    = NamedTextColor.DARK_GRAY;  // brackets, matching EgoHud
    private static final TextColor COUNT    = NamedTextColor.GRAY;       // the stowed magazine's count
    private static final TextColor EMPTY    = NamedTextColor.RED;        // a spent magazine goes red

    private static final Color C_SPORE      = Color.fromRGB(0xE8, 0xC2, 0x3A); // spore-round yellow
    private static final Color C_SPORE_PALE = Color.fromRGB(0xF6, 0xE2, 0x8E); // pale fungal bloom
    private static final Color C_BUCK       = Color.fromRGB(0xFF, 0x00, 0x00); // buckshot red
    private static final Color C_BUCK_DARK  = Color.fromRGB(0x8C, 0x10, 0x10); // clotted red core

    // Particle data classes are a RUNTIME contract on 26.1.2, not a compile-time one: DUST wants
    // DustOptions and DUST_COLOR_TRANSITION wants DustTransition, or the spawn throws. Every particle used
    // below is either one of these two or a no-data particle (SPORE_BLOSSOM_AIR, FALLING_SPORE_BLOSSOM,
    // CRIT, END_ROD, SMOKE, ELECTRIC_SPARK, SNEEZE) — all verified against the 26.1.2 API.
    private static final Particle.DustOptions SPORE_DUST  = new Particle.DustOptions(C_SPORE, 0.7f);
    private static final Particle.DustOptions SPORE_FINE  = new Particle.DustOptions(C_SPORE_PALE, 0.5f);
    private static final Particle.DustOptions BUCK_DUST   = new Particle.DustOptions(C_BUCK, 0.7f);
    private static final Particle.DustOptions BUCK_CORE   = new Particle.DustOptions(C_BUCK_DARK, 0.9f);
    private static final Particle.DustTransition SPORE_BLOOM =
            new Particle.DustTransition(C_SPORE, C_SPORE_PALE, 1.1f);
    private static final Particle.DustTransition BUCK_BLOOM =
            new Particle.DustTransition(C_BUCK, C_BUCK_DARK, 1.2f);

    public HornetWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hornet");
    }

    @Override
    public String id() {
        return "hornet";
    }

    // ---- stance & state ------------------------------------------------------------

    /** The two throats of the gun. The item is identical either way — only the open chamber differs. */
    private enum Stance {
        RIFLE("Rifle", "Spore"),
        SHOTGUN("Shotgun", "Buckshot");

        private final String label;     // shown on the action bar
        private final String ammoName;  // the magazine this stance draws from

        Stance(String label, String ammoName) {
            this.label = label;
            this.ammoName = ammoName;
        }

        Stance other() {
            return this == RIFLE ? SHOTGUN : RIFLE;
        }
    }

    /**
     * One wielder's gun: both magazines, the open chamber, the cadence clock, the auto-reload clock, and the
     * two running strike tallies that drive Spore Diffusion. The tallies run continuously rather than
     * resetting per magazine — "every 10th strike" is a running count — which is exactly what makes a clean
     * full magazine proc on its last round.
     *
     * <p>{@code lastFire} is deliberately ONE clock shared by both stances rather than a clock each: the gun
     * cycles, and it does not care which throat you just opened. Per-stance clocks would let a wielder dance
     * rifle→shotgun→rifle to fire both modes off cooldown simultaneously and skip the cadence that is
     * supposed to be the price of each shot.
     */
    private static final class Hive {
        Stance stance      = Stance.RIFLE;
        int    spore       = SPORE_MAG;
        int    buckshot    = BUCKSHOT_MAG;
        long   lastFire    = 0L;
        long   reloadStart = 0L;   // 0 = not reloading
        long   nagUntil    = 0L;   // show the spent-chamber hint on the bar's tail until this stamp
        int    sporeStrikes    = 0; // running tally of connecting spore rounds
        int    buckshotStrikes = 0; // running tally of connecting buckshot blasts

        boolean reloading() { return reloadStart != 0L; }

        /** Rounds left in the currently open chamber. */
        int rounds() { return stance == Stance.RIFLE ? spore : buckshot; }

        /** Both barrels dry — the only condition under which Hornet is allowed to reload. */
        boolean dry() { return spore <= 0 && buckshot <= 0; }
    }

    // ---- input: right-click alternates the chamber ----------------------------------

    /**
     * Right-click — <b>Alternate</b>. Snap between rifle and shotgun. Sneaking makes no difference: this is
     * Hornet's only right-click ability, so every right-click swaps. Swapping mid-reload is allowed and
     * harmless (the reload refills both magazines regardless) — it just lets the wielder pick which throat
     * is open when the gun comes back up.
     *
     * <p>Note what this deliberately does <em>not</em> do: it never rebuilds the ItemStack. The stance is
     * memory-side state, so the meta written in {@link #createItem()} is never touched again and vanilla
     * enchants survive.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Hive hive = hives.computeIfAbsent(player.getUniqueId(), k -> new Hive());
        hive.stance = hive.stance.other();

        alternateFx(player, hive.stance);
        renderBar(player, hive);
    }

    // ---- input: left-click fires the open chamber ------------------------------------

    /**
     * A gun does not punch. Left-clicking a body at arm's length used to do both — the vanilla melee blow
     * landed <em>and</em> the trigger pulled — and the melee won, because it stamped hurt-immunity on the
     * victim a fraction before the round arrived, so the shot was swallowed whole. From the wielder's side
     * the weapon simply stopped firing whenever an enemy got close, which is the worst possible moment for
     * a gun to stop firing.
     *
     * <p>Cancelling the swing's damage settles it: the blow never lands, no i-frames are stamped, and the
     * round that {@link #onSwing} fires on the very same click is the only thing that touches them. Nothing
     * is lost — Hornet is a {@code ranged} model with no melee damage of its own to give up.
     *
     * <p><b>The fence is not optional.</b> The manager dispatches this for <em>any</em> hit whose damager is
     * a player holding this weapon, and every round we fire is exactly that — so our own bullets arrive back
     * here. Without {@link #shooting} the cancel would eat the shot it exists to protect, and the gun would
     * deal nothing at all.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (shooting.contains(victim.getUniqueId())) return; // our own round, not a swing
        event.setCancelled(true);
    }

    /**
     * Left-click (arm swing) — <b>fire</b>. One swing is one shot, gated by the open chamber's cadence
     * ({@value #RIFLE_COOLDOWN_MS}ms rifle / {@value #SHOTGUN_COOLDOWN_MS}ms shotgun).
     *
     * <p>There is no hold-to-spray window here, unlike the faster E.G.O guns: Hornet's cadence is a whole
     * second at its quickest, and Bukkit does not reliably repeat ARM_SWING while left-click is held in air.
     * A swing-refreshed window long enough to bridge a 1s gap would keep firing after the wielder had already
     * let go — a phantom extra shot out of an unforgiving magazine. One click, one round.
     */
    @Override
    public void onSwing(Player player) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        UUID id = player.getUniqueId();
        Hive hive = hives.computeIfAbsent(id, k -> new Hive());

        // Mid-reload: the trigger just clicks empty.
        if (hive.reloading()) {
            dryClick(player);
            return;
        }

        // Defensive: both barrels dry but no reload running (a state we should never reach, since spending
        // the last round arms the reload inline). Arm it rather than dry-click forever.
        if (hive.dry()) {
            beginReload(player, hive);
            renderBar(player, hive);
            return;
        }

        // The open chamber is spent but the other one isn't. This is the weapon, not a bug: you cannot top up
        // the barrel you like — go spend the other magazine before Hornet will reload anything. Arm the hint
        // window so the bar explains itself instead of just clicking.
        if (hive.rounds() <= 0) {
            dryClick(player);
            hive.nagUntil = System.currentTimeMillis() + DRY_HINT_MS;
            renderBar(player, hive);
            return;
        }

        long now = System.currentTimeMillis();
        long cadence = hive.stance == Stance.RIFLE ? RIFLE_COOLDOWN_MS : SHOTGUN_COOLDOWN_MS;
        if (now - hive.lastFire < cadence) return; // still cycling — the live ammo bar carries on
        hive.lastFire = now;

        if (hive.stance == Stance.RIFLE) fireRifle(player, hive);
        else                             fireShotgun(player, hive);

        // Spending the last round of the LAST loaded magazine is what arms the reload — never before.
        if (hive.dry()) beginReload(player, hive);
        renderBar(player, hive);
    }

    // ---- rifle: one spore round ------------------------------------------------------

    /**
     * One spore round: a single hitscan down the eye line. Spends a round whether or not it connects; only a
     * <em>connecting</em> round advances the Spore Diffusion tally, so shooting the sky can't bank a proc.
     */
    private void fireRifle(Player player, Hive hive) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector dir = scatter(rng, eye.getDirection().normalize(), RIFLE_SPREAD);

        hive.spore--;
        rifleReportFx(player, eye, dir);

        LivingEntity struck = resolveSporeRound(player, world, eye, dir);
        if (struck == null) return;

        hive.sporeStrikes++;
        if (hive.sporeStrikes % SPORE_PROC_EVERY == 0) {
            applySlowness(struck);
            diffusionFx(struck, true);
        }
    }

    /**
     * Trace one spore round: clip at the first real wall, then bite the first living body along the line.
     * Damage is routed through {@code victim.damage(...)} so other plugins can still cancel it. Returns the
     * body it struck, or null.
     */
    private LivingEntity resolveSporeRound(Player player, World world, Location eye, Vector dir) {
        // FluidCollisionMode.NEVER + ignorePassableBlocks: the 3-arg overload would let a grass tuft or a
        // flower swallow a rifle round.
        double maxDist = RIFLE_RANGE;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, RIFLE_RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDist = eye.toVector().distance(blockHit.getHitPosition());
        }

        RayTraceResult entHit = world.rayTraceEntities(
                eye, dir, maxDist, RIFLE_RAY_SIZE,
                e -> e instanceof LivingEntity && !e.getUniqueId().equals(player.getUniqueId()));

        Location muzzle = eye.clone().add(dir.clone().multiply(0.6));
        Location end;
        LivingEntity victim = null;
        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le) {
            victim = le;
            end = entHit.getHitPosition().toLocation(world);
        } else {
            end = eye.clone().add(dir.clone().multiply(maxDist));
        }

        drawTracer(world, muzzle, end, SPORE_DUST, SPORE_FINE, 0.55);

        if (victim != null) {
            shooting.add(victim.getUniqueId());
            try {
                victim.damage(RIFLE_DAMAGE, player);   // routed so other plugins can cancel
            } finally {
                shooting.remove(victim.getUniqueId());
            }
            sporeImpactFx(world, end);
        }
        return victim;
    }

    // ---- shotgun: a buckshot cone ----------------------------------------------------

    /**
     * One buckshot blast: {@value #BUCKSHOT_PELLETS} independent hitscan pellets scattered through a cone,
     * each of which <b>punches on through</b> what it hits into up to {@value #BUCKSHOT_PIERCE} bodies.
     *
     * <p>That pierce is the shotgun's whole reason to exist, so it is worth being plain about the balance
     * it draws. Against a single body nothing has changed: the cone lands
     * {@value #BUCKSHOT_PELLETS} &times; {@value #BUCKSHOT_PELLET_DAMAGE} = 10.8 point-blank, and on a
     * 3-second cadence that is a deliberately poor 3.6/s — the rifle is the single-target gun and is meant
     * to win there. What the pierce buys is the case for ever loading buckshot at all: fire into a press of
     * bodies at close range and the same blast reaches the ones behind, so the blast's worth scales with
     * how many things are in front of it. No body can take more than the same 10.8, whatever else it is
     * standing behind, so the single-instance ceiling is untouched from any angle.
     *
     * <p>The distinct bodies hit are collected into a short-lived local set purely to de-duplicate the
     * Slowness proc (two pellets in one body is two hits but one victim). It is a local, not a victim-keyed
     * map — nothing about a mob outlives this call, so there is nothing here to prune.
     */
    private void fireShotgun(Player player, Hive hive) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector aim = eye.getDirection().normalize();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        hive.buckshot--;
        shotgunReportFx(player, eye, aim);

        List<LivingEntity> struck = new ArrayList<>(BUCKSHOT_PELLETS);
        Set<UUID> seen = new HashSet<>();
        List<LivingEntity> pelletHits = new ArrayList<>(BUCKSHOT_PIERCE);
        for (int i = 0; i < BUCKSHOT_PELLETS; i++) {
            pelletHits.clear();
            resolveBuckshotPellet(player, world, eye, scatter(rng, aim, SHOTGUN_CONE), pelletHits);
            for (LivingEntity hit : pelletHits) {
                if (seen.add(hit.getUniqueId())) struck.add(hit);
            }
        }
        if (struck.isEmpty()) return;

        hive.buckshotStrikes++;
        if (hive.buckshotStrikes % BUCKSHOT_PROC_EVERY == 0) {
            for (LivingEntity victim : struck) {
                applySlowness(victim);
                diffusionFx(victim, false);
            }
        }
    }

    /**
     * Trace one buckshot pellet: clip at the first wall, then bite its way through up to
     * {@value #BUCKSHOT_PIERCE} living bodies along the line, collecting each into {@code out}.
     *
     * <p>The wall is found once and bounds every bite, so a pellet can never punch through someone into a
     * body on the far side of a stone. Each body is bitten at most once by a given pellet — the trace
     * resumes just past whatever it last hit rather than re-finding it — and the tracer draws the whole
     * line, so the through-shot reads as a through-shot.
     */
    private void resolveBuckshotPellet(Player player, World world, Location eye, Vector dir,
                                       List<LivingEntity> out) {
        double maxDist = SHOTGUN_RANGE;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, SHOTGUN_RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDist = eye.toVector().distance(blockHit.getHitPosition());
        }

        Location muzzle = eye.clone().add(dir.clone().multiply(0.6));
        Set<UUID> bitten = new HashSet<>();
        double advanced = 0.0;

        for (int depth = 0; depth < BUCKSHOT_PIERCE; depth++) {
            double segLen = maxDist - advanced;
            if (segLen <= 1.0e-3) break;

            Location from = eye.clone().add(dir.clone().multiply(advanced));
            RayTraceResult entHit = world.rayTraceEntities(
                    from, dir, segLen, SHOTGUN_RAY_SIZE,
                    e -> e instanceof LivingEntity
                            && !e.getUniqueId().equals(player.getUniqueId())
                            && !bitten.contains(e.getUniqueId()));
            if (entHit == null || !(entHit.getHitEntity() instanceof LivingEntity le)) break;

            Location at = entHit.getHitPosition().toLocation(world);
            // A blast is six pellets arriving together, and vanilla only lets a body be hurt once every
            // ten ticks — so without this the first pellet stamped hurt-immunity and the other five were
            // swallowed whole. A full point-blank blast landed 1.8 instead of 10.8, which read in play as
            // "buckshot does half a heart". The pellets aren't weak; they were never arriving.
            le.setNoDamageTicks(0);
            shooting.add(le.getUniqueId());
            try {
                le.damage(BUCKSHOT_PELLET_DAMAGE, player);   // routed so other plugins can cancel
            } finally {
                shooting.remove(le.getUniqueId());
            }
            buckImpactFx(world, at);
            bitten.add(le.getUniqueId());
            out.add(le);
            advanced = eye.toVector().distance(entHit.getHitPosition()) + 0.05;
        }

        drawTracer(world, muzzle, eye.clone().add(dir.clone().multiply(maxDist)), BUCK_DUST, BUCK_CORE, 0.7);
    }

    // ---- passives: Spore Diffusion & Loyalty Pheromone --------------------------------

    /** The Diffusion proc: Slowness on the struck body. No map, no tracking — the effect owns its own clock. */
    private void applySlowness(LivingEntity victim) {
        victim.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS, SLOWNESS_TICKS, SLOWNESS_AMP, false, true, true));
    }

    /**
     * <b>Loyalty Pheromone</b>. A kill made with Hornet in the main hand rewards the wielder with
     * Regeneration I for {@value #REGEN_SECONDS}s — the hive paying its keeper. Gated on the main-hand item
     * so an unrelated kill can't collect it; {@code getKiller()} is populated because every shot routes its
     * damage through {@code victim.damage(entity, player)}.
     */
    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !matches(killer.getInventory().getItemInMainHand())) return;

        killer.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION, REGEN_TICKS, REGEN_AMP, false, true, true));
        pheromoneFx(killer);
    }

    // ---- the auto-reload ---------------------------------------------------------------

    /**
     * Arm the automatic reload. Only ever called once both magazines are dry — Hornet has no manual reload
     * and no partial top-up, by design. One mild point of durability wear is paid here, once per reload
     * rather than per pellet, so a buckshot blast doesn't cost six times what a spore round does.
     */
    private void beginReload(Player player, Hive hive) {
        if (hive.reloading()) return;
        hive.reloadStart = System.currentTimeMillis();
        EgoDurability.wearMainHand(player); // mild — once per reload, never per pellet

        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.ITEM_CROSSBOW_LOADING_START, 0.7f, 0.8f);
        world.playSound(at, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.6f, 0.7f); // the chamber packing wet
        // A short stir, not a drone. This was BEE_LOOP_AGGRESSIVE at 0.7 — an ambient loop, which is long
        // by nature and longer still pitched down, so every reload left a buzz hanging in the room after
        // the wielder had walked away. BEE_HURT is a clipped note: the hive objects, briefly.
        world.playSound(at, Sound.ENTITY_BEE_HURT, 0.45f, 0.8f);             // the hive stirring
    }

    /** Both magazines are back: a bright hive chirp and a puff of fresh spores off the breech. */
    private void reloadReady(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.ITEM_CROSSBOW_LOADING_END, 0.7f, 1.1f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.8f);
        world.playSound(at, Sound.ENTITY_BEE_POLLINATE, 0.5f, 1.4f);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at, 8, 0.3, 0.4, 0.3, 0.0);
        world.spawnParticle(Particle.DUST, at, 6, 0.3, 0.4, 0.3, 0.0, SPORE_FINE);
    }

    // ---- tick: finish the reload + drive the HUD ----------------------------------------

    /**
     * Runs every 2 ticks for active wielders. Returns false the moment Hornet leaves the main hand and there
     * is nothing left to finish — anything else would tick that player forever.
     *
     * <p>The one deliberate exception: an in-flight reload keeps ticking even while the gun is stowed, so
     * pocketing Hornet mid-reload can't strand both magazines empty until the next relog. That mirrors how
     * the other magazine-fed E.G.O guns handle exactly this, and it self-terminates the moment the reload
     * lands.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        boolean held = matches(player.getInventory().getItemInMainHand());
        Hive hive = hives.get(player.getUniqueId());

        if (hive != null && hive.reloading()) {
            long elapsed = System.currentTimeMillis() - hive.reloadStart;
            if (elapsed >= RELOAD_MS) {
                hive.spore = SPORE_MAG;
                hive.buckshot = BUCKSHOT_MAG;
                hive.reloadStart = 0L;
                if (held) {
                    reloadReady(player);
                    renderBar(player, hive);
                }
                return held;    // done — keep ticking only while still held
            }
            if (held) renderBar(player, hive);
            return true;        // drive the reload to completion even if stowed
        }

        if (!held) return false; // idle and not held — stop ticking

        if (hive == null) hive = hives.computeIfAbsent(player.getUniqueId(), k -> new Hive());
        renderBar(player, hive);
        return true;
    }

    // ---- the action bar ------------------------------------------------------------------

    /**
     * The held-gun readout. Reloading shows a filling gauge labelled in <b>whole seconds</b> (never raw
     * milliseconds); otherwise the open chamber gets the full {@link EgoHud#ammo} bar and the stowed
     * magazine trails behind it as a compact count, so both magazines and the active stance are always on
     * screen at once.
     */
    private void renderBar(Player player, Hive hive) {
        if (hive.reloading()) {
            long elapsed = System.currentTimeMillis() - hive.reloadStart;
            double frac = Math.min(1.0, (double) elapsed / RELOAD_MS);
            long remaining = Math.max(0L, RELOAD_MS - elapsed);
            player.sendActionBar(EgoHud.gauge(BUCKSHOT, frac,
                    EgoHud.cooldown("Reloading", remaining, BUCKSHOT)));
            return;
        }

        Component active = hive.stance == Stance.RIFLE
                ? EgoHud.ammo(SPORE, "Spore", hive.spore, SPORE_MAG)
                : EgoHud.ammo(BUCKSHOT, "Buckshot", hive.buckshot, BUCKSHOT_MAG);
        Component tail = System.currentTimeMillis() < hive.nagUntil
                ? dryChamberHint(hive)
                : stowedReadout(hive);
        player.sendActionBar(active.append(tail));
    }

    /**
     * The tail shown for a beat after pulling on a spent chamber. Hornet will not reload until the OTHER
     * magazine is spent too, so the hint names exactly what's left to burn — the gauge beside it is already
     * showing the open chamber at a red 0.
     *
     * <p>This is a windowed tail rather than a one-shot {@code sendActionBar}: {@link #onTick} repaints the
     * bar every 2 ticks, so a plain send would be stomped inside ~100ms and the wielder would learn nothing
     * from the one mechanic the whole weapon is built around.
     */
    private Component dryChamberHint(Hive hive) {
        Stance off = hive.stance.other();
        int left = off == Stance.RIFLE ? hive.spore : hive.buckshot;
        return plain("   ‹ Alternate — ", FRAME)
                .append(plain(left + " " + off.ammoName.toLowerCase(), off == Stance.RIFLE ? SPORE : BUCKSHOT))
                .append(plain(" left to spend ›", FRAME));
    }

    /** {@code   ‹Rifle›  Buckshot 6/6} — the open chamber's name, then the magazine you're NOT holding. */
    private Component stowedReadout(Hive hive) {
        Stance off = hive.stance.other();
        int cur = off == Stance.RIFLE ? hive.spore : hive.buckshot;
        int max = off == Stance.RIFLE ? SPORE_MAG : BUCKSHOT_MAG;
        TextColor offTone = off == Stance.RIFLE ? SPORE : BUCKSHOT;
        TextColor onTone  = hive.stance == Stance.RIFLE ? SPORE : BUCKSHOT;

        return plain("   ‹", FRAME)
                .append(plain(hive.stance.label, onTone))
                .append(plain("›  ", FRAME))
                .append(plain(off.ammoName + " ", cur <= 0 ? EMPTY : offTone))
                .append(plain(cur + "/" + max, cur <= 0 ? EMPTY : COUNT));
    }

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---- presentation ----------------------------------------------------------------------

    /** Nudge a shot off the aim line by a hair so a stream of rounds doesn't read as a laser. */
    private Vector scatter(ThreadLocalRandom rng, Vector base, double spread) {
        return base.clone().add(new Vector(
                rng.nextDouble(-spread, spread),
                rng.nextDouble(-spread, spread),
                rng.nextDouble(-spread, spread))).normalize();
    }

    /** The trigger falling on a spent chamber or a locked breech. */
    private void dryClick(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.35f, 1.6f);
    }

    /** The chamber alternating: a wet mechanical snap, pitched to the throat that just opened. */
    private void alternateFx(Player player, Stance to) {
        World world = player.getWorld();
        Location at = player.getEyeLocation();
        boolean rifle = to == Stance.RIFLE;

        world.playSound(at, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.7f, rifle ? 1.6f : 0.8f);
        world.playSound(at, Sound.BLOCK_SNIFFER_EGG_CRACK, 0.5f, rifle ? 1.5f : 0.7f); // the mutated shell shifting
        // Was BEE_LOOP — an ambient drone, and the swap is a thing you do mid-fight, repeatedly. Stacked
        // loops meant the buzz never actually stopped. A single clipped wing-beat marks the change instead.
        world.playSound(at, Sound.ENTITY_BEE_HURT, 0.4f, rifle ? 1.8f : 1.0f);

        Location breech = at.clone().add(at.getDirection().multiply(0.7));
        if (rifle) {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, breech, 8, 0.12, 0.12, 0.12, 0.0, SPORE_BLOOM);
            world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, breech, 4, 0.10, 0.10, 0.10, 0.0);
        } else {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, breech, 8, 0.12, 0.12, 0.12, 0.0, BUCK_BLOOM);
            world.spawnParticle(Particle.SMOKE, breech, 4, 0.10, 0.10, 0.10, 0.01);
        }
    }

    /** The rifle's report: a tight crack with a hornet's whine riding it, and a spore puff at the muzzle. */
    private void rifleReportFx(Player player, Location eye, Vector dir) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.6));

        world.playSound(muzzle, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.2f + rng.nextFloat() * 0.15f);
        world.playSound(muzzle, Sound.ENTITY_BEE_STING, 0.7f, 1.3f + rng.nextFloat() * 0.2f);   // the sting
        world.playSound(muzzle, Sound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 1.9f);                      // the crack's edge

        world.spawnParticle(Particle.DUST, muzzle, 5, 0.06, 0.06, 0.06, 0.0, SPORE_DUST);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, muzzle, 3, 0.05, 0.05, 0.05, 0.0);
        world.spawnParticle(Particle.SMOKE, muzzle, 2, 0.04, 0.04, 0.04, 0.01);
    }

    /** The shotgun's report: a heavy, ugly boom — no whine, no elegance. Just the blast. */
    private void shotgunReportFx(Player player, Location eye, Vector dir) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.7));

        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.5f + rng.nextFloat() * 0.15f);
        world.playSound(muzzle, Sound.ITEM_CROSSBOW_SHOOT, 0.9f, 0.6f + rng.nextFloat() * 0.1f);
        world.playSound(muzzle, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);

        world.spawnParticle(Particle.DUST, muzzle, 8, 0.10, 0.10, 0.10, 0.0, BUCK_DUST);
        world.spawnParticle(Particle.DUST, muzzle, 4, 0.08, 0.08, 0.08, 0.0, BUCK_CORE);
        world.spawnParticle(Particle.LARGE_SMOKE, muzzle, 5, 0.10, 0.10, 0.10, 0.02);
    }

    /**
     * A tracer down a shot's line. {@code force = true} on every point: the rifle reaches
     * {@value #RIFLE_RANGE} blocks and a client culls unforced particles past ~32, which would quietly cut
     * the tracer off halfway for anyone but the shooter.
     */
    private void drawTracer(World world, Location from, Location to,
                            Particle.DustOptions core, Particle.DustOptions fleck, double spacing) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);

        int idx = 0;
        for (double d = 0.8; d < length; d += spacing, idx++) { // start ~0.8 out — keep it off the face
            Location p = from.clone().add(step.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0,
                    (idx & 1) == 0 ? core : fleck, true);
        }
    }

    /** A spore round biting home: a yellow bloom bursting out of the wound. */
    private void sporeImpactFx(World world, Location at) {
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 8, 0.12, 0.12, 0.12, 0.0, SPORE_BLOOM, true);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at, 4, 0.12, 0.12, 0.12, 0.0, null, true);
        world.spawnParticle(Particle.CRIT, at, 3, 0.10, 0.10, 0.10, 0.05, null, true);
        world.playSound(at, Sound.ENTITY_BEE_STING, 0.6f, 1.5f);
    }

    /** A buckshot pellet landing: a small, blunt red spatter. Kept light — six of these fire at once. */
    private void buckImpactFx(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 3, 0.10, 0.10, 0.10, 0.0, BUCK_DUST, true);
        world.spawnParticle(Particle.CRIT, at, 2, 0.08, 0.08, 0.08, 0.06, null, true);
    }

    /** Spore Diffusion catching: the round blooms open and the body slows inside the cloud. */
    private void diffusionFx(LivingEntity victim, boolean rifle) {
        World world = victim.getWorld();
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 16, 0.35, 0.45, 0.35, 0.0,
                rifle ? SPORE_BLOOM : BUCK_BLOOM, true);
        world.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, body, 10, 0.35, 0.45, 0.35, 0.0, null, true);
        world.spawnParticle(Particle.SNEEZE, body, 6, 0.25, 0.30, 0.25, 0.02, null, true);
        world.playSound(body, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 0.7f, 0.7f);
        world.playSound(body, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.6f, 0.6f);
    }

    /** Loyalty Pheromone: the hive rewarding its keeper — a warm yellow drift rising off the wielder. */
    private void pheromoneFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 14, 0.4, 0.6, 0.4, 0.0, SPORE_BLOOM);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at, 8, 0.4, 0.6, 0.4, 0.0);
        world.spawnParticle(Particle.END_ROD, at, 4, 0.25, 0.4, 0.25, 0.01);
        world.playSound(at, Sound.ENTITY_BEE_POLLINATE, 0.8f, 1.2f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.5f, 1.7f);
    }

    // ---- item ---------------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.HORNET.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.HORNET.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.HORNET);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ---------------------------------------------------------------------------

    /**
     * Built once. The display name is the WEAPON ("Hornet【Alteration】"); the bold title line is the
     * ABNORMALITY ("Queen Bee"). Neither ever repeats the other — that split is the house rule
     * {@link EgoLore} exists to enforce.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Hornet【Alteration】",
            "Queen Bee",
            SPORE,
            BUCKSHOT,
            List.of(
                    "The research team's—that's us—",
                    "examination has confirmed that even",
                    "gears that were outcomes of stable,",
                    "refined extractions may undergo",
                    "slight mutations depending on the",
                    "user. No, I wouldn't attribute that",
                    "to incompleteness. Rather, such",
                    "changes may even be a process of",
                    "these gears reaching their own",
                    "completion, like real E.G.O. Let us",
                    "label this phenomenon \"Alteration\"",
                    "for now."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] E.G.O Alteration / Spore Round",
                            "10 spore rounds, 6 buckshot rounds.",
                            "Reloads only once BOTH are spent —",
                            "a 5s reload then runs on its own."),
                    new EgoLore.Ability("[Passive] Spore Diffusion / Loyalty Pheromone",
                            "Slowness on every 10th spore strike",
                            "and every 6th buckshot strike. A kill",
                            "grants Regeneration I for 15s."),
                    new EgoLore.Ability("[Right-Click] Alternate",
                            "Swap between rifle and shotgun.",
                            "Each keeps its own magazine."),
                    new EgoLore.Ability("[Left-Click] Hornet [Rifle]",
                            "One spore round. 1s between shots."),
                    new EgoLore.Ability("[Left-Click] Hornet [Shotgun]",
                            "A buckshot cone; each pellet",
                            "pierces 3 bodies. 3s between shots.")
            ));

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        hives.remove(id); // magazines, stance, tallies and reload clock all go with them
    }

    /**
     * Nothing to reap. Hornet is hitscan end to end — it spawns no entities, schedules no tasks of its own
     * (the reload rides the shared weapon tick), and makes no world edits, so shutdown is just dropping the
     * per-wielder state.
     */
    @Override
    public void onDisable() {
        hives.clear();
    }
}
