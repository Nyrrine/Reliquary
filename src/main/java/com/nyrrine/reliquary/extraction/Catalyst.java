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

import java.util.List;

/**
 * A signature-lock catalyst — the per-weapon "Certified Reference Material" forged at the Well from its
 * grind-gated sub-components (see {@link Catalysts}). Pour one in with a matching cogito and it <b>cuts the
 * RNG</b>: the exact weapon it locks is extracted, guaranteed, and at Primary Standard purity it certifies to
 * 100%. This is where the top-end weapons are <i>earned</i> — the components are the wall, not luck.
 *
 * <p>A Nether Star carrying the target weapon's id in its persistent data. Static utility.
 */
public final class Catalyst {

    private Catalyst() {}

    private static final NamespacedKey WEAPON = new NamespacedKey("reliquary", "catalyst_weapon");
    private static final Material MATERIAL = Material.NETHER_STAR;

    private static final TextColor GOLD = TextColor.color(0xFFD54A);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether an item is a catalyst. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(WEAPON, PersistentDataType.STRING);
    }

    /** The weapon id a catalyst locks, or {@code null} if it isn't a catalyst. */
    public static String weaponId(ItemStack item) {
        if (!matches(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(WEAPON, PersistentDataType.STRING);
    }

    /** Forge a catalyst item for the given weapon spec. */
    public static ItemStack create(WeaponSpec weapon) {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(weapon.display() + " Catalyst")
                .color(GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Certified Reference Material — " + weapon.grade().display(), GOLD),
                line("Pour with matching cogito to", FAINT, true),
                line("GUARANTEE " + weapon.display() + ".", FAINT, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(WEAPON, PersistentDataType.STRING, weapon.id());

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/catalyst/" + weapon.id()));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text, TextColor color) { return line(text, color, false); }

    private static Component line(String text, TextColor color, boolean italic) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, italic);
    }
}
