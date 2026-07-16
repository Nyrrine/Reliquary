package com.nyrrine.reliquary.index;

import com.nyrrine.reliquary.data.PlayerRecord;
import com.nyrrine.reliquary.data.PlayerStore;
import com.nyrrine.reliquary.data.RoleIndex;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the Index's half of persistence — the promises {@link IndexStore} makes to the rest of
 * the package.
 *
 * <p>Deliberately layered. The shared store's own durability is its author's to prove, and it has its own
 * tests; what is unproven until here is the <b>Index's mapping onto it</b> — that a prescript survives being
 * turned into YAML text and read back, that a ruling is one-way, that a withdrawal never touches a tally, and
 * that the store is told to flush after every mutation. So the fake below is not a convenience: it is a real
 * {@link YamlConfiguration} with a real {@code saveToString}/{@code loadFromString} round trip standing in
 * for a restart, mirroring the live store's semantics exactly (see {@code YamlPlayerRecord.section} — an
 * existing section is returned, never re-created, or it would be wiped).
 */
class IndexStoreTest {

    // ---- a faithful stand-in for the shared store ---------------------------------------------------

    /** Mirrors YamlPlayerRecord/YamlPlayerStore semantics: live sections, and a snapshot that is YAML text. */
    private static final class FakeStore implements PlayerStore {
        final Map<UUID, YamlConfiguration> configs = new HashMap<>();
        final Map<UUID, Integer> touches = new HashMap<>();
        final FakeRoles roles = new FakeRoles();

        @Override
        public PlayerRecord get(UUID id) {
            YamlConfiguration config = configs.computeIfAbsent(id, k -> new YamlConfiguration());
            return new PlayerRecord() {
                @Override public UUID id() { return id; }

                @Override public ConfigurationSection section(String namespace) {
                    ConfigurationSection existing = config.getConfigurationSection(namespace);
                    return existing != null ? existing : config.createSection(namespace);
                }

                @Override public void touch() { touches.merge(id, 1, Integer::sum); }
            };
        }

        @Override public RoleIndex roles() { return roles; }

        int touches(UUID id) { return touches.getOrDefault(id, 0); }

        /** What a restart actually does to this data: through YAML text and back, nothing else surviving. */
        void restart() {
            Map<UUID, YamlConfiguration> reloaded = new HashMap<>();
            configs.forEach((id, config) -> {
                YamlConfiguration fresh = new YamlConfiguration();
                try {
                    fresh.loadFromString(config.saveToString());
                } catch (InvalidConfigurationException e) {
                    throw new AssertionError("the Index wrote YAML that cannot be read back", e);
                }
                reloaded.put(id, fresh);
            });
            configs.clear();
            configs.putAll(reloaded);
            touches.clear();
        }
    }

    private static final class FakeRoles implements RoleIndex {
        final Map<String, Set<UUID>> granted = new HashMap<>();

        @Override public boolean has(UUID id, String role) {
            return granted.getOrDefault(role, Set.of()).contains(id);
        }
        @Override public void grant(UUID id, String role) {
            granted.computeIfAbsent(role, k -> new LinkedHashSet<>()).add(id);
        }
        @Override public void revoke(UUID id, String role) {
            granted.getOrDefault(role, new HashSet<>()).remove(id);
        }
        @Override public Set<UUID> all(String role) {
            return Set.copyOf(granted.getOrDefault(role, Set.of()));
        }
    }

    private final FakeStore backing = new FakeStore();
    private final IndexStore store = new IndexStore(backing);
    private final UUID player = UUID.randomUUID();
    private final UUID weaver = UUID.randomUUID();

    private Prescript drawn(String text, long issued) {
        return new Prescript(UUID.randomUUID(), text, "kelp_vicinity", weaver, issued, false);
    }

    // ---- the tally ----------------------------------------------------------------------------------

    @Test
    void unknownPlayerReadsZeroRatherThanFailing() {
        IndexStore.Tally t = store.tally(UUID.randomUUID());
        assertEquals(0, t.accomplished());
        assertEquals(0, t.unaccomplished());
        assertTrue(t.isEmpty());
    }

