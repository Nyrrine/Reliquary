package com.nyrrine.reliquary.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the catalyst forge — every Well-obtainable weapon must have a grind recipe, and every
 * recipe must be firable (at least one component, positive Enkephalin). The forge is content, not luck: a
 * missing recipe would mean a weapon you can roll but never <i>guarantee</i>.
 */
class CatalystTest {

    @Test
    void everyWeaponInTheRosterHasARecipe() {
        assertEquals(24, Catalysts.count(), "one catalyst recipe per Well-obtainable weapon");
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
}
