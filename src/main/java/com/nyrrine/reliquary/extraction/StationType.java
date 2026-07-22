package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The Pocket Well as a <b>craftable, placeable custom block</b> — a {@link Material#CONDUIT} carrying a
 * persistent {@code station} tag, so a placed one is a Well while every ordinary conduit stays vanilla (the
 * plugin tracks which <i>locations</i> are Wells, see {@link Stations}). Right-clicking a placed Well with an
 * Extraction Ticket pulls a weapon. The other lab stations were deleted with the chemistry; only the Well
 * remains.
 */
public enum StationType {

    WELL(Material.CONDUIT, "Pocket Well", Material.DIAMOND_BLOCK, Material.IRON_BLOCK, Material.GLASS);

    /** PDC tag on a station ITEM, holding the enum name. Blocks are tracked by location in {@link Stations}. */
    public static final NamespacedKey KEY = new NamespacedKey("reliquary", "station");

    private static final TextColor NAME = TextColor.color(0x8FE6DA);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    private final Material base;
    private final String display;
    private final List<Material> ingredients;

    StationType(Material base, String display, Material... ingredients) {
        this.base = base;
        this.display = display;
        this.ingredients = List.of(ingredients);
    }

    public Material base() { return base; }
    public String display() { return display; }

    /** Base block + the extra parts for its shapeless recipe. */
    public List<Material> ingredients() {
        var out = new java.util.ArrayList<Material>(ingredients.size() + 1);
        out.add(base);
        out.addAll(ingredients);
        return out;
    }

    /** The crafted station item. */
    public ItemStack createItem() {
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(display).color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Place it, then right-click with an Extraction Ticket.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(KEY, org.bukkit.persistence.PersistentDataType.STRING, name());
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/station/" + name().toLowerCase()));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** The station type an item represents (by its tag), or {@code null}. */
    public static StationType fromItem(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String n = meta.getPersistentDataContainer()
                .get(KEY, org.bukkit.persistence.PersistentDataType.STRING);
        if (n == null) return null;
        try { return valueOf(n); } catch (IllegalArgumentException e) { return null; }
    }
}
