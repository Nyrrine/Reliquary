package com.nyrrine.reliquary.weapons.laevateinn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player cooldown with an atomic check-and-arm — the gate behind Ridiculous Grit's revive.
 *
 * <p>The revive used to record its cooldown on the last line of the damage handler, after it had
 * already cancelled the killing blow and run the heal, the potion effects and the VFX. On a live
 * server any of those can throw, and if one did the save had already happened (the blow was cancelled)
 * while the cooldown line never ran — so the next lethal hit saved again, and the next, an unbounded
 * revive. A re-entrant damage event arriving mid-handler would slip through the same gap. No headless
 * test could see it, because it takes a real damage sequence to trigger.
 *
 * <p>{@link #tryConsume} closes the gap: it arms the cooldown in the same call that authorises the
 * save, before any of that fallible work runs. A save and its lockout become inseparable — there is no
 * longer a window in which one can happen without the other.
 */
final class GritCooldown {

    private final long cooldownMs;

    /** Per-player: when Grit is ready again (ms epoch). Absent = ready now. */
    private final Map<UUID, Long> readyAt = new HashMap<>();

    GritCooldown(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * If this player is off cooldown at {@code now}, arm it (until {@code now + cooldownMs}) and return
     * true — the caller may fire once. If still cooling, return false and leave everything untouched.
     * The arm happens here, not in the caller, so nothing downstream can skip it.
     */
    boolean tryConsume(UUID id, long now) {
        Long until = readyAt.get(id);
        if (until != null && now < until) return false;
        readyAt.put(id, now + cooldownMs);
        return true;
    }

    /** Drop one player's cooldown (they left). */
    void clear(UUID id) {
        readyAt.remove(id);
    }

    /** Drop every cooldown (plugin shutdown / reload). */
    void clearAll() {
        readyAt.clear();
    }
}
