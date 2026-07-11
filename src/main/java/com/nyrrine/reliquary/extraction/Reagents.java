package com.nyrrine.reliquary.extraction;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.nyrrine.reliquary.extraction.Reagent.Tier.CRUDE;
import static com.nyrrine.reliquary.extraction.Reagent.Tier.PURE;
import static com.nyrrine.reliquary.extraction.Reagent.Tier.REFINED;
import static com.nyrrine.reliquary.extraction.Reagent.Tier.STANDARD;
import static com.nyrrine.reliquary.extraction.Reagent.Tier.UTILITY;
import static com.nyrrine.reliquary.extraction.Sin.ENVY;
import static com.nyrrine.reliquary.extraction.Sin.GLOOM;
import static com.nyrrine.reliquary.extraction.Sin.GLUTTONY;
import static com.nyrrine.reliquary.extraction.Sin.LUST;
import static com.nyrrine.reliquary.extraction.Sin.PRIDE;
import static com.nyrrine.reliquary.extraction.Sin.SLOTH;
import static com.nyrrine.reliquary.extraction.Sin.WRATH;

/**
 * The reagent fingerprint table — the concrete numbers guides get built on. One entry per reagent, grouped
 * by the sin it serves, from dirty bulk (Crude, spam-safe, low ceiling) up to signature-grade Standards
 * (gated behind the grind items) plus the shared utilities (buffers, solvents, panic reset).
 *
 * <p>Values are STARTER tuning — the whole point of the headless engine tests is to let these move without
 * fear. Registration order is stable so a lectern / creative menu lists them sensibly.
 */
public final class Reagents {

    private Reagents() {}

    private static final Map<String, Reagent> REGISTRY = new LinkedHashMap<>();

    private static Reagent reg(Reagent r) {
        REGISTRY.put(r.id(), r);
        return r;
    }

    // ---- Wrath (red) ---------------------------------------------------------------
    public static final Reagent REDSTONE_DUST = reg(Reagent.of("redstone_dust", "Redstone Dust")
            .delta(WRATH, 1).contam(0.1).stab(-1).tier(REFINED)
            .source("mining — the Wrath scalpel").build());
    public static final Reagent BLAZE_POWDER = reg(Reagent.of("blaze_powder", "Blaze Powder")
            .delta(WRATH, 10).delta(GLOOM, -8).contam(1.5).stab(-4).tier(REFINED)
            .source("blaze — mover + Gloom corrector").build());
    public static final Reagent BLAZE_ROD = reg(Reagent.of("blaze_rod", "Blaze Rod")
            .roll(WRATH, 12, 40).contam(0.4).stab(-16).tier(PURE)
            .source("blaze — volatile").build());
    public static final Reagent MAGMA_CREAM = reg(Reagent.of("magma_cream", "Magma Cream")
            .delta(WRATH, 7).delta(GLUTTONY, 2).contam(1.2).stab(-3).tier(REFINED)
            .source("slimes + blaze").build());
    public static final Reagent REFINED_CINDER = reg(Reagent.of("refined_cinder", "Refined Cinder")
            .delta(WRATH, 22).contam(0.05).stab(-30).tier(STANDARD)
            .source("processed blaze chain").build());

    // ---- Gloom (blue) --------------------------------------------------------------
    public static final Reagent INK_SAC = reg(Reagent.of("ink_sac", "Ink Sac")
            .delta(GLOOM, 8).delta(SLOTH, 2).contam(3).stab(-2).tier(CRUDE)
            .source("squid — dirty bulk").build());
    public static final Reagent LAPIS_LAZULI = reg(Reagent.of("lapis_lazuli", "Lapis Lazuli")
            .delta(GLOOM, 3).contam(0.3).stab(-1).tier(REFINED)
            .source("mining — the Gloom scalpel").build());
    public static final Reagent SOUL_SOIL = reg(Reagent.of("soul_soil", "Soul Soil")
            .delta(GLOOM, 9).delta(WRATH, -5).contam(1.2).stab(-3).tier(REFINED)
            .source("nether").build());
    public static final Reagent DISTILLED_SORROW = reg(Reagent.of("distilled_sorrow", "Distilled Sorrow")
            .delta(GLOOM, 11).contam(0.1).stab(-15).tier(PURE)
            .source("ink + lapis chain").build());
    public static final Reagent KNELL_EXTRACT = reg(Reagent.of("knell_extract", "Knell Extract")
            .delta(GLOOM, 20).contam(0.05).stab(-30).tier(STANDARD)
            .source("gated: Bells + Oxidized Copper").build());

