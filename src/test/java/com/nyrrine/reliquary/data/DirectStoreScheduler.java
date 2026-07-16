package com.nyrrine.reliquary.data;

/**
 * Headless stand-in for the Bukkit scheduler: the debounced task is captured rather than timed, so a
 * test can fire it exactly when it wants, and writes run inline on the calling thread.
 *
 * <p>This is what lets the debounce/snapshot/flush path be tested for real without a server — the
 * store's own logic runs unmodified; only the clock and the thread hop are stubbed.
 */
final class DirectStoreScheduler implements StoreScheduler {

    private Runnable pending;
    private long lastDelayTicks = -1;
    int writes;
    boolean shutdown;

    @Override
    public void debounce(Runnable task, long delayTicks) {
        pending = task;
        lastDelayTicks = delayTicks;
    }

    @Override
    public void cancel() {
        pending = null;
    }

    @Override
    public void write(Runnable task) {
        writes++;
        task.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    boolean armed() {
        return pending != null;
    }

    long lastDelayTicks() {
        return lastDelayTicks;
    }

    /** Fire the debounced task, as the scheduler would once the window elapsed. */
    void fire() {
        Runnable task = pending;
        pending = null;
        if (task != null) task.run();
    }
}
