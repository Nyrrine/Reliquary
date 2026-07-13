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
import java.util.Map;

/**
 * The remedies and buffers — the "medicine cabinet" (§10) — as crafted, PDC-tagged custom items so every
 * cure the Censer accepts is a distinctive, texturable item and no longer a plain bucket/snowball lookalike.
 * Each cure is coloured to the taint it clears (so it reads at a glance against the taint alarm), stacks, and
 * is <b>craftable</b> from renewable vanilla ingredients — the raw item (a mined amethyst, a farmed snowball)
 * no longer cures on its own; you refine it into the remedy first.
 *
 * <p>The carrier {@link Material}s here are neutral placeholders — a resource pack keys a custom model off
 * {@code extraction/cure/<id>}, so the base item is only a stand-in until the texture lands. No buckets: the
 * old milk/pufferfish buckets are gone in favour of stackable carriers.
 */
public final class CureItem {

    private CureItem() {}

    private static final NamespacedKey TAG = new NamespacedKey("reliquary", "cure_item");

    /** cure reagent id → placeholder carrier item (stackable, non-bucket; the texture overrides the model). */
    private static final Map<String, Material> CARRIER = new LinkedHashMap<>();
    static {
        CARRIER.put("amethyst_shard", Material.QUARTZ);        // Amethyst Buffer — cures Fissuring
        CARRIER.put("glowstone_dust", Material.FLINT);         // Radiant Ballast — buffer
        CARRIER.put("honeycomb",      Material.CLAY_BALL);     // Emulsifier      — cures Phase Separation
        CARRIER.put("nether_wart",    Material.BRICK);         // Antivenom       — cures Contamination
        CARRIER.put("bone_meal",      Material.NETHER_BRICK);  // Flocculant      — cures Precipitate
        CARRIER.put("snowball",       Material.CHARCOAL);      // Quench          — cures Exotherm
        CARRIER.put("milk_bucket",    Material.BOWL);          // Solvent Wash    — panic reset
        CARRIER.put("water_bottle",   Material.STICK);         // Diluent         — fine dilution
    }

    /** Fallback name colour for buffers/washes that don't clear a specific taint. */
    private static final Map<String, TextColor> UTILITY_COLOR = new LinkedHashMap<>();
    static {
        UTILITY_COLOR.put("glowstone_dust", TextColor.color(0xFFE9A3)); // warm gold — light/ballast
        UTILITY_COLOR.put("milk_bucket",    TextColor.color(0xF2F2F2)); // clean white — solvent
        UTILITY_COLOR.put("water_bottle",   TextColor.color(0x64C8E6)); // pale blue — dilution
    }

    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Whether reagent {@code id} has a cure item form. */
    public static boolean has(String reagentId) { return CARRIER.containsKey(reagentId); }

    /** The reagent ids that have cure item forms, in registration order. */
    public static Iterable<String> ids() { return CARRIER.keySet(); }

    /** A crafted, tagged cure item for {@code reagentId}, or {@code null} if it isn't one. */
    public static ItemStack create(String reagentId, int amount) {
        Material carrier = CARRIER.get(reagentId);
        Reagent r = Reagents.byId(reagentId);
        if (carrier == null || r == null) return null;
        ItemStack item = new ItemStack(carrier, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(r.display()).color(nameColor(reagentId))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        boolean remedy = r.cures() != null && !r.cures().isEmpty();
        lore.add(Component.text((remedy ? "Remedy" : "Buffer") + " — titrate at the Censer.", FAINT)
                .decoration(TextDecoration.ITALIC, false));
        if (remedy) {
            java.util.StringJoiner c = new java.util.StringJoiner("/");
            for (Taint t : r.cures()) c.add(t.display());
            lore.add(Component.text("Cures " + c, nameColor(reagentId)).decoration(TextDecoration.ITALIC, false));
        }
        List<String> effects = new ArrayList<>();
        if (r.stab() != 0) effects.add("Stability " + Reagent.signed(r.stab()));
        if (r.flux() > 0) effects.add("Flux " + r.flux());
        if (r.chargeScale() != 1.0) effects.add("Charge ×" + Reagent.fmt(r.chargeScale()));
        if (r.noiseScale() != 1.0) effects.add("Noise ×" + Reagent.fmt(r.noiseScale()));
        if (!effects.isEmpty()) lore.add(Component.text(String.join("  ·  ", effects), FAINT)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, reagentId);
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/cure/" + reagentId));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** The cure reagent id this item carries (by tag), or {@code null} if it isn't a cure item. */
    public static String idOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
    }

    /** Colour a cure by the taint it clears (matches the alarm), else its utility hue. */
    private static TextColor nameColor(String reagentId) {
        for (Taint t : Taint.values()) if (reagentId.equals(t.cureId())) return t.color();
        return UTILITY_COLOR.getOrDefault(reagentId, TextColor.color(0xB8F0E4));
    }

    // ---- crafting recipes: refine the raw vanilla item into the remedy -------------------------------
    //
    // Each cure is crafted from renewable vanilla ingredients into its tagged form. Inputs are plain items
    // (a mined amethyst, a farmed snowball); the tagged output uses a neutral carrier, so a recipe can never
    // consume its own product. Counts are small — remedies are consumables, not a grind.

    /** cure id → {yield, [Material, count, Material, count, ...] plain inputs}. */
    private static final Map<String, Object[]> RECIPES = new LinkedHashMap<>();
    static {
        RECIPES.put("amethyst_shard", new Object[]{2, Material.AMETHYST_SHARD, 2, Material.GLASS, 1});
        RECIPES.put("glowstone_dust", new Object[]{2, Material.GLOWSTONE_DUST, 3});
        RECIPES.put("honeycomb",      new Object[]{2, Material.HONEYCOMB, 3});
        RECIPES.put("nether_wart",    new Object[]{2, Material.NETHER_WART, 2, Material.FERMENTED_SPIDER_EYE, 1});
        RECIPES.put("bone_meal",      new Object[]{2, Material.BONE_MEAL, 3});
        RECIPES.put("snowball",       new Object[]{2, Material.SNOWBALL, 3, Material.ICE, 1});
        RECIPES.put("milk_bucket",    new Object[]{2, Material.SUGAR, 3});
        RECIPES.put("water_bottle",   new Object[]{2, Material.GLASS_BOTTLE, 2, Material.SUGAR, 1});
    }

    /** Register (idempotently) the crafting recipe for every cure. */
    public static void registerRecipes(Plugin plugin) {
        for (Map.Entry<String, Object[]> e : RECIPES.entrySet()) {
            String id = e.getKey();
            Object[] spec = e.getValue();
            ItemStack result = create(id, (Integer) spec[0]);
            if (result == null) continue;
            NamespacedKey key = new NamespacedKey(plugin, "cure_" + id);
            Bukkit.removeRecipe(key); // idempotent across /reload
            ShapelessRecipe recipe = new ShapelessRecipe(key, result);
            for (int i = 1; i + 1 < spec.length; i += 2) {
                recipe.addIngredient((Integer) spec[i + 1], (Material) spec[i]);
            }
            Bukkit.addRecipe(recipe);
        }
    }
}
