package com.nyrrine.reliquary.index;

import com.nyrrine.reliquary.data.PlayerRecord;
import com.nyrrine.reliquary.data.PlayerStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the Index persists, behind one door.
 *
 * <p>The rest of the package speaks tallies, prescripts and Weavers; only this class knows those are rows in
 * a shared YAML record. Keep it that way — nothing outside here should have to learn how persistence works.
 *
 * <p>Two rules from the store contract shape what is and isn't possible here:
 * <ul>
 *   <li><b>Per-player data is get-by-UUID only.</b> Nothing here enumerates players, because answering "who
 *       has an open prescript?" would mean reading every playerdata file the server has ever accumulated.
 *       The one thing that must be enumerable — who holds the Weaver role — lives in the role index, which
 *       is separate precisely so that it can be.</li>
 *   <li><b>Never write files.</b> Mutate, then {@link PlayerRecord#touch()}; the store flushes off-thread
 *       within a second and on quit. The Index touches no disk and does no work on any tick.</li>
 * </ul>
 *
 * <p>The {@code prescript:} section is the Index's alone. Nothing here reads another system's namespace.
 */
public final class IndexStore {

    /** Our namespace in the shared record. */
    private static final String NS = "prescript";
    /** The role that may issue prescripts and rule on them. */
    private static final String WEAVER = "weaver";

    private static final String ACCOMPLISHED = "accomplished";
    private static final String UNACCOMPLISHED = "unaccomplished";
    private static final String ACTIVE = "active";

    private final PlayerStore store;

    public IndexStore(PlayerStore store) {
        this.store = store;
    }

    /** A player's standing in the Index: what they carried out, and what they didn't. */
    public record Tally(int accomplished, int unaccomplished) {
        /** Whether this player has any history at all — a fresh tally reads differently. */
        public boolean isEmpty() {
            return accomplished == 0 && unaccomplished == 0;
        }
    }

    // ---- tally ---------------------------------------------------------------------------------------

    /** This player's tally. Never null — an unknown player reads zero/zero, never an error. */
    public Tally tally(UUID id) {
        ConfigurationSection s = mine(id);
        return new Tally(s.getInt(ACCOMPLISHED, 0), s.getInt(UNACCOMPLISHED, 0));
    }

    /**
     * Record a Weaver's ruling: move the tally by one and close the prescript.
     *
     * <p>The only thing that ever changes a tally, and it is one-way. There is no decrement and no edit — a
     * ruling stands, and a Weaver who wants to be generous issues another prescript rather than rewriting
     * what the Index already recorded.
     *
     * @return false if that prescript wasn't theirs, or was already closed
     */
    public boolean rule(UUID id, UUID prescriptId, boolean accomplished) {
        PlayerRecord record = store.get(id);
        ConfigurationSection s = record.section(NS);
        ConfigurationSection open = s.getConfigurationSection(ACTIVE);
        if (open == null || !open.isConfigurationSection(prescriptId.toString())) return false;

        String key = accomplished ? ACCOMPLISHED : UNACCOMPLISHED;
        s.set(key, s.getInt(key, 0) + 1);
        open.set(prescriptId.toString(), null);
        record.touch();
        return true;
    }

    // ---- active prescripts ---------------------------------------------------------------------------

    /** This player's outstanding prescripts, oldest first. Never null; often empty. */
    public List<Prescript> active(UUID id) {
        ConfigurationSection open = mine(id).getConfigurationSection(ACTIVE);
        if (open == null) return List.of();
        List<Prescript> out = new ArrayList<>();
        for (String key : open.getKeys(false)) {
            ConfigurationSection one = open.getConfigurationSection(key);
            if (one == null) continue;
            Prescript p = Prescript.read(one);
            if (p != null) out.add(p); // a hand-edited file may hold a malformed entry; skip it, don't throw
        }
        out.sort(Comparator.comparingLong(Prescript::issued));
        return out;
    }

    /** Hand {@code p} to {@code id}. */
    public void issue(UUID id, Prescript p) {
        PlayerRecord record = store.get(id);
        ConfigurationSection s = record.section(NS);
        ConfigurationSection open = s.getConfigurationSection(ACTIVE);
        if (open == null) open = s.createSection(ACTIVE);
        p.write(open);
        record.touch();
    }

    /** The outstanding prescript with this id, or {@code null} if it isn't theirs or has been ruled on. */
    public Prescript find(UUID id, UUID prescriptId) {
        ConfigurationSection open = mine(id).getConfigurationSection(ACTIVE);
        if (open == null) return null;
        ConfigurationSection one = open.getConfigurationSection(prescriptId.toString());
        return one == null ? null : Prescript.read(one);
    }

    /** Drop a prescript without touching the tally — a withdrawal never counts against anyone. */
    public boolean withdraw(UUID id, UUID prescriptId) {
        PlayerRecord record = store.get(id);
        ConfigurationSection open = record.section(NS).getConfigurationSection(ACTIVE);
        if (open == null || !open.isConfigurationSection(prescriptId.toString())) return false;
        open.set(prescriptId.toString(), null);
        record.touch();
        return true;
    }

    /**
     * Raise a prescript's claim flag — the recipient says they've done it.
     *
     * <p>Persisted, deliberately, and the distinction is worth keeping straight. The Weaver's <i>queue</i> of
     * raised hands is in-memory and session-scoped, because enumerating it would need an index the store
     * rightly doesn't hand out. But the claim itself is a fact about this player, sits in this player's own
     * section, and costs one boolean. So a restart loses the queue — a convenience, rebuilt the moment
     * anyone re-claims — and never loses the claim. Without it, {@code /prescript} would quietly forget a
     * hand had ever gone up.
     *
     * @return false if it wasn't theirs, or the hand was already up
     */
    public boolean claim(UUID id, UUID prescriptId) {
        PlayerRecord record = store.get(id);
        ConfigurationSection open = record.section(NS).getConfigurationSection(ACTIVE);
        if (open == null) return false;
        ConfigurationSection one = open.getConfigurationSection(prescriptId.toString());
        if (one == null || one.getBoolean("claimed", false)) return false;
        one.set("claimed", true);
        record.touch();
        return true;
    }

    // ---- the Weaver role -----------------------------------------------------------------------------

    /**
     * Whether this player may issue and rule on prescripts.
     *
     * <p>Three ways in, in order of authority. The role index is the real mechanism — a role admins grant to
     * any player, which is what was asked for and what no permission node can deliver on a server with no
     * permissions plugin. The node is honoured anyway: it costs one {@code ||} and means LuckPerms would work
     * the day it were installed, with no code change. Ops are always Weavers, because it is an admin tool.
     */
    public boolean isWeaver(Player p) {
        return store.roles().has(p.getUniqueId(), WEAVER)
                || p.hasPermission("reliquary.weaver")
                || p.isOp();
    }

    /** Grant the role. Returns false if they already held it. */
    public boolean grantWeaver(UUID id) {
        if (store.roles().has(id, WEAVER)) return false;
        store.roles().grant(id, WEAVER);
        return true;
    }

    /** Revoke the role. Returns false if they didn't hold it. Their tally is untouched — history stands. */
    public boolean revokeWeaver(UUID id) {
        if (!store.roles().has(id, WEAVER)) return false;
        store.roles().revoke(id, WEAVER);
        return true;
    }

    /**
     * Everyone holding the role.
     *
     * <p>The only enumeration the Index performs, and the reason the store keeps roles out of per-player
     * data: grants are few, admin-driven and always resident, so this is a set read rather than a walk of
     * every playerdata file on disk.
     */
    public Set<UUID> weavers() {
        return store.roles().all(WEAVER);
    }

    /** This player's own section. Read fresh each time — records are not cached across ticks. */
    private ConfigurationSection mine(UUID id) {
        return store.get(id).section(NS);
    }
}
