package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The catalyst forge recipes — what grind-gated sub-components each weapon's signature-lock costs (§15/§16).
 * Rarity scales with grade: a ZAYIN lock is a quick 1–2 part forge; a WAW is a multi-component sub-project
 * (Solemn Lament = Bell + Pufferfish + White Candles + Chiseled Quartz + a pile of Lapis). Components are
 * plain vanilla items you grind for — the difficulty is <b>acquisition</b>, not luck.
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
        // ---- ZAYIN — a quick forge ----
        recipe("penitence", 2, Material.FEATHER, 1, Material.REDSTONE, 2);

        // ---- TETH — a couple of parts, one grind-gate ----
        recipe("beak", 4, Material.FEATHER, 2, Material.REDSTONE, 6, Material.GOLD_NUGGET, 2);

        // ---- HE — a small sub-project ----
        recipe("our_galaxy", 8, Material.PUFFERFISH, 4, Material.CHORUS_FRUIT, 3,
                Material.AMETHYST_SHARD, 2, Material.LAPIS_LAZULI, 8);

        // ---- WAW — the multi-component grind ----
        recipe("solemn_lament", 16, Material.BELL, 1, Material.PUFFERFISH, 6,
                Material.WHITE_CANDLE, 4, Material.CHISELED_QUARTZ_BLOCK, 2, Material.LAPIS_LAZULI, 16);
        recipe("sword_of_tears", 16, Material.CHISELED_QUARTZ_BLOCK, 3, Material.INK_SAC, 16,
                Material.AMETHYST_SHARD, 4, Material.ENDER_PEARL, 2);
    }

    /** The recipe for a weapon's catalyst, or {@code null} if none is defined yet. */
    public static Recipe forWeapon(String weaponId) { return REGISTRY.get(weaponId); }

    public static Iterable<Recipe> all() { return REGISTRY.values(); }

    public static int count() { return REGISTRY.size(); }
}
