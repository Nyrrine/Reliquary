package com.nyrrine.reliquary.data;

/**
 * How the store reaches the server's threads. Exists so the store's timing and threading can be
 * exercised headlessly — {@link BukkitStoreScheduler} is the real one, tests substitute a direct
 * implementation that runs everything inline.
 *
 * <p>The split matters: {@link #debounce} lands on the <em>main</em> thread (so snapshots are taken
 * where mutation happens), while {@link #write} lands <em>off</em> it (so disk never touches a tick).
 */
interface StoreScheduler {

    /**
     * Run {@code task} on the main thread in roughly {@code delayTicks}. At most one debounced task
     * is pending at a time; the store only calls this when none is outstanding.
     */
    void debounce(Runnable task, long delayTicks);

    /** Drop any pending debounced task. */
    void cancel();

    /**
     * Run {@code task} off the main thread. Writes are executed in submission order, so a later
     * snapshot can never be overtaken by an earlier one.
     */
    void write(Runnable task);

    /** Run every submitted write to completion, then stop accepting more. Blocks. */
    void shutdown();
}
