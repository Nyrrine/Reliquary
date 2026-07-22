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
 * Carmen's Brain as a crafted, placed custom block. Placing a Brain item registers that block's location
 * ({@link Stations}) and its idle show grows there ({@link CarmenBrainVfx}); breaking it gives the item back,
 * de-registers it, and reaps the show. Right-clicking a registered Brain runs the ticket pull. Ordinary heads
 * are untouched. This listener also guards the plugin's cosmetic items against being eaten by vanilla crafts.
 */
public final class StationListener implements Listener {

    private final ExtractionCommand extraction;
    private final Stations stations;
    private final CarmenBrainVfx brainVfx;

    public StationListener(ExtractionCommand extraction, Stations stations, CarmenBrainVfx brainVfx) {
        this.extraction = extraction;
        this.stations = stations;
        this.brainVfx = brainVfx;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        StationType type = StationType.fromItem(event.getItemInHand());
        if (type != null) stations.register(event.getBlockPlaced(), type);
        // The idle VFX manager polls placed Brains and grows the show on its next frame — nothing to spawn here.
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        StationType type = stations.typeAt(event.getBlock());
        if (type == null) return;
        stations.unregister(event.getBlock());
        brainVfx.onRemoved(event.getBlock().getLocation()); // reap the floating brain/nerves at once
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

    /** A Cogito is a lore vial, not a drink — cancel the swallow so the vial isn't lost. */
    @EventHandler
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (Cogito.matches(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(net.kyori.adventure.text.Component
                    .text("A Cogito vial isn't for drinking.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * The plugin's cosmetic items share vanilla Materials (dyes, nether star, slime ball, fire charge…), and
     * vanilla crafting matches by Material ignoring PDC — so a vanilla recipe would silently destroy/convert
     * one. Block any vanilla craft that consumes one; our own {@code reliquary:} recipes are allowed through.
     */
    @EventHandler
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        var recipe = event.getRecipe();
        if (recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals("reliquary")) return;
        for (org.bukkit.inventory.ItemStack it : event.getInventory().getMatrix()) {
            if (it == null) continue;
            if (Catalyst.matches(it) || SinConcentrate.matches(it) || Cogito.matches(it)
                    || RawCogito.matches(it) || Enkephalin.matches(it) || ExtractionTicket.matches(it)
                    || Cosmetics.isEmberDistillate(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // A Brain removed by anything other than a player break (explosion, piston) still needs de-registering and
    // reaping, else a rebuild at the spot desyncs against the stale registry entry and orphan displays linger.
    @EventHandler
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) { cleanup(event.blockList()); }
    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) { cleanup(event.blockList()); }
    @EventHandler
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) { cleanup(event.getBlocks()); }
    @EventHandler
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) { cleanup(event.getBlocks()); }

    private void cleanup(java.util.List<org.bukkit.block.Block> blocks) {
        for (org.bukkit.block.Block b : blocks) {
            if (stations.typeAt(b) != null) {
                stations.unregister(b);
                brainVfx.onRemoved(b.getLocation());
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // main hand only — no off-hand double-fire
        if (event.getClickedBlock() == null) return;

        StationType type = stations.typeAt(event.getClickedBlock());
        if (type != StationType.WELL) return; // an ordinary block — leave it vanilla

        event.setCancelled(true); // it's a Carmen's Brain — suppress vanilla interaction and run the pull
        var player = event.getPlayer();
        extraction.stationWell(player, event.getClickedBlock().getLocation(), player.isSneaking());
    }
}
