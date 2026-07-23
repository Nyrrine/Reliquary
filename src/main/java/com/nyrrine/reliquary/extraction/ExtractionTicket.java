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
 * An <b>Extraction Ticket</b> — an admin / event item (a PDC-tagged Paper) held near a deployed Carmen's Brain
 * to draw a weapon out <i>without</i> brewing a cogito. It carries one or more grade "pools" (ZAYIN…ALEPH); a
 * sneak right-click extracts a random weapon from the union of those pools. Pools are chained onto a ticket with
 * {@code /cogito ticket add <grade>}, so a single ticket can span any range you like, handy for previewing the
 * extraction show and for event giveaways.
 *
 * <p>A <b>custom</b> ticket ({@code /cogito ticket custom}) additionally carries an explicit set of weapon ids
 * ({@code /cogito ticket add <id>}), so a curator can hand-pick any registered weapon, grade ladder or not. The
 * candidate pool a ticket draws from is the union of its grade pools and its hand-picked ids.
 */
public final class ExtractionTicket {

    private ExtractionTicket() {}

    private static final NamespacedKey MARK     = new NamespacedKey("reliquary", "extraction_ticket");
    private static final NamespacedKey POOLS    = new NamespacedKey("reliquary", "ticket_pools");
    private static final NamespacedKey IDS      = new NamespacedKey("reliquary", "ticket_ids");
    private static final NamespacedKey CUSTOM   = new NamespacedKey("reliquary", "ticket_custom");
    private static final NamespacedKey STANDARD = new NamespacedKey("reliquary", "ticket_standard");
    private static final Material MATERIAL = Material.PAPER;

    /** Valid pool names (grades) — the full ZAYIN…ALEPH ladder. */
    public static final List<String> POOL_NAMES = List.of("ZAYIN", "TETH", "HE", "WAW", "ALEPH");

    private static final TextColor NAME = TextColor.color(0xFFD966);
    private static final TextColor FAINT = TextColor.color(0x8A8F9A);

    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A fresh grade ticket with no pools yet. */
    public static ItemStack create() { return blank(false, false); }

    /** A fresh custom ticket (hand-picked weapon ids), with nothing on it yet. */
    public static ItemStack createCustom() { return blank(true, false); }

    /** A Standard ticket — a fixed weighted table (weapons by grade + pouches + the bag), not a pool. */
    public static ItemStack createStandard() { return blank(false, true); }

    private static ItemStack blank(boolean custom, boolean standard) {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        if (custom) meta.getPersistentDataContainer().set(CUSTOM, PersistentDataType.BYTE, (byte) 1);
        if (standard) meta.getPersistentDataContainer().set(STANDARD, PersistentDataType.BYTE, (byte) 1);
        style(meta, new LinkedHashSet<>(), new LinkedHashSet<>(), custom, standard);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether this ticket was minted as a custom (hand-picked) ticket, for naming. */
    public static boolean isCustom(ItemStack item) {
        if (item == null) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(CUSTOM, PersistentDataType.BYTE);
    }

    /** Whether this is a Standard ticket (weighted table with pouch/bag outcomes). */
    public static boolean isStandard(ItemStack item) {
        if (item == null) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(STANDARD, PersistentDataType.BYTE);
    }

    /** The pools (grade names, upper-case) configured on this ticket, in add order. */
    public static LinkedHashSet<String> pools(ItemStack item) { return read(item, POOLS); }

    /** The explicit weapon ids (lower-case) hand-picked onto this ticket, in add order. */
    public static LinkedHashSet<String> ids(ItemStack item) { return read(item, IDS); }

    private static LinkedHashSet<String> read(ItemStack item, NamespacedKey key) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (item == null) return out;
        ItemMeta m = item.getItemMeta();
        String s = m == null ? null : m.getPersistentDataContainer().get(key, PersistentDataType.STRING);
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
        write(item, POOLS, pools);
        return true;
    }

    /** Add every grade pool. */
    public static void addAllPools(ItemStack item) {
        LinkedHashSet<String> pools = pools(item);
        pools.addAll(POOL_NAMES);
        write(item, POOLS, pools);
    }

    /** Add a hand-picked weapon id (lower-cased) to the ticket; returns false if already present. */
    public static boolean addId(ItemStack item, String id) {
        if (id == null || id.isEmpty()) return false;
        LinkedHashSet<String> ids = ids(item);
        if (!ids.add(id.toLowerCase(Locale.ROOT))) return false;
        write(item, IDS, ids);
        return true;
    }

    /** Clear both the grade pools and the hand-picked ids (keeps the ticket and its custom flag). */
    public static void clearPools(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(POOLS, PersistentDataType.STRING, "");
        meta.getPersistentDataContainer().set(IDS, PersistentDataType.STRING, "");
        restyle(meta);
        item.setItemMeta(meta);
    }

