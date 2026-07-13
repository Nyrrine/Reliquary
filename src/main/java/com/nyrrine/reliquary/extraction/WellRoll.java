package com.nyrrine.reliquary.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The Pocket Well resolution (§28) — pure math over a poured {@link PotState}. Given the pour's grade,
 * volume and composition (plus an optional signature-lock catalyst and the Well's accumulated residue),
 * it lands on one of three rungs:
 *
 * <ol>
 *   <li>{@link Outcome#MANIFEST} — you get the weapon. Certified (catalyst-locked at Primary Standard) is a
 *       guaranteed manifest of the catalyst's target.</li>
 *   <li>{@link Outcome#BREACH} — the Well ruptures and an Abnormality climbs out, scaled to the grade you
 *       reached for (loading a WAW catalyst onto crude cogito can breach a WAW Abnormality).</li>
 * </ol>
 *
 * <p>Two hard gates run before the roll: the <b>grade floor</b> (a weapon whose {@link EgoGrade} needs a
 * higher cogito grade than you have is not in the bucket at all) and the <b>volume gate</b>. Luck only ever
 * picks <i>which</i> same-band weapon — it never crosses tiers.
 */
public final class WellRoll {

    private WellRoll() {}

    /** A catalyst only bites once the target is your top pull AND its odds clear this bar. Below it, nothing. */
    public static final double CATALYST_MIN_ODDS = 0.70;
    /** ZAYIN/TETH/HE catalyst: each stacked catalyst adds a random bump in [MIN,MAX] to the target's odds. */
    public static final double CATALYST_BUFF_MIN = 0.01;
    public static final double CATALYST_BUFF_MAX = 0.15;
    /** Only weapons at least this well-matched are in the pool at all — the "range of sins you chose". Keeps
     *  the small off-chance a neighbour, never a wildcard. */
    public static final double POOL_MATCH_FLOOR = 0.60;
    /** How sharply the gacha favours your best-matched weapon (weight = match^this). High, so a pour shaped
     *  cleanly onto one signature comes out as that weapon ~90% of the time; the rest is close neighbours. */
    public static final double GACHA_SHARPNESS = 28.0;
    /** Purity at/above which a locked catalyst certifies the pour (Certified, 100% lore). */
    public static final double CERTIFIED_PURITY = Grade.PRIMARY_STANDARD.minPurity();

    // A valid pour yields a weapon; it only ruptures if the pot was too UNSTABLE to pour (you didn't steady
    // it) — quality/match no longer cause a "you got nothing". Breach scales with how shaky the pour was.
    public static final double BREACH_W_STAB = 0.9;      // weight on (1 - stability)^2
    public static final double BREACH_W_RESIDUE = 0.30;  // Well spray-and-pray taint

    /** Which rung a pour resolved to. */
    public enum Outcome { MANIFEST, BREACH }

    /**
     * The result of a pour.
     *
     * @param outcome     which rung
     * @param weapon      the manifested / near-missed weapon (null only on a blank-pour breach)
     * @param match       best match achieved, {@code [0,1]}
     * @param certified   whether a catalyst guaranteed the manifest
     * @param cogitoGrade the analytical grade of the pour
     * @param purity      the pour's purity (0–100) — for the attribution stamp
     * @param aimedGrade  the E.G.O grade reached for — sets breach severity
     */
    public record Result(Outcome outcome, WeaponSpec weapon, double match, boolean certified,
                         Grade cogitoGrade, double purity, EgoGrade aimedGrade) {}

    /**
     * Resolve a pour. {@code catalystTargetId} may be null (no catalyst); {@code wellResidue} in {@code [0,1]}
     * is the Well's spray-and-pray taint (raises breach chance); {@code rng} drives the manifest/breach rolls.
     */
    public static Result resolve(PotState pour, String catalystTargetId, double wellResidue,
                                 RandomGenerator rng) {
        double purity = pour.purity();
        Grade cogitoGrade = Grade.of(purity);

        WeaponSpec target = catalystTargetId == null ? null : WeaponSignatures.byId(catalystTargetId);

        // Apex (WAW/ALEPH) weapons MANDATE their catalyst: drop any apex weapon that isn't the inserted target,
        // so a WAW/ALEPH can never be pulled without a matching Radiant Cogito.
        List<Chance> pool = filterApex(pool(pour), catalystTargetId);
        EgoGrade aimed = target != null ? target.grade()
                : (pool.isEmpty() ? EgoGrade.ZAYIN : pool.get(0).weapon().grade());

        if (pool.isEmpty()) {
            // Nothing in range cleared the gates — a hollow / over-reach pour ruptures at the aimed tier.
            return new Result(Outcome.BREACH, null, 0.0, false, cogitoGrade, purity, aimed);
        }

        double bestMatch = pool.get(0).match();

        // A valid pour yields a weapon UNLESS the pot was too unstable to pour — then the Well ruptures.
        if (rng.nextDouble() < breachChance(pour, wellResidue)) {
            return new Result(Outcome.BREACH, pool.get(0).weapon(), bestMatch, false, cogitoGrade, purity, aimed);
        }

        // Catalyst effect — only when the target is your TOP pull and its odds clear the 70% bar.
        if (target != null && pool.get(0).weapon().id().equals(target.id())
                && pool.get(0).odds() >= CATALYST_MIN_ODDS) {
            boolean certified = purity >= CERTIFIED_PURITY - 1.0e-9;
            if (target.grade().isApex()) {
                // Apex: the catalyst is the requirement (no buff) — top pull + past 70% → it manifests.
                return new Result(Outcome.MANIFEST, target, pool.get(0).match(), certified,
                        cogitoGrade, purity, target.grade());
            }
            // ZAYIN/TETH/HE: a random 1–15% odds bump per stacked catalyst (up to 3).
            int count = Math.max(1, pour.catalystCount());
            double buff = 0.0;
            for (int i = 0; i < count; i++) {
                buff += CATALYST_BUFF_MIN + rng.nextDouble() * (CATALYST_BUFF_MAX - CATALYST_BUFF_MIN);
            }
            double boosted = Math.min(1.0, pool.get(0).odds() + buff);
            if (rng.nextDouble() < boosted) {
                return new Result(Outcome.MANIFEST, target, pool.get(0).match(), certified,
                        cogitoGrade, purity, target.grade());
            }
        }

        // An apex target that didn't clear its gate can't be pulled by luck — drop apex weapons before the gacha.
        if (target != null && target.grade().isApex()) {
            List<Chance> lower = new ArrayList<>();
            for (Chance c : pool) if (!c.weapon().grade().isApex()) lower.add(c);
            if (lower.isEmpty()) {
                return new Result(Outcome.BREACH, pool.get(0).weapon(), bestMatch, false, cogitoGrade, purity, aimed);
            }
            pool = lower;
        }

        // The gacha (which weapon): almost always your best match, with a small off-chance of a neighbour.
        WeaponSpec pulled = weightedPick(pool, rng);
        return new Result(Outcome.MANIFEST, pulled, bestMatch, false, cogitoGrade, purity, pulled.grade());
    }

    /** Drop apex (WAW/ALEPH) weapons that aren't the inserted catalyst's target, then renormalize the odds. */
    private static List<Chance> filterApex(List<Chance> pool, String catalystTargetId) {
        boolean dropped = false;
        List<Chance> kept = new ArrayList<>();
        for (Chance c : pool) {
            if (c.weapon().grade().isApex() && !c.weapon().id().equals(catalystTargetId)) { dropped = true; continue; }
            kept.add(c);
        }
        if (!dropped) return pool;
        double sum = 0.0;
        for (Chance c : kept) sum += c.odds();
        if (sum <= 0.0) return kept;
        List<Chance> norm = new ArrayList<>();
        for (Chance c : kept) norm.add(new Chance(c.weapon(), c.match(), c.odds() / sum));
        norm.sort((a, b) -> Double.compare(b.odds(), a.odds()));
        return norm;
    }

    /** A weapon's odds of being pulled from the Well right now: its composition match and normalized pull %. */
    public record Chance(WeaponSpec weapon, double match, double odds) {}

    /**
     * The reachable loot pool with each weapon's normalized pull odds, best first. A weapon's weight is its
     * match raised to {@link #GACHA_SHARPNESS} — so shaping your cogito toward a target sharply tilts the
     * gacha in its favour, while the rest of the pool keeps a real (smaller) chance. Only weapons that clear
     * the grade floor + volume gate are in the pool.
     */
    public static List<Chance> pool(PotState pour) {
        Grade g = Grade.of(pour.purity());
        double titer = pour.titer();
        SinProfile prof = pour.profile();

        List<Chance> weighted = new ArrayList<>();
        double total = 0.0;
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (!w.reachableBy(g, titer)) continue;
            double m = w.matchOf(prof);
            if (m < POOL_MATCH_FLOOR) continue; // out of your sin-range — not a possible pull
            double wt = Math.pow(Math.max(0.0, m), GACHA_SHARPNESS);
            weighted.add(new Chance(w, m, wt)); // stash the raw weight in odds for now
            total += wt;
        }
        List<Chance> out = new ArrayList<>();
        for (Chance c : weighted) {
            out.add(new Chance(c.weapon(), c.match(), total > 0.0 ? c.odds() / total : 0.0));
        }
        out.sort((a, b) -> Double.compare(b.odds(), a.odds()));
        return out;
    }

    /** Chance a pour ruptures instead of yielding a weapon — purely a function of how unstable the pot is
     *  when poured (plus Well residue). A steadied pot is safe; a shaky one is a gamble. */
    public static double breachChance(PotState pour, double wellResidue) {
        double shaky = 1.0 - Math.max(0.0, Math.min(1.0, pour.stability() / 100.0));
        return Math.max(0.0, Math.min(1.0, BREACH_W_STAB * shaky * shaky + BREACH_W_RESIDUE * Math.max(0.0, wellResidue)));
    }

    private static WeaponSpec weightedPick(List<Chance> pool, RandomGenerator rng) {
        // Normalize by the actual odds sum — the pool may have been filtered (apex-dropped) so it needn't sum to 1.
        double total = 0.0;
        for (Chance c : pool) total += c.odds();
        double r = rng.nextDouble() * total;
        double cum = 0.0;
        for (Chance c : pool) {
            cum += c.odds();
            if (r < cum) return c.weapon();
        }
        return pool.get(pool.size() - 1).weapon();
    }
}
