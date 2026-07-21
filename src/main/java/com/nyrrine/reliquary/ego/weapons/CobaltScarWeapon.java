package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import com.nyrrine.reliquary.ego.EgoHud;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cobalt Scar — the claws of a vicious wolf. A close-range flurry E.G.O Equipment built for 1.8-style
 * combat: no attack-cooldown, fast full-damage swings, but a modest per-hit bite and a hitbox-tight
 * reach that keeps the flurry honest.
 *
 * <p>The bargain is speed for range. Its melee damage and (very high) attack speed are stamped by
 * {@link EgoModels#stampWeapon} so a wielder can rattle off full-power blows with no swing-timer — but
 * in {@link #onHit} a blow only lands when the target sits inside a short {@link #REACH} of the
 * attacker's eye; a swing that reaches past that is nullified ({@code event.setCancelled(true)}), so
 * this cannot poke from sword range. Get in close or land nothing.
 *
 * <p>Every cut that lands opens (or refreshes) a short <b>bleed</b> — the flaying wound of a wolf's
 * claws — that keeps costing the victim blood for a couple of seconds, so a sustained flurry keeps the
 * quarry bleeding nonstop. The bleed's own tick calls {@code victim.damage(..., attacker)} and so
 * re-enters {@link #onHit}; a re-entrancy fence ({@link #ticking}) makes onHit refuse to re-seed (or
 * range-cancel) from inside its own tick. Each victim carries at most one bleed — a fresh cut refreshes
 * rather than stacks.
 *
 * <p>Right-click is a short <b>dash</b> on a {@value #DASH_COOLDOWN_MS}ms cooldown (shown in whole
 * seconds via {@link EgoHud#cooldown}). The dash is non-vanilla motion, so it wears the main-hand item
 * through {@link EgoDurability#wearMainHand(Player)}; ordinary swings wear through the vanilla swing.
 */
public final class CobaltScarWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Cobalt Scar. */
    private final NamespacedKey key;

    // ---- bleed model --------------------------------------------------------------
    /** Victim UUID -> their live bleed task. At most one per victim; refreshed (cancel + replace) per cut. */
    private final Map<UUID, BukkitTask> bleeds = new HashMap<>();
    /** Victims currently taking a bleed tick — guards {@link #onHit} against re-seeding from its own tick. */
    private final Set<UUID> ticking = new HashSet<>();

    // Bleed tuning — small per tick so the fast flurry + bleed stays fair for 1.8-style PvP.
    private static final double BLEED_DAMAGE = 1.0;      // half a heart per tick
    private static final long   BLEED_PERIOD_TICKS = 10; // every ~0.5s
    private static final int    BLEED_TICKS = 4;         // four ticks -> ~4.0 total over ~2s, refreshed while pressing

    // ---- reach --------------------------------------------------------------------
    /** Effective melee reach (blocks, eye to victim hitbox). Shorter than a sword — the price of 1.8 speed. */
    private static final double REACH = 2.2;

    // ---- dash ---------------------------------------------------------------------
    /** Wielder UUID -> epoch-millis when their next dash is allowed. */
    private final Map<UUID, Long> dashReadyAt = new HashMap<>();

    // ---- off-hand seal ------------------------------------------------------------
    /** Wielders we've already told (once) that the off hand is sealed. Cleared on unequip/quit. */
    private final Set<UUID> offhandNotified = new HashSet<>();
    /** Dash cooldown — seven seconds. */
    private static final long DASH_COOLDOWN_MS = 7_000L;
    // Whirring Claws (Efficiency): shaves the dash cooldown per level, floored so it never trivialises. This
    // is Cobalt's ONLY enchant on purpose — its 16 attack speed makes any per-hit enchant compound wildly, so
    // the enchant slot is pure mobility utility, never damage.
    private static final long WHIRRING_CD_CUT_MS   = 1_000L; // shaved per Efficiency level
    private static final int  WHIRRING_MAX_LEVELS  = 5;      // cap the levels that count
    private static final long DASH_COOLDOWN_FLOOR_MS = 3_000L; // the dash never comes back faster than this
    /** Dash impulse — a short lunge, not a leap. */
    private static final double DASH_POWER = 0.9;

    public CobaltScarWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "cobalt_scar");
    }

    @Override
    public String id() {
        return "cobalt_scar";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.COBALT_SCAR.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.COBALT_SCAR.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.COBALT_SCAR);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: a short-reach flurry that flays -----------------------------------

    /**
     * Melee hit landed. If this is our own bleed tick re-entering (flagged in {@link #ticking}) we do
     * nothing — the bleed must neither re-seed nor be range-cancelled from inside its own tick. Otherwise
     * we enforce the short reach (nullifying a swing that reaches past {@link #REACH}) and, on a clean
     * close hit, open or refresh the flaying bleed. The vanilla swing damage is left intact underneath.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID vid = victim.getUniqueId();
        if (ticking.contains(vid)) return; // our own bleed tick — don't refresh or cancel from within

        if (eyeReach(attacker, victim) > REACH) {
            event.setCancelled(true); // too far — a close-range weapon can't poke from sword range
            whiff(attacker);
            return;
        }

        // TRUE 1.8-combo multi-hit: vanilla stamps the victim with hurt-immunity (i-frames) that would
        // swallow the next fast swing, and Cobalt Scar's whole point is the flurry. The catch the old
        // inline clear missed: vanilla re-applies that stamp AFTER this event returns, overwriting a clear
        // made from in here — so the strip was inert and the flurry only connected once every ~10 ticks
        // (~10 DPS). Clearing on the NEXT tick, once the stamp has settled, lets the very next swing land,
        // so the flurry actually flurries. Fairness stays in the small per-hit bite and the short REACH
        // gate above, not in a swing-timer.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!victim.isDead() && victim.isValid()) victim.setNoDamageTicks(0);
        });

        clawFx(attacker, victim);
        startBleed(attacker, victim);
    }

    /** Distance from the attacker's eye to the nearest point of the victim's hitbox, in blocks. */
    private double eyeReach(Player attacker, LivingEntity victim) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
        double cx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Cancel any existing bleed on the victim and seed a fresh one — refresh, not stack. */
    private void startBleed(Player attacker, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        BukkitTask old = bleeds.remove(vid);
        if (old != null) old.cancel();

        BukkitTask task = new BleedTask(attacker.getUniqueId(), victim)
                .runTaskTimer(plugin, BLEED_PERIOD_TICKS, BLEED_PERIOD_TICKS);
        bleeds.put(vid, task);
    }

    /**
     * The flaying wound. Deals a small damage tick (attributed to the wielder while they remain online)
     * a fixed number of times, weeping blood off the body each tick, then ends. Re-entrancy into
     * {@link #onHit} is fenced by the {@link #ticking} flag around the damage call.
     */
    private final class BleedTask extends BukkitRunnable {
        private final UUID attackerId;
        private final LivingEntity victim;
        private int ticksLeft = BLEED_TICKS;

        private BleedTask(UUID attackerId, LivingEntity victim) {
            this.attackerId = attackerId;
            this.victim = victim;
        }

        @Override
        public void run() {
            if (ticksLeft <= 0 || victim.isDead() || !victim.isValid()) {
                finish();
                return;
            }
            ticksLeft--;

            UUID vid = victim.getUniqueId();
            Player attacker = plugin.getServer().getPlayer(attackerId);

            // Pure damage-over-time, not a shove: capture the victim's velocity before the tick and
            // restore it after, so the damage event's knockback impulse is undone.
            Vector preVel = victim.getVelocity();

            ticking.add(vid); // fence: victim.damage below re-enters onHit; don't let it re-seed the bleed
            try {
                if (attacker != null && !attacker.equals(victim)) {
                    victim.damage(BLEED_DAMAGE, attacker);
                } else {
                    victim.damage(BLEED_DAMAGE);
                }
            } finally {
                ticking.remove(vid);
                victim.setVelocity(preVel); // undo the bleed tick's knockback
            }

            bloodTrail(victim);

            if (ticksLeft <= 0) finish();
        }

        private void finish() {
            cancel();
            bleeds.remove(victim.getUniqueId());
        }
    }

    // ---- ability: a short lunge -----------------------------------------------------

    /**
     * Right-click: a short forward dash on a {@value #DASH_COOLDOWN_MS}ms cooldown. While cooling, the
     * remaining wait is shown in whole seconds on the action bar (never raw milliseconds). The dash is
     * non-vanilla motion, so it wears the main-hand item.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long ready = dashReadyAt.getOrDefault(id, 0L);
        if (now < ready) {
            renderBar(player); // the persistent line already shows the Dash cooldown
            player.playSound(player.getLocation(), Sound.ITEM_AXE_SCRAPE, 0.3f, 1.6f + jitter());
            return;
        }
        dashReadyAt.put(id, now + dashCooldownMs(player.getInventory().getItemInMainHand()));

        Vector dir = player.getEyeLocation().getDirection();
        Vector vel = dir.multiply(DASH_POWER);
        if (vel.getY() < 0.2) vel.setY(0.2); // a slight hop so the lunge skims rather than digs in
        player.setVelocity(vel);

        EgoDurability.wearMainHand(player); // non-vanilla motion wears the claws

        World world = player.getWorld();
        // A lunging claw-rake: a ravager's tearing snap layered under an iron scrape.
        world.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 1.3f + jitter());
        world.playSound(player.getLocation(), Sound.ITEM_AXE_SCRAPE, 0.6f, 1.5f + jitter());
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 12, 0.3, 0.4, 0.3, 0.0,
                new Particle.DustOptions(COBALT_ICE, 1.0f));
    }

    /** Whirring Claws: the dash cooldown, shaved by Efficiency and floored so it can never trivialise. */
    private long dashCooldownMs(ItemStack item) {
        int eff = item == null ? 0 : item.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (eff <= 0) return DASH_COOLDOWN_MS;
        long cut = Math.min(eff, WHIRRING_MAX_LEVELS) * WHIRRING_CD_CUT_MS;
        return Math.max(DASH_COOLDOWN_FLOOR_MS, DASH_COOLDOWN_MS - cut);
    }

    // ---- SFX / VFX ----------------------------------------------------------------

    /** Claws scratching and mauling flesh, and a cobalt rake spraying red where the skin opens. */
    private void clawFx(Player attacker, LivingEntity victim) {
        World world = victim.getWorld();
        Location wound = victim.getLocation().add(0, 1.0, 0);
        // Tearing, not a bark: a raking scrape + a heavy maul, pitch-jittered so each cut differs.
        world.playSound(wound, Sound.ITEM_AXE_SCRAPE, 0.7f, 1.5f + jitter());
        world.playSound(wound, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 0.9f + jitter());
        if (ThreadLocalRandom.current().nextInt(3) == 0) {
            world.playSound(wound, Sound.ENTITY_RAVAGER_ATTACK, 0.5f, 1.4f + jitter());
        }
        world.spawnParticle(Particle.DUST, wound, 8, 0.25, 0.3, 0.25, 0.0,
                new Particle.DustOptions(COBALT_DEEP, 1.0f));
        world.spawnParticle(Particle.DUST, wound, 6, 0.2, 0.22, 0.2, 0.0,
                new Particle.DustOptions(BLOOD, 0.9f));
    }

    /** A thin trail of red mist weeping off the flayed body — low count, short-lived. */
    private void bloodTrail(LivingEntity victim) {
        Location body = victim.getLocation().add(0, 1.0, 0);
        victim.getWorld().spawnParticle(Particle.DUST, body, 3, 0.18, 0.30, 0.18, 0.0,
                new Particle.DustOptions(BLOOD, 0.8f));
    }

    /** A soft whiff when a swing falls short of the close reach. */
    private void whiff(Player attacker) {
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.5f, 1.4f);
        attacker.sendActionBar(EgoHud.status("Too far", FAINT));
    }

    private static float jitter() {
        return (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.12f;
    }

    // ---- passive: seal the off hand -------------------------------------------------

    /**
     * While Cobalt Scar is in the main hand the off hand is kept empty — no shield, no totem, no
     * attribute-item swapping mid-flurry. Any off-hand item is pushed into the main inventory (or
     * dropped at the wielder's feet if the pack is full), and we flash a one-off note the first time.
     * When the weapon is sheathed we disengage and forget the note so re-equipping can re-announce.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            offhandNotified.remove(id); // sheathed -> go idle, reset the one-off note
            return false;
        }

        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            player.getInventory().setItemInOffHand(null);
            var overflow = player.getInventory().addItem(off);
            for (ItemStack left : overflow.values()) {
                if (left != null && !left.getType().isAir()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
            }
            if (offhandNotified.add(id)) {
                // A one-off seal cue; let it stand this tick, then the persistent Dash bar resumes next tick.
                player.sendActionBar(EgoHud.status("Off hand sealed", FAINT));
                return true;
            }
        }

        renderBar(player);
        return true;
    }

    /**
     * Dash is Cobalt Scar's only cooldown, so its readout is the whole line: its rest while cooling, else
     * ready — kept always on screen rather than only flashed on a blocked cast.
     */
    private void renderBar(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long ready = dashReadyAt.getOrDefault(id, 0L);
        player.sendActionBar(now < ready
                ? EgoHud.cooldown("Dash", ready - now, FAINT)
                : EgoHud.ready("Dash", COBALT));
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // The quitter may have been a bleeding victim and/or a wielder with a dash on cooldown.
        BukkitTask task = bleeds.remove(id);
        if (task != null) task.cancel();
        ticking.remove(id);
        dashReadyAt.remove(id);
        offhandNotified.remove(id);
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : bleeds.values()) task.cancel();
        bleeds.clear();
        ticking.clear();
        offhandNotified.clear();
    }

    // ---- lore ---------------------------------------------------------------------

    /** Primary — cobalt blue. Display name, "How to use:", ability headers. */
    private static final TextColor COBALT = TextColor.color(0x2E6BE6);
    /** Secondary — the pale ice off the cobalt. The Abnormality title line. */
    private static final TextColor PALE   = TextColor.color(0xB9CBE8);
    /** The action-bar voice: cooldowns, "Too far", the off-hand note. */
    private static final TextColor FAINT  = TextColor.color(0x6C7C97);

    // Particle colors (kept apart from the lore palette so tuning one never disturbs the other).
    private static final Color COBALT_DEEP = Color.fromRGB(40, 96, 230);   // deep cobalt claw-spark
    private static final Color COBALT_ICE  = Color.fromRGB(140, 190, 255); // bright ice-blue flare (dash)
    private static final Color BLOOD       = Color.fromRGB(0x8A, 0x0B, 0x0B); // flayed-flesh red

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Cobalt Scar",             // display name — always the weapon
            "Big and Will be Bad Wolf", // title line — always the Abnormality
            COBALT,
            PALE,
            List.of(
                    "Claws of a vicious wolf. Once, they",
                    "cut open bellies and tore out guts.",
                    "",
                    "It flays the flesh and makes the",
                    "target bleed without end."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Close-Range Flurry",
                            "No swing cooldown — every blow lands",
                            "at full force. Only reaches 2.2",
                            "blocks, though: a swing from further",
                            "out is nullified entirely."),
                    new EgoLore.Ability("[Left Click] Flaying Bleed",
                            "Every cut that lands opens a wound —",
                            "half a heart every 0.5s for 2",
                            "seconds. A fresh cut refreshes it",
                            "rather than stacking it."),
                    new EgoLore.Ability("[Right Click] Dash",
                            "A short forward lunge. 7 second",
                            "cooldown."),
                    new EgoLore.Ability("[Passive] Off Hand Seal",
                            "While held, your off hand is kept",
                            "empty — anything in it is pushed back",
                            "into your inventory.")
            ));
}
