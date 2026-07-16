package com.nyrrine.reliquary.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the per-player store — the promises it makes to its callers: what you write
 * comes back after a restart, namespaces stay each other's business, an unknown player is empty
 * rather than null, and a mangled file costs that player's data but never their join.
 */
class PlayerStoreTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private int loggers;

    /** A logger that captures instead of printing, so the corrupt-file path can be asserted on. */
    private Logger logger() {
        Logger log = Logger.getLogger("ReliquaryStoreTest-" + loggers++);
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return log;
    }

    private YamlPlayerStore store(StoreScheduler scheduler) {
        return new YamlPlayerStore(dir.toFile(), logger(), scheduler);
    }

    /** Reopen from the same folder — a fresh store shares nothing but the disk. */
    private YamlPlayerStore reopen() {
        return store(new DirectStoreScheduler());
    }

    private Path playerFile(UUID id) {
        return dir.resolve("playerdata").resolve(id + ".yml");
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void writtenValueSurvivesFlushAndReload() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id).section("prescript").set("tally", 7);
        store.get(id).touch();
        sched.fire(); // the debounce window elapses

        assertTrue(Files.isRegularFile(playerFile(id)), "the record should be on disk at playerdata/<uuid>.yml");
        assertEquals(7, reopen().get(id).section("prescript").getInt("tally"),
                "a value written before the flush should survive a restart");
    }

    @Test
    void touchArmsTheDebounceRatherThanWritingImmediately() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id).section("prescript").set("tally", 1);
        store.get(id).touch();

        assertEquals(0, sched.writes, "touch() must not write on the spot");
        assertTrue(sched.armed(), "touch() should arm the flush");
        assertEquals(YamlPlayerStore.DEBOUNCE_TICKS, sched.lastDelayTicks(), "~1s debounce");

        sched.fire();
        assertEquals(1, sched.writes, "the debounced flush writes once");
    }

    @Test
    void repeatedTouchesCoalesceIntoOneWrite() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            store.get(id).section("prescript").set("tally", i);
            store.get(id).touch();
        }
        sched.fire();

        assertEquals(1, sched.writes, "20 touches inside one window should collapse to a single write");
        assertEquals(19, reopen().get(id).section("prescript").getInt("tally"), "and it's the latest value");
    }

    // ---- namespaces --------------------------------------------------------

    @Test
    void namespacesDoNotDisturbEachOther() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id).section("prescript").set("tally", 7);
        store.get(id).section("distortion").set("level", 3);
        store.get(id).touch();
        sched.fire();

        YamlPlayerStore back = reopen();
        assertEquals(7, back.get(id).section("prescript").getInt("tally"));
        assertEquals(3, back.get(id).section("distortion").getInt("level"));
        assertFalse(back.get(id).section("prescript").contains("level"), "distortion's key must not leak into prescript");
        assertFalse(back.get(id).section("distortion").contains("tally"), "prescript's key must not leak into distortion");
    }

    @Test
    void writingOneNamespaceLeavesTheOtherIntactAcrossReloads() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id).section("distortion").set("level", 3);
        store.get(id).touch();
        sched.fire();

        // A later session touches only prescript.
        DirectStoreScheduler sched2 = new DirectStoreScheduler();
        YamlPlayerStore second = store(sched2);
        second.get(id).section("prescript").set("tally", 7);
        second.get(id).touch();
        sched2.fire();

        YamlPlayerStore back = reopen();
        assertEquals(3, back.get(id).section("distortion").getInt("level"),
                "distortion's state must survive a write that only touched prescript");
        assertEquals(7, back.get(id).section("prescript").getInt("tally"));
    }

    @Test
    void sectionIsNeverNullAndIsStableAcrossCalls() {
        YamlPlayerStore store = store(new DirectStoreScheduler());
        PlayerRecord rec = store.get(UUID.randomUUID());

        assertNotNull(rec.section("prescript"), "an absent section is created empty, never null");
        rec.section("prescript").set("tally", 4);
        assertEquals(4, rec.section("prescript").getInt("tally"),
                "asking for the section again must not wipe it");
    }

    // ---- unknown players ---------------------------------------------------

    @Test
    void unknownPlayerGetsAnEmptyNonNullRecord() {
        YamlPlayerStore store = store(new DirectStoreScheduler());
        UUID id = UUID.randomUUID();

        PlayerRecord rec = store.get(id);
        assertNotNull(rec, "get() never returns null");
        assertEquals(id, rec.id());
        assertTrue(rec.section("prescript").getKeys(false).isEmpty(), "a player we've never seen is empty");
        assertFalse(Files.exists(playerFile(id)), "and merely reading them must not create a file");
    }

    @Test
    void offlinePlayerStateCanBePreassignedAndReadBack() {
        // The "admins may rig it beforehand" case: nobody is online, this is all disk.
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID absent = UUID.randomUUID();

        store.get(absent).section("prescript").set("rigged", true);
        store.get(absent).touch();
        sched.fire();

        assertTrue(reopen().get(absent).section("prescript").getBoolean("rigged"),
                "a record written for an offline player should load on demand later");
    }

    // ---- corruption --------------------------------------------------------

    @Test
    void corruptFileYieldsAnEmptyRecordAndDoesNotThrow() throws IOException {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir.resolve("playerdata"));
        Files.writeString(playerFile(id), "prescript: [1, 2\ndistortion: {\n"); // unterminated flow nodes

        YamlPlayerStore store = store(new DirectStoreScheduler());
        PlayerRecord rec = assertDoesNotThrow(() -> store.get(id), "a mangled file must never throw into the join");

        assertNotNull(rec);
        assertTrue(rec.section("prescript").getKeys(false).isEmpty(), "fall back to empty");
        assertTrue(warnings.stream().anyMatch(w -> w.contains(id.toString())), "and say so in the log");
    }

    @Test
    void nonMappingFileYieldsAnEmptyRecordAndDoesNotThrow() throws IOException {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir.resolve("playerdata"));
        Files.writeString(playerFile(id), "just a bare string, not a mapping\n");

        YamlPlayerStore store = store(new DirectStoreScheduler());
        PlayerRecord rec = assertDoesNotThrow(() -> store.get(id));
        assertTrue(rec.section("prescript").getKeys(false).isEmpty());
    }

    @Test
    void corruptFileIsKeptAsideRatherThanOverwritten() throws IOException {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir.resolve("playerdata"));
        Files.writeString(playerFile(id), "prescript: [1, 2\n");

        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        store.get(id).section("prescript").set("tally", 1);
        store.get(id).touch();
        sched.fire(); // this overwrites the bad file with the empty record

        try (var listing = Files.list(dir.resolve("playerdata"))) {
            assertTrue(listing.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")),
                    "the unreadable original should be preserved, not silently destroyed");
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    void closeFlushesSynchronouslyAndCancelsThePendingDebounce() {
        // The onDisable contract: a queued write must not be left to a scheduler that is about to
        // cancel it. close() has to snapshot and drain here and now.
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id).section("prescript").set("tally", 42);
        store.get(id).touch();
        assertTrue(sched.armed(), "a write is pending");

        store.close();

        assertFalse(sched.armed(), "close() cancels the debounce rather than racing it");
        assertTrue(sched.shutdown, "close() drains the writer before returning");
        assertEquals(42, reopen().get(id).section("prescript").getInt("tally"),
                "the pending value must be on disk once close() returns");
    }

    @Test
    void quitFlushesAndEvicts() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.load(id);
        store.get(id).section("prescript").set("tally", 5);
        store.get(id).touch();
        assertEquals(1, store.cached());

        store.unload(id); // quit, before the debounce ever fires

        assertEquals(0, store.cached(), "the record is evicted on quit");
        assertEquals(5, reopen().get(id).section("prescript").getInt("tally"),
                "quit flushes without waiting for the debounce");
    }

    // ---- threading ---------------------------------------------------------

    @Test
    void offThreadReadFailsLoudlyRatherThanCorruptingTheCache() {
        // The store's collections are unsynchronised on purpose, so an off-thread caller has to be
        // told, not tolerated. AsyncChatEvent is the live example: it fires off the main thread.
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        store.get(id); // on the main thread, this is the ordinary case
        sched.onMainThread = false;

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.get(id),
                "an off-thread read must throw rather than quietly walk the cache");
        assertTrue(e.getMessage().contains("main"), "the message should name the actual problem: " + e.getMessage());
    }

    @Test
    void offThreadTouchFailsLoudly() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        PlayerRecord rec = store.get(id); // handed out on the main thread, kept across the hop
        sched.onMainThread = false;

        assertThrows(IllegalStateException.class, rec::touch,
                "marking a record dirty off-thread must throw rather than race the dirty set");
        assertEquals(0, sched.writes, "and nothing should have been written");
    }

    @Test
    void theGuardDoesNotFireOnTheMainThread() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID id = UUID.randomUUID();

        assertDoesNotThrow(() -> {
            store.get(id).section("prescript").set("tally", 1);
            store.get(id).touch();
            sched.fire();
        }, "the guard must stay out of the way of the normal path");
    }

    // ---- cache pruning -----------------------------------------------------

    @Test
    void cacheDoesNotGrowWithoutBound() {
        YamlPlayerStore store = store(new DirectStoreScheduler());
        for (int i = 0; i < YamlPlayerStore.MAX_CACHED + 100; i++) store.get(UUID.randomUUID());

        assertTrue(store.cached() <= YamlPlayerStore.MAX_CACHED,
                "offline lookups must not accumulate forever, was " + store.cached());
    }

    @Test
    void onlinePlayersAreNeverPrunedOut() {
        YamlPlayerStore store = store(new DirectStoreScheduler());
        UUID online = UUID.randomUUID();
        store.load(online);
        // Unsaved, in-memory only: if the record were evicted, it would reload from disk as empty.
        store.get(online).section("prescript").set("tally", 9);

        for (int i = 0; i < YamlPlayerStore.MAX_CACHED + 100; i++) store.get(UUID.randomUUID());

        assertTrue(store.cached() <= YamlPlayerStore.MAX_CACHED);
        assertEquals(9, store.get(online).section("prescript").getInt("tally"),
                "an online player's record must survive pruning");
    }

    @Test
    void prunedRecordIsFlushedBeforeItIsDropped() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID victim = UUID.randomUUID();

        store.get(victim).section("prescript").set("tally", 3);
        store.get(victim).touch(); // dirty, debounce not yet fired
        for (int i = 0; i < YamlPlayerStore.MAX_CACHED + 100; i++) store.get(UUID.randomUUID());

        assertEquals(3, reopen().get(victim).section("prescript").getInt("tally"),
                "eviction must not throw away an unwritten change");
    }
}
