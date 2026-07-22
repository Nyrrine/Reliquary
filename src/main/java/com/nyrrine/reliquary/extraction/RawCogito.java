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
 * Raw Cogito — the unprocessed emotional lump extracted at the Font, before it's refined into a workable
 * Cogito vial. A green {@link Material#SLIME_BALL} (Lobotomy-green). It holds no chemistry yet; you must
 * process it at the <b>Alembic</b> (Raw Cogito + Enkephalin → a blank Cogito potion). Static utility.
 */
public final class RawCogito {

    private RawCogito() {}

    private static final NamespacedKey MARK = new NamespacedKey("reliquary", "raw_cogito");
    private static final Material MATERIAL = Material.SLIME_BALL;
    private static final TextColor NAME = TextColor.color(0x74C24A); // Lobotomy green
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether an item is Raw Cogito. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A stack of {@code amount} Raw Cogito. */
    public static ItemStack create(int amount) {
        ItemStack item = new ItemStack(MATERIAL, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Raw Cogito").color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Unprocessed emotional matter.", FAINT)
                        .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/raw_cogito"));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack create() { return create(1); }
}
