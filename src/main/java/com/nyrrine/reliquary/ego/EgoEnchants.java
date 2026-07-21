package com.nyrrine.reliquary.ego;

import com.nyrrine.reliquary.core.EgoWeapon;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The E.G.O enchant store: reinterpreted / invented enchants a weapon reads and re-expresses through its
 * own mechanic (Multishot -> more charges), kept in a custom {@code reliquary:ego_enchants} PDC sub-container
 * so they exist on, and are read by, E.G.O weapons only. Genuinely-vanilla enchants (Sharpness, Unbreaking,
 * Mending) stay in the item's real enchantment map and keep working as usual — this store is only for the
 * ones that would otherwise do nothing on a custom-fired shot.
 */
public final class EgoEnchants {

    private EgoEnchants() {}

    /** The one key holding the {@code id -> level} sub-container. */
    private static final NamespacedKey EGO_ENCHANTS = new NamespacedKey("reliquary", "ego_enchants");

    private static NamespacedKey idKey(String enchantId) {
        return new NamespacedKey("reliquary", "ench_" + enchantId);
    }

    /** The level of {@code enchantId} on {@code item}, or 0 if absent. Cheap: one meta + one map read. */
    public static int level(ItemStack item, String enchantId) {
        if (item == null || !item.hasItemMeta()) return 0;
        PersistentDataContainer sub = item.getItemMeta().getPersistentDataContainer()
                .get(EGO_ENCHANTS, PersistentDataType.TAG_CONTAINER);
        if (sub == null) return 0;
        Integer lvl = sub.get(idKey(enchantId), PersistentDataType.INTEGER);
        return lvl == null ? 0 : lvl;
    }

    /** Every ego-enchant on {@code item} as {@code id -> level} (never null). */
    public static Map<String, Integer> all(ItemStack item) {
        Map<String, Integer> out = new HashMap<>();
        if (item == null || !item.hasItemMeta()) return out;
        PersistentDataContainer sub = item.getItemMeta().getPersistentDataContainer()
                .get(EGO_ENCHANTS, PersistentDataType.TAG_CONTAINER);
        if (sub == null) return out;
        for (NamespacedKey key : sub.getKeys()) {
            Integer lvl = sub.get(key, PersistentDataType.INTEGER);
            if (lvl != null) out.put(key.getKey().replaceFirst("^ench_", ""), lvl);
        }
        return out;
    }

    /** Set (or clear, when {@code level <= 0}) an ego-enchant on {@code meta}. The caller sets the meta back. */
    public static void set(ItemMeta meta, String enchantId, int level) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        PersistentDataContainer sub = pdc.get(EGO_ENCHANTS, PersistentDataType.TAG_CONTAINER);
        if (sub == null) sub = pdc.getAdapterContext().newPersistentDataContainer();
        if (level <= 0) sub.remove(idKey(enchantId));
        else sub.set(idKey(enchantId), PersistentDataType.INTEGER, level);
        pdc.set(EGO_ENCHANTS, PersistentDataType.TAG_CONTAINER, sub);
    }

    /**
     * Rebuild {@code item}'s tooltip so its current ego-enchants show in a block at the bottom, from the
     * weapon's immutable base tooltip (so nothing stacks). Call after {@link #set} changes an enchant.
     */
    public static void reapplyLore(EgoWeapon weapon, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<EgoLore.EnchantLine> lines = new ArrayList<>();
        all(item).forEach((id, lvl) -> lines.add(new EgoLore.EnchantLine(pretty(id), lvl)));
        EgoLore.withEnchants(weapon.egoTooltip(), lines).applyTo(meta);
        item.setItemMeta(meta);
    }

    /** Turn a lowercase enchant id ("chattering_burst") into a display label ("Chattering Burst"). */
    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.replace('_', ' ').split(" ")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
