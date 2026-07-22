package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import com.nyrrine.reliquary.ego.SlashVfx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Twilight — "Apocalypse Bird" (Lobotomy Corp Abnormality). The flagship greatsword: the ever-watching
 * eyes, the scale that weighs all sin, and the beak that swallows everything, forged into one blade that
 * brings peace the way the Bird did — by ending everything that broke it.
 *
 * <p>A SPECIAL relic (Cogito-unobtainable), and the showpiece of the roster. It carries three passives and
 * four abilities, and every blow it deals is one <b>ruin</b> — physical, magic and hunger at once, tearing
 * through most armour. Its own damage is routed through {@link #dealRuin}, which fences the blow (so it is
 * never handed back to the melee dispatch), clears i-frames (so a four-hit combo all lands), pierces armour
 * via the framework's {@code pierceInput}, and weighs the target's sin onto the wielder's scale.
 *
 * <p><b>Balance is placeholder.</b> Every magnitude below is a first pass, deliberately grand; the numbers
 * want Nyrrine's balance wave to sit "present but not OP" against prot-netherite. They are gathered at the
 * top so she can retune from one place.
 */
public final class TwilightWeapon implements Weapon, Listener {

    private final Reliquary plugin;
    private final NamespacedKey key;
    /** MAX_HEALTH modifier key for Third Trumpet's bonus health. */
    private final NamespacedKey hpKey;

    // ---- balance (ALL PLACEHOLDER — flag for Nyrrine's balance wave) ----------------
    /** Third Trumpet: bonus max health while held (ten hearts). */
    private static final double BONUS_MAX_HP   = 20.0;
    /** Third Trumpet: the whole bonus pool is restored on this cadence. 66.6s, the Bird's number. */
    private static final long   REGEN_CYCLE_MS = 66_600L;
    /** Fraction of armour every ruin ignores — "most armour + resistances". */
    private static final double RUIN_PIERCE    = 0.60;
    /** The magic ruin rides on top of the physical one, ignoring armour entirely. */
    private static final double RUIN_MAGIC      = 2.0;

    /** Tilted Scale: hits to a full scale, and the stack each ruin adds. */
    private static final int    SIN_MAX        = 5;

    /** Attack Sequence: a click-driven four-hit combo — each swing lands the next ruin, the chain escalating. */
    private static final double[] COMBO_DAMAGE = {6.0, 6.0, 7.0, 9.0};
    private static final double COMBO_REACH    = 4.2;
    private static final double COMBO_CONE     = 0.35; // dot threshold for "in front"
    private static final long   COMBO_WINDOW_MS = 900L; // time after a strike to continue the chain
    private static final long   COMBO_STEP_LOCK = 5L;   // ticks rooted after a mid-combo strike (one click = one strike)
    private static final long   COMBO_RECOVER   = 10L;  // ticks rooted after the finale — the greatsword's weight

    private static final long   PEACE_CD_MS    = 14_000L;
    private static final double PEACE_RADIUS   = 6.5;
    private static final double PEACE_DAMAGE   = 14.0;
    private static final double PEACE_LEAP_UP    = 1.05; // the leap's upward impulse — the Bird takes to the air
    private static final double PEACE_LEAP_FWD   = 0.22; // a touch of forward travel on the leap
    private static final double PEACE_DIVE_SPEED = 0.90; // how hard it bites downward once descending
    private static final int    PEACE_WATCH_TICKS = 45;  // safety cap on the arc — never hang mid-air
    private static final double PEACE_FALL_BONUS_PER_BLOCK = 0.5; // the slam hits harder the further it falls
    private static final double PEACE_FALL_BONUS_CAP        = 6.0;

    private static final long   EYES_CD_MS     = 16_000L;
    private static final int    EYES_COUNT     = 10;
    private static final double EYES_DAMAGE    = 4.0;
    private static final double EYES_RANGE     = 20.0;
    private static final int    EYE_STAGGER    = 5;    // ticks between each eye waking — they open one by one
    private static final int    EYE_OPEN_TICKS = 9;    // the golden lid blooming open before it departs the body
    private static final int    EYE_LIFETIME   = 100;  // max ticks an eye lives once open
    private static final double EYE_SPEED      = 0.42; // blocks/tick — a slow, deliberate drift (was ~1.1)
    private static final double EYE_TURN_DEG   = 10.0; // max steer per tick — a smooth curving homing arc

    private static final long   PUNISH_CD_MS   = 12_000L;
    private static final double PUNISH_POWER   = 2.6;   // dash impulse — a longer lunge (was 1.7)
    private static final double PUNISH_DAMAGE  = 8.0;
    private static final double PUNISH_REACH   = 3.6;   // wider strike sweep to match the longer reach
    private static final int    PUNISH_TICKS   = 12;    // watched longer so the extended lunge still catches a wall

