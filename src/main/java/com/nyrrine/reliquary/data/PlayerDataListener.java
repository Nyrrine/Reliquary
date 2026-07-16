package com.nyrrine.reliquary.data;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Warms a player's record on join and flushes + evicts it on quit. */
public final class PlayerDataListener implements Listener {

    private final YamlPlayerStore store;

    public PlayerDataListener(YamlPlayerStore store) {
        this.store = store;
    }

    /** LOWEST: whatever else handles the join should find the record already loaded. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        store.load(event.getPlayer().getUniqueId());
    }

    /** MONITOR: let every other handler finish mutating before we snapshot and evict. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.unload(event.getPlayer().getUniqueId());
    }
}
