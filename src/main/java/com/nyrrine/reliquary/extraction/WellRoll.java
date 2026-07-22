package com.nyrrine.reliquary.extraction;

/**
 * What the Pocket Well shows for one candidate weapon on its carousel: the weapon, a match weight, and its
 * odds. The old cogito-purity roll math is gone (a ticket pull is a flat random pick); this survives only as
 * the small display record the Well carousel ({@link WellDisplay}) and the ticket pour build.
 */
public final class WellRoll {

    private WellRoll() {}

    /** One candidate on the Well carousel: the weapon, its match weight, and its display odds. */
    public record Chance(WeaponSpec weapon, double match, double odds) {}
}
