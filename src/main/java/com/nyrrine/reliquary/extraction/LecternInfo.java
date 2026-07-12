package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The Lectern station (§35.3) — right-click a Lectern holding an item and it tells you what the lab knows
 * about it: a Cogito's full assay, a catalyst's lock, a reagent's fingerprint, or which catalysts a grind
 * component feeds. Empty hand (or a book) still uses the vanilla lectern, so this doesn't break normal use.
 */
public final class LecternInfo implements Listener {

    private final ExtractionCommand extraction;

    public LecternInfo(ExtractionCommand extraction) {
        this.extraction = extraction;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // main hand only — avoid the off-hand double-fire
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.LECTERN) return;

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return;                 // empty hand → read the vanilla lectern
        Material held = item.getType();
        if (held == Material.WRITTEN_BOOK || held == Material.WRITABLE_BOOK) return; // let vanilla place the book

        event.setCancelled(true); // suppress the vanilla book GUI; identify instead
        extraction.describeItem(event.getPlayer(), item);
    }
}
