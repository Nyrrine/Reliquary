package com.nyrrine.reliquary.extraction;

/**
 * The E.G.O rarity grade of a weapon (Project Moon's ZAYIN → ALEPH ladder) — distinct from the analytical
 * {@link Grade} of a cogito. Each E.G.O grade sets the two hard gates the Pocket Well enforces before a
 * weapon can even enter the roll: the <b>minimum cogito grade</b> (the grade floor — luck never crosses
 * tiers) and the <b>minimum volume</b> (deeper extractions demand a bigger charge; a vial is ~120 titer).
 */
public enum EgoGrade {

    ZAYIN("ZAYIN", Grade.TECHNICAL,         30.0),   // a quarter-vial of technical stock
    TETH ("TETH",  Grade.REAGENT,           60.0),   // ~half a vial of reliable working stock
    HE   ("HE",    Grade.REAGENT,          150.0),   // ~1.3 vials — reagent-grade, a small stockpile
    WAW  ("WAW",   Grade.ANALYTICAL,       300.0),   // ~2.5 vials of tight, analytical-grade cogito
    ALEPH("ALEPH", Grade.PRIMARY_STANDARD, 600.0);   // ~5 vials at the refining cap — the ceiling of the craft

    private final String display;
    private final Grade minCogito;
    private final double minVolume;

    EgoGrade(String display, Grade minCogito, double minVolume) {
        this.display = display;
        this.minCogito = minCogito;
        this.minVolume = minVolume;
    }

    /** Display label (e.g. {@code "WAW"}). */
    public String display() { return display; }

    /** Lowest cogito grade that can extract this tier — the grade floor. */
    public Grade minCogito() { return minCogito; }

    /** Lowest cogito volume (titer) that can extract this tier — the volume gate. */
    public double minVolume() { return minVolume; }

    /** Apex tiers (WAW and ALEPH) — these MANDATE an inserted catalyst (a Radiant Cogito). */
    public boolean isApex() { return this == WAW || this == ALEPH; }
}
