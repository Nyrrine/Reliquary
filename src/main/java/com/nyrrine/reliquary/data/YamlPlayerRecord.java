package com.nyrrine.reliquary.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's file, held open as a {@link YamlConfiguration}. Sections handed out by
 * {@link #section} are live views into it — callers mutate them on the main thread and call
 * {@link #touch()}; the store snapshots the whole config to a String before any of it reaches disk.
 */
final class YamlPlayerRecord implements PlayerRecord {

    private final YamlPlayerStore store;
    private final UUID id;
    private final YamlConfiguration config;

    YamlPlayerRecord(YamlPlayerStore store, UUID id, YamlConfiguration config) {
        this.store = store;
        this.id = id;
        this.config = config;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public ConfigurationSection section(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        ConfigurationSection existing = config.getConfigurationSection(namespace);
        // createSection would wipe an existing one — only ever create when genuinely absent.
        return existing != null ? existing : config.createSection(namespace);
    }

    @Override
    public void touch() {
        store.markDirty(id);
    }

    /**
     * The point of the whole design: turn the live, mutable config into an immutable String on the
     * thread that owns it, so the writer thread never sees a half-mutated object.
     */
    String snapshot() {
        return config.saveToString();
    }
}
