package com.nyrrine.reliquary.extraction;

import java.util.ArrayList;
import java.util.List;
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
    /** How sharply the gacha favours your best-matched weapon (weight = match^this). Higher = more targeted,
     *  lower = more of a lottery across the whole reachable pool. */
    public static final double GACHA_SHARPNESS = 8.0;
    /** Steepness of the yield curve: whether a pour produces a weapon at all is {@code (match × purity)^this}.
     *  Above 1 it punishes mediocre pours and rewards well-crafted ones — so crafting clearly beats RNG. */
    public static final double SUCCESS_STEEPNESS = 2.0;
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
     * @param purity      the pour's purity (0–100) — for the attribution stamp
     * @param aimedGrade  the E.G.O grade reached for — sets breach severity
     */
    public record Result(Outcome outcome, WeaponSpec weapon, double match, boolean certified,
                         Grade cogitoGrade, double purity, EgoGrade aimedGrade) {}

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

        List<Chance> pool = pool(pour); // reachable weapons + their pull odds, best first

        // Catalyst: a reachable target you're already close to is LOCKED — a guaranteed pull (the RNG-cut).
        WeaponSpec target = catalystTargetId == null ? null : WeaponSignatures.byId(catalystTargetId);
        boolean locked = target != null && target.reachableBy(cogitoGrade, titer)
                && target.matchOf(profile) >= SNAP_MIN;

        // The grade you reached for — a catalyst declares intent even if unreachable (over-reach → breach).
        EgoGrade aimed = target != null ? target.grade()
                : (pool.isEmpty() ? EgoGrade.ZAYIN : pool.get(0).weapon().grade());

        if (locked) {
            // Guaranteed manifest of the target; Primary Standard purity also stamps it Certified (100%).
            boolean certified = purity >= CERTIFIED_PURITY - 1.0e-9;
            return new Result(Outcome.MANIFEST, target, target.matchOf(profile), certified,
                    cogitoGrade, purity, target.grade());
        }

        if (pool.isEmpty()) {
            // Nothing cleared the gates — a hollow pour just ruptures at the aimed tier.
            return new Result(Outcome.BREACH, null, 0.0, false, cogitoGrade, purity, aimed);
        }

        double bestMatch = pool.get(0).match();

        // Does the pour manifest anything at all? Quality (best match × purity) on a STEEP curve — a mediocre
        // gamble usually fails, a well-crafted pour reliably yields. This is where crafting beats RNG.
        double pSuccess = Math.pow(clamp01(bestMatch * (purity / 100.0)), SUCCESS_STEEPNESS);
        if (rng.nextDouble() < pSuccess) {
            // The gacha: pull a weapon from the pool weighted by how well the mix matches each. Your cogito
            // tilts the odds toward what you shaped it for; the roll still decides which one you actually get.
            WeaponSpec pulled = weightedPick(pool, rng);
            return new Result(Outcome.MANIFEST, pulled, bestMatch, false, cogitoGrade, purity, pulled.grade());
        }

        // Failed the pull — near-miss (nearest neighbour) or breach.
        double breachChance = clamp01(BREACH_BASE
                + BREACH_W_MATCH * (1.0 - bestMatch)
                + BREACH_W_PURITY * (1.0 - purity / 100.0)
                + BREACH_W_STAB * (1.0 - pour.stability() / 100.0)
                + BREACH_W_RESIDUE * clamp01(wellResidue));

        WeaponSpec nearest = pool.get(0).weapon();
        if (bestMatch < NEARMISS_MIN || rng.nextDouble() < breachChance) {
            return new Result(Outcome.BREACH, nearest, bestMatch, false, cogitoGrade, purity, aimed);
        }
        return new Result(Outcome.NEAR_MISS, nearest, bestMatch, false, cogitoGrade, purity, nearest.grade());
    }

    /** A weapon's odds of being pulled from the Well right now: its composition match and normalized pull %. */
    public record Chance(WeaponSpec weapon, double match, double odds) {}

    /**
     * The reachable loot pool with each weapon's normalized pull odds, best first. A weapon's weight is its
     * match raised to {@link #GACHA_SHARPNESS} — so shaping your cogito toward a target sharply tilts the
     * gacha in its favour, while the rest of the pool keeps a real (smaller) chance. Only weapons that clear
     * the grade floor + volume gate are in the pool.
     */
    public static List<Chance> pool(PotState pour) {
        Grade g = Grade.of(pour.purity());
        double titer = pour.titer();
        SinProfile prof = pour.profile();

        List<Chance> weighted = new ArrayList<>();
        double total = 0.0;
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (!w.reachableBy(g, titer)) continue;
            double m = w.matchOf(prof);
            double wt = Math.pow(Math.max(0.0, m), GACHA_SHARPNESS);
            weighted.add(new Chance(w, m, wt)); // stash the raw weight in odds for now
            total += wt;
        }
        List<Chance> out = new ArrayList<>();
        for (Chance c : weighted) {
            out.add(new Chance(c.weapon(), c.match(), total > 0.0 ? c.odds() / total : 0.0));
        }
        out.sort((a, b) -> Double.compare(b.odds(), a.odds()));
        return out;
    }

    /** The chance this pour yields a weapon at all (before which one) — the steep quality curve. 0 if nothing
     *  is reachable. A catalyst-locked pour ignores this (guaranteed). */
    public static double successChance(PotState pour) {
        List<Chance> pool = pool(pour);
        if (pool.isEmpty()) return 0.0;
        double quality = Math.max(0.0, Math.min(1.0, pool.get(0).match() * pour.purity() / 100.0));
        return Math.pow(quality, SUCCESS_STEEPNESS);
    }

    private static WeaponSpec weightedPick(List<Chance> pool, RandomGenerator rng) {
        double r = rng.nextDouble();
        double cum = 0.0;
        for (Chance c : pool) {
            cum += c.odds();
            if (r < cum) return c.weapon();
        }
        return pool.get(pool.size() - 1).weapon();
    }

    private static double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }
}