    // ---- palette ------------------------------------------------------------------
    private static final TextColor C_GOLD  = TextColor.color(0xFFD54A);
    private static final TextColor C_WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor C_AMBER = TextColor.color(0xE8A23C);
    private static final TextColor C_FAINT = TextColor.color(0x9A8F73);
    private static final Color GOLD_DUST = Color.fromRGB(0xFF, 0xD5, 0x4A);
    private static final Color WHITE_DUST = Color.fromRGB(0xFF, 0xF4, 0xD8);
    private static final Color AMBER_DUST = Color.fromRGB(0xE8, 0xA2, 0x3C);
    private static final Color VOID_DUST = Color.fromRGB(0x6A, 0x4C, 0xC0);
    /** Brilliant Eyes render as pure gold — a bright golden body, a warm amber iris, a faint golden wake. */
    private static final Particle.DustOptions EYE_GOLD       = new Particle.DustOptions(GOLD_DUST, 1.2f);
    private static final Particle.DustOptions EYE_IRIS       = new Particle.DustOptions(AMBER_DUST, 0.9f);
    private static final Particle.DustOptions EYE_GOLD_FAINT = new Particle.DustOptions(Color.fromRGB(0xEA, 0xC2, 0x54), 0.8f);
    /** Tag on every Display this weapon spawns, so a reload can sweep any that outlived their task. */
    private static final String VFX_TAG = "twilight_vfx";

    // ---- per-player state (UUID-keyed, cleared on quit/disable) ----------------------
    private final Map<UUID, Integer> sin          = new HashMap<>();
    private final Map<UUID, Long> peaceReadyAt     = new HashMap<>();
    private final Map<UUID, Long> eyesReadyAt      = new HashMap<>();
    private final Map<UUID, Long> punishReadyAt    = new HashMap<>();
    private final Map<UUID, Long> nextRegenAt      = new HashMap<>();
    private final Map<UUID, Long> busyUntil        = new HashMap<>(); // rooted mid-combo / wind-up
    private final Map<UUID, Long> fallGraceUntil   = new HashMap<>(); // no fall damage while lunging
    private final Map<UUID, Integer> comboStep     = new HashMap<>(); // click-driven combo index
    private final Map<UUID, Long> comboWindowUntil = new HashMap<>(); // the chain lapses if you do not continue in time

