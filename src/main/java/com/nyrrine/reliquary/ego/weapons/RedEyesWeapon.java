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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 * Red Eyes — "Spider Bud" (Lobotomy Corp E.G.O). A relentless hunter's blade whose dozens of eyes
 * never close, forever searching for prey.
 *
 * <p>A plain melee weapon: the vanilla IRON_SWORD swing lands its normal damage, uncancelled.
 *
 * <ul>
 *   <li><b>Passive speed</b> — while the blade is in the main hand the wielder is a touch quicker on
 *       the hunt: a small, keyed MOVEMENT_SPEED attribute modifier is kept applied while held and
 *       stripped the instant the blade leaves the hand (or on quit), so it can never leak or stack.</li>
 *   <li><b>Four-strike stun</b> ({@link #onHit}) — every fourth <em>properly spaced</em> landed strike
 *       roots the target for ~1s (a strong SLOWNESS) with a burst of red spider-eyes. Machine-gun
 *       spam-clicks (hits closer than {@link #HIT_MIN_SPACING_MS} apart) don't count toward the four.</li>
 *   <li><b>Penitence synergy</b> — while the wielder holds <b>Penitence</b> (the One Sin mace) in the
 *       OFF HAND, two things unlock: (1) a right-click JUMP + GROUND SLAM on a three-minute cooldown —
 *       the wielder leaps and, on landing, slams a spider-web AoE that stuns, lightly damages, and
 *       webs everything nearby; and (2) Penitence's on-hit passive is inherited — each melee hit has a
 *       flat 10% chance to restore a little food, plus a ramping heal chance (5%, +5% per miss) that
 *       mends ~2 HP on proc and resets.</li>
 * </ul>
 *
 * <p>All per-player state (speed modifier, strike counters, heal ramp, slam cooldown/landing) lives in
 * a handful of in-memory maps, cleared on quit. The ground slam damages through a re-entrancy fence so
 * its own AoE never recurses back into {@link #onHit}; it is a non-vanilla ability, so it wears the
 * weapon through {@link EgoDurability#wearMainHand(Player)}. Normal strikes wear via vanilla.
 */
public final class RedEyesWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Red Eyes. */
    private final NamespacedKey key;
    /** Penitence's PDC key — reconstructed identically to {@code PenitenceWeapon}'s so we can spot it off-hand. */
    private final NamespacedKey penitenceKey;
    /** The keyed MOVEMENT_SPEED modifier's identity — one per wielder-attribute, so it can't stack. */
    private final NamespacedKey speedModKey;

    // ---- per-player state ----------------------------------------------------------

    /** Wielder -> count of properly-spaced landed strikes toward the next stun. */
    private final Map<UUID, Integer> strikeCount = new HashMap<>();
    /** Wielder -> epoch-millis of their last COUNTED strike (spam-click gate). */
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();
    /** Wielder -> current ramping heal chance while Penitence is off-hand (reset to base on a heal). */
    private final Map<UUID, Double> healRamp = new HashMap<>();
    /** Wielder -> slam-cooldown expiry (epoch ms). Absent = ready / never slammed. */
    private final Map<UUID, Long> slamReadyAt = new HashMap<>();
    /** Wielder -> fall-damage grace expiry (epoch ms), set on their own leap so it can't drop them dead. */
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();
    /** Wielder -> epoch-millis of a pending slam leap; present means "awaiting landing" in {@link #onTick}. */
    private final Map<UUID, Long> slamLaunchAt = new HashMap<>();
    /** Wielders whose pending leap has actually left the ground (so a landing can resolve the slam). */
    private final Set<UUID> slamArmed = new HashSet<>();

    /** Re-entrancy fence: true while our own slam AoE is dealing damage, so {@link #onHit} ignores it. */
    private boolean inAoe = false;

    // ---- tuning --------------------------------------------------------------------

    private static final double SPEED_BONUS       = 0.12;  // +12% movement while held (ADD_SCALAR)

    private static final int    STRIKES_TO_STUN   = 4;     // every fourth spaced strike roots
    private static final long   HIT_MIN_SPACING_MS = 350L; // hits closer than this don't count (anti-spam)
    private static final int    STUN_TICKS        = 22;    // ~1.1s root
    private static final int    STUN_AMP          = 6;     // SLOWNESS VII — near-immobile

    private static final double SATURATION_CHANCE = 0.10;  // flat food-restore chance (Penitence off-hand)
    private static final double HEAL_BASE_CHANCE   = 0.05; // ramping heal starts here, resets here on a proc
    private static final double HEAL_RAMP_STEP     = 0.05; // +5% per non-healing strike
    private static final double HEAL_AMOUNT        = 2.0;  // ~1 heart mended on a proc

    private static final long   SLAM_COOLDOWN_MS   = 180_000L; // 3 minutes
    private static final long   FALL_GRACE_MS      = 4_000L;   // waived fall damage across the leap arc
    private static final long   SLAM_AIR_TIMEOUT_MS = 5_000L;  // safety: resolve the slam even if a landing is never seen
    private static final double LEAP_UP            = 0.95;     // the upward heave of the jump
    private static final double SLAM_RADIUS        = 4.5;      // AoE reach on landing
    private static final int    SLAM_MAX_TARGETS   = 10;       // cap the AoE scan for TPS
    private static final double SLAM_DAMAGE        = 3.0;      // small AoE damage (fenced against re-entry)

    public RedEyesWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "red_eyes");
        this.penitenceKey = new NamespacedKey(plugin, "penitence"); // must mirror PenitenceWeapon's key
        this.speedModKey = new NamespacedKey(plugin, "red_eyes_speed");
    }

    @Override
    public String id() {
        return "red_eyes";
    }

    // ---- passive (only while held) -------------------------------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Returns false — and strips the
     * speed modifier first — the moment the blade is not in the main hand, so the manager stops ticking.
     * While held it keeps the speed modifier applied, resolves a pending ground slam on landing, and
     * paints the slam-cooldown readout when Penitence is off-hand. The blade carries no ambient particle
     * VFX; feedback is reserved for on-hit, stun, and slam.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) {
            removeSpeed(player); // blade sheathed -> strip the passive, go idle
            return false;
        }
        applySpeed(player);

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Resolve a pending slam: leap -> leave the ground -> land -> AoE (with a safety timeout).
        Long launchAt = slamLaunchAt.get(id);
        if (launchAt != null) {
            boolean onGround = player.isOnGround();
            if (!onGround) slamArmed.add(id);
            boolean landed = slamArmed.contains(id) && onGround;
            if (landed || now - launchAt > SLAM_AIR_TIMEOUT_MS) {
                groundSlam(player);
                EgoDurability.wearMainHand(player); // non-vanilla ability wears the weapon
                slamLaunchAt.remove(id);
                slamArmed.remove(id);
            }
        }

        // Slam cooldown HUD — only meaningful while Penitence is off-hand (the ability's unlock).
        if (holdingPenitenceOffhand(player)) {
            Long ready = slamReadyAt.get(id);
            if (ready != null) {
                long remain = ready - now;
                if (remain <= 0) {
                    player.sendActionBar(EgoHud.ready("Serious Skullbuster", NAME));
                    slamReadyAt.remove(id);
                } else {
                    player.sendActionBar(EgoHud.cooldown("Serious Skullbuster", remain, NAME));
                }
            }
        }
        return true;
    }

    /** Ensure the keyed movement-speed modifier is present (idempotent — never stacks). */
    private void applySpeed(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (speedModKey.equals(m.getKey())) return; // already applied
        }
        inst.addModifier(new AttributeModifier(speedModKey, SPEED_BONUS, AttributeModifier.Operation.ADD_SCALAR));
    }

    /** Strip our keyed movement-speed modifier if present — safe to call when it isn't. */
    private void removeSpeed(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (speedModKey.equals(m.getKey())) inst.removeModifier(m);
        }
    }

    // ---- on-hit: four-strike stun + Penitence passive ------------------------------

    /**
     * A melee hit landed. Vanilla damage is left untouched. We sprout the spider-eye mark, count the
     * blow toward the four-strike stun (spam-clicks don't count), and — while Penitence is off-hand —
     * run the inherited food/heal passive. Slam AoE damage is fenced out via {@link #inAoe}.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (inAoe) return; // our own slam AoE — must not re-enter the hit dispatch

        UUID id = attacker.getUniqueId();
        World world = attacker.getWorld();
        long now = System.currentTimeMillis();

        spiderEyeMark(victim);
        skitter(world, attacker.getLocation());

        // Four-strike stun with spam-click prevention: only spaced hits advance the counter.
        Long prev = lastCountedHit.get(id);
        if (prev == null || now - prev >= HIT_MIN_SPACING_MS) {
            lastCountedHit.put(id, now);
            int c = strikeCount.merge(id, 1, Integer::sum);
            if (c >= STRIKES_TO_STUN) {
                strikeCount.put(id, 0);
                stun(victim);
            }
        }

        // Penitence off-hand: inherit its on-hit passive (flat food chance + ramping heal).
        if (holdingPenitenceOffhand(attacker)) {
            penitencePassive(attacker);
        }
    }

    /** Root a target for ~1s with a strong SLOWNESS and a burst of red spider-eyes. */
    private void stun(LivingEntity victim) {
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, STUN_TICKS, STUN_AMP, false, true, true));
        World world = victim.getWorld();
        world.spawnParticle(Particle.DUST, victim.getEyeLocation().add(0, 0.2, 0), 16, 0.3, 0.3, 0.3, 0, EYE_DUST);
        world.playSound(victim.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.8f, 0.7f);
        spiderEyeMark(victim);
    }

    /** Penitence's inherited on-hit passive: a flat food-restore chance plus a ramping heal chance. */
    private void penitencePassive(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        UUID id = player.getUniqueId();

        // (i) 10% flat chance to restore a little food/saturation.
        if (rng.nextDouble() < SATURATION_CHANCE) {
            int food = Math.min(20, player.getFoodLevel() + 1);
            player.setFoodLevel(food);
            player.setSaturation(Math.min(food, player.getSaturation() + 2.0f));
        }

        // (ii) Ramping heal: 5% to start, +5% per non-healing strike, ~2 HP on proc then reset to 5%.
        double chance = healRamp.getOrDefault(id, HEAL_BASE_CHANCE);
        if (rng.nextDouble() < chance) {
            AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
            double healed = Math.min(maxHp, player.getHealth() + HEAL_AMOUNT);
            if (healed > player.getHealth()) {
                player.setHealth(healed);
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.5f, 1.1f);
            }
            healRamp.put(id, HEAL_BASE_CHANCE); // reset the ramp on a proc
        } else {
            healRamp.put(id, Math.min(1.0, chance + HEAL_RAMP_STEP));
        }
    }

    /** A brief little cluster of red "eyes" sprouting at the victim's head — the Spider Bud's mark. */
    private void spiderEyeMark(LivingEntity victim) {
        World world = victim.getWorld();
        Location head = victim.getEyeLocation().add(0, 0.15, 0);

        Vector right = victim.getLocation().getDirection().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = new Vector(0, 1, 0);

        double[][] eyes = {
            {-0.20, 0.05}, {0.20, 0.05},
            {-0.12, 0.20}, {0.12, 0.20},
            {0.00, 0.32},
        };
        for (double[] e : eyes) {
            Location p = head.clone()
                    .add(right.clone().multiply(e[0]))
                    .add(up.clone().multiply(e[1]));
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, EYE_DUST);
        }
    }

    /** The signature soft, fast skittering legs on a hit — pitch-jittered so repeated blows don't drone. */
    private void skitter(World world, Location at) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(at, Sound.ENTITY_SPIDER_STEP, 0.4f, 1.5f + rng.nextFloat() * 0.4f);
        world.playSound(at, Sound.ENTITY_SPIDER_STEP, 0.3f, 1.7f + rng.nextFloat() * 0.4f);
    }

    // ---- Penitence synergy: jump + ground slam -------------------------------------

    /** True if the off-hand item is Penitence — a MACE carrying Penitence's PDC key. */
    private boolean holdingPenitenceOffhand(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() != Material.MACE) return false;
        ItemMeta m = off.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(penitenceKey, PersistentDataType.BYTE);
    }

    /**
     * Right-click: only with Penitence off-hand, LEAP up and — on landing, resolved in {@link #onTick} —
     * SLAM a spider-web AoE. Three-minute cooldown (shown through {@link EgoHud}, never raw ms), a brief
     * fall-damage grace so the leap can't drop the wielder to their death.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!holdingPenitenceOffhand(player)) {
            player.sendActionBar(EgoHud.status("Serious Skullbuster — needs Penitence off-hand", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0.6f);
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (slamLaunchAt.containsKey(id)) return; // already mid-leap

        Long ready = slamReadyAt.get(id);
        if (ready != null && now < ready) {
            player.sendActionBar(EgoHud.cooldown("Serious Skullbuster", ready - now, NAME));
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0.6f);
            return;
        }

        slamReadyAt.put(id, now + SLAM_COOLDOWN_MS);
        fallGraceUntil.put(id, now + FALL_GRACE_MS);
        slamLaunchAt.put(id, now); // pending -> resolves on landing
        slamArmed.remove(id);
        launchLeap(player);
        player.sendActionBar(EgoHud.cooldown("Serious Skullbuster", SLAM_COOLDOWN_MS, NAME));
    }

    /** The upward heave of the jump, wrapped in a spider-ambient shriek and a puff of red eyes. */
    private void launchLeap(Player player) {
        player.setVelocity(player.getVelocity().setY(LEAP_UP));
        player.setFallDistance(0f);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.8f, 1.2f);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 12, 0.3, 0.4, 0.3, 0, EYE_DUST);
    }

    /**
     * The landing SLAM: a spider-web AoE that STUNS + lightly damages everything nearby, wrapped in red
     * web rings and a burst of eyes. Damage runs inside the {@link #inAoe} fence so it never recurses
     * back into {@link #onHit}. Knockback comes free with the vanilla damage.
     */
    private void groundSlam(Player player) {
        World world = player.getWorld();
        Location impact = player.getLocation();
        player.setFallDistance(0f);

        webBurst(impact);
        world.playSound(impact, Sound.ENTITY_SPIDER_DEATH, 0.9f, 0.7f);
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.3f);

        inAoe = true;
        try {
            int hit = 0;
            for (Entity e : player.getNearbyEntities(SLAM_RADIUS, SLAM_RADIUS, SLAM_RADIUS)) {
                if (hit >= SLAM_MAX_TARGETS) break;
                if (e == player || !(e instanceof LivingEntity other)) continue;
                stun(other);
                other.damage(SLAM_DAMAGE, player); // fenced: onHit ignores this
                hit++;
            }
        } finally {
            inAoe = false;
        }
    }

    /** A double red web-ring plus a dome of eye motes at the slam's centre. */
    private void webBurst(Location center) {
        World world = center.getWorld();
        ring(world, center.clone().add(0, 0.15, 0), 1.6, 16, AURA_DUST);
        ring(world, center.clone().add(0, 0.15, 0), 2.6, 22, AURA_DUST);
        world.spawnParticle(Particle.DUST, center.clone().add(0, 1.0, 0), 24, 0.6, 0.5, 0.6, 0, EYE_DUST);
    }

    /** A flat ring of dust of {@code count} points around a centre. */
    private void ring(World world, Location center, double radius, int points, Particle.DustOptions dust) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
    }

    /** Waive the wielder's fall damage across the brief grace window after their own leap. */
    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long t = fallGraceUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.RED_EYES.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.RED_EYES.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.RED_EYES);

        item.setItemMeta(meta);
        return item;
    }

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        strikeCount.remove(id);
        lastCountedHit.remove(id);
        healRamp.remove(id);
        slamReadyAt.remove(id);
        fallGraceUntil.remove(id);
        slamLaunchAt.remove(id);
        slamArmed.remove(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) removeSpeed(p); // don't leave the keyed speed modifier behind
    }

    @Override
    public void onDisable() {
        // On a plugin reload the tick loop stops, so an online holder would keep the +12% speed buff —
        // strip the keyed modifier from everyone online so it can never persist across the reload.
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            removeSpeed(p);
        }
    }

    // ---- palette & lore ------------------------------------------------------------

    /** Primary — deep blood-red. Display name, "How to use:", ability headers, and the action bar. */
    private static final TextColor NAME  = TextColor.color(0xB01414);
    /** Secondary — the darker blood beneath it. The Abnormality title line. */
    private static final TextColor VEIN  = TextColor.color(0x8A0303);
    /** Conditions / controls — the action-bar hint when the off-hand is wrong. */
    private static final TextColor FAINT = TextColor.color(0x7A5A5A);

    private static final Color EYE_RED   = Color.fromRGB(0xC8, 0x10, 0x10); // the eyes / mark
    private static final Color AURA_RED  = Color.fromRGB(0x9B, 0x10, 0x10); // the aura / web
    private static final Particle.DustOptions EYE_DUST  = new Particle.DustOptions(EYE_RED, 0.7f);
    private static final Particle.DustOptions AURA_DUST = new Particle.DustOptions(AURA_RED, 0.8f);

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Red Eyes",
            "Spider Bud",
            NAME,
            VEIN,
            List.of(
                    "The Spider Bud's dozens of eyes never",
                    "close, forever hunting in the dark for",
                    "prey to feed its hungry spiderlings."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Movement Speed",
                            "While held, you move 12% faster."),
                    new EgoLore.Ability("[Left Click] Four-Strike Root",
                            "Every 4th strike roots the target for",
                            "about a second. Hits landed less than",
                            "0.35s apart don't count toward it."),
                    new EgoLore.Ability("[Passive] Inherited Penitence Passive",
                            "With Penitence in the off-hand, each",
                            "hit has a 10% chance to restore food.",
                            "A separate 5% chance mends 2 HP, and",
                            "climbs 5% per hit until it lands."),
                    new EgoLore.Ability("[Right Click] Serious Skullbuster",
                            "Needs Penitence in the off-hand. Leap,",
                            "then slam on landing: up to 10 targets",
                            "within 4.5 blocks are rooted and take",
                            "3 damage. Your fall damage is waived.",
                            "3 minute cooldown.")
            ));
}
