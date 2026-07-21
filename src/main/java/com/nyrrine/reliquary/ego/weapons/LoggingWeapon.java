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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Logging — Warm-Hearted Woodsman. A TETH-tier Lobotomy Corp E.G.O weapon: a broad woodsman's axe whose
 * ever-shiny blade cuts down trees and people alike.
 *
 * <p>A plain melee weapon — the vanilla NETHERITE_AXE swing deals its normal damage, uncancelled, and
 * wears via vanilla. The gimmick is a <b>heart charge bar</b>: each properly-spaced strike on a foe adds
 * {@link #CHARGE_PER_HIT} toward a full charge (so ~5 spaced hits fill it), tracked <em>per wielder</em>.
 * A minimum-interval guard ({@link #MIN_HIT_INTERVAL_MS}ms) means spam-mashing does not fill the bar —
 * only spaced hits count. Every damaging hit also records the wielder's <b>most-recent foe</b> for a short
 * window ({@link #FOE_TTL_MS}ms), so the ability always has a target without a raytrace.
 *
 * <p><b>Rip Their Heart</b> ({@link #onInteract}, right-click) — at a full charge, the heart is torn out of
 * the wielder's most-recent foe (if it is still alive and nearby): a considerable burst of damage, a short
 * bleed, and 9s of Slowness + Weakness. The charge is spent (reset to zero). A right-click with an
 * unfinished bar or no valid recent foe is a quiet no-op with a soft cue. The burst is a non-vanilla hit,
 * so it wears the item via {@link EgoDurability#wearMainHand(Player)}.
 *
 * <p>The action bar carries the live charge as a gauge while the axe is held. State is kept
 * O(active wielders + live bleeds): per-wielder charge/last-hit clocks and a most-recent-foe pointer, plus
 * per-victim bleed tasks, all expiring on their own and cleared on quit/disable. Both the burst and the
 * bleed re-enter {@link #onHit} (their {@code victim.damage} counts as a melee hit); the {@link #ticking}
 * fence makes that a no-op so a rip can never build or spend charge off its own damage.
 */
public final class LoggingWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Logging. */
    private final NamespacedKey key;

    // ---- charge tuning ------------------------------------------------------------

    /** Charge added by each qualifying (spaced) hit on a foe. 0.20 -> ~5 hits fill the bar. */
    private static final double CHARGE_PER_HIT = 0.20;

    // Sharpened Axe (a vanilla enchant — Logging is a netherite axe and holds Efficiency at an anvil, so this
    // needs no catalogue entry): a keener edge sinks the heart charge in faster, +10% charge per qualifying
    // hit per level, up to +30% at Efficiency III (~4 spaced hits to full from ~5). Pace only — it never
    // touches the rip's burst, its bleed or its debuffs, only how quickly the bar fills. The live gauge shows
    // the charge itself, so it stays truthful with no readout change. See chargePerHit.
    private static final double SHARPENED_PER_LEVEL = 0.10;
    private static final int    SHARPENED_CAP       = 3;
    /** A hit inside this window of the wielder's last COUNTED hit is mash — it doesn't build charge. */
    private static final long MIN_HIT_INTERVAL_MS = 350L;
    /** How long the "most-recent foe" pointer stays a valid rip target after the last hit. */
    private static final long FOE_TTL_MS = 9_000L;

    // ---- Rip Their Heart tuning ---------------------------------------------------

    /** How near the recent foe must still be for the rip to reach it (blocks). No raytrace — pure range. */
    private static final double RIP_RANGE = 8.0;
    /** The considerable burst dealt when the heart is torn out. ~6 hearts. */
    private static final double RIP_DAMAGE = 12.0;
    /** Slowness + Weakness duration on the rip: 9 seconds. */
    private static final int DEBUFF_TICKS = 9 * 20;
    private static final int SLOWNESS_AMP = 1; // Slowness II
    private static final int WEAKNESS_AMP = 0; // Weakness I

    // Short bleed opened by the rip — a small DoT, kept modest.
    private static final double BLEED_DAMAGE = 1.5;      // ~0.75 hearts per tick
    private static final long   BLEED_PERIOD_TICKS = 10; // every ~0.5s
    private static final int    BLEED_TICKS = 4;         // four ticks -> ~6.0 over ~2s

    /** Oak splinters flung from every chop. Cached — a plank's block data never changes. */
    private static final BlockData CHIP = Material.OAK_PLANKS.createBlockData();
    /** Blood dust for the torn-out-heart burst / bleed trail. */
    private static final Color BLOOD = Color.fromRGB(0x8A, 0x0B, 0x0B);

    // ---- state (O(active wielders + live bleeds)) ---------------------------------

    /** Wielder -> their current heart charge in [0,1]. Absent means empty. */
    private final Map<UUID, Double> charge = new HashMap<>();
    /** Wielder -> epoch-ms of their last COUNTED hit; drives the mash guard. */
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();
    /** Wielder -> the most-recent foe they damaged, and when that pointer lapses. */
    private final Map<UUID, RecentFoe> recentFoe = new HashMap<>();
    /** Victim -> their live bleed task; refreshed (cancel + replace) per rip. */
    private final Map<UUID, BukkitTask> bleeds = new HashMap<>();
    /**
     * Wielders whose rip burst/bleed is currently resolving. Their {@code victim.damage} re-enters
     * {@link #onHit}; while their id is in here the charge logic is skipped, so the burst can never
     * build or spend charge off its own damage.
     */
    private final Set<UUID> ticking = new HashSet<>();

    /** The wielder's most-recent foe: which entity, and the epoch-ms at which the pointer lapses. */
    private record RecentFoe(LivingEntity entity, long expiresAt) {}

    public LoggingWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "logging");
    }

    @Override
    public String id() {
        return "logging";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LOGGING.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LOGGING.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LOGGING);

        item.setItemMeta(meta);
        return item;
    }

    // ---- charge the heart ---------------------------------------------------------

    /**
     * Melee hit landed. Vanilla axe damage is left intact. Every chop throws splinters; a hit on a foe then
     * records that foe as the most-recent target and, if it is properly spaced from the last counted hit,
     * adds {@link #CHARGE_PER_HIT} to the wielder's charge. Our own rip burst/bleed (fenced by
     * {@link #ticking}) runs none of this.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        if (ticking.contains(aid)) return; // re-entrant rip burst/bleed — no charge off our own damage

        chopFx(victim); // an axe bites the same into wood or flesh — splinters + a solid thunk

        if (victim.equals(attacker)) return; // never charge or target yourself

        long now = System.currentTimeMillis();
        // Every damaging blow refreshes who the wielder's most-recent foe is (even a mashed one).
        recentFoe.put(aid, new RecentFoe(victim, now + FOE_TTL_MS));

        Long last = lastCountedHit.get(aid);
        if (last != null && now - last < MIN_HIT_INTERVAL_MS) return; // mash — doesn't build charge
        lastCountedHit.put(aid, now);

        double c = charge.getOrDefault(aid, 0.0);
        boolean wasFull = c >= 1.0;
        c = Math.min(1.0, c + chargePerHit(attacker));
        charge.put(aid, c);
        if (c >= 1.0 && !wasFull) fullChargeCue(attacker); // just topped off
        sendChargeBar(attacker); // fresh readout right on the qualifying hit
    }

    /** The charge one qualifying hit adds for the axe held now: the base raised by its Sharpened Axe bonus
     *  (Efficiency, capped). Damage is untouched — only the fill rate moves. */
    private double chargePerHit(Player player) {
        int eff = Math.min(SHARPENED_CAP,
                player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.EFFICIENCY));
        return CHARGE_PER_HIT * (1.0 + SHARPENED_PER_LEVEL * eff);
    }

    /**
     * Right-click: at a full charge, tear the heart out of the wielder's most-recent foe (if still alive and
     * nearby) — a considerable burst, a short bleed, and 9s of Slowness + Weakness. The charge is spent.
     * An unfinished bar or no valid recent foe is a quiet no-op with a soft cue.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        double c = charge.getOrDefault(player.getUniqueId(), 0.0);
        if (c < 1.0) { notReadyCue(player); return; } // not charged yet

        LivingEntity foe = validRecentFoe(player);
        if (foe == null) { notReadyCue(player); return; } // no valid foe within reach

        ripHeart(player, foe);
    }

    /** Tear the heart out of the foe: burst + bleed + debuffs, spend the charge, wear the axe. */
    private void ripHeart(Player wielder, LivingEntity foe) {
        UUID aid = wielder.getUniqueId();
        ticking.add(aid); // fence the burst's re-entry into onHit
        try {
            foe.damage(RIP_DAMAGE, wielder);
        } finally {
            ticking.remove(aid);
        }

        foe.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, DEBUFF_TICKS, SLOWNESS_AMP, false, true, true));
        foe.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, DEBUFF_TICKS, WEAKNESS_AMP, false, true, true));

        startBleed(wielder, foe);
        tornHeartFx(foe);

        // Spend the charge; the foe pointer has served its purpose.
        charge.put(aid, 0.0);
        recentFoe.remove(aid);
        sendChargeBar(wielder);

        // A rip is a non-vanilla burst — wear the main-hand item (vanilla strikes wear on their own).
        EgoDurability.wearMainHand(wielder);
    }

    /** The wielder's most-recent foe if it is still live, valid, and within {@link #RIP_RANGE}, else null. */
    private LivingEntity validRecentFoe(Player wielder) {
        UUID aid = wielder.getUniqueId();
        RecentFoe rf = recentFoe.get(aid);
        if (rf == null) return null;
        if (System.currentTimeMillis() > rf.expiresAt()) { recentFoe.remove(aid); return null; }

        LivingEntity foe = rf.entity();
        if (foe == null || foe.isDead() || !foe.isValid()) { recentFoe.remove(aid); return null; }
        if (!foe.getWorld().equals(wielder.getWorld())) return null;
        if (foe.getLocation().distanceSquared(wielder.getLocation()) > RIP_RANGE * RIP_RANGE) return null;
        return foe;
    }

    /** Cancel any live bleed on the victim and seed a fresh one — refresh, not stack. */
    private void startBleed(Player wielder, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        BukkitTask old = bleeds.remove(vid);
        if (old != null) old.cancel();
        BukkitTask task = new BleedTask(wielder.getUniqueId(), victim)
                .runTaskTimer(plugin, BLEED_PERIOD_TICKS, BLEED_PERIOD_TICKS);
        bleeds.put(vid, task);
    }

    /**
     * The rip wound. A small damage tick attributed to the wielder (while online), a fixed number of times,
     * trailing blood each tick. The tick's own knockback is undone so the bleed is a pure DoT, and its
     * {@code victim.damage} re-entry into {@link #onHit} is fenced by {@link #ticking}.
     */
    private final class BleedTask extends BukkitRunnable {
        private final UUID wielderId;
        private final LivingEntity victim;
        private int ticksLeft = BLEED_TICKS;

        private BleedTask(UUID wielderId, LivingEntity victim) {
            this.wielderId = wielderId;
            this.victim = victim;
        }

        @Override
        public void run() {
            if (ticksLeft <= 0 || victim.isDead() || !victim.isValid()) {
                finish();
                return;
            }
            ticksLeft--;

            Player wielder = plugin.getServer().getPlayer(wielderId);
            Vector preVel = victim.getVelocity();

            if (wielder != null) ticking.add(wielderId); // fence: this damage re-enters onHit
            try {
                if (wielder != null && !wielder.equals(victim)) {
                    // Internal bleeding shouldn't be plated — true damage via the pierce helper (full armour
                    // bypass), which also clears i-frames and neutralises knockback for this DoT.
                    plugin.weapons().pierceDamage(victim, BLEED_DAMAGE, 1.0, wielder);
                } else {
                    victim.damage(BLEED_DAMAGE); // wielder offline: no pierce helper without a player
                }
            } finally {
                if (wielder != null) ticking.remove(wielderId);
                victim.setVelocity(preVel); // pure DoT — undo the bleed tick's knockback
            }

            bloodTrail(victim);
            if (ticksLeft <= 0) finish();
        }

        private void finish() {
            cancel();
            bleeds.remove(victim.getUniqueId());
        }
    }

    // ---- action bar ---------------------------------------------------------------

    /**
     * While the axe is held, keep the heart-charge gauge on the action bar. Disengages (returns false) the
     * moment the axe leaves the main hand, so idle wielders stop ticking.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        sendChargeBar(player);
        return true;
    }

    /** Push the current charge to the wielder as a gauge: {@code [▮▮…]  Heart NN%} (or a ready cue at full). */
    private void sendChargeBar(Player player) {
        double c = charge.getOrDefault(player.getUniqueId(), 0.0);
        int pct = (int) Math.round(c * 100.0);
        Component label = c >= 1.0
                ? EgoHud.status("Rip Their Heart — ready", HEART)
                : EgoHud.status("Heart " + pct + "%", HEART);
        player.sendActionBar(EgoHud.gauge(HEART, c, label));
    }

    // ---- SFX / VFX ----------------------------------------------------------------

    /** Wood-chip splinters + a solid chopping thunk — the same bite into wood or flesh. */
    private void chopFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location impact = victim.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.BLOCK, impact, 9, 0.3, 0.3, 0.3, 0.0, CHIP);
        world.spawnParticle(Particle.CRIT, impact, 2, 0.25, 0.25, 0.25, 0.05);

        float pitch = 0.75f + ThreadLocalRandom.current().nextFloat() * 0.15f;
        world.playSound(impact, Sound.BLOCK_WOOD_BREAK, 0.8f, pitch);
    }

    /** The charge just filled: a low heartbeat thunk and a pulse of red at the wielder's chest. */
    private void fullChargeCue(Player wielder) {
        World world = wielder.getWorld();
        Location chest = wielder.getLocation().add(0, 1.0, 0);
        world.playSound(chest, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 0.7f);
        world.spawnParticle(Particle.HEART, chest, 1, 0.1, 0.2, 0.1, 0.0);
        world.spawnParticle(Particle.DUST, chest, 8, 0.2, 0.3, 0.2, 0.0,
                new Particle.DustOptions(BLOOD, 1.1f));
    }

    /** Right-click with nothing to rip: a soft, dry fail click, no fuss. */
    private void notReadyCue(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_WOOD_HIT, 0.6f, 0.6f);
        sendChargeBar(player);
    }

    /** The heart torn free: a wet rip, a burst of blood, and the heart flung out. */
    private void tornHeartFx(LivingEntity victim) {
        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, 1.0, 0);

        world.spawnParticle(Particle.DUST, chest, 24, 0.35, 0.45, 0.35, 0.0,
                new Particle.DustOptions(BLOOD, 1.4f));
        world.spawnParticle(Particle.CRIT, chest, 8, 0.3, 0.3, 0.3, 0.15);
        world.spawnParticle(Particle.HEART, chest, 3, 0.25, 0.35, 0.25, 0.0);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(chest, Sound.BLOCK_HONEY_BLOCK_BREAK, 1.0f, 0.5f + rng.nextFloat() * 0.15f); // wet rip
        world.playSound(chest, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.7f);                          // heavy tear
        world.playSound(chest, Sound.ENTITY_WARDEN_HEARTBEAT, 0.6f, 0.5f);                            // last beat
    }

    /** A thin trail of red mist off the bleeding wound — low count, short-lived. */
    private void bloodTrail(LivingEntity victim) {
        Location body = victim.getLocation().add(0, 1.0, 0);
        victim.getWorld().spawnParticle(Particle.DUST, body, 3, 0.18, 0.30, 0.18, 0.0,
                new Particle.DustOptions(BLOOD, 0.8f));
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // Wielder state.
        charge.remove(id);
        lastCountedHit.remove(id);
        ticking.remove(id);
        recentFoe.remove(id);

        // If the quitter was a bleeding victim, end their wound.
        BukkitTask task = bleeds.remove(id);
        if (task != null) task.cancel();

        // If the quitter was someone's most-recent foe, drop that dangling pointer.
        recentFoe.values().removeIf(rf -> rf.entity() != null && id.equals(rf.entity().getUniqueId()));
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : bleeds.values()) task.cancel();
        bleeds.clear();
        recentFoe.clear();
        charge.clear();
        lastCountedHit.clear();
        ticking.clear();
    }

    // ---- lore ---------------------------------------------------------------------

    /** Primary — the warm woodsman brown. Display name, "How to use:", ability headers. */
    private static final TextColor BARK  = TextColor.color(0x9C6B3F);
    /**
     * Heart red. The Abnormality title line, and the charge gauge on the action bar — the one accent this
     * axe has ever had, and the colour the old tooltip already picked out "Rip Their Heart" in.
     */
    private static final TextColor HEART = TextColor.color(0xC0392B);

    // Kept verbatim: the flavour opens with the same words the helper's footer closes on, so
    // "E.G.O Equipment" reads twice on this tooltip. The prose is the owner's — this pass moves the
    // format and nothing else, and quietly rewording her line would be the worse fix.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Logging",
            "Warm-Hearted Woodsman",
            BARK,
            HEART,
            List.of(
                    "E.G.O Equipment — made to cut down",
                    "trees and people alike."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Heart Charge",
                            "Striking a foe builds heart charge —",
                            "5 spaced hits fill it. Hits landed",
                            "under 0.35s apart build nothing."),
                    new EgoLore.Ability("[Right Click] Rip Their Heart",
                            "At a full charge, tear the heart from",
                            "the last foe you hit — within 8 blocks",
                            "and struck in the last 9 seconds. A",
                            "heavy burst, a short bleed of true",
                            "damage, and 9s of Slowness II and",
                            "Weakness. Spends the charge.")
            ));
}
