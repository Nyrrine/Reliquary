package com.nyrrine.reliquary.extraction;

import java.util.StringJoiner;

/**
 * An immutable, normalized 7-sin composition — the ratio view of a pot (percentages summing to 100, or
 * all-zero for a blank pot). Weapon signatures are also {@code SinProfile}s, so "how close is this pot to
 * that weapon" is just a {@link #distance(SinProfile) distance} in 7-space.
 *
 * <p>The engine's source of truth is the raw charge vector on {@link PotState}; a profile is derived from
 * it via {@link #fromCharge(double[])}. Build signatures fluently with {@link #builder()}.
 */
public final class SinProfile {

    /** Max possible distance between two normalized profiles (two disjoint single-sin points). */
    public static final double DIST_MAX = 100.0 * Math.sqrt(2.0);

    private static final SinProfile EMPTY = new SinProfile(new double[Sin.COUNT]);

    private final double[] pct; // length COUNT, sums to 100 (or all 0)

    private SinProfile(double[] pct) {
        this.pct = pct;
    }

    /** The blank profile (all zeros). */
    public static SinProfile empty() { return EMPTY; }

    /** Normalize a raw charge vector to a percentage profile. Negative/zero totals yield {@link #empty()}. */
    public static SinProfile fromCharge(double[] charge) {
        double total = 0.0;
        for (double c : charge) total += Math.max(0.0, c);
        if (total <= 1.0e-9) return EMPTY;
        double[] p = new double[Sin.COUNT];
        for (int i = 0; i < Sin.COUNT; i++) p[i] = Math.max(0.0, charge[i]) / total * 100.0;
        return new SinProfile(p);
    }

    /** Percentage (0–100) of a given sin in this profile. */
    public double get(Sin s) { return pct[s.index()]; }

    /** Whether this profile carries no composition. */
    public boolean isEmpty() {
        for (double v : pct) if (v > 1.0e-9) return false;
        return true;
    }

    /** The largest-share sin, or {@code null} if empty. */
    public Sin dominant() {
        Sin best = null;
        double bestVal = 0.0;
        for (Sin s : Sin.values()) {
            if (pct[s.index()] > bestVal) { bestVal = pct[s.index()]; best = s; }
        }
        return best;
    }

    /** Euclidean distance to another profile in 7-space (0 = identical, up to {@link #DIST_MAX}). */
    public double distance(SinProfile other) {
        double sum = 0.0;
        for (int i = 0; i < Sin.COUNT; i++) {
            double d = pct[i] - other.pct[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** Match score in {@code [0,1]}: {@code 1 - distance/DIST_MAX}. Used by the Well roll. */
    public double match(SinProfile other) {
        return Math.max(0.0, 1.0 - distance(other) / DIST_MAX);
    }

    /**
     * Titer-weighted blend of several profiles (the Manifold operation): reconstruct each profile's
     * contribution as {@code profile × titer}, sum, and re-normalize. Pure single-sin stocks blend clean.
     */
    public static SinProfile blend(SinProfile[] profiles, double[] titers) {
        double[] charge = new double[Sin.COUNT];
        for (int i = 0; i < profiles.length; i++) {
            double t = titers[i];
            for (int s = 0; s < Sin.COUNT; s++) charge[s] += profiles[i].pct[s] * t / 100.0;
        }
        return fromCharge(charge);
    }

    /** {@code "Gloom 70 / Sloth 20 / Envy 10"} — non-zero shares, descending, rounded. */
    public String format() {
        if (isEmpty()) return "(blank)";
        StringJoiner sj = new StringJoiner(" / ");
        // simple descending pass; COUNT is tiny so an O(n^2) select is fine
        boolean[] used = new boolean[Sin.COUNT];
        for (int k = 0; k < Sin.COUNT; k++) {
            int pick = -1;
            double best = 0.5; // skip shares that round to 0
            for (Sin s : Sin.values()) {
                if (!used[s.index()] && pct[s.index()] > best) { best = pct[s.index()]; pick = s.index(); }
            }
            if (pick < 0) break;
            used[pick] = true;
            sj.add(Sin.values()[pick].display() + " " + Math.round(pct[pick]));
        }
        return sj.toString();
    }

    /** Fluent builder for fixed compositions (weapon signatures, stock targets). */
    public static Builder builder() { return new Builder(); }

    /** Accumulates raw amounts per sin, normalized on {@link #build()}. */
    public static final class Builder {
        private final double[] charge = new double[Sin.COUNT];

        public Builder add(Sin s, double amount) {
            charge[s.index()] += amount;
            return this;
        }

        public SinProfile build() { return fromCharge(charge); }
    }
}
