package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.format.TextColor;

/**
 * An affliction a brewing Cogito can develop — the heart of the "treat a live mind" loop. A taint shows a
 * <b>symptom</b> (it tints the vial, {@link #color()}), ticks down a <b>timer</b> in real time, and does its
 * <b>per-second harm</b> while active. Cure it (add its {@link #cureId()} reagent) before the timer expires
 * and it clears with no trace; let the timer run out and its {@link #scar}—a permanent stat hit—sets in.
 *
 * <p>Pure data so the engine applies every taint generically and new ones (Wave 3) are just more entries.
 */
public enum Taint {

    //      display         rgb        timer  /s stab /s noise  blocksDistill  scar: stab noise ceil  cure              symptom                                     compoundsInto
    // BLEEDING/FRACTURE are declared before TOXIN/FEVER so those can reference them as their compound (backward ref).
    BLEEDING   ("Leaching",        0x8B2E2E, 14.0,  -1.5,   0.0,     false,          -10.0,  0.0,   0.0,  "slime_ball",     "solute bleeding out — you're losing charge every second",   null),
    FRACTURE   ("Fissuring",       0xB0A8B8, 10.0,  -1.0,   0.0,     false,          -16.0,  0.0,   0.0,  "amethyst_shard", "the batch's structure is cracking — buffer it with amethyst", null),
    TOXIN      ("Contamination",   0xA6C33A, 12.0,  -2.0,   0.0,     false,          -18.0,  0.0,   0.0,  "nether_wart",    "a reactive impurity is eating charge every second",         BLEEDING),
    SEDIMENT   ("Precipitate",     0x6B5A3A, 20.0,   0.0,   0.8,     false,            0.0,  0.0,  -8.0,  "bone_meal",      "solids dropping out of solution — purity fouls each second", null),
    FEVER      ("Exotherm",        0xE0533B, 15.0,   0.0,   0.0,     true,             0.0,  6.0,   0.0,  "snowball",       "runaway heat — the batch is boiling itself dirty; quench it", FRACTURE),
    DISSONANCE ("Phase Separation",0x8A4FE0, 12.0,  -3.0,   0.0,     false,          -14.0,  0.0,   0.0,  "honeycomb",      "immiscible phases splitting apart — charge bleeds fast",     null),
    CHILL      ("Vitrification",   0x9EC6E0, 18.0,   0.0,   0.5,     false,            0.0,  5.0,   0.0,  "blaze_powder",   "the mix is glassing over — sluggish and setting solid",      null),
    GRIEF_BLOOM("Efflorescence",   0x3B3B8A, 12.0,   0.0,   1.0,     false,            0.0,  8.0,   0.0,  "honey_bottle",   "a crystalline bloom is creeping across the surface",        null);

    private final String display;
    private final int rgb;
    private final double timerSec;
    private final double perTickStab;   // per second while active
    private final double perTickNoise;  // per second while active
    private final boolean blocksDistill;
    private final double scarStab;      // applied once if it expires untreated
    private final double scarNoise;
    private final double scarCeiling;
    private final String cureId;        // reagent id that clears it cleanly
    private final String symptom;
    private final Taint compoundsInto;  // taint that spontaneously develops if this one scars untreated (or null)

    Taint(String display, int rgb, double timerSec, double perTickStab, double perTickNoise,
          boolean blocksDistill, double scarStab, double scarNoise, double scarCeiling,
          String cureId, String symptom, Taint compoundsInto) {
        this.display = display;
        this.rgb = rgb;
        this.timerSec = timerSec;
        this.perTickStab = perTickStab;
        this.perTickNoise = perTickNoise;
        this.blocksDistill = blocksDistill;
        this.scarStab = scarStab;
        this.scarNoise = scarNoise;
        this.scarCeiling = scarCeiling;
        this.cureId = cureId;
        this.symptom = symptom;
        this.compoundsInto = compoundsInto;
    }

    public String display() { return display; }
    public int rgb() { return rgb; }
    public TextColor color() { return TextColor.color(rgb); }
    public double timerSec() { return timerSec; }
    public double perTickStab() { return perTickStab; }
    public double perTickNoise() { return perTickNoise; }
    public boolean blocksDistill() { return blocksDistill; }
    public String cureId() { return cureId; }
    public String symptom() { return symptom; }
    /** Taint that spontaneously develops if this one scars untreated, or {@code null}. */
    public Taint compoundsInto() { return compoundsInto; }

    /** Lock in this taint's permanent damage — called when its timer expires untreated. */
    public void applyScar(PotState pot) {
        if (scarStab != 0.0) pot.addStability(scarStab);
        if (scarNoise != 0.0) pot.addNoise(scarNoise);
        if (scarCeiling != 0.0) pot.capCeiling(pot.ceiling() + scarCeiling); // scarCeiling is negative
    }

    /** Look up a taint by its lowercase name, or {@code null}. */
    public static Taint byId(String id) {
        for (Taint t : values()) if (t.name().equalsIgnoreCase(id)) return t;
        return null;
    }
}
