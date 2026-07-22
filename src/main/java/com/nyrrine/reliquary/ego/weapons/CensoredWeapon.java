package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import com.nyrrine.reliquary.ego.SlashVfx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CENSORED — a WAW/Aleph-tier E.G.O weapon on the vanilla NETHERITE_SWORD, built as a grappling maw that
 * extends. Everything about it is redacted: the weapon and the Abnormality share the name CENSORED, all four
 * ability headers read the same, and the flavour is a black bar. It is meant to feel horrific.
 *
 * <p><b>It does not ride the vanilla swing.</b> The left-click is a custom long-reach maw that lunges out
 * along the wielder's aim and bites twice, so the vanilla blow is zeroed in {@link #onHit} and every point of
 * melee damage the weapon deals is the maw's own — routed through the framework's {@code pierceDamage} so it
 * ignores part of the target's armour (the passive Held-in-Heaven idiom) and lands both bites past i-frames.
 *
 * <p>Four abilities:
 * <ul>
 *   <li><b>[Passive]</b> — every bite ignores {@link #PIERCE_FRACTION} of armour and leaves the body
 *       starving (a Hunger/exhaustion drain, the "saturation damage"). And anyone who looks at the wielder
 *       for more than {@link #LOOK_SICKEN_MS} continuous milliseconds is sickened with Nausea, "a horrendous
 *       sight."</li>
 *   <li><b>[Left Click]</b> — the maw: a slow, long-reaching strike that reads as a PIERCE (a fast dark
 *       thrust) then a MAW (black jaws snapping shut) and bites twice, on a {@link #MAW_COOLDOWN_MS} cadence
 *       so it stays heavy. Each swing has a {@link #FREE_GRAPPLE_CHANCE} chance to fire the Shift+Right-click
 *       grapple for free, ignoring both its cooldown and attack speed.</li>
 *   <li><b>[Shift + Right Click]</b> — the big grapple: a brief charge, then a pierce-then-maw tear along an
 *       extended line that hits every foe in it {@link #GRAPPLE_HITS} times. {@link #GRAPPLE_COOLDOWN_MS},
 *       skipped when the passive procs it.</li>
 *   <li><b>[Right Click]</b> — TWO-MODE, chosen by whether a fresh corpse is waiting (there is no prime step):
 *       on a fresh kill it is the signature Feast — the wielder VANISHES (invisibility + every equipment slot
 *       hidden from viewers, NO i-frames) and is free to slip away while a 30s torture show plays on the
 *       corpse, ending in a red censored RECTANGLE that bursts and leaves a lingering cognition-filter
 *       ({@link #FEAST_COOLDOWN_MS} of silence afterward); with no corpse it is the grasping arm — a
 *       black-and-red root that stems out to the nearest body and drags it to the wielder
 *       ({@link #ARM_COOLDOWN_MS}).</li>
 * </ul>
 *
 * <p>All VFX are particles and all state is per-wielder maps and self-cancelling tasks, so nothing is left in
 * the world to reap — {@link #onDisable} just cancels the live tasks and clears the state. Every magnitude
 * below is a PLACEHOLDER, flagged for Nyrrine's balance wave; the shape holds the Aleph rail (present, not OP
 * against prot-netherite).
 */
public final class CensoredWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as CENSORED. */
    private final NamespacedKey key;

    // ---- per-wielder state --------------------------------------------------------
    /** Wielder -> epoch-ms of their last maw bite; gates the M1 to a slow, heavy cadence. */
    private final Map<UUID, Long> lastMaw = new HashMap<>();
    /** Wielder -> epoch-ms the grapple is ready again (manual casts only; free procs never set it). */
    private final Map<UUID, Long> grappleReadyAt = new HashMap<>();
    /** Wielders mid-charge, so a second cast (or a free proc) can't stack a charge on top of one running. */
    private final Set<UUID> charging = new HashSet<>();
    /**
     * Wielder -> epoch-ms a fresh corpse's post-kill grace lapses, and the corpse's location. Set by a kill
     * ({@link #recordCorpse}); an R-click while the grace is live begins the Feast on that spot and consumes
     * both. There is no prime anymore: you press AFTER the kill, not before.
     */
    private final Map<UUID, Long>     corpseUntil = new HashMap<>();
    private final Map<UUID, Location> corpseLoc   = new HashMap<>();
    /** Wielder -> epoch-ms the Feast (Right-click on a corpse) is off silence. */
    private final Map<UUID, Long> feastReadyAt = new HashMap<>();
    /** Wielder -> epoch-ms the grasping arm (Right-click with no corpse) is ready again. */
    private final Map<UUID, Long> armReadyAt = new HashMap<>();
    /**
     * Wielders mid-Feast -> a snapshot of the real equipment we hid from viewers, so it can be restored the
     * moment the show ends (or they quit, or the plugin disables). Presence in this map == currently feasting.
     * The 30s Feast is a STEALTH window (invisibility + hidden gear), not an i-frame window.
     */
    private final Map<UUID, ItemStack[]> feastGear = new HashMap<>();
    /** Looker -> epoch-ms they began looking at a wielder; cleared the moment they look away. */
    private final Map<UUID, Long> lookingSince = new HashMap<>();
    /** Every live task (grapple charges, feasts, cognition filters), reaped on disable. */
    private final Set<BukkitRunnable> activeTasks = new HashSet<>();

    /** The equipment slots the Feast hides so an invisible wielder is truly unseen — body, armour, and hands. */
    private static final org.bukkit.inventory.EquipmentSlot[] HIDDEN_SLOTS = {
            org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
            org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET,
            org.bukkit.inventory.EquipmentSlot.HAND, org.bukkit.inventory.EquipmentSlot.OFF_HAND,
    };

    // ---- passive tuning -----------------------------------------------------------
    /** Fraction of armour every CENSORED blow ignores (via the framework's pierce). */
    private static final double PIERCE_FRACTION   = 0.30;
    /** The "saturation damage": exhaustion pushed onto a struck player, plus a short Hunger on any body. */
    private static final float  SATURATION_EXHAUST = 4.0f;
    private static final int    HUNGER_TICKS       = 80;   // ~4s
    private static final int    HUNGER_AMP         = 1;    // Hunger II
    /** Look longer than this at the wielder and you are sickened. Spec: 3 seconds. */
    private static final long   LOOK_SICKEN_MS     = 3_000L;
    /** How close a looker has to be to count, and how head-on their gaze must be (dot of facing vs. line). */
    private static final double LOOK_RANGE         = 16.0;
    private static final double LOOK_THRESHOLD     = 0.6;
    /** Nausea handed to a too-long looker, refreshed while they keep watching. */
    private static final int    NAUSEA_TICKS       = 100;  // 5s, refreshed

    // ---- enchants (conservative: reach + onset only, never damage) -----------------
    /** Custom [C] "Unblinking" (weaponId "censored"): -0.5s to the sicken onset per level, floored at 1.5s. */
    private static final int    UNBLINKING_MAX        = 2;
    private static final long   UNBLINKING_ONSET_CUT_MS = 500L;
    private static final long   LOOK_SICKEN_FLOOR_MS  = 1_500L;
    /** Vanilla-read (Sweeping Edge, otherwise dead here): +0.35 block of maw reach per level, capped at 3. */
    private static final int    SWEEP_REACH_CAP       = 3;
    private static final double SWEEP_REACH_PER_LEVEL = 0.35;

    // ---- M1 maw tuning ------------------------------------------------------------
    /** How far the maw lunges, and how wide its raytrace bites. */
    private static final double MAW_REACH          = 6.0;
    private static final double MAW_RADIUS         = 0.55;
    /** Damage per bite; two bites a swing sit around the item's base attack once the vanilla blow is zeroed. */
    private static final double MAW_BITE_DAMAGE    = 3.5;
    /** Slow, heavy cadence — one maw per this window however fast the wielder clicks. */
    private static final long   MAW_COOLDOWN_MS    = 1_000L;
    /** Chance, per on-cadence swing, that the maw fires the grapple for free (no charge cost, no cooldown). */
    private static final double FREE_GRAPPLE_CHANCE = 0.10;

    // ---- grapple tuning -----------------------------------------------------------
    private static final int    GRAPPLE_CHARGE_TICKS = 18;   // ~0.9s wind-up
    private static final double GRAPPLE_RANGE        = 12.0;  // the extended tear
    private static final double GRAPPLE_RADIUS       = 2.0;   // half-width of the line
    private static final int    GRAPPLE_HITS         = 3;     // each foe in the line is hit this many times
    private static final double GRAPPLE_DAMAGE       = 4.0;   // per hit
    private static final long   GRAPPLE_COOLDOWN_MS  = 12_000L;

    // ---- grasping arm (Right-click with no corpse) tuning -------------------------
    // A black+red limb that stems out like a root forcing itself to grow toward the nearest body, then drags
    // it to the wielder. All magnitudes PLACEHOLDER — flagged for Nyrrine's feel pass on reach/drag.
    /** How far the arm gropes for a body to seize. Slightly under the grapple's line so the two read apart. */
    private static final double ARM_REACH          = 10.0;
    /** The root stems out this slowly — deliberately laboured, "forcing itself to grow." */
    private static final int    ARM_GROW_TICKS     = 22;    // ~1.1s of creeping growth
    /** Once seized, the body is hauled in for up to this long, or until within {@link #ARM_GRAB_DIST}. */
    private static final int    ARM_DRAG_TICKS     = 12;
    private static final double ARM_GRAB_DIST      = 2.2;   // close enough — the haul is done
    private static final double ARM_DRAG_STRENGTH  = 0.65;  // velocity toward the wielder per drag tick
    /** A single bite as the hand closes on the seized body: pierced and left starving, like every CENSORED hit. */
    private static final double ARM_GRAB_DAMAGE    = 3.0;
    /** The real cooldown, armed ONLY on a successful catch. A whiff never pays it (Nyrrine's call). */
    private static final long   ARM_COOLDOWN_MS    = 8_000L;
    /** A tiny anti-spam lockout committed on every cast so an empty grope can't be scan-spammed. */
    private static final long   ARM_WHIFF_LOCKOUT_MS = 1_000L;
    /** The root's VFX: motes per grown segment, and the black jaw-hand scale at the tip. */
    private static final double ARM_SEG_STEP       = 0.45;  // a mote every this-many blocks along the limb
    private static final float  ARM_HAND_SCALE     = 0.7f;

    // ---- feast (Right-click) tuning -----------------------------------------------
    private static final long   PRIME_WINDOW_MS    = 6_000L;
    private static final long   FEAST_COOLDOWN_MS  = 20_000L;

    /**
     * How long the Feast <b>animation</b> runs: 30 seconds of sustained, choreographed torture. This is a
     * PURELY COSMETIC clock — it drives {@link CensoredFeast} and nothing else. It is NOT the i-frame window
     * and NOT the heal window; those are {@link #FEAST_PROTECT_MS} and {@link #HEAL_WINDOW_TICKS} below, and
     * both are kept deliberately short and INDEPENDENT of this number so the show can be long without handing
     * out 30 seconds of invulnerability or 30 seconds of healing. Do not re-couple them.
     */
    private static final int    SHOW_SECONDS       = 30;
    private static final int    SHOW_TICKS         = SHOW_SECONDS * 20;   // 600 ticks, the animation length only

    /**
     * <b>⚠ THE PvP-RELEVANT NUMBER — FLAGGED FOR NYRRINE.</b> The Feast grants the wielder i-frames (incoming
     * damage zeroed by {@link #onIncomingDamage}) for THIS long after the kill, to cover the teleport-in and
     * the first beat of the show. It is deliberately SHORT and is <b>decoupled from {@link #SHOW_TICKS}</b>:
     * the show is 30s, the invulnerability is ~2.5s. Never derive this from the show length. Default 2500ms.
     */
    private static final long   FEAST_PROTECT_MS   = 2_500L;

    /**
     * The wielder heals only during this SHORT opening window of the show, never across the full 30s. Kept as
     * its own tunable (separate from {@link #FEAST_PROTECT_MS} per Nyrrine's rule) even though the default
     * matches the protect window. At {@value #HEAL_EVERY}-tick cadence this is ~6 chunks of
     * {@value #HEAL_CHUNK}, ~6 HP, plus {@value #BURST_FINAL_HEAL} at the finale — a bounded ~9 HP, not 75.
     */
    private static final int    HEAL_WINDOW_TICKS  = 50;      // ~2.5s of healing, then the machine just hurts
    private static final int    HEAL_EVERY         = 8;       // heal a chunk this often during the heal window
    private static final double HEAL_CHUNK         = 1.0;
    private static final double BURST_FINAL_HEAL   = 3.0;     // the red-bar-burst payoff heal, once, at the end

    /** The torture-machine beat: every this-many ticks the show cranks — a heavier crush, bones, a bar-slam. */
    private static final int    CRANK_PERIOD       = 60;      // a crush every 3s across the 30s

    /** The lingering cognition filter left by the burst. */
    private static final double COGNITION_RADIUS   = 5.0;
    private static final double COGNITION_DAMAGE   = 2.0;
    private static final int    COGNITION_PERIOD   = 10;      // a pulse every 0.5s
    private static final int    COGNITION_TICKS    = 60;      // for ~3s

    // ---- VFX tuning: the pierce-then-maw (M1 + grapple), THICK red + black PARTICLES ----
    // Per Nyrrine's live pass: the maw is drawn in particles now, not BlockDisplay jaws — thicker, red AND
    // black, so both the M1 and the grapple read as heavy gore-work rather than tidy geometry.
    /** M1: the PIERCE — a thick dark-and-red particle lance thrusts to {@link #MAW_REACH} this fast. */
    private static final int    PIERCE_TICKS    = 3;      // a fast forward thrust
    private static final float  PIERCE_THICK    = 0.30f;  // the lance's mote jitter (how fat the thrust reads)
    /** M1: the MAW — two particle jaws sweep shut over this at the pierce's tip. */
    private static final int    JAW_CLOSE_TICKS = 4;
    private static final double MAW_SPAN        = 1.10;   // half-width the jaws yawn open before they snap
    private static final float  MAW_DUST_SIZE   = 2.3f;   // THICK red mote (client clamps DustOptions at 4.0)
    private static final float  MAW_VOID_SIZE   = 2.6f;   // THICK black mote
    /** Grapple: the PIERCE lances the full {@link #GRAPPLE_RANGE} line this fast before the jaws clamp it. */
    private static final int    GRAPPLE_LANCE_TICKS = 4;
    private static final float  GRAPPLE_LANCE_THICK = 0.55f;

    /** Feast bones: flung as ItemDisplays this often, this many at a time, tumbling for this long. */
    private static final int    BONE_EVERY      = 6;
    private static final int    BONE_BURST      = 2;
    private static final int    BONE_LIFE       = 30;
    private static final double BONE_SPEED      = 0.5;
    private static final double BONE_LOFT       = 0.5;
    private static final double BONE_GRAVITY    = 0.045;
    private static final float  BONE_SPIN       = 0.5f;
    private static final float  BONE_SCALE      = 0.5f;

    /**
     * The black CENSORED box that stands over the corpse for the Feast (the redaction monolith). Per Nyrrine's
     * live pass: SUPER TALL and WIDE — much bigger than the player, a standing redaction, not a flat slab.
     * Placeholder starting point for her feel-tune. Its base sits on the ground; the feast particles wrap its
     * full volume rather than pooling at its foot. (The old red finale rectangle has been removed.)
     */
    private static final double BODY_BAR_W      = 3.4;   // wide — well past the player's width
    private static final double BODY_BAR_H      = 6.0;   // super tall — a monolith standing over the corpse
    private static final double BODY_BAR_TH     = 3.4;

    /** Scoreboard tag on every display this weapon spawns — the belt-and-braces orphan-reap key. */
    private static final String CENSORED_TAG    = "reliquary_censored_vfx";

    public CensoredWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "censored");
    }

    @Override
    public String id() {
        return "censored";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.CENSORED.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.CENSORED.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.CENSORED);

        item.setItemMeta(meta);
        return item;
    }

    // ---- passive: the vanilla swing is not the weapon -----------------------------

    /**
     * The maw, not the sword, is the weapon. The vanilla melee blow is zeroed here so it can never stack on
     * top of the custom bites the {@link #onSwing} maw delivers; a foe close enough to be inside vanilla reach
     * is also inside the maw's, so nothing is lost. All of CENSORED's melee damage — and its armour-pierce and
     * its saturation drain — lives in the maw path.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        event.setDamage(0.0);
        breakVanish(attacker); // striking anything spends the Feast stealth (the weapon's own pierce is fenced out)
    }

    // ---- [Left Click]: the grappling maw ------------------------------------------

    /**
     * A swing extends the maw. On its slow cadence it lunges out along the wielder's aim, biting the first
     * body it reaches twice, and rolls the free-grapple proc. Off-cadence clicks (a spammed mouse) do nothing
     * but let the previous maw finish — this is a heavy weapon by design.
     */
    @Override
    public void onSwing(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMaw.get(player.getUniqueId());
        if (last != null && now - last < MAW_COOLDOWN_MS) return;
        lastMaw.put(player.getUniqueId(), now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double reach = mawReach(player); // base, plus a little per Sweeping Edge (a reach enchant, no damage)
        // Two beats, smooth: a fast dark PIERCE lances out along the aim, then the black MAW snaps shut at its
        // tip. Thick red and black throughout. Cosmetic — the bites below land synchronously as they always did.
        track(new Pierce(eye, dir, reach, PIERCE_TICKS, PIERCE_THICK, true)).runTaskTimer(plugin, 0L, 1L);

        LivingEntity target = mawTarget(player, eye, dir, reach);
        if (target != null) {
            biteTwice(player, target, dir);
        }

        // 10% per swing: the maw acts on its own, all at once — the grapple, free and instant.
        if (ThreadLocalRandom.current().nextDouble() < FREE_GRAPPLE_CHANCE) {
            castGrapple(player, true);
        }
    }

    /** The first living body the maw's line reaches within {@code reach}, or null on a whiff. */
    private LivingEntity mawTarget(Player player, Location eye, Vector dir, double reach) {
        RayTraceResult rt = player.getWorld().rayTraceEntities(eye, dir, reach, MAW_RADIUS,
                e -> e instanceof LivingEntity && !e.equals(player) && !e.isDead());
        return rt != null && rt.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    /**
     * ENCHANT (vanilla-read, Sweeping Edge): the maw's reach. Base {@link #MAW_REACH}, plus a small, capped
     * bump per Sweeping Edge level. Sweeping Edge is otherwise DEAD on CENSORED (the vanilla blow is zeroed in
     * {@link #onHit}), so this gives a would-be-useless sword enchant an honest reinterpretation — reach only,
     * never damage. The tooltip reads it as "Sweeping Edge"; the reinterpretation lives here and in the wiki.
     */
    private double mawReach(Player player) {
        int se = Math.min(SWEEP_REACH_CAP,
                player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SWEEPING_EDGE));
        return MAW_REACH + se * SWEEP_REACH_PER_LEVEL;
    }

    /**
     * Two bites off one swing. Each goes through the framework's {@code pierceDamage}, which fences it out of
     * the on-hit dispatch, clears the victim's i-frames so the second bite still lands, ignores {@link
     * #PIERCE_FRACTION} of armour, and restores velocity so the maw holds rather than knocks back. Each bite
     * also leaves the body starving.
     */
    private void biteTwice(Player player, LivingEntity target, Vector dir) {
        for (int i = 0; i < 2; i++) {
            plugin.weapons().pierceDamage(target, MAW_BITE_DAMAGE, PIERCE_FRACTION, player);
            starve(target);
        }
        Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        World world = target.getWorld();
        world.spawnParticle(Particle.DUST, at, 14, 0.3, 0.35, 0.3, 0, BLOOD_DUST);
        world.spawnParticle(Particle.ITEM, at, 6, 0.25, 0.3, 0.25, 0.03, BONE_ITEM);
        world.playSound(at, Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 0.6f);
        world.playSound(at, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.6f, 0.5f);
        slashCrescent(at, dir);   // the slash that lands after the maw snaps
        breakVanish(player);      // a maw bite spends the Feast stealth (raytrace hit, past vanilla reach)
    }

    /** The saturation drain: push exhaustion onto a struck player and lay a short Hunger on any body. */
    private void starve(LivingEntity victim) {
        if (victim instanceof Player p) {
            p.setExhaustion(Math.min(40.0f, p.getExhaustion() + SATURATION_EXHAUST));
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, HUNGER_TICKS, HUNGER_AMP, false, true, true));
    }

    // ---- [Shift+Right Click]: the big grapple / [Right Click]: feast-or-arm -------

    /**
     * Shift+Right is always the line grapple. Plain Right is TWO-MODE and always does something: if a fresh
     * corpse is waiting AND the Feast is off cooldown, Right feeds on that body; otherwise (no corpse, OR a
     * corpse but the Feast is still on its silence) it falls through to the grasping arm to haul the nearest
     * body in. There is no prime step, and R-click never dead-ends on a cooldown message.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) { castGrapple(player, false); return; }

        UUID id = player.getUniqueId();
        Location corpse = freshCorpse(id);
        if (corpse != null && feastReady(id)) beginFeastFromInput(player, corpse);
        else graspingArm(player);
    }

    /**
     * Begin the grapple's charge. A manual cast ({@code free == false}) is refused while one is winding up or
     * while on cooldown; a free proc ({@code free == true}) ignores the cooldown entirely but still won't
     * stack on a charge already running.
     */
    private void castGrapple(Player player, boolean free) {
        UUID id = player.getUniqueId();
        if (charging.contains(id)) return;

        long now = System.currentTimeMillis();
        if (!free) {
            Long ready = grappleReadyAt.get(id);
            if (ready != null && now < ready) {
                player.playSound(player.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.25f, 0.5f); // the HUD shows the cd
                return;
            }
        }

        charging.add(id);
        track(new GrappleCharge(id, free)).runTaskTimer(plugin, 1L, 1L);
    }

    /** The charge, then the tear. Draws a maw gathering for {@link #GRAPPLE_CHARGE_TICKS}, then strikes once. */
    private final class GrappleCharge extends BukkitRunnable {
        private final UUID ownerId;
        private final boolean free;
        private int ticks = 0;

        GrappleCharge(UUID ownerId, boolean free) {
            this.ownerId = ownerId;
            this.free = free;
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isValid() || !matches(owner.getInventory().getItemInMainHand())) {
                charging.remove(ownerId);
                stop();
                return;
            }

            if (ticks++ < GRAPPLE_CHARGE_TICKS) {
                chargeVfx(owner);
                return;
            }

            strikeGrapple(owner);
            charging.remove(ownerId);
            if (!free) grappleReadyAt.put(ownerId, System.currentTimeMillis() + GRAPPLE_COOLDOWN_MS);
            stop();
        }

        private void stop() {
            activeTasks.remove(this);
            cancel();
        }
    }

    /**
     * The grapple as a PIERCE then a MAW, thick red and black. A dark spike lances the full line fast (the
     * pierce); every living body it catches takes {@link #GRAPPLE_HITS} bites and is left starving, and a set
     * of black jaws clamps over each caught body once the lance has swept past it (the maw). Same damage and
     * reach as before — only the shape reads differently now.
     */
    private void strikeGrapple(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = owner.getWorld();

        // The pierce: one fast dark-and-red spike down the whole 12-block line.
        track(new Pierce(eye.clone(), dir.clone(), GRAPPLE_RANGE, GRAPPLE_LANCE_TICKS, GRAPPLE_LANCE_THICK, false))
                .runTaskTimer(plugin, 0L, 1L);
        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 0.6f);
        world.playSound(eye, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 0.5f);

        Location mid = eye.clone().add(dir.clone().multiply(GRAPPLE_RANGE * 0.5));
        double half = GRAPPLE_RANGE * 0.5 + GRAPPLE_RADIUS;
        boolean caught = false;
        for (Entity e : world.getNearbyEntities(mid, half, half, half)) {
            if (e.equals(owner) || !(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            Vector v = le.getLocation().add(0, le.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
            double t = v.dot(dir);
            if (t < 0 || t > GRAPPLE_RANGE) continue;
            if (v.clone().subtract(dir.clone().multiply(t)).length() > GRAPPLE_RADIUS) continue;

            for (int i = 0; i < GRAPPLE_HITS; i++) {
                plugin.weapons().pierceDamage(le, GRAPPLE_DAMAGE, PIERCE_FRACTION, owner);
            }
            starve(le);
            caught = true;
            // The maw: particle jaws yawn open at this body until the lance reaches it (openHold scaled by how
            // far down the line it is), then sweep shut. Cosmetic; self-cancelling.
            int hold = (int) Math.round(GRAPPLE_LANCE_TICKS * (t / GRAPPLE_RANGE));
            Location at = le.getLocation().add(0, le.getHeight() * 0.5, 0);
            track(new JawSnap(at, dir.clone(), hold)).runTaskTimer(plugin, 0L, 1L);
            shockwave(at); // a burst where the tear catches a body
        }
        if (caught) breakVanish(owner); // the grapple striking a body spends the Feast stealth
    }

    // ---- [Right Click, no corpse]: the grasping arm ------------------------------

    /**
     * The no-corpse branch of Right-click: a grotesque black-and-red limb stems out like a root forcing itself
     * to grow toward the nearest body, then hauls it in. Casting commits only a short anti-spam lockout
     * ({@link #ARM_WHIFF_LOCKOUT_MS}); the real {@link #ARM_COOLDOWN_MS} is armed ONLY on a successful catch, in
     * {@link GraspingArm}, so a whiff costs almost nothing. Reach and drag feel are PLACEHOLDER.
     */
    private void graspingArm(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long ready = armReadyAt.get(id);
        if (ready != null && now < ready) {
            player.playSound(player.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.25f, 0.5f); // the HUD shows the cd
            return;
        }
        armReadyAt.put(id, now + ARM_WHIFF_LOCKOUT_MS); // tiny lockout now; a catch upgrades it to the full CD
        player.playSound(player.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.7f, 0.5f);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 0.6f, 0.6f);
        track(new GraspingArm(player)).runTaskTimer(plugin, 1L, 1L);
    }

    // ---- corpse bookkeeping: a kill leaves a body to feed on for a short grace ------

    /**
     * A kill by a CENSORED wielder records the corpse and starts its post-kill grace (the repurposed
     * {@link #PRIME_WINDOW_MS}). An R-click within the grace begins the Feast on that spot. This is the whole
     * of the "passive, always-available after the kill" model — there is no prime to press beforehand.
     */
    private void recordCorpse(Player killer, Location where) {
        if (!matches(killer.getInventory().getItemInMainHand())) return;
        UUID id = killer.getUniqueId();
        corpseUntil.put(id, System.currentTimeMillis() + PRIME_WINDOW_MS);
        corpseLoc.put(id, where.clone());
        // No lone action-bar cue — the always-on HUD reads "Feast — feed" while the corpse waits.
        killer.playSound(killer.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.5f, 0.5f);
    }

    /** The corpse still inside its grace for this wielder, or null — lapsed entries are pruned as we look. */
    private Location freshCorpse(UUID id) {
        Long until = corpseUntil.get(id);
        if (until == null || System.currentTimeMillis() > until) {
            corpseUntil.remove(id);
            corpseLoc.remove(id);
            return null;
        }
        return corpseLoc.get(id);
    }

    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) recordCorpse(killer, event.getEntity().getLocation());
    }

    /** A slain player is a corpse to feed on too — CENSORED does not care what it eats. */
    @Override
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) recordCorpse(killer, event.getEntity().getLocation());
    }

    // ---- the Feast: the signature stealth sequence --------------------------------

    /** True if the Feast is off its silence for this wielder — the gate {@link #onInteract} routes on. */
    private boolean feastReady(UUID id) {
        Long ready = feastReadyAt.get(id);
        return ready == null || System.currentTimeMillis() >= ready;
    }

    /**
     * R-click on a fresh corpse with the Feast ready (the caller has already checked {@link #feastReady}):
     * consume the corpse, start the silence, and begin the show. No teleport — the wielder is already on the
     * kill, and the point of the Feast is to slip away invisible while it plays.
     */
    private void beginFeastFromInput(Player wielder, Location corpse) {
        UUID id = wielder.getUniqueId();
        corpseUntil.remove(id);
        corpseLoc.remove(id);
        feastReadyAt.put(id, System.currentTimeMillis() + FEAST_COOLDOWN_MS); // the silence
        beginFeast(wielder, corpse);
    }

    /**
     * Start the 30s Feast. The wielder VANISHES — invisibility plus every equipment slot blanked to viewers —
     * and walks free while the torture show runs on the corpse. NO i-frames: the payoff is stealth and
     * surprise, not invulnerability (this is what resolves the old PvP balance flag). A black censor bar slams
     * over both bodies on the opening beat; then only the corpse keeps its bar so a bar never trails the
     * now-invisible wielder.
     */
    private void beginFeast(Player wielder, Location body) {
        vanish(wielder); // invisibility + hidden gear for the show; undone in CensoredFeast.stop()

        World world = body.getWorld();
        world.playSound(body, Sound.ENTITY_WARDEN_DIG, 1.0f, 0.4f);
        world.playSound(body, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 0.4f);
        track(new CensoredFeast(wielder.getUniqueId(), body.clone(), wielder.getLocation().clone()))
                .runTaskTimer(plugin, 1L, 1L);
    }

    // ---- vanish: the Feast's stealth (replaces the old i-frames) ------------------

    /** Vanish the wielder for the Feast: invisibility, and every equipment slot blanked to nearby viewers. */
    private void vanish(Player wielder) {
        UUID id = wielder.getUniqueId();
        org.bukkit.inventory.PlayerInventory inv = wielder.getInventory();
        feastGear.put(id, new ItemStack[]{ // snapshot the real gear so it comes back exactly as it was
                inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), inv.getItemInOffHand() });
        // Invisibility a hair longer than the show, then explicitly removed on stop() so nothing lingers.
        wielder.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, SHOW_TICKS + 20, 0, false, false, false));
        hideGear(wielder);
    }

    /** Blank every hidden slot to the players who can currently see the wielder — the "hide the armour" packet. */
    private void hideGear(Player wielder) {
        for (Player viewer : nearbyViewers(wielder)) {
            for (org.bukkit.inventory.EquipmentSlot slot : HIDDEN_SLOTS) {
                viewer.sendEquipmentChange(wielder, slot, AIR);
            }
        }
    }

    /** End the vanish: hand the real gear back to nearby viewers and drop the invisibility. Safe if never set. */
    private void unvanish(UUID id) {
        ItemStack[] gear = feastGear.remove(id);
        Player wielder = plugin.getServer().getPlayer(id);
        if (wielder == null) return; // gone — nothing client-side to correct; a re-track resends real gear anyway
        wielder.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (gear == null) return;
        for (Player viewer : nearbyViewers(wielder)) {
            for (int i = 0; i < HIDDEN_SLOTS.length; i++) {
                viewer.sendEquipmentChange(wielder, HIDDEN_SLOTS[i], gear[i] == null ? AIR : gear[i]);
            }
        }
    }

    /**
     * Spend the stealth: if this wielder is vanished mid-Feast, drop it now. Called the instant they attack
     * something or stop holding the weapon — the stealth is spent on the attack (Nyrrine's call). The Feast
     * show itself keeps playing on the corpse; only the invisibility ends. No-op if they are not vanished.
     */
    private void breakVanish(Player wielder) {
        if (feastGear.containsKey(wielder.getUniqueId())) unvanish(wielder.getUniqueId());
    }

    /** Players in the same world within a comfortable tracking range of the wielder. */
    private List<Player> nearbyViewers(Player wielder) {
        List<Player> out = new ArrayList<>();
        for (Player p : wielder.getWorld().getPlayers()) {
            if (p.equals(wielder)) continue;
            if (p.getLocation().distanceSquared(wielder.getLocation()) <= 64.0 * 64.0) out.add(p);
        }
        return out;
    }

    /**
     * The 30s show: a sustained, choreographed torture over the corpse. A steady pour of blood and dread runs
     * throughout; every {@link #CRANK_PERIOD} the machine CRANKS — a heavier crush, a fling of bone, a
     * redaction-bar slam — so it reads as a rhythm, never one burst stretched thin. The wielder heals only in
     * the opening {@link #HEAL_WINDOW_TICKS} window, then it just hurts. A black censor bar rides the corpse
     * the whole time (the wielder's opening bar lasts only the first beat). At the end the red RECTANGLE
     * engulfs and bursts, paying off a final heal and leaving the lingering cognition filter.
     */
    private final class CensoredFeast extends BukkitRunnable {
        private final UUID ownerId;
        private final Location body;
        private int ticks = 0;
        private BlockDisplay corpseBar;   // the black redaction over the corpse, held for the whole show
        private BlockDisplay wielderBar;  // the opening-beat bar over the killer; removed after OPENING_BAR

        private static final int OPENING_BAR_TICKS = 12; // the "coat both bodies" beat, then stealth takes over

        CensoredFeast(UUID ownerId, Location body, Location wielderAt) {
            this.ownerId = ownerId;
            this.body = body;
            // Centre the tall box at half its height so its base sits on the ground and it stands over the body.
            this.corpseBar  = spawnCensorBar(body.clone().add(0, BODY_BAR_H * 0.5, 0));
            this.wielderBar = spawnCensorBar(wielderAt.clone().add(0, BODY_BAR_H * 0.5, 0)); // both bodies, kill moment
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isValid()) { stop(); return; } // logged off — reap + un-vanish

            // Swapping off the weapon spends the stealth immediately; the show carries on at the corpse.
            if (feastGear.containsKey(ownerId) && !matches(owner.getInventory().getItemInMainHand())) {
                unvanish(ownerId);
            }

            if (ticks == OPENING_BAR_TICKS && wielderBar != null) { // the opening redaction is over
                if (wielderBar.isValid()) wielderBar.remove();
                wielderBar = null;
            }

            if (ticks >= SHOW_TICKS) { finale(owner); stop(); return; }

            wrapVfx(body, ticks);
            macabreSfx(body, ticks);
            if (ticks % BONE_EVERY == 0) flingBones(body, BONE_BURST);
            if (ticks < HEAL_WINDOW_TICKS && ticks % HEAL_EVERY == 0) heal(owner, HEAL_CHUNK);
            // Re-hide for new viewers only while still vanished — once the stealth is spent, stop re-blanking.
            if (ticks % 20 == 0 && feastGear.containsKey(ownerId)) hideGear(owner);
            if (ticks > 0 && ticks % CRANK_PERIOD == 0) crank(); // the torture-machine beat
            ticks++;
        }

        /** A crush beat: a wrench of extra blood and bone, a bar-slam over the corpse, a heavy machine sound. */
        private void crank() {
            World world = body.getWorld();
            Location core = body.clone().add(0, 1.0, 0);
            world.spawnParticle(Particle.DUST, core, 40, 0.6, 0.7, 0.6, 0, BLOOD_DUST);
            world.spawnParticle(Particle.BLOCK, core, 24, 0.5, 0.5, 0.5, 0, BLOOD_BLOCK);
            flingBones(body, BONE_BURST + 2);
            world.playSound(body, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.8f, 0.4f);
            world.playSound(body, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.4f);
            if (corpseBar != null && corpseBar.isValid()) barSlam(corpseBar);
        }

        /**
         * The finale: a last heavy beat and the payoff, then the lingering cognition filter. The old red
         * finale rectangle has been removed (Nyrrine's live pass — it was not working); the tall black box is
         * the show, and it is reaped in {@link #stop()} right after this.
         */
        private void finale(Player owner) {
            World world = body.getWorld();
            world.playSound(body, Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.4f);
            world.playSound(body, Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 0.4f);
            heal(owner, BURST_FINAL_HEAL); // one payoff heal, once, at the very end
            track(new CognitionFilter(ownerId, body.clone())).runTaskTimer(plugin, 1L, 1L);
        }

        private void stop() {
            if (corpseBar  != null && corpseBar.isValid())  corpseBar.remove();
            if (wielderBar != null && wielderBar.isValid()) wielderBar.remove();
            unvanish(ownerId); // the wielder reappears the instant the show ends
            activeTasks.remove(this);
            cancel();
        }
    }

    /**
     * The cognition filter the burst leaves behind: a red censored square hanging over the spot that bleeds
     * everything living near it, on a pulse, for {@link #COGNITION_TICKS}. Credited to the wielder while they
     * are online so the pierce lands; pure VFX if they have gone.
     */
    private final class CognitionFilter extends BukkitRunnable {
        private final UUID ownerId;
        private final Location centre;
        private int ticks = 0;

        CognitionFilter(UUID ownerId, Location centre) {
            this.ownerId = ownerId;
            this.centre = centre;
        }

        @Override
        public void run() {
            if (ticks >= COGNITION_TICKS) { stop(); return; }
            redSquare(centre, 1.4);

            if (ticks % COGNITION_PERIOD == 0) {
                Player owner = plugin.getServer().getPlayer(ownerId);
                for (Entity e : centre.getWorld().getNearbyEntities(centre, COGNITION_RADIUS, COGNITION_RADIUS, COGNITION_RADIUS)) {
                    if (e.getUniqueId().equals(ownerId)) continue;
                    if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
                    if (owner != null) plugin.weapons().pierceDamage(le, COGNITION_DAMAGE, PIERCE_FRACTION, owner);
                }
            }
            ticks++;
        }

        private void stop() {
            activeTasks.remove(this);
            cancel();
        }
    }

    // ---- passive: the sickening gaze ----------------------------------------------
    // (The Feast no longer grants i-frames — its protection is stealth, not invulnerability — so there is no
    // onIncomingDamage hook here anymore. See beginFeast / vanish.)

    /**
     * Each engaged tick, sicken anyone who has held their gaze on the wielder too long — UNLESS the wielder is
     * mid-Feast and vanished, in which case the gaze aura is suppressed for the duration (it would otherwise
     * telegraph the stealth by sickening onlookers around an invisible body). It comes back with unvanish.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        // Sheathed -> return false so the manager stops ticking us and releases the HUD; else we would spam the
        // action bar forever and override every other weapon's line once Censored had been held.
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        if (!feastGear.containsKey(player.getUniqueId())) lookScan(player);
        player.sendActionBar(hud(player.getUniqueId())); // one composed line every tick, never a lone flash
        return true;
    }

    // ---- HUD: one always-on composed line — every cooldown/status at once, no lone flashing ----------

    /**
     * The whole readout, built fresh every tick and handed to {@code sendActionBar}: the maw cadence, the
     * grapple cooldown, the Feast state (a fresh corpse to feed on, or its silence), and the grasping arm's
     * cooldown. No ability sends its own lone line anymore — they all read here, always, in the same order, so
     * nothing ever flashes in over another. Long is fine; the house rule is one line, never a replacement.
     */
    private Component hud(UUID id) {
        long now = System.currentTimeMillis();
        Component maw   = cdOrReady("Maw", lastMaw.getOrDefault(id, 0L) + MAW_COOLDOWN_MS, now);
        Component grap  = cdOrReady("Grapple", grappleReadyAt.getOrDefault(id, 0L), now);
        Component feast = feastPart(id, now);
        Component arm   = cdOrReady("Arm", armReadyAt.getOrDefault(id, 0L), now);
        return EgoHud.row(maw, grap, feast, arm);
    }

    private Component cdOrReady(String name, long readyAt, long now) {
        return now >= readyAt ? EgoHud.ready(name, CENSOR_RED) : EgoHud.cooldown(name, readyAt - now, REDACT);
    }

    /** The Feast slot: a fresh corpse waiting to be fed on takes priority; else the silence, else ready. */
    private Component feastPart(UUID id, long now) {
        if (feastReady(id) && freshCorpse(id) != null) return EgoHud.status("Feast — feed", CENSOR_RED);
        Long ready = feastReadyAt.get(id);
        return (ready != null && now < ready) ? EgoHud.cooldown("Feast", ready - now, REDACT)
                                              : EgoHud.ready("Feast", CENSOR_RED);
    }

    /**
     * Anyone within {@link #LOOK_RANGE} facing the wielder head-on accrues look-time; hold it past the sicken
     * onset and they are sickened with Nausea, refreshed while they keep watching. Looking away clears the
     * clock at once. The onset is {@link #LOOK_SICKEN_MS} by default, cut by the Unblinking enchant.
     */
    private void lookScan(Player wielder) {
        long now = System.currentTimeMillis();
        long onset = sickenOnset(wielder);
        Set<UUID> stillLooking = new HashSet<>();
        for (Entity e : wielder.getNearbyEntities(LOOK_RANGE, LOOK_RANGE, LOOK_RANGE)) {
            if (!(e instanceof LivingEntity looker) || looker.isDead() || looker.equals(wielder)) continue;
            if (!isLookingAt(looker, wielder)) continue;
            UUID lid = looker.getUniqueId();
            stillLooking.add(lid);
            long since = lookingSince.computeIfAbsent(lid, k -> now);
            if (now - since >= onset) {
                looker.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0, false, true, true));
            }
        }
        // Prune anyone who looked away, died, or left range this scan — the leak the review caught.
        lookingSince.keySet().retainAll(stillLooking);
    }

    /**
     * ENCHANT (custom [C], Unblinking): the sicken onset. Base {@link #LOOK_SICKEN_MS}, cut by
     * {@link #UNBLINKING_ONSET_CUT_MS} per level (watchers sicken sooner), floored at {@link #LOOK_SICKEN_FLOOR_MS}
     * so it never becomes instant. Duration/utility only, no damage.
     */
    private long sickenOnset(Player wielder) {
        int lvl = Math.min(UNBLINKING_MAX,
                EgoEnchants.level(wielder.getInventory().getItemInMainHand(), "unblinking"));
        return Math.max(LOOK_SICKEN_FLOOR_MS, LOOK_SICKEN_MS - (long) lvl * UNBLINKING_ONSET_CUT_MS);
    }

    /** True if {@code looker} is facing {@code wielder} head-on past {@link #LOOK_THRESHOLD}. */
    private boolean isLookingAt(LivingEntity looker, LivingEntity wielder) {
        Vector facing = looker.getEyeLocation().getDirection();
        if (facing.lengthSquared() < 1.0e-6) return false;
        Vector toWielder = wielder.getEyeLocation().toVector().subtract(looker.getEyeLocation().toVector());
        if (toWielder.lengthSquared() < 1.0e-6) return false;
        return facing.normalize().dot(toWielder.normalize()) >= LOOK_THRESHOLD;
    }

    // ---- helpers ------------------------------------------------------------------

    private void heal(Player owner, double amount) {
        AttributeInstance maxAttr = owner.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        owner.setHealth(Math.min(max, owner.getHealth() + amount));
    }

    private <T extends BukkitRunnable> T track(T task) {
        activeTasks.add(task);
        return task;
    }

    /** A point out in front of the wielder's chest, where the grasping arm tears free. */
    private Location handOf(Player p) {
        return p.getLocation().add(0, 1.0, 0).add(p.getLocation().getDirection().normalize().multiply(0.6));
    }

    /** The nearest living body to the wielder within {@link #ARM_REACH}, or null — what the arm reaches for. */
    private LivingEntity nearestBody(Player owner) {
        LivingEntity best = null;
        double bestSq = ARM_REACH * ARM_REACH;
        for (Entity e : owner.getNearbyEntities(ARM_REACH, ARM_REACH, ARM_REACH)) {
            if (e.equals(owner) || !(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            double sq = le.getLocation().distanceSquared(owner.getLocation());
            if (sq < bestSq) { bestSq = sq; best = le; }
        }
        return best;
    }

    private Location centreOf(LivingEntity le) {
        return le.getLocation().add(0, le.getHeight() * 0.5, 0);
    }

    /** The live entity for {@code id}, or null if it is gone, dead, or invalid. */
    private LivingEntity living(UUID id) {
        Entity e = plugin.getServer().getEntity(id);
        return e instanceof LivingEntity le && !le.isDead() && le.isValid() ? le : null;
    }

    // ---- presentation -------------------------------------------------------------

    /**
     * The slash that lands as the maw snaps shut: Yae's shared crescent ({@link SlashVfx}), tuned for a bite —
     * a wide fan swept diagonally, a blood trail into a bright edge with sparks flung off it. Cosmetic; it
     * reaps its own geometry. (If the maw ever wants a thrust-stab shape instead, that is a SlashVfx variant.)
     */
    private void slashCrescent(Location centre, Vector dir) {
        SlashVfx.slash(plugin, centre, dir)
                .arcSpan(140)
                .reach(2.6)
                .colours(VOID_RGB, CENSOR_RGB) // thick red + black: a near-black trail into a hard red edge
                .thickness(1.7f)
                .duration(4)
                .tilt(20)
                .sparks(true)
                .play();
    }

    /** The grapple gathering: red motes drawn inward at the wielder's chest each charge tick. */
    private void chargeVfx(Player owner) {
        World world = owner.getWorld();
        Location chest = owner.getLocation().add(0, 1.0, 0);
        for (int i = 0; i < 6; i++) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double r = 1.4;
            Location p = chest.clone().add(Math.cos(a) * r, ThreadLocalRandom.current().nextDouble() - 0.5, Math.sin(a) * r);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, CENSOR_DUST);
        }
        world.playSound(owner.getLocation(), Sound.BLOCK_SCULK_CHARGE, 0.4f, 0.5f);
    }

    /** A burst where the tear catches a body: an explosion flash, a sweep glyph, and a low boom. */
    private void shockwave(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, 24, 0.25, 0.3, 0.25, 0, CENSOR_DUST);
        world.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.SWEEP_ATTACK, at, 3, 0.3, 0.3, 0.3, 0);
        world.playSound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.4f, 0.8f);
    }

    /** Fling {@code n} real bones out of the corpse as tumbling, falling ItemDisplays. */
    private void flingBones(Location body, int n) {
        Location from = body.clone().add(0, 0.9, 0);
        for (int i = 0; i < n; i++) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double s = BONE_SPEED * (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8);
            Vector v = new Vector(Math.cos(a) * s, BONE_LOFT + ThreadLocalRandom.current().nextDouble() * 0.25, Math.sin(a) * s);
            track(new FlungBone(from.clone(), v)).runTaskTimer(plugin, 1L, 1L);
        }
    }

    /**
     * Per-tick feast show: particles that WRAP the tall black box — clinging to its outer faces up its full
     * height, not pooled at its foot (Nyrrine's live pass dropped the ground splat). The box is a solid opaque
     * monolith, so every mote is placed just OUTSIDE its faces where it reads against the black: a scatter
     * clinging round it, a red swirl climbing on a rising ring, and the odd wet gobbet down a face. Counts are
     * deliberately trimmed so the show stays light and clears fast rather than caking the air.
     */
    private void wrapVfx(Location body, int t) {
        World world = body.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double h = BODY_BAR_H, rW = BODY_BAR_W * 0.5 + 0.25, rTh = BODY_BAR_TH * 0.5 + 0.25; // a shell just outside

        // A scatter clinging round the box, anywhere up its full height — red over black.
        for (int i = 0; i < 4; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            Location p = body.clone().add(Math.cos(a) * rW, rng.nextDouble() * h, Math.sin(a) * rTh);
            world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, rng.nextBoolean() ? BLOOD_DUST : VOID_DUST);
        }
        // The wrap: a red swirl climbing the box's faces on a rising, rotating ring.
        double climb = (t % 40) / 40.0;                 // sweeps up the full height, repeating
        double ang = t * 0.30;
        for (int k = 0; k < 2; k++) {
            double a = ang + k * Math.PI;
            world.spawnParticle(Particle.DUST, body.clone().add(Math.cos(a) * rW, climb * h, Math.sin(a) * rTh),
                    1, 0.03, 0.03, 0.03, 0, CENSOR_DUST);
        }
        // An occasional wet gobbet sliding down a face.
        if (t % 6 == 0) {
            double a = rng.nextDouble() * Math.PI * 2;
            world.spawnParticle(Particle.FALLING_DUST,
                    body.clone().add(Math.cos(a) * rW, rng.nextDouble() * h, Math.sin(a) * rTh),
                    2, 0.05, 0.1, 0.05, 0, BLOOD_BLOCK);
        }
    }

    /** The sound of the feed: WET and squelching, violently uncomfortable — slime/mud squish over an eat and a
     * heartbeat bed, all staggered on coprime periods so it never reads as one loop. */
    private void macabreSfx(Location at, int t) {
        World world = at.getWorld();
        if (t % 3 == 0)  world.playSound(at, Sound.ENTITY_SLIME_SQUISH, 0.9f, 0.5f);       // the squelch
        if (t % 4 == 0)  world.playSound(at, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.9f, 0.4f);   // a wet tear
        if (t % 5 == 0)  world.playSound(at, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.5f);        // the feed
        if (t % 6 == 0)  world.playSound(at, Sound.BLOCK_MUD_BREAK, 1.0f, 0.5f);           // a thick muddy squish
        if (t % 8 == 0)  world.playSound(at, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 0.4f); // a smaller squelch
        if (t % 11 == 0) world.playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 0.6f, 0.4f);
        if (t % 13 == 0) world.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.5f);
        if (t % 17 == 0) world.playSound(at, Sound.ENTITY_GHAST_HURT, 0.4f, 0.4f);
    }

    /** The red censored square: a flat filled quad of red motes hanging over a point (the lingering filter). */
    private void redSquare(Location centre, double size) {
        World world = centre.getWorld();
        Location c = centre.clone().add(0, 0.15, 0);
        double step = size / 5.0;
        for (double x = -size; x <= size; x += step) {
            for (double z = -size; z <= size; z += step) {
                world.spawnParticle(Particle.DUST, c.clone().add(x, 0, z), 1, 0, 0, 0, 0, CENSOR_DUST);
            }
        }
    }

    // ---- display-entity choreography ----------------------------------------------

    /** A black CENSORED redaction bar (a wide, thin, tall black slab, {@link #JAW_BLOCK}) hung over a point. */
    private BlockDisplay spawnCensorBar(Location at) {
        float w = (float) BODY_BAR_W, h = (float) BODY_BAR_H, th = (float) BODY_BAR_TH;
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setBlock(JAW_BLOCK);                          // BLACK_CONCRETE — the redaction
            d.setTransformation(new Transformation(
                    new Vector3f(-w / 2, -h / 2, -th / 2), new Quaternionf(),
                    new Vector3f(w, h, th), new Quaternionf()));
            d.setBrightness(new Display.Brightness(0, 0));  // fully dark: a true black bar
            d.setInterpolationDuration(2);
            d.setInterpolationDelay(0);
            d.setPersistent(false);
            d.addScoreboardTag(CENSORED_TAG);
        });
    }

    /** A crank-beat accent on the standing corpse bar: a hard scatter of red around the black redaction. */
    private void barSlam(BlockDisplay bar) {
        Location at = bar.getLocation();
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, 20, BODY_BAR_W * 0.5, BODY_BAR_H, BODY_BAR_TH * 0.5, 0, CENSOR_DUST);
        world.spawnParticle(Particle.BLOCK, at, 12, BODY_BAR_W * 0.4, BODY_BAR_H, BODY_BAR_TH * 0.4, 0, SQUARE_BLOCK);
    }

    /**
     * The PIERCE: a fast forward thrust drawn entirely in THICK red-and-black particles (no block geometry) —
     * a fat lance from {@code origin} along {@code dir} out to {@code reach} over {@code travelTicks}, with a
     * dense gore knot at its leading point. On arrival it can hand off to a {@link JawSnap} at the tip, the MAW
     * half of the beat. Captured origin/aim; over in a handful of ticks.
     */
    private final class Pierce extends BukkitRunnable {
        private final Location origin;
        private final Vector dir;
        private final double reach;
        private final int travelTicks;
        private final float thick;
        private final boolean snapAtTip;
        private int ticks = 0;

        Pierce(Location origin, Vector dir, double reach, int travelTicks, float thick, boolean snapAtTip) {
            this.origin = origin.clone();
            this.dir = dir.clone().normalize();
            this.reach = reach;
            this.travelTicks = Math.max(1, travelTicks);
            this.thick = thick;
            this.snapAtTip = snapAtTip;
        }

        @Override
        public void run() {
            if (ticks > travelTicks) { arrive(); return; }
            double frac = (double) ticks / travelTicks;
            double cur = 0.6 + frac * (reach - 0.6);
            lance(cur);
            knot(origin.clone().add(dir.clone().multiply(cur))); // the fat point of the thrust
            ticks++;
        }

        /** The thick red-and-black lance drawn from the wielder to the current tip — sparse, so it clears fast. */
        private void lance(double cur) {
            World world = origin.getWorld();
            for (double t = 0.5; t <= cur; t += 0.5) {
                Location p = origin.clone().add(dir.clone().multiply(t));
                world.spawnParticle(Particle.DUST, p, 1, thick * 0.6, thick * 0.6, thick * 0.6, 0, MAW_VOID_DUST);
                world.spawnParticle(Particle.DUST, p, 1, thick * 0.7, thick * 0.7, thick * 0.7, 0, MAW_RED_DUST);
            }
        }

        /** A thick knot of red and black at the lance's leading point. */
        private void knot(Location p) {
            World world = p.getWorld();
            world.spawnParticle(Particle.DUST, p, 4, 0.16, 0.16, 0.16, 0, MAW_VOID_DUST);
            world.spawnParticle(Particle.DUST, p, 4, 0.14, 0.14, 0.14, 0, MAW_RED_DUST);
        }

        private void arrive() {
            if (snapAtTip) {
                track(new JawSnap(origin.clone().add(dir.clone().multiply(reach)), dir, 0)).runTaskTimer(plugin, 0L, 1L);
            }
            activeTasks.remove(this);
            cancel();
        }
    }

    /**
     * The MAW: two jaws drawn in THICK red-and-black particles (no block geometry) that yawn open at a point
     * and sweep shut in a gore burst — the bite. The jaws spread across the axis perpendicular to the bite and
     * curve like crescents, so it reads from any angle. {@code openHold} keeps them open until a pierce reaches
     * them (used down the grapple line), then they close. Purely cosmetic.
     */
    private final class JawSnap extends BukkitRunnable {
        private final Location at;
        private final Vector wide;   // horizontal axis across which each jaw spreads (perpendicular to the bite)
        private final int openHoldTicks;
        private int ticks = 0;

        JawSnap(Location at, Vector dir, int openHold) {
            this.at = at.clone();
            Vector d = dir.lengthSquared() < 1.0e-6 ? new Vector(1, 0, 0) : dir.clone().normalize();
            Vector w = d.crossProduct(new Vector(0, 1, 0));
            this.wide = w.lengthSquared() < 1.0e-6 ? new Vector(1, 0, 0) : w.normalize();
            this.openHoldTicks = Math.max(0, openHold);
        }

        @Override
        public void run() {
            if (ticks < openHoldTicks) { jaws(MAW_SPAN); ticks++; return; } // yawning open, awaiting the pierce
            int c = ticks - openHoldTicks;
            if (c >= JAW_CLOSE_TICKS) { snap(); return; }
            double f = (double) c / JAW_CLOSE_TICKS;
            jaws(MAW_SPAN * (1.0 - f) + 0.05 * f); // sweeps shut
            ticks++;
        }

        /** Draw the upper and lower jaws at the given half-gap. */
        private void jaws(double gap) {
            drawJaw(gap);
            drawJaw(-gap);
        }

        /** One jaw: a thick red-and-black crescent above (or below) the bite point, tips dipping inward. */
        private void drawJaw(double yOff) {
            World world = at.getWorld();
            int n = 5;
            for (int i = 0; i <= n; i++) {
                double s = (i / (double) n - 0.5) * 2.0;               // -1..1 across the jaw's width
                double curve = -yOff * 0.5 * (1.0 - s * s);            // a shallow crescent toward the centre
                Location p = at.clone().add(wide.clone().multiply(s * MAW_SPAN)).add(0, yOff + curve, 0);
                world.spawnParticle(Particle.DUST, p, 1, 0.04, 0.04, 0.04, 0, MAW_VOID_DUST);
                if ((i & 1) == 0) world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, MAW_RED_DUST);
            }
        }

        private void snap() {
            World world = at.getWorld();
            world.spawnParticle(Particle.DUST, at, 12, 0.28, 0.28, 0.28, 0, MAW_VOID_DUST);
            world.spawnParticle(Particle.DUST, at, 10, 0.24, 0.24, 0.24, 0, MAW_RED_DUST);
            world.spawnParticle(Particle.BLOCK, at, 6, 0.22, 0.22, 0.22, 0, BLOOD_BLOCK);
            world.playSound(at, Sound.ENTITY_RAVAGER_ATTACK, 0.8f, 0.5f);
            activeTasks.remove(this);
            cancel();
        }
    }

    /**
     * The grasping arm: a black-and-red root that forces itself out of the wielder toward the nearest body,
     * seizes it, and hauls it in. Re-reads the (living) wielder and target each tick so both may move. The one
     * grab bite goes through the framework pierce like every CENSORED hit; all geometry is tagged and reaped.
     */
    private final class GraspingArm extends BukkitRunnable {
        private final UUID ownerId;
        private final UUID targetId;   // acquired once, at cast — the nearest body then
        private final BlockDisplay hand;
        private int ticks = 0;
        private boolean grabbed = false;
        private int dragTicks = 0;

        GraspingArm(Player owner) {
            this.ownerId = owner.getUniqueId();
            LivingEntity t = nearestBody(owner);
            this.targetId = t != null ? t.getUniqueId() : null;
            Location from = handOf(owner);
            float s = ARM_HAND_SCALE;
            this.hand = from.getWorld().spawn(from, BlockDisplay.class, d -> {
                d.setBlock(JAW_BLOCK);
                d.setTransformation(new Transformation(
                        new Vector3f(-s / 2, -s / 2, -s / 2), new Quaternionf(),
                        new Vector3f(s, s, s), new Quaternionf()));
                d.setBrightness(new Display.Brightness(1, 4));
                d.setInterpolationDuration(1);
                d.setInterpolationDelay(0);
                d.setPersistent(false);
                d.addScoreboardTag(CENSORED_TAG);
            });
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isValid() || !matches(owner.getInventory().getItemInMainHand())) { reap(); return; }
            Location from = handOf(owner);
            LivingEntity target = targetId != null ? living(targetId) : null;
            if (target != null && target.getWorld() != owner.getWorld()) target = null; // fled the world — let go

            if (!grabbed) {
                double frac = Math.min(1.0, (double) ticks / ARM_GROW_TICKS);
                Location tip = growTip(owner, from, target, frac);
                if (hand.isValid()) hand.teleport(tip);
                root(from, tip);
                if (ticks++ < ARM_GROW_TICKS) return;

                // Reach complete: seize a still-valid, still-close body, else the limb gropes and withers.
                if (target != null && tip.distance(centreOf(target)) <= ARM_GRAB_DIST + 1.5) {
                    grabbed = true;
                    armReadyAt.put(ownerId, System.currentTimeMillis() + ARM_COOLDOWN_MS); // caught — pay the real CD
                    plugin.weapons().pierceDamage(target, ARM_GRAB_DAMAGE, PIERCE_FRACTION, owner);
                    starve(target);
                    breakVanish(owner); // seizing a body spends the Feast stealth
                    target.getWorld().playSound(centreOf(target), Sound.ENTITY_RAVAGER_ATTACK, 0.9f, 0.5f);
                } else {
                    wither(tip);
                    reap();
                }
                return;
            }

            // Drag: haul the seized body in until it is close, or the pull times out.
            if (target == null || dragTicks++ >= ARM_DRAG_TICKS) { reap(); return; }
            Location tc = centreOf(target);
            if (from.distance(tc) <= ARM_GRAB_DIST) { reap(); return; }
            Vector pull = from.toVector().subtract(tc.toVector()).normalize().multiply(ARM_DRAG_STRENGTH);
            target.setVelocity(target.getVelocity().multiply(0.4).add(pull));
            if (hand.isValid()) hand.teleport(tc);
            root(from, tc);
        }

        /** The tip's target this tick: the seized/aimed body's centre, or straight out the aim if none. */
        private Location growTip(Player owner, Location from, LivingEntity target, double frac) {
            Vector toward = target != null
                    ? centreOf(target).toVector().subtract(from.toVector())
                    : owner.getEyeLocation().getDirection().normalize().multiply(ARM_REACH);
            return from.clone().add(toward.multiply(frac));
        }

        /** The root drawn from the wielder to the tip, jittering as it forces its way out: black cored red. */
        private void root(Location from, Location tip) {
            World world = from.getWorld();
            Vector step = tip.toVector().subtract(from.toVector());
            double len = step.length();
            if (len < 1.0e-3) return;
            step.normalize();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (double t = 0.2; t <= len; t += ARM_SEG_STEP) {
                Location p = from.clone().add(step.clone().multiply(t)).add(
                        (rng.nextDouble() - 0.5) * 0.25, (rng.nextDouble() - 0.5) * 0.25, (rng.nextDouble() - 0.5) * 0.25);
                world.spawnParticle(Particle.DUST, p, 1, 0.03, 0.03, 0.03, 0, VOID_DUST);
                if (rng.nextBoolean()) world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, CENSOR_DUST);
            }
        }

        private void wither(Location tip) {
            World world = tip.getWorld();
            world.spawnParticle(Particle.DUST, tip, 12, 0.2, 0.2, 0.2, 0, VOID_DUST);
            world.playSound(tip, Sound.BLOCK_SCULK_SPREAD, 0.5f, 0.4f);
        }

        private void reap() {
            if (hand.isValid()) hand.remove();
            activeTasks.remove(this);
            cancel();
        }
    }

    /** A single flung bone: an ItemDisplay tumbling on a gravity arc, trailing blood, reaped on land/timeout. */
    private final class FlungBone extends BukkitRunnable {
        private final ItemDisplay bone;
        private final Vector vel;
        private int life = BONE_LIFE;
        private float angle = 0f;

        FlungBone(Location from, Vector vel) {
            this.vel = vel;
            float s = BONE_SCALE;
            this.bone = from.getWorld().spawn(from, ItemDisplay.class, d -> {
                d.setItemStack(BONE_ITEM);
                d.setTransformation(new Transformation(
                        new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
                d.setBrightness(new Display.Brightness(8, 12));
                d.setInterpolationDuration(1);
                d.setInterpolationDelay(0);
                d.setPersistent(false);
                d.addScoreboardTag(CENSORED_TAG);
            });
        }

        @Override
        public void run() {
            if (!bone.isValid() || --life < 0) { done(); return; }
            vel.setY(vel.getY() - BONE_GRAVITY);
            Location next = bone.getLocation().add(vel);
            if (next.getBlock().getType().isSolid()) { done(); return; }
            bone.teleport(next);
            angle += BONE_SPIN;
            float s = BONE_SCALE;
            bone.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf().rotateXYZ(angle, angle * 0.6f, angle * 0.3f),
                    new Vector3f(s, s, s), new Quaternionf()));
            next.getWorld().spawnParticle(Particle.DUST, next, 1, 0.03, 0.03, 0.03, 0, BLOOD_DUST);
        }

        private void done() {
            if (bone.isValid()) bone.remove();
            activeTasks.remove(this);
            cancel();
        }
    }

    /** Belt-and-braces: reap any display carrying the CENSORED tag across all loaded worlds. */
    private void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(BlockDisplay.class)) {
                if (e.getScoreboardTags().contains(CENSORED_TAG)) e.remove();
            }
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(CENSORED_TAG)) e.remove();
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        lastMaw.remove(id);
        grappleReadyAt.remove(id);
        charging.remove(id);
        corpseUntil.remove(id);
        corpseLoc.remove(id);
        feastReadyAt.remove(id);
        armReadyAt.remove(id);
        feastGear.remove(id); // logged off mid-feast; the invisibility potion lapses on its own timer
        lookingSince.remove(id);
    }

    @Override
    public void onDisable() {
        // Hand every mid-feast wielder their gear and visibility back before the tasks are torn down.
        for (UUID id : new ArrayList<>(feastGear.keySet())) unvanish(id);
        feastGear.clear();
        // Cancelling a flight task does not remove its display, so sweep every tagged display afterwards.
        for (BukkitRunnable t : new ArrayList<>(activeTasks)) t.cancel();
        activeTasks.clear();
        sweepOrphans();
        lastMaw.clear();
        grappleReadyAt.clear();
        charging.clear();
        corpseUntil.clear();
        corpseLoc.clear();
        feastReadyAt.clear();
        armReadyAt.clear();
        lookingSince.clear();
    }

    // ---- colours / particles ------------------------------------------------------

    /** Primary — the redaction's blood red. Display name, "How to use:", ability headers. */
    private static final TextColor CENSOR_RED = TextColor.color(0xCC0022);
    /** Secondary — the grey of a redaction bar. The Abnormality title line. */
    private static final TextColor REDACT     = TextColor.color(0x808080);

    private static final Color BLOOD_RGB   = Color.fromRGB(0x8A, 0x03, 0x03); // pouring blood
    private static final Color CENSOR_RGB  = Color.fromRGB(0xCC, 0x00, 0x22); // the red square / redaction
    private static final Color VOID_RGB    = Color.fromRGB(0x0E, 0x08, 0x12); // the maw's dark tendril / grapple wall
    private static final Particle.DustOptions BLOOD_DUST    = new Particle.DustOptions(BLOOD_RGB, 1.1f);
    private static final Particle.DustOptions CENSOR_DUST   = new Particle.DustOptions(CENSOR_RGB, 1.3f);
    private static final Particle.DustOptions VOID_DUST     = new Particle.DustOptions(VOID_RGB, 1.2f);
    /** The THICK red and black motes the pierce-maw is drawn in now (particles, not block jaws). */
    private static final Particle.DustOptions MAW_RED_DUST  = new Particle.DustOptions(CENSOR_RGB, MAW_DUST_SIZE);
    private static final Particle.DustOptions MAW_VOID_DUST = new Particle.DustOptions(VOID_RGB, MAW_VOID_SIZE);
    /** Blocks for crack particles: red for the crank bar-slam, near-black for the hand and the body box. */
    private static final BlockData BLOOD_BLOCK  = Material.REDSTONE_BLOCK.createBlockData();
    private static final BlockData SQUARE_BLOCK = Material.RED_CONCRETE.createBlockData();
    private static final BlockData JAW_BLOCK    = Material.BLACK_CONCRETE.createBlockData();
    private static final ItemStack BONE_ITEM = new ItemStack(Material.BONE);
    /** The blank the Feast sends to viewers for each of the wielder's equipment slots, hiding the gear. */
    private static final ItemStack AIR = new ItemStack(Material.AIR);

    // ---- lore ---------------------------------------------------------------------

    // Redacted style, per Nyrrine's words: the (CENSORED) redactions are literal and there are no em-dashes.
    // The weapon and the Abnormality share the name, and all four ability headers read CENSORED — you are told
    // how to trigger each power and roughly what it does, never what it is.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "CENSORED",
            "CENSORED",
            CENSOR_RED,
            REDACT,
            List.of(
                    "(CENSORED) has the ability to (CENSORED),",
                    "but this is a horrendous sight for those",
                    "watching. Looking at the E.G.O for more",
                    "than 3 seconds will make you sick."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] CENSORED",
                            "Your strikes (CENSORED) their armor and",
                            "leave them starving. Any who watch you",
                            "for more than 3 seconds are sickened."),
                    new EgoLore.Ability("[Left Click] CENSORED",
                            "The maw (CENSORED) outward and bites",
                            "twice. Now and then it acts on its",
                            "own, all at once."),
                    new EgoLore.Ability("[Shift + Right Click] CENSORED",
                            "(CENSORED), then tear along a line,",
                            "striking every foe caught in it three",
                            "times over."),
                    new EgoLore.Ability("[Right Click] CENSORED",
                            "Over a fresh kill, feed on the body and",
                            "(CENSORED) from sight while it feeds.",
                            "With no corpse near, an arm (CENSORED)",
                            "out and drags the nearest one to you.")
            ));
}
