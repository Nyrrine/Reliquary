package com.nyrrine.reliquary.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the extraction chemistry. These pin down the <b>intended feel</b> of the system as
 * inequalities and grade outcomes (spam must lose, the surgeon route must reach WAW grade, opposition must
 * bite), so the {@link Engine} and {@link Reagents} constants can be retuned without breaking the design —
 * a test fails only if the tuning stops producing the intended behaviour.
 */
class EngineTest {

    /** A fixed-seed RNG so the volatile/step-failure rolls are deterministic per test. */
    private static RandomGenerator seeded() {
        return new java.util.Random(1234L);
    }

    // ---- grade ladder --------------------------------------------------------------

    @Test
    void gradeLadderBandsPurityCorrectly() {
        assertSame(Grade.CRUDE, Grade.of(50));
        assertSame(Grade.TECHNICAL, Grade.of(72));
        assertSame(Grade.REAGENT, Grade.of(90));
        assertSame(Grade.ANALYTICAL, Grade.of(97));
        assertSame(Grade.PRIMARY_STANDARD, Grade.of(99.5));
        assertSame(Grade.CERTIFIED, Grade.of(100));
        assertTrue(Grade.ANALYTICAL.atLeast(Grade.REAGENT));
        assertTrue(Grade.REAGENT.atMost(Grade.ANALYTICAL));
    }

    // ---- sin model -----------------------------------------------------------------

    @Test
    void oppositionBridgesArePaired() {
        assertTrue(Sin.PRIDE.opposes(Sin.GLOOM));
        assertTrue(Sin.WRATH.opposes(Sin.SLOTH));
        assertTrue(Sin.LUST.opposes(Sin.ENVY));
        assertNull(Sin.GLUTTONY.opposite()); // the hub opposes nothing
        assertEquals(3, Sin.bridges().size());
    }

    @Test
    void sinProfileDistanceAndMatch() {
        SinProfile a = SinProfile.builder().add(Sin.GLOOM, 100).build();
        SinProfile b = SinProfile.builder().add(Sin.GLOOM, 100).build();
        SinProfile c = SinProfile.builder().add(Sin.WRATH, 100).build();

        assertEquals(1.0, a.match(b), 1.0e-9);          // identical → perfect match
        assertTrue(a.match(c) < 0.5);                   // opposite corner → poor match
        assertEquals(Sin.GLOOM, a.dominant());
        assertTrue(a.distance(c) > a.distance(b));
    }

    // ---- the anti-spam guarantee ---------------------------------------------------

    @Test
    void spammingAFineTunerCannotReachWawGrade() {
        // 60x a clean +1% scalpel (Redstone). Escalating handling wrecks the batch, and its Refined tier
        // caps the ceiling — the exact exploit the design forbids.
        PotState spam = new PotState();
        RandomGenerator rng = seeded();
        for (int i = 0; i < 60; i++) Engine.addReagent(spam, Reagents.REDSTONE_DUST, rng);

        assertTrue(spam.grade().atMost(Grade.REAGENT),
                "spam must be capped below Analytical, was " + spam.grade() + " @ " + spam.purity() + "%");
    }

    @Test
    void surgeonRouteReachesAnalyticalGrade() {
        // A handful of decisive Pure adds, buffered, then distilled — the earned WAW-grade route.
        PotState pot = new PotState();
        RandomGenerator rng = seeded();
        for (int i = 0; i < 6; i++) {
            Engine.addReagent(pot, Reagents.DISTILLED_SORROW, rng); // Pure Gloom
            if (i % 2 == 1) Engine.addReagent(pot, Reagents.AMETHYST_SHARD, rng); // buffer to keep piloting
        }
        Engine.distill(pot);
        Engine.distill(pot);

        assertTrue(pot.grade().atLeast(Grade.ANALYTICAL),
                "surgeon route should reach Analytical, was " + pot.grade() + " @ " + pot.purity() + "%");
        assertEquals(Sin.GLOOM, pot.profile().dominant());
    }

    @Test
    void surgeonBeatsSpamDecisively() {
        RandomGenerator rng = seeded();

        PotState spam = new PotState();
        for (int i = 0; i < 60; i++) Engine.addReagent(spam, Reagents.REDSTONE_DUST, rng);

        PotState surgeon = new PotState();
        for (int i = 0; i < 6; i++) Engine.addReagent(surgeon, Reagents.DISTILLED_SORROW, rng);
        Engine.distill(surgeon);

        assertTrue(surgeon.purity() > spam.purity() + 20.0,
                "surgeon " + surgeon.purity() + "% should crush spam " + spam.purity() + "%");
    }

    // ---- ceiling: worst reagent caps the batch -------------------------------------

    @Test
    void oneDirtyReagentCapsTheWholeBatch() {
        PotState pot = new PotState();
        RandomGenerator rng = seeded();
        Engine.addReagent(pot, Reagents.INK_SAC, rng);          // Crude — caps at 85
        for (int i = 0; i < 4; i++) Engine.addReagent(pot, Reagents.DISTILLED_SORROW, rng); // Pure can't lift it
        Engine.distill(pot);
        Engine.distill(pot);

        assertTrue(pot.ceiling() <= 85.0 + 1.0e-9, "ceiling should be capped at Crude, was " + pot.ceiling());
        assertTrue(pot.grade().atMost(Grade.REAGENT), "capped batch can't be Analytical, was " + pot.grade());
    }

