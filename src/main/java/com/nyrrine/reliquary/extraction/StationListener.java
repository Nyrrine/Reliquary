package com.nyrrine.reliquary.extraction;

import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The lab stations (§35) as crafted, placed custom blocks. Placing a station item registers that block's
 * location as a station ({@link Stations}); breaking it gives the station item back and de-registers it.
 * Right-clicking a <i>registered</i> station block runs its pipeline step (the same logic as the matching
 * {@code /cogito} command). Ordinary blocks of the same type are untouched.
 */
public final class StationListener implements Listener {

    private final ExtractionCommand extraction;
    private final Stations stations;

    public StationListener(ExtractionCommand extraction, Stations stations) {
        this.extraction = extraction;
        this.stations = stations;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        StationType type = StationType.fromItem(event.getItemInHand());
        if (type != null) stations.register(event.getBlockPlaced(), type);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        StationType type = stations.typeAt(event.getBlock());
        if (type == null) return;
        stations.unregister(event.getBlock());
        event.setDropItems(false); // don't drop the plain vanilla block
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5),
                    type.createItem());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // main hand only — no off-hand double-fire
        if (event.getClickedBlock() == null) return;

        StationType type = stations.typeAt(event.getClickedBlock());
        if (type == null) return; // an ordinary block — leave it vanilla

        event.setCancelled(true); // it's a station — suppress the vanilla GUI and run the step
        var player = event.getPlayer();
        switch (type) {
            case LECTERN    -> extraction.describeItem(player, player.getInventory().getItemInMainHand());
            case FONT       -> extraction.stationFont(player);
            case ALEMBIC    -> extraction.stationAlembic(player);
            case CENSER     -> extraction.stationCenser(player, player.getInventory().getItemInMainHand());
            case CENTRIFUGE -> extraction.stationCentrifuge(player);
            case MANIFOLD   -> extraction.stationManifold(player);
            case WELL       -> extraction.stationWell(player, player.isSneaking());
        }
    }
}
