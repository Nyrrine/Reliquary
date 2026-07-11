package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.format.TextColor;

/**
 * The analytical-chemistry grade ladder — how clean a Cogito is, derived purely from its purity.
 *
 * <p>Grade is the gate the Pocket Well reads: a pour can only manifest weapons whose required grade is
 * {@code <=} the cogito's grade (the hard grade floor — luck never crosses tiers). Ordinal order is the
 * ladder order, so {@link #atLeast(Grade)} / {@link #atMost(Grade)} express the floor directly.
 *
 * <p>The mind always keeps residual noise, so refining asymptotes to <b>Primary Standard</b> (99%); the
 * only way to {@link #CERTIFIED} (100%) is a catalyst locking a Primary Standard batch.
 */
public enum Grade {

    CRUDE            ("Crude",             0.0,   0x6E7A5A), // coin-flip material
    TECHNICAL        ("Technical",        70.0,   0x9AA36B), // cheap, low-grade E.G.O only
    REAGENT          ("Reagent",          85.0,   0x7FC24A), // reliable TETH/HE working stock
    ANALYTICAL       ("Analytical",       95.0,   0x4ADF7A), // tight targeting — what WAW demands
    PRIMARY_STANDARD ("Primary Standard", 99.0,   0x3BE2C0), // reference-quality; the refining cap
    CERTIFIED        ("Certified",       100.0,   0xFFD54A); // catalyst-locked, guaranteed manifest

    private final String display;
    private final double minPurity;
    private final TextColor color;

    Grade(String display, double minPurity, int rgb) {
        this.display = display;
        this.minPurity = minPurity;
        this.color = TextColor.color(rgb);
    }

    /** The grade band a given purity (0–100) falls into. */
    public static Grade of(double purity) {
        Grade result = CRUDE;
        for (Grade g : values()) {
            if (purity >= g.minPurity - 1.0e-9) result = g;
        }
        return result;
    }

    /** Human-readable name (e.g. {@code "Analytical"}). */
    public String display() { return display; }

    /** Lowest purity that qualifies for this grade. */
    public double minPurity() { return minPurity; }

    /** Tier color for lore / gauges. */
    public TextColor color() { return color; }

    /** Whether this grade is at or above {@code other} on the ladder (the grade-floor test). */
    public boolean atLeast(Grade other) { return ordinal() >= other.ordinal(); }

    /** Whether this grade is at or below {@code other} on the ladder. */
    public boolean atMost(Grade other) { return ordinal() <= other.ordinal(); }
}
