package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The high-grade "chain" reagents (§35.4) as crafted, PDC-tagged custom items — the item forms the Censer
 * needs so the Pure/Standard reagents (the ones that push a pot to Analytical, and so unlock WAW) can be
 * titrated at the station and not only via {@code /cogito add}. Each is a distinctive carrier item that only
 * counts when it carries our tag, so the refining recipe can't be bypassed by finding a vanilla lookalike.
 *
 * <p>Being Pure/Standard, they are also precision tools at ANY tier: tiny contamination and a high ceiling
 * make them the scalpel for fine corrections in a low-grade brew, not just the fuel for a Primary Standard.
 * A refining recipe consumes the sin's own lower-tier bases, so the tier ladder feeds upward.
 */
public final class RefinedReagent {

    private RefinedReagent() {}

    private static final NamespacedKey TAG = new NamespacedKey("reliquary", "refined_reagent");

    /** reagent id → the vanilla item that carries its icon (renamed + tagged; never the plain item counts). */
    private static final Map<String, Material> CARRIER = new LinkedHashMap<>();
    static {
        CARRIER.put("distilled_sorrow", Material.INK_SAC);      // Gloom  · Pure
        CARRIER.put("knell_extract",    Material.GRAY_DYE);     // Gloom  · Standard
        CARRIER.put("refined_cinder",   Material.GUNPOWDER);    // Wrath  · Standard
        CARRIER.put("mirror_polish",    Material.QUARTZ);       // Pride  · Standard
        CARRIER.put("nectar_draught",   Material.GLASS_BOTTLE); // Lust   · Pure
        CARRIER.put("verdigris_rest",   Material.GREEN_DYE);    // Sloth  · Standard
        CARRIER.put("verdant_spite",    Material.LIME_DYE);     // Envy   · Pure
        CARRIER.put("ravening_draught", Material.SUGAR);        // Gluttony · Pure
        // Completing the 7-sin ladder: a Pure refined form for every sin, plus Standards where warranted.
        CARRIER.put("ember_distillate", Material.RED_DYE);      // Wrath    · Pure
        CARRIER.put("burnished_vanity", Material.BLUE_DYE);     // Pride    · Pure
        CARRIER.put("lethe_draught",    Material.YELLOW_DYE);   // Sloth    · Pure
        CARRIER.put("amber_rapture",    Material.ORANGE_DYE);   // Lust     · Standard
        CARRIER.put("rancorous_bloom",  Material.PURPLE_DYE);   // Envy     · Standard
        CARRIER.put("gluttons_feast",   Material.CYAN_DYE);     // Gluttony · Standard
    }

    private static final TextColor NAME = TextColor.color(0xB8F0E4);
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether reagent {@code id} has a refined item form. */
    public static boolean has(String reagentId) { return CARRIER.containsKey(reagentId); }

    /** The reagent ids that have refined item forms, in registration order. */
    public static Iterable<String> ids() { return CARRIER.keySet(); }

