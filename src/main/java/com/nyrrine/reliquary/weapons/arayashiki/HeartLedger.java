package com.nyrrine.reliquary.weapons.arayashiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The bookkeeping brain behind Arayashiki's heart-strip: who has had hearts erased, how many, and when
 * they were last in combat with the blade. Kept free of any Bukkit type so the invariants that matter —
 * strips stack, the floor holds, combat lapses restore, nothing leaks — can be tested without a server.
 *
 * <p>One stripped heart is {@link #HEART} (two max-HP). A body is never taken below {@link #FLOOR} (one
 * heart left): the erase is a wound, not the kill — the kill is the blade's own job. The strip is carried
 * as a single running attribute-modifier amount ({@code -HEART * count}) so the Bukkit side can restore it
 * exactly by removing that one modifier, regardless of what else has touched the victim's max health.
 */
final class HeartLedger {

    /** One heart, in max-HP points. */
    static final double HEART = 2.0;
    /** The least max-HP a body may be stripped down to — one heart always remains. */
    static final double FLOOR = 2.0;
    /** How long a stripped body may go unstruck by Arayashiki before its hearts are returned. */
    static final long COMBAT_TIMEOUT_MS = 8000L;

    private static final class Entry {
        int count;
        long lastCombatMs;
    }

    private final Map<UUID, Entry> table = new HashMap<>();

    /** True if this body currently has any hearts erased. */
    boolean tracked(UUID id) {
        return table.containsKey(id);
    }

    /** How many hearts are currently erased from this body (0 if none). */
    int count(UUID id) {
        Entry e = table.get(id);
        return e == null ? 0 : e.count;
    }

    boolean isEmpty() {
        return table.isEmpty();
    }

    /** Keep a stripped body's combat clock alive — called on every Arayashiki strike, whether or not it erases. */
    void touch(UUID id, long nowMs) {
        Entry e = table.get(id);
        if (e != null) e.lastCombatMs = nowMs;
    }

    /**
     * Erase one more heart from a body whose current effective max-HP is {@code effectiveMax}.
     *
     * @return the new total modifier amount to apply (negative), or {@link Double#NaN} when the floor
     *         blocks it — the body already sits at one heart and keeps it.
     */
    double strip(UUID id, double effectiveMax, long nowMs) {
        if (effectiveMax - HEART < FLOOR) return Double.NaN; // one heart always survives
        Entry e = table.computeIfAbsent(id, k -> new Entry());
        e.count++;
        e.lastCombatMs = nowMs;
        return -HEART * e.count;
    }

    /**
     * Remove and return every body whose combat with the blade has lapsed by {@code nowMs}. The caller
     * restores each one's max health. Bodies still in combat stay tracked.
     */
    List<UUID> reapExpired(long nowMs) {
        List<UUID> out = new ArrayList<>();
        Iterator<Map.Entry<UUID, Entry>> it = table.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> en = it.next();
            if (nowMs - en.getValue().lastCombatMs > COMBAT_TIMEOUT_MS) {
                out.add(en.getKey());
                it.remove();
            }
        }
        return out;
    }

    /** Drop one body from the ledger (a death, a quit, a body that has left the world). */
    boolean forget(UUID id) {
        return table.remove(id) != null;
    }

    /** Drop and return every tracked body (plugin shutdown — the caller restores them all). */
    Set<UUID> drainAll() {
        Set<UUID> all = new HashSet<>(table.keySet());
        table.clear();
        return all;
    }
}
