package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ridiculous Grit — the blade won't let its wielder die on their feet.
 *
 * <p>For a long time the tooltip named this passive and the code held nothing behind the name: "Grit"
 * was only ever the seal-decay meter dressed up as a mechanic, and a killing blow killed you like any
 * other. The audit filed it as a ghost. This is the passive the name was always promising — a real
 * Totem of Undying baked into the sword, so Matthias grits his teeth and refuses to go down.
 *
 * <p>When a blow would drop the wielder to nothing and the blade is in their hand, the blow is eaten:
 * they're left on a sliver of health with the totem's own kit (Regeneration II, Fire Resistance,
 * Absorption II) and the totem's own light and sound. Then it sleeps for an hour, per player — spend
 * the save and the next lethal hit lands for real until the clock comes back around. Nothing is
 * persisted: a restart is a fresh hour, same as every other bit of this blade's state.
 */
final class LaevateinnGrit implements Listener {

    /** One hour between saves, per player. Kept in step with the tooltip's "once an hour". */
    private static final long COOLDOWN_MS = 3_600_000L;

    /** Health the save leaves the wielder on — a sliver, ~1 heart (clamped to their real max). */
    private static final double REVIVE_HEALTH = 2.0;

    // The vanilla Totem of Undying kit, at its own levels and canonical durations. Amplifier 1 reads as
    // level II. Retune here if the numbers ever want moving — nothing else reads them.
    private static final PotionEffect REGEN = new PotionEffect(PotionEffectType.REGENERATION, 900, 1);
    private static final PotionEffect FIRE_RES = new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0);
    private static final PotionEffect ABSORPTION = new PotionEffect(PotionEffectType.ABSORPTION, 100, 1);

    private final LaevateinnWeapon weapon;

    /** Per-player: when Grit is ready again (ms epoch). Absent = ready now. Cleared on quit and disable. */
    private final Map<UUID, Long> readyAt = new HashMap<>();

    LaevateinnGrit(Reliquary plugin, LaevateinnWeapon weapon) {
        this.weapon = weapon;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Lethal or nothing: getFinalDamage is the settled figure — armour, resistance, blocking and the
        // absorption it eats are already folded in — so this is the health the blow actually clears. If a
        // sliver survives, it isn't a killing blow and Grit stays out of it.
        if (event.getFinalDamage() < player.getHealth()) return;

        // The void and /kill go under a totem in vanilla; they go under this too, so the blade can't be
        // used to sit in the void or shrug off an admin kill.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;

        // Only the hand that holds the blade grits — off-hand it's inert, the same rule the rest of the
        // sword lives by.
        if (!weapon.matches(player.getInventory().getItemInMainHand())) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = readyAt.get(id);
        if (until != null && now < until) return; // still cooling — no save; the blow lands for real

        // Grit through it. Cancel the blow outright so no death fires, drop them to a sliver, hand them
        // the totem kit, and light it up. Then start the hour.
        event.setCancelled(true);
        player.setFireTicks(0);
        player.setHealth(Math.min(REVIVE_HEALTH, maxHealth(player)));
        player.setAbsorptionAmount(0); // let the Absorption effect set the gold hearts cleanly
        player.addPotionEffect(REGEN);
        player.addPotionEffect(FIRE_RES);
        player.addPotionEffect(ABSORPTION);
        // The totem's own light and sound — a burst of its particles up the body, and the horn.
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1, 0), 80, 0.4, 0.7, 0.4, 0.35);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);

        readyAt.put(id, now + COOLDOWN_MS);
    }

    private static double maxHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        return max != null ? max.getValue() : 20.0;
    }

    /** Drop one player's cooldown (they left). */
    void clear(UUID id) {
        readyAt.remove(id);
    }

    /** Drop every cooldown (plugin shutdown / reload). */
    void clearAll() {
        readyAt.clear();
    }
}
