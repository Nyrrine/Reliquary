package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An <b>Extraction Ticket</b> — an admin / event item (a PDC-tagged Paper) that can be poured straight at the
 * Pocket Well <i>without</i> brewing a cogito. It carries one or more grade "pools" (ZAYIN…ALEPH); pouring it
 * spins the Well carousel and manifests a random weapon from the union of those pools. Pools are chained onto
 * a ticket with {@code /cogito ticket add <grade>}, so a single ticket can span any range you like — handy for
 * previewing the Well animation and for event giveaways.
 */
public final class ExtractionTicket {

    private ExtractionTicket() {}

    private static final NamespacedKey MARK  = new NamespacedKey("reliquary", "extraction_ticket");
    private static final NamespacedKey POOLS = new NamespacedKey("reliquary", "ticket_pools");
    private static final Material MATERIAL = Material.PAPER;

    /** Valid pool names (grades). ALEPH is accepted for the future even though no ALEPH weapons exist yet. */
    public static final List<String> POOL_NAMES = List.of("ZAYIN", "TETH", "HE", "WAW", "ALEPH");

    private static final TextColor NAME = TextColor.color(0xFFD966);
    private static final TextColor FAINT = TextColor.color(0x8A8F9A);

    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A fresh ticket with no pools yet. */
    public static ItemStack create() {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        style(meta, new LinkedHashSet<>());
        item.setItemMeta(meta);
        return item;
    }

    /** The pools (grade names, upper-case) configured on this ticket, in add order. */
    public static LinkedHashSet<String> pools(ItemStack item) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (item == null) return out;
        ItemMeta m = item.getItemMeta();
        String s = m == null ? null : m.getPersistentDataContainer().get(POOLS, PersistentDataType.STRING);
        if (s != null && !s.isEmpty()) for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Whether {@code pool} is a recognised grade name. */
    public static boolean isValidPool(String pool) {
        return pool != null && POOL_NAMES.contains(pool.toUpperCase(Locale.ROOT));
    }

    /** Add a pool (grade) to the ticket in place; returns false if invalid or already present. */
    public static boolean addPool(ItemStack item, String pool) {
        if (!isValidPool(pool)) return false;
        LinkedHashSet<String> pools = pools(item);
        if (!pools.add(pool.toUpperCase(Locale.ROOT))) return false;
        writePools(item, pools);
        return true;
    }

    /** Add every grade pool. */
    public static void addAllPools(ItemStack item) {
        LinkedHashSet<String> pools = pools(item);
        pools.addAll(POOL_NAMES);
        writePools(item, pools);
    }

    public static void clearPools(ItemStack item) { writePools(item, new LinkedHashSet<>()); }

    private static void writePools(ItemStack item, Set<String> pools) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(POOLS, PersistentDataType.STRING, String.join(",", pools));
        style(meta, pools);
        item.setItemMeta(meta);
    }

    private static void style(ItemMeta meta, Set<String> pools) {
        meta.displayName(Component.text("Extraction Ticket").color(NAME)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Pour it at the Well — no cogito needed.", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        lore.add(Component.text(pools.isEmpty()
                        ? "Pools: none — /cogito ticket add <grade>"
                        : "Pools: " + String.join(", ", pools), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click Well: preview · Sneak: pull (consumes ticket)", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/ticket"));
        meta.setCustomModelDataComponent(cmd);
    }
}
