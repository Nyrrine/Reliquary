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
 * A home for cosmetic extraction items whose old chemistry host classes were deleted. Right now that is just
 * <b>ember_distillate</b> ("Smoldering Ire"), which used to be a refined-reagent carrier — kept here as a
 * pure, givable, textured collectible (same {@link Material#FIRE_CHARGE} + model + name). Static utility.
 */
public final class Cosmetics {

    private Cosmetics() {}

    private static final NamespacedKey EMBER = new NamespacedKey("reliquary", "ember_distillate");
    private static final Material EMBER_MATERIAL = Material.FIRE_CHARGE;
    private static final String EMBER_CMD = "extraction/reagent/ember_distillate";
    private static final TextColor EMBER_NAME = TextColor.color(0xE23B3B); // wrath red
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** A stack of {@code amount} Ember Distillate ("Smoldering Ire"). */
    public static ItemStack emberDistillate(int amount) {
        ItemStack item = new ItemStack(EMBER_MATERIAL, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Smoldering Ire").color(EMBER_NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("A distillate of banked wrath.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(EMBER, PersistentDataType.BYTE, (byte) 1);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(EMBER_CMD));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether an item is Ember Distillate. */
    public static boolean isEmberDistillate(ItemStack item) {
        if (item == null || item.getType() != EMBER_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(EMBER, PersistentDataType.BYTE);
    }
}
