package com.nyrrine.reliquary.busego.weapons;

import org.bukkit.Location;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * The Flower Burying Wedge's death-defiance passive and the lock-down for its cinematic.
 *
 * <ul>
 *   <li>A lethal blow while a wedge is on the wielder is intercepted: the wedge is consumed and the
 *       {@linkplain FlowerBuryingWedgeWeapon#defyDeath sky-reckoning} triggers instead of death.</li>
 *   <li>While a reckoning runs, the wielder is immune to all damage and cannot move, swap hotbar slots,
 *       drop, or rearrange items — they hang floating through the whole animation (they can still look).</li>
 *   <li>The finale's physics debris is render-only — a tagged falling block never places or drops.</li>
 * </ul>
 */
public final class FlowerBuryingWedgeReckoning implements Listener {

    private final FlowerBuryingWedgeWeapon weapon;

    public FlowerBuryingWedgeReckoning(FlowerBuryingWedgeWeapon weapon) {
        this.weapon = weapon;
    }

    /** Intercept a lethal blow to trigger the reckoning; and block all damage while one is already running. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (weapon.isReckoning(player.getUniqueId())) { event.setCancelled(true); return; } // immune mid-cinematic
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) return;                  // the passive doesn't save from the void
        if (player.getHealth() - event.getFinalDamage() > 0.0) return;                       // not lethal
        if (weapon.defyDeath(player)) event.setCancelled(true);
    }

    /** Lock the floating wielder in place — looking around is fine, moving is not. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location f = weapon.frozenSpot(event.getPlayer().getUniqueId());
        if (f == null) return;
        Location to = event.getTo();
        if (to == null) return;
        if (!to.getWorld().equals(f.getWorld()) || to.toVector().distanceSquared(f.toVector()) > 1.0e-4) {
            Location held = f.clone();
            held.setYaw(to.getYaw());
            held.setPitch(to.getPitch());
            event.setTo(held);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent event) {
        if (weapon.isReckoning(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (weapon.isReckoning(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (weapon.isReckoning(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (weapon.isReckoning(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (weapon.isReckoning(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** The finale's flying blocks are pure VFX — never let a tagged falling block place or drop. */
    @EventHandler(ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fb
                && fb.getScoreboardTags().contains(FlowerBuryingWedgeWeapon.DEBRIS_TAG)) {
            event.setCancelled(true);
            fb.remove();
        }
    }
}