    private static void write(ItemStack item, NamespacedKey key, Set<String> values) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.join(",", values));
        restyle(meta);
        item.setItemMeta(meta);
    }

    /**
     * Re-run styling from the meta's own pools/ids/custom flag. Reads the in-progress {@code meta} (which already
     * carries the just-written value) rather than the item, whose stored meta is still one step stale until
     * {@code setItemMeta} lands — so a freshly added grade/id shows on the lore immediately.
     */
    private static void restyle(ItemMeta meta) {
        style(meta, readMeta(meta, POOLS), readMeta(meta, IDS),
                meta.getPersistentDataContainer().has(CUSTOM, PersistentDataType.BYTE),
                meta.getPersistentDataContainer().has(STANDARD, PersistentDataType.BYTE));
    }

    /** Read a comma-joined string set straight from a meta's PDC (used mid-write, before setItemMeta). */
    private static LinkedHashSet<String> readMeta(ItemMeta meta, NamespacedKey key) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String s = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s != null && !s.isEmpty()) for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static void style(ItemMeta meta, Set<String> pools, Set<String> ids, boolean custom, boolean standard) {
        if (standard) { styleStandard(meta); return; }
        meta.displayName(Component.text(custom ? "Custom Extraction Ticket" : "Extraction Ticket").color(NAME)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(custom
                        ? "Hold it near Carmen's Brain to draw a hand-picked weapon."
                        : "Hold it near Carmen's Brain to draw out a weapon.", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        boolean empty = pools.isEmpty() && ids.isEmpty();
        if (empty) {
            lore.add(Component.text("Pools: none (add with /cogito ticket add <grade|id>)", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            if (!pools.isEmpty()) {
                lore.add(Component.text("Pools: " + String.join(", ", pools), NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (!ids.isEmpty()) {
                lore.add(Component.text("Picks: " + String.join(", ", ids), NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.text("Right-click nearby: preview. Sneak right-click: extract (spends the ticket).", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        // Once a ticket carries an apex grade pool (WAW or ALEPH) it wears the upgraded model; a plain ZAYIN..HE
        // or custom-only ticket keeps the base one. The pack maps each string to its own texture.
        boolean apex = pools.contains("WAW") || pools.contains("ALEPH");
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(apex ? "extraction/ticket/waw" : "extraction/ticket"));
        meta.setCustomModelDataComponent(cmd);
    }

    /**
     * The Standard ticket: name + a colour-coded rarity odds table (each entry tinted to match its in-world glow,
     * grades from {@link EgoGrade#color()}, loot tiers from {@link Pouch.Rarity#nameColor()}). No em-dashes.
     */
    private static void styleStandard(ItemMeta meta) {
        meta.displayName(Component.text("Standard Extraction Ticket").color(NAME)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        TextColor head = TextColor.color(0xC9CDD6);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Hold near Carmen's Brain, sneak to roll.", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        lore.add(Component.empty());
        lore.add(Component.text("Weapons (20%)", head).decoration(TextDecoration.ITALIC, false));
        lore.add(row(gradeEntry(EgoGrade.ALEPH, "1%"), gradeEntry(EgoGrade.WAW, "3%"),
                gradeEntry(EgoGrade.HE, "4.5%"), gradeEntry(EgoGrade.TETH, "5%"), gradeEntry(EgoGrade.ZAYIN, "6.5%")));
        lore.add(Component.empty());
        lore.add(Component.text("Loot (79%)", head).decoration(TextDecoration.ITALIC, false));
        lore.add(row(lootEntry(Pouch.Rarity.LEGENDARY, "3%"), lootEntry(Pouch.Rarity.RARE, "7%"),
                lootEntry(Pouch.Rarity.UNCOMMON, "20%"), lootEntry(Pouch.Rarity.COMMON, "49%")));
        lore.add(Component.empty());
        lore.add(Component.text("Special (1%)", head).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("A Certain ", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Daughters", TextColor.color(0x3B5DC9)))
                .append(Component.text(" Bag", NamedTextColor.RED)));
        lore.add(Component.empty());
        lore.add(Component.text("Sneak right-click near the Brain to roll. Spends the ticket.", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/ticket")); // no new art this round, base model
        meta.setCustomModelDataComponent(cmd);
    }

    /** A grade odds entry ("ALEPH 1%") tinted to the grade's show colour. */
    private static Component gradeEntry(EgoGrade grade, String pct) {
        return Component.text(grade.display() + " " + pct, TextColor.color(grade.color().asRGB()))
                .decoration(TextDecoration.ITALIC, false);
    }

    /** A loot odds entry ("Fabled 3%") tinted to the rarity's name colour. */
    private static Component lootEntry(Pouch.Rarity rarity, String pct) {
        return Component.text(rarity.display() + " " + pct, rarity.nameColor())
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Join tier entries with gray " · " separators into one lore row. */
    private static Component row(Component... entries) {
        Component sep = Component.text(" · ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        Component out = Component.empty().decoration(TextDecoration.ITALIC, false);
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) out = out.append(sep);
            out = out.append(entries[i]);
        }
        return out;
    }
}
