package com.nyrrine.reliquary.extraction;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.nyrrine.reliquary.extraction.EgoGrade.HE;
import static com.nyrrine.reliquary.extraction.EgoGrade.TETH;
import static com.nyrrine.reliquary.extraction.EgoGrade.WAW;
import static com.nyrrine.reliquary.extraction.EgoGrade.ZAYIN;
import static com.nyrrine.reliquary.extraction.Sin.ENVY;
import static com.nyrrine.reliquary.extraction.Sin.GLOOM;
import static com.nyrrine.reliquary.extraction.Sin.GLUTTONY;
import static com.nyrrine.reliquary.extraction.Sin.LUST;
import static com.nyrrine.reliquary.extraction.Sin.PRIDE;
import static com.nyrrine.reliquary.extraction.Sin.SLOTH;
import static com.nyrrine.reliquary.extraction.Sin.WRATH;

/**
 * The weapon roster as 7-sin coordinates — what the Pocket Well rolls against (§27). Ids match the E.G.O
 * weapon model ids so a manifest can hand back the real item. Signatures are the emotional <i>contradiction</i>
 * of each abnormality; single-cluster weapons sit calm, cross-axis ones straddle an opposition bridge and
 * are the hard extractions.
 *
 * <p>These are deliberately <b>not</b> shown to players (§25 — the community datamines/publishes). The
 * lectern only ever reveals what a <i>reagent</i> would do, never a weapon's exact target.
 */
public final class WeaponSignatures {

    private WeaponSignatures() {}

    private static final Map<String, WeaponSpec> REGISTRY = new LinkedHashMap<>();

    private static WeaponSpec reg(String id, String display, EgoGrade grade, SinProfile signature) {
        WeaponSpec spec = new WeaponSpec(id, display, grade, signature);
        REGISTRY.put(id, spec);
        return spec;
    }

    // ---- ZAYIN ----
    public static final WeaponSpec PENITENCE = reg("penitence", "Penitence", ZAYIN,
            SinProfile.builder().add(GLOOM, 60).add(SLOTH, 40).build());
    public static final WeaponSpec SODA = reg("soda", "Soda", ZAYIN,
            SinProfile.builder().add(LUST, 50).add(GLUTTONY, 50).build());

    // ---- TETH ----
    public static final WeaponSpec FOURTH_MATCH_FLAME = reg("fourth_match_flame", "Fourth Match Flame", TETH,
            SinProfile.builder().add(WRATH, 70).add(LUST, 30).build());
    public static final WeaponSpec RED_EYES = reg("red_eyes", "Red Eyes", TETH,
            SinProfile.builder().add(WRATH, 60).add(PRIDE, 20).add(GLUTTONY, 20).build());
    public static final WeaponSpec REGRET = reg("regret", "Regret", TETH,
            SinProfile.builder().add(WRATH, 70).add(GLOOM, 30).build());
    public static final WeaponSpec BEAK = reg("beak", "Beak", TETH,
            SinProfile.builder().add(WRATH, 60).add(PRIDE, 30).add(GLUTTONY, 10).build());
    public static final WeaponSpec LOGGING = reg("logging", "Logging", TETH,
            SinProfile.builder().add(GLUTTONY, 50).add(SLOTH, 30).add(WRATH, 20).build());
    public static final WeaponSpec WRIST_CUTTER = reg("wrist_cutter", "Wrist Cutter", TETH,
            SinProfile.builder().add(GLOOM, 70).add(SLOTH, 30).build());
    public static final WeaponSpec CHRISTMAS = reg("christmas", "Christmas", TETH,
            SinProfile.builder().add(GLUTTONY, 60).add(LUST, 40).build());

    // ---- HE ----
    public static final WeaponSpec GRINDER_MK4 = reg("grinder_mk4", "Grinder Mk4", HE,
            SinProfile.builder().add(GLUTTONY, 60).add(WRATH, 40).build());
    public static final WeaponSpec CRIMSON_SCAR = reg("crimson_scar", "Crimson Scar", HE,
            SinProfile.builder().add(WRATH, 70).add(PRIDE, 30).build());
    public static final WeaponSpec COBALT_SCAR = reg("cobalt_scar", "Cobalt Scar", HE,
            SinProfile.builder().add(ENVY, 50).add(WRATH, 30).add(PRIDE, 20).build());
    public static final WeaponSpec OUR_GALAXY = reg("our_galaxy", "Our Galaxy", HE,
            SinProfile.builder().add(ENVY, 60).add(GLOOM, 40).build());
    public static final WeaponSpec HARVEST = reg("harvest", "Harvest", HE,
            SinProfile.builder().add(SLOTH, 40).add(ENVY, 40).add(GLOOM, 20).build());
    public static final WeaponSpec LIFE_FOR_A_DAREDEVIL = reg("life_for_a_daredevil", "Life for a Daredevil", HE,
            SinProfile.builder().add(LUST, 50).add(PRIDE, 30).add(WRATH, 20).build());
    public static final WeaponSpec LAETITIA = reg("laetitia", "Laetitia", HE,
            SinProfile.builder().add(LUST, 60).add(GLUTTONY, 40).build());

    // ---- WAW ----
    public static final WeaponSpec LAMP = reg("lamp", "Lamp", WAW,
            SinProfile.builder().add(PRIDE, 40).add(GLOOM, 40).add(SLOTH, 20).build());
    public static final WeaponSpec MAGIC_BULLET = reg("magic_bullet", "Magic Bullet", WAW,
            SinProfile.builder().add(PRIDE, 60).add(WRATH, 40).build());
    public static final WeaponSpec SOLEMN_LAMENT = reg("solemn_lament", "Solemn Lament", WAW,
            SinProfile.builder().add(GLOOM, 70).add(SLOTH, 20).add(ENVY, 10).build());
    public static final WeaponSpec LOVE_AND_HATE = reg("love_and_hate", "Love and Hate", WAW,
            SinProfile.builder().add(LUST, 50).add(ENVY, 50).build());
    public static final WeaponSpec SWORD_OF_TEARS = reg("sword_of_tears", "Sword of Sharpened Tears", WAW,
            SinProfile.builder().add(GLOOM, 50).add(PRIDE, 30).add(ENVY, 20).build());
    public static final WeaponSpec GREEN_STEM = reg("green_stem", "Green Stem", WAW,
            SinProfile.builder().add(GLUTTONY, 40).add(ENVY, 40).add(LUST, 20).build());
    public static final WeaponSpec SCREAMING_WEDGE = reg("screaming_wedge", "Screaming Wedge", WAW,
            SinProfile.builder().add(GLOOM, 50).add(WRATH, 30).add(SLOTH, 20).build());
    public static final WeaponSpec HEAVEN = reg("heaven", "Heaven", WAW,
            SinProfile.builder().add(PRIDE, 80).add(WRATH, 20).build());

    // ---- Bus E.G.O (grade assigned HE pending final call) ----
    public static final WeaponSpec FLOWER_BURYING_WEDGE = reg("flower_burying_wedge", "Flower Burying Wedge", HE,
            SinProfile.builder().add(GLOOM, 50).add(SLOTH, 30).add(GLUTTONY, 20).build());

    /** Look a weapon up by id, or {@code null}. */
    public static WeaponSpec byId(String id) { return REGISTRY.get(id); }

    /** All weapons in roster order. */
    public static Iterable<WeaponSpec> all() { return REGISTRY.values(); }

    /** Count of registered weapons. */
    public static int count() { return REGISTRY.size(); }
}
