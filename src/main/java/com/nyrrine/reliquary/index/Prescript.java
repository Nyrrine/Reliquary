package com.nyrrine.reliquary.index;

import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;

/**
 * One issued prescript — the instruction itself, and who it came from.
 *
 * <p>This record is the <b>authority</b>; the paper item a recipient carries is only a receipt stamped with
 * {@link #id()}. Papers burn, drop and duplicate, so nothing that matters may live on them: a prescript
 * survives its paper, and {@code /prescript} works with an empty inventory.
 *
 * <p>Persisted inside the recipient's own {@code prescript:} section of the shared player store. Its shape is
 * deliberately small — text, provenance, and a claim flag. Whether it was <i>accomplished</i> is not recorded
 * here: that is a Weaver's ruling, and the moment it lands the prescript leaves the active list and becomes a
 * number in the tally.
 */
public record Prescript(
        /** Identity of this issuance. Stamped onto the paper so a receipt can find its record. */
        UUID id,
        /** The instruction, verbatim as the recipient must read it. Custom text or a pool entry's line. */
        String text,
        /** The pool entry this was drawn from, or {@code null} when a Weaver wrote it by hand. */
        String poolId,
        /** The Weaver who issued it. */
        UUID issuer,
        /** Epoch seconds at issuance — {@code /prescript} reports how long it has stood outstanding. */
        long issued,
        /** Whether the recipient has raised their hand and said they've done it. */
        boolean claimed
) {

    /** Whether a Weaver wrote this one by hand rather than drawing it. */
    public boolean isCustom() {
        return poolId == null;
    }

    /** Seconds this prescript has stood outstanding, as of now. */
    public long outstandingSeconds() {
        return Math.max(0, System.currentTimeMillis() / 1000L - issued);
    }

    // ---- store shape --------------------------------------------------------------------------------
    //
    // Read and written only through the recipient's own "prescript" section. The store owns the file and the
    // flush; these two methods own nothing but the mapping.

    /** Write this prescript into {@code to} (one child section per prescript, keyed by {@link #id()}). */
    public void write(ConfigurationSection to) {
        ConfigurationSection s = to.createSection(id.toString());
        s.set("text", text);
        s.set("pool_id", poolId); // null for custom — YAML simply omits it
        s.set("issuer", issuer.toString());
        s.set("issued", issued);
        if (claimed) s.set("claimed", true); // absent means false; keeps the common case out of the file
    }

    /**
     * The prescript stored under {@code from}, or {@code null} if the section is malformed.
     *
     * <p>Tolerant by design: a hand-edited playerdata file is a feature of the store (admins rig prescripts
     * beforehand), so a bad entry is skipped rather than thrown — one fat-fingered edit must not cost a
     * player their whole tally.
     */
    public static Prescript read(ConfigurationSection from) {
        try {
            String text = from.getString("text");
            String issuer = from.getString("issuer");
            if (text == null || text.isBlank() || issuer == null) return null;
            return new Prescript(
                    UUID.fromString(from.getName()),
                    text,
                    from.getString("pool_id"),
                    UUID.fromString(issuer),
                    from.getLong("issued"),
                    from.getBoolean("claimed", false));
        } catch (IllegalArgumentException malformedUuid) {
            return null;
        }
    }
}
