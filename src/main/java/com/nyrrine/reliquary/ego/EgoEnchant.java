package com.nyrrine.reliquary.ego;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of E.G.O enchantments — the reinterpreted and invented ones a weapon re-expresses through its
 * own mechanic. This is the single source of truth for "what custom enchants exist": the
 * {@code /reliquary enchant} command validates against it, tab-completes from it, {@code /reliquary enchant all}
 * maxes the ones {@link #forWeapon belonging to} the held weapon, and {@link EgoEnchants} pulls display names
 * from it.
 *
 * <p>An enchant only earns an entry here once a weapon actually reads it (via
 * {@link EgoEnchants#level(org.bukkit.inventory.ItemStack, String)}) and does something with it — so the
 * command never offers an enchant that would sit inert on the item. Each entry carries the {@code weaponId}
 * (the owning weapon's {@link com.nyrrine.reliquary.core.Weapon#id()}) it belongs to, so "enchant all" applies
 * only the ones the weapon in hand actually reads.
 *
 * <p>Only CUSTOM enchants live here. A weapon that reinterprets a real vanilla enchant (a crossbow's Multishot,
 * a sword's Sharpness) reads it straight from the item's enchant map instead, applied at an anvil in normal
 * play — see LaetitiaWeapon and BeakWeapon.
 */
public record EgoEnchant(String id, String displayName, int maxLevel, String weaponId, String description) {

    private static final Map<String, EgoEnchant> REGISTRY = new LinkedHashMap<>();

    private static EgoEnchant reg(String id, String displayName, int maxLevel, String weaponId, String description) {
        EgoEnchant e = new EgoEnchant(id, displayName, maxLevel, weaponId, description);
        REGISTRY.put(id, e);
        return e;
    }

    // ---- The catalogue (id, display, maxLevel, owning weaponId, description) ----
    public static final EgoEnchant CONSTELLATION = reg("constellation", "Constellation", 2, "our_galaxy",
            "Our Galaxy: +1 comet in the pool per level before a recharge.");
    public static final EgoEnchant HEMORRHAGE = reg("hemorrhage", "Hemorrhage", 3, "wrist_cutter",
            "Wrist Cutter: +1 to the bleed-stack cap per level (a deeper, longer wound).");
    public static final EgoEnchant RAVENOUS = reg("ravenous", "Ravenous", 3, "beak",
            "Beak: +2 rounds in the magazine per level.");
    public static final EgoEnchant HALL_OF_MIRRORS = reg("hall_of_mirrors", "Hall of Mirrors", 3, "discord",
            "Discord: +1 bounce to the Devil's Pendant per level (a beam that mirrors farther).");
    public static final EgoEnchant DEEP_FREEZE = reg("deep_freeze", "Deep Freeze", 3, "frost_splinter",
            "Frost Splinter: +0.5s to the Second Kiss slowness per level (a longer chill).");
    public static final EgoEnchant BACKDRAFT = reg("backdraft", "Backdraft", 3, "fourth_match_flame",
            "Fourth Match Flame: -5% reload per level while you are on fire (up to -15%).");
    public static final EgoEnchant FIXATION = reg("fixation", "Fixation", 2, "gaze",
            "Gaze: +1.5s of Delight decay grace per level (hold your peak, not a higher one).");
    public static final EgoEnchant SWIFT_JUSTICE = reg("swift_justice", "Swift Justice", 3, "justitia",
            "Justitia: -2s to the Judgement cooldown per level (floor 8s).");
    public static final EgoEnchant BLOODTHIRST = reg("bloodthirst", "Bloodthirst", 3, "mimicry",
            "Mimicry: +0.05 lifesteal per level (sustain, clamped to max health).");
    public static final EgoEnchant ARCANA_FOCUS = reg("arcana_focus", "Arcana Focus", 3, "love_and_hate",
            "Love & Hate: -15s to the current form's ult cooldown per level (floor 60s).");
    public static final EgoEnchant HAIR_TRIGGER = reg("hair_trigger", "Hair Trigger", 3, "regret",
            "Regret: -300ms to the charge time per level (floor 1.5s).");
    public static final EgoEnchant ADRENALINE = reg("adrenaline", "Adrenaline", 3, "life_for_a_daredevil",
            "Life for a Daredevil: Speed + Resistance per level while at or below 35% health.");
    public static final EgoEnchant GROVE = reg("grove", "Grove", 3, "heaven",
            "Heaven: +1 to a summoned tree's skull cap per level (up to 8).");
    public static final EgoEnchant ALL_SEEING = reg("all_seeing", "All-Seeing", 3, "heaven",
            "Heaven: +10% stasis chance on a looking hit per level.");
    public static final EgoEnchant RAMPANT_BLOOM = reg("rampant_bloom", "Rampant Bloom", 3, "faint_aroma",
            "Faint Aroma: +3 to the petal cap per level (a slow, gated bloom).");
    public static final EgoEnchant RAM_THE_POWDER = reg("ram_the_powder", "Ram the Powder", 3, "crimson_scar",
            "Crimson Scar: -12% to the flintlock reload per level.");
    public static final EgoEnchant LONG_HAIR = reg("long_hair", "Long Hair", 3, "screaming_wedge",
            "Screaming Wedge: +25% strand reach per level.");
    public static final EgoEnchant CONVERGING_GRIEF = reg("converging_grief", "Converging Grief", 3, "sword_of_tears",
            "Sword of Tears: -12% to the Converging Impale cooldown per level.");
    public static final EgoEnchant REFRACTED_STEP = reg("refracted_step", "Refracted Step", 3, "fragments_from_somewhere",
            "Fragments: +1s to the refraction return window per level.");

    /** The enchant with this id, or {@code null} if no such enchant is catalogued. */
    public static EgoEnchant get(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase());
    }

    /** Every catalogued enchant, in registration order. */
    public static Collection<EgoEnchant> all() {
        return REGISTRY.values();
    }

    /** The custom enchants belonging to the weapon with this {@code weaponId} — what "enchant all" maxes. */
    public static List<EgoEnchant> forWeapon(String weaponId) {
        List<EgoEnchant> out = new ArrayList<>();
        for (EgoEnchant e : REGISTRY.values()) {
            if (e.weaponId().equals(weaponId)) out.add(e);
        }
        return out;
    }
}