    @Test
    void aRulingMovesTheTallyAndClosesThePrescript() {
        Prescript p = drawn("Eat 16 dried kelp in the vicinity of a player", 100);
        store.issue(player, p);

        assertTrue(store.rule(player, p.id(), true));
        assertEquals(1, store.tally(player).accomplished());
        assertEquals(0, store.tally(player).unaccomplished());
        assertTrue(store.active(player).isEmpty(), "a ruled prescript is no longer outstanding");
    }

    @Test
    void unaccomplishedMovesTheOtherColumn() {
        Prescript p = drawn("Ride a pig past a player without acknowledging them", 100);
        store.issue(player, p);

        assertTrue(store.rule(player, p.id(), false));
        assertEquals(0, store.tally(player).accomplished());
        assertEquals(1, store.tally(player).unaccomplished());
    }

    @Test
    void aPrescriptCannotBeRuledOnTwice() {
        Prescript p = drawn("Throw 3 eggs at the same player", 100);
        store.issue(player, p);

        assertTrue(store.rule(player, p.id(), true));
        assertFalse(store.rule(player, p.id(), true), "the second ruling must not double the tally");
        assertEquals(1, store.tally(player).accomplished());
    }

    @Test
    void rulingSomethingThatWasNeverIssuedChangesNothing() {
        assertFalse(store.rule(player, UUID.randomUUID(), true));
        assertTrue(store.tally(player).isEmpty());
    }

    @Test
    void withdrawalNeverCountsAgainstAnyone() {
        Prescript p = drawn("Ring a bell 10 times within earshot of a player", 100);
        store.issue(player, p);

        assertTrue(store.withdraw(player, p.id()));
        assertTrue(store.active(player).isEmpty());
        assertTrue(store.tally(player).isEmpty(), "a withdrawal is not an unaccomplished prescript");
    }

    // ---- surviving a restart ------------------------------------------------------------------------

    @Test
    void aPrescriptSurvivesARestartIntact() {
        Prescript p = drawn("Mine a stack of mud and throw it to a player today", 1752681600L);
        store.issue(player, p);

        backing.restart();

        List<Prescript> after = store.active(player);
        assertEquals(1, after.size());
        Prescript back = after.get(0);
        assertEquals(p.id(), back.id());
        assertEquals(p.text(), back.text());
        assertEquals("kelp_vicinity", back.poolId());
        assertEquals(weaver, back.issuer());
        assertEquals(1752681600L, back.issued());
        assertFalse(back.claimed());
    }

    @Test
    void aTallySurvivesARestart() {
        Prescript a = drawn("one", 100);
        Prescript b = drawn("two", 200);
        store.issue(player, a);
        store.issue(player, b);
        store.rule(player, a.id(), true);
        store.rule(player, b.id(), false);

        backing.restart();

        assertEquals(1, store.tally(player).accomplished());
        assertEquals(1, store.tally(player).unaccomplished());
    }

    @Test
    void aCustomPrescriptRoundTripsAsCustom() {
        // pool_id is null for a hand-written one, and YAML simply omits it — the read must not invent one.
        Prescript custom = new Prescript(UUID.randomUUID(), "Go and apologise to the fox.", null,
                weaver, 100, false);
        store.issue(player, custom);

        backing.restart();

        Prescript back = store.active(player).get(0);
        assertNull(back.poolId());
        assertTrue(back.isCustom());
        assertEquals("Go and apologise to the fox.", back.text());
    }

    // ---- claiming -----------------------------------------------------------------------------------

    @Test
    void aClaimSurvivesARestartEvenThoughTheQueueDoesNot() {
        // The point of persisting the flag: the Weaver's queue is session-scoped, but a raised hand is a
        // fact about the player and must not be quietly forgotten.
        Prescript p = drawn("Eat 8 sweet berries while crouched beside a player", 100);
        store.issue(player, p);
        assertTrue(store.claim(player, p.id()));

        backing.restart();

        assertTrue(store.active(player).get(0).claimed());
    }

    @Test
    void aHandCannotBeRaisedTwice() {
        Prescript p = drawn("Feed 64 items into a composter", 100);
        store.issue(player, p);

        assertTrue(store.claim(player, p.id()));
        assertFalse(store.claim(player, p.id()), "a second claim must not re-ping the Weavers");
    }

