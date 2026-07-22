package com.nyrrine.reliquary.extraction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the Well's pool arithmetic as the roster grows. {@link WellRoll#pool} normalizes odds
 * across everything that clears the gates and {@code filterApex} re-normalizes what survives the apex rule, so
 * every weapon added to the roster changes what every existing pour competes against. These tests pin the
 * invariants that must survive that: a pour still lands on the weapon it was shaped for, the odds stay a real
 * distribution, and the apex tiers stay sealed behind their catalyst and their gates.
 */
class PoolMathTest {

    /** Build a pot at a target composition, volume, and purity (noise = 100 − purity). */
    private static PotState pour(SinProfile sig, double titer, double purity, double stability) {
        PotState p = new PotState();
        for (Sin s : Sin.values()) p.setCharge(s, sig.get(s) / 100.0 * titer);
        p.setNoise(100.0 - purity);
        p.setStability(stability);
        return p;
    }

    /** A pot poured exactly onto a weapon's signature. Stability 100 → never a breach, so the gacha is isolated. */
    private static PotState onTarget(WeaponSpec w, double titer, double purity) {
        return pour(w.signature(), titer, purity, 100.0);
    }

    /** Extraction histogram (weapon id → count) over {@code n} pours; breaches counted under "BREACH". */
    private static Map<String, Integer> histogram(PotState p, String catalystId, int n, long seed) {
        RandomGenerator rng = new java.util.Random(seed);
        Map<String, Integer> hist = new HashMap<>();
        for (int i = 0; i < n; i++) {
            WellRoll.Result r = WellRoll.resolve(p, catalystId, 0.0, rng);
            hist.merge(r.outcome() == WellRoll.Outcome.EXTRACT ? r.weapon().id() : "BREACH", 1, Integer::sum);
        }
        return hist;
    }

    // ---- 1. the roster grew; existing pours must still land where they always did ----

    /**
     * The anti-regression pin: a pour shaped onto an existing weapon still comes out as that weapon. The new
     * signatures put real neighbours next to these (Solitude sits at match .90 from both Penitence and Wrist
     * Cutter), so this asserts the neighbours only ever nibble the tail — they never take the pull.
     */
    @Test
    void existingWeaponsStillExtractAsThemselves() {
        for (WeaponSpec w : List.of(WeaponSignatures.PENITENCE, WeaponSignatures.WRIST_CUTTER,
                WeaponSignatures.SODA, WeaponSignatures.LOGGING, WeaponSignatures.OUR_GALAXY,
                WeaponSignatures.LAETITIA, WeaponSignatures.CRIMSON_SCAR)) {
            // Volume/purity comfortably past this weapon's own gates, shaped exactly on its signature.
            PotState p = onTarget(w, Math.max(200.0, w.grade().minVolume() * 2), 92.0);
            List<WellRoll.Chance> pool = WellRoll.pool(p);

            assertEquals(w.id(), pool.get(0).weapon().id(),
                    w.id() + " must still be the top pull for its own signature");
            assertEquals(1.0, pool.get(0).match(), 1.0e-9, w.id() + " must match its own signature exactly");
            assertTrue(pool.get(0).odds() > 0.80,
                    w.id() + " should still dominate its own pour, odds were " + pool.get(0).odds());

            Map<String, Integer> hist = histogram(p, null, 2000, 42L);
            int self = hist.getOrDefault(w.id(), 0);
            assertTrue(self > 1600, w.id() + " should extract in the vast majority of on-target pours, got "
                    + self + "/2000 — " + hist);
        }
    }

    /**
     * The roster's own design target: "a pour shaped cleanly onto one signature comes out as that weapon ~90%
     * of the time; the rest is close neighbours" ({@link WellRoll#GACHA_SHARPNESS}). The 11 new signatures
     * measurably dilute Penitence (its odds fall ~96.9% → ~91.2% once Solitude joins the pool at match .90),
     * which is a real shift — but it must stay inside the documented ~90% intent, not slide out of it.
     */
    @Test
    void newNeighboursDiluteButDoNotBreakTheDesignTarget() {
        PotState p = onTarget(WeaponSignatures.PENITENCE, 200, 92.0);
        List<WellRoll.Chance> pool = WellRoll.pool(p);

        assertEquals("penitence", pool.get(0).weapon().id());
        assertTrue(pool.get(0).odds() >= 0.85 && pool.get(0).odds() <= 0.98,
                "a clean on-target pour should sit near the documented ~90% intent, was " + pool.get(0).odds());

        // Solitude is the new neighbour; it must be present and behind Penitence, never ahead of it.
        WellRoll.Chance solitude = pool.stream().filter(c -> c.weapon().id().equals("solitude")).findFirst()
                .orElseThrow(() -> new AssertionError("solitude should be a reachable neighbour of penitence"));
        assertTrue(solitude.odds() < pool.get(0).odds() / 10.0,
                "the neighbour must stay a tail event, was " + solitude.odds());
    }

