package com.nyrrine.reliquary.extraction;

import org.bukkit.Color;

/**
 * The E.G.O rarity grade of a weapon (Project Moon's ZAYIN → ALEPH ladder). Each weapon in the roster
 * carries one; an Extraction Ticket's pools are keyed by these tier names, and the Well pulls a random
 * weapon from the pooled grades. Just a tier label now — the old cogito-chemistry gates are gone.
 *
 * <p>Each grade also carries a {@link #color()} (placeholders from the LobCorp grade plate) used to tint the
 * extraction show — a weapon's synapse tendril, its glow, and its reveal burst are its grade colour.
 *
 * <p>{@link #SPECIAL} is the off-ladder tier for weapons that are not in any grade pool (Twilight and the
 * like). It is deliberately <b>not</b> a ticket pool name, so a grade ticket never rolls it; it only labels a
 * weapon hand-picked onto a custom ticket by id, and gets a gold burst as a fallback.
 */
public enum EgoGrade {

    ZAYIN  ("ZAYIN", 0x3FE04F),
    TETH   ("TETH",  0x2E8BFF),
    HE     ("HE",    0xFFE23D),
    WAW    ("WAW",   0xA24BFF),
    ALEPH  ("ALEPH", 0xFF3B3B),
    SPECIAL("SPECIAL", 0xFFD34D);

    private final String display;
    private final Color color;

    EgoGrade(String display, int rgb) {
        this.display = display;
        this.color = Color.fromRGB(rgb);
    }

    /** Display label (e.g. {@code "WAW"}). */
    public String display() { return display; }

    /** The grade's show colour (tendril / glow / burst). */
    public Color color() { return color; }
}
