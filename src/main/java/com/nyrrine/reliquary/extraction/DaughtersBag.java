package com.nyrrine.reliquary.extraction;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * <b>A Certain Daughters Bag</b> — the 1% ultra-rare (ALEPH odds) collectible from a Standard Extraction Ticket.
 * A pre-loved bag recovered during Operation Spider Pyre that once held "a powerful item that connects
 * possibilities."
 *
 * <p>It is an <b>inert collectible</b> right now: right-clicking it only reports that its contents are sealed.
 * The crafting / Relic-component / armor-upgrade rolling it hints at belongs to the deferred relics+armor
 * system and is deliberately NOT built here — this class is just the item and a seam.
 */
public final class DaughtersBag {

    private DaughtersBag() {}

    private static final NamespacedKey MARK = new NamespacedKey("reliquary", "daughters_bag");

    private static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTE2MDUxY2U0YjcxZmZiODM2NDI5ZTU5YmM2ZDhiZDAxNjY0OTE5M2YxZmFkZTk3NWU4Nzg0ZWVmMzczOCJ9fX0=";

    private static final TextColor NAME_RED  = NamedTextColor.RED;
    private static final TextColor NAME_BLUE = TextColor.color(0x3B5DC9);   // dark lapis blue
    private static final TextColor YELLOW    = TextColor.color(0xFFF6A0);   // light yellow
    private static final TextColor DARK_RED  = NamedTextColor.DARK_RED;
    private static final TextColor BODY      = NamedTextColor.GRAY;

    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    public static ItemStack create() {
        ItemStack item = skull();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("A Certain ").color(NAME_RED).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Daughters").color(NAME_BLUE))
                .append(Component.text(" Bag").color(NAME_RED)));

        // Her flavour, wording preserved, wrapped across lines; phrase colours mid-line. No em-dashes.
        List<Component> lore = List.of(
                line().append(Component.text("It seems like ", BODY))
                        .append(Component.text("a powerful item that", YELLOW)),
                line().append(Component.text("connects possibilities", YELLOW))
                        .append(Component.text(" was once left", BODY)),
                line().append(Component.text("behind this pre-loved bag that was", BODY)),
                line().append(Component.text("recovered during ", BODY))
                        .append(Component.text("Operation Spider Pyre", DARK_RED))
                        .append(Component.text(".", BODY)),
                Component.empty(),
                line().append(Component.text("You may roll it for crafting components", BODY)),
                line().append(Component.text("for Relics and special armor upgrades.", BODY)));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** A lore base component with italics off (so the coloured segments read clean). */
    private static Component line() {
        return Component.text("").decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack skull() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "DaughtersBag");
            profile.setProperty(new ProfileProperty("textures", TEXTURE));
            skull.setPlayerProfile(profile);
            item.setItemMeta(skull);
        }
        return item;
    }
}
