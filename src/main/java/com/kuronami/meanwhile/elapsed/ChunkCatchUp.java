package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import com.kuronami.meanwhile.guard.CatchUpGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Spends a chunk's missing ticks on the machines that were in it.
 *
 * <p>{@link ChunkClock} works out how many ticks a chunk was gone for and stops there. This takes
 * that number and offers it to every block entity the chunk holds, through
 * {@link GenericCatchUp}, which knows nothing about any of them. A block entity the generic
 * catch-up will not touch is left exactly as it came back, which is what vanilla did with it
 * while it was away.
 *
 * <h3>No type is chosen</h3>
 * <p>Every block entity in the chunk is tried. The ones that cannot be caught up decline, and a
 * decline costs nothing — the counted reasons are kept and logged, because "it never jumped" and
 * "it never jumped because a list changes every tick" are different answers and only one of them
 * is a limitation of this mod.
 *
 * <h3>The two guards</h3>
 * <ul>
 *   <li><b>Non-positive elapsed is not dispatched.</b> A stored time ahead of the current one has
 *       been measured ({@code lastSeen=9451 at=1}); in a real server it is what a crash leaves
 *       behind when {@code level.dat} rolls back further than the chunk files did. Such a chunk
 *       is skipped entirely. {@link GenericCatchUp#catchUp} happens to be inert for a
 *       non-positive tick count — its loop is {@code while (remaining > 0)} — so what this guard
 *       buys is that no negative ever reaches it, not that a negative would corrupt anything
 *       today. {@link #minDispatchedTicks()} is what a test asserts on.</li>
 *   <li><b>Nothing is read from a chunk on its way out.</b> This class registers no chunk-unload
 *       listener and is reachable only from inside {@link ChunkClock}'s level sweep, which runs
 *       after the level has finished ticking, on a chunk that is running. Calls arriving from
 *       anywhere else are refused before a single block entity is fetched: a
 *       {@code getBlockEntity} inside the unload path pulls the chunk back to FULL and re-posts
 *       {@code Load}, which is the loop that kept a server from finishing its shutdown
 *       (GAP_LOG G58).</li>
 * </ul>
 *
 * <h3>Installed unconditionally</h3>
 * <p>This is the mod. {@link ChunkClock} stamps every running chunk whether or not anything reads
 * the stamp back, so a build in which this is not installed pays the whole cost of the clock and
 * settles nothing.
 *
 * <p>What that costs the verification suite is worth stating, because it is the reason a marker
 * file used to stand here and no longer does. A development world in {@code run/} carries
 * {@code last_seen_game_time} on its chunks from every previous run, so the catch-up can fire on
 * whatever a test arena inherits, at a count that depends on what the last run left behind. The
 * answer is a run directory that is not carried between runs — which is what a fresh clone is —
 * not a switch that turns the product off.
 */
public final class ChunkCatchUp {

    /**
     * Which parts of the behaviour are in force.
     *
     * <p>Everything but {@link #PRODUCT} exists to be measured failing. A negative control that
     * cannot go red says nothing about the control it is paired with.
     *
     * @param allowNonPositiveElapsed hand a non-positive elapsed count to the catch-up instead of
     *                                skipping the chunk
     * @param ignoreElapsed           dispatch zero ticks however long the chunk was gone, which is
     *                                the mod doing nothing while still reporting that it looked
     * @param threshold               fewer elapsed ticks than this and the chunk is left alone
     */
    public record Mode(boolean allowNonPositiveElapsed, boolean ignoreElapsed, long threshold,
                       Spend spend, @Nullable BlockPos restrictedTo, int fixedWindow) {

        public static final Mode PRODUCT =
                new Mode(false, false, ChunkClock.THRESHOLD_TICKS, Spend.CATCH_UP, null, 0);
        /**
         * Negative control for the first guard.
         *
         * <p>The threshold goes with it, and that is itself a measurement: in the product a
         * non-positive difference is stopped twice over, once by the sign check and again by
         * being below the threshold, so removing only the sign check demonstrates nothing.
         */
        public static final Mode ALLOW_NON_POSITIVE =
                new Mode(true, false, Long.MIN_VALUE, Spend.CATCH_UP, null, 0);
        /** Negative control for the catch-up itself: look, then do nothing. */
        public static final Mode IGNORE_ELAPSED =
                new Mode(false, true, ChunkClock.THRESHOLD_TICKS, Spend.CATCH_UP, null, 0);
        /** Control: the real path's order, with the jumping taken out. */
        public static final Mode TICK_SEQUENTIAL =
                new Mode(false, false, ChunkClock.THRESHOLD_TICKS, Spend.TICK_SEQUENTIAL, null, 0);
        /**
         * Control: look at the chunk and spend nothing, ever.
         *
         * <p>For asking what a round trip on its own does to a machine, with this mod's spending
         * taken out of the picture rather than merely set to zero ticks.
         */
        public static final Mode NO_CATCH_UP =
                new Mode(false, false, Long.MAX_VALUE, Spend.CATCH_UP, null, 0);
        /** Control: the game's order, with the jumping taken out. */
        public static final Mode TICK_INTERLEAVED =
                new Mode(false, false, ChunkClock.THRESHOLD_TICKS, Spend.TICK_INTERLEAVED, null, 0);

        /**
         * The same behaviour offered to one position instead of everything in the chunk.
         *
         * <p>Not the product, which chooses no types and therefore no positions. It exists
         * because a chunk holding two machines of one kinetic network cannot be compared against
         * a re-ticked copy of itself: the network is a shared object, and an arm that ticks both
         * machines leaves it somewhere the next arm cannot be put back to. Restricting both arms
         * to one machine removes the coupling and leaves the question that was actually asked.
         */
        public Mode restrictedTo(BlockPos pos) {
            return new Mode(allowNonPositiveElapsed, ignoreElapsed, threshold, spend, pos,
                    fixedWindow);
        }

        /**
         * Spend this many ticks whatever the chunk's own difference works out to.
         *
         * <p>A control, never the mod. Comparing two round trips means comparing two windows,
         * and the number of ticks between dropping a ticket and the game actually letting the
         * chunk go is not the same twice (2 to 8 measured). Left alone, that jitter would put a
         * different window in each arm and make bit equality an unreasonable thing to ask for
         * something else entirely. The elapsed count is verified on its own terms elsewhere.
         */
        public Mode withFixedWindow(int ticks) {
            return new Mode(allowNonPositiveElapsed, ignoreElapsed, threshold, spend, restrictedTo,
                    ticks);
        }

        public String label() {
            if (restrictedTo != null) {
                return baseLabel() + "@" + restrictedTo.toShortString();
            }
            return baseLabel();
        }

        private String baseLabel() {
            if (allowNonPositiveElapsed) {
                return "allow-non-positive";
            }
            if (ignoreElapsed) {
                return "ignore-elapsed";
            }
            if (threshold == Long.MAX_VALUE) {
                return "no-catch-up";
            }
            return switch (spend) {
                case CATCH_UP -> "product";
                case TICK_SEQUENTIAL -> "tick-sequential";
                case TICK_INTERLEAVED -> "tick-interleaved";
            };
        }
    }

    /**
     * How the window is spent, so that a mismatch can be attributed.
     *
     * <p>{@link #CATCH_UP} is the mod. The other two run every one of the window's ticks for real
     * and differ only in the order, which separates three things that a single comparison would
     * confound: whether the arena and the restore are faithful at all, whether finishing one
     * machine before starting the next matters, and whether the jumping is what moved anything.
     */
    public enum Spend {
        /** One machine at a time, jumping where the generic catch-up says it may. */
        CATCH_UP,
        /** One machine at a time, every tick real. The real path's order without the jumping. */
        TICK_SEQUENTIAL,
        /** All machines a tick at a time, every tick real. The order the game itself uses. */
        TICK_INTERLEAVED
    }

    /**
     * What was tried at one position, and what came of it.
     *
     * <p>Only the counters are always filled in. {@code block}, {@code type} and {@code ticker}
     * read {@link #UNRECORDED} and the two tags are null unless an {@link Observer} was installed
     * — the product spends the window and reads none of this back, and producing it is most of
     * what a slice would otherwise cost.
     */
    public record Attempt(BlockPos pos, String block, String type, String ticker, int ticks,
                          boolean declined, @Nullable String declineReason, int realTicks,
                          int jumps, int jumpedTicks, String refusals, String writes,
                          @Nullable CompoundTag before, @Nullable CompoundTag after) {

        public boolean jumped() {
            return !declined && jumps > 0;
        }

        public String summary() {
            return pos.toShortString() + " " + block + " ticker=" + ticker
                    + " real=" + realTicks + " jumps=" + jumps + " jumpedTicks=" + jumpedTicks
                    + (declined ? " DECLINED(" + declineReason + ")" : "")
                    + " writes=" + writes + " refusals=" + refusals;
        }
    }

    /** One chunk's worth of catch-up. */
    public record Sweep(long chunkPos, long lastSeen, long at, long elapsed, int dispatched,
                        int attempted, int jumped, int declined, int realTicks, int jumpedTicks,
                        List<Attempt> attempts) {
    }

    /** Handed each sweep, in the tick it happens. Measurement only; nothing ships one. */
    public interface Observer {

        /**
         * Called with the positions about to be spent, before any of them is touched.
         *
         * <p>A comparison needs it because some machines hold live state their own NBT cannot
         * carry, so an arm rebuilt from a recorded tag is not the arm that was running. Putting
         * both arms through the same reconstruction here is the only way to remove that, and
         * doing it before the sweep rather than after is what keeps the two symmetric.
         */
        default void beforeSweep(ServerLevel level, LevelChunk chunk, int dispatched,
                                 List<BlockPos> positions) {
        }

        void afterSweep(ServerLevel level, LevelChunk chunk, Sweep sweep);
    }

    private static final Map<ResourceKey<Level>, Map<Long, Sweep>> SWEPT = new ConcurrentHashMap<>();

    private static volatile Mode mode = Mode.PRODUCT;
    @Nullable
    private static volatile Observer observer;
    private static volatile boolean installed;

    /**
     * The smallest tick count ever handed to {@link GenericCatchUp}. Starts above every possible
     * one so that "nothing was dispatched" cannot be mistaken for "nothing improper was".
     */
    private static volatile int minDispatchedTicks = Integer.MAX_VALUE;
    private static volatile int dispatches;
    private static volatile int refusedOutsideSweep;
    private static volatile int skippedNonPositive;
    private static volatile int skippedBelowThreshold;

    // ---- the worklist and what it is paying off ---------------------------------------------

    /** Chunks with something outstanding, oldest first. Appended to, never inserted into. */
    private static final Deque<Job> WORKLIST = new ArrayDeque<>();
    /** One entry per absence being worked off. */
    private static final Map<Long, Pending> PENDING = new HashMap<>();

    /**
     * Ticks of debt one chunk may be given in one level tick.
     *
     * <p>Measured rather than picked. Against a millstone owing 120000 ticks, the longest single
     * drain was 4786us settling it in one payment, 2643us at 8000, and then flat at roughly 600
     * to 680us for 1000, 500 and 250 alike — below about a thousand the cost stops falling,
     * because what is left is the fixed cost of a drain rather than the work in it. (A reading of
     * 6216us at 100 came from the first phase measured in a run and is a cold one; the shape of
     * the rest is what the choice rests on.)
     *
     * <p>1000 is the largest value still on that floor, which buys the same spike as 250 while
     * settling in a quarter of the instalments: 120 rather than 480. For the 120000-tick absence
     * measured, that is a machine that takes about six seconds of real time to catch up, which
     * reads as a machine spinning up rather than as a stall.
     */
    public static final int SLICE_TICKS = 1000;
    /** Real ticker invocations after which a level tick stops paying anything more. */
    public static final int BUDGET_REAL_TICKS = 400;

    private static volatile int sliceTicks = SLICE_TICKS;
    /**
     * Test-only: whether a balance still outstanding when a chunk goes away is added to whatever
     * the next absence brings. Turning it off is how "the debt did not survive the round trip"
     * is measured, since with it off the outstanding part is simply dropped.
     */
    private static volatile boolean carryDebtAcrossReload = true;
    private static volatile int budgetRealTicks = BUDGET_REAL_TICKS;
    private static boolean draining;
    /**
     * How many times a drain was asked for while one was already running and was turned away.
     *
     * <p>Counts refusals, not re-entrant runs: the guard returns before doing anything, so a
     * non-zero figure here is the guard working rather than the thing it guards against.
     */
    private static volatile int reentryRefused;
    private static volatile boolean reentryReported;

    /** What an {@link Attempt}'s descriptive fields say when no {@link Observer} asked for them. */
    private static final String UNRECORDED = "<not recorded>";

    /** Slices abandoned because something outside the ticker call threw. Logged once, counted. */
    private static volatile int drainFailures;
    private static volatile boolean drainFailureReported;

    /** Cumulative, per chunk, so that paying the same absence twice can be asserted against. */
    private static final Map<Long, Long> owedTotal = new ConcurrentHashMap<>();
    private static final Map<Long, Long> paidTotal = new ConcurrentHashMap<>();
    /** Instalments the last settled absence took, per chunk. */
    private static final Map<Long, Integer> slicesUsed = new ConcurrentHashMap<>();

    private static volatile long drainNanos;
    private static volatile long worstDrainNanos;
    private static volatile int drains;

    private ChunkCatchUp() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ChunkClock.setReconciler(ChunkCatchUp::onReconciled);
        ChunkClock.setDrainer(ChunkCatchUp::drain);
        Meanwhile.LOGGER.info("[catchup] installed | mode={} threshold={} ticks",
                mode.label(), mode.threshold());
    }

    public static boolean isInstalled() {
        return installed;
    }

    // ---- the sweep ----------------------------------------------------------------------

    /**
     * A chunk has just been found to be behind. Spend the difference.
     *
     * <p>Called by {@link ChunkClock} from its level sweep and from nowhere else. The first thing
     * it does is check that, which is the second guard.
     */
    private static void onReconciled(ServerLevel level, LevelChunk chunk, long lastSeen, long at,
                                     long elapsed) {
        long key = chunk.getPos().toLong();

        if (!ChunkClock.inSweep()) {
            refusedOutsideSweep++;
            Meanwhile.LOGGER.warn("[catchup] REFUSED | chunk={} dim={} elapsed={} reason="
                            + "not-in-sweep (a chunk reached here from outside the level sweep,"
                            + " and reading block entities off it could be reviving one that is"
                            + " on its way out)",
                    new ChunkPos(key), level.dimension().location(), elapsed);
            return;
        }

        Mode current = mode;
        if (elapsed <= 0 && !current.allowNonPositiveElapsed()) {
            skippedNonPositive++;
            Meanwhile.LOGGER.info("[catchup] skip | chunk={} dim={} lastSeen={} at={} elapsed={}"
                            + " reason=non-positive",
                    new ChunkPos(key), level.dimension().location(), lastSeen, at, elapsed);
            return;
        }
        if (elapsed > 0 && elapsed < current.threshold()) {
            skippedBelowThreshold++;
            return;
        }

        long owed;
        if (current.ignoreElapsed()) {
            owed = 0L;
        } else if (current.fixedWindow() > 0) {
            owed = current.fixedWindow();
        } else {
            owed = elapsed;
        }

        // Added to whatever is already outstanding rather than replacing it: a chunk can go away
        // again before it has been paid off, and both absences are real.
        long carried = carryDebtAcrossReload ? debtOf(chunk) : 0L;
        long total = carried + owed;
        setDebt(chunk, total);
        owedTotal.merge(key, owed, Long::sum);

        Pending pending = PENDING.computeIfAbsent(key,
                ignored -> new Pending(key, lastSeen, at, level.dimension()));
        pending.owed += owed;
        if (!pending.queued) {
            pending.queued = true;
            WORKLIST.addLast(new Job(level.dimension(), key));
        }
        Meanwhile.LOGGER.info("[catchup] owed | chunk={} dim={} lastSeen={} at={} elapsed={}"
                        + " added={} carried={} debt={} mode={} queue={}",
                new ChunkPos(key), level.dimension().location(), lastSeen, at, elapsed,
                owed, carried, total, current.label(), WORKLIST.size());
    }

    // ---- paying it off --------------------------------------------------------------------

    /**
     * Pays down what is owed, a slice at a time, and never from inside the walk over the chunks.
     *
     * <p>Two separate problems, one mechanism.
     *
     * <p>The first is structural. Spending a window runs block entity tickers, and a ticker is
     * allowed to load a chunk — that is what a chunk loader is. A load posts
     * {@code ChunkEvent.Load}, which writes into the very map {@link ChunkClock} is walking at the
     * moment it notices an absence. Here the walk only writes down what is owed; paying happens
     * afterwards, over a queue, so a load provoked mid-payment appends to the tail instead of
     * appearing inside an iteration. The number of jobs to take is fixed before the first is
     * taken, so work created by a drain waits for the next one and cannot starve the tick.
     *
     * <p>The second is what a player sees. A chunk away for eight hours owes over half a million
     * ticks, and a hundred machines owing that between them is a stall at the moment the chunk
     * comes back. A decline is not a wrong answer, it is a slow one, and slowness lands exactly
     * where the player is standing. Slices turn that into a machine that visibly takes a few
     * seconds to catch up.
     *
     * <p>The re-entrancy guard covers the whole drain, including the single real tick
     * {@link GenericCatchUp} takes to see what a machine does — that tick is itself a chance for a
     * chunk to load and for something to try to start another drain.
     */
    static void drain(ServerLevel level) {
        if (draining) {
            reentryRefused++;
            if (!reentryReported) {
                reentryReported = true;
                StringBuilder where = new StringBuilder();
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (int i = 1; i < Math.min(stack.length, 14); i++) {
                    where.append(System.lineSeparator()).append("    at ").append(stack[i]);
                }
                Meanwhile.LOGGER.warn("[catchup] REFUSED | reason=drain asked for while one was"
                        + " already running; turned away before touching anything. Called from:{}",
                        where);
            }
            return;
        }
        if (WORKLIST.isEmpty()) {
            return;
        }
        draining = true;
        long startedAt = System.nanoTime();
        int spentRealTicks = 0;
        int jobsTaken = 0;
        int jobsThisTick = WORKLIST.size();
        try {
            while (jobsTaken < jobsThisTick && spentRealTicks < budgetRealTicks) {
                Job job = WORKLIST.pollFirst();
                if (job == null) {
                    break;
                }
                jobsTaken++;
                // The backstop. Everything a third party's code can throw is caught a level
                // down, next to the ticker call, which is where the block entity that threw can
                // still be named; this catches what is left — the scaffolding around it, and
                // anything a future call site forgets. One chunk failing must not take the rest
                // of the queue, and none of it may reach LevelTickEvent.Post.
                try {
                    spentRealTicks += pay(level, job);
                } catch (Throwable thrown) {
                    drainFailures++;
                    // The job was taken off the queue and the slice it was in the middle of is
                    // not coming back. Drop the half-finished bookkeeping so the next
                    // reconciliation builds a fresh one; what the chunk is owed rides on the
                    // chunk itself and is untouched by this.
                    PENDING.remove(job.chunkPos());
                    if (!drainFailureReported) {
                        drainFailureReported = true;
                        Meanwhile.LOGGER.warn("[catchup] drain failed | chunk={} dim={} | {} |"
                                        + " the chunk keeps what it is owed and is offered again"
                                        + " the next time it is reconciled; further failures are"
                                        + " counted and not logged",
                                new ChunkPos(job.chunkPos()), job.dimension().location(), thrown);
                    }
                }
            }
        } finally {
            draining = false;
        }
        long nanos = System.nanoTime() - startedAt;
        drainNanos += nanos;
        drains++;
        if (nanos > worstDrainNanos) {
            worstDrainNanos = nanos;
        }
        if (jobsTaken > 0) {
            Meanwhile.LOGGER.info("[catchup] drain | dim={} jobs={} realTicks={} budget={}"
                            + " slice={} took={}us queue={}",
                    level.dimension().location(), jobsTaken, spentRealTicks, budgetRealTicks,
                    sliceTicks, nanos / 1000L, WORKLIST.size());
        }
    }

    /** One slice for one chunk. Returns the real ticker invocations it cost. */
    private static int pay(ServerLevel level, Job job) {
        if (!job.dimension().equals(level.dimension())) {
            WORKLIST.addLast(job);
            return 0;
        }
        Pending pending = PENDING.get(job.chunkPos());
        if (pending == null) {
            return 0;
        }
        ChunkPos pos = new ChunkPos(job.chunkPos());
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null || !level.shouldTickBlocksAt(job.chunkPos())) {
            // Gone again, or not running. What it is owed rides on the chunk and survives with
            // it; the job is remade the next time the chunk is reconciled.
            pending.queued = false;
            return 0;
        }

        Observer watcher = observer;
        if (!pending.announced) {
            pending.announced = true;
            if (watcher != null) {
                watcher.beforeSweep(level, chunk, (int) Math.min(pending.owed, Integer.MAX_VALUE),
                        positionsOf(chunk));
            }
        }

        long remaining = debtOf(chunk);
        // A non-positive figure only gets here with the sign guard deliberately off, and then the
        // whole point is what reaches GenericCatchUp, so it is passed through unchanged.
        int slice = remaining <= 0
                ? (int) Math.max(Integer.MIN_VALUE, remaining)
                : (int) Math.min(remaining, sliceTicks);
        minDispatchedTicks = Math.min(minDispatchedTicks, slice);
        dispatches++;

        Mode current = mode;
        Sweep sliceResult = current.spend() == Spend.TICK_INTERLEAVED
                ? spendInterleaved(level, chunk, pending.lastSeen, pending.at, pending.owed, slice)
                : spend(level, chunk, pending.lastSeen, pending.at, pending.owed, slice,
                        current.spend());
        pending.absorb(sliceResult);
        pending.slices++;
        paidTotal.merge(job.chunkPos(), (long) Math.max(slice, 0), Long::sum);

        long left = remaining <= 0 ? 0L : remaining - slice;
        setDebt(chunk, left);
        if (left > 0) {
            WORKLIST.addLast(job);
            return sliceResult.realTicks();
        }

        pending.queued = false;
        PENDING.remove(job.chunkPos());
        slicesUsed.put(job.chunkPos(), pending.slices);
        Sweep whole = pending.toSweep();
        SWEPT.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .put(job.chunkPos(), whole);
        Meanwhile.LOGGER.info("[catchup] sweep | chunk={} dim={} lastSeen={} at={} elapsed={}"
                        + " dispatched={} mode={} attempted={} jumped={} declined={}"
                        + " realTicks={} jumpedTicks={} slices={} paidOver={} ticks",
                pos, level.dimension().location(), whole.lastSeen(), whole.at(), whole.elapsed(),
                whole.dispatched(), current.label(), whole.attempted(), whole.jumped(),
                whole.declined(), whole.realTicks(), whole.jumpedTicks(), pending.slices,
                level.getGameTime() - pending.at);
        for (Attempt attempt : whole.attempts()) {
            Meanwhile.LOGGER.info("[catchup] be | chunk={} {}", pos, attempt.summary());
        }
        if (watcher != null) {
            watcher.afterSweep(level, chunk, whole);
        }
        return sliceResult.realTicks();
    }

    private static long debtOf(LevelChunk chunk) {
        Long stored = chunk.getExistingDataOrNull(ChunkClockAttachments.CATCH_UP_DEBT);
        return stored == null ? 0L : stored;
    }

    private static void setDebt(LevelChunk chunk, long debt) {
        if (debt == 0L) {
            chunk.removeData(ChunkClockAttachments.CATCH_UP_DEBT);
        } else {
            chunk.setData(ChunkClockAttachments.CATCH_UP_DEBT, debt);
        }
        chunk.setUnsaved(true);
    }

    /** One chunk waiting to be paid. */
    private record Job(ResourceKey<Level> dimension, long chunkPos) {
    }

    /** What one absence is owed, and what has been done about it so far. */
    private static final class Pending {
        private final long chunkPos;
        private final long lastSeen;
        private final long at;
        private final ResourceKey<Level> dimension;
        private final Map<BlockPos, Attempt> attempts = new LinkedHashMap<>();
        private long owed;
        private int slices;
        private boolean queued;
        private boolean announced;

        private Pending(long chunkPos, long lastSeen, long at, ResourceKey<Level> dimension) {
            this.chunkPos = chunkPos;
            this.lastSeen = lastSeen;
            this.at = at;
            this.dimension = dimension;
        }

        private void absorb(Sweep slice) {
            for (Attempt attempt : slice.attempts()) {
                attempts.merge(attempt.pos(), attempt, Pending::combine);
            }
        }

        private Sweep toSweep() {
            List<Attempt> all = new ArrayList<>(attempts.values());
            int jumped = 0;
            int declined = 0;
            int realTicks = 0;
            int jumpedTicks = 0;
            for (Attempt attempt : all) {
                if (attempt.declined()) {
                    declined++;
                } else if (attempt.jumps() > 0) {
                    jumped++;
                }
                realTicks += attempt.realTicks();
                jumpedTicks += attempt.jumpedTicks();
            }
            return new Sweep(chunkPos, lastSeen, at, owed,
                    (int) Math.min(owed, Integer.MAX_VALUE), all.size(), jumped, declined,
                    realTicks, jumpedTicks, all);
        }

        /**
         * Two slices of one machine, as one. The tag it started from is the first slice's and the
         * tag it ended at is the last slice's; a decline only stands if the last slice declined,
         * since an earlier slice that worked is not undone by a later one that could not.
         */
        private static Attempt combine(Attempt first, Attempt next) {
            return new Attempt(first.pos(), first.block(), first.type(), next.ticker(),
                    first.ticks() + next.ticks(), next.declined(), next.declineReason(),
                    first.realTicks() + next.realTicks(), first.jumps() + next.jumps(),
                    first.jumpedTicks() + next.jumpedTicks(),
                    first.refusals() + "+" + next.refusals(),
                    first.writes() + "+" + next.writes(), first.before(), next.after());
        }
    }

    /**
     * Offers the window to every block entity the chunk holds.
     *
     * <p>The positions come out of the chunk's own map and are copied first, because a catch-up
     * is allowed to change the block it is standing on and that would otherwise be a modification
     * of the map being walked. They are sorted so that two runs spend the window in the same
     * order; the map's iteration order is not promised to be stable and a comparison made against
     * a different order is a comparison of a different machine.
     */
    private static Sweep spend(ServerLevel level, LevelChunk chunk, long lastSeen, long at,
                               long elapsed, int dispatched, Spend how) {
        HolderLookup.Provider registries = level.registryAccess();
        List<BlockPos> positions = positionsOf(chunk);

        List<Attempt> attempts = new ArrayList<>();
        int jumped = 0;
        int declined = 0;
        int realTicks = 0;
        int jumpedTicks = 0;

        // Everything below that is not the catch-up itself exists to be compared against, and a
        // comparison is something a measurement installs. Serialising every block entity twice
        // per slice, naming its block, its class and the ticker the game would have handed out
        // are all reads that the product never looks at; on a chunk of a hundred machines they
        // are the larger half of what a slice costs. What is not conditional is anything a
        // counter is taken from: the real ticks spent are what the drain budgets on.
        boolean recording = observer != null;

        for (BlockPos pos : positions) {
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity == null) {
                // Removed by an earlier catch-up in this same sweep.
                continue;
            }
            CompoundTag before = recording ? blockEntity.saveWithoutMetadata(registries) : null;
            String block = recording
                    ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()
                    : UNRECORDED;
            String type = recording ? blockEntity.getClass().getName() : UNRECORDED;
            String ticker = recording ? tickerName(level, pos) : UNRECORDED;

            Attempt attempt;
            try {
                if (CatchUpGuard.isIsolated(level.dimension(), pos)) {
                    // Already threw its way out of catch-up. Still walked and still reported, so
                    // that a chunk of isolated machines does not read as a chunk of nothing.
                    attempt = new Attempt(pos, block, type, ticker, dispatched, true,
                            "isolated by the guard", 0, 0, 0, "{}", "{}", before, before);
                } else if (how == Spend.CATCH_UP) {
                    GenericCatchUp.Result result = GenericCatchUp.catchUp(level, pos, dispatched,
                            GenericCatchUp.Mode.SAFE);
                    attempt = new Attempt(pos, block, type, ticker, dispatched, result.declined(),
                            result.declineReason(), result.realTicks(), result.jumps(),
                            result.jumpedTicks(), String.valueOf(result.refusals()),
                            String.valueOf(result.writes()), before,
                            recording ? tagAt(level, pos, registries) : null);
                } else {
                    int ran = GenericCatchUp.tick(level, pos, dispatched);
                    attempt = new Attempt(pos, block, type, ticker, dispatched, ran == 0,
                            ran == 0 ? "nothing tickable at " + pos : null, ran, 0, 0, "{}",
                            "{control}", before,
                            recording ? tagAt(level, pos, registries) : null);
                }
            } catch (Throwable thrown) {
                // The only place a third party's code runs on this mod's route. Vanilla's own
                // ticking loop catches here; the route the catch-up added does not, and it sits
                // inside LevelTickEvent.Post, so without this the server goes down. The block is
                // named here rather than above: what the guard needs it for happens once per
                // exception, not once per block entity per slice.
                boolean isolated = CatchUpGuard.record(level.dimension(), pos,
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                                .toString(),
                        thrown);
                attempt = new Attempt(pos, block, type, ticker, dispatched, true,
                        (isolated ? "threw and was isolated: " : "threw: ")
                                + thrown.getClass().getName(),
                        0, 0, 0, "{}", "{}", before, before);
            }
            attempts.add(attempt);

            if (attempt.declined()) {
                declined++;
            } else if (attempt.jumps() > 0) {
                jumped++;
            }
            realTicks += attempt.realTicks();
            jumpedTicks += attempt.jumpedTicks();
        }

        return new Sweep(chunk.getPos().toLong(), lastSeen, at, elapsed, dispatched,
                attempts.size(), jumped, declined, realTicks, jumpedTicks, attempts);
    }

    /**
     * The same window, spent the way the game itself would have spent it: every machine one tick,
     * then every machine the next.
     *
     * <p>A control and never the mod. It exists so that a mismatch against a real-ticking arm can
     * be attributed — to the jumping, to finishing one machine before starting the next, or to
     * the comparison's own scaffolding — instead of being reported as one undivided disagreement.
     */
    private static Sweep spendInterleaved(ServerLevel level, LevelChunk chunk, long lastSeen,
                                          long at, long elapsed, int dispatched) {
        HolderLookup.Provider registries = level.registryAccess();
        List<BlockPos> positions = positionsOf(chunk);

        List<String> blocks = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<String> tickers = new ArrayList<>();
        List<CompoundTag> befores = new ArrayList<>();
        List<BlockPos> present = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity == null) {
                continue;
            }
            present.add(pos);
            befores.add(blockEntity.saveWithoutMetadata(registries));
            blocks.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                    .toString());
            types.add(blockEntity.getClass().getName());
            tickers.add(tickerName(level, pos));
        }

        int[] ran = new int[present.size()];
        boolean[] isolated = new boolean[present.size()];
        for (int tick = 0; tick < dispatched; tick++) {
            for (int i = 0; i < present.size(); i++) {
                BlockPos pos = present.get(i);
                if (isolated[i] || CatchUpGuard.isIsolated(level.dimension(), pos)) {
                    isolated[i] = true;
                    continue;
                }
                // Guarded on the same terms as the real path. This arm is a control and never
                // the mod, but it reaches the same third-party tickers by the same route, so
                // leaving it bare would put the hole back for every run that measures.
                try {
                    if (GenericCatchUp.tickOnce(level, pos)) {
                        ran[i]++;
                    }
                } catch (Throwable thrown) {
                    isolated[i] = CatchUpGuard.record(level.dimension(), pos, blocks.get(i),
                            thrown);
                }
            }
        }

        List<Attempt> attempts = new ArrayList<>();
        int declined = 0;
        int realTicks = 0;
        for (int i = 0; i < present.size(); i++) {
            BlockPos pos = present.get(i);
            BlockEntity now = level.getBlockEntity(pos);
            attempts.add(new Attempt(pos, blocks.get(i), types.get(i), tickers.get(i), dispatched,
                    ran[i] == 0, ran[i] == 0 ? "nothing tickable at " + pos : null, ran[i], 0, 0,
                    "{}", "{control}", befores.get(i),
                    now == null ? null : now.saveWithoutMetadata(registries)));
            if (ran[i] == 0) {
                declined++;
            }
            realTicks += ran[i];
        }
        return new Sweep(chunk.getPos().toLong(), lastSeen, at, elapsed, dispatched,
                attempts.size(), 0, declined, realTicks, 0, attempts);
    }

    /**
     * The positions the chunk holds block entities at, copied and ordered.
     *
     * <p>Copied because a catch-up is allowed to change the block it is standing on, which would
     * otherwise modify the map being walked. Ordered because the map's iteration order is not
     * promised to be stable, and a window spent in a different order is a different measurement.
     */
    /** The tag at a position, or null if the catch-up left nothing there. */
    @Nullable
    private static CompoundTag tagAt(ServerLevel level, BlockPos pos,
                                     HolderLookup.Provider registries) {
        BlockEntity now = level.getBlockEntity(pos);
        return now == null ? null : now.saveWithoutMetadata(registries);
    }

    private static List<BlockPos> positionsOf(LevelChunk chunk) {
        List<BlockPos> positions = new ArrayList<>(chunk.getBlockEntities().keySet());
        positions.sort(Comparator.comparingLong(BlockPos::asLong));
        BlockPos only = mode.restrictedTo();
        if (only != null) {
            positions.removeIf(pos -> !pos.equals(only));
        }
        return positions;
    }

    /**
     * The ticker the game would run for this position, named rather than used.
     *
     * <p>Worked out the same way {@link GenericCatchUp#tickOnce} reaches it, so that a window in
     * which the game's dispatch resolves differently shows up as a different name in the log
     * instead of as an unexplained mismatch further down.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tickerName(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !(state.getBlock() instanceof EntityBlock entityBlock)) {
            return "<no-entity-block>";
        }
        BlockEntityTicker ticker =
                entityBlock.getTicker(level, state, (BlockEntityType) blockEntity.getType());
        return ticker == null ? "<null>" : ticker.getClass().getName();
    }

    // ---- what a test may set and ask ------------------------------------------------------

    public static void setMode(Mode next) {
        mode = next;
        Meanwhile.LOGGER.info("[catchup] mode | {} threshold={}", next.label(), next.threshold());
    }

    public static Mode mode() {
        return mode;
    }

    public static void setObserver(@Nullable Observer next) {
        observer = next;
    }

    /**
     * The smallest tick count handed to the generic catch-up so far, or
     * {@link Integer#MAX_VALUE} when none has been.
     */
    public static int minDispatchedTicks() {
        return minDispatchedTicks;
    }

    public static int dispatches() {
        return dispatches;
    }

    public static int refusedOutsideSweep() {
        return refusedOutsideSweep;
    }

    public static int skippedNonPositive() {
        return skippedNonPositive;
    }

    public static int skippedBelowThreshold() {
        return skippedBelowThreshold;
    }

    public static void resetCounters() {
        minDispatchedTicks = Integer.MAX_VALUE;
        dispatches = 0;
        refusedOutsideSweep = 0;
        skippedNonPositive = 0;
        skippedBelowThreshold = 0;
        reentryRefused = 0;
        drainNanos = 0L;
        worstDrainNanos = 0L;
        drains = 0;
        owedTotal.clear();
        paidTotal.clear();
        slicesUsed.clear();
    }

    /**
     * Test-only: drop every outstanding job and every debt this run has written down.
     *
     * <p>A test that hands out a large artificial debt writes it onto every chunk the sweep
     * touches while its mode is in force, not only the one it is watching. Left behind, those
     * keep being paid off during whatever runs next.
     */
    public static void forget(ServerLevel level) {
        for (Long key : new ArrayList<>(owedTotal.keySet())) {
            ChunkPos pos = new ChunkPos(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                setDebt(chunk, 0L);
            }
        }
        WORKLIST.clear();
        PENDING.clear();
        Meanwhile.LOGGER.info("[catchup] forget | queue and debts cleared");
    }

    /** How many instalments the last settled absence on this chunk took. */
    public static int slicesFor(ChunkPos pos) {
        return slicesUsed.getOrDefault(pos.toLong(), 0);
    }

    /**
     * How many times a drain was asked for while one was already running and was turned away.
     * Nothing ran on those calls.
     */
    public static int reentryRefused() {
        return reentryRefused;
    }

    /** Slices abandoned by the drain's backstop. Zero is the only figure a run should show. */
    public static int drainFailures() {
        return drainFailures;
    }

    /** The longest single drain, in microseconds. This is the number a stall would show up in. */
    public static long worstDrainMicros() {
        return worstDrainNanos / 1000L;
    }

    public static long totalDrainMicros() {
        return drainNanos / 1000L;
    }

    public static int drains() {
        return drains;
    }

    public static int queueLength() {
        return WORKLIST.size();
    }

    /** What this chunk has been told it is owed, over the whole run. */
    public static long owedFor(ChunkPos pos) {
        return owedTotal.getOrDefault(pos.toLong(), 0L);
    }

    /** What has actually been handed over for it, over the whole run. */
    public static long paidFor(ChunkPos pos) {
        return paidTotal.getOrDefault(pos.toLong(), 0L);
    }

    /** Ticks still outstanding on a loaded chunk, or 0. */
    public static long debtFor(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        return chunk == null ? 0L : debtOf(chunk);
    }

    /**
     * Test-only: how much of a debt one chunk may be given per level tick, and how much real
     * ticking a level tick will do before it stops. Both go back to the product values with
     * {@link #restoreBudget()}.
     */
    public static void setBudget(int slice, int realTicks) {
        sliceTicks = slice;
        budgetRealTicks = realTicks;
        Meanwhile.LOGGER.info("[catchup] budget | slice={} realTicks={}", slice, realTicks);
    }

    /** Test-only. See {@link #carryDebtAcrossReload}. */
    public static void setCarryDebtAcrossReload(boolean carry) {
        carryDebtAcrossReload = carry;
        Meanwhile.LOGGER.info("[catchup] carry debt across reload | {}", carry);
    }

    public static void restoreBudget() {
        setBudget(SLICE_TICKS, BUDGET_REAL_TICKS);
    }

    public static int sliceTicks() {
        return sliceTicks;
    }

    @Nullable
    public static Sweep lastSweep(ServerLevel level, ChunkPos pos) {
        Map<Long, Sweep> perLevel = SWEPT.get(level.dimension());
        return perLevel == null ? null : perLevel.get(pos.toLong());
    }

    /**
     * Offers a chunk to the catch-up from outside the level sweep, which is the thing the second
     * guard exists to stop. Reachable only from a test; the refusal it produces is the
     * measurement.
     */
    public static void offerFromOutsideTheSweep(ServerLevel level, LevelChunk chunk, long elapsed) {
        onReconciled(level, chunk, level.getGameTime() - elapsed, level.getGameTime(), elapsed);
    }
}
