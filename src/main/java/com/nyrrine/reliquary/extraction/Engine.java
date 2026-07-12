package com.nyrrine.reliquary.extraction;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The chemistry engine — the pure math of titration, distillation, blending and drift, operating on
 * {@link PotState}. No Bukkit here on purpose: this is unit-testable headless (see the extraction engine
 * tests), which is where the tuning constants below get validated before they ever touch a server.
 *
 * <p>The whole difficulty curve lives in {@link #addReagent}: escalating <b>handling contamination</b>
 * (every touch dirties the pot, and each successive touch more than the last — this is what kills spam),
 * the worst reagent capping the batch {@link PotState#ceiling() ceiling}, <b>opposition drain</b> on the
 * three sin bridges, and a <b>step-failure roll</b> that grows with instability and reagent tier (the
 * white-knuckle latter part of a high-grade extraction). Constants are STARTER values — tunable.
 */
public final class Engine {

    private Engine() {}

    // ---- tuning constants (§25 — starter, tune in playtest) ------------------------

    /** Base handling contamination per add. */
    public static final double HANDLING_BASE = 0.30;
    /** Each add's handling grows by this fraction over the previous (escalating — the anti-spam core). */
    public static final double HANDLING_ESCALATION = 0.15;
    /** Opposition drain per point of the smaller share of an opposed pair, per add. */
    public static final double OPPOSITION_DRAIN = 0.06;
    /** Opposition drain applied once when a blend brings an opposed pair together. */
    public static final double OPPOSITION_BLEND = 0.30;
    /** Base step-failure probability (scaled by instability × reagent tier factor). */
    public static final double STEP_BASE = 0.25;
    /** Extra contamination a failed step spikes into the pot. */
    public static final double FAIL_NOISE = 2.0;
    /** Extra stability a failed step costs. */
    public static final double FAIL_STAB = 6.0;
    /** Fraction of current noise a distillation pass removes (before decay). High enough that a meticulous
     *  nerd can scrub a clean pot to the Primary Standard cap (~99%) — but it never lifts the ceiling, so it
     *  can't rescue a spammed/dirty batch. */
    public static final double DISTILL_FRACTION = 0.45;
    /** Per-pass efficiency decay — diminishing returns (~4 useful passes). */
    public static final double DISTILL_DECAY = 0.6;
    /** Volume a distillation pass boils off — concentration costs material. Over-distilling shrinks the batch
     *  below the volume gate, so distilling is a measured decision, not a spam button. */
    public static final double DISTILL_VOLUME_LOSS = 0.08;
    /** How much a flux charge (Honeycomb) dampens opposition drain on the adds it covers. */
    public static final double FLUX_DRAIN_FACTOR = 0.3;
    /** Extra contamination a blend introduces. Kept small so clean stocks blend clean (§11) — a blend of
     *  Primary-Standard stocks must stay Primary Standard, or the catalyst path is dead. */
    public static final double BLEND_PENALTY = 0.25;
    /** Below this stability, an unstable pot drifts toward noise between touches. */
    public static final double DRIFT_FLOOR = 40.0;
    /** A single vial's volume ceiling — you run out of flask (anti-spam guard #4). Bigger = blend vials. */
    public static final double VIAL_CAP = 120.0;

    /** Outcome of a single reagent addition, for the station to narrate. */
    public record AddResult(boolean full, boolean stepFailed, boolean breached, double stabilityAfter) {}

    /**
     * Add a reagent to a pot (the Censer operation). Mutates {@code pot}. Handling contamination and the
     * ceiling cap always apply (you touched it); on a step failure the composition change is wasted and
     * the slip spikes noise + stability instead. {@code rng} drives the volatile roll and the failure roll.
     *
     * <p>A vial that has hit {@link #VIAL_CAP} refuses further reagent — you've run out of flask, and must
     * distill or blend to go bigger (this is what pushes high-volume WAW work onto the stock-solution route).
     * Solvents/buffers (no positive charge) are still allowed so you can always dilute or steady a full vial.
     */
    public static AddResult addReagent(PotState pot, Reagent r, RandomGenerator rng) {
        if (pot.titer() >= VIAL_CAP && addsVolume(r)) {
            return new AddResult(true, false, pot.stability() <= 0.0, pot.stability());
        }
        double stabilityGoingIn = pot.stability();
        pot.incrementAdds();

        // Handling: every touch of the volatile pot dirties it a little (escalating). This lands whether or
        // not the reagent enters cleanly — you disturbed the batch either way.
        double handling = HANDLING_BASE * (1.0 + HANDLING_ESCALATION * (pot.adds() - 1));
        pot.addNoise(handling);

        // Step-failure roll — worse when you were already shaky and the reagent runs hot.
        double pFail = STEP_BASE * (1.0 - stabilityGoingIn / 100.0) * r.failFactor();
        boolean failed = rng.nextDouble() < pFail;

        if (failed) {
            // Slipped — the reagent never entered the pot. None of its own effects apply (no composition,
            // no contamination, no ceiling cap, no stability cost); you eat only the fumble's toll.
            pot.addNoise(FAIL_NOISE);
            pot.addStability(-FAIL_STAB);
        } else {
            // It went in cleanly: its contamination, ceiling cap, stability cost, dilution and shift all land.
            if (r.noiseScale() != 1.0) pot.setNoise(pot.noise() * r.noiseScale()); // milk washes noise
            pot.addNoise(r.contam());
            pot.capCeiling(r.tierCeiling());
            pot.addStability(r.stab());
            if (r.flux() > 0) pot.addFluxCharges(r.flux()); // Honeycomb: mediates opposition on coming adds
            if (r.chargeScale() != 1.0) pot.scaleCharge(r.chargeScale()); // solvent dilution
            applyDelta(pot, r, rng);
        }

        // Opposition drain — the opposed pairs now coexisting bleed stability every touch.
        applyOppositionDrain(pot, OPPOSITION_DRAIN);

        boolean breached = pot.stability() <= 0.0;
        return new AddResult(false, failed, breached, pot.stability());
    }

    /** Whether a reagent contributes positive charge (so it counts against the vial cap). */
    private static boolean addsVolume(Reagent r) {
        if (r.isVolatile()) return true;
        for (double d : r.delta()) if (d > 0.0) return true;
        return false;
    }

    private static void applyDelta(PotState pot, Reagent r, RandomGenerator rng) {
        double[] delta = r.delta();
        for (Sin s : Sin.values()) {
            double d = delta[s.index()];
            if (d != 0.0) pot.addCharge(s, d);
        }
        if (r.isVolatile()) {
            Reagent.Roll roll = r.roll();
            double magnitude = roll.min() + rng.nextDouble() * (roll.max() - roll.min());
            pot.addCharge(roll.sin(), magnitude);
        }
    }

    /** Bleed stability for each opposed pair present, scaled by the smaller of the two shares. A flux charge
     *  (Honeycomb) mediates the warring sins on the adds it covers, dampening the drain and spending itself. */
    private static void applyOppositionDrain(PotState pot, double k) {
        SinProfile profile = pot.profile();
        double drain = 0.0;
        for (Sin.Bridge b : Sin.bridges()) {
            drain += k * Math.min(profile.get(b.a()), profile.get(b.b()));
        }
        if (drain <= 0.0) return;
        if (pot.fluxCharges() > 0) {
            drain *= FLUX_DRAIN_FACTOR;
            pot.setFluxCharges(pot.fluxCharges() - 1);
        }
        pot.addStability(-drain);
    }

    /**
     * Distill (the Centrifuge): scrub a fraction of the noise with diminishing returns per pass — but it
     * concentrates, boiling off {@link #DISTILL_VOLUME_LOSS} of the volume each pass, so over-distilling
     * shrinks the batch below the volume gate. Never touches the ceiling (can't wash dirt into a standard)
     * and never removes a named taint. Costs Enkephalin (charged by the caller). Mutates {@code pot}.
     */
    public static void distill(PotState pot) {
        double eff = Math.pow(DISTILL_DECAY, pot.distillPasses());
        pot.setNoise(pot.noise() * (1.0 - DISTILL_FRACTION * eff)); // purity up (diminishing per pass)
        pot.scaleCharge(1.0 - DISTILL_VOLUME_LOSS);                 // ...but it boils off volume (concentration)
        pot.incrementDistillPasses();
    }

    /**
     * Blend finished pots (the Manifold): sum charge, take the strictest ceiling, titer-weighted-average the
     * noise plus a small blend penalty, and pay a one-time opposition tax for any opposed pair the mix brings
     * together (the blender buffers once instead of every add). Returns a new pot; inputs are unchanged.
     */
    public static PotState blend(List<PotState> pots) {
        PotState out = new PotState();
        if (pots.isEmpty()) return out;

        double totalTiter = 0.0;
        double noiseAccum = 0.0;
        double stabAccum = 0.0;
        double ceiling = 100.0;
        int maxAdds = 0;
        int minPasses = Integer.MAX_VALUE;

        for (PotState p : pots) {
            double t = Math.max(p.titer(), 1.0e-9);
            for (Sin s : Sin.values()) out.addCharge(s, p.charge(s));
            noiseAccum += p.noise() * t;
            stabAccum += p.stability() * t;
            totalTiter += t;
            ceiling = Math.min(ceiling, p.ceiling());
            maxAdds = Math.max(maxAdds, p.adds());
            minPasses = Math.min(minPasses, p.distillPasses());
        }

        out.capCeiling(ceiling);
        out.setNoise(noiseAccum / totalTiter + BLEND_PENALTY);
        out.setStability(stabAccum / totalTiter);
        out.setAdds(maxAdds);
        out.setDistillPasses(minPasses == Integer.MAX_VALUE ? 0 : minPasses);

        applyOppositionDrain(out, OPPOSITION_BLEND);
        return out;
    }

    /**
     * Drift (a station tick): only bites below {@link #DRIFT_FLOOR}. An unstable pot's composition wanders a
     * hair toward noise and its contamination creeps — work briskly or buffer. {@code rng} picks the wander.
     */
    public static void drift(PotState pot, RandomGenerator rng) {
        if (pot.stability() >= DRIFT_FLOOR || pot.isBlank()) return;
        double severity = (DRIFT_FLOOR - pot.stability()) / DRIFT_FLOOR; // 0..1
        Sin wander = Sin.values()[rng.nextInt(Sin.COUNT)];
        pot.addCharge(wander, pot.titer() * 0.01 * severity);
        pot.addNoise(0.5 * severity);
    }
}
