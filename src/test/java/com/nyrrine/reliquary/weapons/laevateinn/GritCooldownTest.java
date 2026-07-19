package com.nyrrine.reliquary.weapons.laevateinn;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate behind the revive. The spam bug was that a save could fire without ever arming its
 * cooldown; these pin the invariant that one save arms the lockout and the lockout holds until it
 * genuinely elapses. Time is passed in, so the whole thing is deterministic and headless.
 */
class GritCooldownTest {

    private static final long CD = 15L * 60L * 1000L; // 15 minutes, Grit's cooldown

    @Test
    void oneSaveThenLockedOutForTheWholeWindow() {
        GritCooldown cd = new GritCooldown(CD);
        UUID p = UUID.randomUUID();
        long t0 = 1_000_000L;

        assertTrue(cd.tryConsume(p, t0), "the first lethal hit should fire the save");
        // This is the bug, stated as a test: no further hit inside the window may fire again.
        assertFalse(cd.tryConsume(p, t0), "a second hit in the same instant must not re-save");
        assertFalse(cd.tryConsume(p, t0 + 1), "a hit 1ms later must not re-save");
        assertFalse(cd.tryConsume(p, t0 + CD - 1), "a hit just before expiry must not re-save");
    }

    @Test
    void firesAgainOnceTheWindowElapses() {
        GritCooldown cd = new GritCooldown(CD);
        UUID p = UUID.randomUUID();
        long t0 = 5_000_000L;

        assertTrue(cd.tryConsume(p, t0));
        assertTrue(cd.tryConsume(p, t0 + CD), "exactly at expiry the save is available again");
    }

    @Test
    void cooldownsAreIndependentPerPlayer() {
        GritCooldown cd = new GritCooldown(CD);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        long t0 = 2_000_000L;

        assertTrue(cd.tryConsume(a, t0));
        assertTrue(cd.tryConsume(b, t0), "one player's save must not spend another's cooldown");
        assertFalse(cd.tryConsume(a, t0), "a is still locked out");
    }

    @Test
    void clearReleasesTheLockout() {
        GritCooldown cd = new GritCooldown(CD);
        UUID p = UUID.randomUUID();
        long t0 = 3_000_000L;

        assertTrue(cd.tryConsume(p, t0));
        cd.clear(p);
        assertTrue(cd.tryConsume(p, t0), "after a quit-clear the cooldown is gone");
    }
}
