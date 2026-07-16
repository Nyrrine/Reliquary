package com.nyrrine.reliquary.data;

import com.nyrrine.reliquary.index.IndexStore;
import com.nyrrine.reliquary.index.Prescript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Index against the real store, on real disk — the composition, not either half.
 *
 * <p>Lives in {@code data} rather than {@code index} only because {@link YamlPlayerStore}'s test constructor
 * is package-private, and that seam is deliberate: the store's internals stay internal, and no production
 * API is widened for a test's convenience.
 *
 * <p><b>Why this exists when both halves are already tested.</b> {@code PlayerStoreTest} proves a value
 * survives a flush and reload — with a <i>flat</i> key ({@code section("prescript").set("tally", 7)}).
 * {@code IndexStoreTest} proves the Index's mapping survives a YAML round trip — against a fake. Neither
 * proves the thing the Index actually does to the real store: <b>nested</b> sections, three deep
 * ({@code prescript.active.<uuid>.text}), created, read back, and <b>deleted</b> again, through a real
 * snapshot and a real file. Two proven halves ought to compose. This asserts that they do.
 *
 * <p>Everything here is a restart. Nothing is asserted in memory that isn't also asserted after reopening
 * from disk, because a tally that only exists in RAM is the one thing this system cannot afford.
 */
class IndexStorePersistenceTest {

    @TempDir
    Path dir;

    private final Logger log = Logger.getLogger("IndexStorePersistenceTest");
    private DirectStoreScheduler scheduler;
    private YamlPlayerStore live;

    private final UUID player = UUID.randomUUID();
    private final UUID weaver = UUID.randomUUID();

    /** @TempDir is injected after field initialisers run, so the store is built here rather than inline. */
    @BeforeEach
    void openStore() {
        scheduler = new DirectStoreScheduler();
        live = new YamlPlayerStore(dir.toFile(), log, scheduler);
    }

    private IndexStore index() {
        return new IndexStore(live);
    }

    /** Flush the debounce, then come back as a fresh process would: nothing shared but the disk. */
    private IndexStore restart() {
        scheduler.fire();
        live.close(); // flushes synchronously and cancels any pending debounce
        scheduler = new DirectStoreScheduler();
        live = new YamlPlayerStore(dir.toFile(), log, scheduler);
        return new IndexStore(live);
    }

    private Prescript written(String text, long issued) {
        return new Prescript(UUID.randomUUID(), text, weaver, issued, false);
    }

    @Test
    void aPrescriptSurvivesARealRestart() {
        // Three levels deep through a real snapshot and a real file — the thing neither half's tests reach.
        Prescript p = written("Eat 16 dried kelp in the vicinity of a player", 1752681600L);
        index().issue(player, p);

        IndexStore after = restart();

        List<Prescript> active = after.active(player);
        assertEquals(1, active.size(), "the prescript should still be outstanding after a restart");
        Prescript back = active.get(0);
        assertEquals(p.id(), back.id());
        assertEquals("Eat 16 dried kelp in the vicinity of a player", back.text());
        assertEquals(weaver, back.issuer());
        assertEquals(1752681600L, back.issued());
        assertFalse(back.claimed());
    }

    @Test
    void aRulingSurvivesARealRestart() {
        Prescript p = written("Ring a bell 10 times within earshot of a player", 100);
        index().issue(player, p);
        assertTrue(index().rule(player, p.id(), true));

        IndexStore after = restart();

        assertEquals(1, after.tally(player).accomplished());
        assertEquals(0, after.tally(player).unaccomplished());
        assertTrue(after.active(player).isEmpty(), "a ruled prescript must not come back outstanding");
    }

    @Test
    void aWithdrawalSurvivesARealRestart() {
        // Deleting a nested child is set(key, null) — worth proving it stays deleted through a real file,
        // rather than reappearing because the removal never reached the snapshot.
        Prescript p = written("Throw 3 eggs at the same player", 100);
        index().issue(player, p);
        assertTrue(index().withdraw(player, p.id()));

        IndexStore after = restart();

        assertTrue(after.active(player).isEmpty(), "a withdrawn prescript must stay withdrawn");
        assertTrue(after.tally(player).isEmpty(), "and must never have counted against them");
        assertNull(after.find(player, p.id()));
    }

    @Test
    void aClaimSurvivesARealRestart() {
        Prescript p = written("Ride a pig past a player without acknowledging them", 100);
        index().issue(player, p);
        assertTrue(index().claim(player, p.id()));

        IndexStore after = restart();

        assertTrue(after.active(player).get(0).claimed(),
                "the queue may be lost on restart, but never the raised hand");
    }

    @Test
    void severalPrescriptsSurviveTogetherAndKeepTheirOrder() {
        IndexStore store = index();
        Prescript older = written("older", 100);
        Prescript newer = written("newer", 300);
        store.issue(player, newer);
        store.issue(player, older);

        IndexStore after = restart();

        List<Prescript> active = after.active(player);
        assertEquals(2, active.size());
        assertEquals("older", active.get(0).text(), "oldest first, regardless of how YAML ordered the keys");
        assertEquals("newer", active.get(1).text());
    }

    @Test
    void theWeaverRoleSurvivesARealRestart() {
        // Roles live in their own always-loaded file, not the player record — a different path to disk.
        assertTrue(index().grantWeaver(weaver));

        IndexStore after = restart();

        assertEquals(java.util.Set.of(weaver), after.weavers());
    }

    @Test
    void aRevokedRoleStaysRevokedAcrossARestart() {
        index().grantWeaver(weaver);
        restart();
        assertTrue(index().revokeWeaver(weaver));

        IndexStore after = restart();

        assertTrue(after.weavers().isEmpty());
    }

    @Test
    void theIndexLeavesAnotherSystemsSectionAlone() {
        // The record is shared with distortion; that's an implementation detail, not licence to disturb it.
        live.get(player).section("distortion").set("golden_chains", 3);
        live.get(player).touch();

        IndexStore store = index();
        Prescript p = written("Feed 64 items into a composter", 100);
        store.issue(player, p);
        store.rule(player, p.id(), true);

        restart();

        assertEquals(3, live.get(player).section("distortion").getInt("golden_chains"),
                "the Index must not have trodden on another system's namespace");
        assertEquals(1, index().tally(player).accomplished());
    }

    @Test
    void aTallyIsNotLostWhenTheServerStopsWithoutADebounce() {
        // The realistic crash-shaped case: a ruling lands and the server goes down before the ~1s window
        // elapses. close() must flush it — this is the increment the whole system exists to remember.
        Prescript p = written("Break 64 dirt with a diamond shovel", 100);
        index().issue(player, p);
        scheduler.fire();
        index().rule(player, p.id(), false);

        live.close(); // no fire() first: the debounce never got the chance to run
        YamlPlayerStore reopened = new YamlPlayerStore(dir.toFile(), log, new DirectStoreScheduler());

        assertEquals(1, new IndexStore(reopened).tally(player).unaccomplished(),
                "close() must flush a ruling that the debounce never got to");
    }
}
