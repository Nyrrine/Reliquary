package com.nyrrine.reliquary.extraction;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * Carmen's Brain as a <b>craftable, placeable custom block</b> — a {@link Material#PLAYER_HEAD} wearing the
 * brain skin and carrying a persistent {@code station} tag, so a placed one is a Carmen's Brain while every
 * ordinary head stays vanilla (the plugin tracks which <i>locations</i> are Brains, see {@link Stations}).
 * Right-clicking a placed Brain with an Extraction Ticket pulls a weapon; a placed Brain also grows an idle
 * floating-brain-and-nervous-system show ({@link CarmenBrainVfx}).
 *
 * <p>The enum constant stays {@code WELL} on purpose — the station tag persists as {@code "WELL"} in
 * {@code stations.yml}, so renaming it would orphan every already-placed Brain.
 */
public enum StationType {

    WELL(Material.PLAYER_HEAD, "Carmen's Brain", Material.DIAMOND_BLOCK, Material.IRON_BLOCK, Material.GLASS);

    /** PDC tag on a station ITEM, holding the enum name. Blocks are tracked by location in {@link Stations}. */
    public static final NamespacedKey KEY = new NamespacedKey("reliquary", "station");

    /** The brain skin (minecraft-heads "Brain") as a texture-property base64 value — Nyrrine's supplied profile. */
    private static final String BRAIN_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODY3ZTI3YTI1NmNkYjdiNzQxZGJmZTg0MTlmNWYzZDg5YmRmYmQ1MmExYjNmMGZkYjljOTVlZGVlYWZjMWYzZiJ9fX0=";

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

    /**
     * A brain-skinned {@link Material#PLAYER_HEAD} — the shared visual for both the placeable block and the
     * floating {@link CarmenBrainVfx} display. Carries no station tag on its own.
     */
    public static ItemStack brainHead() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "CarmensBrain");
            profile.setProperty(new ProfileProperty("textures", BRAIN_TEXTURE));
            skull.setPlayerProfile(profile);
            item.setItemMeta(skull);
        }
        return item;
    }

    /** The crafted station item — the brain head, named and tagged. */
    public ItemStack createItem() {
        ItemStack item = brainHead();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(display).color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("For extracting Cogito.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(KEY, org.bukkit.persistence.PersistentDataType.STRING, name());
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
