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

    /** The enchant with this id, or {@code null} if no such enchant is catalogued. */
    public static EgoEnchant get(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase());
    }

    /** Every catalogued enchant, in registration order. */
    public static Collection<EgoEnchant> all() {
        return REGISTRY.values();
    }
}
