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

import java.nio.charset.StandardCharsets;
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

    /** Random wood pool for a Common "log" roll. */
    private static final Material[] LOGS = {
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG };

    /** A loot tier: display label, name colour, burst colour, its skull texture, a flavour line, and its table. */
    public enum Rarity {
        COMMON("Common", NamedTextColor.GRAY, 0xAAAAAA,
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDdlYzQxZTBkZjhlMTcwZDk3ZjliOWFmMWQ2NWVkYWQ0OTc5Yzc4Yzg5YjAxYjE4MGYzODllZTA4YTYxYWY4MiJ9fX0=",
                "A worn bag of odds and ends.", List.of(
                new ItemStack(Material.COAL, 16),
                new ItemStack(Material.IRON_INGOT, 16),
                new ItemStack(Material.OAK_LOG, 16),        // rolled as a random wood
                new ItemStack(Material.COBBLESTONE, 32),
                new ItemStack(Material.COPPER_INGOT, 16),
                new ItemStack(Material.BREAD, 16))),
        UNCOMMON("Uncommon", NamedTextColor.GREEN, 0x55FF55,
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmQwYmRmMzliNTRmNDk2OTJmYjM3OWI0ZWIwNGQxZWI0YTAwZTc4ZWQzOTExYWQzYjYzYTdlNWJmMzE3NjgzNyJ9fX0=",
                "A tidy bag with a little promise.", List.of(
                new ItemStack(Material.COPPER_BLOCK, 8),
                new ItemStack(Material.REDSTONE, 16),
                new ItemStack(Material.LAPIS_LAZULI, 16),
                new ItemStack(Material.GOLD_INGOT, 8),
                new ItemStack(Material.IRON_BLOCK, 4),
                new ItemStack(Material.GLOWSTONE_DUST, 16))),
        RARE("Rare", NamedTextColor.BLUE, 0x5555FF,
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmE2ZGFjODAzNWQzNjFiYTdmMmMyYTYxNGI0ZWJhYWZjMWU1ZTMxMDFmODViZWVmNjgzNTM2ZjMzN2U1MDkwIn19fQ==",
                "A heavy bag that clinks with worth.", List.of(
                new ItemStack(Material.GOLD_BLOCK, 4),
                new ItemStack(Material.EMERALD, 8),
                new ItemStack(Material.DIAMOND, 2),
                new ItemStack(Material.COPPER_BLOCK, 16),
                new ItemStack(Material.IRON_BLOCK, 3),
                new ItemStack(Material.AMETHYST_SHARD, 16))),
        LEGENDARY("Fabled", NamedTextColor.GOLD, 0xFFAA00,
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmVlNGE1Y2Q0ZWU2ZTk4OWE2M2RjNDFjNGI0MGQ4M2YwZDU4NTk4ZTdlY2RmMmM5NGRmZWVjMGFkYTAyZWM5MyJ9fX0=",
                "A gilded bag heavy with fortune.", List.of(
                new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                new ItemStack(Material.DIAMOND_BLOCK, 3),
                new ItemStack(Material.GOLD_BLOCK, 8),
                new ItemStack(Material.COPPER_BLOCK, 16),
                new ItemStack(Material.EMERALD_BLOCK, 4),
                new ItemStack(Material.DIAMOND, 6)));

        private final String label;
        private final TextColor nameColor;
        private final Color burst;
        private final String texture;
        private final String flavour;
        private final List<ItemStack> loot;

        Rarity(String label, TextColor nameColor, int burstRgb, String texture, String flavour, List<ItemStack> loot) {
            this.label = label;
            this.nameColor = nameColor;
            this.burst = Color.fromRGB(burstRgb);
            this.texture = texture;
            this.flavour = flavour;
            this.loot = loot;
        }

        public TextColor nameColor() { return nameColor; }
        public Color burst() { return burst; }
        public String display() { return label; }

        static Rarity byId(String id) {
            if (id == null) return null;
            try { return valueOf(id.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** A sealed Loot bag of the given rarity. Same-rarity bags stack (fixed profile + identical meta). */
    public static ItemStack create(Rarity rarity) {
        ItemStack item = skull(rarity);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(rarity.label + " Loot").color(rarity.nameColor)
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

    /**
     * A Loot skull. The profile UUID is <b>deterministic per rarity</b> (not random) so every bag of a rarity
     * carries byte-identical meta and therefore <b>stacks</b>; different rarities differ by UUID/texture/name so
     * they never cross-stack.
     */
    private static ItemStack skull(Rarity rarity) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            UUID id = UUID.nameUUIDFromBytes(("reliquary_loot_" + rarity.name()).getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(id, "ReliquaryLoot");
            profile.setProperty(new ProfileProperty("textures", rarity.texture));
            skull.setPlayerProfile(profile);
            item.setItemMeta(skull);
        }
        return item;
    }
}
