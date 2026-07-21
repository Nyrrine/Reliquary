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
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Harvest — the rake of the man who sought wisdom. An HE-tier Lobotomy Corp E.G.O Equipment: a great
 * rake, modelled on a NETHERITE_HOE (4.0 dmg / 1.0 speed).
 *
 * <p>A patient reaper dressed in farmer's colours — barn red, shirt white and overall denim-blue, with
 * only a hint of straw. Most swings are plain: the vanilla hoe blow, nothing more. But the wielder's
 * strikes are counted, and on every <b>third</b> strike — <i>if</i> its short cooldown has come up —
 * the rake looses a <b>slow, wide reaping arc</b> ({@link #onHit} → {@link #performSlash}) that sweeps
 * across a few ticks, cuts everything in the frontal fan for a modest netherite-band bonus, and mends
 * the wielder. Only the slashing strike heals; the first two strikes do nothing special.
 *
 * <p>Its signature is the <b>Harvest</b> passive: anything the wielder fells with the rake — anything
 * but a player — is reaped straight into their pack ({@link #onEntityDeath}): the kill's drops and XP
 * skip the ground and land in the killer's inventory (overflow spills at their feet). And it tills
 * real farmland too — right-click a mature crop ({@link #onInteract}) and the rake harvests it into
 * the pack, replants the same crop, and now and again turns up an extra.
 *
 * <p>Keeps a little per-player state (the strike counter and the slash cooldown), dropped on quit.
 */
public final class HarvestWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Harvest. */
    private final NamespacedKey key;

    // ---- reaping-slash tuning (the nerfed special) --------------------------------
    private static final int STRIKES_PER_SLASH = 3;    // only every 3rd strike can slash
    private static final long SLASH_CD_MS = 4000L;     // …and only if this cooldown has come up
    private static final double SLASH_DAMAGE = 7.0;    // bonus on top of the hoe blow; clears i-frames so it lands in full
    private static final double SLASH_RANGE = 3.6;     // how far the arc reaches
    private static final double SLASH_CONE_DEG = 150.0;// wide frontal fan
    private static final int SLASH_MAX_TARGETS = 8;    // cap the scan so a crowd can't blow up the tick
    private static final double SLASH_HEAL = 4.0;      // two hearts back on the slash, clamped to max
    private static final int SLASH_ARC_TICKS = 8;      // the arc reveals slowly over these ticks
    private static final int SLASH_STRIKE_TICK = 4;    // …and bites when the blade is mid-sweep

    /** Per-player strike counter toward the next slash. */
    private final Map<UUID, Integer> strikeCount = new HashMap<>();
    /** Per-player earliest-time the slash may fire again. */
    private final Map<UUID, Long> slashReadyAt = new HashMap<>();
    /** Re-entrancy fence: while a wielder's slash AoE is landing, ignore its own re-entered onHit calls. */
    private final Set<UUID> ticking = new HashSet<>();

    public HarvestWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "harvest");
    }

    @Override
    public String id() {
        return "harvest";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.HARVEST.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.HARVEST.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.HARVEST);

        item.setItemMeta(meta);
        return item;
    }

    // ---- the nerfed slash: every third strike, if the cooldown is up -------------------

    /**
     * Each landed strike is counted. Strikes 1 and 2 are plain hits — they do nothing special and do
     * not heal. On the 3rd strike the counter resets and, <i>if</i> the slash cooldown has come up, the
     * rake looses its slow reaping arc (see {@link #performSlash}); otherwise it too is a plain hit.
     *
     * <p>The slash's own AoE re-enters this dispatch, so a {@link #ticking} fence short-circuits those
     * re-entered calls — they must not be counted as fresh strikes.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();
        if (ticking.contains(id)) return; // re-entered by our own slash AoE — not a real strike

        int n = strikeCount.merge(id, 1, Integer::sum);
        if (n < STRIKES_PER_SLASH) {          // strikes 1 & 2: plain, no heal
            showSlash(attacker);
            return;
        }
        strikeCount.put(id, 0);               // 3rd strike — reset the counter either way

        long now = System.currentTimeMillis();
        Long ready = slashReadyAt.get(id);
        if (ready != null && now < ready) {   // cooldown not up yet: still just a plain hit
            showSlash(attacker);
            return;
        }
        slashReadyAt.put(id, now + SLASH_CD_MS);
        performSlash(attacker);
        showSlash(attacker);
    }

    /**
     * The slow reaping arc (inspired by Arayashiki's swept slashes): a wide fan of red-and-white blade
     * motes revealed a slice at a time over a few ticks. Mid-sweep the blade bites — a frontal AoE for a
     * modest bonus — and the wielder is mended. Non-vanilla-swing use, so it wears the rake once.
     */
    private void performSlash(Player player) {
        EgoDurability.wearMainHand(player);
        slashWhoosh(player);

        final World world = player.getWorld();
        final Location pivot = player.getLocation().add(0, 1.0, 0); // waist/chest height, at the cast
        final Vector dir0 = player.getEyeLocation().getDirection().normalize();
        Vector flatTmp = dir0.clone().setY(0);
        if (flatTmp.lengthSquared() < 1.0e-6) flatTmp = new Vector(1, 0, 0);
        final Vector flat = flatTmp.normalize();
        final Vector right = flat.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > SLASH_ARC_TICKS || !player.isOnline()) { cancel(); return; }
                drawBlade(world, pivot, flat, right, t);
                if (t == SLASH_STRIKE_TICK) applySlashDamage(player);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** One swept slice of the arc at tick {@code t}: a radial blade edge of red motes with a white tip. */
    private void drawBlade(World world, Location pivot, Vector flat, Vector right, int t) {
        double half = Math.toRadians(SLASH_CONE_DEG) * 0.5 * 0.9; // draw just inside the hit fan
        double f = (double) t / SLASH_ARC_TICKS;
        double a = -half + 2 * half * f;
        Vector radial = flat.clone().multiply(Math.cos(a)).add(right.clone().multiply(Math.sin(a))).normalize();

        final int SAMPLES = 6;
        for (int i = 1; i <= SAMPLES; i++) {
            double r = SLASH_RANGE * (0.45 + 0.55 * i / SAMPLES);
            Location p = pivot.clone().add(radial.clone().multiply(r));
            world.spawnParticle(Particle.DUST, p, 1, 0.04, 0.04, 0.04, 0, RED_DUST);
            if (i >= SAMPLES - 1) world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0, WHITE_DUST); // bright edge
            if (i == 2) world.spawnParticle(Particle.DUST, p, 1, 0.04, 0.04, 0.04, 0, DENIM_DUST);           // denim glint
        }
        if (t % 3 == 0) {
            Location tip = pivot.clone().add(radial.clone().multiply(SLASH_RANGE));
            world.spawnParticle(Particle.DUST, tip, 1, 0.05, 0.05, 0.05, 0, STRAW_DUST); // a faint straw mote
        }
    }

    /** The blade bites: a frontal fan of modest bonus damage, capped, then mend the reaper. Fenced. */
    private void applySlashDamage(Player player) {
        UUID id = player.getUniqueId();
        if (ticking.contains(id)) return;
        ticking.add(id);
        try {
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            double cosLimit = Math.cos(Math.toRadians(SLASH_CONE_DEG) * 0.5);
            int felled = 0;
            for (var entity : player.getNearbyEntities(SLASH_RANGE, SLASH_RANGE, SLASH_RANGE)) {
                if (entity == player || !(entity instanceof LivingEntity target)) continue;
                Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
                if (to.lengthSquared() < 1.0e-6) continue;
                if (to.clone().normalize().dot(dir) < cosLimit) continue;
                target.setNoDamageTicks(0); // the melee blow ~5t ago still has them in i-frames; clear so the slash lands
                target.damage(SLASH_DAMAGE, player);
                if (++felled >= SLASH_MAX_TARGETS) break;
            }
        } finally {
            ticking.remove(id);
        }
        healWielder(player); // only the slashing strike mends the reaper
    }

    /** Mend the wielder by a small clamped chunk (never overheals) with a soft cue. */
    private void healWielder(Player player) {
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double healed = Math.min(maxHp, player.getHealth() + SLASH_HEAL);
        if (healed > player.getHealth()) player.setHealth(healed);

        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.HEART, at, 2, 0.3, 0.4, 0.3, 0);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.0f);
    }

    /** A slow, low scythe whoosh across the sweep, pitch-jittered so repeated slashes don't drone. */
    private void slashWhoosh(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + rng.nextFloat() * 0.1f);
        world.playSound(at, Sound.ITEM_TRIDENT_RETURN, 0.4f, 0.9f + rng.nextFloat() * 0.2f);
    }

    /** Action-bar readout: strike pips toward the slash, plus its cooldown/ready state. Via EgoHud. */
    private void showSlash(Player player) {
        UUID id = player.getUniqueId();
        int n = Math.min(strikeCount.getOrDefault(id, 0), STRIKES_PER_SLASH);
        long rem = slashReadyAt.getOrDefault(id, 0L) - System.currentTimeMillis();
        Component state = rem > 0 ? EgoHud.cooldown("Slash", rem, DENIM) : EgoHud.ready("Slash", RED);
        Component bar = EgoHud.pips("Reap", STRAW, n, STRIKES_PER_SLASH)
                .append(Component.text("  ").decoration(TextDecoration.ITALIC, false))
                .append(state);
        player.sendActionBar(bar);
    }

    // ---- Harvest passive: what the rake fells, it reaps into the pack --------------

    /**
     * Anything felled by the rake — anything but a player — is harvested straight into the killer's
     * inventory: its drops and XP skip the ground. Overflow items spill at the killer's feet.
     */
    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) return;                 // players are never harvested
        Player killer = dead.getKiller();
        if (killer == null || !matches(killer.getInventory().getItemInMainHand())) return;

        Location feet = killer.getLocation();
        for (ItemStack drop : event.getDrops()) giveOrDrop(killer, drop, feet);
        int xp = event.getDroppedExp();
        if (xp > 0) killer.giveExp(xp);
        event.getDrops().clear();
        event.setDroppedExp(0);

        harvestCollect(killer);
    }

    /** A small collecting flourish on the reaper when a kill is drawn into the pack. */
    private void harvestCollect(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.0f);
        world.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.8f);
        world.spawnParticle(Particle.DUST, at, 8, 0.3, 0.4, 0.3, 0, RED_DUST);
        world.spawnParticle(Particle.DUST, at, 5, 0.3, 0.4, 0.3, 0, WHITE_DUST);
        world.spawnParticle(Particle.DUST, at, 3, 0.3, 0.4, 0.3, 0, DENIM_DUST);
    }

    // ---- farmland auto-harvest: right-click a ripe crop ---------------------------

    /**
     * Right-click a mature crop the wielder is looking at (within 5 blocks): harvest it into the pack,
     * replant the same crop at age 0, and — half the time — turn up one extra of its produce. Nothing
     * spills unless the pack is full. Any block that is not a ripe crop is left untouched.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Block block = player.getTargetBlockExact(5);
        if (block == null) return;
        Material type = block.getType();
        if (!isHarvestable(type)) return;

        BlockData data = block.getBlockData();
        if (!(data instanceof Ageable ag) || ag.getAge() != ag.getMaximumAge()) return;

        Collection<ItemStack> drops = block.getDrops();          // computed before we replant
        Location center = block.getLocation().add(0.5, 0.5, 0.5);

        // Replant the very same crop, reset to a seedling.
        Ageable replanted = (Ageable) block.getBlockData();
        replanted.setAge(0);
        block.setBlockData(replanted);

        for (ItemStack drop : drops) giveOrDrop(player, drop, center);
        if (ThreadLocalRandom.current().nextDouble() < 0.5) {    // 50% bonus of the produce
            Material produce = produceOf(type);
            if (produce != null) giveOrDrop(player, new ItemStack(produce, 1), center);
        }

        EgoDurability.wearMainHand(player);                      // non-vanilla-swing use — wear the rake
        harvestPuff(center);
    }

    /** A light till-and-plant flourish at the harvested crop. */
    private void harvestPuff(Location at) {
        World world = at.getWorld();
        world.playSound(at, Sound.BLOCK_CROP_BREAK, 0.8f, 1.0f);
        world.playSound(at, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.5f, 1.2f);
        world.spawnParticle(Particle.DUST, at, 6, 0.25, 0.2, 0.25, 0, WHITE_DUST);
        world.spawnParticle(Particle.DUST, at, 3, 0.25, 0.2, 0.25, 0, STRAW_DUST);
        world.spawnParticle(Particle.HAPPY_VILLAGER, at, 4, 0.25, 0.2, 0.25, 0);
    }

    /** Add an item to the pack; anything that won't fit spills at {@code loc}. */
    private void giveOrDrop(Player player, ItemStack item, Location loc) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        var overflow = player.getInventory().addItem(item.clone());
        for (ItemStack left : overflow.values()) {
            if (left != null && !left.getType().isAir()) loc.getWorld().dropItemNaturally(loc, left);
        }
    }

    private static boolean isHarvestable(Material m) {
        return m == Material.WHEAT || m == Material.CARROTS || m == Material.POTATOES
                || m == Material.BEETROOTS || m == Material.NETHER_WART;
    }

    /** The primary produce of a crop — used for the 50% bonus. */
    private static Material produceOf(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    // ---- per-player state ---------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        strikeCount.remove(id);
        slashReadyAt.remove(id);
        ticking.remove(id);
    }

    // ---- palette: farmer red + shirt white + overall denim-blue, a hint of straw --

    private static final TextColor RED   = TextColor.color(0xC0392B); // name / barn red — primary
    private static final TextColor DENIM = TextColor.color(0x3E5C82); // overall denim-blue accent
    private static final TextColor STRAW = TextColor.color(0xC9A94E); // minor straw accent — secondary

    private static final Particle.DustOptions RED_DUST   = new Particle.DustOptions(Color.fromRGB(0xC0392B), 1.0f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(Color.fromRGB(0xEDE7D9), 0.9f);
    private static final Particle.DustOptions DENIM_DUST = new Particle.DustOptions(Color.fromRGB(0x3E5C82), 1.0f);
    private static final Particle.DustOptions STRAW_DUST = new Particle.DustOptions(Color.fromRGB(0xC9A94E), 0.8f);

    // ---- lore ---------------------------------------------------------------------

    // Primary stays the barn red the display name has always been. Secondary is STRAW — the palette's
    // own straw accent, and the only one bright enough to carry a bold title line: DENIM (#3E5C82) is
    // the other candidate but sits dim against a tooltip's near-black background. The title line was the
    // name's red under the old format; the new format wants the two distinct, and a scarecrow reading in
    // straw is the reason the accent is in the palette at all.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Harvest",
            "Scarecrow Searching for Wisdom",
            RED,
            STRAW,
            List.of(
                    "The rake of the man who sought",
                    "wisdom, that tilled minds not soil."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Harvest",
                            "Anything you fell with the rake — not",
                            "players — sends its drops and XP",
                            "straight to your inventory. Overflow",
                            "spills at your feet."),
                    new EgoLore.Ability("[Left Click] Third-Strike Slash",
                            "Every 3rd strike sweeps a wide arc:",
                            "5 bonus damage to everything in a",
                            "150° fan out to 3.6 blocks, up to 8",
                            "targets, and mends you 2 hearts.",
                            "4 second cooldown — until it comes up,",
                            "the 3rd strike is a plain hit too."),
                    new EgoLore.Ability("[Right Click] Crop Harvest and Replant",
                            "Right-click a ripe crop within 5",
                            "blocks to reap it into your inventory",
                            "and replant it. Half the time it turns",
                            "up one extra of its produce.")
            ));
}
