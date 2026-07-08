package com.nyrrine.reliquary.weapons.laevateinn;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Lævateinn only bites through its own moves. A raw sword swing does nothing: this guard cancels
 * vanilla melee (and its sweep) from anyone holding the blade, so it can't be spam-clicked or used
 * as a normal sword. The blade's own combo/skill hits are flagged (via {@link LaevateinnWeapon#dealDamage})
 * and pass straight through. Combined with the attack-speed freeze while the M1 combo cools, the sword
 * is effectively unswingable until its combo is ready.
 */
public final class LaevateinnMelee implements Listener {

    private final LaevateinnWeapon weapon;

    public LaevateinnMelee(LaevateinnWeapon weapon) {
        this.weapon = weapon;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (weapon.isDealing()) return; // the blade's own move — let it through
        if (!(event.getDamager() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!weapon.matches(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true); // raw sword swing — no damage; use the combo
    }
}
