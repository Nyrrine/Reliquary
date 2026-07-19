package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Heaven — "The Burrowing Heaven" (Lobotomy Corp E.G.O, WAW). A macabre blade in crimson and dark red,
 * marked by the single large sickly-yellow eye that stares from its heart. Its heaven is not a light
 * above but a thing that watches, and burrows.
 *
 * <p>A pure melee weapon: it rides the vanilla NETHERITE_SWORD swing (never cancelled), which deals its
 * normal damage and wears the blade one point per hit — nothing extra is needed for durability. The soul
 * of the weapon is the gaze gimmick in {@link #onHit}: the abnormality feeds on being <b>seen</b>. When a
 * struck victim is roughly facing their attacker — looking straight into the eye — the blow bites
 * {@link #DAMAGE_MULT harder} and, on a {@link #STUN_CHANCE small chance}, the heaven opens beneath them:
 * a brief stasis that pins the victim in place while crimson rises from the ground and a great
 * eye-yellow ring stares up around them.
 *
 * <p>The stasis is driven by a self-cancelling {@link StasisTask}: each tick it zeroes the target's
 * velocity (killing walk, sprint <b>and</b> jump — nothing survives a reset velocity) and re-applies a
 * crushing Slowness, then paints the eye. For mobs it also cuts {@link Mob#setAI(boolean) AI} for the
 * duration and restores it on completion — guarded so the AI is restored even if the target dies mid-hold.
 * Players cannot be fully frozen server-side without movement packets, so for players the stasis is a
 * best-effort root: per-tick velocity zero + Slowness 6 + the same heavy VFX. A determined player can
 * still nudge themselves, but only barely.
 *
 * <p>The bonus damage is applied via {@code event.setDamage(...)} (never {@code victim.damage()} — that
 * would re-enter this dispatch). Per-victim stasis state is tracked so overlapping procs don't stack tasks
 * or leak a mob's AI-off flag; it is cleared on quit and on disable.
 */
public final class HeavenWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Heaven. */
    private final NamespacedKey key;

    /** Entities currently held in stasis — guards against stacking tasks / leaking a mob's AI flag. */
    private final Set<UUID> stunned = new HashSet<>();

    /** Live stasis holds — reaped on disable so a mob frozen at reload-time gets its AI restored. */
    private final Set<StasisTask> activeStasis = new HashSet<>();

    // Tuning — the gaze rewards striking a victim who is looking at you.
    /** Dot of the victim's facing vs. the direction to the attacker above which they count as "looking". */
    private static final double LOOK_THRESHOLD = 0.4;
    /** Bonus damage multiplier when the victim is looking at the attacker (+10%). */
    private static final double DAMAGE_MULT    = 1.1;
    /** Chance, on a looking hit, that the heaven opens and pins the victim. */
    private static final double STUN_CHANCE    = 0.25;
    /** How long the stasis holds — ~1.5s. */
    private static final int    STASIS_TICKS   = 30;
    /** Slowness amplifier during stasis — 6 → Slowness VII, a near-total crawl on top of the velocity zero. */
    private static final int    SLOWNESS_AMP   = 6;

    public HeavenWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "heaven");
    }

    @Override
    public String id() {
        return "heaven";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.HEAVEN.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.HEAVEN.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.HEAVEN);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: the gaze rewards being seen --------------------------------------

    /**
     * A landed blow. Vanilla netherite damage (and its one point of blade wear) is left intact. If the
     * victim is roughly facing the attacker — looking into the eye — the blow bites +10% harder, and on a
     * {@link #STUN_CHANCE} roll the heaven opens beneath them: a brief stasis that pins them in place.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (!isLookingAt(victim, attacker)) return;

        // Struck while staring into the eye — the wound bites harder.
        event.setDamage(event.getDamage() * DAMAGE_MULT);

        if (ThreadLocalRandom.current().nextDouble() < STUN_CHANCE) {
            openHeaven(victim);
        }
    }

    /**
     * True if {@code victim} is roughly facing {@code attacker}: the victim's facing direction dotted
     * against the (attacker - victim) direction clears {@link #LOOK_THRESHOLD}.
     */
    private boolean isLookingAt(LivingEntity victim, LivingEntity attacker) {
        Vector facing = victim.getLocation().getDirection();
        if (facing.lengthSquared() < 1.0e-6) return false;
        Vector toAttacker = attacker.getLocation().toVector().subtract(victim.getLocation().toVector());
        if (toAttacker.lengthSquared() < 1.0e-6) return false;
        return facing.normalize().dot(toAttacker.normalize()) >= LOOK_THRESHOLD;
    }

    // ---- the stasis: heaven burrows and holds --------------------------------------

    /** Open the heaven beneath a victim: pin them for {@link #STASIS_TICKS}, cutting mob AI for the hold. */
    private void openHeaven(LivingEntity victim) {
        UUID id = victim.getUniqueId();
        if (!stunned.add(id)) return; // already held — don't stack tasks or double-touch the AI flag

        // Cut AI for the duration so mobs don't path/attack while pinned. Restored on task completion.
        if (victim instanceof Mob mob) {
            plugin.weapons().suspendAi(mob);
        }

        openSfx(victim.getLocation());
        StasisTask task = new StasisTask(victim);
        activeStasis.add(task); // tracked so onDisable can restore a frozen mob's AI on reload
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * The per-tick hold. Zeroes velocity (killing walk/sprint/jump alike), re-applies crushing Slowness,
     * and paints the crimson-and-eye VFX. Self-cancels after {@link #STASIS_TICKS} ticks or once the
     * target is gone, restoring a mob's AI on the way out — guarded so it restores even if the target died.
     */
    private final class StasisTask extends BukkitRunnable {
        private final LivingEntity target;
        private int ticks = 0;

        StasisTask(LivingEntity target) {
            this.target = target;
        }

        @Override
        public void run() {
            if (ticks++ >= STASIS_TICKS || !target.isValid() || target.isDead()) {
                finish();
                return;
            }

            // Pin: reset velocity every tick so nothing — walk, sprint, or jump — can carry them.
            target.setVelocity(new Vector(0, 0, 0));
            // Crushing crawl on top of the velocity zero; refreshed so it never lapses mid-hold.
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 6, SLOWNESS_AMP, false, false, false));

            stasisVfx(target.getLocation());
        }

        private void finish() {
            stunned.remove(target.getUniqueId());
            if (target instanceof Mob mob) {
                plugin.weapons().restoreAi(mob);
            }
            activeStasis.remove(this);
            cancel();
        }

        /** Disable-time reap: restore a still-frozen mob's AI and cancel; the caller clears the tracking set. */
        void shutdown() {
            if (target instanceof Mob mob) {
                plugin.weapons().restoreAi(mob);
            }
            cancel();
        }
    }

    // ---- presentation --------------------------------------------------------------

    /** The eye opening: a low burrowing rumble under an unblinking stare. Pitch-jittered per proc. */
    private void openSfx(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(at, Sound.ENTITY_WARDEN_DIG, 0.9f, 0.5f + rng.nextFloat() * 0.10f);      // the burrow
        world.playSound(at, Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.7f + rng.nextFloat() * 0.10f);  // the gaze
    }

    /**
     * The stasis mark: crimson motes rising from the ground beneath the pinned target, and a large
     * sickly eye-yellow ring staring up around their feet with a crimson pupil at its heart.
     */
    private void stasisVfx(Location at) {
        World world = at.getWorld();
        Location feet = at.clone().add(0, 0.05, 0);

        // Crimson / dark-red rising from the ground beneath them.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.3, 0), 6, 0.28, 0.45, 0.28, 0, CRIMSON_DUST);
        world.spawnParticle(Particle.DUST, feet, 3, 0.30, 0.05, 0.30, 0, DARKRED_DUST);

        // The eye: a large eye-yellow ring around the feet...
        final int points = 20;
        final double radius = 1.6;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * radius, 0.02, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.01, 0.02, 0, EYE_DUST);
        }
        // ...with a crimson pupil at its heart.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.05, 0), 4, 0.18, 0.02, 0.18, 0, CRIMSON_DUST);
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        stunned.remove(id);
    }

    @Override
    public void onDisable() {
        // A mob frozen at disable/reload-time would stay AI-disabled after reload — cancel every live hold
        // and restore any still-valid frozen mob's AI before clearing state.
        for (StasisTask task : new ArrayList<>(activeStasis)) {
            task.shutdown();
        }
        activeStasis.clear();
        stunned.clear();
    }

    // ---- colours / particles ------------------------------------------------------

    /** Primary — the blade's crimson. Display name, "How to use:", ability headers. */
    private static final TextColor CRIMSON = TextColor.color(0xDC143C);
    /**
     * Secondary — the sickly yellow of the eye at the blade's heart. The Abnormality title line.
     *
     * <p>This colour has always been in Heaven's palette; it used to pick out the single word "eye" inside
     * the flavour line. The shared tooltip paints the whole flavour block in one off-white, so the yellow
     * moves up to the title line, where the Abnormality the eye belongs to now carries it.
     */
    private static final TextColor EYE     = TextColor.color(0xE6C74C);

    private static final Color CRIMSON_RGB = Color.fromRGB(0xDC, 0x14, 0x3C); // rising motes / pupil
    private static final Color DARKRED_RGB = Color.fromRGB(0x8B, 0x00, 0x00); // ground accent
    private static final Color EYE_RGB     = Color.fromRGB(0xE6, 0xC7, 0x4C); // the staring ring
    private static final Particle.DustOptions CRIMSON_DUST = new Particle.DustOptions(CRIMSON_RGB, 1.1f);
    private static final Particle.DustOptions DARKRED_DUST = new Particle.DustOptions(DARKRED_RGB, 1.3f);
    private static final Particle.DustOptions EYE_DUST     = new Particle.DustOptions(EYE_RGB, 1.2f);

    // ---- lore ---------------------------------------------------------------------

    // The whole weapon is one melee gimmick, so both entries below are [Left Click]: there is no
    // onInteract and no onTick on this relic, and nothing here fires unless a swing lands. The old
    // how-to read "Its gaze may pin them in stasis", which suggested the pin was its own thing; the
    // roll in onHit sits INSIDE the isLookingAt guard, so a foe who is not facing you can never be
    // pinned at all. The moveset follows the code.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Heaven",
            "The Burrowing Heaven",
            CRIMSON,
            EYE,
            List.of(
                    "Just contain it in your sight.",
                    "A great yellow eye watches within."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Eye Contact Bonus",
                            "Melee hits on a foe who is facing",
                            "you deal +10% damage. A foe looking",
                            "away takes no bonus."),
                    new EgoLore.Ability("[Left Click] Stasis Pin",
                            "Each hit that lands the eye contact",
                            "bonus has a 25% chance to open the",
                            "heaven: the foe is pinned in place",
                            "for 1.5 seconds, crushed to a crawl.",
                            "Mobs also lose their AI for the hold.")
            ));
}
