package com.nyrrine.reliquary.extraction;

import java.util.random.RandomGenerator;

/**
 * The Pocket Well resolution (§28) — pure math over a poured {@link PotState}. Given the pour's grade,
 * volume and composition (plus an optional signature-lock catalyst and the Well's accumulated residue),
 * it lands on one of three rungs:
 *
 * <ol>
 *   <li>{@link Outcome#MANIFEST} — you get the weapon. Certified (catalyst-locked at Primary Standard) is a
 *       guaranteed manifest of the catalyst's target.</li>
 *   <li>{@link Outcome#NEAR_MISS} — no weapon; the nearest reachable neighbour, floored to your grade band
 *       (never a lucky tier-up).</li>
 *   <li>{@link Outcome#BREACH} — the Well ruptures and an Abnormality climbs out, scaled to the grade you
 *       reached for (loading a WAW catalyst onto crude cogito can breach a WAW Abnormality).</li>
 * </ol>
 *
 * <p>Two hard gates run before the roll: the <b>grade floor</b> (a weapon whose {@link EgoGrade} needs a
 * higher cogito grade than you have is not in the bucket at all) and the <b>volume gate</b>. Luck only ever
 * picks <i>which</i> same-band weapon — it never crosses tiers.
 */
public final class WellRoll {

    private WellRoll() {}

    /** Below this match, a failed pour always breaches rather than near-missing. */
    public static final double NEARMISS_MIN = 0.5;
    /** A catalyst only locks onto its target if the pour is at least this close to it. */
    public static final double SNAP_MIN = 0.85;
    /** Purity at/above which a locked catalyst certifies the pour to a guaranteed manifest. */
    public static final double CERTIFIED_PURITY = Grade.PRIMARY_STANDARD.minPurity();

    // Breach-chance weights (§28.6) — how a failed pour tips toward rupture.
    public static final double BREACH_BASE = 0.05;
    public static final double BREACH_W_MATCH = 0.45;
    public static final double BREACH_W_PURITY = 0.25;
    public static final double BREACH_W_STAB = 0.15;
    public static final double BREACH_W_RESIDUE = 0.30;

    /** Which rung a pour resolved to. */
    public enum Outcome { MANIFEST, NEAR_MISS, BREACH }

    /**
     * The result of a pour.
     *
     * @param outcome     which rung
     * @param weapon      the manifested / near-missed weapon (null only on a blank-pour breach)
     * @param match       best match achieved, {@code [0,1]}
     * @param certified   whether a catalyst guaranteed the manifest
     * @param cogitoGrade the analytical grade of the pour
     * @param aimedGrade  the E.G.O grade reached for — sets breach severity
     */
    public record Result(Outcome outcome, WeaponSpec weapon, double match, boolean certified,
                         Grade cogitoGrade, EgoGrade aimedGrade) {}

    /**
     * Resolve a pour. {@code catalystTargetId} may be null (no catalyst); {@code wellResidue} in {@code [0,1]}
     * is the Well's spray-and-pray taint (raises breach chance); {@code rng} drives the manifest/breach rolls.
     */
    public static Result resolve(PotState pour, String catalystTargetId, double wellResidue,
                                 RandomGenerator rng) {
        double purity = pour.purity();
        Grade cogitoGrade = Grade.of(purity);
        double titer = pour.titer();
        SinProfile profile = pour.profile();

        // Best reachable weapon by match (grade floor + volume gate applied).
        WeaponSpec best = null;
        double bestMatch = -1.0;
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (!w.reachableBy(cogitoGrade, titer)) continue;
            double m = w.matchOf(profile);
            if (m > bestMatch) { bestMatch = m; best = w; }
        }

        // Catalyst: if its target is reachable and the pour is close enough, it locks onto that target.
        WeaponSpec target = catalystTargetId == null ? null : WeaponSignatures.byId(catalystTargetId);
        boolean locked = false;
        if (target != null && target.reachableBy(cogitoGrade, titer)) {
            double tm = target.matchOf(profile);
            if (tm >= SNAP_MIN) { best = target; bestMatch = tm; locked = true; }
        }

        // The grade you reached for — a catalyst declares intent even if unreachable (over-reach → breach).
        EgoGrade aimed = target != null ? target.grade()
                : (best != null ? best.grade() : EgoGrade.ZAYIN);

        if (best == null) {
            // Nothing in the bucket — a hollow pour just ruptures at the aimed tier.
            return new Result(Outcome.BREACH, null, 0.0, false, cogitoGrade, aimed);
        }

        // Success = match × grade; a locked catalyst at Primary Standard certifies to a guaranteed manifest.
        boolean certified = locked && purity >= CERTIFIED_PURITY - 1.0e-9;
        double pSuccess = certified ? 1.0 : clamp01(bestMatch * (purity / 100.0));

        if (rng.nextDouble() < pSuccess) {
            return new Result(Outcome.MANIFEST, best, bestMatch, certified, cogitoGrade, best.grade());
        }

        // Failed the manifest — decide near-miss vs breach.
        double breachChance = clamp01(BREACH_BASE
                + BREACH_W_MATCH * (1.0 - bestMatch)
                + BREACH_W_PURITY * (1.0 - purity / 100.0)
                + BREACH_W_STAB * (1.0 - pour.stability() / 100.0)
                + BREACH_W_RESIDUE * clamp01(wellResidue));

        if (bestMatch < NEARMISS_MIN || rng.nextDouble() < breachChance) {
            return new Result(Outcome.BREACH, best, bestMatch, false, cogitoGrade, aimed);
        }
        return new Result(Outcome.NEAR_MISS, best, bestMatch, false, cogitoGrade, best.grade());
    }

    private static double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }
}
