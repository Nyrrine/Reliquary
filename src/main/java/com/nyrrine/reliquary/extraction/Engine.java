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
    public static final double DISTILL_VOLUME_LOSS = 0.03;
    /** How much a flux charge (Honeycomb) dampens opposition drain on the adds it covers. */
    public static final double FLUX_DRAIN_FACTOR = 0.3;
    /** Extra contamination a blend introduces. Kept small so clean stocks blend clean (§11) — a blend of
     *  Primary-Standard stocks must stay Primary Standard, or the catalyst path is dead. */
    public static final double BLEND_PENALTY = 0.25;
    /** Below this stability, an unstable pot drifts toward noise between touches. */
    public static final double DRIFT_FLOOR = 40.0;
    /** A single vial's volume ceiling — you run out of flask (anti-spam guard #4). Bigger = blend vials. */
    public static final double VIAL_CAP = 120.0;

    /** Chance an opposed pair (both shares ≥ this) sparks Dissonance on an add. */
    public static final double DISSONANCE_SHARE = 20.0;
    public static final double DISSONANCE_CHANCE = 0.25;

    // ---- spontaneous-ailment tuning (STARTER — variety layer, not material-locked) --
    /** Floor chance of a spontaneous ailment on any clean add — afflictions can strike even a pristine pot. */
    public static final double RANDOM_AIL_BASE = 0.04;
    /** How much accumulated noise raises the spontaneous-ailment chance (per point of noise). */
    public static final double RANDOM_AIL_NOISE_K = 0.003;
    /** How much instability (100 − stability) raises the spontaneous-ailment chance (per point). */
    public static final double RANDOM_AIL_INSTAB_K = 0.0015;
    /** Upper clamp on the per-add spontaneous-ailment chance. */
    public static final double RANDOM_AIL_MAX = 0.6;

    /** Base chance a distillation pass sparks a fault (concentrating can crash a solute out). */
    public static final double DISTILL_AIL_BASE = 0.05;
    /** How much noise raises the per-pass distill-ailment chance (per point of noise). */
    public static final double DISTILL_AIL_NOISE_K = 0.004;
    /** Upper clamp on the per-pass distill-ailment chance. */
    public static final double DISTILL_AIL_MAX = 0.5;

    // ---- blend-abuse punishment tuning (STARTER — super-linear in extra vials) ------
    /** Stability fatigue per extra vial in a blend (linear in N−1). */
    public static final double BLEND_STAB_FATIGUE = 5.0;
    /** Base of the blend random-ailment curve (scaled by (N−1)^1.7). */
    public static final double BLEND_AIL_BASE = 0.06;
    /** Upper clamp on the blend random-ailment chance. */
    public static final double BLEND_AIL_MAX = 0.90;

    /** Outcome of a single reagent addition, for the station to narrate. */
    public record AddResult(boolean full, boolean stepFailed, boolean breached, double stabilityAfter,
                            Taint inflicted, java.util.Set<Taint> cured) {}

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
            return new AddResult(true, false, pot.stability() <= 0.0, pot.stability(), null, java.util.Set.of());
        }
        double stabilityGoingIn = pot.stability();
        pot.incrementAdds();

        // Remedies act even on a "wasted" handling: applying a cure clears its taint cleanly. Do this first so
        // a cure always works, and note which taints it lifted.
        java.util.Set<Taint> cured = new java.util.HashSet<>();
        for (Taint t : Taint.values()) {
            if (r.cures(t) && pot.hasTaint(t)) { pot.clearTaint(t); cured.add(t); }
        }

        // Handling: every touch of the volatile pot dirties it a little (escalating). This lands whether or
        // not the reagent enters cleanly — you disturbed the batch either way.
        double handling = HANDLING_BASE * (1.0 + HANDLING_ESCALATION * (pot.adds() - 1));
        pot.addNoise(handling);

        // Step-failure roll — worse when you were already shaky and the reagent runs hot.
        double pFail = STEP_BASE * (1.0 - stabilityGoingIn / 100.0) * r.failFactor();
        boolean failed = rng.nextDouble() < pFail;

        Taint inflicted = null;
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

            // The reagent may taint the batch (only on a clean add — a slipped reagent never entered).
            if (r.inflicts() != null && rng.nextDouble() < r.inflicts().chance()) {
                pot.inflict(r.inflicts().taint());
                inflicted = r.inflicts().taint();
            }

            // Spontaneous-ailment layer (independent of the material): on top of the reagent-specific roll,
            // an ailment can arise on its own — more likely as the pot gets noisier and shakier — so
            // afflictions feel random and varied and even a clean pot is never fully safe. The taint chosen is
            // random among those not already present, so it isn't locked to the reagent. Prefer reporting it.
            double pSpontaneous = clamp(RANDOM_AIL_BASE
                    + RANDOM_AIL_NOISE_K * pot.noise()
                    + RANDOM_AIL_INSTAB_K * (100.0 - pot.stability()), 0.0, RANDOM_AIL_MAX);
            if (rng.nextDouble() < pSpontaneous) {
                Taint spontaneous = inflictRandomAbsentTaint(pot, rng);
                if (spontaneous != null) inflicted = spontaneous;
            }
        }

        // Opposition drain — the opposed pairs now coexisting bleed stability every touch.
        applyOppositionDrain(pot, OPPOSITION_DRAIN);

        // A pot straddling an opposition bridge can spark Dissonance.
        if (inflicted == null && !pot.hasTaint(Taint.DISSONANCE) && sparksDissonance(pot, rng)) {
            pot.inflict(Taint.DISSONANCE);
            inflicted = Taint.DISSONANCE;
        }

        boolean breached = pot.stability() <= 0.0;
        return new AddResult(false, failed, breached, pot.stability(), inflicted, cured);
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

    /** Inflict a taint chosen uniformly at random from those the pot does NOT already have, returning it
     *  (or {@code null} if the pot already carries every taint). Keeps ailments varied run-to-run. */
    private static Taint inflictRandomAbsentTaint(PotState pot, RandomGenerator rng) {
        java.util.List<Taint> absent = new java.util.ArrayList<>();
        for (Taint t : Taint.values()) if (!pot.hasTaint(t)) absent.add(t);
        if (absent.isEmpty()) return null;
        Taint pick = absent.get(rng.nextInt(absent.size()));
        pot.inflict(pick);
        return pick;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Whether the pot straddles an opposition bridge hard enough to risk Dissonance this add. */
    private static boolean sparksDissonance(PotState pot, RandomGenerator rng) {
        SinProfile p = pot.profile();
        for (Sin.Bridge b : Sin.bridges()) {
            if (p.get(b.a()) >= DISSONANCE_SHARE && p.get(b.b()) >= DISSONANCE_SHARE) {
                return rng.nextDouble() < DISSONANCE_CHANCE;
            }
        }
        return false;
    }

    /**
     * Advance active taints by {@code dtSeconds} (the station/testbed ticker): apply each taint's per-second
     * harm, then expire any whose timer ran out — locking in its permanent scar. Returns the taints that just
     * scarred, for the caller to announce. Mutates {@code pot}.
     */
    public static java.util.Set<Taint> tickTaints(PotState pot, double dtSeconds) {
        java.util.Set<Taint> scarred = new java.util.HashSet<>();
        for (Taint t : new java.util.ArrayList<>(pot.taints().keySet())) {
            if (t.perTickStab() != 0.0) pot.addStability(t.perTickStab() * dtSeconds);
            if (t.perTickNoise() != 0.0) pot.addNoise(t.perTickNoise() * dtSeconds);
            double remaining = pot.taints().get(t) - dtSeconds;
            if (remaining <= 0.0) {
                t.applyScar(pot);   // untreated in time → permanent scar sets in
                pot.clearTaint(t);
                scarred.add(t);
                // Compounding: an untreated scar can spontaneously develop a follow-on affliction.
                Taint compound = t.compoundsInto();
                if (compound != null && !pot.hasTaint(compound)) pot.inflict(compound);
            } else {
                pot.setTaintTime(t, remaining);
            }
        }
        return scarred;
    }

    /**
     * Distill (the Centrifuge): scrub a fraction of the noise with diminishing returns per pass — but it
     * concentrates, boiling off {@link #DISTILL_VOLUME_LOSS} of the volume each pass, so over-distilling
     * shrinks the batch below the volume gate. Never touches the ceiling (can't wash dirt into a standard)
     * and never removes a named taint. Costs Enkephalin (charged by the caller). Mutates {@code pot}.
     */
    public static boolean distill(PotState pot) {
        if (pot.anyTaintBlocksDistill()) return false; // Fever must be quenched first
        double eff = Math.pow(DISTILL_DECAY, pot.distillPasses());
        pot.setNoise(pot.noise() * (1.0 - DISTILL_FRACTION * eff)); // purity up (diminishing per pass)
        pot.scaleCharge(1.0 - DISTILL_VOLUME_LOSS);                 // ...but it boils off volume (concentration)
        pot.incrementDistillPasses();
        return true;
    }

    /**
     * Distill with a spontaneous-fault roll: does everything {@link #distill(PotState)} does, then — because
     * concentrating a batch can crash a solute out or spark a runaway — rolls a small chance to inflict a
     * random not-yet-present taint. The chance rises with the pot's noise. Only rolls if the pass succeeded.
     * {@code rng} drives the fault roll. Old callers of {@link #distill(PotState)} are unaffected.
     */
    public static boolean distill(PotState pot, RandomGenerator rng) {
        if (!distill(pot)) return false;
        double pDistillAil = clamp(DISTILL_AIL_BASE + DISTILL_AIL_NOISE_K * pot.noise(), 0.0, DISTILL_AIL_MAX);
        if (rng.nextDouble() < pDistillAil) inflictRandomAbsentTaint(pot, rng);
        return true;
    }

    /**
     * Blend finished pots (the Manifold): sum charge, take the strictest ceiling, titer-weighted-average the
     * noise, and pay a one-time opposition tax for any opposed pair the mix brings together (the blender
     * buffers once instead of every add). Returns a new pot; inputs are unchanged.
     *
     * <p>This no-rng entry point delegates to {@link #blend(List, RandomGenerator)} with a fixed-seed RNG so
     * it stays deterministic while still applying the full anti-abuse punishment described there. Callers that
     * want fresh randomness (the station) should call the rng overload directly.
     */
    public static PotState blend(List<PotState> pots) {
        return blend(pots, new java.util.Random(0L));
    }

    /**
     * Blend finished pots with the full <b>anti-abuse curve</b> — mega-merging many charged vials is actively
     * punished, super-linearly, so a blend of 2 clean stocks stays clean but stacking 5–6 is a real gamble:
     * <ul>
     *   <li><b>Escalating noise</b> {@code BLEND_PENALTY·(N−1)²} injected into the result (quadratic in extra
     *       vials — directly cuts purity): N=2→0.25, N=3→1.0, N=4→2.25, N=5→4.0, N=6→6.25.</li>
     *   <li><b>Stability fatigue</b> {@code BLEND_STAB_FATIGUE·(N−1)} subtracted from the blended stability.</li>
     *   <li><b>Rising ailment chance</b> {@code min(BLEND_AIL_MAX, BLEND_AIL_BASE·(N−1)^1.7)} to inflict a
     *       random not-yet-present taint (up to two for N≥5): N=2→~6%, N=3→~20%, N=4→~37%, N=5→~61%, N=6→~85%.</li>
     * </ul>
     * The one-time opposition tax still applies. {@code rng} drives the ailment roll and taint pick. Returns a
     * new pot; inputs are unchanged.
     */
    public static PotState blend(List<PotState> pots, RandomGenerator rng) {
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

        int n = pots.size();
        double blendNoise = BLEND_PENALTY * (n - 1) * (n - 1);   // quadratic in extra vials
        double blendStabHit = BLEND_STAB_FATIGUE * (n - 1);      // linear stability fatigue

        out.capCeiling(ceiling);
        out.setNoise(noiseAccum / totalTiter + blendNoise);
        out.setStability(stabAccum / totalTiter - blendStabHit);
        out.setAdds(maxAdds);
        out.setDistillPasses(minPasses == Integer.MAX_VALUE ? 0 : minPasses);

        applyOppositionDrain(out, OPPOSITION_BLEND);

        // Rising random-ailment chance — merging many charged vials is likely to crash something out.
        double pBlendAil = Math.min(BLEND_AIL_MAX, BLEND_AIL_BASE * Math.pow(n - 1, 1.7));
        if (rng.nextDouble() < pBlendAil) {
            inflictRandomAbsentTaint(out, rng);
            if (n >= 5 && rng.nextDouble() < pBlendAil) inflictRandomAbsentTaint(out, rng); // up to a 2nd
        }
        // Inherit afflictions (min remaining per taint) + flux — a blend must NOT launder taints away.
        int flux = 0;
        for (PotState p : pots) {
            flux = Math.max(flux, p.fluxCharges());
            for (var e : p.taints().entrySet()) out.taints().merge(e.getKey(), e.getValue(), Math::min);
        }
        out.setFluxCharges(flux);
        // The inserted catalyst is inherited — the first target among the blended vials carries forward (count too).
        for (PotState p : pots) if (p.catalystTarget() != null) {
            out.catalystTarget(p.catalystTarget());
            out.catalystCount(p.catalystCount());
            break;
        }
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
