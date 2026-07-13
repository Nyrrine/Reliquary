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
        // Pures — the sin dyes now belong to Concentrates, so Pures carry their own small items.
        CARRIER.put("distilled_sorrow", Material.INK_SAC);               // Gloom    · Pure
        CARRIER.put("ember_distillate", Material.FIRE_CHARGE);           // Wrath    · Pure
        CARRIER.put("burnished_vanity", Material.NETHERITE_SCRAP);       // Pride    · Pure
        CARRIER.put("nectar_draught",   Material.GLASS_BOTTLE);          // Lust     · Pure
        CARRIER.put("lethe_draught",    Material.LINGERING_POTION);      // Sloth    · Pure  — Lethe's Draught
        CARRIER.put("verdant_spite",    Material.LIME_DYE);              // Envy     · Pure
        CARRIER.put("ravening_draught", Material.SUGAR);                 // Gluttony · Pure
        // Standards — cool, name-matched items (Dante / Project Moon flavour).
        CARRIER.put("refined_cinder",   Material.NETHERITE_INGOT);       // Wrath    · Standard — Phlegethon Regulus
        CARRIER.put("knell_extract",    Material.GOAT_HORN);             // Gloom    · Standard — Acheron Knell
        CARRIER.put("mirror_polish",    Material.ENCHANTED_GOLDEN_APPLE);// Pride    · Standard — Narcissus Gilt
        CARRIER.put("verdigris_rest",   Material.BLUE_ICE);              // Sloth    · Standard — Cocytus Rime
        CARRIER.put("amber_rapture",    Material.SPLASH_POTION);         // Lust     · Standard — Cytherea's Rapture
        CARRIER.put("rancorous_bloom",  Material.CREEPER_HEAD);          // Envy     · Standard — Invidia's Bloom
        CARRIER.put("gluttons_feast",   Material.PIGLIN_HEAD);           // Gluttony · Standard — Cerberus' Morsel
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
        meta.displayName(Component.text(r.display()).color(sinColor(r)).decoration(TextDecoration.ITALIC, false));
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
        // Potion-carrier reagents (draughts) get a neutral base + hidden effect lines so no vanilla clutter shows.
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
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

    /** Colour a refined reagent's name by its dominant sin (QoL — reads as its sin at a glance). */
    private static TextColor sinColor(Reagent r) {
        Sin dom = null; double best = 0.0;
        for (Sin s : Sin.values()) { double d = r.delta()[s.index()]; if (d > best) { best = d; dom = s; } }
        return dom != null ? dom.color() : NAME;
    }

    private static String tierLabel(Reagent.Tier t) {
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---- refining recipes: the affinity grind ladder (Concentrate → Pure → Standard) ----------------
    //
    // Raw sin material is weak + dirty. You crush it into a Concentrate (SinConcentrate, 8 raw each), then:
    //   Pure     = 4 Concentrate + 1 Amethyst   (32 raw)
    //   Standard = 4 Pure        + 1 gated item  (128 raw ≈ two stacks)
    // Concentrates and Pures are consumed by EXACT match (RecipeChoice.ExactChoice), so you truly have to
    // climb the ladder — no shortcut with a vanilla lookalike. Each sin's (pure, standard) ids below.

    /** Concentrates refined into one Pure/Standard. */
    public static final int CONCENTRATE_PER_PURE = 4;
    public static final int PURE_PER_STANDARD = 4;

    /** Sin → {pureId, standardId} — the refined reagent this sin's ladder produces at each rung. */
    private static final Map<Sin, String[]> LADDER = new java.util.EnumMap<>(Sin.class);
    /** Standard → the gated patina/chiseled item its recipe also demands. */
    private static final Map<String, Material> STANDARD_GATE = new LinkedHashMap<>();
    static {
        LADDER.put(Sin.WRATH,    new String[]{"ember_distillate", "refined_cinder"});
        LADDER.put(Sin.GLOOM,    new String[]{"distilled_sorrow", "knell_extract"});
        LADDER.put(Sin.PRIDE,    new String[]{"burnished_vanity", "mirror_polish"});
        LADDER.put(Sin.LUST,     new String[]{"nectar_draught",   "amber_rapture"});
        LADDER.put(Sin.SLOTH,    new String[]{"lethe_draught",    "verdigris_rest"});
        LADDER.put(Sin.ENVY,     new String[]{"verdant_spite",    "rancorous_bloom"});
        LADDER.put(Sin.GLUTTONY, new String[]{"ravening_draught", "gluttons_feast"});
        STANDARD_GATE.put("refined_cinder",  Material.CHISELED_QUARTZ_BLOCK);
        STANDARD_GATE.put("knell_extract",   Material.WEATHERED_COPPER);
        STANDARD_GATE.put("mirror_polish",   Material.CHISELED_QUARTZ_BLOCK);
        STANDARD_GATE.put("amber_rapture",   Material.WEATHERED_COPPER);
        STANDARD_GATE.put("verdigris_rest",  Material.WEATHERED_COPPER);
        STANDARD_GATE.put("rancorous_bloom", Material.WEATHERED_COPPER);
        STANDARD_GATE.put("gluttons_feast",  Material.WEATHERED_COPPER);
    }

    /** The Pure refined reagent id for a sin's ladder, or {@code null}. */
    public static String pureId(Sin s) { String[] l = LADDER.get(s); return l == null ? null : l[0]; }

    /** The Standard refined reagent id for a sin's ladder, or {@code null}. */
    public static String standardId(Sin s) { String[] l = LADDER.get(s); return l == null ? null : l[1]; }

    /** Which sin's ladder a refined reagent id belongs to, or {@code null}. */
    public static Sin sinOf(String reagentId) {
        for (var e : LADDER.entrySet()) if (e.getValue()[0].equals(reagentId) || e.getValue()[1].equals(reagentId)) return e.getKey();
        return null;
    }

    /** Whether a refined reagent id is the Standard (top) rung of its ladder. */
    public static boolean isStandard(String reagentId) {
        for (String[] l : LADDER.values()) if (l[1].equals(reagentId)) return true;
        return false;
    }

    /** The gated item a Standard reagent's recipe demands. */
    public static Material gateFor(String standardId) {
        return STANDARD_GATE.getOrDefault(standardId, Material.WEATHERED_COPPER);
    }

    /** Human-readable crafting-recipe lines for a refined reagent id (empty if it isn't one). */
    public static List<String> recipeLines(String reagentId) {
        for (Sin s : LADDER.keySet()) {
            String[] l = LADDER.get(s);
            if (l[0].equals(reagentId)) { // Pure
                return List.of(CONCENTRATE_PER_PURE + "× " + s.display() + " Concentrate",
                        "1× Amethyst Shard", "1× Iron Ingot");
            }
            if (l[1].equals(reagentId)) { // Standard
                Reagent pure = Reagents.byId(l[0]);
                Material gate = STANDARD_GATE.getOrDefault(reagentId, Material.WEATHERED_COPPER);
                return List.of(PURE_PER_STANDARD + "× " + (pure != null ? pure.display() : l[0]),
                        "1× " + prettyMat(gate));
            }
        }
        return List.of();
    }

    private static String prettyMat(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    /** Register (idempotently) the Pure and Standard refining recipes for every sin's ladder. */
    public static void registerRecipes(Plugin plugin) {
        for (Map.Entry<Sin, String[]> e : LADDER.entrySet()) {
            Sin sin = e.getKey();
            String pureId = e.getValue()[0];
            String standardId = e.getValue()[1];

            // Pure = 4 Concentrate(this sin, exact) + 1 Amethyst.
            ShapelessRecipe pure = new ShapelessRecipe(recipeKey(plugin, pureId), create(pureId, 1));
            var concentrate = new org.bukkit.inventory.RecipeChoice.ExactChoice(SinConcentrate.create(sin, 1));
            for (int i = 0; i < CONCENTRATE_PER_PURE; i++) pure.addIngredient(concentrate);
            pure.addIngredient(Material.AMETHYST_SHARD);
            pure.addIngredient(Material.IRON_INGOT); // a little iron binds the concentrates into a Pure
            addRecipe(plugin, pureId, pure);

            // Standard = 4 Pure(exact) + 1 gated item.
            ShapelessRecipe std = new ShapelessRecipe(recipeKey(plugin, standardId), create(standardId, 1));
            var pureChoice = new org.bukkit.inventory.RecipeChoice.ExactChoice(create(pureId, 1));
            for (int i = 0; i < PURE_PER_STANDARD; i++) std.addIngredient(pureChoice);
            std.addIngredient(STANDARD_GATE.getOrDefault(standardId, Material.WEATHERED_COPPER));
            addRecipe(plugin, standardId, std);
        }
    }

    private static NamespacedKey recipeKey(Plugin plugin, String reagentId) {
        return new NamespacedKey(plugin, "refine_" + reagentId);
    }

    private static void addRecipe(Plugin plugin, String reagentId, ShapelessRecipe recipe) {
        Bukkit.removeRecipe(recipeKey(plugin, reagentId)); // idempotent across /reload
        if (recipe.getResult().getType().isAir()) return;
        Bukkit.addRecipe(recipe);
    }
}
