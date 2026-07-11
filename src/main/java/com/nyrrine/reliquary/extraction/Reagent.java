package com.nyrrine.reliquary.extraction;

/**
 * A single reagent — one thing you can add to a pot at the Censer. Pure data; the {@link Engine} applies it.
 *
 * <p>A reagent shifts composition ({@link #delta}, signed per sin), drips a little contamination
 * ({@link #contam}), moves the breach gauge ({@link #stab}, signed — negative costs stability, positive
 * buffers it), and — the primary balancing rule — <b>caps the batch's purity ceiling at its {@link Tier}</b>:
 * your worst reagent caps the whole batch, so you cannot distill dirt into a standard.
 *
 * <p>Special forms: a {@link Roll volatile} reagent rolls its magnitude in a range each use (unpredictable),
 * a {@link #chargeScale} below 1 dilutes/concentrates all charge (solvents), and a {@link #noiseScale} below
 * 1 washes accumulated noise (the milk panic-reset). Build with the fluent {@link #of(String, String)}.
 */
public record Reagent(
        String id,
        String display,
        double[] delta,      // length Sin.COUNT — additive per-sin shift
        double contam,       // additive contamination (purity hit)
        double stab,         // signed stability delta (− = cost, + = buffer)
        Tier tier,           // caps the batch ceiling + drives step-failure odds
        Roll roll,           // nullable volatile magnitude roll
        double chargeScale,  // 1.0 = none; < 1 dilutes all charge (solvents)
        double noiseScale,   // 1.0 = none; < 1 washes accumulated noise (milk)
        String source) {     // lectern grind text

    /** Reagent purity tiers: the ceiling each permits and how failure-prone it is to handle. */
    public enum Tier {
        CRUDE   (85.0, 0.3),  // dirty bulk — spam-safe, low ceiling
        REFINED (95.0, 0.6),  // light processing
        PURE    (99.0, 1.2),  // unstable — skill check
        STANDARD(99.9, 1.6),  // signature-grade — hard skill check
        UTILITY (100.0, 0.2); // buffers / solvents — no ceiling cap, gentle to handle

        private final double ceiling;
        private final double failFactor;

        Tier(double ceiling, double failFactor) {
            this.ceiling = ceiling;
            this.failFactor = failFactor;
        }

        /** Purity this tier caps a batch at. */
        public double ceiling() { return ceiling; }

        /** Multiplier on step-failure odds when handling a reagent of this tier. */
        public double failFactor() { return failFactor; }
    }

    /** A volatile magnitude roll: on each add, roll {@code [min,max]} into {@code sin}. */
    public record Roll(Sin sin, double min, double max) {}

    /** The purity this reagent caps the batch at (its tier's ceiling). */
    public double tierCeiling() { return tier.ceiling(); }

    /** This reagent's step-failure factor (its tier's). */
    public double failFactor() { return tier.failFactor(); }

    /** Whether it rolls a volatile magnitude each use. */
    public boolean isVolatile() { return roll != null; }

    // ---- builder -------------------------------------------------------------------

    /** Start building a reagent {@code id} shown as {@code display}. */
    public static Builder of(String id, String display) { return new Builder(id, display); }

    /** Fluent assembler — defaults to no shift, no contamination, no stab change, UTILITY tier. */
    public static final class Builder {
        private final String id;
        private final String display;
        private final double[] delta = new double[Sin.COUNT];
        private double contam = 0.0;
        private double stab = 0.0;
        private Tier tier = Tier.UTILITY;
        private Roll roll = null;
        private double chargeScale = 1.0;
        private double noiseScale = 1.0;
        private String source = "";

        private Builder(String id, String display) {
            this.id = id;
            this.display = display;
        }

        public Builder delta(Sin s, double amount) { delta[s.index()] += amount; return this; }
        public Builder contam(double c) { this.contam = c; return this; }
        public Builder stab(double s) { this.stab = s; return this; }
        public Builder tier(Tier t) { this.tier = t; return this; }
        public Builder roll(Sin s, double min, double max) { this.roll = new Roll(s, min, max); return this; }
        public Builder chargeScale(double f) { this.chargeScale = f; return this; }
        public Builder noiseScale(double f) { this.noiseScale = f; return this; }
        public Builder source(String src) { this.source = src; return this; }

        public Reagent build() {
            return new Reagent(id, display, delta, contam, stab, tier, roll, chargeScale, noiseScale, source);
        }
    }
}
