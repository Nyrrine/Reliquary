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
        if (type == StationType.FONT) extraction.clearFont(event.getBlock().getLocation()); // reset compost fill
        if (type == StationType.CENSER) extraction.censerReturnOnBreak(event.getBlock().getLocation()); // return seated vial
        event.setDropItems(false); // don't drop the plain vanilla block
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5),
                    type.createItem());
        }
    }

    /**
     * Enkephalin is an unthrowable bottle of essence — cancel the vanilla XP-bottle throw on any right-click
     * with it. Checks the acting hand's item ({@code getItem()}), so it holds for the off hand too.
     */
    @EventHandler
    public void onThrow(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (Enkephalin.matches(event.getItem())) event.setCancelled(true);
    }

    /** A Cogito is a volatile emotional distillate, not a drink — cancel the swallow so the vial isn't lost. */
    @EventHandler
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (Cogito.matches(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(net.kyori.adventure.text.Component
                    .text("A Cogito vial isn't for drinking — pour it at the Well.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * Plugin items (refined reagents, concentrates, catalysts, Cogito/Raw Cogito/Enkephalin, tickets) share
     * vanilla Materials (dyes, lapis block, copper ingot, slime ball, nether star…), and vanilla crafting
     * matches by Material ignoring PDC — so a vanilla recipe would silently destroy/convert them (e.g. a
     * Blinding Pride = Lapis Block uncrafted into 9 lapis). Block any vanilla craft that consumes one; our own
     * {@code reliquary:} refining recipes are allowed through.
     */
    @EventHandler
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        var recipe = event.getRecipe();
        if (recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals("reliquary")) return;
        for (org.bukkit.inventory.ItemStack it : event.getInventory().getMatrix()) {
            if (it == null) continue;
            if (Catalyst.matches(it) || RefinedReagent.idOf(it) != null || SinConcentrate.sinOf(it) != null
                    || Cogito.matches(it) || RawCogito.matches(it) || Enkephalin.matches(it)
                    || ExtractionTicket.matches(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // Stations removed by anything other than a player break (explosion, piston) still need their state cleaned
    // up — otherwise a seated Censer vial + its floating display leak and a rebuild at the spot desyncs.
    @EventHandler
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) { cleanupStations(event.blockList()); }
    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) { cleanupStations(event.blockList()); }
    @EventHandler
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) { cleanupStations(event.getBlocks()); }
    @EventHandler
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) { cleanupStations(event.getBlocks()); }

    private void cleanupStations(java.util.List<org.bukkit.block.Block> blocks) {
        for (org.bukkit.block.Block b : blocks) {
            StationType t = stations.typeAt(b);
            if (t == null) continue;
            stations.unregister(b);
            if (t == StationType.FONT) extraction.clearFont(b.getLocation());
            if (t == StationType.CENSER) extraction.censerReturnOnBreak(b.getLocation());
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
        var held = player.getInventory().getItemInMainHand();
        switch (type) {
            case LECTERN    -> {
                if (held == null || held.getType().isAir()) extraction.assayOverview(player); // empty hand → chat assay
                else extraction.describeItem(player, held);        // holding → identify it (weapon/catalyst → track)
            }
            case FONT       -> extraction.stationFont(player, event.getClickedBlock().getLocation(), held);
            case ALEMBIC    -> extraction.stationAlembic(player, player.isSneaking());
            case CENSER     -> extraction.stationCenser(player, held,
                    event.getClickedBlock().getLocation(), player.isSneaking());
            case CENTRIFUGE -> extraction.stationCentrifuge(player);
            case MANIFOLD   -> extraction.stationManifold(player);
            case CRUCIBLE   -> extraction.stationCrucible(player, player.isSneaking());
            case WELL       -> extraction.stationWell(player,
                    event.getClickedBlock().getLocation(), player.isSneaking());
        }
    }
}
