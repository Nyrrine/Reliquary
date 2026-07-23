package com.nyrrine.reliquary.extraction;

/**
 * The E.G.O rarity grade of a weapon (Project Moon's ZAYIN → ALEPH ladder). Each weapon in the roster
 * carries one; an Extraction Ticket's pools are keyed by these tier names, and the Well pulls a random
 * weapon from the pooled grades. Just a tier label now — the old cogito-chemistry gates are gone.
 *
 * <p>{@link #SPECIAL} is the off-ladder tier for weapons that are not in any grade pool (Twilight and the
 * like). It is deliberately <b>not</b> a ticket pool name, so a grade ticket never rolls it; it only labels a
 * weapon hand-picked onto a custom ticket by id.
 */
public enum EgoGrade {

    ZAYIN("ZAYIN"),
    TETH ("TETH"),
    HE   ("HE"),
    WAW  ("WAW"),
    ALEPH("ALEPH"),
    SPECIAL("SPECIAL");

    private final String display;

    EgoGrade(String display) {
        this.display = display;
    }

    /** Display label (e.g. {@code "WAW"}). */
    public String display() { return display; }
}
