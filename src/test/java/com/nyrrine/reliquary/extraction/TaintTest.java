package com.nyrrine.reliquary.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the diagnosis layer — the "treat in time or scar" contract: a cure clears cleanly,
 * an untreated taint locks in permanent damage on expiry, the right cure matters, and Fever blocks distilling.
 */
class TaintTest {

    private static java.util.random.RandomGenerator rng() { return new java.util.Random(0L); }

    @Test
    void cureInTimeClearsWithNoScar() {
        PotState p = new PotState();
        p.setCharge(Sin.GLOOM, 60);
        p.setStability(80);
        p.inflict(Taint.TOXIN);
        assertTrue(p.hasTaint(Taint.TOXIN));

        Engine.AddResult r = Engine.addReagent(p, Reagents.NETHER_WART, rng());
        assertFalse(p.hasTaint(Taint.TOXIN), "the antidote clears Toxin");
        assertTrue(r.cured().contains(Taint.TOXIN));
        assertTrue(p.stability() >= 78.0, "cured before any tick = no scar, stability intact: " + p.stability());
    }

    @Test
    void untreatedTaintScarsOnExpiry() {
        PotState p = new PotState();
        p.setCharge(Sin.GLOOM, 60);
        p.setStability(80);
        p.inflict(Taint.TOXIN); // 12s timer, -2/s, -18 scar

        boolean scarred = false;
        for (int i = 0; i < 14; i++) {
            if (Engine.tickTaints(p, 1.0).contains(Taint.TOXIN)) scarred = true;
        }
        assertTrue(scarred, "an untreated Toxin should scar on expiry");
        assertFalse(p.hasTaint(Taint.TOXIN), "the expired taint is gone");
        assertTrue(p.stability() < 45.0, "permanent damage stuck (drain + scar), was " + p.stability());
    }

    @Test
    void wrongCureLeavesTheTaint() {
        PotState p = new PotState();
        p.setCharge(Sin.GLOOM, 60);
        p.setStability(80);
        p.inflict(Taint.TOXIN);
        Engine.addReagent(p, Reagents.SNOWBALL, rng()); // Snowball cures Fever, not Toxin
        assertTrue(p.hasTaint(Taint.TOXIN), "the wrong remedy doesn't touch it");
    }

    @Test
    void feverBlocksDistillUntilQuenched() {
        PotState p = new PotState();
        p.setCharge(Sin.WRATH, 60);
        p.setNoise(20);
        p.setStability(90);
        p.inflict(Taint.FEVER);

        assertFalse(Engine.distill(p), "Fever blocks the Centrifuge");
        Engine.addReagent(p, Reagents.SNOWBALL, rng()); // quench
        assertFalse(p.hasTaint(Taint.FEVER));
        double noiseBefore = p.noise();
        assertTrue(Engine.distill(p), "quenched -> distilling works again");
        assertTrue(p.noise() < noiseBefore, "distill actually scrubbed noise");
    }

    @Test
    void untreatedTaintCompoundsIntoAnother() {
        PotState p = new PotState();
        p.setCharge(Sin.GLOOM, 60);
        p.setStability(95);
        p.inflict(Taint.TOXIN); // compounds into Bleeding on expiry
        for (int i = 0; i < 13; i++) Engine.tickTaints(p, 1.0);
        assertFalse(p.hasTaint(Taint.TOXIN), "Toxin expired");
        assertTrue(p.hasTaint(Taint.BLEEDING), "neglected Toxin should compound into Bleeding");
    }

    @Test
    void dirtyReagentSometimesInflictsSediment() {
        boolean seen = false;
        for (long seed = 0; seed < 60 && !seen; seed++) {
            PotState p = new PotState();
            p.setStability(100); // steady, so the first add never slips
            Engine.addReagent(p, Reagents.INK_SAC, new java.util.Random(seed));
            if (p.hasTaint(Taint.SEDIMENT)) seen = true;
        }
        assertTrue(seen, "dirty bulk should sometimes cloud the pot with Sediment");
    }
}
