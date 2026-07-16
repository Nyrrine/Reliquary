package com.nyrrine.reliquary.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the role index — the half of the store that exists precisely so it can be
 * enumerated ("who are all the Weavers?") without scanning every player file.
 */
class RoleIndexTest {

    @TempDir
    Path dir;

    private static Logger quiet() {
        Logger log = Logger.getLogger("ReliquaryRoleTest");
        log.setUseParentHandlers(false);
        return log;
    }

    private YamlPlayerStore store(StoreScheduler scheduler) {
        return new YamlPlayerStore(dir.toFile(), quiet(), scheduler);
    }

    private Path rolesFile() {
        return dir.resolve("roles.yml");
    }

    @Test
    void grantHasRevoke() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        UUID id = UUID.randomUUID();

        assertFalse(roles.has(id, "weaver"), "nobody holds a role by default");
        roles.grant(id, "weaver");
        assertTrue(roles.has(id, "weaver"));
        roles.revoke(id, "weaver");
        assertFalse(roles.has(id, "weaver"));
    }

    @Test
    void grantAndRevokeAreIdempotent() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        UUID id = UUID.randomUUID();

        roles.grant(id, "weaver");
        roles.grant(id, "weaver");
        assertEquals(1, roles.all("weaver").size(), "granting twice holds it once");

        roles.revoke(id, "weaver");
        assertDoesNotBlowUp(() -> roles.revoke(id, "weaver"));
        assertTrue(roles.all("weaver").isEmpty());
    }

    private static void assertDoesNotBlowUp(Runnable r) {
        r.run();
    }

    @Test
    void allEnumeratesEveryHolder() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        roles.grant(a, "weaver");
        roles.grant(b, "weaver");
        roles.grant(c, "other");

        assertEquals(Set.of(a, b), roles.all("weaver"), "all() backs /prescript weaver list");
        assertEquals(Set.of(c), roles.all("other"), "roles are independent of each other");
    }

    @Test
    void allOfAnUnknownRoleIsEmptyNotNull() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        Set<UUID> held = roles.all("nobody-has-this");
        assertNotNull(held);
        assertTrue(held.isEmpty());
    }

    @Test
    void allIsASnapshotTheCallerCannotCorrupt() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        UUID a = UUID.randomUUID();
        roles.grant(a, "weaver");

        Set<UUID> held = roles.all("weaver");
        assertThrows(UnsupportedOperationException.class, () -> held.add(UUID.randomUUID()));
        assertEquals(Set.of(a), roles.all("weaver"), "the index is unchanged");
    }

    @Test
    void grantsSurviveAReload() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        store.roles().grant(a, "weaver");
        store.roles().grant(b, "weaver");
        sched.fire();

        assertTrue(Files.isRegularFile(rolesFile()), "roles.yml sits at the plugin folder root");
        assertEquals(Set.of(a, b), store(new DirectStoreScheduler()).roles().all("weaver"),
                "grants must come back after a restart");
    }

    @Test
    void revokeSurvivesAReload() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        store.roles().grant(a, "weaver");
        store.roles().grant(b, "weaver");
        store.roles().revoke(a, "weaver");
        sched.fire();

        assertEquals(Set.of(b), store(new DirectStoreScheduler()).roles().all("weaver"),
                "a revoke must not come back from the dead on restart");
    }

    @Test
    void grantIsFlushedOnClose() {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID a = UUID.randomUUID();

        store.roles().grant(a, "weaver");
        store.close(); // the debounce never fired

        assertTrue(store(new DirectStoreScheduler()).roles().has(a, "weaver"),
                "onDisable must not drop a pending grant");
    }

    @Test
    void handEditedRolesFileIsRead() throws IOException {
        UUID a = UUID.randomUUID();
        Files.writeString(rolesFile(), "weaver:\n- " + a + "\n");

        assertTrue(store(new DirectStoreScheduler()).roles().has(a, "weaver"),
                "roles.yml is meant to be hand-editable offline");
    }

    @Test
    void onDiskShapeIsAPlainListOfUuids() throws IOException {
        DirectStoreScheduler sched = new DirectStoreScheduler();
        YamlPlayerStore store = store(sched);
        UUID a = UUID.randomUUID();
        store.roles().grant(a, "weaver");
        sched.fire();

        String yaml = Files.readString(rolesFile());
        assertTrue(yaml.contains("weaver:"), "expected a 'weaver:' key, got:\n" + yaml);
        assertTrue(yaml.contains(a.toString()), "expected the uuid listed under it, got:\n" + yaml);
    }

    @Test
    void malformedUuidInRolesFileIsSkippedNotFatal() throws IOException {
        UUID good = UUID.randomUUID();
        Files.writeString(rolesFile(), "weaver:\n- not-a-uuid\n- " + good + "\n");

        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        assertEquals(Set.of(good), roles.all("weaver"), "one bad hand-edit must not void the whole role");
    }

    @Test
    void corruptRolesFileLeavesAnEmptyIndexAndDoesNotThrow() throws IOException {
        Files.writeString(rolesFile(), "weaver: [\n");

        YamlPlayerStore store = store(new DirectStoreScheduler());
        assertTrue(store.roles().all("weaver").isEmpty(), "fall back to no grants rather than failing enable");
    }

    @Test
    void roleNamesAreCaseInsensitive() {
        RoleIndex roles = store(new DirectStoreScheduler()).roles();
        UUID a = UUID.randomUUID();

        roles.grant(a, "Weaver");
        assertTrue(roles.has(a, "weaver"), "a hand-edited 'Weaver' should still resolve");
        assertEquals(Set.of(a), roles.all("WEAVER"));
    }
}
