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
 * The lab stations as <b>craftable, placeable custom blocks</b> (§35). Each is a crafted item — a vanilla
 * block base carrying a persistent {@code station} tag — so a placed one is a station, while every ordinary
 * block of the same type stays vanilla (the plugin tracks which <i>locations</i> are stations, see
 * {@link Stations}). A resource pack can reskin them via the CustomModelData string later.
 *
 * <p>{@link #ingredients} are the shapeless crafting recipe (the base block + a couple of thematic parts).
 */
public enum StationType {

    // Lab-bench builds: a vanilla base block fitted with common lab hardware (iron/redstone/glass/diamond).
    FONT      (Material.CAULDRON,          "Font",        Material.IRON_BLOCK,  Material.COPPER_BLOCK,   Material.GLASS),
    ALEMBIC   (Material.BLAST_FURNACE,     "Alembic",     Material.IRON_BLOCK,  Material.GLASS_BOTTLE,   Material.REDSTONE),
    CENSER    (Material.BREWING_STAND,     "Censer",      Material.IRON_INGOT,  Material.GLASS_BOTTLE,   Material.REDSTONE_BLOCK),
    CENTRIFUGE(Material.GRINDSTONE,        "Centrifuge",  Material.IRON_BLOCK,  Material.REDSTONE_BLOCK, Material.DIAMOND),
    MANIFOLD  (Material.CHISELED_BOOKSHELF,"Manifold",    Material.IRON_INGOT,  Material.REDSTONE_BLOCK, Material.GLASS),
    CRUCIBLE  (Material.SMITHING_TABLE,    "Crucible",    Material.IRON_BLOCK,  Material.DIAMOND,        Material.MAGMA_BLOCK),
    WELL      (Material.CONDUIT,           "Pocket Well", Material.DIAMOND_BLOCK, Material.IRON_BLOCK,   Material.GLASS),
    LECTERN   (Material.LECTERN,           "Assay",       Material.IRON_INGOT,  Material.GLASS_PANE,     Material.REDSTONE);

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
        meta.lore(List.of(Component.text("Extraction station — place it, then right-click.", FAINT)
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
