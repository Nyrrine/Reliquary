package com.nyrrine.reliquary.data;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The YAML-backed {@link PlayerStore}.
 *
 * <pre>
 * plugins/Reliquary/
 *   playerdata/&lt;uuid&gt;.yml   per-player, loaded on join and on demand for offline lookups
 *   roles.yml                always resident, enumerable
 * </pre>
 *
 * <h2>Threading</h2>
 * Records are read and mutated on the main thread only. {@link #markDirty} never writes; it marks
 * the record and arms a ~1s debounce. When the debounce fires — still on the main thread — each
 * dirty config is snapshotted to an immutable String, and only that String crosses to the writer
 * thread. A live {@code ConfigurationSection} is never handed off, so a mutation on the next tick
 * cannot race a half-finished save.
 *
 * <p>Reads do hit disk on the calling thread, which the contract sanctions ("offline lookups hit
 * disk, so don't call this per-tick"). Joins pay one small YAML read.
 */
public final class YamlPlayerStore implements PlayerStore {

    /** The debounce window. Worst case a crash costs this much, not the session. */
    static final long DEBOUNCE_TICKS = 20L; // ~1s

    /**
     * Cache ceiling. This targets a ~100-player server, so online players never come close; the
     * headroom absorbs offline admin lookups, which are exactly what would otherwise grow forever.
     */
    static final int MAX_CACHED = 256;

    private final File playerDir;
    private final File rolesFile;
    private final Logger log;
    private final StoreScheduler scheduler;

    /** Access-ordered: iteration yields least-recently-used first, which is what {@link #prune} wants. */
    private final Map<UUID, YamlPlayerRecord> cache = new LinkedHashMap<>(16, 0.75f, true);
    /** Online players. Pinned entries are never pruned; quit unpins them. */
    private final Set<UUID> pinned = new HashSet<>();
    private final Set<UUID> dirty = new HashSet<>();

    private final YamlRoleIndex roles;
    private boolean rolesDirty;
    private boolean flushArmed;
    private boolean closed;

    public YamlPlayerStore(Plugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger(), new BukkitStoreScheduler(plugin));
    }

    YamlPlayerStore(File dataFolder, Logger log, StoreScheduler scheduler) {
        this.playerDir = new File(dataFolder, "playerdata");
        this.rolesFile = new File(dataFolder, "roles.yml");
        this.log = log;
        this.scheduler = scheduler;
        this.roles = new YamlRoleIndex(this::markRolesDirty);
        loadRoles();
    }

    // ---- contract ----------------------------------------------------------

    @Override
    public PlayerRecord get(UUID id) {
        Objects.requireNonNull(id, "id");
        YamlPlayerRecord rec = cache.get(id);
        if (rec == null) {
            rec = read(id);
            cache.put(id, rec);
            prune();
        }
        return rec;
    }

    @Override
    public RoleIndex roles() {
        return roles;
    }

    // ---- lifecycle ---------------------------------------------------------

    /** Join: pin the record in cache and warm it from disk. */
    public void load(UUID id) {
        pinned.add(Objects.requireNonNull(id, "id"));
        get(id);
    }

    /** Quit: flush anything outstanding, then drop the record so the cache doesn't accumulate. */
    public void unload(UUID id) {
        Objects.requireNonNull(id, "id");
        pinned.remove(id);
        YamlPlayerRecord rec = cache.remove(id);
        if (rec != null && dirty.remove(id)) submit(id, rec);
    }

    /**
     * Disable: flush synchronously.
     *
     * <p>This is the one path that must not be lazy. Bukkit cancels scheduled tasks as the server
     * goes down, so anything still sitting in the debounce window would be silently discarded. We
     * cancel the timer ourselves, snapshot every dirty record here and now, and block until the
     * writer thread has drained.
     */
    public void close() {
        if (closed) return;
        scheduler.cancel();
        flushArmed = false;
        flushDirty();
        closed = true;
        scheduler.shutdown(); // blocks until every submitted write has landed
    }

    // ---- dirty tracking ----------------------------------------------------

    void markDirty(UUID id) {
        if (closed) {
            // Touched after shutdown drained. Rare, but dropping it would lose data — write through
            // on this thread instead. We're already off the tick loop by definition here.
            YamlPlayerRecord rec = cache.get(id);
            if (rec != null) writeSnapshot(fileFor(id), rec.snapshot());
            return;
        }
        dirty.add(id);
        armFlush();
    }

    private void markRolesDirty() {
        if (closed) {
            writeSnapshot(rolesFile, roles.snapshot());
            return;
        }
        rolesDirty = true;
        armFlush();
    }

    private void armFlush() {
        if (flushArmed) return;
        flushArmed = true;
        scheduler.debounce(this::flushDirty, DEBOUNCE_TICKS);
    }

    /** Main thread: snapshot every dirty record and hand the Strings to the writer. */
    private void flushDirty() {
        flushArmed = false;
        for (UUID id : dirty) {
            YamlPlayerRecord rec = cache.get(id);
            if (rec != null) submit(id, rec); // a miss means unload/prune already wrote it
        }
        dirty.clear();
        if (rolesDirty) {
            rolesDirty = false;
            String content = roles.snapshot();
            scheduler.write(() -> writeSnapshot(rolesFile, content));
        }
    }

    /** Snapshot now (main thread), write later (writer thread). */
    private void submit(UUID id, YamlPlayerRecord rec) {
        String content = rec.snapshot();
        File file = fileFor(id);
        scheduler.write(() -> writeSnapshot(file, content));
    }

    /** Evict least-recently-used unpinned records, flushing any that still owe a write. */
    private void prune() {
        if (cache.size() <= MAX_CACHED) return;
        Iterator<Map.Entry<UUID, YamlPlayerRecord>> it = cache.entrySet().iterator();
        while (it.hasNext() && cache.size() > MAX_CACHED) {
            Map.Entry<UUID, YamlPlayerRecord> e = it.next();
            if (pinned.contains(e.getKey())) continue;
            if (dirty.remove(e.getKey())) submit(e.getKey(), e.getValue());
            it.remove();
        }
    }

    // ---- disk --------------------------------------------------------------

    File fileFor(UUID id) {
        return new File(playerDir, id + ".yml");
    }

    /** How many records are resident. Test seam for {@link #prune}. */
    int cached() {
        return cache.size();
    }

    /** Never throws. A file we can't parse costs that player's data, not their join. */
    private YamlPlayerRecord read(UUID id) {
        File file = fileFor(id);
        YamlConfiguration cfg = new YamlConfiguration();
        if (file.isFile()) {
            try {
                cfg.load(file);
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                // RuntimeException too: the YAML parser can throw its own unchecked errors, and
                // nothing in here is worth breaking a join over.
                log.warning("Could not read playerdata/" + id + ".yml (" + e.getMessage()
                        + ") — continuing with an empty record.");
                quarantine(file);
                cfg = new YamlConfiguration();
            }
        }
        return new YamlPlayerRecord(this, id, cfg);
    }

    /**
     * Move a file we couldn't parse aside before the empty record we just substituted overwrites it.
     * Usually this is a hand-edit with a typo, and silently deleting the admin's work would be worse
     * than the corruption.
     */
    private void quarantine(File file) {
        File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.warning("Kept the unreadable file as " + backup.getName() + ".");
        } catch (IOException | RuntimeException e) {
            log.warning("Could not set aside " + file.getName() + ": " + e.getMessage());
        }
    }

    private void loadRoles() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (rolesFile.isFile()) {
            try {
                cfg.load(rolesFile);
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                log.warning("Could not read roles.yml (" + e.getMessage() + ") — starting with no grants.");
                quarantine(rolesFile);
                cfg = new YamlConfiguration();
            }
        }
        roles.loadFrom(cfg, log::warning);
    }

    /**
     * Writer thread. Write to a sibling temp file and move it into place, so a crash mid-write
     * leaves the previous good file rather than a truncated one.
     */
    private void writeSnapshot(File file, String content) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                log.warning("Could not create " + dir + " — " + file.getName() + " not saved.");
                return;
            }
            Path target = file.toPath();
            Path tmp = target.resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }
}
