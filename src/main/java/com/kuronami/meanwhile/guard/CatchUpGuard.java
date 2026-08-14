package com.kuronami.meanwhile.guard;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Counts how often the same block entity throws while it is being caught up, and says when to
 * stop offering it one.
 *
 * <p>The catch-up runs other people's tickers on a route the game itself never takes: outside
 * {@code LevelChunk.BoundTickingBlockEntity}, which is where vanilla's own try/catch lives, and
 * inside {@code LevelTickEvent.Post}, from which an exception unwinds into the server tick. A
 * ticker written on the assumption that it only ever runs from the chunk's ticking list — one
 * that reaches for a neighbour the catch-up has not restored, or for a capability that is only
 * bound during normal ticking — takes the server down on the route this mod added. The count is
 * what turns that into one machine that stops being caught up.
 *
 * <p>The policy is taken from {@code mod-052-free-server-saver}'s {@code ExceptionGuard}, which
 * has the same shape and the same numbers: {@link #THRESHOLD} exceptions inside
 * {@link #WINDOW_MS} milliseconds. Nothing else is taken from it. That mod's Mixin wraps
 * vanilla's ticking loop, and this one calls tickers outside that loop, so the Mixin would never
 * see anything this has to catch.
 *
 * <h3>What isolation means here</h3>
 * <p>The block stays. Its inventory stays. Its block entity stays, and the game goes on ticking
 * it exactly as it did before — the only thing that changes is that this mod stops offering it a
 * window to catch up on. That is the smallest isolation that removes the added risk, and it is
 * the direction {@code Neruina} chose for the same problem: keep the machine, drop the extra
 * thing being done to it.
 *
 * <h3>What we do not do</h3>
 * <ul>
 *   <li><b>Crash on purpose when exceptions are frequent.</b> Neruina has a valve that does this
 *       and an in-world interface to recover from it; without the second, the first is a server
 *       that stops for a machine the operator cannot find.</li>
 *   <li><b>Notify anyone in chat.</b> One log line per exception, with the top stack frame,
 *       which is the frame that differs between two exceptions of the same class.</li>
 *   <li><b>Persist the counts.</b> They reset with the server. A genuinely broken machine trips
 *       the count again within a few chunk loads; one that threw once at a boundary gets another
 *       chance, which is the side to err on when the cost of being wrong is a machine that
 *       silently stops catching up.</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * <p>Everything here is touched from the server thread only: the catch-up runs from
 * {@code LevelTickEvent.Post} and from nowhere else. Plain maps, no synchronization.
 */
public final class CatchUpGuard {

    /** Exceptions from one source inside {@link #WINDOW_MS} before it is isolated. */
    public static final int THRESHOLD = 3;

    /** The window those exceptions have to fall inside, in wall-clock milliseconds. */
    public static final long WINDOW_MS = 10_000L;

    /** Sweep the count map at most this often; cheap, but there is no reason to do it more. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    /**
     * One block entity, in one dimension.
     *
     * <p>The dimension is part of the key rather than left out: the overworld and the nether both
     * have a block at (0, 64, 0), and a guard keyed on position alone would count their exceptions
     * together and isolate a machine that never threw.
     */
    private record Source(ResourceKey<Level> dimension, BlockPos pos) {

        @Override
        public String toString() {
            return dimension.location() + " " + pos.toShortString();
        }
    }

    /** Recent exception timestamps per source, pruned to {@link #WINDOW_MS}. */
    private static final Map<Source, Deque<Long>> HITS = new HashMap<>();

    /** Sources that have reached the threshold and are no longer offered a catch-up. */
    private static final Set<Source> ISOLATED = new HashSet<>();

    private static int threshold = THRESHOLD;
    private static long lastSweepMs;
    private static int isolations;
    private static int exceptions;

    private CatchUpGuard() {
    }

    /** Whether this position has already been dropped from catch-up. */
    public static boolean isIsolated(ResourceKey<Level> dimension, BlockPos pos) {
        return !ISOLATED.isEmpty() && ISOLATED.contains(new Source(dimension, pos.immutable()));
    }

    /**
     * Record that catching up this position threw, and say whether to stop offering it one.
     *
     * @param dimension  the level the position is in; part of the key, not decoration
     * @param pos        the block entity that threw
     * @param descriptor short human-readable subject, logged as-is
     * @param thrown     what came out
     * @return {@code true} when this exception took the source to the threshold, so the caller
     *         should stop catching it up
     */
    public static boolean record(ResourceKey<Level> dimension, BlockPos pos, String descriptor,
                                 Throwable thrown) {
        long now = System.currentTimeMillis();
        maybeSweep(now);
        exceptions++;

        Source source = new Source(dimension, pos.immutable());
        Deque<Long> hits = HITS.computeIfAbsent(source, ignored -> new ArrayDeque<>(THRESHOLD));
        prune(hits, now);
        hits.addLast(now);

        boolean isolate = hits.size() >= threshold;
        // One line per exception, always, so that a machine on its way to being isolated can be
        // correlated with whatever else was happening. The top frame rather than the whole trace:
        // the class alone does not separate two different bugs that both throw NPE, and the full
        // trace is unreadable at one line per chunk load.
        Meanwhile.LOGGER.warn("[guard] catch-up threw | {} at {} | {} | {}/{}{}",
                descriptor, source, summarize(thrown), hits.size(), threshold,
                isolate ? " | ISOLATED: this block entity keeps its block and its contents and"
                        + " goes on ticking normally, and is no longer caught up" : "");

        if (isolate) {
            HITS.remove(source);
            ISOLATED.add(source);
            isolations++;
        }
        return isolate;
    }

    /**
     * Drop what is remembered about one chunk, because the chunk has gone.
     *
     * <p>Both halves are keyed on a position, and nothing the game does removes either of them:
     * an isolation made in a chunk that then unloads stays in this set for the lifetime of the
     * process, so a long exploration grows it without bound. Called from the same forget path
     * {@link com.kuronami.meanwhile.elapsed.ChunkClock} uses to tell the catch-up a chunk is
     * gone, which is handed the position and nothing else.
     *
     * <p>Nothing is lost by dropping it. An isolation only ever decides whether a loaded chunk's
     * block entity is offered a window, so one belonging to a chunk that is not there decides
     * nothing until the chunk returns — and a machine that is genuinely broken reaches the
     * threshold again on the first few windows after it does, which is the second chance this
     * guard already says it errs towards.
     */
    public static void forgetChunk(ResourceKey<Level> dimension, long chunkPos) {
        if (HITS.isEmpty() && ISOLATED.isEmpty()) {
            return;
        }
        ChunkPos pos = new ChunkPos(chunkPos);
        HITS.keySet().removeIf(source -> inChunk(source, dimension, pos));
        ISOLATED.removeIf(source -> inChunk(source, dimension, pos));
    }

    /** The same, for every chunk of one level, because the level has gone. */
    public static void forgetLevel(ResourceKey<Level> dimension) {
        HITS.keySet().removeIf(source -> source.dimension().equals(dimension));
        ISOLATED.removeIf(source -> source.dimension().equals(dimension));
    }

    /**
     * Everything remembered about where machines are, dropped, because the server has stopped.
     *
     * <p>This is static state and an integrated server is started and stopped inside one
     * process: without this, a position isolated in one world decides whether a machine at the
     * same coordinates of the next world is caught up. The totals are not cleared — they are a
     * count of what this process has seen, and a measurement reading them is entitled to that.
     */
    public static void forgetAll() {
        HITS.clear();
        ISOLATED.clear();
    }

    private static boolean inChunk(Source source, ResourceKey<Level> dimension, ChunkPos pos) {
        return source.dimension().equals(dimension) && new ChunkPos(source.pos()).equals(pos);
    }

    private static void prune(Deque<Long> hits, long now) {
        long cutoff = now - WINDOW_MS;
        while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
            hits.pollFirst();
        }
    }

    /**
     * Drop count entries whose window has emptied.
     *
     * <p>Without it a machine that throws once and is never caught up again leaves its entry
     * behind forever: it never reaches the threshold, so {@link #record} never removes it. Run
     * from {@link #record} rather than from a timer, so there is no thread here.
     */
    private static void maybeSweep(long now) {
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = now;
        HITS.entrySet().removeIf(entry -> {
            prune(entry.getValue(), now);
            return entry.getValue().isEmpty();
        });
    }

    /**
     * One line for a throwable, preferring the top stack frame.
     *
     * <p>The frame is what differs between two exceptions of the same class thrown by different
     * bugs; the message is often per-instance noise.
     */
    private static String summarize(Throwable thrown) {
        if (thrown == null) {
            return "<null>";
        }
        StackTraceElement[] trace = thrown.getStackTrace();
        String top = trace != null && trace.length > 0
                ? trace[0].getClassName() + "." + trace[0].getMethodName() + ":"
                        + trace[0].getLineNumber()
                : "<no stack>";
        return thrown.getClass().getName() + " at " + top;
    }

    // ---- what a measurement may set and ask ------------------------------------------------

    public static int threshold() {
        return threshold;
    }

    /**
     * Move the threshold. Not a setting — nothing in the product calls this.
     *
     * <p>It exists so that the negative control can be the same machine throwing the same
     * exception with only the policy changed. A control that swaps the subject as well as the
     * policy does not say which of the two produced the difference.
     */
    public static void setThreshold(int next) {
        threshold = next;
        Meanwhile.LOGGER.info("[guard] threshold | {}", next);
    }

    public static int isolations() {
        return isolations;
    }

    public static int exceptions() {
        return exceptions;
    }

    /** Everything back to where it starts, so one measurement does not stand on another. */
    public static void reset() {
        HITS.clear();
        ISOLATED.clear();
        threshold = THRESHOLD;
        isolations = 0;
        exceptions = 0;
        lastSweepMs = 0L;
    }
}
