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
        // ---- ZAYIN — a quick forge (2 types, small counts, enkephalin 2) ----
        recipe("penitence", 2, Material.FEATHER, 1, Material.REDSTONE, 2);
        // Soda — Lust/Gluttony: sweet fizz and gut. Honey + slime.
        recipe("soda", 2, Material.HONEY_BOTTLE, 2, Material.SLIME_BALL, 3);

        // ---- TETH — a couple of parts, one grind-gate (enkephalin 4) ----
        recipe("beak", 4, Material.FEATHER, 2, Material.REDSTONE, 6, Material.GOLD_NUGGET, 2);
        // Fourth Match Flame — Wrath/Lust: a struck match, a warm ache. Blaze + honeycomb.
        recipe("fourth_match_flame", 4, Material.BLAZE_POWDER, 4, Material.FIRE_CHARGE, 3,
                Material.HONEYCOMB, 2);
        // Red Eyes — Wrath/Pride/Gluttony: bloodshot fury, gold glare, gorged flesh.
        recipe("red_eyes", 4, Material.BLAZE_ROD, 3, Material.GOLD_NUGGET, 4, Material.ROTTEN_FLESH, 6);
        // Regret — Wrath/Gloom: hot anger cooling into a blue sink. Redstone + ink.
        recipe("regret", 4, Material.REDSTONE, 5, Material.MAGMA_CREAM, 3, Material.INK_SAC, 4);
        // Logging — Gluttony/Sloth/Wrath: a bread-fed grind that never stops. Bread + soul sand + redstone.
        recipe("logging", 4, Material.BREAD, 6, Material.SOUL_SAND, 3, Material.REDSTONE, 2);
        // Wrist Cutter — Gloom/Sloth: a slow blue despair. Lapis + cobweb.
        recipe("wrist_cutter", 4, Material.LAPIS_LAZULI, 5, Material.COBWEB, 3);
        // Christmas — Gluttony/Lust: a gorged, sugared feast. Cake + pumpkin pie + glow berries.
        recipe("christmas", 4, Material.CAKE, 2, Material.PUMPKIN_PIE, 3, Material.GLOW_BERRIES, 4);

        // ---- HE — a small sub-project (3–4 types, ≥1 grind-gate, enkephalin 8) ----
        recipe("our_galaxy", 8, Material.PUFFERFISH, 4, Material.CHORUS_FRUIT, 3,
                Material.AMETHYST_SHARD, 2, Material.LAPIS_LAZULI, 8);
        // Grinder Mk4 — Gluttony/Wrath: an insatiable weathered engine. Gate: OXIDIZED_COPPER.
        recipe("grinder_mk4", 8, Material.OXIDIZED_COPPER, 2, Material.SLIME_BALL, 8,
                Material.ROTTEN_FLESH, 10, Material.BLAZE_POWDER, 6);
        // Crimson Scar — Wrath/Pride: a burning, gilded wound. Gate: CHISELED_QUARTZ_BLOCK.
        recipe("crimson_scar", 8, Material.CHISELED_QUARTZ_BLOCK, 2, Material.BLAZE_ROD, 6,
                Material.GOLD_INGOT, 4, Material.REDSTONE, 8);
        // Cobalt Scar — Envy/Wrath/Pride: an end-touched, jealous edge. Gate: ENDER_PEARL.
        recipe("cobalt_scar", 8, Material.ENDER_PEARL, 2, Material.PRISMARINE_CRYSTALS, 6,
                Material.BLAZE_POWDER, 6, Material.GOLD_INGOT, 3);
        // Harvest — Sloth/Envy/Gloom: a listless, envious reaping. Gate: PUFFERFISH.
        recipe("harvest", 8, Material.PUFFERFISH, 4, Material.SOUL_SAND, 6,
                Material.LAPIS_LAZULI, 8, Material.COBWEB, 4);
        // Life for a Daredevil — Lust/Pride/Wrath: a gilded, reckless thrill. Gate: CHISELED_QUARTZ_BLOCK.
        recipe("life_for_a_daredevil", 8, Material.CHISELED_QUARTZ_BLOCK, 2, Material.GLOW_BERRIES, 8,
                Material.GOLD_INGOT, 4, Material.BLAZE_POWDER, 6);
        // Laetitia — Lust/Gluttony: a honeyed, gorging joy. Gate: PUFFERFISH.
        recipe("laetitia", 8, Material.PUFFERFISH, 3, Material.HONEY_BOTTLE, 8,
                Material.HONEYCOMB, 6, Material.PUMPKIN_PIE, 4);

        // ---- WAW — the multi-component grind (4–5 types, TWO+ grind-gates, enkephalin 16) ----
        recipe("solemn_lament", 16, Material.BELL, 1, Material.PUFFERFISH, 6,
                Material.WHITE_CANDLE, 4, Material.CHISELED_QUARTZ_BLOCK, 2, Material.LAPIS_LAZULI, 16);
        recipe("sword_of_tears", 16, Material.CHISELED_QUARTZ_BLOCK, 3, Material.INK_SAC, 16,
                Material.AMETHYST_SHARD, 4, Material.ENDER_PEARL, 2);
        // Lamp — Pride/Gloom/Sloth: a proud light gone to verdigris and dusk. Gates: CHISELED_QUARTZ_BLOCK + OXIDIZED_COPPER.
        recipe("lamp", 16, Material.CHISELED_QUARTZ_BLOCK, 3, Material.OXIDIZED_COPPER, 4,
                Material.GOLD_INGOT, 8, Material.LAPIS_LAZULI, 12, Material.SOUL_SAND, 6);
        // Magic Bullet — Pride/Wrath: a gilded round, a powder charge. Gates: CHISELED_QUARTZ_BLOCK + OXIDIZED_COPPER.
        recipe("magic_bullet", 16, Material.CHISELED_QUARTZ_BLOCK, 3, Material.OXIDIZED_COPPER, 3,
                Material.GOLD_INGOT, 10, Material.BLAZE_POWDER, 12, Material.FIRE_CHARGE, 8);
        // Love and Hate — Lust/Envy: honey and end-pearl covetousness. Gates: PUFFERFISH + ENDER_PEARL.
        recipe("love_and_hate", 16, Material.PUFFERFISH, 6, Material.ENDER_PEARL, 3,
                Material.HONEY_BOTTLE, 12, Material.GLOW_BERRIES, 10, Material.PRISMARINE_CRYSTALS, 8);
        // Green Stem — Gluttony/Envy/Lust: a gorging, jealous, sweet vine. Gates: PUFFERFISH + ENDER_PEARL.
        recipe("green_stem", 16, Material.PUFFERFISH, 6, Material.ENDER_PEARL, 2,
                Material.SLIME_BALL, 12, Material.CHORUS_FRUIT, 8, Material.HONEYCOMB, 8);
        // Screaming Wedge — Gloom/Wrath/Sloth: a tolling, furious, weathered grief. Gates: BELL + OXIDIZED_COPPER.
        recipe("screaming_wedge", 16, Material.BELL, 2, Material.OXIDIZED_COPPER, 4,
                Material.REDSTONE, 12, Material.INK_SAC, 10, Material.SOUL_SAND, 8);
        // Heaven — Pride/Wrath: an ascendant gilded blaze. Gates: CHISELED_QUARTZ_BLOCK + ENDER_PEARL.
        recipe("heaven", 16, Material.CHISELED_QUARTZ_BLOCK, 4, Material.ENDER_PEARL, 4,
                Material.GOLD_INGOT, 16, Material.DIAMOND, 3, Material.BLAZE_ROD, 6);
    }

    /** The recipe for a weapon's catalyst, or {@code null} if none is defined yet. */
    public static Recipe forWeapon(String weaponId) { return REGISTRY.get(weaponId); }

    public static Iterable<Recipe> all() { return REGISTRY.values(); }

    public static int count() { return REGISTRY.size(); }
}
