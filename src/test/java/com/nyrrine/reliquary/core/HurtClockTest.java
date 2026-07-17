package com.nyrrine.reliquary.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HurtClock} against vanilla's hurt-immunity rule, as {@link FakeVictim} models it. */
class HurtClockTest {

    /** The problem, stated: a follow-up the size of the swing it rides on is dropped whole. */
    @Test
    void vanillaDropsAnEqualFollowUpWhole() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);          // the swing
        victim.tick(5);            // five ticks later...
        victim.hurt(7.0);          // ...the follow-up

        assertEquals(7.0, victim.taken, "the follow-up should have vanished into the swing's i-frames");
    }

    /** And the same follow-up, put through the clock, lands in full. */
    @Test
    void strikeLandsAFollowUpVanillaWouldDrop() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);
        victim.tick(5);

        assertTrue(HurtClock.strike(victim.handle(), 7.0, FakeVictim.attacker()));
        assertEquals(14.0, victim.taken, "the follow-up should land whole, not be eaten");
    }

    /** The half nobody but Gaze found: the countdown is put back exactly where it was. */
    @Test
    void restoresTheClockItFound() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);
        victim.tick(5);
        assertEquals(15, victim.clock);

        HurtClock.strike(victim.handle(), 2.0, FakeVictim.attacker());

        assertEquals(15, victim.clock, "the strike must leave the victim's clock as it found it, not re-stamped");
    }

    /** Clearing has to happen before the damage, or it clears nothing. */
    @Test
    void clearsBeforeItStrikes() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);
        victim.tick(5);

        HurtClock.strike(victim.handle(), 2.0, FakeVictim.attacker());

        assertEquals(java.util.List.of("clock=0", "damage(2.0,source)", "clock=15"), victim.log);
    }

    /**
     * The whole reason the restore matters, run end to end: a follow-up that re-stamps the clock bills its
     * own next swing for exactly what it added.
     *
     * <p>A 7-damage swing, a 2-damage tag five ticks later, and the next swing seven ticks after that. Left
     * re-stamped, the tag's fresh countdown is still over halfway when the second swing arrives, so that
     * swing is measured against the tag and lands only its excess — 7+2+5, which is what two bare swings
     * would have dealt anyway. Restored, the clock has decayed past halfway by then and the swing lands
     * whole. The tag is only worth anything if the clock goes back.
     */
    @Test
    void theRestoreIsWhatMakesAFollowUpWorthAnything() {
        FakeVictim restored = new FakeVictim();
        restored.hurt(7.0);
        restored.tick(5);
        HurtClock.strike(restored.handle(), 2.0, FakeVictim.attacker());
        restored.tick(7);
        restored.hurt(7.0);

        FakeVictim reStamped = new FakeVictim();
        reStamped.hurt(7.0);
        reStamped.tick(5);
        reStamped.clock = 0;              // the clear every weapon already does...
        reStamped.hurt(2.0);              // ...and the damage, with no restore behind it
        reStamped.tick(7);
        reStamped.hurt(7.0);

        assertEquals(14.0, reStamped.taken, "without the restore the tag pays for itself out of the next swing");
        assertEquals(16.0, restored.taken, "with the restore the tag is actually worth its 2");
    }

    /**
     * A clock already at zero displaced nothing, so nothing is put back and the strike keeps the ordinary
     * stamp it just earned. Restoring the zero would strip a body of immunity it should have had.
     */
    @Test
    void aZeroClockKeepsTheStampTheStrikeEarned() {
        FakeVictim victim = new FakeVictim();

        HurtClock.strike(victim.handle(), 5.0, FakeVictim.attacker());

        assertEquals(5.0, victim.taken);
        assertEquals(FakeVictim.DURATION, victim.clock, "the originating blow keeps its own i-frames");
    }

    /** A burst on one body in one tick: every instance lands, and the clock is still the swing's at the end. */
    @Test
    void everyBladeOfAVolleyLands() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);
        victim.tick(5);

        for (int blade = 0; blade < 4; blade++) {
            assertTrue(HurtClock.strike(victim.handle(), 5.5, FakeVictim.attacker()));
        }

        assertEquals(7.0 + 4 * 5.5, victim.taken, "all four blades should land, not just the first");
        assertEquals(15, victim.clock);
    }

    /** The volley ends at the body that fell — a corpse never soaks up the rest. */
    @Test
    void aVolleyStopsAtTheBodyThatFell() {
        FakeVictim victim = new FakeVictim();
        int landed = 0;
        for (int blade = 0; blade < 4; blade++) {
            if (blade == 2) victim.dead = true;   // the second blade killed it
            if (!HurtClock.strike(victim.handle(), 5.5, FakeVictim.attacker())) break;
            landed++;
        }
        assertEquals(2, landed);
    }

    @Test
    void declinesADeadOrDepartedBody() {
        FakeVictim dead = new FakeVictim();
        dead.dead = true;
        assertFalse(HurtClock.strike(dead.handle(), 5.0, FakeVictim.attacker()));
        assertTrue(dead.log.isEmpty(), "a corpse should not be touched at all");

        FakeVictim gone = new FakeVictim();
        gone.valid = false;
        assertFalse(HurtClock.strike(gone.handle(), 5.0, FakeVictim.attacker()));
        assertTrue(gone.log.isEmpty());
    }

    /** Being recorded as your own attacker is never what the caller meant. */
    @Test
    void selfDamageIsDealtWithoutASource() {
        FakeVictim victim = new FakeVictim();
        HurtClock.strike(victim.handle(), 3.0, victim.handle());
        assertEquals(java.util.List.of("clock=0", "damage(3.0)"), victim.log);
    }

    @Test
    void aNullSourceIsDealtWithoutASource() {
        FakeVictim victim = new FakeVictim();
        HurtClock.strike(victim.handle(), 3.0);
        assertEquals(java.util.List.of("clock=0", "damage(3.0)"), victim.log);
    }

    /** A listener that throws must not leave the victim stripped of the immunity they were owed. */
    @Test
    void restoresTheClockEvenWhenTheDamageBlowsUp() {
        FakeVictim victim = new FakeVictim();
        victim.hurt(7.0);
        victim.tick(5);
        victim.throwOnDamage = new IllegalStateException("a listener exploded");

        assertThrows(IllegalStateException.class,
                () -> HurtClock.strike(victim.handle(), 2.0, FakeVictim.attacker()));
        assertEquals(15, victim.clock, "the clock is owed back even when the strike fails");
    }
}
