package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;

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
            .item(Material.REDSTONE).source("mining — the Wrath scalpel").build());
    public static final Reagent BLAZE_POWDER = reg(Reagent.of("blaze_powder", "Blaze Powder")
            .delta(WRATH, 10).delta(GLOOM, -8).contam(1.5).stab(-4).tier(REFINED).inflicts(Taint.FEVER, 0.15).cures(Taint.CHILL)
            .item(Material.BLAZE_POWDER).source("blaze — mover + Gloom corrector; cures Chill").build());
    public static final Reagent BLAZE_ROD = reg(Reagent.of("blaze_rod", "Blaze Rod")
            .roll(WRATH, 12, 40).contam(0.4).stab(-16).tier(PURE).inflicts(Taint.FEVER, 0.35)
            .item(Material.BLAZE_ROD).source("blaze — volatile").build());
    public static final Reagent MAGMA_CREAM = reg(Reagent.of("magma_cream", "Magma Cream")
            .delta(WRATH, 7).delta(GLUTTONY, 2).contam(1.2).stab(-3).tier(REFINED)
            .item(Material.MAGMA_CREAM).source("slimes + blaze").build());
    public static final Reagent REFINED_CINDER = reg(Reagent.of("refined_cinder", "Burning Wrath")
            .delta(WRATH, 22).contam(0.05).stab(-30).tier(STANDARD).inflicts(Taint.FEVER, 0.25)
            .source("processed blaze chain").build());
    public static final Reagent EMBER_DISTILLATE = reg(Reagent.of("ember_distillate", "Smoldering Ire")
            .delta(WRATH, 11).contam(0.1).stab(-15).tier(PURE)
            .source("redstone + blaze chain").build());

    // ---- Gloom (blue) --------------------------------------------------------------
    public static final Reagent INK_SAC = reg(Reagent.of("ink_sac", "Glow Ink Sac")
            .delta(GLOOM, 4).delta(SLOTH, 1).contam(4.5).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.30)
            .item(Material.GLOW_INK_SAC).source("glow squid — weak dirty bulk").build());
    public static final Reagent LAPIS_LAZULI = reg(Reagent.of("lapis_lazuli", "Lapis Lazuli")
            .delta(GLOOM, 3).contam(0.3).stab(-1).tier(REFINED)
            .item(Material.LAPIS_LAZULI).source("mining — the Gloom scalpel").build());
    public static final Reagent SOUL_SOIL = reg(Reagent.of("soul_soil", "Soul Soil")
            .delta(GLOOM, 9).delta(WRATH, -5).contam(1.2).stab(-3).tier(REFINED)
            .item(Material.SOUL_SOIL).source("nether").build());
    public static final Reagent DISTILLED_SORROW = reg(Reagent.of("distilled_sorrow", "Welling Sorrow")
            .delta(GLOOM, 11).contam(0.1).stab(-15).tier(PURE)
            .source("ink + lapis chain").build());
    public static final Reagent KNELL_EXTRACT = reg(Reagent.of("knell_extract", "Drowning Despair")
            .delta(GLOOM, 20).contam(0.05).stab(-30).tier(STANDARD)
            .source("gated: Bells + Oxidized Copper").build());

    // ---- Pride (indigo) ------------------------------------------------------------
    public static final Reagent GOLD_NUGGET = reg(Reagent.of("gold_nugget", "Gold Nugget")
            .delta(PRIDE, 2).contam(0.2).stab(-1).tier(REFINED)
            .item(Material.GOLD_NUGGET).source("craft — the Pride scalpel").build());
    public static final Reagent GOLD_INGOT = reg(Reagent.of("gold_ingot", "Gold Ingot")
            .delta(PRIDE, 8).delta(GLOOM, -4).contam(1).stab(-3).tier(REFINED)
            .item(Material.GOLD_INGOT).source("mining").build());
    public static final Reagent DIAMOND = reg(Reagent.of("diamond", "Diamond")
            .delta(PRIDE, 12).contam(0.1).stab(-14).tier(PURE)
            .item(Material.DIAMOND).source("mining").build());
    public static final Reagent MIRROR_POLISH = reg(Reagent.of("mirror_polish", "Blinding Pride")
            .delta(PRIDE, 20).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: Chiseled Quartz").build());
    public static final Reagent BURNISHED_VANITY = reg(Reagent.of("burnished_vanity", "Preening Vanity")
            .delta(PRIDE, 11).contam(0.1).stab(-15).tier(PURE)
            .source("gold + nugget chain").build());

    // ---- Lust (orange) -------------------------------------------------------------
    public static final Reagent GLOW_BERRIES = reg(Reagent.of("glow_berries", "Glow Berries")
            .delta(LUST, 4).delta(GLUTTONY, 1).contam(4.5).stab(-2).tier(CRUDE)
            .item(Material.GLOW_BERRIES).source("farm — weak dirty bulk").build());
    public static final Reagent HONEY_BOTTLE = reg(Reagent.of("honey_bottle", "Honey Bottle")
            .delta(LUST, 9).delta(ENVY, -4).contam(1).stab(-3).tier(REFINED).cures(Taint.GRIEF_BLOOM)
            .item(Material.HONEY_BOTTLE).source("bees; cures Grief Bloom").build());
    public static final Reagent NECTAR_DRAUGHT = reg(Reagent.of("nectar_draught", "Aching Yearning")
            .delta(LUST, 11).contam(0.1).stab(-15).tier(PURE)
            .source("honey chain").build());
    public static final Reagent AMBER_RAPTURE = reg(Reagent.of("amber_rapture", "Feverish Lust")
            .delta(LUST, 20).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: honey chain + Weathered Copper").build());

    // ---- Sloth (yellow) ------------------------------------------------------------
    public static final Reagent SOUL_SAND = reg(Reagent.of("soul_sand", "Soul Sand")
            .delta(SLOTH, 4).delta(GLOOM, 1).contam(4.5).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.25)
            .item(Material.SOUL_SAND).source("nether — weak dirty bulk").build());
    public static final Reagent FERMENTED_SPIDER_EYE = reg(Reagent.of("fermented_spider_eye", "Fermented Spider Eye")
            .delta(SLOTH, 8).delta(WRATH, -5).contam(1.2).stab(-3).tier(REFINED).inflicts(Taint.TOXIN, 0.25)
            .item(Material.FERMENTED_SPIDER_EYE).source("craft").build());
    public static final Reagent VERDIGRIS_REST = reg(Reagent.of("verdigris_rest", "Leaden Sloth")
            .delta(SLOTH, 19).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: Oxidized Copper").build());
    public static final Reagent LETHE_DRAUGHT = reg(Reagent.of("lethe_draught", "Numbing Apathy")
            .delta(SLOTH, 11).contam(0.1).stab(-15).tier(PURE)
            .source("soul sand + fermented chain").build());

    // ---- Envy (violet) -------------------------------------------------------------
    public static final Reagent PUFFERFISH = reg(Reagent.of("pufferfish", "Pufferfish")
            .roll(ENVY, 3, 7).contam(4.5).stab(-4).tier(CRUDE).inflicts(Taint.TOXIN, 0.40)
            .item(Material.PUFFERFISH).source("fishing — weak, volatile").build());
    public static final Reagent CHORUS_FRUIT = reg(Reagent.of("chorus_fruit", "Pitcher Plant")
            .delta(ENVY, 8).delta(GLUTTONY, -4).contam(1).stab(-3).tier(REFINED)
            .item(Material.PITCHER_PLANT).source("sniffer archaeology — a coveted bloom").build());
    public static final Reagent VERDANT_SPITE = reg(Reagent.of("verdant_spite", "Gnawing Envy")
            .delta(ENVY, 11).contam(0.1).stab(-15).tier(PURE)
            .source("pufferfish chain").build());
    public static final Reagent RANCOROUS_BLOOM = reg(Reagent.of("rancorous_bloom", "Festering Envy")
            .delta(ENVY, 20).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: pitcher chain + Chiseled Quartz").build());

    // ---- Gluttony (green — the safe hub) -------------------------------------------
    public static final Reagent SLIME_BALL = reg(Reagent.of("slime_ball", "Glistering Melon Slice")
            .delta(GLUTTONY, 6).contam(0.5).stab(1).tier(REFINED).cures(Taint.BLEEDING)
            .item(Material.GLISTERING_MELON_SLICE).source("brewing oddment — safe hub filler; cures Bleeding").build());
    public static final Reagent ROTTEN_FLESH = reg(Reagent.of("rotten_flesh", "Rotten Flesh")
            .delta(GLUTTONY, 4).delta(SLOTH, 1).contam(4.5).stab(-1).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.20)
            .item(Material.ROTTEN_FLESH).source("zombies — weak dirty bulk; crush 8 into a Gluttony Concentrate").build());
    // Pure Gluttony — added to complete the hub's tier ladder so Gluttony-dominant WAW weapons (Green Stem)
    // can reach Analytical. NAME/SOURCE PROVISIONAL — retune to taste.
    public static final Reagent RAVENING_DRAUGHT = reg(Reagent.of("ravening_draught", "Hollow Hunger")
            .delta(GLUTTONY, 11).contam(0.1).stab(-14).tier(PURE)
            .source("slime + honey chain").build());
    public static final Reagent GLUTTONS_FEAST = reg(Reagent.of("gluttons_feast", "Ravening Gluttony")
            .delta(GLUTTONY, 20).contam(0.05).stab(-28).tier(STANDARD)
            .source("gated: melon chain + Weathered Copper").build());

    // ---- Utilities: buffers, solvents, panic reset ---------------------------------
    public static final Reagent AMETHYST_SHARD = reg(Reagent.of("amethyst_shard", "Amethyst Shard")
            .stab(18).tier(UTILITY).cures(Taint.FRACTURE)
            .item(Material.AMETHYST_SHARD).source("geode — the buffer; cures Fracture").build());
    public static final Reagent GLOWSTONE_DUST = reg(Reagent.of("glowstone_dust", "Glowstone Dust")
            .contam(0.5).stab(10).tier(UTILITY)
            .item(Material.GLOWSTONE_DUST).source("nether — cheap, dirty buffer").build());
    // Flux: mediates the warring sins of a cross-axis pot (dampens opposition drain for a few adds). The
    // right steadier for a Dissonant/opposed batch — useless on plain handling shakes.
    public static final Reagent HONEYCOMB = reg(Reagent.of("honeycomb", "Honeycomb")
            .stab(3).flux(4).tier(UTILITY).cures(Taint.DISSONANCE)
            .item(Material.HONEYCOMB).source("bees — opposition flux; cures Dissonance").build());

    // ---- Remedies (the medicine cabinet — no charge, they treat afflictions) -------
    public static final Reagent NETHER_WART = reg(Reagent.of("nether_wart", "Pufferfish Bucket")
            .stab(2).tier(UTILITY).cures(Taint.TOXIN)
            .item(Material.PUFFERFISH_BUCKET).source("bottled antidote — cures Toxin").build());
    public static final Reagent BONE_MEAL = reg(Reagent.of("bone_meal", "Bone Meal")
            .noiseScale(0.9).tier(UTILITY).cures(Taint.SEDIMENT)
            .item(Material.BONE_MEAL).source("precipitate + filter — cures Sediment, recovers a little purity").build());
    public static final Reagent SNOWBALL = reg(Reagent.of("snowball", "Snowball")
            .stab(1).tier(UTILITY).cures(Taint.FEVER)
            .item(Material.SNOWBALL).source("quench — cures Fever").build());
    public static final Reagent MILK_BUCKET = reg(Reagent.of("milk_bucket", "Milk Bucket")
            .chargeScale(0.6).noiseScale(0.7).stab(5).tier(UTILITY)
            .item(Material.MILK_BUCKET).source("cow — panic reset").build());
    public static final Reagent WATER_BOTTLE = reg(Reagent.of("water_bottle", "Water Bottle")
            .chargeScale(0.85).tier(UTILITY).stab(2)
            .source("fine dilution → finer control").build());

    // ---- Experiences & Emotions (volatile memories) --------------------------------
    // Raw memory-charge: potent sin payloads on a dirty, volatile carrier (Crude) that gambles a taint.
    public static final Reagent BOTTLE_OF_ENCHANTING = reg(Reagent.of("bottle_of_enchanting", "Polished Granite Stairs")
            .delta(PRIDE, 14).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.FEVER, 0.25)
            .item(Material.POLISHED_GRANITE_STAIRS).source("a moment of triumph").build());
    public static final Reagent WRITTEN_BOOK = reg(Reagent.of("written_book", "Written Book")
            .delta(GLOOM, 10).delta(ENVY, 6).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.GRIEF_BLOOM, 0.30)
            .item(Material.WRITTEN_BOOK).source("a confession").build());
    public static final Reagent MUSIC_DISC = reg(Reagent.of("music_disc", "Music Disc")
            .delta(GLOOM, 10).delta(LUST, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.GRIEF_BLOOM, 0.25)
            .source("a nostalgic ache").build());
    public static final Reagent WITHER_ROSE = reg(Reagent.of("wither_rose", "Wither Rose")
            .delta(GLOOM, 12).delta(WRATH, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.TOXIN, 0.40)
            .item(Material.WITHER_ROSE).source("a memory of loss").build());
    public static final Reagent GOLDEN_APPLE = reg(Reagent.of("golden_apple", "Golden Apple")
            .delta(PRIDE, 12).delta(WRATH, 8).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.FEVER, 0.35)
            .item(Material.GOLDEN_APPLE).source("a heroic act").build());
    public static final Reagent POPPY = reg(Reagent.of("poppy", "Dandelion")
            .delta(LUST, 9).contam(2).stab(-2).tier(CRUDE)
            .item(Material.DANDELION).source("a tender memory").build());
    public static final Reagent SPIDER_EYE = reg(Reagent.of("spider_eye", "Spider Eye")
            .delta(ENVY, 10).delta(WRATH, 6).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.TOXIN, 0.35)
            .item(Material.SPIDER_EYE).source("a betrayal").build());
    public static final Reagent PHANTOM_MEMBRANE = reg(Reagent.of("phantom_membrane", "Breeze Rod")
            .delta(SLOTH, 9).delta(GLOOM, 7).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.CHILL, 0.30)
            .item(Material.BREEZE_ROD).source("restless wind — sleepless regret").build());
    public static final Reagent CAKE = reg(Reagent.of("cake", "Cake")
            .delta(GLUTTONY, 12).delta(LUST, 8).contam(2).stab(-2).tier(CRUDE)
            .item(Material.CAKE).source("a celebration").build());
    public static final Reagent ROTTEN_MEMORY = reg(Reagent.of("rotten_memory", "Rotten Flesh (Decayed Memory)")
            .delta(GLUTTONY, 11).contam(2).stab(-2).tier(CRUDE).inflicts(Taint.SEDIMENT, 0.30)
            .source("a decayed memory").build());

    /** Look a reagent up by id, or {@code null}. */
    public static Reagent byId(String id) { return REGISTRY.get(id); }

    /** The reagent whose physical item is this material, or {@code null}. */
    public static Reagent byItem(Material m) {
        if (m == null) return null;
        for (Reagent r : REGISTRY.values()) if (r.item() == m) return r;
        return null;
    }

    /**
     * The reagent an item represents — a crafted {@link RefinedReagent} (by tag) first, else a plain vanilla
     * reagent item (by material). This is what the Censer and the lectern resolve a held item through.
     */
    public static Reagent fromItem(org.bukkit.inventory.ItemStack item) {
        if (item == null) return null;
        String refined = RefinedReagent.idOf(item);
        if (refined != null) return byId(refined);
        return byItem(item.getType());
    }

    /** All reagents in registration order (unmodifiable-ish view over insertion order). */
    public static Iterable<Reagent> all() { return REGISTRY.values(); }

    /** Count of registered reagents. */
    public static int count() { return REGISTRY.size(); }
}
