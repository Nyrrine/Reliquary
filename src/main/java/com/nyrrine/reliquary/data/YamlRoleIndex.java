package com.nyrrine.reliquary.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * roles.yml, held in memory in full. Grants are rare, admin-driven and few, so the whole index is
 * always resident — that's what lets it answer "who are all the Weavers?" without touching a single
 * player file.
 *
 * <p>Shape on disk is exactly {@code weaver: [uuid, ...]}, hand-editable offline.
 */
final class YamlRoleIndex implements RoleIndex {

    private final Map<String, Set<UUID>> grants = new HashMap<>();
    private final Runnable onChange;

    YamlRoleIndex(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Role names are matched case-insensitively so a hand-edited "Weaver" still resolves. */
    private static String key(String role) {
        return Objects.requireNonNull(role, "role").toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean has(UUID id, String role) {
        Objects.requireNonNull(id, "id");
        Set<UUID> held = grants.get(key(role));
        return held != null && held.contains(id);
    }

    @Override
    public void grant(UUID id, String role) {
        Objects.requireNonNull(id, "id");
        if (grants.computeIfAbsent(key(role), r -> new LinkedHashSet<>()).add(id)) onChange.run();
    }

    @Override
    public void revoke(UUID id, String role) {
        Objects.requireNonNull(id, "id");
        Set<UUID> held = grants.get(key(role));
        if (held != null && held.remove(id)) onChange.run();
    }

    @Override
    public Set<UUID> all(String role) {
        Set<UUID> held = grants.get(key(role));
        if (held == null || held.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(held));
    }

    /** Replace the in-memory index from a parsed roles.yml. Unparseable entries are skipped, not fatal. */
    void loadFrom(ConfigurationSection cfg, java.util.function.Consumer<String> warn) {
        grants.clear();
        for (String role : cfg.getKeys(false)) {
            Set<UUID> held = new LinkedHashSet<>();
            for (String raw : cfg.getStringList(role)) {
                try {
                    held.add(UUID.fromString(raw.trim()));
                } catch (IllegalArgumentException e) {
                    warn.accept("Ignoring malformed uuid '" + raw + "' under role '" + role + "' in roles.yml.");
                }
            }
            if (!held.isEmpty()) grants.put(key(role), held);
        }
    }

    /** Snapshot to YAML text on the calling (main) thread. Empty roles are dropped to keep the file tidy. */
    String snapshot() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Set<UUID>> e : grants.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> ids = new ArrayList<>(e.getValue().size());
            for (UUID id : e.getValue()) ids.add(id.toString());
            cfg.set(e.getKey(), ids);
        }
        return cfg.saveToString();
    }
}
