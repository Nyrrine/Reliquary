package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the catalyst forge — every Well-obtainable weapon must have a grind recipe, and every
 * recipe must be firable (at least one component, positive Enkephalin). The forge is content, not luck: a
 * missing recipe would mean a weapon you can roll but never <i>guarantee</i>.
 */
class CatalystTest {

    @Test
    void everyWeaponInTheRosterHasARecipe() {
        assertEquals(35, Catalysts.count(), "one catalyst recipe per Well-obtainable weapon");
        for (WeaponSpec w : WeaponSignatures.all()) {
            assertNotNull(Catalysts.forWeapon(w.id()),
                    "weapon '" + w.id() + "' has no catalyst recipe");
        }
    }

    @Test
    void everyRecipeIsFirable() {
        for (Catalysts.Recipe r : Catalysts.all()) {
            assertFalse(r.components().isEmpty(),
                    "recipe '" + r.weaponId() + "' must have at least one component");
            assertTrue(r.enkephalin() > 0,
                    "recipe '" + r.weaponId() + "' must cost positive Enkephalin, was " + r.enkephalin());
        }
    }

    /**
     * Global uniqueness: no {@link Material} may appear in more than one weapon's recipe. The owner wants "the
     * most random blocks" — every component is unique across all 35 recipes, so a material maps to exactly one
     * weapon. Fails loudly with the offending material and the two weapons that share it.
     */
    @Test
    void noMaterialSharedAcrossRecipes() {
        Map<Material, String> owner = new HashMap<>();
        for (Catalysts.Recipe r : Catalysts.all()) {
            for (Material m : r.components().keySet()) {
                String prior = owner.putIfAbsent(m, r.weaponId());
                assertNull(prior, "material " + m + " is shared by '" + prior + "' and '"
                        + r.weaponId() + "' — every material must belong to exactly one recipe");
            }
        }
    }
}
