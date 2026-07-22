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
 * The seven Sin Concentrates — lore trinkets, one per Limbus sin, each a tagged and renamed dye. Pure
 * cosmetic now: the old affinity-grind chemistry (raw mob drops → concentrate → refined reagent) is gone,
 * so these are just givable, textured collectibles. Their identities (name, colour, carrier dye, model) are
 * kept standalone here rather than keyed off the deleted {@code Sin} enum. Static utility.
 */
public final class SinConcentrate {

    private SinConcentrate() {}

    private static final NamespacedKey TAG = new NamespacedKey("reliquary", "sin_concentrate");
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** One concentrate's identity: id, display name, Limbus sin colour, carrier dye, and pack model key. */
    public record Kind(String id, String display, TextColor color, Material carrier, String model) {}

    private static final List<Kind> KINDS = List.of(
            new Kind("wrath",    "Wrath",    TextColor.color(0xE23B3B), Material.RED_DYE,        "extraction/concentrate/wrath"),
            new Kind("pride",    "Pride",    TextColor.color(0x6A5ACD), Material.BLUE_DYE,       "extraction/concentrate/pride"),
            new Kind("lust",     "Lust",     TextColor.color(0xE8862E), Material.ORANGE_DYE,     "extraction/concentrate/lust"),
            new Kind("gloom",    "Gloom",    TextColor.color(0x3B6FE2), Material.LIGHT_BLUE_DYE, "extraction/concentrate/gloom"),
            new Kind("sloth",    "Sloth",    TextColor.color(0xE8D23B), Material.YELLOW_DYE,     "extraction/concentrate/sloth"),
            new Kind("envy",     "Envy",     TextColor.color(0x9B3BE2), Material.PURPLE_DYE,     "extraction/concentrate/envy"),
            new Kind("gluttony", "Gluttony", TextColor.color(0x3BE26A), Material.GREEN_DYE,      "extraction/concentrate/gluttony"));

    /** The seven concentrates, in sin order. */
    public static List<Kind> kinds() { return KINDS; }

    /** A concentrate kind by id, or {@code null}. */
    public static Kind byId(String id) {
        for (Kind k : KINDS) if (k.id().equals(id)) return k;
        return null;
    }

    /** A crafted, tagged Concentrate for {@code kind}. */
    public static ItemStack create(Kind kind, int amount) {
        if (kind == null) return null;
        ItemStack item = new ItemStack(kind.carrier(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(kind.display() + " Concentrate")
                .color(kind.color()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("A distilled fragment of feeling.", FAINT)
                .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, kind.id());
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(kind.model()));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether an item is a Sin Concentrate (by tag). */
    public static boolean matches(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(TAG, PersistentDataType.STRING);
    }
}
