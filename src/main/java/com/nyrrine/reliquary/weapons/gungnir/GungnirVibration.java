package com.nyrrine.reliquary.weapons.gungnir;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Gungnir's canon ability: while it's buried in a body, its vibrations amplify the force of the
 * strikes that follow. Any hit on a marked body — from the thrower, another player, or another mob —
 * lands harder and throws off extra "vibration" particles.
 */
public final class GungnirVibration implements Listener {

    /** How much harder a struck-while-embedded body is hit. */
    private static final double AMPLIFY = 1.5; // +50% force

    private final GungnirWeapon weapon;

    public GungnirVibration(GungnirWeapon weapon) {
        this.weapon = weapon;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStrike(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (!weapon.isEmbedded(victim.getUniqueId())) return;
        event.setDamage(event.getDamage() * AMPLIFY);
        GungnirSpear.vibrationHit(victim);
    }
}
