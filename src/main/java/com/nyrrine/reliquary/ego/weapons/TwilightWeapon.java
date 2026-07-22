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
import org.bukkit.entity.ItemDisplay;
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

    /** Attack Sequence: four strikes, one ruin each, a few ticks apart. Per-strike damage + the reach. */
    private static final double[] COMBO_DAMAGE = {6.0, 6.0, 7.0, 9.0};
    private static final long   COMBO_STEP_TICKS = 4L;
    private static final double COMBO_REACH    = 4.2;
    private static final double COMBO_CONE     = 0.35; // dot threshold for "in front"

    private static final long   PEACE_CD_MS    = 14_000L;
    private static final int    PEACE_WINDUP   = 18;   // ticks of wind-up before the slam
    private static final double PEACE_RADIUS   = 6.5;
    private static final double PEACE_DAMAGE   = 14.0;

    private static final long   EYES_CD_MS     = 16_000L;
    private static final int    EYES_COUNT     = 10;
    private static final double EYES_DAMAGE    = 4.0;
    private static final double EYES_RANGE     = 20.0;

    private static final long   PUNISH_CD_MS   = 12_000L;
    private static final double PUNISH_POWER   = 1.7;   // dash impulse
    private static final double PUNISH_DAMAGE  = 8.0;
    private static final double PUNISH_REACH   = 3.0;
    private static final int    PUNISH_TICKS   = 8;     // how long the lunge is watched for a wall

    // ---- palette ------------------------------------------------------------------
    private static final TextColor C_GOLD  = TextColor.color(0xFFD54A);
    private static final TextColor C_WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor C_AMBER = TextColor.color(0xE8A23C);
    private static final TextColor C_FAINT = TextColor.color(0x9A8F73);
    private static final Color GOLD_DUST = Color.fromRGB(0xFF, 0xD5, 0x4A);
    private static final Color WHITE_DUST = Color.fromRGB(0xFF, 0xF4, 0xD8);
    private static final Color AMBER_DUST = Color.fromRGB(0xE8, 0xA2, 0x3C);
    private static final Color VOID_DUST = Color.fromRGB(0x6A, 0x4C, 0xC0);
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
        setBusy(id, COMBO_STEP_TICKS * COMBO_DAMAGE.length + 4);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.7f);
        new BukkitRunnable() {
            int strike = 0;
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline() || !matches(p.getInventory().getItemInMainHand())
                        || strike >= COMBO_DAMAGE.length) {
                    cancel();
                    return;
                }
                comboStrike(p, strike, COMBO_DAMAGE[strike]);
                strike++;
            }
        }.runTaskTimer(plugin, 0L, COMBO_STEP_TICKS);
    }

    /** One strike of the sequence: a wide cone in front takes a ruin, with a slash whose fidelity climbs. */
    private void comboStrike(Player player, int index, double damage) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        // Each ruin swings on its own plane and colour, building strike to strike — four distinct crescents.
        crescent(player, index);
        world.playSound(eye, Sound.ITEM_TRIDENT_RETURN, 0.7f, 0.8f + index * 0.18f);
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f + index * 0.15f);
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

    // ---- ability: [Right Click] Peace For All — wind up, then a shockwave ------------

    private void peaceForAll(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < peaceReadyAt.getOrDefault(id, 0L)) {
            player.sendActionBar(hud(id));
            return;
        }
        boolean empowered = sinFull(id);
        peaceReadyAt.put(id, now + PEACE_CD_MS);
        setBusy(id, PEACE_WINDUP + 4);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.6f);
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 0.8f, 0.7f);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline()) { cancel(); return; }
                if (t < PEACE_WINDUP) {
                    windupVfx(p, (double) t / PEACE_WINDUP); // the blade gathers light overhead
                    t++;
                    return;
                }
                peaceSlam(p, empowered);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** The slam itself: a shockwave outward, heavy ruin all around, and — at full sin — a crippling launch. */
    private void peaceSlam(Player player, boolean empowered) {
        UUID id = player.getUniqueId();
        World world = player.getWorld();
        Location c = player.getLocation();
        world.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.7f);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.6f);
        world.playSound(c, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.0f, 0.5f);
        shockwaveVfx(c, empowered);
        for (Entity e : player.getNearbyEntities(PEACE_RADIUS, PEACE_RADIUS, PEACE_RADIUS)) {
            if (!(e instanceof LivingEntity le) || e.equals(player)) continue;
            dealRuin(player, le, PEACE_DAMAGE);
            if (empowered) {
                le.setVelocity(le.getLocation().toVector().subtract(c.toVector()).setY(0.9)
                        .normalize().multiply(1.1).setY(0.9)); // catch and launch
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 3, false, true, true));
                le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 1, false, true, true));
            }
        }
        if (empowered) {
            spendSin(id);
            world.playSound(c, Sound.ITEM_TOTEM_USE, 0.8f, 0.7f);
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
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.7f, 1.4f);
        world.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 1.2f);
        eyesOpenVfx(player);

        List<LivingEntity> targets = nearbyEnemies(player, EYES_RANGE);
        Location origin = player.getEyeLocation().add(0, 0.4, 0);
        for (int i = 0; i < count; i++) {
            LivingEntity target = targets.isEmpty() ? null : targets.get(i % targets.size());
            new BrilliantEye(id, origin.clone(), target, i).runTaskTimer(plugin, (long) (i / 2), 1L);
        }
        if (twice) spendSin(id);
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
            @Override public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline() || t >= PUNISH_TICKS || launched) { cancel(); return; }
                lungeVfx(p, dir);
                // Strike anything the lunge passes through.
                for (Entity e : p.getNearbyEntities(PUNISH_REACH, PUNISH_REACH, PUNISH_REACH)) {
                    if (!(e instanceof LivingEntity le) || e.equals(p)) continue;
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

    // ---- Brilliant Eye: a glowing golden eye (ItemDisplay) trailing a ribbon --------

    private final class BrilliantEye extends BukkitRunnable {
        private final UUID ownerId;
        private final Location pos;
        private final LivingEntity target;
        private final int index;
        private ItemDisplay body;
        private Location prev;
        private int age = 0;

        BrilliantEye(UUID ownerId, Location origin, LivingEntity target, int index) {
            this.ownerId = ownerId;
            this.pos = origin;
            this.prev = origin.clone();
            this.target = target;
            this.index = index;
        }

        @Override public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || age++ > 60 || target == null || !target.isValid() || target.isDead()
                    || !target.getWorld().equals(pos.getWorld())) {
                remove();
                return;
            }
            if (body == null) body = spawnEyeBody(pos); // real geometry: a glowing golden eye
            Location aim = target.getLocation().add(0, target.getHeight() * 0.55, 0);
            Vector step = aim.toVector().subtract(pos.toVector());
            double dist = step.length();
            World world = pos.getWorld();
            if (dist <= 1.1) {
                dealRuin(owner, target, EYES_DAMAGE);
                world.spawnParticle(Particle.FLASH, aim, 1, 0, 0, 0, 0, GOLD_DUST);
                world.spawnParticle(Particle.DUST, aim, 12, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(GOLD_DUST, 1.3f));
                world.playSound(aim, Sound.ENTITY_BLAZE_HURT, 0.5f, 1.6f);
                remove();
                return;
            }
            // A slight curve for the first few ticks so the swarm fans out before it homes.
            Vector head = step.normalize();
            if (age < 5) head.add(new Vector((index % 3 - 1) * 0.35, 0.12, (index % 2 == 0 ? 0.25 : -0.25))).normalize();
            prev = pos.clone();
            pos.add(head.multiply(Math.min(1.1, dist)));
            if (body.isValid()) {
                body.teleport(pos);
                body.setRotation(owner.getLocation().getYaw() + age * 26f, 0f); // the eye turns as it flies
            }
            ribbon(world, prev, pos); // a glowing golden ribbon streaming behind it
        }

        private void remove() {
            if (body != null && body.isValid()) body.remove();
            cancel();
        }
    }

    /** A glowing golden eye body — an ItemDisplay stand-in the pack can remodel; full-bright, viewer-facing. */
    private ItemDisplay spawnEyeBody(Location at) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.GLOWSTONE_DUST));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.addScoreboardTag(VFX_TAG);
            d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(0.55f, 0.55f, 0.55f), new Quaternionf()));
        });
    }

    /** A dense, continuous gold-to-white dust ribbon between two points — a trailing streamer, not a dot cloud. */
    private void ribbon(World world, Location from, Location to) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 1.0e-4) return;
        int steps = Math.max(2, (int) (len / 0.12));
        Vector unit = d.multiply(1.0 / steps);
        Location p = from.clone();
        for (int i = 0; i <= steps; i++) {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.01, 0.01, 0.01, 0,
                    new Particle.DustTransition(GOLD_DUST, WHITE_DUST, 1.1f));
            if (i % 3 == 0) world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0.0);
            p.add(unit);
        }
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
        switch (index) {
            case 0 -> SlashVfx.slash(plugin, origin, aim).tilt(-42).arcSpan(130).reach(3.8)
                    .colours(GOLD_DUST, WHITE_DUST).thickness(1.1f).duration(4).play();
            case 1 -> SlashVfx.slash(plugin, origin, aim).tilt(42).arcSpan(140).reach(4.1)
                    .colours(GOLD_DUST, WHITE_DUST).thickness(1.2f).duration(4).play();
            case 2 -> SlashVfx.slash(plugin, origin, aim).tilt(-8).arcSpan(160).reach(4.6)
                    .colours(AMBER_DUST, GOLD_DUST).thickness(1.4f).duration(5).play();
            default -> SlashVfx.slash(plugin, origin, aim).tilt(90).arcSpan(185).reach(5.3)
                    .colours(WHITE_DUST, GOLD_DUST).thickness(1.7f).duration(6)
                    .blade(Material.NETHERITE_SWORD).bladeScale(2.4).play();
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

    private void eyesOpenVfx(Player player) {
        World world = player.getWorld();
        Location o = player.getEyeLocation().add(0, 0.6, 0);
        for (int i = 0; i < 24; i++) {
            double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            Location p = o.clone().add(Math.cos(a) * 1.2, ThreadLocalRandom.current().nextDouble(-0.5, 0.9), Math.sin(a) * 1.2);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD_DUST, 1.2f));
            if (i % 3 == 0) world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0.02);
        }
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
        world.playSound(c, Sound.ITEM_TOTEM_USE, 0.7f, 1.3f);
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
                            "Wind up, then a shockwave that ruins",
                            "everything around you. 14s cooldown."),
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
        meta.setEnchantmentGlintOverride(true); // the flagship glimmers
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.TWILIGHT);
        item.setItemMeta(meta);
        return item;
    }
}
