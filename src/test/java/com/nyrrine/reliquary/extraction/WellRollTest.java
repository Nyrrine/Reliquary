package com.nyrrine.reliquary.extraction;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the Pocket Well resolution — the anti-luck guarantees the design leans on: the grade
 * floor and volume gate seal luck inside your band, a Certified pour is a guaranteed manifest, and
 * over-reaching breaches at the tier you reached for.
 */
class WellRollTest {

    private static RandomGenerator seeded() { return new java.util.Random(99L); }

    /** Build a pot at a target composition, volume, and purity (noise = 100 − purity, ceiling stays 100). */
    private static PotState pour(SinProfile sig, double titer, double purity, double stability) {
        PotState p = new PotState();
        for (Sin s : Sin.values()) p.setCharge(s, sig.get(s) / 100.0 * titer);
        p.setNoise(100.0 - purity);
        p.setStability(stability);
        return p;
    }

    @Test
    void rosterIsFullyRegistered() {
        assertEquals(24, WeaponSignatures.count(), "the full Well roster (24 E.G.O; bus ego is not Well-obtainable)");
        assertNotNull(WeaponSignatures.byId("solemn_lament"));
        assertSame(EgoGrade.WAW, WeaponSignatures.SOLEMN_LAMENT.grade());
    }

    @Test
    void certifiedPourIsGuaranteedManifestOfTheCatalystTarget() {
        // On-target WAW cogito at Primary Standard, big volume, catalyst loaded → guaranteed Solemn Lament.
        PotState p = pour(WeaponSignatures.SOLEMN_LAMENT.signature(), 1000, 99.5, 85);
        WellRoll.Result r = WellRoll.resolve(p, "solemn_lament", 0.0, seeded());

        assertSame(WellRoll.Outcome.MANIFEST, r.outcome());
        assertEquals("solemn_lament", r.weapon().id());
        assertTrue(r.certified(), "Primary Standard + locked catalyst should certify");
    }

    @Test
    void gradeFloorSealsWawOutOfSubAnalyticalPours() {
        // Perfect Solemn Lament composition but only Reagent grade (88%) → WAW is not even in the pool.
        PotState p = pour(WeaponSignatures.SOLEMN_LAMENT.signature(), 1000, 88.0, 80);
        assertTrue(WellRoll.pool(p).stream().noneMatch(c -> c.weapon().grade() == EgoGrade.WAW),
                "sub-Analytical cogito must never put a WAW weapon in the pool");
    }

    @Test
    void volumeGateSealsWawOutOfThinPours() {
        // Analytical grade and a perfect signature, but only a single vial of volume → WAW volume-gated out.
        PotState p = pour(WeaponSignatures.SOLEMN_LAMENT.signature(), 120, 97.0, 85);
        assertTrue(WellRoll.pool(p).stream().noneMatch(c -> c.weapon().grade() == EgoGrade.WAW),
                "a thin pour can't put WAW in the pool even at Analytical grade");
    }

    @Test
    void overReachingWithACatalystBreachesAtTheAimedTier() {
        // Muddy, scattered, unstable crude cogito but a WAW catalyst loaded → over-reach → WAW-class breach.
        SinProfile scattered = SinProfile.builder()
                .add(Sin.WRATH, 1).add(Sin.PRIDE, 1).add(Sin.LUST, 1).add(Sin.GLOOM, 1)
                .add(Sin.SLOTH, 1).add(Sin.ENVY, 1).add(Sin.GLUTTONY, 1).build();
        PotState p = pour(scattered, 1000, 45.0, 5);
        WellRoll.Result r = WellRoll.resolve(p, "solemn_lament", 0.6, seeded());

        assertSame(WellRoll.Outcome.BREACH, r.outcome());
        assertSame(EgoGrade.WAW, r.aimedGrade(), "the catalyst declares WAW intent → WAW breach severity");
    }

    @Test
    void cleanZayinPourCanManifestWithoutACatalyst() {
        // A tidy single-cluster ZAYIN target at reagent grade should manifest on its own often enough.
        PotState p = pour(WeaponSignatures.PENITENCE.signature(), 200, 92.0, 80);
        WellRoll.Result r = WellRoll.resolve(p, null, 0.0, seeded());

        // Reachable and a strong match — outcome is a weapon on the ZAYIN/low band, never a WAW.
        assertNotNull(r.weapon());
        assertTrue(r.weapon().grade().minCogito().atMost(Grade.of(92.0)));
        assertTrue(r.match() > 0.9, "a clean on-target pour should match tightly, was " + r.match());
    }

    @Test
    void wellIsAWeightedGachaNotDeterministic() {
        // A mix sitting between reachable weapons should, over many pours, yield MORE THAN ONE — weighted
        // toward the closer. The odds must be a real distribution (sum to 1).
        SinProfile mid = SinProfile.builder().add(Sin.GLOOM, 62).add(Sin.SLOTH, 38).build();
        PotState p = pour(mid, 200, 90.0, 95);

        List<WellRoll.Chance> pool = WellRoll.pool(p);
        assertTrue(pool.size() >= 2, "the mix should sit near several reachable weapons");
        assertEquals(1.0, pool.stream().mapToDouble(WellRoll.Chance::odds).sum(), 1.0e-6, "odds are a distribution");

        RandomGenerator rng = new java.util.Random(1L);
        Set<String> manifested = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            WellRoll.Result r = WellRoll.resolve(p, null, 0.0, rng);
            if (r.outcome() == WellRoll.Outcome.MANIFEST) manifested.add(r.weapon().id());
        }
        assertTrue(manifested.size() >= 2, "the gacha should produce variety, got " + manifested);
    }

    @Test
    void aCatalystGuaranteesTheTargetOutOfThePool() {
        // Same neighbourhood, but load Penitence's catalyst on an on-target pour → Penitence every time.
        PotState p = pour(WeaponSignatures.PENITENCE.signature(), 200, 90.0, 95);
        RandomGenerator rng = new java.util.Random(2L);
        for (int i = 0; i < 50; i++) {
            WellRoll.Result r = WellRoll.resolve(p, "penitence", 0.0, rng);
            assertSame(WellRoll.Outcome.MANIFEST, r.outcome());
            assertEquals("penitence", r.weapon().id(), "the catalyst must cut the RNG to a guaranteed pull");
        }
    }
}
