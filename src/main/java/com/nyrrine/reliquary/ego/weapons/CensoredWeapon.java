package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 *   <li><b>[Left Click]</b> — the maw: a slow, long-reaching strike that bites twice, on a {@link
 *       #MAW_COOLDOWN_MS} cadence so it stays heavy. Each swing has a {@link #FREE_GRAPPLE_CHANCE} chance to
 *       fire the Shift+Right-click grapple for free, ignoring both its cooldown and attack speed.</li>
 *   <li><b>[Shift + Right Click]</b> — the big grapple: a brief charge, then a tear along an extended line
 *       that hits every foe in it {@link #GRAPPLE_HITS} times. {@link #GRAPPLE_COOLDOWN_MS}, skipped when the
 *       passive procs it.</li>
 *   <li><b>[Right Click]</b> — prime. The wielder's next kill within {@link #PRIME_WINDOW_MS} is the
 *       signature Feast: teleport to the body, i-frames while you heal, a gruesome macabre show, then a red
 *       censored square bursts over the corpse to heal you and leave a lingering cognition-filter that bleeds
 *       everything near it. {@link #FEAST_COOLDOWN_MS} of silence afterward.</li>
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
    /** Wielder -> epoch-ms their prime lapses; their next kill before this fires the Feast. */
    private final Map<UUID, Long> primedUntil = new HashMap<>();
    /** Wielder -> epoch-ms the Feast (Right-click) is off silence. */
    private final Map<UUID, Long> feastReadyAt = new HashMap<>();
    /** Wielder -> epoch-ms their Feast i-frames end; zeroed incoming damage until then. */
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    /** Looker -> epoch-ms they began looking at a wielder; cleared the moment they look away. */
    private final Map<UUID, Long> lookingSince = new HashMap<>();
    /** Every live task (grapple charges, feasts, cognition filters), reaped on disable. */
    private final Set<BukkitRunnable> activeTasks = new HashSet<>();

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

    // ---- feast (Right-click) tuning -----------------------------------------------
    private static final long   PRIME_WINDOW_MS    = 6_000L;
    private static final long   FEAST_COOLDOWN_MS  = 20_000L;
    private static final int    SHOW_TICKS         = 40;      // ~2s gruesome show
    private static final long   SHOW_MS            = 2_200L;  // i-frame window covering the show (+buffer)
    private static final int    HEAL_EVERY         = 8;       // heal a chunk this often during the show
    private static final double HEAL_CHUNK         = 1.0;
    private static final double BURST_FINAL_HEAL   = 3.0;     // the square-burst payoff heal
    /** The lingering cognition filter left by the burst. */
    private static final double COGNITION_RADIUS   = 5.0;
    private static final double COGNITION_DAMAGE   = 2.0;
    private static final int    COGNITION_PERIOD   = 10;      // a pulse every 0.5s
    private static final int    COGNITION_TICKS    = 60;      // for ~3s

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
        drawMawExtend(eye, dir);

        LivingEntity target = mawTarget(player, eye, dir);
        if (target != null) {
            biteTwice(player, target);
        }

        // 10% per swing: the maw acts on its own, all at once — the grapple, free and instant.
        if (ThreadLocalRandom.current().nextDouble() < FREE_GRAPPLE_CHANCE) {
            castGrapple(player, true);
        }
    }

    /** The first living body the maw's line reaches within {@link #MAW_REACH}, or null on a whiff. */
    private LivingEntity mawTarget(Player player, Location eye, Vector dir) {
        RayTraceResult rt = player.getWorld().rayTraceEntities(eye, dir, MAW_REACH, MAW_RADIUS,
                e -> e instanceof LivingEntity && !e.equals(player) && !e.isDead());
        return rt != null && rt.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    /**
     * Two bites off one swing. Each goes through the framework's {@code pierceDamage}, which fences it out of
     * the on-hit dispatch, clears the victim's i-frames so the second bite still lands, ignores {@link
     * #PIERCE_FRACTION} of armour, and restores velocity so the maw holds rather than knocks back. Each bite
     * also leaves the body starving.
     */
    private void biteTwice(Player player, LivingEntity target) {
        for (int i = 0; i < 2; i++) {
            plugin.weapons().pierceDamage(target, MAW_BITE_DAMAGE, PIERCE_FRACTION, player);
            starve(target);
        }
        Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        World world = target.getWorld();
        world.spawnParticle(Particle.DUST, at, 10, 0.3, 0.35, 0.3, 0, BLOOD_DUST);
        world.spawnParticle(Particle.ITEM, at, 4, 0.25, 0.3, 0.25, 0.02, BONE_ITEM);
        world.playSound(at, Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 0.6f);
        world.playSound(at, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.6f, 0.5f);
    }

    /** The saturation drain: push exhaustion onto a struck player and lay a short Hunger on any body. */
    private void starve(LivingEntity victim) {
        if (victim instanceof Player p) {
            p.setExhaustion(Math.min(40.0f, p.getExhaustion() + SATURATION_EXHAUST));
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, HUNGER_TICKS, HUNGER_AMP, false, true, true));
    }

    // ---- [Shift+Right Click]: the big grapple / [Right Click]: prime --------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) castGrapple(player, false);
        else primeFeast(player);
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
                player.sendActionBar(EgoHud.cooldown("CENSORED", ready - now, CENSOR_RED));
                player.playSound(player.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.25f, 0.5f);
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

    /** Tear along the line: every living body inside it takes {@link #GRAPPLE_HITS} bites and is left starving. */
    private void strikeGrapple(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = owner.getWorld();

        drawGrappleTear(eye, dir);
        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 0.6f);
        world.playSound(eye, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 0.5f);

        Location mid = eye.clone().add(dir.clone().multiply(GRAPPLE_RANGE * 0.5));
        double half = GRAPPLE_RANGE * 0.5 + GRAPPLE_RADIUS;
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
            Location at = le.getLocation().add(0, le.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.DUST, at, 14, 0.3, 0.4, 0.3, 0, BLOOD_DUST);
            world.spawnParticle(Particle.ITEM, at, 6, 0.3, 0.35, 0.3, 0.03, BONE_ITEM);
        }
    }

    /** Prime the Feast: the wielder's next kill within {@link #PRIME_WINDOW_MS} triggers the signature. */
    private void primeFeast(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long ready = feastReadyAt.get(id);
        if (ready != null && now < ready) {
            player.sendActionBar(EgoHud.cooldown("CENSORED", ready - now, CENSOR_RED));
            player.playSound(player.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.25f, 0.5f);
            return;
        }
        primedUntil.put(id, now + PRIME_WINDOW_MS);
        player.sendActionBar(EgoHud.status("CENSORED", CENSOR_RED));
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.6f, 0.5f);
    }

    // ---- the Feast: the signature kill sequence -----------------------------------

    /**
     * A body died. If its killer is a primed CENSORED wielder, the Feast begins: the prime is spent, the
     * silence starts, and the wielder is dragged to the corpse to feed.
     */
    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        UUID id = killer.getUniqueId();
        if (!matches(killer.getInventory().getItemInMainHand())) return;

        long now = System.currentTimeMillis();
        Long prime = primedUntil.get(id);
        if (prime == null || now > prime) return;

        primedUntil.remove(id);
        feastReadyAt.put(id, now + FEAST_COOLDOWN_MS); // the silence
        beginFeast(killer, event.getEntity().getLocation());
    }

    /** Teleport to the body, grant the i-frames, and start the gruesome show. */
    private void beginFeast(Player wielder, Location body) {
        Location dest = body.clone();
        dest.setYaw(wielder.getLocation().getYaw());
        dest.setPitch(wielder.getLocation().getPitch());
        wielder.teleport(dest);

        immuneUntil.put(wielder.getUniqueId(), System.currentTimeMillis() + SHOW_MS);
        wielder.getWorld().playSound(body, Sound.ENTITY_WARDEN_DIG, 1.0f, 0.4f);
        wielder.getWorld().playSound(body, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 0.4f);
        track(new CensoredFeast(wielder.getUniqueId(), body.clone())).runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * The show: a wall of blood and thrown bone over the corpse for {@link #SHOW_TICKS}, layered macabre
     * sound left to the imagination, the wielder healing as it runs. At the end the red censored square bursts
     * and leaves the lingering cognition filter.
     */
    private final class CensoredFeast extends BukkitRunnable {
        private final UUID ownerId;
        private final Location body;
        private int ticks = 0;

        CensoredFeast(UUID ownerId, Location body) {
            this.ownerId = ownerId;
            this.body = body;
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isValid()) { stop(); return; }

            if (ticks >= SHOW_TICKS) {
                burst(owner);
                stop();
                return;
            }

            gruesomeVfx(body);
            macabreSfx(body, ticks);
            if (ticks % HEAL_EVERY == 0) heal(owner, HEAL_CHUNK);
            ticks++;
        }

        /** The red square engulfs the corpse and bursts: the payoff heal, and the lingering filter. */
        private void burst(Player owner) {
            World world = body.getWorld();
            redSquare(body, 2.0);
            world.spawnParticle(Particle.DUST, body.clone().add(0, 1.0, 0), 60, 1.2, 1.2, 1.2, 0, CENSOR_DUST);
            world.spawnParticle(Particle.ITEM, body.clone().add(0, 1.0, 0), 24, 0.9, 0.9, 0.9, 0.06, BONE_ITEM);
            world.playSound(body, Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.4f);
            world.playSound(body, Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 0.4f);
            heal(owner, BURST_FINAL_HEAL);
            track(new CognitionFilter(ownerId, body.clone())).runTaskTimer(plugin, 1L, 1L);
        }

        private void stop() {
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

    // ---- passive: the i-frames, and the sickening gaze ----------------------------

    /** During the Feast show the wielder is untouchable — zeroed, not cancelled, so nothing else is misled. */
    @Override
    public void onIncomingDamage(Player wielder, EntityDamageEvent event) {
        Long until = immuneUntil.get(wielder.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) event.setDamage(0.0);
    }

    /** Each engaged tick, sicken anyone who has held their gaze on the wielder too long. */
    @Override
    public boolean onTick(Player player, long tick) {
        lookScan(player);
        return true;
    }

    /**
     * Anyone within {@link #LOOK_RANGE} facing the wielder head-on accrues look-time; hold it past {@link
     * #LOOK_SICKEN_MS} and they are sickened with Nausea, refreshed while they keep watching. Looking away
     * clears the clock at once.
     */
    private void lookScan(Player wielder) {
        long now = System.currentTimeMillis();
        for (Entity e : wielder.getNearbyEntities(LOOK_RANGE, LOOK_RANGE, LOOK_RANGE)) {
            if (!(e instanceof LivingEntity looker) || looker.isDead() || looker.equals(wielder)) continue;
            UUID lid = looker.getUniqueId();
            if (isLookingAt(looker, wielder)) {
                long since = lookingSince.computeIfAbsent(lid, k -> now);
                if (now - since >= LOOK_SICKEN_MS) {
                    looker.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0, false, true, true));
                }
            } else {
                lookingSince.remove(lid);
            }
        }
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

    // ---- presentation -------------------------------------------------------------

    /** The maw lunging out along the aim: a line of blood-dust that gets denser toward the far end. */
    private void drawMawExtend(Location eye, Vector dir) {
        World world = eye.getWorld();
        for (double d = 0.6; d <= MAW_REACH; d += 0.5) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, BLOOD_DUST);
            if (d > MAW_REACH * 0.6) world.spawnParticle(Particle.DUST, p, 1, 0.12, 0.12, 0.12, 0, CENSOR_DUST);
        }
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

    /** The grapple's tear: a broad red-and-blood lance along the line. */
    private void drawGrappleTear(Location eye, Vector dir) {
        World world = eye.getWorld();
        for (double d = 0.5; d <= GRAPPLE_RANGE; d += 0.5) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 2, 0.25, 0.25, 0.25, 0, CENSOR_DUST);
            world.spawnParticle(Particle.DUST, p, 1, 0.15, 0.15, 0.15, 0, BLOOD_DUST);
        }
    }

    /** Per-tick gruesome show: blood pouring off the corpse and bones flung out of it. */
    private void gruesomeVfx(Location body) {
        World world = body.getWorld();
        Location core = body.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.DUST, core, 12, 0.4, 0.5, 0.4, 0, BLOOD_DUST);
        world.spawnParticle(Particle.FALLING_DUST, core, 6, 0.5, 0.2, 0.5, 0, BLOOD_BLOCK);
        world.spawnParticle(Particle.ITEM, core, 5, 0.35, 0.4, 0.35, 0.08, BONE_ITEM);
        if (ThreadLocalRandom.current().nextInt(3) == 0) {
            world.spawnParticle(Particle.DUST, core, 8, 0.6, 0.4, 0.6, 0, CENSOR_DUST);
        }
    }

    /** The sound of the feed: layered, low, squelching and muffled, staggered so it never reads as one loop. */
    private void macabreSfx(Location at, int t) {
        World world = at.getWorld();
        if (t % 5 == 0)  world.playSound(at, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.5f);
        if (t % 7 == 0)  world.playSound(at, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.9f, 0.4f);
        if (t % 11 == 0) world.playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 0.6f, 0.4f);
        if (t % 13 == 0) world.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.5f);
        if (t % 17 == 0) world.playSound(at, Sound.ENTITY_GHAST_HURT, 0.4f, 0.4f);
    }

    /** The red censored square: a flat filled quad of red motes hanging over a point. */
    private void redSquare(Location centre, double size) {
        World world = centre.getWorld();
        Location c = centre.clone().add(0, 1.0, 0);
        double step = size / 4.0;
        for (double x = -size; x <= size; x += step) {
            for (double z = -size; z <= size; z += step) {
                world.spawnParticle(Particle.DUST, c.clone().add(x, 0, z), 1, 0, 0, 0, 0, CENSOR_DUST);
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        lastMaw.remove(id);
        grappleReadyAt.remove(id);
        charging.remove(id);
        primedUntil.remove(id);
        feastReadyAt.remove(id);
        immuneUntil.remove(id);
        lookingSince.remove(id);
    }

    @Override
    public void onDisable() {
        for (BukkitRunnable t : new ArrayList<>(activeTasks)) t.cancel();
        activeTasks.clear();
        lastMaw.clear();
        grappleReadyAt.clear();
        charging.clear();
        primedUntil.clear();
        feastReadyAt.clear();
        immuneUntil.clear();
        lookingSince.clear();
    }

    // ---- colours / particles ------------------------------------------------------

    /** Primary — the redaction's blood red. Display name, "How to use:", ability headers. */
    private static final TextColor CENSOR_RED = TextColor.color(0xCC0022);
    /** Secondary — the grey of a redaction bar. The Abnormality title line. */
    private static final TextColor REDACT     = TextColor.color(0x808080);

    private static final Color BLOOD_RGB  = Color.fromRGB(0x8A, 0x03, 0x03); // pouring blood
    private static final Color CENSOR_RGB = Color.fromRGB(0xCC, 0x00, 0x22); // the red square / redaction
    private static final Particle.DustOptions BLOOD_DUST  = new Particle.DustOptions(BLOOD_RGB, 1.1f);
    private static final Particle.DustOptions CENSOR_DUST = new Particle.DustOptions(CENSOR_RGB, 1.3f);
    /** A red block for the FALLING_DUST blood, and a bone for the thrown-bone ITEM particles. */
    private static final org.bukkit.block.data.BlockData BLOOD_BLOCK = Material.REDSTONE_BLOCK.createBlockData();
    private static final ItemStack BONE_ITEM = new ItemStack(Material.BONE);

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
                            "Mark yourself. Your next kill within 6s",
                            "is (CENSORED): you are dragged to the",
                            "body to feed, healing as it (CENSORED)",
                            "everything left near it.")
            ));
}
