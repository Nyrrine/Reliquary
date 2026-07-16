package com.nyrrine.reliquary.extraction;

/**
 * An extractable weapon as the Well sees it: an id (matching the E.G.O weapon's model id), a display name,
 * its {@link EgoGrade}, and its target {@link SinProfile signature} — a single point in 7-sin space. The
 * whole roster is just a list of these, so adding a weapon costs nothing systemically (§11).
 */
public record WeaponSpec(String id, String display, EgoGrade grade, SinProfile signature) {

    /** Whether a cogito of this grade and volume can reach this weapon (both hard gates). */
    public boolean reachableBy(Grade cogitoGrade, double titer) {
        return grade.minCogito().atMost(cogitoGrade) && titer >= grade.minVolume();
    }

    /** How closely a pot's composition matches this weapon's signature, in {@code [0,1]}. */
    public double matchOf(SinProfile profile) {
        return profile.match(signature);
    }
}