    @Test
    void claimingSomethingNotYoursDoesNothing() {
        assertFalse(store.claim(player, UUID.randomUUID()));
    }

    // ---- ordering and resilience --------------------------------------------------------------------

    @Test
    void outstandingPrescriptsReadOldestFirst() {
        Prescript newer = drawn("newer", 300);
        Prescript older = drawn("older", 100);
        store.issue(player, newer);
        store.issue(player, older);

        List<Prescript> active = store.active(player);
        assertEquals("older", active.get(0).text());
        assertEquals("newer", active.get(1).text());
    }

    @Test
    void aHandEditedMalformedEntryIsSkippedRatherThanThrown() {
        // The store is hand-editable on purpose — admins rig prescripts beforehand. One fat-fingered edit
        // must cost that entry, never the player's whole record.
        Prescript good = drawn("a real one", 100);
        store.issue(player, good);
        ConfigurationSection open = backing.get(player).section("prescript")
                .getConfigurationSection("active");
        assertNotNull(open);
        open.createSection("not-a-uuid").set("text", "nonsense");

        List<Prescript> active = store.active(player);
        assertEquals(1, active.size(), "the good prescript survives its malformed neighbour");
        assertEquals("a real one", active.get(0).text());
    }

    @Test
    void twoPlayersDoNotSeeEachOthersPrescripts() {
        UUID other = UUID.randomUUID();
        store.issue(player, drawn("mine", 100));

        assertTrue(store.active(other).isEmpty());
        assertNull(store.find(other, store.active(player).get(0).id()));
    }

    // ---- the store contract -------------------------------------------------------------------------

    @Test
    void everyMutationTellsTheStoreToFlush() {
        // The contract's rule: mutate, then touch(). Never write files. A tally that forgets to touch is a
        // tally that loses its last increment to a restart.
        Prescript p = drawn("Break 64 dirt with a diamond shovel", 100);

        store.issue(player, p);
        assertEquals(1, backing.touches(player), "issue must touch");

        store.claim(player, p.id());
        assertEquals(2, backing.touches(player), "claim must touch");

        store.rule(player, p.id(), true);
        assertEquals(3, backing.touches(player), "a ruling must touch");
    }

    @Test
    void aFailedMutationDoesNotTouch() {
        store.rule(player, UUID.randomUUID(), true);
        store.claim(player, UUID.randomUUID());
        store.withdraw(player, UUID.randomUUID());
        assertEquals(0, backing.touches(player), "nothing changed, so nothing needs writing");
    }

    @Test
    void theIndexWritesOnlyItsOwnNamespace() {
        // Namespace discipline: the file is shared with other systems, which is an implementation detail and
        // not licence to write anywhere else.
        store.issue(player, drawn("mine", 100));
        store.rule(player, store.active(player).get(0).id(), true);

        Set<String> top = backing.configs.get(player).getKeys(false);
        assertEquals(Set.of("prescript"), top);
    }

    // ---- the Weaver role ----------------------------------------------------------------------------

    @Test
    void theRoleIsGrantedRevokedAndEnumerable() {
        assertTrue(store.grantWeaver(weaver));
        assertFalse(store.grantWeaver(weaver), "granting twice is not an error, but it is not a change");
        assertEquals(Set.of(weaver), store.weavers());

        assertTrue(store.revokeWeaver(weaver));
        assertFalse(store.revokeWeaver(weaver));
        assertTrue(store.weavers().isEmpty());
    }

    @Test
    void revokingTheRoleLeavesTheirTallyStanding() {
        // A change of office does not undo history.
        Prescript p = drawn("something they did", 100);
        store.issue(weaver, p);
        store.rule(weaver, p.id(), true);
        store.grantWeaver(weaver);

        store.revokeWeaver(weaver);

        assertEquals(1, store.tally(weaver).accomplished());
    }

    @Test
    void theRoleLivesOutsideThePlayerRecord() {
        // Roles are a separate enumerable index precisely so that /prescript weaver list never has to walk
        // every playerdata file. Granting must not write to the player's record at all.
        store.grantWeaver(weaver);
        assertEquals(0, backing.touches(weaver));
        assertFalse(backing.configs.containsKey(weaver));
    }
}