    // ---- 2. ALEPH is never reachable by luck ----

    /**
     * The apex rule, at the new tier: with no catalyst inserted, no amount of grade, volume or on-target
     * shaping may produce an ALEPH. {@code filterApex} drops every apex weapon when the target id is null.
     */
    @Test
    void alephIsNeverReachableWithoutACatalyst() {
        for (WeaponSpec aleph : List.of(WeaponSignatures.JUSTITIA, WeaponSignatures.MIMICRY)) {
            // A perfect pour: Certified-grade purity, ten vials of volume, dead on the signature.
            PotState p = onTarget(aleph, 2000, 99.9);

            assertTrue(WellRoll.pool(p).stream().anyMatch(c -> c.weapon().id().equals(aleph.id())),
                    aleph.id() + " should clear its gates on this pour (else the test proves nothing)");

            Map<String, Integer> hist = histogram(p, null, 3000, 11L);
            assertFalse(hist.containsKey(aleph.id()),
                    "an ALEPH must never fall out of a catalyst-less pour — got " + hist);
            for (int i = 0; i < 3000; i++) {
                WellRoll.Result r = WellRoll.resolve(p, null, 0.0, new java.util.Random(i));
                if (r.outcome() == WellRoll.Outcome.EXTRACT) {
                    assertNotSame(EgoGrade.ALEPH, r.weapon().grade(),
                            "luck crossed into ALEPH on seed " + i + " → " + r.weapon().id());
                }
            }
        }
    }

    /** The same seal, stated over the pool: a catalyst-less pour never leaves an apex weapon in the bucket. */
    @Test
    void catalystlessPoolNeverRetainsAnApexWeapon() {
        PotState p = onTarget(WeaponSignatures.JUSTITIA, 2000, 99.9);
        RandomGenerator rng = new java.util.Random(5L);
        for (int i = 0; i < 200; i++) {
            WellRoll.Result r = WellRoll.resolve(p, null, 0.0, rng);
            if (r.outcome() == WellRoll.Outcome.EXTRACT) {
                assertFalse(r.weapon().grade().isApex(),
                        "no apex weapon may be pulled without its catalyst, got " + r.weapon().id());
            }
        }
    }

    // ---- 3. the ALEPH gates ----

    /** Below Primary Standard, an ALEPH is not in the bucket at all — even with its catalyst inserted. */
    @Test
    void alephIsGradeGatedBelowPrimaryStandard() {
        // Analytical (97%) is enough for WAW but one rung short of ALEPH's Primary Standard floor.
        PotState p = onTarget(WeaponSignatures.JUSTITIA, 2000, 97.0);
        assertSame(Grade.ANALYTICAL, Grade.of(97.0), "97% must sit at Analytical for this test to mean anything");

        assertTrue(WellRoll.pool(p).stream().noneMatch(c -> c.weapon().grade() == EgoGrade.ALEPH),
                "sub-Primary-Standard cogito must never put an ALEPH in the pool");

        Map<String, Integer> hist = histogram(p, "justitia", 1000, 3L);
        assertFalse(hist.containsKey("justitia"),
                "a grade-gated ALEPH must not extract even with its catalyst — got " + hist);
    }

    /** Below 400 titer, an ALEPH is not in the bucket — even at Certified purity, even with its catalyst. */
    @Test
    void alephIsVolumeGatedBelowFourHundredTiter() {
        // 399 titer: past WAW's 300 gate, one titer short of ALEPH's 400.
        PotState p = onTarget(WeaponSignatures.JUSTITIA, 399, 99.9);

        assertTrue(WellRoll.pool(p).stream().noneMatch(c -> c.weapon().grade() == EgoGrade.ALEPH),
                "a pour under 400 titer must never put an ALEPH in the pool");

        Map<String, Integer> hist = histogram(p, "justitia", 1000, 4L);
        assertFalse(hist.containsKey("justitia"),
                "a volume-gated ALEPH must not extract even with its catalyst — got " + hist);

        // And the gate is exactly 400, not "around" 400.
        assertTrue(WellRoll.pool(onTarget(WeaponSignatures.JUSTITIA, 400, 99.9)).stream()
                        .anyMatch(c -> c.weapon().id().equals("justitia")),
                "exactly 400 titer must clear the ALEPH volume gate");
    }

