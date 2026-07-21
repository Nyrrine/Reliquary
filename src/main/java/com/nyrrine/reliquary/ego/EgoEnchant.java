package com.nyrrine.reliquary.ego;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The catalogue of E.G.O enchantments — the reinterpreted and invented ones a weapon re-expresses through its
 * own mechanic. This is the single source of truth for "what enchants exist": the {@code /reliquary enchant}
 * command validates against it, tab-completes from it, and {@link EgoEnchants} pulls display names from it.
 *
 * <p>An enchant only earns an entry here once a weapon actually reads it (via
 * {@link EgoEnchants#level(org.bukkit.inventory.ItemStack, String)}) and does something with it — so the
 * command never offers an enchant that would sit inert on the item. As each weapon's effects land, its ids
 * are registered here alongside them.
 */
public record EgoEnchant(String id, String displayName, int maxLevel, String description) {

    private static final Map<String, EgoEnchant> REGISTRY = new LinkedHashMap<>();

    private static EgoEnchant reg(String id, String displayName, int maxLevel, String description) {
        EgoEnchant e = new EgoEnchant(id, displayName, maxLevel, description);
        REGISTRY.put(id, e);
        return e;
    }

    // ---- The catalogue ----
    // Only CUSTOM enchants live here — invented ones with no vanilla equivalent, applied with /reliquary enchant.
    // A weapon that reinterprets a real vanilla enchant (a crossbow's Multishot, a sword's Sharpness) reads it
    // straight from the item's enchant map instead, so it can be applied at an anvil in normal play — see
    // LaetitiaWeapon and BeakWeapon.
    //
    // Constellation is Our Galaxy's take on Multishot, but a Breeze rod can't hold vanilla Multishot, so it is a
    // custom enchant: +1 comet in the pool per level before the recharge.
    public static final EgoEnchant CONSTELLATION = reg("constellation", "Constellation", 2,
            "Our Galaxy: +1 comet in the pool per level before a recharge.");
    // Hemorrhage: a deeper wound on Wrist Cutter — +1 to the bleed-stack cap per level (a longer, larger bleed).
    public static final EgoEnchant HEMORRHAGE = reg("hemorrhage", "Hemorrhage", 3,
            "Wrist Cutter: +1 to the bleed-stack cap per level (a deeper, longer wound).");
    // Ravenous: a hungrier Beak carries a bigger magazine — +2 rounds per level, more shots between reloads.
    public static final EgoEnchant RAVENOUS = reg("ravenous", "Ravenous", 3,
            "Beak: +2 rounds in the magazine per level.");
    // Hall of Mirrors: Discord's Devil's Pendant mirrors off more walls — +1 to the beam's bounce cap per
    // level, up to +3 (an 11-bounce beam). It turns more corners, never cuts harder.
    public static final EgoEnchant HALL_OF_MIRRORS = reg("hall_of_mirrors", "Hall of Mirrors", 3,
            "Discord: +1 bounce to the Devil's Pendant per level (a beam that mirrors farther).");
    // Deep Freeze: Frost Splinter's parting chill lingers longer — +0.5s to the Second Kiss slowness per
    // level, up to +1.5s. A longer chill, never a deeper one; the amplifier is untouched.
    public static final EgoEnchant DEEP_FREEZE = reg("deep_freeze", "Deep Freeze", 3,
            "Frost Splinter: +0.5s to the Second Kiss slowness per level (a longer chill).");
    // Backdraft: a wielder already ablaze reloads Fourth Match Flame faster — the draft of their own fire
    // feeds the barrel. -5% to the reload per level while on fire, up to -15%. Cadence only, never damage.
    public static final EgoEnchant BACKDRAFT = reg("backdraft", "Backdraft", 3,
            "Fourth Match Flame: -5% reload per level while you are on fire (up to -15%).");
    // Fixation: Gaze holds its Delight — +1.5s of decay grace per level. It never raises the Delight damage
    // cap (full Delight stays level with the band); it just makes reaching and keeping that peak easier.
    public static final EgoEnchant FIXATION = reg("fixation", "Fixation", 2,
            "Gaze: +1.5s of Delight decay grace per level (hold your peak, not a higher one).");
    // Swift Justice: Judgement recurs sooner — -2s to its cooldown per level, floored at 8s. Cadence only.
    public static final EgoEnchant SWIFT_JUSTICE = reg("swift_justice", "Swift Justice", 3,
            "Justitia: -2s to the Judgement cooldown per level (floor 8s).");
    // Bloodthirst: Mimicry drinks deeper — +0.05 to its lifesteal fraction per level. Sustain, not damage;
    // still clamped to max health, so it stays in band.
    public static final EgoEnchant BLOODTHIRST = reg("bloodthirst", "Bloodthirst", 3,
            "Mimicry: +0.05 lifesteal per level (sustain, clamped to max health).");
    // Arcana Focus: the current form's ultimate returns sooner — -15s to its cooldown per level, floor 60s.
    public static final EgoEnchant ARCANA_FOCUS = reg("arcana_focus", "Arcana Focus", 3,
            "Love & Hate: -15s to the current form's ult cooldown per level (floor 60s).");
    // Hair Trigger: Regret winds up faster — -300ms to its charge time per level, floored at 1.5s. Cadence only.
    public static final EgoEnchant HAIR_TRIGGER = reg("hair_trigger", "Hair Trigger", 3,
            "Regret: -300ms to the charge time per level (floor 1.5s).");
    // Adrenaline: Life for a Daredevil fights harder cornered — Speed + Resistance while at or below 35% HP.
    // Survival, not damage: it helps the daredevil live, per level a little longer/stronger.
    public static final EgoEnchant ADRENALINE = reg("adrenaline", "Adrenaline", 3,
            "Life for a Daredevil: Speed + Resistance per level while at or below 35% health.");
    // Grove: a summoned Burrowing Heaven carries more heads — +1 to its skull cap per level (base 5, up to 8).
    public static final EgoEnchant GROVE = reg("grove", "Grove", 3,
            "Heaven: +1 to a summoned tree's skull cap per level (up to 8).");
    // All-Seeing: the heaven opens more readily — +10% stasis chance on a looking hit per level (up to +30%).
    public static final EgoEnchant ALL_SEEING = reg("all_seeing", "All-Seeing", 3,
            "Heaven: +10% stasis chance on a looking hit per level.");
    // Rampant Bloom: Faint Aroma carries more petals — +3 to the petal cap per level (a slow, gated build).
    public static final EgoEnchant RAMPANT_BLOOM = reg("rampant_bloom", "Rampant Bloom", 3,
            "Faint Aroma: +3 to the petal cap per level (a slow, gated bloom).");
    // Ram the Powder: Crimson Scar's flintlock reloads faster — -12% to its reload per level. Cadence only.
    public static final EgoEnchant RAM_THE_POWDER = reg("ram_the_powder", "Ram the Powder", 3,
            "Crimson Scar: -12% to the flintlock reload per level.");
    // Long Hair: the Screaming Wedge's strand reaches farther — +25% reach per level. Reach only, no damage.
    public static final EgoEnchant LONG_HAIR = reg("long_hair", "Long Hair", 3,
            "Screaming Wedge: +25% strand reach per level.");
    // Converging Grief: Sword of Tears' Impale returns sooner — -12% to its cooldown per level. Cadence only.
    public static final EgoEnchant CONVERGING_GRIEF = reg("converging_grief", "Converging Grief", 3,
            "Sword of Tears: -12% to the Converging Impale cooldown per level.");
    // Refracted Step: Fragments' return window lingers — +1s to the refraction window per level.
    public static final EgoEnchant REFRACTED_STEP = reg("refracted_step", "Refracted Step", 3,
            "Fragments: +1s to the refraction return window per level.");

    /** The enchant with this id, or {@code null} if no such enchant is catalogued. */
    public static EgoEnchant get(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase());
    }

    /** Every catalogued enchant, in registration order. */
    public static Collection<EgoEnchant> all() {
        return REGISTRY.values();
    }
}
