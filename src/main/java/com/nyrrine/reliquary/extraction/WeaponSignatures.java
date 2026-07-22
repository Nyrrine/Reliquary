package com.nyrrine.reliquary.extraction;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.nyrrine.reliquary.extraction.EgoGrade.ALEPH;
import static com.nyrrine.reliquary.extraction.EgoGrade.HE;
import static com.nyrrine.reliquary.extraction.EgoGrade.TETH;
import static com.nyrrine.reliquary.extraction.EgoGrade.WAW;
import static com.nyrrine.reliquary.extraction.EgoGrade.ZAYIN;

/**
 * The weapon roster the Pocket Well pulls from, as (id, display, grade) entries. Ids match the E.G.O weapon
 * model ids so an extraction hands back the real item. A ticket pools by grade; the Well picks a random
 * weapon whose grade is in the ticket's pools. Adding a weapon is one line here.
 */
public final class WeaponSignatures {

    private WeaponSignatures() {}

    private static final Map<String, WeaponSpec> REGISTRY = new LinkedHashMap<>();

    private static WeaponSpec reg(String id, String display, EgoGrade grade) {
        WeaponSpec spec = new WeaponSpec(id, display, grade);
        REGISTRY.put(id, spec);
        return spec;
    }

    // ---- ZAYIN ----
    public static final WeaponSpec PENITENCE = reg("penitence", "Penitence", ZAYIN);
    public static final WeaponSpec SODA = reg("soda", "Soda", ZAYIN);

    // ---- TETH ----
    public static final WeaponSpec FOURTH_MATCH_FLAME = reg("fourth_match_flame", "Fourth Match Flame", TETH);
    public static final WeaponSpec RED_EYES = reg("red_eyes", "Red Eyes", TETH);
    public static final WeaponSpec REGRET = reg("regret", "Regret", TETH);
    public static final WeaponSpec BEAK = reg("beak", "Beak", TETH);
    public static final WeaponSpec LOGGING = reg("logging", "Logging", TETH);
    public static final WeaponSpec WRIST_CUTTER = reg("wrist_cutter", "Wrist Cutter", TETH);
    public static final WeaponSpec CHRISTMAS = reg("christmas", "Christmas", TETH);
    public static final WeaponSpec SOLITUDE = reg("solitude", "Solitude", TETH);
    public static final WeaponSpec FRAGMENTS_FROM_SOMEWHERE = reg("fragments_from_somewhere", "Fragments from Somewhere", TETH);
    public static final WeaponSpec LANTERN = reg("lantern", "Lantern", TETH);

    // ---- HE ----
    public static final WeaponSpec GRINDER_MK4 = reg("grinder_mk4", "Grinder Mk4", HE);
    public static final WeaponSpec CRIMSON_SCAR = reg("crimson_scar", "Crimson Scar", HE);
    public static final WeaponSpec COBALT_SCAR = reg("cobalt_scar", "Cobalt Scar", HE);
    public static final WeaponSpec OUR_GALAXY = reg("our_galaxy", "Our Galaxy", HE);
    public static final WeaponSpec HARVEST = reg("harvest", "Harvest", HE);
    public static final WeaponSpec LIFE_FOR_A_DAREDEVIL = reg("life_for_a_daredevil", "Life for a Daredevil", HE);
    public static final WeaponSpec LAETITIA = reg("laetitia", "Laetitia", HE);
    public static final WeaponSpec FROST_SPLINTER = reg("frost_splinter", "Frost Splinter", HE);
    public static final WeaponSpec GREEN_STEM = reg("green_stem", "Green Stem", HE);
    public static final WeaponSpec SCREAMING_WEDGE = reg("screaming_wedge", "Screaming Wedge", HE);

    // ---- WAW ----
    public static final WeaponSpec LAMP = reg("lamp", "Lamp", WAW);
    public static final WeaponSpec MAGIC_BULLET = reg("magic_bullet", "Magic Bullet", WAW);
    public static final WeaponSpec SOLEMN_LAMENT = reg("solemn_lament", "Solemn Lament", WAW);
    public static final WeaponSpec LOVE_AND_HATE = reg("love_and_hate", "Love and Hate", WAW);
    public static final WeaponSpec SWORD_OF_TEARS = reg("sword_of_tears", "Sword of Sharpened Tears", WAW);
    public static final WeaponSpec HEAVEN = reg("heaven", "Heaven", WAW);
    public static final WeaponSpec HARMONY = reg("harmony", "Harmony", WAW);
    public static final WeaponSpec GAZE = reg("gaze", "Gaze", WAW);
    public static final WeaponSpec HORNET = reg("hornet", "Hornet", WAW);
    public static final WeaponSpec FAINT_AROMA = reg("faint_aroma", "Faint Aroma", WAW);
    public static final WeaponSpec DISCORD = reg("discord", "Discord", WAW);

    // ---- ALEPH ----
    public static final WeaponSpec JUSTITIA = reg("justitia", "Justitia", ALEPH);
    public static final WeaponSpec MIMICRY = reg("mimicry", "Mimicry", ALEPH);
    public static final WeaponSpec CENSORED = reg("censored", "CENSORED", ALEPH);

    // The bus E.G.O (Flower Burying Wedge) is NOT here — it's earned through its own mechanic, not the Well.
    // Twilight is NOT here either — it's the SPECIAL, Cogito-unobtainable flagship.

    /** Look a weapon up by id, or {@code null}. */
    public static WeaponSpec byId(String id) { return REGISTRY.get(id); }

    /** All weapons in roster order. */
    public static Iterable<WeaponSpec> all() { return REGISTRY.values(); }

    /** Count of registered weapons. */
    public static int count() { return REGISTRY.size(); }
}