    /** The gates are declarative — the enum is the single source of truth the Well reads. */
    @Test
    void alephGatesAreTheApprovedValues() {
        assertSame(Grade.PRIMARY_STANDARD, EgoGrade.ALEPH.minCogito());
        assertEquals(400.0, EgoGrade.ALEPH.minVolume(), 1.0e-9);
        assertTrue(EgoGrade.ALEPH.isApex(), "ALEPH must be an apex tier — it mandates its catalyst");
        assertTrue(EgoGrade.WAW.isApex(), "WAW stays apex");
        for (EgoGrade g : List.of(EgoGrade.ZAYIN, EgoGrade.TETH, EgoGrade.HE)) {
            assertFalse(g.isApex(), g + " must not be apex");
        }
    }

    // ---- 4. a certified ALEPH pour ----

    /** Catalyst + top pull + odds past the 70% bar + grade/volume cleared → the ALEPH extracts, certified. */
    @Test
    void certifiedAlephPourExtracts() {
        for (WeaponSpec aleph : List.of(WeaponSignatures.JUSTITIA, WeaponSignatures.MIMICRY)) {
            PotState p = onTarget(aleph, 1000, 99.5);

            List<WellRoll.Chance> pool = WellRoll.pool(p);
            assertEquals(aleph.id(), pool.get(0).weapon().id(), aleph.id() + " must be the top pull");
            assertTrue(pool.get(0).odds() >= WellRoll.CATALYST_MIN_ODDS,
                    aleph.id() + " must clear the 70% catalyst bar, was " + pool.get(0).odds());

            RandomGenerator rng = new java.util.Random(13L);
            for (int i = 0; i < 200; i++) {
                WellRoll.Result r = WellRoll.resolve(p, aleph.id(), 0.0, rng);
                assertSame(WellRoll.Outcome.EXTRACT, r.outcome(), aleph.id() + " pour should extract");
                assertEquals(aleph.id(), r.weapon().id(), "the catalyst must guarantee its ALEPH target");
                assertTrue(r.certified(), "Primary Standard + locked catalyst should certify");
                assertSame(EgoGrade.ALEPH, r.aimedGrade());
            }
        }
    }

    /** An ALEPH catalyst on a hopeless pour still declares ALEPH intent — breach severity scales to the reach. */
    @Test
    void overReachingForAnAlephBreachesAtAlephSeverity() {
        SinProfile scattered = SinProfile.builder()
                .add(Sin.WRATH, 1).add(Sin.PRIDE, 1).add(Sin.LUST, 1).add(Sin.GLOOM, 1)
                .add(Sin.SLOTH, 1).add(Sin.ENVY, 1).add(Sin.GLUTTONY, 1).build();
        PotState p = pour(scattered, 1000, 45.0, 5);
        WellRoll.Result r = WellRoll.resolve(p, "mimicry", 0.6, new java.util.Random(99L));

        assertSame(WellRoll.Outcome.BREACH, r.outcome());
        assertSame(EgoGrade.ALEPH, r.aimedGrade(), "the catalyst declares ALEPH intent → ALEPH breach severity");
    }

    // ---- 5. the odds stay a real distribution at the bigger roster ----

    /**
     * Across a spread of pours the raw pool is always a probability distribution — sums to 1, nothing negative,
     * nothing over 1, sorted best-first. With 35 specs in the roster a normalization slip would show up here.
     */
    @Test
    void poolOddsAlwaysNormalizeToADistribution() {
        for (WeaponSpec w : WeaponSignatures.all()) {
            // Pour each weapon's own signature at a grade/volume that clears its gates.
            PotState p = onTarget(w, Math.max(200.0, w.grade().minVolume() * 2), 99.5);
            List<WellRoll.Chance> pool = WellRoll.pool(p);

            assertFalse(pool.isEmpty(), "a weapon's own signature must reach at least itself: " + w.id());
            double sum = 0.0;
            double prev = Double.MAX_VALUE;
            for (WellRoll.Chance c : pool) {
                assertTrue(c.odds() >= 0.0, "negative odds for " + c.weapon().id());
                assertTrue(c.odds() <= 1.0 + 1.0e-9, "odds past 1.0 for " + c.weapon().id());
                assertTrue(c.match() >= WellRoll.POOL_MATCH_FLOOR, "sub-floor weapon in pool: " + c.weapon().id());
                assertTrue(c.odds() <= prev + 1.0e-9, "pool must be sorted best-first, at " + c.weapon().id());
                prev = c.odds();
                sum += c.odds();
            }
            assertEquals(1.0, sum, 1.0e-9, "pool odds must sum to 1 for a " + w.id() + " pour");
        }
    }

