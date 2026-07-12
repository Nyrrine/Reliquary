package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The lab stations (§35) as block front-ends. Right-click the assigned block and it runs that step of the
 * pipeline on you, calling the same logic as the matching {@code /cogito} command:
 *
 * <ul>
 *   <li><b>Cauldron</b> → the Font (draw Enkephalin)</li>
 *   <li><b>Blast Furnace</b> → the Alembic (make a vial)</li>
 *   <li><b>Brewing Stand</b> → the Censer (titrate the held reagent item into your vial)</li>
 *   <li><b>Grindstone</b> → the Centrifuge (distill)</li>
 *   <li><b>Chiseled Bookshelf</b> → the Manifold (blend)</li>
 *   <li><b>Vault</b> → the Pocket Well (right-click = pour, sneak = forge the best catalyst you can afford)</li>
 * </ul>
 *
 * <p>The event is cancelled so the vanilla block GUI doesn't also fire. (Until attunement lands in §35.1,
 * <i>any</i> block of these types is a station — fine on a private lab server.) The Lectern is handled by
 * {@link LecternInfo}.
 */
public final class StationListener implements Listener {

    private final ExtractionCommand extraction;

    public StationListener(ExtractionCommand extraction) {
        this.extraction = extraction;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // main hand only — no off-hand double-fire
        if (event.getClickedBlock() == null) return;

        var player = event.getPlayer();
        switch (event.getClickedBlock().getType()) {
            case CAULDRON, WATER_CAULDRON -> { event.setCancelled(true); extraction.stationFont(player); }
            case BLAST_FURNACE -> { event.setCancelled(true); extraction.stationAlembic(player); }
            case BREWING_STAND -> {
                event.setCancelled(true);
                extraction.stationCenser(player, player.getInventory().getItemInMainHand());
            }
            case GRINDSTONE -> { event.setCancelled(true); extraction.stationCentrifuge(player); }
            case CHISELED_BOOKSHELF -> { event.setCancelled(true); extraction.stationManifold(player); }
            case VAULT -> { event.setCancelled(true); extraction.stationWell(player, player.isSneaking()); }
            default -> { /* not a station block */ }
        }
    }
}
