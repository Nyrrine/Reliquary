package com.nyrrine.reliquary.extraction;

/**
 * The full mutable state of a single Cogito pot — the chemistry engine operates on this, and
 * {@link Cogito} serializes it to/from the potion's persistent data.
 *
 * <p>The <b>charge vector</b> is the source of truth: a non-negative raw amount per {@link Sin}. Everything
 * a player reads is derived from it plus three scalars:
 * <ul>
 *   <li>{@link #titer()} — total charge (concentration / volume). Diluting scales all charge down.</li>
 *   <li>{@link #profile()} — the normalized 7-sin ratio ({@link SinProfile}).</li>
 *   <li>{@link #purity()} — {@code min(ceiling, 100 - noise)}; {@link #grade()} bands it.</li>
 * </ul>
 *
 * <p>Handling accounting: {@link #ceiling} is the best purity still reachable (your worst reagent caps it),
 * {@link #noise} is accumulated contamination, {@link #stability} is the breach gauge, and {@link #adds}
 * counts reagent additions to drive escalating handling contamination. This class is pure data — no rolls,
 * no reagent logic; that lands in the Phase-2 engine.
 */
public final class PotState {

    private final double[] charge = new double[Sin.COUNT];
    private double ceiling = 100.0;   // max reachable purity — only ever lowered
    private double noise = 0.0;       // accumulated contamination
    private double stability = 100.0; // breach gauge, 0–100
    private int adds = 0;             // reagent additions so far

    /** A fresh blank pot: no composition, pristine gauges. */
    public PotState() {}

    // ---- charge -------------------------------------------------------------------

    /** Raw charge of a single sin. */
    public double charge(Sin s) { return charge[s.index()]; }

    /** Direct access to the backing charge array (length {@link Sin#COUNT}) for the engine. */
    public double[] chargeArray() { return charge; }

    /** Set a sin's charge, clamped to non-negative. */
    public void setCharge(Sin s, double value) { charge[s.index()] = Math.max(0.0, value); }

    /** Add (or, with a negative delta, burn off) a sin's charge; never drops below zero. */
    public void addCharge(Sin s, double delta) { setCharge(s, charge[s.index()] + delta); }

    /** Scale all charge by a factor (dilution / concentration). */
    public void scaleCharge(double factor) {
        for (int i = 0; i < Sin.COUNT; i++) charge[i] = Math.max(0.0, charge[i] * factor);
    }

    // ---- scalars ------------------------------------------------------------------

    public double ceiling() { return ceiling; }

    /** Lower the ceiling to {@code cap} if it is stricter (worst reagent caps the batch). */
    public void capCeiling(double cap) { ceiling = Math.min(ceiling, cap); }

    public double noise() { return noise; }
    public void setNoise(double v) { noise = Math.max(0.0, v); }
    public void addNoise(double delta) { setNoise(noise + delta); }

    public double stability() { return stability; }
    public void setStability(double v) { stability = clamp(v, 0.0, 100.0); }
    public void addStability(double delta) { setStability(stability + delta); }

    public int adds() { return adds; }
    public void setAdds(int v) { adds = Math.max(0, v); }
    public void incrementAdds() { adds++; }

    // ---- derived ------------------------------------------------------------------

    /** Total charge across all sins — the pot's concentration / volume. */
    public double titer() {
        double sum = 0.0;
        for (double c : charge) sum += c;
        return sum;
    }

    /** The normalized 7-sin composition. */
    public SinProfile profile() { return SinProfile.fromCharge(charge); }

    /** Effective purity: capped by the ceiling, cut by noise. */
    public double purity() { return Math.max(0.0, Math.min(ceiling, 100.0 - noise)); }

    /** Grade band of the current {@link #purity()}. */
    public Grade grade() { return Grade.of(purity()); }

    /** Whether the pot holds any composition at all. */
    public boolean isBlank() { return titer() <= 1.0e-9; }

    /** A deep copy — for what-if projections at the lectern and headless tests. */
    public PotState copy() {
        PotState c = new PotState();
        System.arraycopy(charge, 0, c.charge, 0, Sin.COUNT);
        c.ceiling = ceiling;
        c.noise = noise;
        c.stability = stability;
        c.adds = adds;
        return c;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
