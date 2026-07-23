package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carmen's Brain as a deployed floating entity — NOT a placed block. Right-clicking a surface with the Brain
 * item deploys the floating brain/hitbox ({@link CarmenBrainVfx}) and consumes the item (the head block is
 * never placed). You no longer touch the Brain to extract: holding an Extraction Ticket <i>near</i> a deployed
 * Brain, a right-click anywhere previews and a sneak right-click pulls (see {@link #onExtract}). Punching the
 * Brain still knocks it loose and drops the item back. This listener also guards the plugin's cosmetic items
 * against being eaten by vanilla crafts.
 */
public final class StationListener implements Listener {

    /** How near (blocks, eye→brain-centre) a deployed Brain must be to extract with a held ticket. Placeholder. */
    private static final double PREVIEW_RANGE = 16.0;

    private final ExtractionCommand extraction;
    private final CarmenBrainVfx brainVfx;

    // At most one extraction per player per tick — belt-and-braces so the entity-click path (below) can never
    // double-fire an extract alongside the proximity path if a client emits both events on one click.
    private final Map<UUID, Integer> lastExtractTick = new HashMap<>();

    public StationListener(ExtractionCommand extraction, CarmenBrainVfx brainVfx) {
        this.extraction = extraction;
        this.brainVfx = brainVfx;
    }

    /** Claim this player's single extraction slot for the current tick; false if one already fired this tick. */
    private boolean claimExtract(Player player) {
        int tick = Bukkit.getCurrentTick();
        Integer prev = lastExtractTick.put(player.getUniqueId(), tick);
        return prev == null || prev != tick;
    }

    /**
     * Proximity extract: holding an Extraction Ticket within {@link #PREVIEW_RANGE} of a deployed Carmen's Brain,
     * a right-click anywhere (air or block) previews, and a sneak right-click pulls. No need to touch the Brain.
     */
    @EventHandler
    public void onExtract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!ExtractionTicket.matches(event.getItem())) return;

        Player player = event.getPlayer();
        Location brain = brainVfx.nearestWell(player.getEyeLocation(), PREVIEW_RANGE);
        if (brain == null) {
            player.sendActionBar(Component.text("No Carmen's Brain near.").color(NamedTextColor.GRAY));
            return;
        }
        event.setCancelled(true); // don't let the ticket do anything vanilla with the click
        if (!claimExtract(player)) return;
        extraction.stationWell(player, brain, player.isSneaking());
    }

    /** Right-click a surface holding a Carmen's Brain: deploy the floating entity instead of placing a head. */
    @EventHandler
    public void onDeploy(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (StationType.fromItem(event.getItem()) != StationType.WELL) return;

        event.setCancelled(true); // never place the head as a block
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir()) {
            event.getPlayer().sendActionBar(Component.text("No room to deploy Carmen's Brain here.")
                    .color(NamedTextColor.RED));
            return;
        }
        brainVfx.deploy(event.getPlayer(), target.getLocation());
    }

    /** Belt-and-braces: the Carmen's Brain head must never place as a vanilla block. */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (StationType.fromItem(event.getItemInHand()) == StationType.WELL) event.setCancelled(true);
    }

    /**
     * Entity-click paths. Right-clicking the floating Brain routes to the pull (legacy). Right-clicking a floating
     * <i>preview weapon</i> ({@link WellDisplay#TAG} hitbox) would otherwise swallow the click as an entity
     * interaction — so with a ticket held we route it to the same proximity extract, and the crosshair landing on
     * a weapon still previews/pulls. The per-tick claim guards both from double-firing alongside {@link #onExtract}.
     * (Left-click batting of the preview weapons is polled inside {@link WellDisplay}; no listener here.)
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Location loc = brainVfx.locationOf(event.getRightClicked());
        if (loc != null) { // the Brain hitbox
            event.setCancelled(true);
            if (!claimExtract(player)) return;
            extraction.stationWell(player, loc, player.isSneaking());
            return;
        }
        if (event.getRightClicked().getScoreboardTags().contains(WellDisplay.TAG)
                && ExtractionTicket.matches(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            Location brain = brainVfx.nearestWell(player.getEyeLocation(), PREVIEW_RANGE);
            if (brain != null && claimExtract(player)) {
                extraction.stationWell(player, brain, player.isSneaking());
            }
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
            event.getPlayer().sendActionBar(Component.text("A Cogito vial isn't for drinking.")
                    .color(NamedTextColor.RED));
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
        for (ItemStack it : event.getInventory().getMatrix()) {
            if (it == null) continue;
            if (Catalyst.matches(it) || SinConcentrate.matches(it) || Cogito.matches(it)
                    || RawCogito.matches(it) || Enkephalin.matches(it) || ExtractionTicket.matches(it)
                    || Cosmetics.isEmberDistillate(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