    /** A crafted, tagged refined-reagent item for {@code reagentId}, or {@code null} if it isn't one. */
    public static ItemStack create(String reagentId, int amount) {
        Material carrier = CARRIER.get(reagentId);
        Reagent r = Reagents.byId(reagentId);
        if (carrier == null || r == null) return null;
        ItemStack item = new ItemStack(carrier, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(r.display()).color(NAME).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(tierLabel(r.tier()) + " reagent — titrate at the Censer.", FAINT)
                .decoration(TextDecoration.ITALIC, false));
        // Exact numbers so players see what an add does on hover (data lines: faint, non-italic).
        for (Sin s : Sin.values()) {
            double d = r.delta()[s.index()];
            if (d != 0) lore.add(Component.text(Reagent.signed(d) + " " + s.display(), FAINT)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (r.roll() != null) {
            lore.add(Component.text("+" + Reagent.fmt(r.roll().min()) + ".." + Reagent.fmt(r.roll().max())
                    + " " + r.roll().sin().display(), FAINT).decoration(TextDecoration.ITALIC, false));
        }
        String stats = "Ceiling " + Reagent.fmt(r.tierCeiling()) + "%";
        if (r.stab() != 0) stats += "  ·  Stability " + Reagent.signed(r.stab());
        if (r.flux() > 0) stats += "  ·  Flux " + r.flux();
        lore.add(Component.text(stats, FAINT).decoration(TextDecoration.ITALIC, false));
        if (r.cures() != null && !r.cures().isEmpty()) {
            java.util.StringJoiner c = new java.util.StringJoiner("/");
            for (Taint t : r.cures()) c.add(t.display());
            lore.add(Component.text("Cures " + c, FAINT).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("High purity: a scalpel at any grade.", FAINT)
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, reagentId);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/reagent/" + reagentId));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** The reagent id this item carries (by tag), or {@code null} if it isn't a refined-reagent item. */
    public static String idOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
    }

    private static String tierLabel(Reagent.Tier t) {
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---- refining recipes: the sin's sourced bases (+ a gated item) → the tagged reagent ------------

    /** Register (idempotently) the crafting-table recipes that refine the chain reagents. */
    public static void registerRecipes(Plugin plugin) {
        // Pure — the sin's refined/crude vanilla items, purified with amethyst.
        recipe(plugin, "distilled_sorrow", Material.LAPIS_LAZULI, Material.LAPIS_LAZULI,
                Material.GLOW_INK_SAC, Material.AMETHYST_SHARD);
        recipe(plugin, "nectar_draught", Material.HONEY_BOTTLE, Material.HONEYCOMB,
                Material.GLOW_BERRIES, Material.AMETHYST_SHARD);
        recipe(plugin, "verdant_spite", Material.PITCHER_PLANT, Material.PUFFERFISH,
                Material.PUFFERFISH, Material.AMETHYST_SHARD);
        recipe(plugin, "ravening_draught", Material.GLISTERING_MELON_SLICE, Material.SLIME_BALL,
                Material.HONEY_BOTTLE, Material.AMETHYST_SHARD);
        // Standard — a Pure-tier base plus a gated patina / chiseled-quartz item.
        recipe(plugin, "refined_cinder", Material.BLAZE_ROD, Material.BLAZE_POWDER,
                Material.BLAZE_POWDER, Material.AMETHYST_SHARD);
        recipe(plugin, "knell_extract", Material.LAPIS_LAZULI, Material.LAPIS_LAZULI,
                Material.GLOW_INK_SAC, Material.WEATHERED_COPPER, Material.AMETHYST_SHARD);
        recipe(plugin, "mirror_polish", Material.DIAMOND, Material.CHISELED_QUARTZ_BLOCK,
                Material.AMETHYST_SHARD);
        recipe(plugin, "verdigris_rest", Material.FERMENTED_SPIDER_EYE, Material.SOUL_SAND,
                Material.WEATHERED_COPPER, Material.AMETHYST_SHARD);
        // --- ladder completion: new Pures (sin bases + amethyst) ---
        recipe(plugin, "ember_distillate", Material.REDSTONE, Material.BLAZE_POWDER,
                Material.MAGMA_CREAM, Material.AMETHYST_SHARD);
        recipe(plugin, "burnished_vanity", Material.GOLD_INGOT, Material.GOLD_NUGGET,
                Material.GOLD_NUGGET, Material.AMETHYST_SHARD);
        recipe(plugin, "lethe_draught", Material.SOUL_SAND, Material.SOUL_SAND,
                Material.FERMENTED_SPIDER_EYE, Material.AMETHYST_SHARD);
        // --- ladder completion: new Standards (sin bases + a gated patina/chiseled item) ---
        recipe(plugin, "amber_rapture", Material.HONEY_BOTTLE, Material.GLOW_BERRIES,
                Material.WEATHERED_COPPER, Material.AMETHYST_SHARD);
        recipe(plugin, "rancorous_bloom", Material.PITCHER_PLANT, Material.PUFFERFISH,
                Material.CHISELED_QUARTZ_BLOCK, Material.AMETHYST_SHARD);
        recipe(plugin, "gluttons_feast", Material.GLISTERING_MELON_SLICE, Material.SLIME_BALL,
                Material.WEATHERED_COPPER, Material.AMETHYST_SHARD);
    }

    private static void recipe(Plugin plugin, String reagentId, Material... ingredients) {
        NamespacedKey key = new NamespacedKey(plugin, "refine_" + reagentId);
        Bukkit.removeRecipe(key); // idempotent across /reload
        ItemStack result = create(reagentId, 1);
        if (result == null) return;
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (Material m : ingredients) recipe.addIngredient(m);
        Bukkit.addRecipe(recipe);
    }
}
