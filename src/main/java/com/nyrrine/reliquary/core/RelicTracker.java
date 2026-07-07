package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks every relic that exists in the world by a unique per-item instance id.
 *
 * A relic handed out by command is stamped with a fresh id and remembered. A
 * periodic scan finds where each one is — in a player's inventory or ender
 * chest, dropped on the ground, or sitting in a container — and if a relic gets
 * duplicated (e.g. cloned in creative), the copy is re-stamped with its own id
 * and flagged, so admins can always tell instances apart and see where they lie.
 *
 * Only loaded chunks can be scanned; a relic in an unloaded chunk is invisible
 * until that chunk loads again (and dropped items despawn after ~5 minutes).
 */
public final class RelicTracker implements Listener {

    private final Reliquary plugin;
    private final NamespacedKey instanceKey; // per-item unique id (UUID string)
    private final NamespacedKey typeKey;     // which relic this is (weapon id)

    /** A tracked relic instance, as of the last scan. */
    public record Entry(UUID id, String weaponId, String origin, String where) {}

    private final Map<UUID, String> origin = new HashMap<>();     // "given to X" / "cloned ..." / "found"
    private final Map<UUID, String> weaponType = new HashMap<>();
    private final Map<UUID, String> where = new HashMap<>();      // live, from the last scan

    // scratch tables rebuilt each scan
    private Map<UUID, String> nWhere, nOrigin, nType;

    public RelicTracker(Reliquary plugin) {
        this.plugin = plugin;
        this.instanceKey = new NamespacedKey(plugin, "relic_instance");
        this.typeKey = new NamespacedKey(plugin, "relic_type");
    }

    public void start() {
        // No polling. Locations are resolved on demand via /reliquary track. The
        // only live hook is a cheap guard below that re-tags a relic the instant
        // it's set in a creative slot, so a creative clone can never share an id.
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** The moment a creative player places a relic, give that copy its own id. */
    @EventHandler(ignoreCancelled = true)
    public void onCreativeSet(InventoryCreativeEvent event) {
        ItemStack placed = event.getCursor();
        if (instanceOf(placed) == null) return;
        ItemStack fresh = placed.clone();
        write(fresh, UUID.randomUUID(), typeOf(fresh));
        event.setCursor(fresh);
    }

    /** Stamp a freshly created relic with a unique id and record who received it. */
    public ItemStack register(ItemStack item, String weaponId, String givenTo) {
        UUID iid = UUID.randomUUID();
        write(item, iid, weaponId);
        origin.put(iid, "given to " + givenTo);
        weaponType.put(iid, weaponId);
        return item;
    }

    public UUID instanceOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null; // cheap skip for plain items
        ItemMeta m = item.getItemMeta();
        if (m == null) return null;
        String s = m.getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isRelic(ItemStack item) {
        return instanceOf(item) != null;
    }

    private void write(ItemStack item, UUID iid, String weaponId) {
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(instanceKey, PersistentDataType.STRING, iid.toString());
        if (weaponId != null) pdc.set(typeKey, PersistentDataType.STRING, weaponId);
        item.setItemMeta(m);
    }

    private String typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "?";
        ItemMeta m = item.getItemMeta();
        if (m == null) return "?";
        String s = m.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return s == null ? "?" : s;
    }

    /** Something that writes a (possibly re-tagged) stack back where it came from. */
    @FunctionalInterface
    private interface Sink { void store(ItemStack stack); }

    /**
     * Full scan: players (+ ender chests), dropped items, and loaded containers.
     * Rebuilds the tables so only relics that still exist are tracked, and gives
     * any duplicated copy its own new id.
     */
    public void scan() {
        nWhere = new HashMap<>();
        nOrigin = new HashMap<>();
        nType = new HashMap<>();

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            scanInventory(p.getInventory(), p.getName());
            scanInventory(p.getEnderChest(), p.getName() + " (ender chest)");
        }

        for (World world : plugin.getServer().getWorlds()) {
            for (Item drop : world.getEntitiesByClass(Item.class)) {
                consider(drop.getItemStack(), "on ground @ " + loc(drop.getLocation()),
                        drop::setItemStack);
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState bs : chunk.getTileEntities(false)) {
                    if (!(bs instanceof Container container)) continue;
                    // Chests can be double — use just this block's half so we don't double-count.
                    Inventory inv = (bs instanceof Chest chest) ? chest.getBlockInventory()
                            : container.getInventory();
                    scanInventory(inv, container.getType() + " @ " + loc(bs.getLocation()));
                }
            }
        }

        where.clear(); where.putAll(nWhere);
        origin.clear(); origin.putAll(nOrigin);
        weaponType.clear(); weaponType.putAll(nType);
        nWhere = nOrigin = nType = null;
    }

    private void scanInventory(Inventory inv, String label) {
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            final int s = slot;
            consider(contents[slot], label, stack -> inv.setItem(s, stack));
        }
    }

    /** Record one relic stack at {@code label}; if its id was already seen, re-tag this copy. */
    private void consider(ItemStack it, String label, Sink sink) {
        UUID iid = instanceOf(it);
        if (iid == null) return;
        String wid = typeOf(it);

        if (nWhere.containsKey(iid)) {
            UUID neu = UUID.randomUUID();
            write(it, neu, wid);
            sink.store(it);
            nWhere.put(neu, label);
            nType.put(neu, wid);
            nOrigin.put(neu, "cloned (was " + shortId(iid) + ")");
            plugin.getLogger().warning("Relic duplicate " + shortId(iid)
                    + " re-tagged " + shortId(neu) + " (" + label + ")");
        } else {
            nWhere.put(iid, label);
            nType.put(iid, wid);
            nOrigin.put(iid, origin.getOrDefault(iid, "found")); // keep provenance if known
        }
    }

    /** Every currently-tracked relic instance (runs a fresh scan first). */
    public List<Entry> list() {
        scan();
        List<Entry> out = new ArrayList<>();
        for (UUID id : origin.keySet()) {
            out.add(new Entry(id,
                    weaponType.getOrDefault(id, "?"),
                    origin.getOrDefault(id, "?"),
                    where.getOrDefault(id, "(unknown)")));
        }
        return out;
    }

    /** Remove all relic items from a player's inventory and ender chest. Returns the count. */
    public int purge(Player player) {
        return stripRelics(player.getInventory()) + stripRelics(player.getEnderChest());
    }

    private int stripRelics(Inventory inv) {
        int removed = 0;
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isRelic(contents[slot])) {
                inv.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    private static String loc(Location l) {
        String w = l.getWorld() != null ? l.getWorld().getName() : "?";
        return w + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