    // ---- opposition: cross-axis pots bleed stability -------------------------------

    @Test
    void crossAxisPotIsLessStableThanSingleCluster() {
        RandomGenerator rng = seeded();

        // Single-cluster (cold): Gloom + Sloth resonate — stays calmer.
        PotState calm = new PotState();
        for (int i = 0; i < 4; i++) Engine.addReagent(calm, Reagents.LAPIS_LAZULI, rng);
        for (int i = 0; i < 4; i++) Engine.addReagent(calm, Reagents.SOUL_SAND, rng);

        // Cross-axis: Gloom + Pride sit on an opposition bridge — bleeds faster.
        PotState tense = new PotState();
        for (int i = 0; i < 4; i++) Engine.addReagent(tense, Reagents.LAPIS_LAZULI, rng);
        for (int i = 0; i < 4; i++) Engine.addReagent(tense, Reagents.GOLD_INGOT, rng); // Pride vs Gloom

        assertTrue(tense.stability() < calm.stability(),
                "cross-axis (" + tense.stability() + ") should be shakier than single-cluster (" + calm.stability() + ")");
    }

    // ---- distillation ---------------------------------------------------------------

    @Test
    void distillCutsNoiseWithDiminishingReturnsAndNeverLiftsCeiling() {
        PotState pot = new PotState();
        RandomGenerator rng = seeded();
        Engine.addReagent(pot, Reagents.INK_SAC, rng); // dirty: noise + an 85 ceiling
        double ceilingBefore = pot.ceiling();
        double noise0 = pot.noise();

        Engine.distill(pot);
        double noise1 = pot.noise();
        Engine.distill(pot);
        double noise2 = pot.noise();

        assertTrue(noise1 < noise0, "first distill should cut noise");
        assertTrue(noise2 < noise1, "second distill should still cut noise");
        assertTrue((noise0 - noise1) > (noise1 - noise2), "returns should diminish");
        assertEquals(ceilingBefore, pot.ceiling(), 1.0e-9, "distill must never lift the ceiling");
    }

    // ---- blending stocks ------------------------------------------------------------

    @Test
    void blendingCleanStocksHitsTheTargetRatio() {
        // Two clean single-sin stocks, blended 50:30 by volume, should land near a 62/38 Gloom/Pride ratio.
        PotState gloom = new PotState();
        gloom.setCharge(Sin.GLOOM, 50);
        PotState pride = new PotState();
        pride.setCharge(Sin.PRIDE, 30);

        PotState mix = Engine.blend(List.of(gloom, pride));
        SinProfile p = mix.profile();

        assertEquals(62.5, p.get(Sin.GLOOM), 1.0, "blend should preserve the volume ratio");
        assertEquals(37.5, p.get(Sin.PRIDE), 1.0);
        assertEquals(80.0, mix.titer(), 1.0e-9, "titer is the sum of the stocks");
    }

    // ---- cogito value round-trips through the pot state -----------------------------

    @Test
    void potStateCopyIsIndependent() {
        PotState a = new PotState();
        a.setCharge(Sin.GLOOM, 40);
        a.setNoise(5);
        PotState b = a.copy();
        b.addCharge(Sin.GLOOM, 10);
        b.addNoise(5);

        assertEquals(40, a.charge(Sin.GLOOM), 1.0e-9, "copy must not alias the original");
        assertEquals(5, a.noise(), 1.0e-9);
        assertEquals(50, b.charge(Sin.GLOOM), 1.0e-9);
    }

    @Test
    void vialCapRefusesFurtherVolumeButAllowsUtilities() {
        // The finite-vessel guard: a single vial fills up, forcing distill/blend for more volume (WAW).
        PotState pot = new PotState();
        RandomGenerator rng = seeded();
        boolean hitFull = false;
        for (int i = 0; i < 300; i++) {
            if (Engine.addReagent(pot, Reagents.LAPIS_LAZULI, rng).full()) { hitFull = true; break; }
        }
        assertTrue(hitFull, "repeated volume adds should hit the vial cap");
        assertTrue(pot.titer() >= Engine.VIAL_CAP, "cap reached, titer=" + pot.titer());

        double titerAtCap = pot.titer();
        Engine.AddResult buffered = Engine.addReagent(pot, Reagents.AMETHYST_SHARD, rng); // a utility
        assertFalse(buffered.full(), "buffers/solvents are allowed even at the cap");
        assertEquals(titerAtCap, pot.titer(), 1.0e-9, "a buffer adds no volume");
    }

    @Test
    void reagentTableIsFullyRegistered() {
        assertEquals(44, Reagents.count(), "the full starter fingerprint table should be present");
        assertNotNull(Reagents.byId("knell_extract"));
        assertSame(Reagent.Tier.STANDARD, Reagents.KNELL_EXTRACT.tier());
        assertTrue(Reagents.BLAZE_ROD.isVolatile());
    }
}
