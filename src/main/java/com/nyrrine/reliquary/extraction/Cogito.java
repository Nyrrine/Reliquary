package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.List;

/**
 * Cogito — a lore vial, kept as a Minecraft <b>potion</b> so it can carry a custom green tint. Pure cosmetic
 * now (the extraction chemistry that used to fill and grade it is gone): a plain green {@link #create() Cogito}
 * and its gold apex form, the {@link #createRadiant() Radiant Cogito}. Both are givable and textured; neither
 * does anything mechanically. Static utility.
 */
public final class Cogito {

    private Cogito() {}

    private static final NamespacedKey MARK = new NamespacedKey("reliquary", "cogito");

    /** Pack-swappable model keys (the resource pack reskins the vials). */
    private static final String CMD = "extraction/cogito";
    private static final String RADIANT_CMD = "extraction/radiant_cogito";

    private static final Color GREEN_TINT = Color.fromRGB(0x3B, 0xE2, 0x6A); // Lobotomy green
    private static final Color RADIANT_TINT = Color.fromRGB(0xFF, 0xE9, 0xA3);
    private static final TextColor GREEN_NAME = TextColor.color(0x74F066);
    private static final TextColor RADIANT_NAME = TextColor.color(0xFFE9A3);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether an item is a Cogito vial (plain or radiant). */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        PotionMeta meta = potionMeta(item);
        return meta != null && meta.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A plain green Cogito vial. */
    public static ItemStack create() {
        return build("Cogito", GREEN_NAME, GREEN_TINT, CMD, false);
    }

    /** The gold Radiant Cogito — the apex vial form. */
    public static ItemStack createRadiant() {
        return build("Radiant Cogito", RADIANT_NAME, RADIANT_TINT, RADIANT_CMD, true);
    }

    private static ItemStack build(String name, TextColor nameColor, Color tint, String model, boolean glint) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = potionMeta(item);
        if (meta == null) return item;

        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        meta.setColor(tint);
        // Strip every vanilla-potion tell so only our tint + lore show — no base effect, no "No Effects" line.
        meta.clearCustomEffects();
        meta.setBasePotionType(PotionType.WATER);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.setEnchantmentGlintOverride(glint);

        meta.displayName(Component.text(name).color(nameColor).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("An emotional distillate in a vial.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }

    private static PotionMeta potionMeta(ItemStack item) {
        return item.getItemMeta() instanceof PotionMeta pm ? pm : null;
    }
}
