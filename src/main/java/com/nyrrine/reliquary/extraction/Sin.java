package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.format.TextColor;

import java.util.List;

/**
 * The seven sins — the emotion palette every Cogito is composed of, and the axes of the whole
 * extraction chemistry.
 *
 * <p>A pot's composition is a non-negative <b>charge</b> per sin (see {@link PotState}); the normalized
 * ratio is a {@link SinProfile}. Sins are indexed by {@link #index()} (== {@link #ordinal()}) so the
 * engine can carry composition as a flat {@code double[}{@link #COUNT}{@code ]} keyed by sin — the
 * declaration order here <b>is</b> the array layout and must stay stable.
 *
 * <p><b>Clusters</b> author the difficulty curve. Sins in the same {@link Cluster} resonate (stabilize
 * together); the {@link Cluster#HUB hub} sin, Gluttony, resonates with everything and is the safe filler.
 * Three <b>opposition bridges</b> — Pride✕Gloom, Wrath✕Sloth, Lust✕Envy — bleed stability fast when both
 * halves share one pot, which is what forces cross-axis weapons onto the stock-solution / buffer method.
 *
 * <p>Label colors follow Limbus' canonical sin spectrum (ROYGBIV): Wrath red, Lust orange, Sloth yellow,
 * Gluttony green, Gloom blue, Pride indigo, Envy violet. These tint the assay bars only — the Cogito
 * <i>potion</i> is always green (shade = purity, never emotion; see {@link Cogito}).
 */
public enum Sin {

    // Declaration order is the charge-array layout — do not reorder.
    // HOT cluster — mutually resonant.
    WRATH   (Cluster.HOT,  0xE23B3B, "Wrath"),    // red
    PRIDE   (Cluster.HOT,  0x6A5ACD, "Pride"),    // indigo
    LUST    (Cluster.HOT,  0xE8862E, "Lust"),     // orange
    // COLD cluster — mutually resonant.
    GLOOM   (Cluster.COLD, 0x3B6FE2, "Gloom"),    // blue
    SLOTH   (Cluster.COLD, 0xE8D23B, "Sloth"),    // yellow
    ENVY    (Cluster.COLD, 0x9B3BE2, "Envy"),     // violet
    // Neutral hub — resonates with everything.
    GLUTTONY(Cluster.HUB,  0x3BE26A, "Gluttony"); // green

    /** Number of sins — the width of every charge/profile array. */
    public static final int COUNT = 7;

    /** Resonance grouping: same-cluster sins stabilize together; the hub is safe with all. */
    public enum Cluster { HOT, COLD, HUB }

    /** An opposition pair: sharing one pot bleeds stability (the cross-axis tax). */
    public record Bridge(Sin a, Sin b) {}

    private final Cluster cluster;
    private final TextColor color;
    private final String display;
    private Sin opposite; // wired in the static block below (enums can't cross-reference in ctors)

    Sin(Cluster cluster, int rgb, String display) {
        this.cluster = cluster;
        this.color = TextColor.color(rgb);
        this.display = display;
    }

    private static final List<Bridge> BRIDGES = List.of(
            new Bridge(PRIDE, GLOOM),
            new Bridge(WRATH, SLOTH),
            new Bridge(LUST, ENVY));

    static {
        for (Bridge b : BRIDGES) {
            b.a().opposite = b.b();
            b.b().opposite = b.a();
        }
    }

    /** Stable array index for this sin (equal to {@link #ordinal()}). */
    public int index() { return ordinal(); }

    /** Resonance cluster this sin belongs to. */
    public Cluster cluster() { return cluster; }

    /** ROYGBIV label color for assay bars (not the potion — Cogito is always green). */
    public TextColor color() { return color; }

    /** Human-readable name (e.g. {@code "Gloom"}). */
    public String display() { return display; }

    /** The sin this one opposes across a bridge, or {@code null} for the hub (Gluttony). */
    public Sin opposite() { return opposite; }

    /** Whether {@code other} is this sin's opposition-bridge partner. */
    public boolean opposes(Sin other) { return opposite != null && opposite == other; }

    /** The three opposition bridges — Pride✕Gloom, Wrath✕Sloth, Lust✕Envy. */
    public static List<Bridge> bridges() { return BRIDGES; }
}
