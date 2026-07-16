package com.nyrrine.reliquary.data;

/** One player's persisted state, split into per-system namespaces. */
public interface PlayerRecord {

    java.util.UUID id();

    /**
     * Your system's private section. Pass a stable namespace — "prescript", "distortion".
     * You own everything inside it; nobody else reads or writes it.
     * Never null: an absent section is created empty.
     */
    org.bukkit.configuration.ConfigurationSection section(String namespace);

    /**
     * Mark this record changed. It flushes asynchronously within ~1s, plus on quit and
     * onDisable. Call it after every mutation — it's cheap and idempotent.
     * You never write files yourself.
     */
    void touch();
}
