package com.nyrrine.reliquary.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-entrancy mark on its own.
 *
 * <p>The dispatch around it needs a server — a real player, a real inventory, a real damage event — but the
 * mark is just bookkeeping, and the bookkeeping is where a hook that re-enters itself is won or lost. The
 * manager holds no plugin state to reach either, so it stands up with none.
 */
class WeaponManagerDealingTest {

    private final WeaponManager manager = new WeaponManager(null);
    private final UUID wielder = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void aWielderIsNotDealingUntilTheyAre() {
        assertFalse(manager.isDealing(wielder));
    }

    @Test
    void theMarkStandsForTheBlowAndIsGoneAfter() {
        manager.dealing(wielder, () -> assertTrue(manager.isDealing(wielder), "the blow should be marked as ours"));
        assertFalse(manager.isDealing(wielder), "the mark must not outlive the blow");
    }

    /** The mark is per-wielder: one player's follow-up must not fence another player's swing. */
    @Test
    void theMarkIsOneWieldersAlone() {
        manager.dealing(wielder, () -> assertFalse(manager.isDealing(other)));
        assertFalse(manager.isDealing(wielder));
    }

    /** A move that strikes from inside another move: the inner one unwinding must not lift the outer's mark. */
    @Test
    void nestedBlowsUnwindInOrder() {
        manager.dealing(wielder, () -> {
            manager.dealing(wielder, () -> assertTrue(manager.isDealing(wielder)));
            assertTrue(manager.isDealing(wielder), "the outer blow is still ours after the inner one returns");
        });
        assertFalse(manager.isDealing(wielder));
    }

    /** A listener that throws must not strand the mark and fence the wielder's every future swing. */
    @Test
    void theMarkIsLiftedEvenWhenTheBlowBlowsUp() {
        assertThrows(IllegalStateException.class, () -> manager.dealing(wielder, () -> {
            throw new IllegalStateException("a listener exploded");
        }));
        assertFalse(manager.isDealing(wielder), "a thrown blow must not leave the wielder permanently fenced");
    }
}
