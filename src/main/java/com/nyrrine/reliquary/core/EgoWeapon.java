package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.ego.EgoLore;

/**
 * An E.G.O weapon — a {@link Weapon} that carries the shared E.G.O tooltip and so can hold reinterpreted
 * enchantments. Only the {@code ego.weapons.*} classes implement this; the relics and the bus-ego do not,
 * which is how the enchant system excludes them for free: "is this enchantable?" is simply
 * {@code weapon instanceof EgoWeapon}, with no package-name matching.
 *
 * <p>The interface exposes the weapon's immutable base tooltip so the enchant renderer can rebuild the lore
 * with an Enchantments block appended — from the base each time, never accumulating stale entries.
 */
public interface EgoWeapon extends Weapon {

    /** This weapon's base E.G.O tooltip — the {@code static final TOOLTIP} every E.G.O weapon already holds. */
    EgoLore.Tooltip egoTooltip();
}
