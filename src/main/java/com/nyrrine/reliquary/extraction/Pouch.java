package com.nyrrine.reliquary.extraction;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A <b>Pouch</b> — the hoardable "dud" outcome of a Standard Extraction Ticket. A Standard pull hands over a
 * sealed pouch (a skull item) instead of loot; the player opens it later with a right-click to roll one entry
 * from its rarity's table. All four rarities share one skull texture and differ only by name colour, flavour
 * lore, and loot table. The loot is deliberately toned down (bulk materials, not handouts) and every count is a
 * placeholder for the live tune.
 */
public final class Pouch {

    private Pouch() {}

    private static final NamespacedKey MARK   = new NamespacedKey("reliquary", "pouch");
    private static final NamespacedKey RARITY = new NamespacedKey("reliquary", "pouch_rarity");

    /** Shared pouch skull texture (base64 skin profile). */
    private static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTRjYWU0OWExZTdmNjVmOTM1YmQ2MjAzYmYzZjNjOTQ0ZGU4M2Y0YTk2MDhhMjZjOGM3NzVjMTg0OWI1YTc0ZiJ9fX0=";

    /** Random wood pool for a Common "log" roll. */
    private static final Material[] LOGS = {
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG };

    /** A pouch tier: its name colour, its burst colour, a flavour line, and its (uniform) loot table. */
    public enum Rarity {
        COMMON(NamedTextColor.GRAY, 0xAAAAAA, "A worn pouch of odds and ends.", List.of(
                new ItemStack(Material.COAL, 16),
                new ItemStack(Material.IRON_INGOT, 16),
                new ItemStack(Material.OAK_LOG, 16),        // rolled as a random wood
                new ItemStack(Material.COBBLESTONE, 32),
                new ItemStack(Material.COPPER_INGOT, 16),
                new ItemStack(Material.BREAD, 16))),
        UNCOMMON(NamedTextColor.GREEN, 0x55FF55, "A tidy pouch with a little promise.", List.of(
                new ItemStack(Material.COPPER_BLOCK, 8),
                new ItemStack(Material.REDSTONE, 16),
                new ItemStack(Material.LAPIS_LAZULI, 16),
                new ItemStack(Material.GOLD_INGOT, 8),
                new ItemStack(Material.IRON_BLOCK, 4),
                new ItemStack(Material.GLOWSTONE_DUST, 16))),
        RARE(NamedTextColor.BLUE, 0x5555FF, "A heavy pouch that clinks with worth.", List.of(
                new ItemStack(Material.GOLD_BLOCK, 4),
                new ItemStack(Material.EMERALD, 8),
                new ItemStack(Material.DIAMOND, 2),
                new ItemStack(Material.COPPER_BLOCK, 16),
                new ItemStack(Material.IRON_BLOCK, 3),
                new ItemStack(Material.AMETHYST_SHARD, 16))),
        LEGENDARY(NamedTextColor.GOLD, 0xFFAA00, "A gilded pouch heavy with fortune.", List.of(
                new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                new ItemStack(Material.DIAMOND_BLOCK, 3),
                new ItemStack(Material.GOLD_BLOCK, 8),
                new ItemStack(Material.COPPER_BLOCK, 16),
                new ItemStack(Material.EMERALD_BLOCK, 4),
                new ItemStack(Material.DIAMOND, 6)));

        private final TextColor nameColor;
        private final Color burst;
        private final String flavour;
        private final List<ItemStack> loot;

        Rarity(TextColor nameColor, int burstRgb, String flavour, List<ItemStack> loot) {
            this.nameColor = nameColor;
            this.burst = Color.fromRGB(burstRgb);
            this.flavour = flavour;
            this.loot = loot;
        }

        public TextColor nameColor() { return nameColor; }
        public Color burst() { return burst; }
        public String display() { return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT); }

        static Rarity byId(String id) {
            if (id == null) return null;
            try { return valueOf(id.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** A sealed pouch item of the given rarity. */
    public static ItemStack create(Rarity rarity) {
        ItemStack item = skull();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(rarity.display() + " Pouch").color(rarity.nameColor)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text(rarity.flavour, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                Component.text("Right-click to open.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(rarity == Rarity.LEGENDARY);
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(RARITY, PersistentDataType.STRING, rarity.name());
        item.setItemMeta(meta);
        return item;
    }

    /** Whether {@code item} is a pouch of any rarity. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** The rarity of a pouch item, or {@code null} if it isn't a pouch. */
    public static Rarity rarityOf(ItemStack item) {
        if (!matches(item)) return null;
        return Rarity.byId(item.getItemMeta().getPersistentDataContainer().get(RARITY, PersistentDataType.STRING));
    }

    /** Roll one loot entry (uniform) from a rarity's table; a copy, with a random wood for the log entry. */
    public static ItemStack rollLoot(Rarity rarity) {
        List<ItemStack> table = rarity.loot;
        ItemStack pick = table.get(ThreadLocalRandom.current().nextInt(table.size())).clone();
        if (pick.getType().name().endsWith("_LOG")) {
            pick.setType(LOGS[ThreadLocalRandom.current().nextInt(LOGS.length)]);
        }
        return pick;
    }

    private static ItemStack skull() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "ReliquaryPouch");
            profile.setProperty(new ProfileProperty("textures", TEXTURE));
            skull.setPlayerProfile(profile);
            item.setItemMeta(skull);
        }
        return item;
    }
}
