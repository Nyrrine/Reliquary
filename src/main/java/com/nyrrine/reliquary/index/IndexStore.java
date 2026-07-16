package com.nyrrine.reliquary.index;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the Index persists, behind one door.
 *
 * <p><b>Why this class exists.</b> The shared player store (March's {@code com.nyrrine.reliquary.data}) is
 * still landing. Rather than mirror her interface — two copies of one contract, free to drift — the Index
 * talks only to this facade, which speaks the Index's own vocabulary: tallies, prescripts, Weavers. Today it
 * is an in-memory map. When the real store arrives, the bodies below swap to {@code plugin.store()} and
 * <b>nothing else in the package changes</b>, because nothing else in the package knows how persistence
 * works.
 *
 * <p>The in-memory backing is throwaway and honest about it: <b>state does not survive a restart yet.</b>
 * That is the one success criterion the Index cannot currently meet, and it is a dependency, not a design.
 *
 * <p>Two rules from the store contract are baked into the shape here, so they survive the swap:
 * <ul>
 *   <li><b>Per-player data is get-by-UUID only.</b> Nothing here enumerates players. The one thing that must
 *       be enumerable — who holds the Weaver role — is a role index, deliberately separate.</li>
 *   <li><b>Never write files.</b> Mutations mark the record dirty and the store flushes off-thread; the
 *       Index touches no disk and does no work on the tick.</li>
 * </ul>
 */
public final class IndexStore {

    /** A player's standing in the Index: what they carried out, and what they didn't. */
    public record Tally(int accomplished, int unaccomplished) {
        /** Whether this player has any history at all — a fresh tally reads differently. */
        public boolean isEmpty() {
            return accomplished == 0 && unaccomplished == 0;
        }

        /** Total prescripts ruled on. */
        public int total() {
            return accomplished + unaccomplished;
        }
    }

    // ---- in-memory backing — every field below dies at the rebase -----------------------------------
    //
    // TODO(rebase onto March's data/): replace these three maps with plugin.store().
    //   tallies/actives  -> plugin.store().get(id).section("prescript") + .touch()
    //   weavers          -> plugin.store().roles()   ["weaver"]
    // The method signatures are already the ones the real store supports, so the swap is body-only.

    private final Map<UUID, Tally> tallies = new HashMap<>();
    private final Map<UUID, List<Prescript>> actives = new HashMap<>();
    private final Set<UUID> weavers = new LinkedHashSet<>();

    // ---- tally ---------------------------------------------------------------------------------------

    /** This player's tally. Never null — an unknown player reads zero/zero, never an error. */
    public Tally tally(UUID id) {
        return tallies.getOrDefault(id, new Tally(0, 0));
    }

    // NOTE: nothing writes a tally yet, so every tally currently reads zero/zero. The only thing that may
    // ever move one is a Weaver's ruling, and the adjudication model is awaiting Nyrrine's approval — so the
    // mutator lands with the flow it belongs to rather than sitting here unused against a decision she has
    // not made. The read path below is complete and correct; it simply has nothing to report yet.

    // ---- active prescripts ---------------------------------------------------------------------------

    /** This player's outstanding prescripts, oldest first. Never null; often empty. */
    public List<Prescript> active(UUID id) {
        return List.copyOf(actives.getOrDefault(id, List.of()));
    }

    /** Hand {@code p} to {@code id}. */
    public void issue(UUID id, Prescript p) {
        actives.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
        // TODO(rebase): plugin.store().get(id).touch();
    }

    /** The outstanding prescript with this id, or {@code null} if it isn't theirs or has been ruled on. */
    public Prescript find(UUID id, UUID prescriptId) {
        for (Prescript p : actives.getOrDefault(id, List.of())) {
            if (p.id().equals(prescriptId)) return p;
        }
        return null;
    }

    /** Drop a prescript without touching the tally — a withdrawal never counts against anyone. */
    public boolean withdraw(UUID id, UUID prescriptId) {
        List<Prescript> list = actives.get(id);
        if (list == null) return false;
        boolean removed = list.removeIf(p -> p.id().equals(prescriptId));
        if (list.isEmpty()) actives.remove(id);
        // TODO(rebase): if (removed) plugin.store().get(id).touch();
        return removed;
    }

    // The claim flag is persisted rather than queued — see Prescript#claimed. The mutator lands with
    // /prescript claim, which is part of the held adjudication flow.

    // ---- the Weaver role -----------------------------------------------------------------------------

    /**
     * Whether this player may issue and rule on prescripts.
     *
     * <p>Three ways in, in order of authority. The role index is the real mechanism — a role admins grant to
     * any player, which is what Nyrrine asked for and what no permission node can do on a server without a
     * permissions plugin. The node is honoured anyway: it costs one {@code ||} and means LuckPerms works for
     * free if one is ever installed. Ops are always Weavers, because it is an admin tool.
     */
    public boolean isWeaver(Player p) {
        return weavers.contains(p.getUniqueId())
                || p.hasPermission("reliquary.weaver")
                || p.isOp();
        // TODO(rebase): weavers.contains(...) -> plugin.store().roles().has(p.getUniqueId(), "weaver")
    }

    /** Grant the role. Returns false if they already held it. */
    public boolean grantWeaver(UUID id) {
        return weavers.add(id); // TODO(rebase): plugin.store().roles().grant(id, "weaver")
    }

    /** Revoke the role. Returns false if they didn't hold it. Their tally is untouched — history stands. */
    public boolean revokeWeaver(UUID id) {
        return weavers.remove(id); // TODO(rebase): plugin.store().roles().revoke(id, "weaver")
    }

    /**
     * Everyone holding the role.
     *
     * <p>The only enumeration the Index performs, and the reason the store keeps roles out of per-player
     * data: grants are few, admin-driven, and always resident, so this is a set read rather than a walk of
     * every playerdata file the server has ever accumulated.
     */
    public Set<UUID> weavers() {
        return Set.copyOf(weavers); // TODO(rebase): plugin.store().roles().all("weaver")
    }
}
