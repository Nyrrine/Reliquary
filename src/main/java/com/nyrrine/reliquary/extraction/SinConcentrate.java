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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Sin Concentrate</b> — the middle rung of the affinity grind (§35.4). Raw sin materials are weak and
 * dirty on their own; you crush {@link #RAW_PER_CONCENTRATE} of them into one Concentrate, and Concentrates
 * are what the Pure/Standard {@link RefinedReagent refined reagents} are actually built from. It is a pure
 * crafting component (never titrated) — its whole job is to make the good reagents a long, hand-worked climb:
 * a single Standard reagent is {@code 4 Pure × 4 Concentrate × 8 raw} ≈ two stacks of raw material and ~21
 * separate crafts. That's the loop — keep the bench busy.
 */
public final class SinConcentrate {

    private SinConcentrate() {}

    /** Raw sin items crushed into one Concentrate. */
    public static final int RAW_PER_CONCENTRATE = 8;

    private static final NamespacedKey TAG = new NamespacedKey("reliquary", "sin_concentrate");

    /** The cheap raw vanilla item that carries each sin's affinity (crushed into the Concentrate). */
    private static final Map<Sin, Material> RAW = new EnumMap<>(Sin.class);
    /** The Concentrate's carrier icon (a spare dye per sin; PDC-tagged + renamed, only the tagged one counts). */
    private static final Map<Sin, Material> CARRIER = new EnumMap<>(Sin.class);
    static {
        // Carriers are dyed to the Limbus sin colours (a Concentrate reads as its sin at a glance).
        RAW.put(Sin.WRATH, Material.REDSTONE);        CARRIER.put(Sin.WRATH, Material.RED_DYE);        // red
        RAW.put(Sin.GLOOM, Material.GLOW_INK_SAC);    CARRIER.put(Sin.GLOOM, Material.LIGHT_BLUE_DYE);  // blue
        RAW.put(Sin.PRIDE, Material.GOLD_NUGGET);     CARRIER.put(Sin.PRIDE, Material.BLUE_DYE);        // dark blue
        RAW.put(Sin.LUST, Material.GLOW_BERRIES);     CARRIER.put(Sin.LUST, Material.ORANGE_DYE);       // orange
        RAW.put(Sin.SLOTH, Material.SOUL_SAND);       CARRIER.put(Sin.SLOTH, Material.YELLOW_DYE);      // yellow
        RAW.put(Sin.ENVY, Material.PUFFERFISH);       CARRIER.put(Sin.ENVY, Material.PURPLE_DYE);       // violet
        RAW.put(Sin.GLUTTONY, Material.ROTTEN_FLESH); CARRIER.put(Sin.GLUTTONY, Material.GREEN_DYE);    // green
    }

    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** The raw vanilla item that feeds a sin's Concentrate. */
    public static Material rawFor(Sin s) { return RAW.get(s); }

    /** A crafted, tagged Concentrate for {@code sin}. */
    public static ItemStack create(Sin sin, int amount) {
        Material carrier = CARRIER.get(sin);
        if (carrier == null) return null;
        ItemStack item = new ItemStack(carrier, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(sin.display() + " Concentrate")
                .color(sin.color()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Refined crafting component.", FAINT).decoration(TextDecoration.ITALIC, false),
                Component.text(RAW_PER_CONCENTRATE + "× raw " + sin.display() + " → 1.", FAINT)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Not for the Censer — feeds Pure/Standard reagents.", FAINT)
                        .decoration(TextDecoration.ITALIC, true)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, sin.name());
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("extraction/concentrate/" + sin.name().toLowerCase()));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** The sin a Concentrate item carries (by tag), or {@code null} if it isn't one. */
    public static Sin sinOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String n = meta.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        if (n == null) return null;
        try { return Sin.valueOf(n); } catch (IllegalArgumentException e) { return null; }
    }

    /** Register the raw→Concentrate recipes (one per sin). Idempotent across reload. */
    public static void registerRecipes(Plugin plugin) {
        for (Sin s : Sin.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "concentrate_" + s.name().toLowerCase());
            Bukkit.removeRecipe(key);
            ItemStack result = create(s, 1);
            if (result == null) continue;
            ShapelessRecipe recipe = new ShapelessRecipe(key, result);
            recipe.addIngredient(RAW_PER_CONCENTRATE, RAW.get(s)); // 8 raw of this sin
            Bukkit.addRecipe(recipe);
        }
    }
}
