package com.nyrrine.reliquary.distortion;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently wearing Carmen, and the swap in and back out.
 *
 * <p>The whole identity change is one call — {@code setPlayerProfile}. The server already knows the
 * dance: it hides the player from everyone who can see them, swaps the profile underneath, shows
 * them again, and re-sends the state a respawn would otherwise eat. Above-head name, tab list and
 * chat all follow from that one swap. There is no packet work here and, deliberately, no scoreboard
 * team — teams are how nametag plugins fight each other, because joining one evicts you from any
 * other. We stay out of that entirely.
 *
 * <p>The swap lives only in memory, so a relog always restores the real identity even if the server
 * died mid-form. That's a safety net, not the plan: every exit path below puts the profile back.
 */
final class CarmenForm implements Listener {

    /** What a player looked like before the voice took them. */
    private record Original(PlayerProfile profile, Component displayName) {
    }

    /**
     * Concurrent because chat isn't on the main thread. Transforms happen on the main thread from a
     * command, but the chat mask asks {@link #isCarmen} from Paper's async chat thread — a plain
     * HashMap read against a live write is the kind of bug that shows up as nonsense once a month on
     * a busy server and never once while testing.
     */
    private final Map<UUID, Original> originals = new ConcurrentHashMap<>();

    /** Safe from any thread — the chat mask calls this off the main thread on every message. */
    boolean isCarmen(Player player) {
        return originals.containsKey(player.getUniqueId());
    }

    /** @return false if they were already in form. */
    boolean become(Player player) {
        if (isCarmen(player)) return false;

        // getPlayerProfile() hands back a detached copy, so this snapshot is safe to hold and to
        // mutate independently.
        originals.put(player.getUniqueId(), new Original(player.getPlayerProfile(), player.displayName()));

        // Build the profile rather than rename theirs: setName/setId are deprecated for removal, and
        // a profile is meant to be made with the identity it's going to have.
        //
        // It must be createProfileExact, not createProfile. For someone who's online, createProfile
        // hands back their *existing* profile and drops the name argument on the floor — the rename
        // would quietly do nothing and the skin would never land.
        PlayerProfile carmen = Bukkit.createProfileExact(player.getUniqueId(), CarmenSkin.NAME);

        // Exact still isn't a blank slate: asked for a known player's uuid under a different name, it
        // copies that player's own properties across — their skin included. Clear it, or "Carmen"
        // walks around wearing their face.
        carmen.clearProperties();
        carmen.setProperty(new ProfileProperty("textures", CarmenSkin.VALUE, CarmenSkin.SIGNATURE));

        player.setPlayerProfile(carmen); // their own uuid went in, so their uuid comes out

        // The profile swap alone doesn't move the display name — that's cached separately from the
        // moment they joined. Setting it is what makes chat plugins print "Carmen" of their own
        // accord, without anyone having to fight them for the renderer.
        player.displayName(Component.text(CarmenSkin.NAME));
        return true;
    }

    /**
     * Finds a transformed player by the name they had before the voice took them.
     *
     * <p>Needed because the rename is real: while she's Carmen, {@code getPlayerExact("Nyrrine")}
     * finds nobody, which is precisely when someone would be typing it to undo this. Worse, two
     * people in form are both literally named Carmen, so asking for the name back is ambiguous while
     * the name they arrived with never is.
     *
     * @return the player, or null if nobody in form used to be called that.
     */
    Player findByOriginalName(String name) {
        for (Map.Entry<UUID, Original> entry : originals.entrySet()) {
            String was = entry.getValue().profile().getName();
            if (was != null && was.equalsIgnoreCase(name)) return Bukkit.getPlayer(entry.getKey());
        }
        return null;
    }

    /**
     * The names everyone in form arrived with — what you'd type to give one of them back.
     *
     * <p>Completing on live names would offer "Carmen" once per transformed player, which tells you
     * nothing about which one you're pointing at.
     */
    List<String> originalNames() {
        List<String> names = new ArrayList<>();
        for (Original original : originals.values()) {
            String was = original.profile().getName();
            if (was != null) names.add(was);
        }
        return names;
    }

    /** @return false if they weren't in form. */
    boolean revert(Player player) {
        Original original = originals.remove(player.getUniqueId());
        if (original == null) return false;
        player.setPlayerProfile(original.profile());
        player.displayName(original.displayName());
        return true;
    }

    /**
     * Put the profile back before anything else watching the quit can read it.
     *
     * <p>LOWEST on purpose. While in form {@code getName()} really does answer "Carmen" server-wide,
     * so any plugin that records a name as its player leaves — chat and permission plugins keep
     * name-to-uuid tables — would happily file Carmen against her account. Reverting first means
     * they see the real name and nothing durable gets poisoned by an RP session.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        revert(event.getPlayer());
    }

    /** Everyone back to themselves — the plugin is going down and the swap must not outlive it. */
    void restoreAll() {
        // Copy the keys first: revert() mutates the map underneath the loop.
        for (UUID id : List.copyOf(originals.keySet())) {
            Player player = Bukkit.getPlayer(id); // by uuid, which still resolves while renamed
            if (player != null) {
                revert(player);
            } else {
                originals.remove(id); // gone already; the swap died with their connection
            }
        }
    }
}
