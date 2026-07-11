package com.nyrrine.reliquary.busego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.busego.BusEgoModels;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Flower Burying Wedge — the first <b>bus ego</b> weapon. A REDSTONE_TORCH carrying a custom flower model;
 * eight crimson wedges rest behind the wielder in a spinning radial halo, each petal angled outward like
 * the bloom it's named for. It does no melee damage of its own — its whole fantasy is command: like eight
 * of Yondu's arrows loosed and steered by will.
 *
 * <ul>
 *   <li><b>Right-click</b> — mark the looked-at (or nearest-ahead) foe and loose ONE wedge after it; press
 *       again to send the next, at your own pace. Each swims around the mark on its own chaotic, auto-aiming
 *       path, then folds into a fast <em>piercing</em> dive clean through the body (Gungnir's bury, not a
 *       slash), chaining to a fresh foe if the mark falls, before gliding home.</li>
 *   <li><b>Shift + right-click</b> — send every ready wedge at once.</li>
 *   <li><b>Left-click</b> — loose ONE wedge dead-straight (no auto-aim); it impales and lodges INTO the
 *       first body it strikes, staying visibly stuck in them (else in a block / at the end of its reach).</li>
 *   <li><b>Shift + left-click</b> — dislodge: rip the lodged lances out for stacking damage (more wedges
 *       stabbed into one body = a bigger blow), then they fold home.</li>
 *   <li><b>Swap-hands (F)</b> — recall everything still out.</li>
 * </ul>
 *
 * <p>The wedges are lightweight {@link ItemDisplay} entities, tagged {@link #WEDGE_TAG}, non-persistent,
 * and reaped on unequip / quit / disable so no orphan is ever left in the world. The auto-aim flights skim
 * above terrain via a gentle anti-burrow clamp (never hard collision, which made them bail the instant a
 * path grazed a block); the left-click lance instead flies dead-straight and impales what it hits. Strikes
 * respect vanilla i-frames so a swarm can't stack burst damage; per-dive / per-flight hit-sets keep one pass
 * from striking the same body twice.
 */
public final class FlowerBuryingWedgeWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Flower Burying Wedge. */
    private final NamespacedKey key;

    /** Wielder -> their live wedge halo. Present only while the weapon is held. */
    private final Map<UUID, Halo> halos = new HashMap<>();

    /** Players mid-reckoning (the death-defiance cinematic): damage-immune, frozen, floating. */
    private final Set<UUID> reckoners = new HashSet<>();
    /** Where each reckoner is held floating — their movement is locked to this spot. */
    private final Map<UUID, Location> frozenAt = new HashMap<>();

    // ---- tuning (Nyrrine will tune) -----------------------------------------------
    private static final int    WEDGE_COUNT     = 8;      // eight charges = eight wedges in the halo
    private static final double TARGET_RANGE    = 24.0;   // how far the command reaches for a mark
    // Damage tuned to vanilla sword tiers, per how they're loosed:
    private static final double SORTIE_DAMAGE_SINGLE = 5.0; // right-click, one wedge at a time — a stone sword
    private static final double SORTIE_DAMAGE_ALL    = 4.0; // shift-right-click, all at once — a wooden sword each
    private static final double LANCE_DAMAGE         = 6.0; // left-click impaling lance — an iron sword
    private static final double DISLODGE_DAMAGE      = 5.0; // per lodged wedge on shift-LC dislodge (stacks per body)
    private static final long   WEDGE_RECHARGE_MS = 600L; // brief resettle after a wedge glides home
    private static final String WEDGE_TAG       = "reliquary_flower_burying_wedge";

    // ---- death-defiance passive: the sky-reckoning ---------------------------------
    static final String DEBRIS_TAG = "reliquary_fbw_debris"; // finale physics blocks (never place — see the Reckoning listener)
    private static final int    STORM_TICKS   = 300;   // 15s of raining wedges before the finale
    private static final int    SKY_HEIGHT    = 32;    // rain spawns this high above the wielder
    private static final int    RAIN_INTERVAL = 2;     // a wave every N ticks
    private static final int    RAIN_PER_WAVE = 2;     // wedges per wave (~hundreds over 15s)
    private static final int    RAIN_MAX      = 50;    // hard cap on wedges falling at once (TPS guard)
    private static final double RAIN_RADIUS   = 15.0;  // spread of impacts around the wielder
    private static final double RAIN_SPEED    = 3.1;   // FAST — hurled down like a left-click lance
    private static final int    RAIN_MAX_LIFE = 40;    // ticks before a rain wedge self-removes
    private static final double RAIN_DAMAGE   = 4.0;   // per rain-wedge impact (hundreds fall — i-frames throttle)
    private static final double RAIN_HIT_RADIUS = 2.2;
    private static final float  RAIN_SCALE    = 0.95f; // rain wedges match the (now 2x) spear size
    private static final int    RAIN_DEBRIS   = 3;     // small block-burst per rain impact (render-only)
    private static final int    RAIN_DEBRIS_LIFE = 22; // short-lived so concurrent debris stays bounded
    private static final int    GIANT_SKY     = 46;    // the finale wedge starts this high
    private static final int    GIANT_CHARGE  = 36;    // a dramatic gather before the plunge
    private static final double GIANT_FALL    = 3.4;   // plunge speed
    private static final float  GIANT_SCALE   = 7.0f;  // the massive finale wedge
    private static final double FINALE_DAMAGE = 22.0;  // the ground-strike AoE
    private static final double SHOCK_RADIUS  = 13.0;  // shockwave / debris reach
    private static final int    DEBRIS_COUNT  = 80;    // physics blocks flung by the strike
    private static final int    DEBRIS_LIFE   = 55;    // ticks before a debris block self-removes
    private static final double FLOAT_LIFT    = 2.2;   // how high the wielder floats during the cinematic
    private static final int    INTRO_TICKS   = 26;    // the spear-through-the-chest stab + rise before the storm
    private static final float  CARRY_SCALE   = 1.5f;  // big dramatic red spear that impales + carries the wielder (per the Yinglong ref)
    private static final double CARRY_Y       = 0.95;  // display height above feet — the spear crosses the CHEST (tune ±)
    private static final double CARRY_TILT    = 0.3;   // UP-lean of the spear (0 = flat/horizontal front-to-back, higher = steeper). Kept low = mostly flat through the chest

    public FlowerBuryingWedgeWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "flower_burying_wedge");
    }

    @Override
    public String id() {
        return "flower_burying_wedge";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != BusEgoModels.FLOWER_BURYING_WEDGE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(BusEgoModels.FLOWER_BURYING_WEDGE.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(WEAPON_NAME);
        meta.lore(LORE);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        BusEgoModels.stampWeapon(meta, BusEgoModels.FLOWER_BURYING_WEDGE);
        item.setItemMeta(meta);
        return item;
    }

    // ---- the halo lives only while the weapon is held ------------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Forms the halo the moment the
     * torch is drawn, wheels it behind the wielder, and reaps it (returning {@code false}) the instant the
     * weapon leaves the main hand so no wedge is left behind.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            Halo gone = halos.remove(id);
            if (gone != null) gone.dispose();
            return false;
        }
        halos.computeIfAbsent(id, k -> new Halo(this, player)).tick(player, tick);
        return true;
    }

    // ---- right-click: command the auto-aim swarm -----------------------------------

    /** Plain right-click looses one wedge per press; shift + right-click sends every ready wedge at once. */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        Halo halo = halos.computeIfAbsent(player.getUniqueId(), k -> new Halo(this, player));
        LivingEntity target = acquireTarget(player);
        if (target == null) {
            player.sendActionBar(Component.text("Flower Burying Wedge — no target").color(FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 0.7f);
            return;
        }
        int sent = halo.command(player, target, !sneaking); // plain = one-by-one, shift = all at once
        if (sent > 0) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.7f, 0.7f);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.4f);
        }
        player.sendActionBar(charges(halo));
    }

    // ---- left-click: the straight lance / dislodge ---------------------------------

    /** Left-click looses one heavy straight lance; shift + left-click dislodges the lances home. */
    @Override
    public void onSwing(Player player) {
        Halo halo = halos.computeIfAbsent(player.getUniqueId(), k -> new Halo(this, player));
        if (player.isSneaking()) {
            if (halo.dislodgeLances()) {
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.6f, 1.2f);
                player.sendActionBar(charges(halo));
            }
        } else if (halo.fireLance(player)) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 0.7f);
            player.sendActionBar(charges(halo));
        }
    }

    /** F recalls every wedge still out (auto-aim swarm and lances alike). */
    @Override
    public void onSwapHands(Player player, PlayerSwapHandItemsEvent event) {
        Halo halo = halos.get(player.getUniqueId());
        if (halo == null) return;
        if (matches(event.getMainHandItem()) || matches(event.getOffHandItem())) event.setCancelled(true);
        if (halo.recallAll()) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.6f, 1.5f);
            player.sendActionBar(charges(halo));
        }
    }

    /** The living body under the crosshair within range, else the nearest one roughly ahead of the wielder. */
    private LivingEntity acquireTarget(Player player) {
        Entity looked = player.getTargetEntity((int) TARGET_RANGE);
        if (looked instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId()) && !le.isDead()) {
            return le;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(TARGET_RANGE, TARGET_RANGE, TARGET_RANGE)) {
            if (e.getUniqueId().equals(player.getUniqueId()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            Vector to = le.getLocation().add(0, le.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.01 || dist > TARGET_RANGE) continue;
            if (to.multiply(1.0 / dist).dot(dir) < 0.5) continue; // off to the side / behind
            if (dist < bestDist) { bestDist = dist; best = le; }
        }
        return best;
    }

    private Component charges(Halo halo) {
        return Component.text("Wedges ", CRIMSON)
                .append(Component.text(halo.readyCount() + "/" + WEDGE_COUNT, FAINT));
    }

    // ---- death-defiance passive: the sky-reckoning ---------------------------------

    /**
     * The passive. A lethal blow while a Flower Burying Wedge is on the wielder consumes one and, instead of
     * dying, they come back to life. For a dramatic {@value #STORM_TICKS}-tick storm they hang in the air —
     * floating, unmoving, and untouchable — while hundreds of wedges rain from the sky around them, ending in
     * one massive wedge that plunges down and detonates the ground in red shockwaves and flying debris. The
     * {@link FlowerBuryingWedgeReckoning} listener calls this when it sees the lethal damage; a {@code true}
     * return tells it to cancel that damage.
     */
    public boolean defyDeath(Player player) {
        UUID id = player.getUniqueId();
        if (reckoners.contains(id)) return true;    // already mid-reckoning — keep surviving
        if (!consumeOneWedge(player)) return false; // nothing to spend — die as normal

        Halo h = halos.remove(id);
        if (h != null) h.dispose();                 // the halo is spent with the weapon

        reckoners.add(id);
        // Freeze them where they fell — impaled in place; the intro spear will lift them (listener locks movement).
        player.setFallDistance(0f);
        player.setGravity(false);
        frozenAt.put(id, player.getLocation());

        // Come back to life — no vanilla totem animation, our own crimson resurrection.
        player.setHealth(maxHealth(player));
        player.setFireTicks(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, STORM_TICKS + 120, 3, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, STORM_TICKS + 120, 0, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, STORM_TICKS + 120, 4, true, false, true)); // backup to the listener's immunity
        resurrectFx(player);

        new Storm(this, player).start();
        return true;
    }

    /** True while this player is in the death-defiance cinematic (the listener uses it to lock them down). */
    public boolean isReckoning(UUID id) { return reckoners.contains(id); }

    /** The spot a reckoner is frozen floating at, or null if they're not in a reckoning. */
    public Location frozenSpot(UUID id) { return frozenAt.get(id); }

    /** Update where a reckoner is held (used by the intro's rise). */
    void setFrozen(UUID id, Location loc) { if (reckoners.contains(id)) frozenAt.put(id, loc); }

    /** End a reckoning: release the wielder (movement + gravity) and forget them. Safe if they're offline. */
    void endReckoning(UUID id) {
        frozenAt.remove(id);
        if (!reckoners.remove(id)) return;
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) p.setGravity(true);
    }

    /** Remove one Flower Burying Wedge from anywhere in the inventory. Returns true if one was found. */
    private boolean consumeOneWedge(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int s = 0; s < contents.length; s++) {
            if (matches(contents[s])) {
                ItemStack it = contents[s];
                if (it.getAmount() <= 1) player.getInventory().setItem(s, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private static double maxHealth(Player player) {
        var inst = player.getAttribute(Attribute.MAX_HEALTH);
        return inst != null ? inst.getValue() : 20.0;
    }

    /** The crimson "coming back to life" burst. */
    private void resurrectFx(Player player) {
        World world = player.getWorld();
        Location c = player.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.DUST, c, 140, 0.7, 1.3, 0.7, 0, BLOOD_BIG, true);
        world.spawnParticle(Particle.DUST, c, 70, 0.5, 1.1, 0.5, 0, CORE_BIG, true);
        world.spawnParticle(Particle.EXPLOSION, c, 6, 0.5, 0.8, 0.5, 0);
        world.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 1.2f, 0.5f);
        world.playSound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 0.8f);
    }

    /** The flower-model item stamped for a display (no attribute modifiers). */
    ItemStack wedgeModelItem() {
        ItemStack model = new ItemStack(BusEgoModels.FLOWER_BURYING_WEDGE.material());
        ItemMeta m = model.getItemMeta();
        if (m != null) { BusEgoModels.stamp(m, BusEgoModels.FLOWER_BURYING_WEDGE); model.setItemMeta(m); }
        return model;
    }

    /** Spawn a free-flying wedge display (rain / finale) at {@code scale}, tagged for cleanup. */
    ItemDisplay spawnFlyingWedge(World world, Location at, Vector face, float scale) {
        return spawnFlyingWedge(world, at, face, scale, ItemDisplay.ItemDisplayTransform.FIXED);
    }

    /**
     * As above with an explicit item-display transform. The carry spear uses {@code NONE} — the model's
     * {@code display.fixed} transform bakes in a 12° roll + a translation, which reads as an unwanted
     * sideways tilt on a big single spear; NONE renders the raw model so only our aim rotation applies.
     */
    ItemDisplay spawnFlyingWedge(World world, Location at, Vector face, float scale, ItemDisplay.ItemDisplayTransform transform) {
        return world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(wedgeModelItem());
            d.setItemDisplayTransform(transform);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setTransformation(scaled(face, scale));
            d.addScoreboardTag(WEDGE_TAG);
        });
    }

    /** The wedge model posed tip-leading along {@code dir}, at an arbitrary scale. */
    static Transformation scaled(Vector dir, float scale) {
        Vector d = dir.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : dir.clone().normalize();
        return new Transformation(new Vector3f(),
                new Quaternionf().rotationTo(0, 1, 0, (float) d.getX(), (float) d.getY(), (float) d.getZ()),
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    /** Fling a burst of render-only physics blocks from {@code center} (never place — see the listener). */
    void debrisBurst(Location center, int count) { debrisBurst(center, count, DEBRIS_LIFE); }

    /** As {@link #debrisBurst(Location, int)}, with a custom lifespan (rain impacts use a short one). */
    void debrisBurst(Location center, int count, int life) {
        World world = center.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int k = 0; k < count; k++) {
            double ang = rng.nextDouble(0, Math.PI * 2);
            double rr = rng.nextDouble() * 3.0;
            Location at = center.clone().add(Math.cos(ang) * rr, 0.4, Math.sin(ang) * rr);
            BlockData bd = surfaceBlock(at);            // the REAL terrain here — no out-of-place dirt fallback
            if (bd == null) continue;                   // open air below — spawn no debris rather than fake dirt
            FallingBlock fb = world.spawnFallingBlock(at, bd);
            fb.setDropItem(false);
            fb.setPersistent(false);
            fb.getScoreboardTags().add(DEBRIS_TAG);
            double out = 0.45 + rng.nextDouble() * 0.65;
            fb.setVelocity(new Vector(Math.cos(ang) * out, 0.75 + rng.nextDouble() * 0.75, Math.sin(ang) * out));
            final FallingBlock ref = fb;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (ref.isValid()) ref.remove(); }, life);
        }
    }

    /** The nearest solid block at or a few blocks below {@code from}, or null if the column is open air. */
    private static BlockData surfaceBlock(Location from) {
        World w = from.getWorld();
        int x = from.getBlockX(), z = from.getBlockZ(), y = from.getBlockY();
        int floor = Math.max(w.getMinHeight(), y - 6);
        for (int yy = y; yy >= floor; yy--) {
            org.bukkit.block.Block b = w.getBlockAt(x, yy, z);
            if (b.getType().isSolid()) return b.getBlockData();
        }
        return null;
    }

    /** The solid ground column at or below {@code from} (capped scan). */
    static Location groundBelow(Location from) {
        World w = from.getWorld();
        int x = from.getBlockX(), z = from.getBlockZ(), y = from.getBlockY();
        int floor = Math.max(w.getMinHeight(), y - 40);
        while (y > floor && !w.getBlockAt(x, y - 1, z).getType().isSolid()) y--;
        Location l = from.clone();
        l.setY(y);
        return l;
    }

    // ---- lifecycle: never orphan an entity ----------------------------------------

    @Override
    public void onQuit(UUID id) {
        Halo h = halos.remove(id);
        if (h != null) h.dispose();
        endReckoning(id); // release float/gravity while they're still online
    }

    @Override
    public void onDisable() {
        for (Halo h : halos.values()) h.dispose();
        halos.clear();
        for (UUID id : new HashSet<>(reckoners)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) p.setGravity(true);
        }
        reckoners.clear();
        frozenAt.clear();
        // belt-and-braces: reap any stray tagged wedge / debris anywhere in the world (crash/reload safety)
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(WEDGE_TAG)) e.remove();
            }
            for (Entity e : w.getEntitiesByClass(FallingBlock.class)) {
                if (e.getScoreboardTags().contains(DEBRIS_TAG)) e.remove();
            }
        }
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Halo> e : halos.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            out.add("flower_burying_wedge  " + e.getValue().readyCount() + "/" + WEDGE_COUNT + " wedges  (" + who + ")");
        }
        return out;
    }

    // ---- the halo: eight wedges wheeling behind the wielder ------------------------

    /**
     * A wielder's ring of eight wedge {@link ItemDisplay} companions. Each wedge is either <b>home</b>
     * (hovering in the spinning halo behind the wielder, angled outward like a petal) or out (a self-driving
     * flight owns it — an auto-aim sortie or a straight lance). Idle-and-off-cooldown wedges are "ready".
     */
    private static final class Halo {

        // Halo geometry — a spinning vertical disc close behind the wielder's back; petals point outward.
        private static final double HALO_RADIUS  = 1.5;    // ring radius — closer in than the last pass, still clears the 2x spears
        private static final double HALO_BACK    = 1.05;   // how far behind the back the disc sits
        private static final double HALO_HEIGHT  = 1.25;   // disc centre height off the feet
        private static final double HALO_SPIN    = 0.045;  // radians/tick the whole disc rotates
        private static final float  WEDGE_SCALE  = 0.9f;   // 2x bigger — a chunky raid-boss spear (~2.6 blocks)

        // Auto-aim sortie feel. Blocks / blocks-per-tick.
        private static final int    STRIKES_PER_SORTIE = 4;    // dives a wedge makes before folding home
        private static final int    ORBIT_TICKS_MIN    = 12;   // chaotic circling before a dive (jittered per wedge)
        private static final int    ORBIT_TICKS_VAR    = 10;
        private static final double ORBIT_MOVE         = 0.95;  // travel toward the wheeling orbit point
        private static final double ORBIT_RADIUS_MIN   = 5.5;  // WIDE raid-boss circling — all eight sweep around
        private static final double ORBIT_RADIUS_VAR   = 3.5;
        private static final double DIVE_SPEED         = 2.2;  // the fast piercing lunge
        private static final double DIVE_HOMING        = 0.35; // per-tick re-aim toward the (moving) mark
        private static final int    DIVE_TICKS         = 13;   // safety cap on a dive (longer for the wider orbit)
        private static final double PIERCE_OVERSHOOT   = 3.0;  // drive this far PAST the body — a bury-through
        private static final double HIT_RADIUS         = 1.5;  // a body this near the wedge's path is pierced
        private static final double CHAIN_RANGE        = 18.0; // re-lock a fresh foe within this if the mark falls

        // Straight lance feel (left-click).
        private static final double LANCE_SPEED        = 2.5;  // blocks/tick dead-straight
        private static final double LANCE_RANGE        = 40.0; // how far it flies before lodging
        private static final double LANCE_HIT_RADIUS   = 2.3;  // pierce radius along the lance path — a forgiving hitbox
        private static final int    LANCE_LODGE_TIMEOUT = 1200; // ~60s lodged, then it comes home on its own
        private static final long   LANCE_FIRE_CD_MS   = 250L; // gap between left-click lances (deliberate, not a spray)
        private static final int    RETURN_MAX         = 100;  // safety: force a returning wedge home after ~5s

        private final FlowerBuryingWedgeWeapon weapon;
        private final Reliquary plugin;
        private final UUID ownerId;

        private final ItemDisplay[] wedges = new ItemDisplay[WEDGE_COUNT];
        private final boolean[] home       = new boolean[WEDGE_COUNT]; // in the halo (vs out on a flight)
        private final boolean[] busy        = new boolean[WEDGE_COUNT]; // a flight animation owns it
        private final boolean[] lance       = new boolean[WEDGE_COUNT]; // out as a straight lance (dislodgeable)
        private final UUID[] lodgedIn       = new UUID[WEDGE_COUNT];    // body a lance is impaled in (null = block/none)
        private final long[] readyAt        = new long[WEDGE_COUNT];    // brief resettle after gliding home
        // Per-wedge recall signal (F / shift-LC). MUST be per-wedge, not a global flag: a global flag only
        // clears when every wedge is home, but launching a new wedge makes one busy — so the flag would
        // stick on forever and every freshly-thrown wedge would immediately turn back. A launch clears its
        // own slot's flag, so a new throw never inherits a stale recall.
        private final boolean[] recall = new boolean[WEDGE_COUNT];
        private long lanceFireReadyAt = 0L;
        private boolean alive = true;

        Halo(FlowerBuryingWedgeWeapon weapon, Player owner) {
            this.weapon = weapon;
            this.plugin = weapon.plugin;
            this.ownerId = owner.getUniqueId();
            Location c = haloCenter(owner);
            for (int i = 0; i < WEDGE_COUNT; i++) {
                wedges[i] = spawnWedge(owner.getWorld(), c);
                home[i] = true;
            }
        }

        private ItemDisplay spawnWedge(World world, Location at) {
            return world.spawn(at, ItemDisplay.class, d -> {
                ItemStack model = new ItemStack(BusEgoModels.FLOWER_BURYING_WEDGE.material());
                ItemMeta m = model.getItemMeta();
                if (m != null) {
                    BusEgoModels.stamp(m, BusEgoModels.FLOWER_BURYING_WEDGE); // model only — no attributes on a display
                    model.setItemMeta(m);
                }
                d.setItemStack(model);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15)); // self-lit crimson, readable in the dark
                d.setPersistent(false);          // a crash can never leave these on disk
                d.setInterpolationDuration(3);
                d.setTeleportDuration(3);         // smooth the glide between ticks (snapped shorter for a dive)
                d.setTransformation(pointing(new Vector(0, 1, 0)));
                d.addScoreboardTag(WEDGE_TAG);
            });
        }

        // ---- the resting halo -----------------------------------------------------

        /** Wheel the home wedges into their spinning petal ring; leave flying ones to their flights. */
        void tick(Player owner, long tick) {
            if (!alive) return;
            Location center = haloCenter(owner);
            double spin = tick * HALO_SPIN;
            drawWreath(owner, spin);                       // the Yinglong intestine wreath, its own loop behind the player
            for (int i = 0; i < WEDGE_COUNT; i++) {
                ItemDisplay w = wedges[i];
                if (w == null || w.isDead() || !w.isValid()) {
                    if (home[i] && !busy[i]) { w = spawnWedge(owner.getWorld(), center); wedges[i] = w; }
                    else continue;
                }
                if (busy[i] || !home[i]) continue;         // a flight owns this wedge
                Location pos = petalSlot(owner, center, i, spin);
                Vector out = pos.toVector().subtract(center.toVector());
                // Smooth the spin: interpolate BOTH the position and the outward-facing transform over the
                // 2-tick tick gap, so the rotation glides instead of snapping (was jittering).
                w.setInterpolationDelay(0);
                w.setInterpolationDuration(2);
                w.setTeleportDuration(2);
                w.setTransformation(pointing(out.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : out.normalize()));
                w.teleport(pos);
                if ((tick % 22) == 0) { // an occasional crimson ember weeping off a hovering petal
                    w.getWorld().spawnParticle(Particle.DUST, w.getLocation(), 1, 0.04, 0.04, 0.04, 0, EMBER);
                }
            }
        }

        /**
         * The Yinglong intestine wreath: a flowing coiled rope forming its OWN loop behind the wielder (a
         * halo behind the head, not wrapped around the blades). A helix coils around the ring tube and flows
         * as {@code spin} advances; the ring gently undulates; the colours run fleshy red with pale-pink
         * (white) highlights so it reads as coiled gut.
         */
        private void drawWreath(Player owner, double spin) {
            World world = owner.getWorld();
            double rad = Math.toRadians(owner.getLocation().getYaw());
            Vector right = new Vector(Math.cos(rad), 0, Math.sin(rad));
            Vector up = new Vector(0, 1, 0);
            Vector fwd = new Vector(-Math.sin(rad), 0, Math.cos(rad)); // player forward
            // A SHAWL draped behind the shoulders and up behind the head — set CLOSE to the body so it reads
            // as worn (the blade halo floats further back, BEHIND this). Only the top arc is drawn: open at
            // the bottom/front, like a collar, not a full ring.
            Location wc = owner.getLocation().clone().subtract(fwd.clone().multiply(0.42)).add(0, 1.42, 0);
            final double ringR = 0.9, tube = 0.22;
            final int seg = 36;
            for (int i = 0; i < seg; i++) {
                double a = (Math.PI * 2 * i) / seg;
                if (Math.sin(a) < -0.45) continue;                         // skip the bottom/front — a shawl, not a full loop
                double rr = ringR + 0.08 * Math.sin(a * 2.0 + spin * 0.6); // gentle, flowy undulation
                Vector radial = right.clone().multiply(Math.cos(a)).add(up.clone().multiply(Math.sin(a)));
                Location base = wc.clone().add(radial.clone().multiply(rr));
                double coilA = a * 7.0 - spin * 1.6;                        // the coil flows along the drape
                Vector off = radial.clone().multiply(Math.cos(coilA) * tube)
                        .add(fwd.clone().multiply(Math.sin(coilA) * tube));
                Location p = base.clone().add(off);
                Particle.DustOptions col = (i % 6 == 0) ? WREATH_WHITE : ((i % 2 == 0) ? WREATH : WREATH_DARK);
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, col);
            }
        }

        /** Centre of the halo disc — behind the wielder's back at hover height. */
        private static Location haloCenter(Player owner) {
            Location base = owner.getLocation();
            double rad = Math.toRadians(base.getYaw());
            Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            return base.clone().subtract(forward.multiply(HALO_BACK)).add(0, HALO_HEIGHT, 0);
        }

        /** The petal position for wedge {@code i} on the spinning disc (plane = wielder's right × up). */
        private Location petalSlot(Player owner, Location center, int i, double spin) {
            double rad = Math.toRadians(owner.getLocation().getYaw());
            Vector right = new Vector(Math.cos(rad), 0, Math.sin(rad));
            Vector up = new Vector(0, 1, 0);
            double ang = spin + (Math.PI * 2 * i) / WEDGE_COUNT;
            Vector offset = right.clone().multiply(Math.cos(ang) * HALO_RADIUS)
                    .add(up.clone().multiply(Math.sin(ang) * HALO_RADIUS));
            Location pos = center.clone().add(offset);
            pos.setYaw(0f); pos.setPitch(0f);
            return pos;
        }

        // ---- command: auto-aim, one per click or all at once ----------------------

        /**
         * Loose wedges at the mark. Plain right-click ({@code single}) sends exactly ONE ready wedge — press
         * again to loose the next, at your own pace; shift + right-click sends every ready wedge at once.
         * Returns how many were sent.
         */
        int command(Player owner, LivingEntity mark, boolean single) {
            if (!alive) return 0;
            long now = System.currentTimeMillis();
            if (single) {
                int idx = firstReady(now);
                if (idx < 0) return 0;
                launchSortie(owner, idx, mark, SORTIE_DAMAGE_SINGLE, true); // a stone sword per swat
                return 1;
            }
            int sent = 0;
            // Shift-RC: launch all at once but play ONE launch cue (8 stacked sounds were far too loud).
            for (int i = 0; i < WEDGE_COUNT; i++) if (ready(i, now)) { launchSortie(owner, i, mark, SORTIE_DAMAGE_ALL, false); sent++; }
            if (sent > 0) sfxLaunch(owner.getLocation());
            return sent;
        }

        /** One wedge's auto-aim sortie: chaotic wide orbit -> piercing dive -> repeat, chaining, then home. */
        private void launchSortie(Player owner, final int i, LivingEntity firstMark, final double strikeDmg, boolean sfx) {
            final ItemDisplay w = wedges[i];
            if (w == null || !w.isValid()) { home[i] = false; return; }
            home[i] = false;
            busy[i] = true;
            recall[i] = false;   // a fresh throw ignores any stale recall
            if (sfx) sfxLaunch(owner.getLocation());

            final ThreadLocalRandom rng = ThreadLocalRandom.current();
            final double orbitPhase  = rng.nextDouble(0, Math.PI * 2);
            final double orbitSpeed  = (rng.nextBoolean() ? 1 : -1) * (0.30 + rng.nextDouble() * 0.28);
            final double orbitRadius = ORBIT_RADIUS_MIN + rng.nextDouble() * ORBIT_RADIUS_VAR;
            final Vector axisA = randomUnit(rng);
            final Vector axisB = axisA.clone().crossProduct(randomUnit(rng)).normalize();

            new BukkitRunnable() {
                int phase = 0;            // 0 = ORBIT, 1 = DIVE, 2 = RETURN
                int t = 0;
                int strikes = 0;
                int retTicks = 0;
                int orbitTarget = ORBIT_TICKS_MIN + rng.nextInt(ORBIT_TICKS_VAR + 1);
                LivingEntity mark = firstMark;
                Vector diveDir = new Vector(0, 0, 1);
                Vector diveStart = null;
                final Set<UUID> hitThisDive = new HashSet<>();

                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || w.isDead() || !w.isValid() || p == null || !p.isOnline()) {
                        retire(i, w); cancel(); return;
                    }
                    if (recall[i]) phase = 2;
                    Location cur = w.getLocation();

                    if (phase != 2 && (mark == null || mark.isDead() || !mark.isValid())) {
                        mark = nearestFoe(cur, CHAIN_RANGE);
                        if (mark == null) phase = 2;
                    }

                    if (phase == 0) {                              // ---- ORBIT: chaotic wide auto-aim circling ----
                        Location center = mark.getLocation().add(0, mark.getHeight() * 0.55, 0);
                        double a = orbitPhase + t * orbitSpeed;
                        Vector ring = axisA.clone().multiply(Math.cos(a) * orbitRadius)
                                .add(axisB.clone().multiply(Math.sin(a) * orbitRadius));
                        Location orbitPt = center.clone().add(ring);
                        Vector to = orbitPt.toVector().subtract(cur.toVector());
                        double d = to.length();
                        Vector step = d < 1.0e-4 ? new Vector(0, 0, 1) : to.clone().multiply(Math.min(d, ORBIT_MOVE) / d);
                        move(w, aboveGround(cur.clone().add(step)), step, 2);
                        thickTrail(cur, step);
                        if (++t >= orbitTarget) {                  // commit to the dive
                            phase = 1; t = 0;
                            hitThisDive.clear();
                            diveStart = cur.toVector();
                            diveDir = center.toVector().subtract(cur.toVector());
                            if (diveDir.lengthSquared() < 1.0e-6) diveDir = new Vector(0, 0, 1);
                            diveDir.normalize();
                            w.setTeleportDuration(1);
                            w.getWorld().playSound(cur, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.35f, 1.45f); // a quiet diving whoosh
                            w.getWorld().playSound(cur, Sound.ENTITY_BREEZE_SHOOT, 0.25f, 1.3f);
                        }
                        return;
                    }

                    if (phase == 1) {                              // ---- DIVE: piercing bury-through ----
                        Location center = mark.getLocation().add(0, mark.getHeight() * 0.55, 0);
                        Vector want = center.toVector().subtract(cur.toVector());
                        if (want.lengthSquared() > 1.0e-6) {
                            diveDir.multiply(1 - DIVE_HOMING).add(want.normalize().multiply(DIVE_HOMING));
                            if (diveDir.lengthSquared() < 1.0e-6) diveDir = new Vector(0, 0, 1);
                            diveDir.normalize();
                        }
                        Location next = aboveGround(cur.clone().add(diveDir.clone().multiply(DIVE_SPEED)));
                        move(w, next, diveDir, 1);
                        thickTrail(cur, diveDir);
                        pierce(p, next, HIT_RADIUS, strikeDmg, hitThisDive, false);

                        boolean overshot = diveStart != null
                                && next.toVector().subtract(diveStart).dot(diveDir) >= diveStart.distance(center.toVector()) + PIERCE_OVERSHOOT;
                        if (overshot || ++t >= DIVE_TICKS) {
                            strikes++;
                            if (strikes >= STRIKES_PER_SORTIE) phase = 2;
                            else { phase = 0; t = 0; orbitTarget = ORBIT_TICKS_MIN + rng.nextInt(ORBIT_TICKS_VAR + 1);
                                   w.setTeleportDuration(3); }
                        }
                        return;
                    }

                    if (returnHome(w, cur, p, i) || ++retTicks > RETURN_MAX) {
                        if (!home[i]) retire(i, w);   // timed out short of home — force it back
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        // ---- the straight lance (left-click) --------------------------------------

        /** A foe already roughly under the crosshair (narrow cone), for the lance's tiny aim assist. */
        private LivingEntity aimAssist(Player owner, Vector aim) {
            Location eye = owner.getEyeLocation();
            LivingEntity best = null;
            double bestDot = 0.82; // ~35° cone — a wide, forgiving grab
            for (Entity e : owner.getNearbyEntities(28, 28, 28)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || le.getUniqueId().equals(owner.getUniqueId())) continue;
                Vector to = le.getLocation().add(0, le.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
                double dist = to.length();
                if (dist < 0.5) continue;
                double dot = to.multiply(1.0 / dist).dot(aim);
                if (dot > bestDot) { bestDot = dot; best = le; }
            }
            return best;
        }

        /** Loose ONE ready wedge as a heavy, near-straight lance (tiny aim assist). Returns true if one fired. */
        boolean fireLance(Player owner) {
            if (!alive) return false;
            long now = System.currentTimeMillis();
            if (now < lanceFireReadyAt) return false;
            int idx = firstReady(now);
            if (idx < 0) return false;
            lanceFireReadyAt = now + LANCE_FIRE_CD_MS;
            launchLance(owner, idx);
            return true;
        }

        private void launchLance(Player owner, final int i) {
            final ItemDisplay w = wedges[i];
            if (w == null || !w.isValid()) { home[i] = false; return; }
            home[i] = false;
            busy[i] = true;
            lance[i] = true;
            recall[i] = false;   // a fresh throw ignores any stale recall

            Vector aim = owner.getEyeLocation().getDirection().normalize();
            LivingEntity assist = aimAssist(owner, aim);      // aim assist — snap toward a foe near the crosshair
            if (assist != null) {
                Vector toT = assist.getLocation().add(0, assist.getHeight() * 0.5, 0).toVector()
                        .subtract(owner.getEyeLocation().toVector()).normalize();
                aim = aim.multiply(0.5).add(toT.multiply(0.5)).normalize(); // 50% snap — noticeable, still not a hard lock
            }
            final Vector dir = aim;
            final Location muzzle = owner.getEyeLocation().add(dir.clone().multiply(1.2));
            w.setTeleportDuration(0);
            w.setTransformation(pointing(dir));
            w.teleport(muzzle);
            sfxLaunch(muzzle);

            final ThreadLocalRandom rng = ThreadLocalRandom.current();
            // A small per-wedge offset so several wedges stabbed into one body fan out instead of overlapping.
            final Vector lodgeOffset = new Vector((rng.nextDouble() - 0.5) * 0.5,
                    (rng.nextDouble() - 0.5) * 0.6, (rng.nextDouble() - 0.5) * 0.5);

            new BukkitRunnable() {
                int phase = 0;   // 0 = FLY, 1 = LODGED, 2 = RETURN
                int lodgeTicks = 0;
                int retTicks = 0;
                LivingEntity lodged = null; // the body this lance is impaled in, if any

                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (!alive || w.isDead() || !w.isValid() || p == null || !p.isOnline()) {
                        retire(i, w); cancel(); return;
                    }
                    if (recall[i]) phase = 2;
                    Location cur = w.getLocation();

                    if (phase == 0) {                              // ---- FLY: dead-straight impaling strike ----
                        // NO skim/bounce (that's reserved for the right-click swarm). It flies dead-straight
                        // and lodges INTO the first body it hits — staying visibly stuck in them — else in the
                        // block it strikes, else at the end of its reach.
                        World world = cur.getWorld();
                        Location next = cur.clone().add(dir.clone().multiply(LANCE_SPEED));
                        LivingEntity body = nearestFoe(next, LANCE_HIT_RADIUS + 0.4);
                        if (body != null) {                        // impale + lodge into the body
                            body.damage(LANCE_DAMAGE, p);          // an iron-sword impale
                            Location chest = body.getLocation().add(0, body.getHeight() * 0.55, 0);
                            powerStrikeFx(chest);
                            lodged = body;
                            lodgedIn[i] = body.getUniqueId();
                            move(w, chest.clone().add(lodgeOffset), dir, 2);
                            phase = 1;
                            return;
                        }
                        RayTraceResult rt = world.rayTraceBlocks(cur, dir, LANCE_SPEED, FluidCollisionMode.NEVER, true);
                        if (rt != null && rt.getHitBlock() != null) { // lodge in the block
                            Location at = rt.getHitPosition().toLocation(world).subtract(dir.clone().multiply(0.2));
                            move(w, at, dir, 1);
                            powerStrikeFx(at);
                            phase = 1;
                            return;
                        }
                        move(w, next, dir, 1);
                        thickTrail(cur, dir);
                        if (cur.distance(muzzle) >= LANCE_RANGE) { powerStrikeFx(w.getLocation()); phase = 1; }
                        return;
                    }

                    if (phase == 1) {                              // ---- LODGED: stuck in the body/block ----
                        if (lodged != null) {
                            if (lodged.isDead() || !lodged.isValid()) { lodged = null; lodgedIn[i] = null; }
                            else {                                 // stay impaled in the body, following it
                                Location chest = lodged.getLocation().add(0, lodged.getHeight() * 0.55, 0).add(lodgeOffset);
                                chest.setYaw(0); chest.setPitch(0);
                                if (w.getTeleportDuration() != 2) w.setTeleportDuration(2);
                                w.setTransformation(pointing(dir));
                                w.teleport(chest);
                            }
                        }
                        if (++lodgeTicks >= LANCE_LODGE_TIMEOUT) phase = 2;
                        if ((lodgeTicks % 12) == 0) {              // a faint crimson smoulder while lodged
                            w.getWorld().spawnParticle(Particle.DUST, w.getLocation(), 2, 0.06, 0.06, 0.06, 0, EMBER, true);
                        }
                        return;
                    }

                    if (returnHome(w, cur, p, i) || ++retTicks > RETURN_MAX) {
                        if (!home[i]) retire(i, w);   // timed out short of home — force it back
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        /**
         * Dislodge (shift + left-click): rip every lodged lance out, dealing STACKING damage — a body with
         * more wedges stabbed into it takes {@code count ×} the dislodge damage, all in one blow — then the
         * lances fold home. (Plain F just recalls them, no dislodge damage.)
         */
        boolean dislodgeLances() {
            if (!alive) return false;
            Map<UUID, Integer> perBody = new HashMap<>();
            for (int i = 0; i < WEDGE_COUNT; i++) if (lance[i] && lodgedIn[i] != null) perBody.merge(lodgedIn[i], 1, Integer::sum);
            Player owner = plugin.getServer().getPlayer(ownerId);
            for (Map.Entry<UUID, Integer> e : perBody.entrySet()) {
                Entity ent = plugin.getServer().getEntity(e.getKey());
                if (ent instanceof LivingEntity le && !le.isDead() && le.isValid()) {
                    le.damage(DISLODGE_DAMAGE * e.getValue(), owner); // stacks with how many are stuck in
                    powerStrikeFx(le.getLocation().add(0, le.getHeight() * 0.55, 0));
                }
            }
            boolean any = false;
            for (int i = 0; i < WEDGE_COUNT; i++) if (lance[i]) { recall[i] = true; any = true; }
            return any;
        }

        // ---- shared flight helpers ------------------------------------------------

        /**
         * Glide a returning wedge straight toward the halo (no terrain collision — a homing-in wedge may
         * phase through the ground on its way back, which is fine and avoids getting stuck against the
         * player's own footing). Returns {@code true} once it has arrived and been retired, so the caller
         * MUST cancel its flight task — otherwise the task keeps re-retiring every tick (sound spam +
         * a wedge that never becomes ready again).
         */
        private boolean returnHome(ItemDisplay w, Location cur, Player p, int i) {
            Location slot = haloCenter(p);
            Vector to = slot.toVector().subtract(cur.toVector());
            double d = to.length();
            if (d <= ORBIT_MOVE * 1.6) { sfxReturn(cur); retire(i, w); return true; }
            Vector step = to.multiply(Math.min(d, DIVE_SPEED * 0.5) / d);
            move(w, cur.clone().add(step), step, 2);
            thickTrail(cur, step);
            return false;
        }

        /** Retire a wedge back into the halo: home, off its brief resettle cooldown, not a lance. */
        private void retire(int i, ItemDisplay w) {
            home[i] = true;
            busy[i] = false;
            lance[i] = false;
            recall[i] = false;
            lodgedIn[i] = null;
            readyAt[i] = System.currentTimeMillis() + WEDGE_RECHARGE_MS;
            if (w != null && w.isValid()) w.setTeleportDuration(3);
        }

        /** Pierce every unstruck body within {@code radius} of the wedge tip (a line of foes). */
        private void pierce(Player owner, Location tip, double radius, double dmg, Set<UUID> hitSet, boolean powerful) {
            for (Entity e : tip.getWorld().getNearbyEntities(tip, radius, radius, radius)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || le.getUniqueId().equals(ownerId)) continue;
                if (!hitSet.add(le.getUniqueId())) continue;   // once per pass per body
                // NB: vanilla i-frames are respected on purpose — a whole swarm diving one body can't stack
                // burst damage; only ~2 blows/sec land, each ~an iron-sword swat.
                le.damage(dmg, owner);
                Location at = le.getLocation().add(0, le.getHeight() * 0.55, 0);
                if (powerful) powerStrikeFx(at); else pierceFx(at);
            }
        }

        /** Nearest living foe to {@code from} within {@code range} (skips the wielder). */
        private LivingEntity nearestFoe(Location from, double range) {
            LivingEntity best = null;
            double bestSq = range * range;
            for (Entity e : from.getWorld().getNearbyEntities(from, range, range, range)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || le.getUniqueId().equals(ownerId)) continue;
                double sq = le.getLocation().distanceSquared(from);
                if (sq < bestSq) { bestSq = sq; best = le; }
            }
            return best;
        }

        // ---- ready / recall bookkeeping -------------------------------------------

        private boolean ready(int i, long now) { return home[i] && !busy[i] && now >= readyAt[i]; }

        private int firstReady(long now) {
            for (int i = 0; i < WEDGE_COUNT; i++) if (ready(i, now)) return i;
            return -1;
        }

        /** Fold everything out (swarm and lances) home. Returns true if any were out. */
        boolean recallAll() {
            if (!alive) return false;
            boolean any = false;
            for (int i = 0; i < WEDGE_COUNT; i++) if (!home[i] || busy[i]) { recall[i] = true; any = true; }
            return any;
        }

        int readyCount() {
            long now = System.currentTimeMillis();
            int n = 0;
            for (int i = 0; i < WEDGE_COUNT; i++) if (ready(i, now)) n++;
            return n;
        }

        void dispose() {
            alive = false;
            for (int i = 0; i < WEDGE_COUNT; i++) {
                if (wedges[i] != null) { wedges[i].remove(); wedges[i] = null; }
            }
        }

        // ---- motion / vfx ---------------------------------------------------------

        /** Teleport a wedge to {@code to}, tip-leading along {@code face}, at the given interp cadence. */
        private static void move(ItemDisplay w, Location to, Vector face, int teleportDur) {
            if (w.getTeleportDuration() != teleportDur) w.setTeleportDuration(teleportDur);
            w.setTransformation(pointing(face.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : face.clone().normalize()));
            to.setYaw(0f); to.setPitch(0f);
            w.teleport(to);
        }

        /**
         * A gentle anti-burrow clamp (NOT hard collision): if {@code loc} sits inside solid terrain, nudge
         * it up to just above the surface so a wedge skims over the ground instead of sinking into it. It
         * never stops or shortens a flight (the old ray-trace collision did, which made wedges bail the
         * instant their path grazed a block); passable blocks like grass don't count.
         */
        private static Location aboveGround(Location loc) {
            if (!loc.getBlock().getType().isSolid()) return loc;
            Location up = loc.clone();
            for (int k = 0; k < 4 && up.getBlock().getType().isSolid(); k++) up.add(0, 1, 0);
            return up.add(0, 0.05, 0);
        }

        /** A thick, dramatic crimson trail: a bright core, a blood-red body, and a dark shroud ring. */
        private static void thickTrail(Location at, Vector dir) {
            World world = at.getWorld();
            world.spawnParticle(Particle.DUST, at, 2, 0.04, 0.04, 0.04, 0, CORE, true);
            world.spawnParticle(Particle.DUST, at, 3, 0.12, 0.12, 0.12, 0, BLOOD, true);
            Vector d = dir.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : dir.clone().normalize();
            Vector perp = d.clone().crossProduct(new Vector(0, 1, 0));
            if (perp.lengthSquared() < 1.0e-6) perp = new Vector(1, 0, 0);
            perp.normalize().multiply(0.26);
            Vector perp2 = d.clone().crossProduct(perp).normalize().multiply(0.26);
            world.spawnParticle(Particle.DUST, at.clone().add(perp), 1, 0, 0, 0, 0, DARK, true);
            world.spawnParticle(Particle.DUST, at.clone().subtract(perp), 1, 0, 0, 0, 0, DARK, true);
            world.spawnParticle(Particle.DUST, at.clone().add(perp2), 1, 0, 0, 0, 0, DARK, true);
            world.spawnParticle(Particle.DUST, at.clone().subtract(perp2), 1, 0, 0, 0, 0, DARK, true);
        }

        /** The auto-aim bury-through: a crimson puncture burst driven into the pierced body. */
        private static void pierceFx(Location at) {
            World world = at.getWorld();
            world.spawnParticle(Particle.DUST, at, 16, 0.28, 0.32, 0.28, 0, BLOOD, true);
            world.spawnParticle(Particle.DUST, at, 8, 0.18, 0.22, 0.18, 0, CORE, true);
            world.spawnParticle(Particle.CRIT, at, 10, 0.22, 0.28, 0.22, 0.12);
            world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.18f, 1.8f);  // a quiet, pitched-up mace thunk
        }

        /** The lance's heavy strike — a big crimson blast every time it drives through something. */
        private static void powerStrikeFx(Location at) {
            World world = at.getWorld();
            world.spawnParticle(Particle.DUST, at, 40, 0.45, 0.5, 0.45, 0, BLOOD, true);
            world.spawnParticle(Particle.DUST, at, 22, 0.3, 0.35, 0.3, 0, CORE, true);
            world.spawnParticle(Particle.DUST, at, 18, 0.5, 0.55, 0.5, 0, DARK, true);
            world.spawnParticle(Particle.CRIT, at, 24, 0.35, 0.4, 0.35, 0.25);
            world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.28f, 1.5f);  // heavier, still-quiet pitched-up mace
        }

        /** A satisfying launch flourish as a wedge peels off the halo — a whoosh, a metallic zing, a gust. */
        private static void sfxLaunch(Location at) {
            World w = at.getWorld();
            w.playSound(at, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.4f, 1.25f);
            w.playSound(at, Sound.BLOCK_CHAIN_HIT, 0.3f, 1.5f);
            w.playSound(at, Sound.ENTITY_BREEZE_SHOOT, 0.3f, 0.9f);
        }

        /** A wedge folding home — a soft recall whoosh and an amethyst chime. */
        private static void sfxReturn(Location at) {
            World w = at.getWorld();
            w.playSound(at, Sound.ITEM_TRIDENT_RETURN, 0.5f, 1.4f);
            w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.7f);
        }

        private static Vector randomUnit(ThreadLocalRandom rng) {
            double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian();
            Vector v = new Vector(x, y, z);
            return v.lengthSquared() < 1.0e-6 ? new Vector(0, 1, 0) : v.normalize();
        }

        /** The wedge model posed tip-leading along {@code dir} (its long axis is the model's local +Y). */
        private static Transformation pointing(Vector dir) {
            return new Transformation(new Vector3f(),
                    new Quaternionf().rotationTo(0, 1, 0, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ()),
                    new Vector3f(WEDGE_SCALE, WEDGE_SCALE, WEDGE_SCALE), new Quaternionf());
        }
    }

    // ---- the sky-reckoning storm --------------------------------------------------

    /**
     * The death-defiance cinematic: {@value #STORM_TICKS} ticks of wedges raining from the sky around the
     * floating, frozen, immune wielder, then a single massive wedge that plunges down and detonates the
     * ground in a red shockwave and flying debris. All spawned displays carry {@link #WEDGE_TAG} and the
     * debris {@link #DEBRIS_TAG}, so the weapon's disable sweep reaps anything a crash leaves behind.
     */
    private static final class Storm {
        private final FlowerBuryingWedgeWeapon weapon;
        private final Reliquary plugin;
        private final UUID ownerId;
        private final List<RainWedge> rain = new ArrayList<>();
        private ItemDisplay carry; // the spear driven through the chest, carrying the wielder aloft

        private static final class RainWedge {
            final ItemDisplay d; final Vector dir; int life;
            RainWedge(ItemDisplay d, Vector dir) { this.d = d; this.dir = dir; }
        }

        Storm(FlowerBuryingWedgeWeapon weapon, Player owner) {
            this.weapon = weapon;
            this.plugin = weapon.plugin;
            this.ownerId = owner.getUniqueId();
        }

        void start() {
            Player p0 = plugin.getServer().getPlayer(ownerId);
            Location spot = weapon.frozenSpot(ownerId);
            if (p0 == null || spot == null) { weapon.endReckoning(ownerId); return; }
            final Location deathSpot = spot.clone();
            World world = p0.getWorld();
            // ONE spear drives DIAGONALLY through the chest (forward-lean, tip up-and-out the front) — this is
            // what raises them. Orientation is fixed at spawn so it doesn't swing when they move the camera.
            double yaw = Math.toRadians(deathSpot.getYaw());
            Vector fwd = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)); // unit horizontal forward
            // Mostly HORIZONTAL front-to-back through the chest, tilted up only slightly (CARRY_TILT).
            Vector carryDir = fwd.clone().add(new Vector(0, CARRY_TILT, 0)).normalize();
            carry = weapon.spawnFlyingWedge(world, deathSpot.clone().add(0, CARRY_Y, 0), carryDir, CARRY_SCALE,
                    ItemDisplay.ItemDisplayTransform.NONE); // NONE = no baked 12° tilt
            stabFx(deathSpot.clone().add(0, 1.1, 0)); // the blood burst sits at the actual chest

            new BukkitRunnable() {
                int phase = 0; // 0 = INTRO (impale + rise), 1 = STORM
                int t = 0;
                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (p == null || !p.isOnline()) { clearRain(); dropCarry(); weapon.endReckoning(ownerId); cancel(); return; }

                    if (phase == 0) {                              // ---- INTRO: the spear lifts them aloft ----
                        t++;
                        double frac = Math.min(1.0, t / (double) INTRO_TICKS);
                        Location lift = deathSpot.clone().add(0, FLOAT_LIFT * frac, 0);
                        weapon.setFrozen(ownerId, lift);
                        p.setVelocity(new Vector(0, 0, 0));
                        p.setFallDistance(0f);
                        Location keep = lift.clone();
                        keep.setYaw(p.getLocation().getYaw());
                        keep.setPitch(p.getLocation().getPitch());
                        p.teleport(keep);
                        if (carry != null && carry.isValid()) carry.teleport(lift.clone().add(0, CARRY_Y, 0));
                        world.spawnParticle(Particle.DUST, lift.clone().add(0, 1.0, 0), 6, 0.3, 0.55, 0.3, 0, BLOOD, true);
                        if (t >= INTRO_TICKS) { phase = 1; t = 0; }
                        return;
                    }

                    // ---- STORM ----
                    holdFloat(p);
                    Location f = weapon.frozenSpot(ownerId);
                    if (carry != null && carry.isValid() && f != null) carry.teleport(f.clone().add(0, CARRY_Y, 0));
                    if (t >= STORM_TICKS) { clearRain(); dropCarry(); cancel(); finale(p); return; }
                    if (t % RAIN_INTERVAL == 0 && rain.size() < RAIN_MAX) {
                        for (int k = 0; k < RAIN_PER_WAVE; k++) spawnRain(p);
                    }
                    tickRain(p);
                    if (t % 8 == 0) p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.3f, 0.5f);
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        /** The spear driving through the chest — a heavy impale burst and a wet crunch. */
        private void stabFx(Location chest) {
            World world = chest.getWorld();
            world.spawnParticle(Particle.DUST, chest, 46, 0.28, 0.45, 0.28, 0, BLOOD_BIG, true);
            world.spawnParticle(Particle.DUST, chest, 22, 0.2, 0.35, 0.2, 0, CORE_BIG, true);
            world.spawnParticle(Particle.CRIT, chest, 22, 0.22, 0.35, 0.22, 0.15);
            world.playSound(chest, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.8f, 0.9f);
            world.playSound(chest, Sound.ITEM_TRIDENT_HIT, 0.8f, 0.6f);
        }

        private void dropCarry() {
            if (carry != null) { if (carry.isValid()) carry.remove(); carry = null; }
        }

        /** Keep the wielder pinned floating (belt to the listener's move-lock) with zero drift. */
        private void holdFloat(Player p) {
            Location f = weapon.frozenSpot(ownerId);
            if (f == null) return;
            p.setVelocity(new Vector(0, 0, 0));
            p.setFallDistance(0f);
            if (p.getLocation().distanceSquared(f) > 4.0) {   // knocked/teleported off — pull back
                Location back = f.clone();
                back.setYaw(p.getLocation().getYaw());
                back.setPitch(p.getLocation().getPitch());
                p.teleport(back);
            }
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1.0, 0), 3, 0.4, 0.7, 0.4, 0, BLOOD, true);
        }

        private void spawnRain(Player p) {
            World world = p.getWorld();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double ang = rng.nextDouble(0, Math.PI * 2), r = rng.nextDouble() * RAIN_RADIUS;
            Location base = weapon.frozenSpot(ownerId);
            if (base == null) base = p.getLocation();
            Location start = new Location(world, base.getX() + Math.cos(ang) * r,
                    base.getY() + SKY_HEIGHT + rng.nextDouble() * 8, base.getZ() + Math.sin(ang) * r);
            // Point (almost) straight DOWN — tip facing the ground, striking it, not a lazy drifting drop.
            Vector dir = new Vector((rng.nextDouble() - 0.5) * 0.12, -1, (rng.nextDouble() - 0.5) * 0.12).normalize();
            rain.add(new RainWedge(weapon.spawnFlyingWedge(world, start, dir, RAIN_SCALE), dir));
        }

        private void tickRain(Player owner) {
            for (Iterator<RainWedge> it = rain.iterator(); it.hasNext(); ) {
                RainWedge rw = it.next();
                if (rw.d == null || rw.d.isDead() || !rw.d.isValid() || ++rw.life > RAIN_MAX_LIFE) {
                    if (rw.d != null) rw.d.remove();
                    it.remove();
                    continue;
                }
                Location cur = rw.d.getLocation();
                Location next = cur.clone().add(rw.dir.clone().multiply(RAIN_SPEED));
                boolean ground = next.getBlock().getType().isSolid();
                LivingEntity foe = ground ? null : nearestFoe(next, RAIN_HIT_RADIUS);
                if (ground || foe != null) {
                    rainImpact(owner, foe != null ? foe.getLocation().add(0, foe.getHeight() * 0.5, 0) : cur);
                    rw.d.remove();
                    it.remove();
                    continue;
                }
                rw.d.teleport(next);
                World w = cur.getWorld();
                w.spawnParticle(Particle.DUST, cur, 4, 0.07, 0.07, 0.07, 0, BLOOD, true);
                w.spawnParticle(Particle.DUST, cur, 2, 0.05, 0.05, 0.05, 0, CORE, true);
                w.spawnParticle(Particle.DUST, cur, 2, 0.09, 0.09, 0.09, 0, DARK, true);
            }
        }

        private void rainImpact(Player owner, Location at) {
            World world = at.getWorld();
            for (Entity e : world.getNearbyEntities(at, RAIN_HIT_RADIUS, RAIN_HIT_RADIUS, RAIN_HIT_RADIUS)) {
                if (e instanceof LivingEntity le && !le.isDead() && !le.getUniqueId().equals(ownerId)) le.damage(RAIN_DAMAGE, owner);
            }
            weapon.debrisBurst(at, RAIN_DEBRIS, RAIN_DEBRIS_LIFE); // a small block-burst — destructive-looking, render-only
            world.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DUST, at, 22, 0.45, 0.3, 0.45, 0, BLOOD_BIG, true);
            world.spawnParticle(Particle.DUST, at, 10, 0.3, 0.2, 0.3, 0, CORE, true);
            world.spawnParticle(Particle.CRIT, at, 12, 0.3, 0.25, 0.3, 0.14);
            world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.4f, 1.4f);
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.28f, 1.5f);
        }

        // ---- the finale: a massive wedge plunges from the sky ---------------------

        private void finale(Player pOwner) {
            World world = pOwner.getWorld();
            Location anchor = weapon.frozenSpot(ownerId) != null ? weapon.frozenSpot(ownerId) : pOwner.getLocation();
            Location ground = groundBelow(anchor);
            ItemDisplay giant = weapon.spawnFlyingWedge(world, ground.clone().add(0, GIANT_SKY, 0), new Vector(0, -1, 0), GIANT_SCALE);
            world.playSound(ground, Sound.ITEM_TRIDENT_THUNDER, 1.3f, 0.4f);

            new BukkitRunnable() {
                int t = 0;
                @Override
                public void run() {
                    Player p = plugin.getServer().getPlayer(ownerId);
                    if (giant.isDead() || !giant.isValid() || p == null || !p.isOnline()) {
                        giant.remove();
                        weapon.endReckoning(ownerId);
                        cancel();
                        return;
                    }
                    holdFloat(p);
                    Location gl = giant.getLocation();
                    if (t < GIANT_CHARGE) {                        // gather red energy overhead
                        world.spawnParticle(Particle.DUST, gl, 14, 1.4, 1.8, 1.4, 0, BLOOD_BIG, true);
                        world.spawnParticle(Particle.DUST, gl, 6, 0.8, 1.0, 0.8, 0, CORE_BIG, true);
                        if (t % 4 == 0) world.playSound(gl, Sound.BLOCK_BEACON_AMBIENT, 0.6f, Math.min(1.6f, 0.4f + t * 0.02f));
                    } else {                                       // plunge
                        Location next = gl.clone().subtract(0, GIANT_FALL, 0);
                        boolean landed = next.getY() <= ground.getY() + 0.8 || next.getBlock().getType().isSolid();
                        if (landed) {
                            giant.teleport(ground.clone().add(0, 0.4, 0));
                            giantImpact(p, ground);
                            giant.remove();
                            weapon.endReckoning(ownerId);          // the cinematic is over — release the wielder
                            cancel();
                            return;
                        }
                        giant.teleport(next);
                        world.spawnParticle(Particle.DUST, gl, 20, 0.7, 1.0, 0.7, 0, BLOOD_BIG, true);
                        world.spawnParticle(Particle.DUST, gl, 10, 0.4, 0.6, 0.4, 0, CORE, true);
                    }
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private void giantImpact(Player owner, Location ground) {
            World world = ground.getWorld();
            for (Entity e : world.getNearbyEntities(ground, SHOCK_RADIUS, 6, SHOCK_RADIUS)) {
                if (e instanceof LivingEntity le && !le.isDead() && !le.getUniqueId().equals(ownerId)) le.damage(FINALE_DAMAGE, owner);
            }
            weapon.debrisBurst(ground, DEBRIS_COUNT);               // blocks flying (render-only)
            world.playSound(ground, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.7f);
            world.playSound(ground, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
            world.playSound(ground, Sound.ITEM_TRIDENT_THUNDER, 1.0f, 0.6f);
            world.playSound(ground, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 0.6f);
            new BukkitRunnable() {                                  // an expanding red shockwave ring
                double r = 1.0;
                @Override
                public void run() {
                    if (r > SHOCK_RADIUS) { cancel(); return; }
                    double y = ground.getY() + 0.4;
                    int pts = Math.max(12, (int) (r * 9));
                    for (int a = 0; a < pts; a++) {
                        double ang = (Math.PI * 2 * a) / pts;
                        Location pt = new Location(world, ground.getX() + Math.cos(ang) * r, y, ground.getZ() + Math.sin(ang) * r);
                        world.spawnParticle(Particle.DUST, pt, 1, 0.05, 0.06, 0.05, 0, BLOOD_BIG, true);
                        if ((a & 3) == 0) world.spawnParticle(Particle.DUST, pt.clone().add(0, 0.3, 0), 1, 0.05, 0.2, 0.05, 0, CORE_BIG, true);
                    }
                    r += 1.1;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private LivingEntity nearestFoe(Location from, double range) {
            LivingEntity best = null;
            double bestSq = range * range;
            for (Entity e : from.getWorld().getNearbyEntities(from, range, range, range)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || le.getUniqueId().equals(ownerId)) continue;
                double sq = le.getLocation().distanceSquared(from);
                if (sq < bestSq) { bestSq = sq; best = le; }
            }
            return best;
        }

        private void clearRain() {
            for (RainWedge rw : rain) if (rw.d != null && rw.d.isValid()) rw.d.remove();
            rain.clear();
        }
    }

    // ---- palette ------------------------------------------------------------------
    private static final TextColor CRIMSON = TextColor.color(0xC81E1E); // name / accent
    private static final TextColor FAINT   = TextColor.color(0x8A5A5A); // action-bar status

    private static final Color C_CORE  = Color.fromRGB(0xF0, 0x3A, 0x3A); // bright crimson core
    private static final Color C_BLOOD = Color.fromRGB(0x9A, 0x10, 0x14); // blood red body
    private static final Color C_DARK  = Color.fromRGB(0x33, 0x05, 0x08); // near-black red shroud
    // Sizes trimmed (finer particles) — counts unchanged.
    private static final Particle.DustOptions CORE  = new Particle.DustOptions(C_CORE, 0.8f);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(C_BLOOD, 1.1f);
    private static final Particle.DustOptions DARK  = new Particle.DustOptions(C_DARK, 1.4f);
    private static final Particle.DustOptions EMBER = new Particle.DustOptions(C_BLOOD, 0.7f);
    // Bigger crimson for the reckoning's resurrection burst + finale shockwave.
    private static final Particle.DustOptions CORE_BIG  = new Particle.DustOptions(C_CORE, 1.7f);
    private static final Particle.DustOptions BLOOD_BIG = new Particle.DustOptions(C_BLOOD, 2.4f);
    // The Yinglong intestine wreath behind the wielder — fleshy reds with pale-pink highlights.
    private static final Particle.DustOptions WREATH       = new Particle.DustOptions(Color.fromRGB(0xB8, 0x1C, 0x2A), 1.1f);
    private static final Particle.DustOptions WREATH_DARK  = new Particle.DustOptions(Color.fromRGB(0x70, 0x0E, 0x18), 1.2f);
    private static final Particle.DustOptions WREATH_WHITE = new Particle.DustOptions(Color.fromRGB(0xF3, 0xDD, 0xDF), 0.9f);

    // ---- name & lore --------------------------------------------------------------
    // Core colours: white + red. The quote's voice is a light teal.
    private static final TextColor L_RED   = TextColor.color(0xD11A2A); // blood red
    private static final TextColor L_WHITE = TextColor.color(0xF1EFEF); // white
    private static final TextColor L_TEAL  = TextColor.color(0x8FE6DA); // light teal — the quote body
    private static final TextColor L_FAINT = TextColor.color(0x9A8488); // muted — the how-to guide

    private static final Component WEAPON_NAME = Component.empty().decoration(TextDecoration.ITALIC, false)
            .append(Component.text("Tears of the Tarnished Blood", L_RED))
            .append(Component.text(" [汚血泣淚]", L_WHITE));

    private record Seg(String text, TextColor color, boolean italic, boolean bold) {
        Seg(String text, TextColor color) { this(text, color, false, false); }
        Seg(String text, TextColor color, boolean italic) { this(text, color, italic, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("Ying", L_RED, false, true), new Seg("long", L_WHITE, false, true)),   // abnormality
        List.of(),
        List.of(new Seg("The winged dragon of rain, who once", L_WHITE)),
        List.of(new Seg("parted the heavens — now it weeps", L_WHITE)),
        List.of(new Seg("tarnished ", L_WHITE), new Seg("blood", L_RED), new Seg(" for a sky lost to it.", L_WHITE)),
        List.of(),
        List.of(new Seg("“", L_RED, true), new Seg("With contemplation, I shall", L_TEAL, true)),
        List.of(new Seg("darken the clear skies above; with", L_TEAL, true)),
        List.of(new Seg("my sacrifice, I shall exsanguinate", L_TEAL, true)),
        List.of(new Seg("my carnal blood upon this earth.", L_TEAL, true), new Seg("”", L_RED, true)),
        List.of(),
        List.of(new Seg("How to use:", L_FAINT, true)),
        List.of(new Seg("Right-click — one wedge hunts a foe;", L_FAINT, true)),
        List.of(new Seg("Shift + RC — loose all at once.", L_FAINT, true)),
        List.of(new Seg("Left-click — hurl one to impale;", L_FAINT, true)),
        List.of(new Seg("Shift + LC — rip them out (stacks).", L_FAINT, true)),
        List.of(new Seg("F — call the wedges home.", L_FAINT, true)),
        List.of(),
        List.of(new Seg("Flower-burying Wedge ", L_WHITE, false, true), new Seg("[埋花櫼]", L_RED, false, true)),
        List.of(new Seg("At death's door, you rise again.", L_FAINT, true)),
        List.of(new Seg("Tears of the Tarnished Blood - The End ", L_RED, false, true), new Seg("[終]", L_WHITE, false, true)),
        List.of(new Seg("Then the sky bleeds a storm of spears.", L_FAINT, true)),
        List.of(),
        List.of(new Seg("E.G.O", L_RED, false, true))
    );

    private static final List<Component> LORE = buildLore();

    private static List<Component> buildLore() {
        List<Component> out = new ArrayList<>(LORE_SRC.size());
        for (List<Seg> line : LORE_SRC) {
            if (line.isEmpty()) { out.add(Component.empty()); continue; }
            Component c = Component.empty().decoration(TextDecoration.ITALIC, false);
            for (Seg seg : line) {
                c = c.append(Component.text(seg.text())
                        .color(seg.color())
                        .decoration(TextDecoration.ITALIC, seg.italic())
                        .decoration(TextDecoration.BOLD, seg.bold()));
            }
            out.add(c);
        }
        return out;
    }
}