    public TwilightWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "twilight");
        this.hpKey = new NamespacedKey(plugin, "twilight_bonus_hp");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---- Weapon interface ----------------------------------------------------------

    @Override public String id() { return "twilight"; }

    @Override
    public void onSwing(Player player) {
        if (busy(player.getUniqueId())) return;
        if (player.isSneaking()) punishment(player);
        else                     attackSequence(player);
    }

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (busy(player.getUniqueId())) return;
        if (sneaking) brilliantEyes(player);
        else          peaceForAll(player);
    }

    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) {
            clearBonusHp(player);
            return false;
        }
        UUID id = player.getUniqueId();
        applyBonusHp(player);
        thirdTrumpetCycle(player, id);
        if (tick % 2 == 0) auraVfx(player); // a slow golden halo around the wielder
        player.sendActionBar(hud(id));
        return true;
    }

    @Override
    public void onQuit(UUID id) {
        sin.remove(id);
        peaceReadyAt.remove(id);
        eyesReadyAt.remove(id);
        punishReadyAt.remove(id);
        nextRegenAt.remove(id);
        busyUntil.remove(id);
        fallGraceUntil.remove(id);
        comboStep.remove(id);
        comboWindowUntil.remove(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) clearBonusHp(p); // best-effort on quit; onJoin is the real backstop for a dirty exit
    }

    @Override
    public void onJoin(Player player) {
        // An unclean server shutdown can save the bonus-HP modifier to a player's NBT. If they log in
        // WITHOUT Twilight in hand, that modifier is unjustified — strip it so it can never harden into a
        // permanent +10 hearts. If they are holding it, onTick keeps the live one.
        if (!matches(player.getInventory().getItemInMainHand())) clearBonusHp(player);
    }

    @Override
    public void onDisable() {
        // Lift the live max-health modifiers off the online wielders...
        for (Player p : plugin.getServer().getOnlinePlayers()) clearBonusHp(p);
        // ...and sweep any VFX Display (eye body / shockwave block) that outlived its task on a crash/reload.
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(Display.class)) {
                if (e.getScoreboardTags().contains(VFX_TAG)) e.remove();
            }
        }
    }

    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long t = fallGraceUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    // ---- passive: John Lobotomy + Tilted Scale, folded into every ruin --------------

    /**
     * Deal one ruin to a body: the physical blow (armour-pierced by {@link #RUIN_PIERCE}), the magic blow on
     * top of it (armour ignored entirely), and — on a player — a bite of hunger. Every ruin fences itself
     * through {@code dealing} and clears i-frames, so a whole combo or a shockwave lands in full; and every
     * ruin weighs one more sin onto the wielder's scale.
     */
    private void dealRuin(Player src, LivingEntity victim, double physical) {
        if (victim.equals(src) || victim.isDead() || !victim.isValid()) return;
        UUID id = src.getUniqueId();
        Vector before = victim.getVelocity();
        plugin.weapons().dealing(id, () -> {
            victim.setNoDamageTicks(0);
            victim.damage(plugin.weapons().pierceInput(victim, physical, RUIN_PIERCE), src); // physical ruin
            victim.setNoDamageTicks(0);
            victim.damage(plugin.weapons().pierceInput(victim, RUIN_MAGIC, 1.0), src);       // magic ruin
        });
        victim.setVelocity(before); // ruin lands as a wound, not a shove — abilities add their own knockback
        if (victim instanceof Player p) {
            p.setFoodLevel(Math.max(0, p.getFoodLevel() - 1)); // hunger — the beak that swallows everything
        }
        weighSin(id); // Tilted Scale
    }

    /** Tilted Scale: one more sin on the wielder's scale, capped at a full weight. */
    private void weighSin(UUID id) {
        sin.merge(id, 1, (a, b) -> Math.min(SIN_MAX, a + b));
    }

    private boolean sinFull(UUID id) { return sin.getOrDefault(id, 0) >= SIN_MAX; }

    private void spendSin(UUID id) { sin.put(id, 0); }

    // ---- passive: Third Trumpet — great health, no natural regen, a full pour every 66.6s ----

    /** Apply the bonus-health modifier if it is not already borne (remove-before-add safe). */
    private void applyBonusHp(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (hpKey.equals(m.getKey())) return; // already carried
        }
        inst.addModifier(new AttributeModifier(hpKey, BONUS_MAX_HP, AttributeModifier.Operation.ADD_NUMBER));
        nextRegenAt.putIfAbsent(player.getUniqueId(), System.currentTimeMillis() + REGEN_CYCLE_MS);
    }

    /** Strip the bonus-health modifier from a live player, if present. */
    private void clearBonusHp(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        inst.getModifiers().stream().filter(m -> hpKey.equals(m.getKey())).toList()
                .forEach(inst::removeModifier);
    }

    /** Every {@link #REGEN_CYCLE_MS}, pour the whole pool back — the trumpet sounds and the wielder is whole. */
    private void thirdTrumpetCycle(Player player, UUID id) {
        long now = System.currentTimeMillis();
        Long at = nextRegenAt.get(id);
        if (at == null) { nextRegenAt.put(id, now + REGEN_CYCLE_MS); return; }
        if (now < at) return;
        nextRegenAt.put(id, now + REGEN_CYCLE_MS);
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max != null ? max.getValue() : 20.0;
        player.setHealth(maxHp); // the whole pool pours back — the only healing the trumpet allows
        trumpetVfx(player);
    }

    /** Natural food regen is barred while Twilight is held — the third trumpet took it. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getRegainReason() != RegainReason.SATIATED) return; // only natural (food) passive regen
        if (!(event.getEntity() instanceof Player p)) return;
        if (matches(p.getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    /** A raw sword swing does nothing — Twilight speaks only through its combo and abilities. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRawMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (plugin.weapons().isDealing(p.getUniqueId())) return; // our own fenced ruin — let it through
        EntityDamageEvent.DamageCause c = event.getCause();
        if (c != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && c != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (matches(p.getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    // ---- ability: [Left Click] Attack Sequence — four ruins, one after another ------

    private void attackSequence(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        // Click-driven: each swing lands ONE strike and arms the next. The chain only advances if you keep
        // swinging inside the window — so the wielder sets the cadence instead of being locked into an auto-combo.
        int step = now <= comboWindowUntil.getOrDefault(id, 0L) ? comboStep.getOrDefault(id, 0) : 0;
        if (step >= COMBO_DAMAGE.length) step = 0; // wrap after a finished combo
        comboStrike(player, step, COMBO_DAMAGE[step]);
        int next = step + 1;
        if (next >= COMBO_DAMAGE.length) {
            comboStep.put(id, 0);
            comboWindowUntil.put(id, 0L);
            setBusy(id, COMBO_RECOVER); // the finale lands with weight — a short recovery before the next chain
        } else {
            comboStep.put(id, next);
            comboWindowUntil.put(id, now + COMBO_WINDOW_MS);
            setBusy(id, COMBO_STEP_LOCK); // one click = one strike; the guard stops a single swing double-firing
        }
    }

    /** One strike of the sequence: a wide cone in front takes a ruin, with a slash whose fidelity climbs. */
    private void comboStrike(Player player, int index, double damage) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        // Each ruin swings on its own plane and colour, building strike to strike — four distinct crescents.
        crescent(player, index);
        // A clean rising whoosh per swing (pitch climbs across the chain), with a touch of greatsword weight.
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.85f, 0.7f + index * 0.14f);
        world.playSound(eye, Sound.ITEM_MACE_SMASH_AIR, 0.5f, 1.0f + index * 0.12f);
        for (Entity e : player.getNearbyEntities(COMBO_REACH, COMBO_REACH, COMBO_REACH)) {
            if (!(e instanceof LivingEntity le) || e.equals(player)) continue;
            Vector to = le.getLocation().toVector().subtract(eye.toVector());
            if (to.lengthSquared() > 1e-6 && look.dot(to.normalize()) < COMBO_CONE) continue; // behind — skip
            dealRuin(player, le, damage);
        }
        // The final strike lands heavier — a bright flare and a deeper toll.
        if (index == COMBO_DAMAGE.length - 1) {
            world.playSound(eye, Sound.BLOCK_BELL_RESONATE, 0.6f, 1.4f);
            world.spawnParticle(Particle.FLASH, eye.add(look.multiply(1.5)), 1, 0, 0, 0, 0, WHITE_DUST);
        }
    }

    // ---- ability: [Right Click] Peace For All — leap up, then a ground-slam on the descent ------------

    private void peaceForAll(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < peaceReadyAt.getOrDefault(id, 0L)) {
            player.sendActionBar(hud(id));
            return;
        }
        boolean empowered = sinFull(id);
        peaceReadyAt.put(id, now + PEACE_CD_MS);
        setBusy(id, PEACE_WATCH_TICKS + 6);   // no other ability through the whole arc
        fallGraceUntil.put(id, now + 4_000L); // no fall damage through the leap + descent
        World world = player.getWorld();
        final double startY = player.getY();
        // The Bird takes to the air: leap up (a touch forward), then bring peace down on the way back.
        Vector look = player.getEyeLocation().getDirection().setY(0);
        Vector leap = (look.lengthSquared() < 1.0e-6 ? new Vector(0, 0, 0) : look.normalize().multiply(PEACE_LEAP_FWD))
                .setY(PEACE_LEAP_UP);
        player.setVelocity(leap);
        player.setFallDistance(0f);
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.6f);
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 0.8f, 0.7f);

        new BukkitRunnable() {
            int t = 0;
            boolean descending = false;
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline()) { cancel(); return; }
                windupVfx(p, Math.min(1.0, t / 12.0)); // the blade gathers light overhead through the arc
                if (t > 3 && p.getVelocity().getY() < 0) descending = true;
                // Land the slam on real ground contact past the apex (or if the arc times out) — never mid-air.
                if ((descending && p.isOnGround() && t > 4) || t >= PEACE_WATCH_TICKS) {
                    peaceSlam(p, empowered, startY);
                    cancel();
                    return;
                }
                // Once past the apex, bite hard downward so the descent reads as a dive, not a float.
                if (descending) {
                    Vector v = p.getVelocity();
                    if (v.getY() > -PEACE_DIVE_SPEED) { v.setY(-PEACE_DIVE_SPEED); p.setVelocity(v); p.setFallDistance(0f); }
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The slam itself: a shockwave outward, heavy ruin all around (scaled by the drop), and — at full sin — a crippling launch. */
    private void peaceSlam(Player player, boolean empowered, double startY) {
        UUID id = player.getUniqueId();
        World world = player.getWorld();
        Location c = player.getLocation();
        double fall = Math.max(0.0, startY - c.getY());
        double damage = PEACE_DAMAGE + Math.min(fall * PEACE_FALL_BONUS_PER_BLOCK, PEACE_FALL_BONUS_CAP);
        world.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.7f);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.6f);
        world.playSound(c, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.0f, 0.5f);
        shockwaveVfx(c, empowered);
        player.setVelocity(new Vector(0, 0.14, 0)); // a small rebound reads as the shockwave throwing the wielder up
        for (Entity e : player.getNearbyEntities(PEACE_RADIUS, PEACE_RADIUS, PEACE_RADIUS)) {
            if (!(e instanceof LivingEntity le) || e.equals(player)) continue;
            dealRuin(player, le, damage);
            if (empowered) {
                le.setVelocity(le.getLocation().toVector().subtract(c.toVector()).setY(0.9)
                        .normalize().multiply(1.1).setY(0.9)); // catch and launch
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 3, false, true, true));
                le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 1, false, true, true));
            }
        }
        if (empowered) {
            spendSin(id);
            world.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 0.9f, 0.8f); // the scale tips — a heavy judgment clap
        }
    }

    // ---- ability: [Shift + Right] Brilliant Eyes — ten (or twenty) homing eyes -------

    private void brilliantEyes(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < eyesReadyAt.getOrDefault(id, 0L)) {
            player.sendActionBar(hud(id));
            return;
        }
        boolean twice = sinFull(id);
        eyesReadyAt.put(id, now + EYES_CD_MS);
        int count = twice ? EYES_COUNT * 2 : EYES_COUNT;
        int stagger = twice ? Math.max(2, EYE_STAGGER / 2) : EYE_STAGGER; // twice as many — wake them a touch faster
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.5f);
        world.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.3f);

        List<LivingEntity> targets = nearbyEnemies(player, EYES_RANGE);
        Vector look = player.getEyeLocation().getDirection();
        for (int i = 0; i < count; i++) {
            LivingEntity target = targets.isEmpty() ? null : targets.get(i % targets.size());
            // Each eye wakes at a different spot around the wielder's body and leaves on its own fanned heading,
            // so the swarm curves outward before it homes — never a straight line.
            Location origin = eyeSpawn(player, i, count);
            Vector launch = eyeLaunch(look, i);
            new BrilliantEye(id, origin, launch, target, i).runTaskTimer(plugin, (long) i * stagger, 1L);
        }
        if (twice) spendSin(id);
    }

    /** A spawn point in a ring AROUND the wielder — well clear of the body, spread across shoulder-to-head height. */
    private Location eyeSpawn(Player player, int i, int count) {
        double a = (Math.PI * 2 * i) / Math.max(1, count) + (i % 2) * 0.6;
        double r = 1.3 + (i % 3) * 0.22; // 1.3-1.7 blocks out — the eyes open around them, never inside them
        double y = 0.9 + (i % 4) * 0.28; // staggered heights up the body
        return player.getLocation().add(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    /** The heading an eye leaves on: fanned out to the side and lifted, so steering has to curve it back in. */
    private Vector eyeLaunch(Vector look, int i) {
        double side = (i % 2 == 0) ? 1 : -1;
        double yaw = Math.toRadians(side * (28 + (i / 2 % 4) * 16)); // a widening left/right fan
        Vector v = look.clone().rotateAroundY(yaw);
        v.setY(v.getY() * 0.3 + 0.55); // lift it off the body first
        return v.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : v.normalize();
    }

    // ---- ability: [Shift + Left] Punishment — a dash-strike, wall → launch up --------

    private void punishment(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < punishReadyAt.getOrDefault(id, 0L)) {
            player.sendActionBar(hud(id));
            return;
        }
        punishReadyAt.put(id, now + PUNISH_CD_MS);
        fallGraceUntil.put(id, now + 3_000L);
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().setY(0.08).normalize();
        player.setVelocity(dir.clone().multiply(PUNISH_POWER));
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.9f, 1.3f);
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.8f);

        new BukkitRunnable() {
            int t = 0;
            boolean launched = false;
            final java.util.Set<UUID> struck = new java.util.HashSet<>(); // each body takes ONE ruin per dash, not one per tick
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline() || t >= PUNISH_TICKS || launched) { cancel(); return; }
                lungeVfx(p, dir);
                // Strike anything the lunge passes through — once each, or the swept i-frame clear stacks ruin per tick.
                for (Entity e : p.getNearbyEntities(PUNISH_REACH, PUNISH_REACH, PUNISH_REACH)) {
                    if (!(e instanceof LivingEntity le) || e.equals(p)) continue;
                    if (!struck.add(le.getUniqueId())) continue; // already caught by this lunge
                    dealRuin(p, le, PUNISH_DAMAGE);
                    le.setVelocity(dir.clone().multiply(1.2).setY(0.45)); // launched back
                }
                // Wall ahead? The lunge's force has nowhere to go but up.
                Location ahead = p.getLocation().add(dir.clone().multiply(1.0));
                if (ahead.getBlock().getType().isSolid()
                        && p.getEyeLocation().add(dir.clone().multiply(0.6)).getBlock().getType().isSolid()) {
                    wallLaunch(p);
                    launched = true;
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The wall-launch: flung upward, a high muffled roar, and light streaming DOWNWARD as it rises. */
    private void wallLaunch(Player player) {
        UUID id = player.getUniqueId();
        fallGraceUntil.put(id, System.currentTimeMillis() + 4_000L);
        player.setVelocity(new Vector(player.getVelocity().getX() * 0.2, 1.35, player.getVelocity().getZ() * 0.2));
        World world = player.getWorld();
        Location at = player.getLocation();
        // NOT a deep growl — a high roar, muffled: pitched up and held quiet, layered for body.
        world.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.9f);
        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.6f);
        world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 2.0f);
        // Particles going DOWN — streamers falling from the rising wielder.
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline() || t++ > 14) { cancel(); return; }
                // A wide golden curtain overhead, streaming DOWN past the wielder as they rise.
                Location o = p.getLocation().add(0, 2.4, 0);
                for (int i = 0; i < 24; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                    double r = 0.9 + ThreadLocalRandom.current().nextDouble(0, 1.0);
                    double h = ThreadLocalRandom.current().nextDouble(-0.6, 2.2);
                    Location s = o.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
                    p.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, s, 1, 0.02, 0, 0.02, 0,
                            new Particle.DustTransition(GOLD_DUST, i % 3 == 0 ? VOID_DUST : WHITE_DUST, 1.3f));
                    p.getWorld().spawnParticle(Particle.END_ROD, s, 1, 0, -0.35, 0, 0.22); // streaming steeply down
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- Brilliant Eye: a golden eye drawn purely in particles, curving into its foe -------

    /**
     * A single Brilliant Eye, drawn in golden particles only — no entity. It opens like an eye at the wielder's
     * body, lingers a moment as the golden lid blooms, then departs on a smooth curving arc that steers into its
     * target (League's Ruined King curve): each tick the heading turns toward the foe by a capped angle, so a
     * fanned-out launch bows back into the hit rather than snapping straight there.
     */
    private final class BrilliantEye extends BukkitRunnable {
        private final UUID ownerId;
        private final Location pos;
        private final LivingEntity target;
        private Vector vel;   // unit heading; the speed is applied via EYE_SPEED
        private int age = 0;

        BrilliantEye(UUID ownerId, Location origin, Vector launch, LivingEntity target, int index) {
            this.ownerId = ownerId;
            this.pos = origin;
            this.vel = launch;
            this.target = target;
        }

        @Override public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            World world = pos.getWorld();
            if (owner == null || world == null || age > EYE_OPEN_TICKS + EYE_LIFETIME) { cancel(); return; }

            // Phase 1 — the eye opens: a golden lid blooming wider each tick before it lifts away from the body.
            if (age < EYE_OPEN_TICKS) {
                drawEyeOpening(world, pos, (double) age / EYE_OPEN_TICKS);
                age++;
                return;
            }

            boolean homing = target != null && target.isValid() && !target.isDead()
                    && target.getWorld().equals(world);
            if (homing) {
                Location aim = target.getLocation().add(0, target.getHeight() * 0.55, 0);
                Vector desired = aim.toVector().subtract(pos.toVector());
                double dist = desired.length();
                if (dist <= 1.0) {
                    dealRuin(owner, target, EYES_DAMAGE);
                    drawEyeBurst(world, aim);
                    world.playSound(aim, Sound.ENTITY_BLAZE_HURT, 0.5f, 1.7f);
                    cancel();
                    return;
                }
                steer(desired.multiply(1.0 / dist)); // turn the heading toward the foe by a capped angle each tick
            } else if (age > EYE_OPEN_TICKS + 45) {
                drawEyeBurst(world, pos); // no target and it has drifted long enough — fade out
                cancel();
                return;
            }

            pos.add(vel.clone().multiply(EYE_SPEED));
            drawEyeBody(world, pos, vel);
            age++;
        }

        /** Rotate the heading toward {@code desired} (a unit vector) by at most EYE_TURN_DEG — the curving arc. */
        private void steer(Vector desired) {
            Vector cur = vel.clone().normalize();
            double dot = Math.max(-1.0, Math.min(1.0, cur.dot(desired)));
            double ang = Math.acos(dot);
            double maxTurn = Math.toRadians(EYE_TURN_DEG);
            if (ang <= maxTurn || ang < 1.0e-6) { vel = desired.clone(); return; }
            Vector axis = cur.getCrossProduct(desired);
            if (axis.lengthSquared() < 1.0e-9) { vel = desired.clone(); return; } // exactly opposed — just snap
            vel = cur.rotateAroundAxis(axis.normalize(), maxTurn);
        }
    }

    /** The golden lid opening: a lens shape whose height grows from a slit to a full eye, a warm iris at the core. */
    private void drawEyeOpening(World world, Location at, double frac) {
        double w = 0.42;
        double h = 0.03 + frac * 0.26; // a horizontal slit widening into an eye
        int pts = 12;
        for (int k = 0; k <= pts; k++) {
            double a = Math.PI * k / pts;
            double x = Math.cos(a) * w;
            double lid = Math.sin(a) * h;
            world.spawnParticle(Particle.DUST, at.clone().add(x, lid, 0), 1, 0, 0, 0, 0, EYE_GOLD);
            world.spawnParticle(Particle.DUST, at.clone().add(x, -lid, 0), 1, 0, 0, 0, 0, EYE_GOLD);
        }
        if (frac > 0.45) world.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, EYE_IRIS);
    }

    /** The flying eye: a golden body with a warm amber iris, and a soft golden wake behind (no end-rod streak). */
    private void drawEyeBody(World world, Location at, Vector heading) {
        world.spawnParticle(Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0, EYE_GOLD);
        world.spawnParticle(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0, EYE_IRIS);
        Location back = at.clone().subtract(heading.clone().multiply(EYE_SPEED * 0.6));
        world.spawnParticle(Particle.DUST, back, 1, 0.03, 0.03, 0.03, 0, EYE_GOLD_FAINT); // reads the curve, softly
    }

    /** The eye's end: a small golden bloom where it strikes (or fades). */
    private void drawEyeBurst(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 16, 0.28, 0.28, 0.28, 0, new Particle.DustOptions(GOLD_DUST, 1.4f));
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, GOLD_DUST);
    }

    // ---- helpers -------------------------------------------------------------------

    private List<LivingEntity> nearbyEnemies(Player player, double range) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(range, range, range)) {
            if (e instanceof LivingEntity le && !e.equals(player) && !le.isDead()) out.add(le);
        }
        return out;
    }

    private boolean busy(UUID id) {
        Long t = busyUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    private void setBusy(UUID id, long ticks) {
        busyUntil.put(id, System.currentTimeMillis() + ticks * 50L);
    }

    // ---- HUD: one composed line — the regen clock, three cooldowns, the sin scale ----

    private Component hud(UUID id) {
        long now = System.currentTimeMillis();
        Component regen = cdOrReady("Trumpet", nextRegenAt.getOrDefault(id, now), now, C_GOLD);
        Component peace = cdOrReady("Peace", peaceReadyAt.getOrDefault(id, 0L), now, C_WHITE);
        Component eyes  = cdOrReady("Eyes", eyesReadyAt.getOrDefault(id, 0L), now, C_GOLD);
        Component punish = cdOrReady("Punish", punishReadyAt.getOrDefault(id, 0L), now, C_AMBER);
        Component scale = EgoHud.pips("Sin", sinFull(id) ? C_WHITE : C_GOLD, sin.getOrDefault(id, 0), SIN_MAX);
        return EgoHud.row(regen, peace, eyes, punish, scale);
    }

    private Component cdOrReady(String name, long readyAt, long now, TextColor color) {
        return now >= readyAt ? EgoHud.ready(name, color) : EgoHud.cooldown(name, readyAt - now, C_FAINT);
    }

    // ---- VFX -----------------------------------------------------------------------

    private void auraVfx(Player player) {
        World world = player.getWorld();
        Location c = player.getLocation().add(0, 1.0, 0);
        double a = (System.currentTimeMillis() % 2000) / 2000.0 * Math.PI * 2;
        for (int i = 0; i < 2; i++) {
            double ang = a + i * Math.PI;
            Location p = c.clone().add(Math.cos(ang) * 0.7, Math.sin(a * 2) * 0.2, Math.sin(ang) * 0.7);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD_DUST, 0.8f));
        }
    }

    /**
     * One of the four combo crescents, drawn through Yae's shared {@link SlashVfx}. Each strike swings on a
     * different plane (tilt) and colour, and grows in reach/span/thickness — so the sequence climbs rather
     * than repeats, ending on a vertical overhead cleave with a swept blade.
     */
    private void crescent(Player player, int index) {
        Location origin = player.getEyeLocation();
        Vector aim = origin.getDirection();
        // The kata: a down-right diagonal, a down-left diagonal, a wide horizontal sweep, then an overhead
        // cleave. Each swings slower (higher duration = a smoother, more legible arc) and denser than the last.
        switch (index) {
            case 0 -> SlashVfx.slash(plugin, origin, aim).tilt(-52).arcSpan(150).reach(4.0)
                    .colours(GOLD_DUST, WHITE_DUST).thickness(1.3f).duration(6).play();
            case 1 -> SlashVfx.slash(plugin, origin, aim).tilt(52).arcSpan(155).reach(4.3)
                    .colours(GOLD_DUST, WHITE_DUST).thickness(1.4f).duration(6).play();
            case 2 -> SlashVfx.slash(plugin, origin, aim).tilt(2).arcSpan(180).reach(4.8)
                    .colours(AMBER_DUST, GOLD_DUST).thickness(1.6f).duration(7).play();
            default -> SlashVfx.slash(plugin, origin, aim).tilt(90).arcSpan(190).reach(5.6)
                    .colours(WHITE_DUST, GOLD_DUST).thickness(1.9f).duration(8).play();
        }
    }

    private void windupVfx(Player player, double frac) {
        World world = player.getWorld();
        Location o = player.getEyeLocation().add(0, 1.2 + frac * 0.8, 0);
        int n = (int) (6 + frac * 10);
        for (int i = 0; i < n; i++) {
            double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double r = (1.0 - frac) * 1.4 + 0.1;
            Location p = o.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0.01);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD_DUST, 1.0f));
        }
    }

    /** A real expanding ring: glowing BlockDisplays grow outward across the ground from the slam, over a crack. */
    private void shockwaveVfx(Location c, boolean empowered) {
        World world = c.getWorld();
        world.spawnParticle(Particle.FLASH, c.clone().add(0, 1, 0), empowered ? 2 : 1, 0, 0, 0, 0, empowered ? WHITE_DUST : GOLD_DUST);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        final var groundData = c.getBlock().getRelative(0, -1, 0).getBlockData();
        final var blockData = (empowered ? Material.AMETHYST_BLOCK : Material.GLOWSTONE).createBlockData();
        final int seg = 20;
        final List<BlockDisplay> ring = new ArrayList<>();
        for (int i = 0; i < seg; i++) {
            double a = (Math.PI * 2 * i) / seg;
            Location at = c.clone().add(Math.cos(a) * 0.7, 0.05, Math.sin(a) * 0.7);
            ring.add(world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(blockData);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.addScoreboardTag(VFX_TAG);
                d.setTransformation(new Transformation(
                        new Vector3f(-0.2f, 0f, -0.2f), new Quaternionf(), new Vector3f(0.4f, 0.4f, 0.4f), new Quaternionf()));
            }));
        }
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t++ >= 14) { ring.forEach(Entity::remove); cancel(); return; }
                double r = 0.7 + t * (PEACE_RADIUS - 0.7) / 14.0;
                float sc = (float) Math.max(0.05, 0.45 * (1.0 - t / 18.0));
                for (int i = 0; i < seg; i++) {
                    BlockDisplay d = ring.get(i);
                    if (!d.isValid()) continue;
                    double a = (Math.PI * 2 * i) / seg;
                    Location p = c.clone().add(Math.cos(a) * r, 0.05, Math.sin(a) * r);
                    d.teleport(p);
                    d.setTransformation(new Transformation(
                            new Vector3f(-sc / 2f, 0f, -sc / 2f), new Quaternionf(),
                            new Vector3f(sc, sc * 0.35f, sc), new Quaternionf()));
                    world.spawnParticle(Particle.BLOCK, p, 4, 0.25, 0.05, 0.25, 0, groundData);
                    if (empowered && i % 4 == 0) world.spawnParticle(Particle.END_ROD, p, 1, 0, 0.1, 0, 0.03);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void lungeVfx(Player player, Vector dir) {
        World world = player.getWorld();
        Location o = player.getLocation().add(0, 1.0, 0);
        for (int i = 0; i < 6; i++) {
            Location p = o.clone().subtract(dir.clone().multiply(i * 0.35));
            world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(WHITE_DUST, 1.1f));
            world.spawnParticle(Particle.CRIT, p, 2, 0.1, 0.1, 0.1, 0.05);
        }
    }

    private void trumpetVfx(Player player) {
        World world = player.getWorld();
        Location c = player.getLocation();
        world.playSound(c, Sound.BLOCK_BELL_USE, 1.0f, 0.7f);
        // The third trumpet sounds low and far off — a muffled roar, kept quiet, not a raid horn.
        world.playSound(c, Sound.ENTITY_RAVAGER_ROAR, 0.3f, 0.5f);
        world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.2f, 0.6f);
        for (int i = 0; i < 40; i++) {
            double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double r = ThreadLocalRandom.current().nextDouble(0, 1.6);
            Location p = c.clone().add(Math.cos(a) * r, ThreadLocalRandom.current().nextDouble(0, 2.2), Math.sin(a) * r);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0,
                    new Particle.DustTransition(GOLD_DUST, WHITE_DUST, 1.2f));
        }
    }

    // ---- identity + bespoke tooltip ------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.TWILIGHT.material()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** The roster-standard tooltip — gold primary, white secondary, the same shape every E.G.O weapon wears. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Twilight",
            "Apocalypse Bird",
            C_GOLD, C_WHITE,
            List.of(
                    "Just like how the ever-watching eyes, the",
                    "scale that could measure any and all sin,",
                    "and the beak that could swallow everything",
                    "protected the peace of the Black Forest...",
                    "",
                    "The wielder of this armament may also",
                    "bring peace as they did."),
            List.of(
                    new EgoLore.Ability("[Passive] Third Trumpet",
                            "Great health, but no natural healing.",
                            "The whole pool pours back every 66.6s."),
                    new EgoLore.Ability("[Passive] John Lobotomy",
                            "Every blow is physical, magic and hunger",
                            "at once, tearing through most armour."),
                    new EgoLore.Ability("[Passive] Tilted Scale",
                            "Every hit weighs the target's sin. At full",
                            "weight, Peace For All cripples, and",
                            "Brilliant Eyes strikes twice."),
                    new EgoLore.Ability("[Left Click] Attack Sequence",
                            "A four-hit greatsword combo — one",
                            "strike for each kind of ruin."),
                    new EgoLore.Ability("[Right Click] Peace For All",
                            "Leap skyward, then slam down a shockwave",
                            "that ruins everything around where you",
                            "land. 14s cooldown."),
                    new EgoLore.Ability("[Shift + Right] Brilliant Eyes",
                            "Open the golden eyes: ten homing lights",
                            "seek your foes. 16s cooldown."),
                    new EgoLore.Ability("[Shift + Left] Punishment",
                            "A dashing strike that launches foes. Hit a",
                            "wall mid-lunge and you are flung skyward.",
                            "12s cooldown.")));

    /** The Cogito-unobtainable marker, a clean line below the standard footer. */
    private static final Component SPECIAL_MARK = Component.text("SPECIAL", C_GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.TWILIGHT.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        List<Component> lore = new ArrayList<>(meta.lore()); // append the SPECIAL marker under the E.G.O footer
        lore.add(SPECIAL_MARK);
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.TWILIGHT);
        item.setItemMeta(meta);
        return item;
    }
}
