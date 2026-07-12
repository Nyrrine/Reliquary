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
 * Enkephalin — the raw fuel of the extraction lab. Drawn at the Font, spent to distill Cogito at the
 * Centrifuge and to power pours at the Pocket Well. It carries no emotional composition of its own; it is
 * pure energy, the feedstock the whole pipeline runs on.
 *
 * <p>A plain vanilla {@link Material#GLOWSTONE_DUST} carrying a persistent marker and a pack-swappable
 * model key, so it looks fine bare and can be reskinned later. Stackable like any resource — this is the
 * bulk mana you farm.
 */
public final class Enkephalin {

    private Enkephalin() {}

    private static final NamespacedKey MARK = new NamespacedKey("reliquary", "enkephalin");
    private static final String CMD = "extraction/enkephalin";

    private static final Material MATERIAL = Material.EXPERIENCE_BOTTLE; // an (unthrowable) bottle of essence
    private static final TextColor NAME = TextColor.color(0x88DD66); // Lobotomy green
    private static final TextColor BODY = TextColor.color(0xB8B8C0);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether an item is Enkephalin fuel. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A stack of {@code amount} Enkephalin. */
    public static ItemStack create(int amount) {
        ItemStack item = new ItemStack(MATERIAL, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Enkephalin").color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
        meta.setEnchantmentGlintOverride(true); // it glows with stored energy
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }

    /** A single unit of Enkephalin. */
    public static ItemStack create() { return create(1); }

    private static final List<Component> LORE = List.of(
            line("Distilled mental energy — the fuel", BODY),
            line("that drives every extraction.", BODY),
            Component.empty(),
            line("Spent at the Centrifuge and the Well.", FAINT, true));

    private static Component line(String text, TextColor color) { return line(text, color, false); }

    private static Component line(String text, TextColor color, boolean italic) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, italic);
    }
}
