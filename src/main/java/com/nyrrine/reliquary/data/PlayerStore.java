package com.nyrrine.reliquary.data;

/** The shared per-player store. One instance, owned by the plugin. */
public interface PlayerStore {

    /**
     * This player's record. Never null — an unknown player gets a fresh empty record.
     * Works for OFFLINE players too (admins must be able to pre-assign; see Reliquary's
     * "golden chains" rigging). Offline lookups hit disk, so don't call this per-tick.
     */
    PlayerRecord get(java.util.UUID id);

    /** Roles (weaver, …) — always loaded, enumerable. Deliberately NOT per-player data. */
    RoleIndex roles();
}
