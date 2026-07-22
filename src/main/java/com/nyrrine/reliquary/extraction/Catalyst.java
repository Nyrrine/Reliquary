package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * A Catalyst — a lore trinket, kept as a textured {@link Material#NETHER_STAR}. Pure cosmetic now: the old
 * per-weapon signature-lock was chemistry and is gone, so this is a single generic item. Static utility.
 */
public final class Catalyst {

    private Catalyst() {}

    private static final NamespacedKey MARK = new NamespacedKey("reliquary", "catalyst");
    private static final Material MATERIAL = Material.NETHER_STAR;
    private static final String CMD = "extraction/catalyst";
    private static final TextColor GOLD = TextColor.color(0xFFD54A);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether an item is a Catalyst. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A Catalyst item. */
    public static ItemStack create() {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Catalyst").color(GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Certified Reference Material.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }
}