    // ---- Pride (indigo) ------------------------------------------------------------
    public static final Reagent GOLD_NUGGET = reg(Reagent.of("gold_nugget", "Gold Nugget")
            .delta(PRIDE, 2).contam(0.2).stab(-1).tier(REFINED)
            .source("craft — the Pride scalpel").build());
    public static final Reagent GOLD_INGOT = reg(Reagent.of("gold_ingot", "Gold Ingot")
            .delta(PRIDE, 8).delta(GLOOM, -4).contam(1).stab(-3).tier(REFINED)
            .source("mining").build());
    public static final Reagent DIAMOND = reg(Reagent.of("diamond", "Diamond")
            .delta(PRIDE, 12).contam(0.1).stab(-14).tier(PURE)
            .source("mining").build());
    public static final Reagent MIRROR_POLISH = reg(Reagent.of("mirror_polish", "Mirror Polish")
            .delta(PRIDE, 20).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: Chiseled Quartz").build());

    // ---- Lust (orange) -------------------------------------------------------------
    public static final Reagent GLOW_BERRIES = reg(Reagent.of("glow_berries", "Glow Berries")
            .delta(LUST, 7).delta(GLUTTONY, 2).contam(3).stab(-2).tier(CRUDE)
            .source("farm — dirty bulk").build());
    public static final Reagent HONEY_BOTTLE = reg(Reagent.of("honey_bottle", "Honey Bottle")
            .delta(LUST, 9).delta(ENVY, -4).contam(1).stab(-3).tier(REFINED)
            .source("bees").build());
    public static final Reagent NECTAR_DRAUGHT = reg(Reagent.of("nectar_draught", "Nectar Draught")
            .delta(LUST, 11).contam(0.1).stab(-15).tier(PURE)
            .source("honey chain").build());

    // ---- Sloth (yellow) ------------------------------------------------------------
    public static final Reagent SOUL_SAND = reg(Reagent.of("soul_sand", "Soul Sand")
            .delta(SLOTH, 7).delta(GLOOM, 2).contam(3).stab(-2).tier(CRUDE)
            .source("nether — dirty bulk").build());
    public static final Reagent FERMENTED_SPIDER_EYE = reg(Reagent.of("fermented_spider_eye", "Fermented Spider Eye")
            .delta(SLOTH, 8).delta(WRATH, -5).contam(1.2).stab(-3).tier(REFINED)
            .source("craft").build());
    public static final Reagent VERDIGRIS_REST = reg(Reagent.of("verdigris_rest", "Verdigris Rest")
            .delta(SLOTH, 19).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: Oxidized Copper").build());

    // ---- Envy (violet) -------------------------------------------------------------
    public static final Reagent PUFFERFISH = reg(Reagent.of("pufferfish", "Pufferfish")
            .roll(ENVY, 5, 12).contam(3).stab(-4).tier(CRUDE)
            .source("fishing — volatile, grindy").build());
    public static final Reagent CHORUS_FRUIT = reg(Reagent.of("chorus_fruit", "Chorus Fruit")
            .delta(ENVY, 8).delta(GLUTTONY, -4).contam(1).stab(-3).tier(REFINED)
            .source("End").build());
    public static final Reagent VERDANT_SPITE = reg(Reagent.of("verdant_spite", "Verdant Spite")
            .delta(ENVY, 11).contam(0.1).stab(-15).tier(PURE)
            .source("pufferfish chain").build());

    // ---- Gluttony (green — the safe hub) -------------------------------------------
    public static final Reagent SLIME_BALL = reg(Reagent.of("slime_ball", "Slime Ball")
            .delta(GLUTTONY, 6).contam(0.5).stab(1).tier(REFINED)
            .source("slime — safe hub filler").build());
    public static final Reagent ROTTEN_FLESH = reg(Reagent.of("rotten_flesh", "Rotten Flesh")
            .delta(GLUTTONY, 7).delta(SLOTH, 2).contam(3).stab(-1).tier(CRUDE)
            .source("zombies — dirty bulk").build());

    // ---- Utilities: buffers, solvents, panic reset ---------------------------------
    public static final Reagent AMETHYST_SHARD = reg(Reagent.of("amethyst_shard", "Amethyst Shard")
            .stab(18).tier(UTILITY)
            .source("geode — the buffer").build());
    public static final Reagent GLOWSTONE_DUST = reg(Reagent.of("glowstone_dust", "Glowstone Dust")
            .contam(0.5).stab(10).tier(UTILITY)
            .source("nether — lesser buffer").build());
    public static final Reagent MILK_BUCKET = reg(Reagent.of("milk_bucket", "Milk Bucket")
            .chargeScale(0.6).noiseScale(0.7).stab(5).tier(UTILITY)
            .source("cow — panic reset").build());
    public static final Reagent WATER_BOTTLE = reg(Reagent.of("water_bottle", "Water Bottle")
            .chargeScale(0.85).tier(UTILITY).stab(2)
            .source("fine dilution → finer control").build());

    /** Look a reagent up by id, or {@code null}. */
    public static Reagent byId(String id) { return REGISTRY.get(id); }

    /** All reagents in registration order (unmodifiable-ish view over insertion order). */
    public static Iterable<Reagent> all() { return REGISTRY.values(); }

    /** Count of registered reagents. */
    public static int count() { return REGISTRY.size(); }
}
