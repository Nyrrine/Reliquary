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
            .delta(WRATH, 10).delta(GLOOM, -8).contam(1.5).stab(-4).tier(REFINED).inflicts(Taint.FEVER, 0.15).cures(Taint.CHILL)
            .source("blaze — mover + Gloom corrector; cures Chill").build());
    public static final Reagent BLAZE_ROD = reg(Reagent.of("blaze_rod", "Blaze Rod")
            .roll(WRATH, 12, 40).contam(0.4).stab(-16).tier(PURE).inflicts(Taint.FEVER, 0.35)
            .source("blaze — volatile").build());
    public static final Reagent MAGMA_CREAM = reg(Reagent.of("magma_cream", "Magma Cream")
            .delta(WRATH, 7).delta(GLUTTONY, 2).contam(1.2).stab(-3).tier(REFINED)
            .source("slimes + blaze").build());
    public static final Reagent REFINED_CINDER = reg(Reagent.of("refined_cinder", "Refined Cinder")
            .delta(WRATH, 22).contam(0.05).stab(-30).tier(STANDARD).inflicts(Taint.FEVER, 0.25)
            .source("processed blaze chain").build());

    // ---- Gloom (blue) --------------------------------------------------------------
    public static final Reagent INK_SAC = reg(Reagent.of("ink_sac", "Ink Sac")
            .delta(GLOOM, 8).delta(SLOTH, 2).contam(3).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.30)
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
            .delta(LUST, 9).delta(ENVY, -4).contam(1).stab(-3).tier(REFINED).cures(Taint.GRIEF_BLOOM)
            .source("bees; cures Grief Bloom").build());
    public static final Reagent NECTAR_DRAUGHT = reg(Reagent.of("nectar_draught", "Nectar Draught")
            .delta(LUST, 11).contam(0.1).stab(-15).tier(PURE)
            .source("honey chain").build());

    // ---- Sloth (yellow) ------------------------------------------------------------
    public static final Reagent SOUL_SAND = reg(Reagent.of("soul_sand", "Soul Sand")
            .delta(SLOTH, 7).delta(GLOOM, 2).contam(3).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.25)
            .source("nether — dirty bulk").build());
    public static final Reagent FERMENTED_SPIDER_EYE = reg(Reagent.of("fermented_spider_eye", "Fermented Spider Eye")
            .delta(SLOTH, 8).delta(WRATH, -5).contam(1.2).stab(-3).tier(REFINED).inflicts(Taint.TOXIN, 0.25)
            .source("craft").build());
    public static final Reagent VERDIGRIS_REST = reg(Reagent.of("verdigris_rest", "Verdigris Rest")
            .delta(SLOTH, 19).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: Oxidized Copper").build());

    // ---- Envy (violet) -------------------------------------------------------------
    public static final Reagent PUFFERFISH = reg(Reagent.of("pufferfish", "Pufferfish")
            .roll(ENVY, 5, 12).contam(3).stab(-4).tier(CRUDE).inflicts(Taint.TOXIN, 0.40)
            .source("fishing — volatile, grindy").build());
    public static final Reagent CHORUS_FRUIT = reg(Reagent.of("chorus_fruit", "Chorus Fruit")
            .delta(ENVY, 8).delta(GLUTTONY, -4).contam(1).stab(-3).tier(REFINED)
            .source("End").build());
    public static final Reagent VERDANT_SPITE = reg(Reagent.of("verdant_spite", "Verdant Spite")
            .delta(ENVY, 11).contam(0.1).stab(-15).tier(PURE)
            .source("pufferfish chain").build());

    // ---- Gluttony (green — the safe hub) -------------------------------------------
    public static final Reagent SLIME_BALL = reg(Reagent.of("slime_ball", "Slime Ball")
            .delta(GLUTTONY, 6).contam(0.5).stab(1).tier(REFINED).cures(Taint.BLEEDING)
            .source("slime — safe hub filler; cures Bleeding").build());
    public static final Reagent ROTTEN_FLESH = reg(Reagent.of("rotten_flesh", "Rotten Flesh")
            .delta(GLUTTONY, 7).delta(SLOTH, 2).contam(3).stab(-1).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.20)
            .source("zombies — dirty bulk").build());
    // Pure Gluttony — added to complete the hub's tier ladder so Gluttony-dominant WAW weapons (Green Stem)
    // can reach Analytical. NAME/SOURCE PROVISIONAL — retune to taste.
    public static final Reagent RAVENING_DRAUGHT = reg(Reagent.of("ravening_draught", "Ravening Draught")
            .delta(GLUTTONY, 11).contam(0.1).stab(-14).tier(PURE)
            .source("slime + honey chain").build());

    // ---- Utilities: buffers, solvents, panic reset ---------------------------------
    public static final Reagent AMETHYST_SHARD = reg(Reagent.of("amethyst_shard", "Amethyst Shard")
            .stab(18).tier(UTILITY).cures(Taint.FRACTURE)
            .source("geode — the buffer; cures Fracture").build());
    public static final Reagent GLOWSTONE_DUST = reg(Reagent.of("glowstone_dust", "Glowstone Dust")
            .contam(0.5).stab(10).tier(UTILITY)
            .source("nether — cheap, dirty buffer").build());
    // Flux: mediates the warring sins of a cross-axis pot (dampens opposition drain for a few adds). The
    // right steadier for a Dissonant/opposed batch — useless on plain handling shakes.
    public static final Reagent HONEYCOMB = reg(Reagent.of("honeycomb", "Honeycomb")
            .stab(3).flux(4).tier(UTILITY).cures(Taint.DISSONANCE)
            .source("bees — opposition flux; cures Dissonance").build());

    // ---- Remedies (the medicine cabinet — no charge, they treat afflictions) -------
    public static final Reagent NETHER_WART = reg(Reagent.of("nether_wart", "Nether Wart")
            .stab(2).tier(UTILITY).cures(Taint.TOXIN)
            .source("antidote — cures Toxin").build());
    public static final Reagent BONE_MEAL = reg(Reagent.of("bone_meal", "Bone Meal")
            .noiseScale(0.9).tier(UTILITY).cures(Taint.SEDIMENT)
            .source("precipitate + filter — cures Sediment, recovers a little purity").build());
    public static final Reagent SNOWBALL = reg(Reagent.of("snowball", "Snowball")
            .stab(1).tier(UTILITY).cures(Taint.FEVER)
            .source("quench — cures Fever").build());
    public static final Reagent MILK_BUCKET = reg(Reagent.of("milk_bucket", "Milk Bucket")
            .chargeScale(0.6).noiseScale(0.7).stab(5).tier(UTILITY)
            .source("cow — panic reset").build());
    public static final Reagent WATER_BOTTLE = reg(Reagent.of("water_bottle", "Water Bottle")
            .chargeScale(0.85).tier(UTILITY).stab(2)
            .source("fine dilution → finer control").build());

    // ---- Experiences & Emotions (volatile memories) --------------------------------
    // Raw memory-charge: potent sin payloads on a dirty, volatile carrier (Crude) that gambles a taint.
    public static final Reagent BOTTLE_OF_ENCHANTING = reg(Reagent.of("bottle_of_enchanting", "Bottle o' Enchanting")
            .delta(PRIDE, 14).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.FEVER, 0.25)
            .source("a moment of triumph").build());
    public static final Reagent WRITTEN_BOOK = reg(Reagent.of("written_book", "Written Book")
            .delta(GLOOM, 10).delta(ENVY, 6).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.GRIEF_BLOOM, 0.30)
            .source("a confession").build());
    public static final Reagent MUSIC_DISC = reg(Reagent.of("music_disc", "Music Disc")
            .delta(GLOOM, 10).delta(LUST, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.GRIEF_BLOOM, 0.25)
            .source("a nostalgic ache").build());
    public static final Reagent WITHER_ROSE = reg(Reagent.of("wither_rose", "Wither Rose")
            .delta(GLOOM, 12).delta(WRATH, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.TOXIN, 0.40)
            .source("a memory of loss").build());
    public static final Reagent GOLDEN_APPLE = reg(Reagent.of("golden_apple", "Golden Apple")
            .delta(PRIDE, 12).delta(WRATH, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.FEVER, 0.35)
            .source("a heroic act").build());
    public static final Reagent POPPY = reg(Reagent.of("poppy", "Poppy")
            .delta(LUST, 9).contam(2).stab(-2).tier(CRUDE)
            .source("a tender memory").build());
    public static final Reagent SPIDER_EYE = reg(Reagent.of("spider_eye", "Spider Eye")
            .delta(ENVY, 10).delta(WRATH, 6).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.TOXIN, 0.35)
            .source("a betrayal").build());
    public static final Reagent PHANTOM_MEMBRANE = reg(Reagent.of("phantom_membrane", "Phantom Membrane")
            .delta(SLOTH, 9).delta(GLOOM, 7).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.CHILL, 0.30)
            .source("sleepless regret").build());
    public static final Reagent CAKE = reg(Reagent.of("cake", "Cake")
            .delta(GLUTTONY, 12).delta(LUST, 8).contam(2).stab(-2).tier(CRUDE)
            .source("a celebration").build());
    public static final Reagent ROTTEN_MEMORY = reg(Reagent.of("rotten_memory", "Rotten Flesh (Decayed Memory)")
            .delta(GLUTTONY, 11).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.30)
            .source("a decayed memory").build());

    /** Look a reagent up by id, or {@code null}. */
    public static Reagent byId(String id) { return REGISTRY.get(id); }

    /** All reagents in registration order (unmodifiable-ish view over insertion order). */
    public static Iterable<Reagent> all() { return REGISTRY.values(); }

    /** Count of registered reagents. */
    public static int count() { return REGISTRY.size(); }
}
