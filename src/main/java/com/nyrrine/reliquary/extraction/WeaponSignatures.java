package com.nyrrine.reliquary.extraction;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.nyrrine.reliquary.extraction.EgoGrade.ALEPH;
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
 * weapon model ids so an extraction can hand back the real item. Signatures are the emotional <i>contradiction</i>
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
            SinProfile.builder().add(GLOOM, 54).add(SLOTH, 28).add(PRIDE, 10).add(ENVY, 8).build());
    public static final WeaponSpec SODA = reg("soda", "Soda", ZAYIN,
            SinProfile.builder().add(LUST, 42).add(GLUTTONY, 40).add(SLOTH, 10).add(PRIDE, 8).build());

    // ---- TETH ----
    public static final WeaponSpec FOURTH_MATCH_FLAME = reg("fourth_match_flame", "Fourth Match Flame", TETH,
            SinProfile.builder().add(WRATH, 60).add(LUST, 22).add(GLUTTONY, 10).add(PRIDE, 8).build());
    public static final WeaponSpec RED_EYES = reg("red_eyes", "Red Eyes", TETH,
            SinProfile.builder().add(WRATH, 58).add(PRIDE, 18).add(GLUTTONY, 16).add(ENVY, 8).build());
    public static final WeaponSpec REGRET = reg("regret", "Regret", TETH,
            SinProfile.builder().add(WRATH, 58).add(GLOOM, 26).add(SLOTH, 8).add(ENVY, 8).build());
    public static final WeaponSpec BEAK = reg("beak", "Beak", TETH,
            SinProfile.builder().add(WRATH, 56).add(PRIDE, 26).add(GLUTTONY, 12).add(SLOTH, 6).build());
    public static final WeaponSpec LOGGING = reg("logging", "Logging", TETH,
            SinProfile.builder().add(GLUTTONY, 50).add(SLOTH, 26).add(WRATH, 16).add(PRIDE, 8).build());
    public static final WeaponSpec WRIST_CUTTER = reg("wrist_cutter", "Wrist Cutter", TETH,
            SinProfile.builder().add(GLOOM, 60).add(SLOTH, 22).add(WRATH, 10).add(ENVY, 8).build());
    public static final WeaponSpec CHRISTMAS = reg("christmas", "Christmas", TETH,
            SinProfile.builder().add(GLUTTONY, 52).add(LUST, 30).add(SLOTH, 10).add(PRIDE, 8).build());
    // A void that cannot be filled: gloom that waits (sloth) and covets company (envy). Cold-pure, no bridge.
    public static final WeaponSpec SOLITUDE = reg("solitude", "Solitude", TETH,
            SinProfile.builder().add(GLOOM, 56).add(SLOTH, 24).add(ENVY, 12).add(GLUTTONY, 8).build());
    // Drift, not despair — sloth leads the wielder into the long realm of mind; gloom only colours it.
    public static final WeaponSpec FRAGMENTS_FROM_SOMEWHERE = reg("fragments_from_somewhere", "Fragments from Somewhere", TETH,
            SinProfile.builder().add(SLOTH, 46).add(GLOOM, 30).add(ENVY, 16).add(GLUTTONY, 8).build());
    // The lure that eats: gluttony is the mouth, lust is the light it hangs out to be wanted by.
    public static final WeaponSpec LANTERN = reg("lantern", "Lantern", TETH,
            SinProfile.builder().add(GLUTTONY, 58).add(LUST, 22).add(WRATH, 12).add(PRIDE, 8).build());

    // ---- HE ----
    public static final WeaponSpec GRINDER_MK4 = reg("grinder_mk4", "Grinder Mk4", HE,
            SinProfile.builder().add(GLUTTONY, 52).add(WRATH, 30).add(PRIDE, 10).add(SLOTH, 8).build());
    public static final WeaponSpec CRIMSON_SCAR = reg("crimson_scar", "Crimson Scar", HE,
            SinProfile.builder().add(WRATH, 56).add(PRIDE, 30).add(LUST, 8).add(GLUTTONY, 6).build());
    public static final WeaponSpec COBALT_SCAR = reg("cobalt_scar", "Cobalt Scar", HE,
            SinProfile.builder().add(ENVY, 48).add(WRATH, 26).add(PRIDE, 18).add(GLOOM, 8).build());
    public static final WeaponSpec OUR_GALAXY = reg("our_galaxy", "Our Galaxy", HE,
            SinProfile.builder().add(ENVY, 54).add(GLOOM, 30).add(SLOTH, 8).add(LUST, 8).build());
    public static final WeaponSpec HARVEST = reg("harvest", "Harvest", HE,
            SinProfile.builder().add(SLOTH, 38).add(ENVY, 34).add(GLOOM, 18).add(PRIDE, 10).build());
    public static final WeaponSpec LIFE_FOR_A_DAREDEVIL = reg("life_for_a_daredevil", "Life for a Daredevil", HE,
            SinProfile.builder().add(LUST, 48).add(PRIDE, 26).add(WRATH, 18).add(GLUTTONY, 8).build());
    public static final WeaponSpec LAETITIA = reg("laetitia", "Laetitia", HE,
            SinProfile.builder().add(LUST, 52).add(GLUTTONY, 30).add(PRIDE, 10).add(GLOOM, 8).build());
    // Beautiful, but where her heart should have been it was empty and frozen — cold-dominant, with just
    // enough pride against the gloom to graze the Pride✕Gloom bridge. Half a step past a calm extraction.
    public static final WeaponSpec FROST_SPLINTER = reg("frost_splinter", "Frost Splinter", HE,
            SinProfile.builder().add(GLOOM, 48).add(ENVY, 24).add(SLOTH, 16).add(PRIDE, 12).build());
    public static final WeaponSpec GREEN_STEM = reg("green_stem", "Green Stem", HE,
            SinProfile.builder().add(GLUTTONY, 38).add(ENVY, 34).add(LUST, 18).add(SLOTH, 10).build());
    public static final WeaponSpec SCREAMING_WEDGE = reg("screaming_wedge", "Screaming Wedge", HE,
            SinProfile.builder().add(GLOOM, 48).add(WRATH, 26).add(SLOTH, 18).add(ENVY, 8).build());

    // ---- WAW ----
    public static final WeaponSpec LAMP = reg("lamp", "Lamp", WAW,
            SinProfile.builder().add(PRIDE, 32).add(GLOOM, 30).add(SLOTH, 16).add(ENVY, 12).add(WRATH, 10).build());
    public static final WeaponSpec MAGIC_BULLET = reg("magic_bullet", "Magic Bullet", WAW,
            SinProfile.builder().add(PRIDE, 50).add(WRATH, 26).add(GLUTTONY, 10).add(LUST, 8).add(ENVY, 6).build());
    public static final WeaponSpec SOLEMN_LAMENT = reg("solemn_lament", "Solemn Lament", WAW,
            SinProfile.builder().add(GLOOM, 58).add(SLOTH, 16).add(ENVY, 12).add(PRIDE, 8).add(WRATH, 6).build());
    public static final WeaponSpec LOVE_AND_HATE = reg("love_and_hate", "Love and Hate", WAW,
            SinProfile.builder().add(LUST, 36).add(ENVY, 34).add(WRATH, 12).add(GLOOM, 10).add(PRIDE, 8).build());
    public static final WeaponSpec SWORD_OF_TEARS = reg("sword_of_tears", "Sword of Sharpened Tears", WAW,
            SinProfile.builder().add(GLOOM, 42).add(PRIDE, 24).add(ENVY, 16).add(SLOTH, 10).add(WRATH, 8).build());
    public static final WeaponSpec HEAVEN = reg("heaven", "Heaven", WAW,
            SinProfile.builder().add(PRIDE, 62).add(WRATH, 16).add(GLUTTONY, 8).add(LUST, 8).add(SLOTH, 6).build());
    // Art is a devil's gift, born from despair and suffering: the artist's pride set against the despair that
    // feeds it. Pride✕Gloom, near-balanced — the bridge is the whole weapon.
    public static final WeaponSpec HARMONY = reg("harmony", "Harmony", WAW,
            SinProfile.builder().add(PRIDE, 34).add(GLOOM, 32).add(LUST, 16).add(SLOTH, 10).add(WRATH, 8).build());
    // Delight at another's misfortune, through a keyhole. Lust✕Envy nearly even — the wanting and the watching.
    public static final WeaponSpec GAZE = reg("gaze", "Gaze", WAW,
            SinProfile.builder().add(ENVY, 36).add(LUST, 30).add(PRIDE, 18).add(GLOOM, 10).add(WRATH, 6).build());
    // Loyalty as pheromone — lust is literal here, not metaphor. The hive covets, the queen commands. Lust✕Envy.
    public static final WeaponSpec HORNET = reg("hornet", "Hornet", WAW,
            SinProfile.builder().add(LUST, 32).add(ENVY, 28).add(PRIDE, 22).add(GLUTTONY, 12).add(WRATH, 6).build());
    // Desire and beauty, wistful for its own obsolescence — when flowers substitute all desire it is no longer
    // needed. Pride✕Gloom over a light Lust✕Envy.
    public static final WeaponSpec FAINT_AROMA = reg("faint_aroma", "Faint Aroma", WAW,
            SinProfile.builder().add(LUST, 38).add(PRIDE, 22).add(GLOOM, 20).add(ENVY, 12).add(GLUTTONY, 8).build());
    // The dark half: the inevitability of the cycle is sloth, the shadow is gloom, and the power it grants is
    // pride. Pride✕Gloom.
    public static final WeaponSpec DISCORD = reg("discord", "Discord", WAW,
            SinProfile.builder().add(GLOOM, 40).add(SLOTH, 24).add(PRIDE, 18).add(ENVY, 10).add(WRATH, 8).build());

    // ---- ALEPH ----
    // Never forgot others' sins, and wants to hide the sad memories: righteous wrath, the pride of the just,
    // the grief underneath. Straddles TWO bridges (Pride✕Gloom and Wrath✕Sloth) — brutal, as an apex should be.
    public static final WeaponSpec JUSTITIA = reg("justitia", "Justitia", ALEPH,
            SinProfile.builder().add(WRATH, 30).add(PRIDE, 26).add(GLOOM, 24).add(SLOTH, 12).add(ENVY, 8).build());
    // Mimicry IS envy — a thing wearing the shape of a weapon, wanting to be what it isn't. It devours, it is
    // the Red Mist, and underneath there is nothing there. Six sins, two bridges: the hardest pour in the roster.
    public static final WeaponSpec MIMICRY = reg("mimicry", "Mimicry", ALEPH,
            SinProfile.builder().add(ENVY, 28).add(GLUTTONY, 22).add(WRATH, 20).add(GLOOM, 16).add(SLOTH, 8).add(PRIDE, 6).build());

    // The bus E.G.O (Flower Burying Wedge) is NOT here — it's earned through its own mechanic, not the Well.

    /** Look a weapon up by id, or {@code null}. */
    public static WeaponSpec byId(String id) { return REGISTRY.get(id); }

    /** All weapons in roster order. */
    public static Iterable<WeaponSpec> all() { return REGISTRY.values(); }

    /** Count of registered weapons. */
    public static int count() { return REGISTRY.size(); }
}
