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
    // Multishot, reinterpreted by Laetitia as a larger bolt magazine before the forced reload (+1 per level).
    public static final EgoEnchant MULTISHOT = reg("multishot", "Multishot", 3,
            "Laetitia: +1 bolt in the magazine per level before a reload.");
    // Constellation, Our Galaxy's own reinterpretation of Multishot: +1 comet in the pool per level before recharge.
    public static final EgoEnchant CONSTELLATION = reg("constellation", "Constellation", 2,
            "Our Galaxy: +1 comet in the pool per level before a recharge.");

    /** The enchant with this id, or {@code null} if no such enchant is catalogued. */
    public static EgoEnchant get(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase());
    }

    /** Every catalogued enchant, in registration order. */
    public static Collection<EgoEnchant> all() {
        return REGISTRY.values();
    }
}
