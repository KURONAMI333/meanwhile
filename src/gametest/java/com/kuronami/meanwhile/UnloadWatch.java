package com.kuronami.meanwhile;

/**
 * How long a chunk is given to post {@code ChunkEvent.Unload} after its forced tickets are
 * dropped, and the one place that says why the figure is what it is.
 *
 * <p>This is a precondition, not a measurement. Every test that waits here goes on to assert
 * something about what survived a round trip, and none of them assert how long the round trip
 * took. A chunk that never leaves still fails the test, hard, and says which wait it was.
 *
 * <p><b>Why the figure moved.</b> {@code ChunkEvent.Unload} is posted from a runnable chained
 * onto the chunk's save future ({@code ChunkMap.scheduleUnload}), so its arrival is bounded by an
 * asynchronous disk write, which is a quantity of real time. {@code GameTestServer} overrides
 * {@code waitUntilNextTick} with {@code runAllTasks} and never sleeps, so this runner turns about
 * 3,850 ticks a second: the old allowance of 200 ticks was about 52ms of real time, and one
 * half-second pause put an unload 1,851 ticks past the deadline (GAP_LOG G129, G130).
 *
 * <p><b>Why it is still in ticks.</b> Every mechanism that contains a GameTest is denominated in
 * ticks. {@code GameTestSequence.thenExecuteFor} burns its whole window whatever happens, and
 * {@code timeoutTicks} ends the test. A wall-clock deadline longer than the window that remains
 * would hand the failure to the framework's own timeout and lose the message saying what was
 * being waited for. So the allowance is a <em>proxy</em> for real time, and the proxy is kept
 * honest by printing both units on every wait: if {@code waitedMs} climbs across runs while
 * {@code waitedTicks} stays flat, the conversion has drifted and this figure has to be
 * remeasured rather than trusted.
 *
 * <p>Whatever this figure is, the windows that contain it have to be at least this much larger
 * than the work they hold, and they are burnt in full on every run. That is the price of the
 * allowance and it is paid on green runs too.
 */
public final class UnloadWatch {

    /**
     * Ticks a chunk is given to unload. Measured, not guessed: over the runs recorded in G131 the
     * longest arrival was well inside this, and the one recorded overshoot before it (G129) was
     * about 2,050 ticks. Roughly 2 seconds of real time on this runner.
     */
    public static final int ALLOWANCE_TICKS = 8000;

    private long startedTick = -1L;
    private long startedNanos;

    /** Arms the wait. Call this on the tick the forced tickets are dropped. */
    public void start(long gameTime) {
        this.startedTick = gameTime;
        this.startedNanos = System.nanoTime();
    }

    /** Whether the allowance has run out. False before {@link #start} is called. */
    public boolean overdue(long gameTime) {
        return startedTick >= 0L && gameTime - startedTick > ALLOWANCE_TICKS;
    }

    /**
     * Records how long the unload actually took, in both units, so the distribution is visible
     * rather than guessed at. Call this on the tick the unload is seen.
     */
    public void arrived(String where, long gameTime) {
        Meanwhile.LOGGER.info("[unloadwait] {} | waitedTicks={} waitedMs={} allowanceTicks={}",
                where, waitedTicks(gameTime), waitedMillis(), ALLOWANCE_TICKS);
    }

    /** What a test says when the unload never came. Carries both units for the same reason. */
    public String overdueMessage(String where) {
        return where + ": no ChunkEvent.Unload in " + ALLOWANCE_TICKS + " ticks ("
                + waitedMillis() + "ms) after the forced tickets were dropped";
    }

    public long waitedTicks(long gameTime) {
        return startedTick < 0L ? -1L : gameTime - startedTick;
    }

    public long waitedMillis() {
        return startedTick < 0L ? -1L : (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
