package com.nyrrine.reliquary.extraction;

/**
 * An extractable weapon as the Well sees it: an id (matching the E.G.O weapon's model id), a display name,
 * and its {@link EgoGrade}. The whole roster is just a list of these, so adding a weapon costs nothing
 * systemically. A ticket pools by grade; the Well hands back the real weapon whose id matches.
 */
public record WeaponSpec(String id, String display, EgoGrade grade) {
}
