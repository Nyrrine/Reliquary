package com.nyrrine.reliquary.weapons.arayashiki;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeartLedger} — the server-free brain behind Arayashiki's heart-strip. These hold the invariants
 * the rails care about: strips stack, the erase never takes the last heart, a body out of combat is
 * returned, and nothing lingers in the ledger once it's been given back.
 */
class HeartLedgerTest {

    /** The first erase on a full body reduces max-HP by exactly one heart and starts a count. */
    @Test
    void firstStripTakesOneHeart() {
        HeartLedger l = new HeartLedger();
        UUID v = UUID.randomUUID();

        double amount = l.strip(v, 20.0, 0L);

        assertEquals(-2.0, amount, "one heart is two max-HP off");
        assertEquals(1, l.count(v));
        assertTrue(l.tracked(v));
    }

    /** Successive erases stack into one running amount — the caller re-reads the shrinking live max each time. */
    @Test
    void stripsStackByTheCount() {
        HeartLedger l = new HeartLedger();
        UUID v = UUID.randomUUID();

        // Spaced past the per-victim cooldown, so each erase is allowed to land.
        long gap = HeartLedger.STRIP_COOLDOWN_MS;
        assertEquals(-2.0, l.strip(v, 20.0, 0L));
        assertEquals(-4.0, l.strip(v, 18.0, gap));
        assertEquals(-6.0, l.strip(v, 16.0, gap * 2));
        assertEquals(3, l.count(v), "three hearts erased");
    }

    /** The nerf: once a body loses a heart it is immune to losing another until the cooldown elapses. */
    @Test
    void perVictimCooldownThrottlesRapidStrips() {
        HeartLedger l = new HeartLedger();
        UUID v = UUID.randomUUID();

        assertEquals(-2.0, l.strip(v, 20.0, 0L), "the first erase lands");

        // A flurry of further hits inside the window take nothing, however many arrive.
        assertTrue(Double.isNaN(l.strip(v, 18.0, 500L)), "still immune half a second later");
        assertTrue(Double.isNaN(l.strip(v, 18.0, HeartLedger.STRIP_COOLDOWN_MS - 1)), "immune right up to the edge");
        assertEquals(1, l.count(v), "no heart lost while on cooldown");

        // Once the window passes, the next hit can erase again.
        assertEquals(-4.0, l.strip(v, 18.0, HeartLedger.STRIP_COOLDOWN_MS), "the cooldown has elapsed");
        assertEquals(2, l.count(v));
    }

    /** The floor: a body at two hearts loses one; a body at one heart keeps it and is never even tracked. */
    @Test
    void oneHeartFloorHolds() {
        HeartLedger l = new HeartLedger();
        UUID twoHearts = UUID.randomUUID();
        UUID oneHeart = UUID.randomUUID();

        assertEquals(-2.0, l.strip(twoHearts, 4.0, 0L), "two hearts -> one is fair game");

        assertTrue(Double.isNaN(l.strip(oneHeart, 2.0, 0L)), "the last heart survives");
        assertFalse(l.tracked(oneHeart), "a blocked erase leaves no trace to restore");
        assertEquals(0, l.count(oneHeart));
    }

    /** Being struck keeps the combat clock warm, so an erase mid-fight isn't reaped early. */
    @Test
    void touchKeepsABodyInCombat() {
        HeartLedger l = new HeartLedger();
        UUID v = UUID.randomUUID();
        l.strip(v, 20.0, 0L);

        l.touch(v, 5000L);                              // struck again five seconds in
        assertTrue(l.reapExpired(9000L).isEmpty(), "only 4s since the last cut — still in combat");

        List<UUID> reaped = l.reapExpired(14000L);      // now nine seconds past that cut
        assertEquals(List.of(v), reaped, "combat lapsed — its hearts are returned");
        assertFalse(l.tracked(v), "and it's dropped from the ledger");
    }

    /** touch on an untracked body does nothing — it can't conjure an entry out of a miss. */
    @Test
    void touchIgnoresUntrackedBodies() {
        HeartLedger l = new HeartLedger();
        UUID stranger = UUID.randomUUID();

        l.touch(stranger, 0L);

        assertFalse(l.tracked(stranger));
    }

    /** forget returns a body's hearts once (a death, a quit) and reports whether it held any. */
    @Test
    void forgetIsIdempotentAndHonest() {
        HeartLedger l = new HeartLedger();
        UUID v = UUID.randomUUID();
        l.strip(v, 20.0, 0L);

        assertTrue(l.forget(v), "the first forget clears a real strip");
        assertFalse(l.tracked(v));
        assertFalse(l.forget(v), "a second forget finds nothing");
    }

    /** drainAll hands back every outstanding body and empties the ledger — the shutdown restore. */
    @Test
    void drainAllEmptiesTheLedger() {
        HeartLedger l = new HeartLedger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        l.strip(a, 20.0, 0L);
        l.strip(b, 20.0, 0L);

        Set<UUID> drained = l.drainAll();

        assertEquals(Set.of(a, b), drained);
        assertTrue(l.isEmpty(), "nothing left owing after a drain");
    }
}