    /**
     * The apex filter re-normalizes what it keeps: after dropping the non-target apex weapons the survivors
     * must still be a distribution summing to 1 — a leak here would silently mis-weight every pour that
     * competes against an apex signature.
     */
    @Test
    void apexFilteredPoolReNormalizesToOne() {
        // A pour rich enough to put both ALEPHs and the WAWs in the raw bucket.
        PotState p = onTarget(WeaponSignatures.JUSTITIA, 2000, 99.9);
        assertTrue(WellRoll.pool(p).stream().anyMatch(c -> c.weapon().grade().isApex()),
                "the raw pool should contain apex weapons for this test to mean anything");

        // resolve() with no catalyst drops every apex weapon; the surviving pull must still be a valid weapon
        // and never apex — i.e. the renormalized pool was sane and non-empty.
        RandomGenerator rng = new java.util.Random(21L);
        int extracts = 0;
        for (int i = 0; i < 500; i++) {
            WellRoll.Result r = WellRoll.resolve(p, null, 0.0, rng);
            if (r.outcome() == WellRoll.Outcome.EXTRACT) {
                extracts++;
                assertFalse(r.weapon().grade().isApex(), "apex leaked through the filter: " + r.weapon().id());
            }
        }
        assertTrue(extracts > 0, "a stable, on-signature pour should extract something after the apex filter");
    }

    /**
     * No empty-pool surprise: the two ALEPH signatures are the most cross-axis points in the roster, so they
     * are the likeliest to strand a catalyst-less pour with nothing to fall back to. Assert they always leave
     * lower-tier company in the bucket rather than an empty pool (which would resolve to a bare breach).
     */
    @Test
    void alephPoursAlwaysLeaveLowerTierCompanyInThePool() {
        for (WeaponSpec aleph : List.of(WeaponSignatures.JUSTITIA, WeaponSignatures.MIMICRY)) {
            PotState p = onTarget(aleph, 2000, 99.9);
            long nonApex = WellRoll.pool(p).stream().filter(c -> !c.weapon().grade().isApex()).count();
            assertTrue(nonApex > 0, aleph.id()
                    + " must leave non-apex weapons in the pool, else a catalyst-less pour is a bare breach");
        }
    }

    /** Every ALEPH must be forgeable: the apex rule makes an ALEPH with no catalyst recipe unobtainable. */
    @Test
    void everyAlephHasACatalystRecipe() {
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (w.grade() != EgoGrade.ALEPH) continue;
            assertTrue(Catalysts.forWeapon(w.id()) != null,
                    "ALEPH '" + w.id() + "' mandates a catalyst but has no recipe — it would be unobtainable");
        }
    }

    /**
     * Every signature is a live, normalized point in 7-sin space — i.e. no weapon was registered with a blank
     * profile, which would sit at distance 0 from an empty pot and poison the pool.
     *
     * <p><b>Note for the reviewer:</b> this canNOT check that the authored numbers sum to 100.
     * {@link SinProfile#fromCharge} re-normalizes whatever it is given, so a profile typed as {@code 76/24/12/8}
     * (sum 120) silently becomes the valid-but-different point {@code 63.3/20/10/6.7} and every test here still
     * passes. The sum-to-100 convention is what makes the authored numbers mean what they read as, and it is
     * only ever enforceable by arithmetic at authoring/review time — never by the suite.
     */
    @Test
    void everySignatureIsANonBlankNormalizedPoint() {
        for (WeaponSpec w : WeaponSignatures.all()) {
            assertFalse(w.signature().isEmpty(), w.id() + " must not have a blank signature");
            double sum = 0.0;
            for (Sin s : Sin.values()) sum += w.signature().get(s);
            assertEquals(100.0, sum, 1.0e-9, w.id() + "'s signature must normalize to 100");
        }
    }
}
