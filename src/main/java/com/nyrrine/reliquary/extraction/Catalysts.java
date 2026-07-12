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
        // used by EXACTLY ONE recipe across the whole roster (a test enforces global uniqueness). Counts scale
        // hard by grade so even a ZAYIN forge bites and a WAW forge is a multi-stack endgame grind; the
        // components stay a wide, arbitrary — but always obtainable — vanilla spread (no End items, no Heavy
        // Core, no Ominous/trial-chamber-ominous-only drops).

        // ---- ZAYIN — a quick forge, but never free (3 types, counts 2–5, enkephalin 3) ----
        recipe("penitence", 3, Material.FEATHER, 4, Material.BELL, 3, Material.PHANTOM_MEMBRANE, 2);
        recipe("soda", 3, Material.WHITE_CANDLE, 5, Material.ORANGE_CANDLE, 4, Material.YELLOW_CANDLE, 3);

        // ---- TETH — a real handful of parts, one grind-gate (4 types, counts 3–12, enkephalin 6) ----
        recipe("beak", 6, Material.PURPLE_CANDLE, 4, Material.PUFFERFISH, 6, Material.ENDER_PEARL, 4,
                Material.INK_SAC, 5);
        recipe("fourth_match_flame", 6, Material.OXIDIZED_COPPER, 8, Material.TUFF, 10, Material.CALCITE, 12,
                Material.POLISHED_TUFF, 6);
        recipe("red_eyes", 6, Material.DRIPSTONE_BLOCK, 6, Material.SCULK, 8, Material.PACKED_MUD, 10,
                Material.ECHO_SHARD, 3);
        recipe("regret", 6, Material.MANGROVE_ROOTS, 8, Material.SPORE_BLOSSOM, 4, Material.BIG_DRIPLEAF, 6,
                Material.SMALL_DRIPLEAF, 5);
        recipe("logging", 6, Material.AZALEA, 8, Material.GLOW_LICHEN, 10, Material.SHROOMLIGHT, 4,
                Material.FLOWERING_AZALEA, 5);
        recipe("wrist_cutter", 6, Material.WEEPING_VINES, 6, Material.TWISTING_VINES, 8,
                Material.CRIMSON_FUNGUS, 10, Material.CRIMSON_ROOTS, 6);
        recipe("christmas", 6, Material.WARPED_FUNGUS, 6, Material.BASALT, 12, Material.SMOOTH_BASALT, 8,
                Material.POLISHED_BASALT, 6);

        // ---- HE — a genuine sub-project (5 types, counts 6–14, enkephalin 12) ----
        recipe("our_galaxy", 12, Material.BLACKSTONE, 10, Material.GILDED_BLACKSTONE, 8,
                Material.QUARTZ_BRICKS, 9, Material.POLISHED_BLACKSTONE, 8, Material.CHISELED_QUARTZ_BLOCK, 6);
        recipe("grinder_mk4", 12, Material.SMOOTH_QUARTZ, 10, Material.PRISMARINE_BRICKS, 9,
                Material.DARK_PRISMARINE, 8, Material.PRISMARINE, 8, Material.PRISMARINE_SHARD, 10);
        recipe("crimson_scar", 12, Material.SEA_LANTERN, 8, Material.SPONGE, 10, Material.DRIED_KELP, 14,
                Material.WET_SPONGE, 6, Material.PRISMARINE_CRYSTALS, 8);
        recipe("cobalt_scar", 12, Material.ANVIL, 6, Material.TRIAL_KEY, 8, Material.TUBE_CORAL, 10,
                Material.TUBE_CORAL_BLOCK, 8, Material.TUBE_CORAL_FAN, 8);
        recipe("harvest", 12, Material.BRAIN_CORAL, 8, Material.BUBBLE_CORAL, 10, Material.FIRE_CORAL, 12,
                Material.BRAIN_CORAL_BLOCK, 8, Material.BUBBLE_CORAL_FAN, 8);
        recipe("life_for_a_daredevil", 12, Material.HORN_CORAL, 8, Material.WAXED_CHISELED_COPPER, 8,
                Material.SWEET_BERRIES, 14, Material.HORN_CORAL_BLOCK, 8, Material.FIRE_CORAL_BLOCK, 8);
        recipe("laetitia", 12, Material.ANGLER_POTTERY_SHERD, 6, Material.ARCHER_POTTERY_SHERD, 8,
                Material.LIGHTNING_ROD, 8, Material.PRIZE_POTTERY_SHERD, 6, Material.BLADE_POTTERY_SHERD, 6);

        // ---- WAW — the multi-stack endgame grind (6 types, counts 8–18, enkephalin 24) ----
        recipe("solemn_lament", 24, Material.MUD_BRICKS, 14, Material.DECORATED_POT, 10,
                Material.PURPLE_WOOL, 10, Material.OXIDIZED_COPPER_GRATE, 12,
                Material.MUDDY_MANGROVE_ROOTS, 10, Material.SHEAF_POTTERY_SHERD, 8);
        recipe("sword_of_tears", 24, Material.BREEZE_ROD, 16, Material.GHAST_TEAR, 10,
                Material.MAGMA_BLOCK, 18, Material.CRYING_OBSIDIAN, 12,
                Material.MAGMA_CREAM, 12, Material.BLAZE_ROD, 10);
        recipe("lamp", 24, Material.LODESTONE, 10, Material.HONEY_BLOCK, 16, Material.SLIME_BLOCK, 18,
                Material.WEATHERED_COPPER_BULB, 10, Material.HONEYCOMB_BLOCK, 12, Material.SLIME_BALL, 16);
        recipe("magic_bullet", 24, Material.CRAFTER, 10, Material.WIND_CHARGE, 14,
                Material.AMETHYST_CLUSTER, 10, Material.TINTED_GLASS, 10,
                Material.AMETHYST_SHARD, 16, Material.AMETHYST_BLOCK, 8);
        recipe("love_and_hate", 24, Material.POINTED_DRIPSTONE, 14, Material.ROOTED_DIRT, 18,
                Material.TURTLE_EGG, 10, Material.TROPICAL_FISH, 16,
                Material.HANGING_ROOTS, 12, Material.TURTLE_SCUTE, 8);
        recipe("green_stem", 24, Material.PUMPKIN_PIE, 14, Material.GLOW_BERRIES, 18,
                Material.FERMENTED_SPIDER_EYE, 10, Material.RABBIT_FOOT, 10,
                Material.SPIDER_EYE, 14, Material.MELON_SLICE, 16);
        recipe("screaming_wedge", 24, Material.RABBIT_HIDE, 16, Material.SUSPICIOUS_STEW, 12,
                Material.WARPED_WART_BLOCK, 14, Material.CRIMSON_NYLIUM, 18,
                Material.NETHER_WART_BLOCK, 12, Material.WARPED_ROOTS, 14);
        recipe("heaven", 24, Material.WARPED_NYLIUM, 18, Material.SOUL_LANTERN, 12, Material.COPPER_BULB, 16,
                Material.LANTERN, 12, Material.GLOWSTONE, 14, Material.SOUL_TORCH, 16);
    }

    /** The recipe for a weapon's catalyst, or {@code null} if none is defined yet. */
    public static Recipe forWeapon(String weaponId) { return REGISTRY.get(weaponId); }

    public static Iterable<Recipe> all() { return REGISTRY.values(); }

    public static int count() { return REGISTRY.size(); }
}
