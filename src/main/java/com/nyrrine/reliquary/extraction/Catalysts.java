package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The catalyst forge recipes — what grind-gated sub-components each weapon's signature-lock costs (§15/§16).
 * Rarity scales with grade: a ZAYIN lock is a quick 2-part forge; a WAW is a 4-component sub-project. Every
 * material is globally unique — no vanilla item appears in more than one weapon's recipe — so the pool is a
 * wide, deliberately arbitrary spread of blocks, plants, mob drops and oddments. The difficulty is
 * <b>acquisition</b>, not luck.
 */
public final class Catalysts {

    private Catalysts() {}

    /** A weapon's catalyst recipe: the vanilla components it consumes + the Enkephalin to fire the forge. */
    public record Recipe(String weaponId, Map<Material, Integer> components, int enkephalin) {}

    private static final Map<String, Recipe> REGISTRY = new LinkedHashMap<>();

    /** Register a recipe: {@code recipe(id, enkephalin, MAT, count, MAT, count, ...)}. */
    private static void recipe(String weaponId, int enkephalin, Object... matCountPairs) {
        Map<Material, Integer> comps = new LinkedHashMap<>();
        for (int i = 0; i + 1 < matCountPairs.length; i += 2) {
            comps.put((Material) matCountPairs[i], (Integer) matCountPairs[i + 1]);
        }
        REGISTRY.put(weaponId, new Recipe(weaponId, comps, enkephalin));
    }

    static {
        // Owner's directive: drop strict sin-theming, go for "the most random blocks" — every material below is
        // used by EXACTLY ONE recipe across the whole roster (a test enforces global uniqueness). Counts still
        // scale by grade so a WAW forge is a real grind, but the components are a wide, arbitrary vanilla spread.

        // ---- ZAYIN — a quick forge (2 types, counts 1–3, enkephalin 2) ----
        recipe("penitence", 2, Material.FEATHER, 2, Material.BELL, 1);
        recipe("soda", 2, Material.WHITE_CANDLE, 3, Material.ORANGE_CANDLE, 2);

        // ---- TETH — a couple of parts, one grind-gate (3 types, counts 2–6, enkephalin 4) ----
        recipe("beak", 4, Material.PURPLE_CANDLE, 2, Material.PUFFERFISH, 3, Material.ENDER_PEARL, 2);
        recipe("fourth_match_flame", 4, Material.OXIDIZED_COPPER, 4, Material.TUFF, 5, Material.CALCITE, 6);
        recipe("red_eyes", 4, Material.DRIPSTONE_BLOCK, 3, Material.SCULK, 4, Material.PACKED_MUD, 5);
        recipe("regret", 4, Material.MANGROVE_ROOTS, 4, Material.SPORE_BLOSSOM, 2, Material.BIG_DRIPLEAF, 3);
        recipe("logging", 4, Material.AZALEA, 4, Material.GLOW_LICHEN, 5, Material.SHROOMLIGHT, 2);
        recipe("wrist_cutter", 4, Material.WEEPING_VINES, 3, Material.TWISTING_VINES, 4,
                Material.CRIMSON_FUNGUS, 5);
        recipe("christmas", 4, Material.WARPED_FUNGUS, 3, Material.BASALT, 6, Material.SMOOTH_BASALT, 4);

        // ---- HE — a small sub-project (3 types, counts 4–8, enkephalin 8) ----
        recipe("our_galaxy", 8, Material.BLACKSTONE, 6, Material.GILDED_BLACKSTONE, 4,
                Material.QUARTZ_BRICKS, 5);
        recipe("grinder_mk4", 8, Material.SMOOTH_QUARTZ, 6, Material.PRISMARINE_BRICKS, 5,
                Material.DARK_PRISMARINE, 4);
        recipe("crimson_scar", 8, Material.SEA_LANTERN, 4, Material.SPONGE, 6, Material.DRIED_KELP, 8);
        recipe("cobalt_scar", 8, Material.HEAVY_CORE, 4, Material.TRIAL_KEY, 4,
                Material.TUBE_CORAL, 6);
        recipe("harvest", 8, Material.BRAIN_CORAL, 5, Material.BUBBLE_CORAL, 6, Material.FIRE_CORAL, 7);
        recipe("life_for_a_daredevil", 8, Material.HORN_CORAL, 5, Material.WAXED_CHISELED_COPPER, 4,
                Material.SWEET_BERRIES, 8);
        recipe("laetitia", 8, Material.ANGLER_POTTERY_SHERD, 4, Material.ARCHER_POTTERY_SHERD, 6, Material.LIGHTNING_ROD, 5);

        // ---- WAW — the multi-component grind (4 types, counts 6–16, enkephalin 16) ----
        recipe("solemn_lament", 16, Material.MUD_BRICKS, 8, Material.DECORATED_POT, 6,
                Material.SHULKER_SHELL, 6, Material.OXIDIZED_COPPER_GRATE, 8);
        recipe("sword_of_tears", 16, Material.BREEZE_ROD, 10, Material.GHAST_TEAR, 6,
                Material.MAGMA_BLOCK, 12, Material.CRYING_OBSIDIAN, 8);
        recipe("lamp", 16, Material.LODESTONE, 6, Material.HONEY_BLOCK, 10, Material.SLIME_BLOCK, 12,
                Material.WEATHERED_COPPER_BULB, 6);
        recipe("magic_bullet", 16, Material.OMINOUS_TRIAL_KEY, 8, Material.WIND_CHARGE, 8,
                Material.AMETHYST_CLUSTER, 6, Material.TINTED_GLASS, 6);
        recipe("love_and_hate", 16, Material.POINTED_DRIPSTONE, 8, Material.ROOTED_DIRT, 12,
                Material.TURTLE_EGG, 6, Material.TROPICAL_FISH, 10);
        recipe("green_stem", 16, Material.PUMPKIN_PIE, 8, Material.GLOW_BERRIES, 12,
                Material.FERMENTED_SPIDER_EYE, 6, Material.RABBIT_FOOT, 6);
        recipe("screaming_wedge", 16, Material.RABBIT_HIDE, 10, Material.SNIFFER_EGG, 8,
                Material.WARPED_WART_BLOCK, 8, Material.CRIMSON_NYLIUM, 12);
        recipe("heaven", 16, Material.WARPED_NYLIUM, 12, Material.SOUL_LANTERN, 8, Material.COPPER_BULB, 10,
                Material.LANTERN, 8);
    }

    /** The recipe for a weapon's catalyst, or {@code null} if none is defined yet. */
    public static Recipe forWeapon(String weaponId) { return REGISTRY.get(weaponId); }

    public static Iterable<Recipe> all() { return REGISTRY.values(); }

    public static int count() { return REGISTRY.size(); }
}
