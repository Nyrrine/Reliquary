package com.nyrrine.reliquary.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The live scheduler: Bukkit's main-thread timer for the debounce, a private single writer thread
 * for the disk work.
 *
 * <p>Writes deliberately do <em>not</em> go through {@code runTaskAsynchronously}. Bukkit's async
 * pool gives no ordering guarantee between two writes to the same file, and — the part that costs
 * data — its tasks are cancelled during shutdown, so a queued save would simply be dropped. A
 * private executor orders writes FIFO and, because we own it, can be drained to completion on
 * disable rather than discarded.
 */
final class BukkitStoreScheduler implements StoreScheduler {

    private final Plugin plugin;
    private final ExecutorService writer;
    private BukkitTask pending;

    BukkitStoreScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Reliquary-playerdata");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public boolean onMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void debounce(Runnable task, long delayTicks) {
        pending = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending = null;
            task.run();
        }, delayTicks);
    }

    @Override
    public void cancel() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    @Override
    public void write(Runnable task) {
        try {
            writer.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Past shutdown — run it here rather than lose it. The store only reaches this if it
            // was touched after close(), which already writes through on the calling thread.
            task.run();
        }
    }

    @Override
    public void shutdown() {
        writer.shutdown(); // already-queued writes still run
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Player data writes did not finish within 10s of shutdown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
