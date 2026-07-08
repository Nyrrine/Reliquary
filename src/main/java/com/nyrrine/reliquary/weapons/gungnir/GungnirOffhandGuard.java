package com.nyrrine.reliquary.weapons.gungnir;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Keeps Gungnir out of the offhand from the inventory side. The swap-hands key (F) is handled by the
 * weapon itself; this catches the two menu vectors — pressing F while hovering the relic in an open
 * inventory (a SWAP_OFFHAND click), and dropping it onto the offhand slot with the cursor.
 */
public final class GungnirOffhandGuard implements Listener {

    /** Raw slot index of the offhand in the default player inventory view. */
    private static final int OFFHAND_RAW_SLOT = 45;

    private final GungnirWeapon weapon;

    public GungnirOffhandGuard(GungnirWeapon weapon) {
        this.weapon = weapon;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        // F pressed while hovering the relic -> would shove it into the offhand.
        if (event.getClick() == ClickType.SWAP_OFFHAND && weapon.matches(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        // Placing the relic directly onto the offhand slot with the cursor.
        if (event.getRawSlot() == OFFHAND_RAW_SLOT && weapon.matches(event.getCursor())) {
            event.setCancelled(true);
        }
    }
}
