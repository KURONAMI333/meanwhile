package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.compat.CompatibilityCoordinator;
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
import java.util.function.LongSupplier;
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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
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

    /**
     * The last settled sweep per chunk, per dimension, for a test to read back.
     *
     * <p>Dropped when the chunk goes, which is what keeps it bounded by what is loaded rather than
     * by how much of the world has ever been visited. Nothing used to clear it, and a {@link Sweep}
     * carries an {@link Attempt} per block entity — with an {@link Observer} installed, two
     * serialised tags each (GAP_LOG G139). The eviction arrives through
     * {@link ChunkClock.Forgetter} rather than a chunk event of this class's own, because the
     * unload path is the one place a chunk may not be touched at all (G58) and routing it through
     * the clock keeps that rule in one place.
     */
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
    /**
     * One entry per absence being worked off, keyed by the chunk <em>and its dimension</em>.
     *
     * <p>The packed chunk position alone is not a key. Overworld (5,5) and Nether (5,5) pack to
     * the same long, and with one entry between them the second dimension to fall behind found
     * the first one's entry already queued, added its window to that one, and never queued a job
     * of its own — so its chunk kept a balance nothing was going to pay (GAP_LOG G139).
     * {@link Job} is that pair and is used as the key throughout.
     */
    private static final Map<Job, Pending> PENDING = new HashMap<>();

    /**
     * Ticks of debt one chunk may be given in one level tick.
     *
     * <p><b>Left where it was, because the rule that should set it does not close.</b> Two bounds
     * were measured and they point in opposite directions (GAP_LOG G151).
     *
     * <p>From above: a level tick spends {@link #BUDGET_NANOS} plus one machine's slice, because
     * the walk stops on a machine boundary and cannot stop inside one — the state that authorises
     * a jump is built up over the whole window and is not resumable without reopening the jump
     * learning (GAP_LOG G137). One real tick of catch-up costs about 18us here, measured twice on
     * a crowded chunk (7135us over 410 real ticks, 4875us over 261), dominated by the jump
     * learning serialising the block entity once a tick. For a machine that never jumps and so
     * really does run every tick of its slice, that puts a slice of 250 at 22.7% of a tick on a
     * host half as fast, and only a slice near 100 inside a tenth of a tick.
     *
     * <p>From below: the jumping stops paying once the slice is shorter than the regime the
     * machine is in. A furnace carried over a 900-tick window ran 10 real ticks at a slice of
     * 1000 and 13 at 250, but 309 at 100 — its burn regime is 300 ticks, and a window shorter
     * than that cannot be jumped across. At that point the catch-up is real ticking with extra
     * bookkeeping, which is the opposite of what it is for.
     *
     * <p>No value satisfies both. Lowering this is not the fix: the overshoot is the full
     * real-tick price only for machines that cannot jump, and for those the answer is to bound
     * the window inside {@link GenericCatchUp#catchUp} by time as well, which is a change to its
     * per-tick loop and to how a part-carried machine is accounted for. That is left for the
     * supervisor rather than guessed at here.
     *
     * <p>What the value below still rests on is the original measurement: against a millstone
     * owing 120000 ticks the longest single drain was 4786us settling it in one payment, 2643us
     * at 8000, and then flat at roughly 600 to 680us for 1000, 500 and 250 alike. 1000 is the
     * largest value on that floor, and it settles in 120 instalments rather than 480.
     */
    public static final int SLICE_TICKS = 1000;
    /**
     * Real ticker invocations after which a level tick stops paying anything more.
     *
     * <p>The backstop, not the primary budget. What one ticker invocation costs is not a
     * constant — a millstone and a machine from a large tech mod both count one here and do not
     * cost the same (the spread by type is in {@code _handoff/BENCH_create_tickcost.csv}) — so a
     * budget denominated in invocations cannot bound what a level tick spends. {@link
     * #BUDGET_NANOS} does that. This one stays for two reasons: it bounds the worst case in a
     * unit that does not depend on how fast the host is, and it is what the deterministic half of
     * the gates asserts on, since how much work fits in a given time is a property of the machine
     * the test runs on (GAP_LOG G130 ruling 6, G151).
     */
    public static final int BUDGET_REAL_TICKS = 400;
    /**
     * How long a level tick may spend paying off debt, in nanoseconds.
     *
     * <p>The primary budget, and a <em>fraction of the tick rather than a constant</em>. A server
     * tick is 50ms; this is 2ms, or 4% of one. That form is what makes the default safe on a host
     * this was not measured on: on a host half as fast, 2ms buys half as much work, so the catch-up
     * takes twice as many level ticks to settle and the spike a player feels stays at 4% of a
     * tick. A constant number of invocations would instead double the spike.
     *
     * <p>What it does not bound is the machine already in progress when the budget runs out. The
     * walk stops on a machine boundary, so the overshoot is one machine carried by one {@link
     * #SLICE_TICKS} window, and that is the term that does grow on a slower host. Measured on this
     * host in GAP_LOG G151.
     */
    public static final long BUDGET_NANOS = 2_000_000L;

    private static volatile int sliceTicks = SLICE_TICKS;
    /**
     * Test-only: whether a balance still outstanding when a chunk goes away is added to whatever
     * the next absence brings. Turning it off is how "the debt did not survive the round trip"
     * is measured, since with it off the outstanding part is simply dropped.
     */
    private static volatile boolean carryDebtAcrossReload = true;
    private static volatile int budgetRealTicks = BUDGET_REAL_TICKS;
    private static volatile long budgetNanos = BUDGET_NANOS;
    /**
     * Where the drain reads the time from.
     *
     * <p>{@link System#nanoTime} in the product. A test that wants to assert on the time budget
     * installs one that advances a fixed amount per reading, which turns "the walk stopped when
     * it ran out of time" into a counted, repeatable claim about how many machines were carried
     * rather than a wall-clock inequality — the shape ruling 6 threw out (GAP_LOG G130, G151).
     */
    private static volatile LongSupplier nanoClock = System::nanoTime;
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

    /**
     * Why a block entity another mod is catching up was walked past.
     *
     * <p>Named rather than inlined because two walks produce it and a gate reads it back.
     */
    private static final String DEFERRED_REASON =
            "deferred to " + CompatibilityCoordinator.UNLOADED_ACTIVITY;

    /** Slices abandoned because something outside the ticker call threw. Logged once, counted. */
    private static volatile int drainFailures;
    private static volatile boolean drainFailureReported;

    /**
     * Cumulative, per chunk, so that paying the same absence twice can be asserted against.
     *
     * <p>Kept only while {@link #recordRunningTotals} is on, which nothing in the product turns
     * on. They are the same shape of leak {@link #SWEPT} was — one entry per chunk ever swept,
     * never evicted, so a server whose players keep moving accumulates three map entries for
     * every chunk they have ever passed through — and unlike {@link #SWEPT} they cannot be
     * dropped when the chunk goes, because what they are for is being read back across a round
     * trip ({@code UnloadedCatchUpGameTests:1114}). Nothing in the product reads them at all
     * (GAP_LOG G142), so the answer is not to evict them but not to write them.
     */
    private static final Map<Job, Long> owedTotal = new ConcurrentHashMap<>();
    private static final Map<Job, Long> paidTotal = new ConcurrentHashMap<>();
    /** Instalments the last settled absence took, per chunk. See {@link #owedTotal}. */
    private static final Map<Job, Integer> slicesUsed = new ConcurrentHashMap<>();

    /**
     * Whether the three maps above are filled in.
     *
     * <p>Off in the product and turned on by the gametest entrypoint, which is the only thing
     * that reads them. Off, the three accessors answer 0 — a test that wants them must ask for
     * them first.
     */
    private static volatile boolean recordRunningTotals;

    private static volatile long drainNanos;
    private static volatile long worstDrainNanos;
    /**
     * The most real ticker invocations any one drain has paid for. The deterministic half of
     * {@link #worstDrainNanos}: how long a drain took is a property of the host, how much work it
     * did is a property of the budget.
     */
    private static volatile int worstDrainRealTicks;
    /**
     * Diagnostic: inside the worst drain, how much of it one single machine cost.
     *
     * <p>What separates two readings of the same spike. If one machine accounts for most of the
     * drain, the spike is the overshoot — the walk cannot stop inside a machine, so a machine that
     * runs its whole slice for real is spent whatever the budget says. If instead the ticks are
     * spread over the machines walked, the spike is the aggregate per-tick cost and the budget is
     * what bounds it. The two have different fixes and the counters that existed could not tell
     * them apart.
     */
    private static volatile int worstDrainOneMachine;
    /** Machines walked in the worst drain, next to {@link #worstDrainOneMachine}. */
    private static volatile int worstDrainMachines;
    /** Which drain of the run the worst one was, counted from one. */
    private static volatile int worstDrainIndex;
    /** Why the costliest machine in the worst drain did not jump. */
    private static volatile String worstDrainWhy = "";
    /** Where it was. */
    private static volatile long worstDrainWherePos;

    // Accumulated while one drain runs. Not volatile: a drain is on the server thread and the
    // re-entrancy guard makes it the only one.
    private static int drainMachines;
    private static int drainOneMachine;
    private static String drainOneMachineWhy = "";
    private static long drainOneMachinePos;

    private static volatile int drains;
    /**
     * How many times an instalment ran out of budget in the middle of a chunk and was carried on
     * by a later level tick.
     *
     * <p>Counted rather than inferred. Whether the walk stops inside a chunk is the whole of what
     * the mid-chunk stop is for, and a suite in which it never happens is a suite that does not
     * exercise it — which is not the same thing as one in which it happens and changes nothing.
     */
    private static volatile int partialPayments;

    private ChunkCatchUp() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ChunkClock.setReconciler(ChunkCatchUp::onReconciled);
        ChunkClock.setDrainer(ChunkCatchUp::drain);
        ChunkClock.setForgetter(new ChunkClock.Forgetter() {
            @Override
            public void forgetChunk(ResourceKey<Level> dimension, long chunkPos) {
                Map<Long, Sweep> perLevel = SWEPT.get(dimension);
                if (perLevel != null) {
                    perLevel.remove(chunkPos);
                }
            }

            @Override
            public void forgetLevel(ResourceKey<Level> dimension) {
                SWEPT.remove(dimension);
            }
        });
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
            Meanwhile.LOGGER.debug("[catchup] skip | chunk={} dim={} lastSeen={} at={} elapsed={}"
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
        Job job = new Job(level.dimension(), key);
        if (recordRunningTotals) {
            owedTotal.merge(job, owed, Long::sum);
        }

        Pending pending = PENDING.computeIfAbsent(job,
                ignored -> new Pending(key, lastSeen, at, level.dimension()));
        pending.owed += owed;
        if (!pending.queued) {
            pending.queued = true;
            WORKLIST.addLast(job);
        }
        Meanwhile.LOGGER.debug("[catchup] owed | chunk={} dim={} lastSeen={} at={} elapsed={}"
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
        long startedAt = nanoClock.getAsLong();
        drainMachines = 0;
        drainOneMachine = 0;
        drainOneMachineWhy = "";
        drainOneMachinePos = 0L;
        int spentRealTicks = 0;
        int jobsTaken = 0;
        int jobsThisTick = WORKLIST.size();
        try {
            // Elapsed rather than a precomputed deadline. An unbounded budget is expressed as
            // Long.MAX_VALUE, and startedAt plus that overflows to a time already past, which
            // stops the drain before it does anything -- measured as every debt gate reporting
            // owed=0 (GAP_LOG G151). A difference of two readings cannot overflow that way.
            while (jobsTaken < jobsThisTick && spentRealTicks < budgetRealTicks
                    && nanoClock.getAsLong() - startedAt < budgetNanos) {
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
                    // What is left of this level tick's budget, so that the chunk walk can stop
                    // inside the chunk instead of only between chunks.
                    spentRealTicks += pay(level, job, budgetRealTicks - spentRealTicks, startedAt);
                } catch (Throwable thrown) {
                    drainFailures++;
                    // The job was taken off the queue and the slice it was in the middle of is
                    // not coming back. Drop the half-finished bookkeeping so the next
                    // reconciliation builds a fresh one; what the chunk is owed rides on the
                    // chunk itself and is untouched by this.
                    PENDING.remove(job);
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
        if (spentRealTicks > worstDrainRealTicks) {
            worstDrainRealTicks = spentRealTicks;
            worstDrainOneMachine = drainOneMachine;
            worstDrainMachines = drainMachines;
            worstDrainIndex = drains;
            worstDrainWhy = drainOneMachineWhy;
            worstDrainWherePos = drainOneMachinePos;
            Meanwhile.LOGGER.info("[catchup] worst drain | dim={} drain={} realTicks={}"
                            + " machines={} oneMachine={} at={} why={}",
                    level.dimension().location(), drains, spentRealTicks, drainMachines,
                    drainOneMachine, BlockPos.of(drainOneMachinePos).toShortString(),
                    drainOneMachineWhy);
        }
        if (jobsTaken > 0) {
            Meanwhile.LOGGER.debug("[catchup] drain | dim={} jobs={} realTicks={} budget={}"
                            + " slice={} took={}us queue={}",
                    level.dimension().location(), jobsTaken, spentRealTicks, budgetRealTicks,
                    sliceTicks, nanos / 1000L, WORKLIST.size());
        }
    }

    /**
     * As much of one chunk's instalment as {@code allowance} pays for. Returns the real ticker
     * invocations it cost.
     *
     * <p>May stop in the middle of the chunk and be called again on a later level tick, which is
     * what keeps the worst single tick off (machines in the chunk) x (the slice).
     */
    private static int pay(ServerLevel level, Job job, int allowance, long startedAt) {
        if (!job.dimension().equals(level.dimension())) {
            WORKLIST.addLast(job);
            return 0;
        }
        Pending pending = PENDING.get(job);
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
        int slice;
        if (pending.instalmentSlice != 0) {
            // Halfway through one. The window was fixed when it started and the balance it is
            // against has not been written down yet, so both are read rather than recomputed.
            slice = pending.instalmentSlice;
        } else {
            // A non-positive figure only gets here with the sign guard deliberately off, and then
            // the whole point is what reaches GenericCatchUp, so it is passed through unchanged.
            slice = remaining <= 0
                    ? (int) Math.max(Integer.MIN_VALUE, remaining)
                    : (int) Math.min(remaining, sliceTicks);
            pending.instalmentSlice = slice;
            // Counted where the instalment is decided, not where it is worked on, so that a
            // chunk carried over several level ticks is still one dispatch of one window.
            minDispatchedTicks = Math.min(minDispatchedTicks, slice);
            dispatches++;
        }

        Mode current = mode;
        Portion portion = current.spend() == Spend.TICK_INTERLEAVED
                // The control is never resumed part-way. "Every machine one tick, then every
                // machine the next" is what it is comparing against, and a control stopped in
                // the middle of a round is no longer that.
                ? new Portion(spendInterleaved(level, chunk, pending.lastSeen, pending.at,
                        pending.owed, slice), Long.MIN_VALUE, true)
                : spend(level, chunk, pending.lastSeen, pending.at, pending.owed, slice,
                        current.spend(), pending.paidUpTo, allowance, startedAt);
        Sweep sliceResult = portion.sweep();
        pending.absorb(sliceResult);

        if (!portion.complete()) {
            // Nothing is written down for a part payment. The balance, the instalment count and
            // the ledger all move exactly once per instalment, at the end of it; crediting them
            // here would count one payment as many and turn "was anything paid twice?" — the one
            // assertion in this file that would otherwise be silent and severe — into a test of
            // how many level ticks the instalment happened to be spread over.
            pending.paidUpTo = portion.stoppedAfter();
            if (partialPayments++ == 0) {
                // Once per run. That the walk stops inside a chunk at all is the thing the
                // mid-chunk stop exists to do, and "it never happened" and "it happened and
                // changed nothing" are different readings of the same silent log.
                Meanwhile.LOGGER.info("[catchup] part payment | chunk={} dim={} stoppedAfter={}"
                                + " carried={} | slice={} allowance={}",
                        pos, level.dimension().location(),
                        BlockPos.of(portion.stoppedAfter()),
                        sliceResult.attempted(), slice, allowance);
            }
            WORKLIST.addLast(job);
            return sliceResult.realTicks();
        }

        pending.paidUpTo = Long.MIN_VALUE;
        pending.instalmentSlice = 0;
        pending.slices++;
        if (recordRunningTotals) {
            paidTotal.merge(job, (long) Math.max(slice, 0), Long::sum);
        }

        long left = remaining <= 0 ? 0L : remaining - slice;
        setDebt(chunk, left);
        if (left > 0) {
            WORKLIST.addLast(job);
            return sliceResult.realTicks();
        }

        pending.queued = false;
        PENDING.remove(job);
        if (recordRunningTotals) {
            slicesUsed.put(job, pending.slices);
        }
        Sweep whole = pending.toSweep();
        SWEPT.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .put(job.chunkPos(), whole);
        Meanwhile.LOGGER.debug("[catchup] sweep | chunk={} dim={} lastSeen={} at={} elapsed={}"
                        + " dispatched={} mode={} attempted={} jumped={} declined={}"
                        + " realTicks={} jumpedTicks={} slices={} paidOver={} ticks",
                pos, level.dimension().location(), whole.lastSeen(), whole.at(), whole.elapsed(),
                whole.dispatched(), current.label(), whole.attempted(), whole.jumped(),
                whole.declined(), whole.realTicks(), whole.jumpedTicks(), pending.slices,
                level.getGameTime() - pending.at);
        // Guarded rather than merely demoted: summary() builds a string per block entity in the
        // chunk, and that is paid whether or not anything goes on to print it.
        if (Meanwhile.LOGGER.isDebugEnabled()) {
            for (Attempt attempt : whole.attempts()) {
                Meanwhile.LOGGER.debug("[catchup] be | chunk={} {}", pos, attempt.summary());
            }
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
        /**
         * How far into the chunk the instalment in flight has got, as the packed position of the
         * last block entity it carried. {@link Long#MIN_VALUE} when no instalment is in flight.
         *
         * <p>A packed position rather than an index into the walk. The walk is rebuilt from the
         * chunk's own map every time and a catch-up is allowed to add or remove a block entity,
         * so an index means something different on the next pass; a position that the walk is
         * sorted by does not move when its neighbours do. A machine placed behind the mark during
         * an instalment is simply not carried by that instalment and waits for the next one.
         */
        private long paidUpTo = Long.MIN_VALUE;
        /**
         * The window this instalment is carrying every machine by, frozen when it starts.
         *
         * <p>Zero when none is in flight. Frozen because the instalment is one payment of the
         * debt however many level ticks it is spread over: recomputing it against the outstanding
         * balance halfway would carry the second half of the chunk by a different amount than the
         * first, and the balance is only written down when the whole chunk has been carried.
         */
        private int instalmentSlice;

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
    private static Portion spend(ServerLevel level, LevelChunk chunk, long lastSeen, long at,
                                 long elapsed, int dispatched, Spend how, long resumeAfter,
                                 int allowance, long startedAt) {
        HolderLookup.Provider registries = level.registryAccess();
        List<BlockPos> positions = positionsOf(chunk);

        List<Attempt> attempts = new ArrayList<>();
        int jumped = 0;
        int declined = 0;
        int realTicks = 0;
        int jumpedTicks = 0;
        long stoppedAfter = resumeAfter;
        boolean complete = true;

        // Everything below that is not the catch-up itself exists to be compared against, and a
        // comparison is something a measurement installs. Serialising every block entity twice
        // per slice, naming its block, its class and the ticker the game would have handed out
        // are all reads that the product never looks at; on a chunk of a hundred machines they
        // are the larger half of what a slice costs. What is not conditional is anything a
        // counter is taken from: the real ticks spent are what the drain budgets on.
        boolean recording = observer != null;

        for (BlockPos pos : positions) {
            if (pos.asLong() <= resumeAfter) {
                // Carried already by an earlier level tick of this same instalment.
                continue;
            }
            // Checked before the machine rather than after it, so that what the budget bounds is
            // the work this level tick is about to start rather than the work it has finished.
            // One machine is always carried, whatever the allowance: a drain that can decline to
            // do anything is a drain that never finishes, and the chunk it stalled on is the one
            // the player is standing in. The overshoot that buys is one machine's slice, which is
            // the quantum the budget cannot see inside of (GAP_LOG G151).
            if (!attempts.isEmpty()
                    && (realTicks >= allowance
                            || nanoClock.getAsLong() - startedAt >= budgetNanos)) {
                complete = false;
                break;
            }
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity == null) {
                // Removed by an earlier catch-up in this same sweep.
                continue;
            }
            stoppedAfter = pos.asLong();
            CompoundTag before = recording ? blockEntity.saveWithoutMetadata(registries) : null;
            String block = recording
                    ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()
                    : UNRECORDED;
            String type = recording ? blockEntity.getClass().getName() : UNRECORDED;
            String ticker = recording ? tickerName(level, pos) : UNRECORDED;

            Attempt attempt;
            try {
                if (isDeferred(blockEntity)) {
                    // Another mod's machine this run. Walked and reported like any other, and not
                    // touched: no tick, no serialisation, no jump. The window it was owed is spent
                    // by whoever owns it, which is the whole of what deferring means here.
                    attempt = new Attempt(pos, block, type, ticker, dispatched, true,
                            DEFERRED_REASON, 0, 0, 0, "{}", "{}", before, before);
                } else if (CatchUpGuard.isIsolated(level.dimension(), pos)) {
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
            drainMachines++;
            if (attempt.realTicks() > drainOneMachine) {
                // References only, no string built: this runs once per machine per slice and the
                // line that reads it is printed at most once per new worst drain.
                drainOneMachine = attempt.realTicks();
                drainOneMachineWhy = attempt.refusals();
                drainOneMachinePos = pos.asLong();
            }

            if (attempt.declined()) {
                declined++;
            } else if (attempt.jumps() > 0) {
                jumped++;
            }
            realTicks += attempt.realTicks();
            jumpedTicks += attempt.jumpedTicks();
        }

        return new Portion(new Sweep(chunk.getPos().toLong(), lastSeen, at, elapsed, dispatched,
                attempts.size(), jumped, declined, realTicks, jumpedTicks, attempts),
                stoppedAfter, complete);
    }

    /**
     * One level tick's worth of an instalment: what it carried, how far it got, and whether that
     * was the whole chunk.
     *
     * <p>{@code stoppedAfter} is only meaningful when {@code complete} is false, and is the packed
     * position the next level tick resumes after.
     */
    private record Portion(Sweep sweep, long stoppedAfter, boolean complete) {
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
        List<Boolean> deferrals = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity == null) {
                continue;
            }
            present.add(pos);
            // Asked here, once, rather than inside the tick loop: the answer cannot change while
            // the loop runs, and this arm runs the loop once per tick of the window.
            deferrals.add(isDeferred(blockEntity));
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
                if (deferrals.get(i)) {
                    continue;
                }
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
                    ran[i] == 0,
                    deferrals.get(i) ? DEFERRED_REASON
                            : ran[i] == 0 ? "nothing tickable at " + pos : null,
                    ran[i], 0, 0, "{}", "{control}", befores.get(i),
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

    /**
     * Whether this block entity is another mod's job this run.
     *
     * <p>Two reads and no allocation, per block entity per slice, and both are false in every run
     * where nothing overlapping is installed: the flag is checked first so that the type test is
     * not even reached in the ordinary case. Which types these are and why the set is this small
     * is {@link CompatibilityCoordinator}'s subject.
     */
    private static boolean isDeferred(BlockEntity blockEntity) {
        return CompatibilityCoordinator.defersFurnaces()
                && blockEntity instanceof AbstractFurnaceBlockEntity;
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
        worstDrainRealTicks = 0;
        worstDrainOneMachine = 0;
        worstDrainMachines = 0;
        worstDrainIndex = 0;
        worstDrainWhy = "";
        worstDrainWherePos = 0L;
        drains = 0;
        partialPayments = 0;
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
     *
     * <p>Which chunks to zero comes from {@link #owedTotal}, so this clears debts only while
     * the running totals are being kept. That is every run this is reachable from — the same
     * source set turns both on — and the product calls neither.
     */
    public static void forget(ServerLevel level) {
        for (Job job : new ArrayList<>(owedTotal.keySet())) {
            if (!job.dimension().equals(level.dimension())) {
                continue;
            }
            ChunkPos pos = new ChunkPos(job.chunkPos());
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                setDebt(chunk, 0L);
            }
        }
        WORKLIST.clear();
        PENDING.clear();
        Meanwhile.LOGGER.info("[catchup] forget | queue and debts cleared");
    }

    /**
     * How many instalments the last settled absence on this chunk took. 0 unless
     * {@link #setRecordRunningTotals} asked for the figure to be kept.
     */
    public static int slicesFor(ServerLevel level, ChunkPos pos) {
        return slicesUsed.getOrDefault(new Job(level.dimension(), pos.toLong()), 0);
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

    /**
     * The most real ticker invocations any one drain has paid for. What a budget is supposed to
     * bound, counted rather than timed, so an assertion about it holds on any host.
     */
    public static int worstDrainTicks() {
        return worstDrainRealTicks;
    }

    /** See {@link #worstDrainOneMachine}. */
    public static int worstDrainOneMachine() {
        return worstDrainOneMachine;
    }

    /** See {@link #worstDrainMachines}. */
    public static int worstDrainMachines() {
        return worstDrainMachines;
    }

    /** See {@link #worstDrainIndex}. */
    public static int worstDrainIndex() {
        return worstDrainIndex;
    }

    /** See {@link #worstDrainWhy}. */
    public static String worstDrainWhy() {
        return worstDrainWhy;
    }

    public static long totalDrainMicros() {
        return drainNanos / 1000L;
    }

    public static int drains() {
        return drains;
    }

    /** See {@link #partialPayments}. */
    public static int partialPayments() {
        return partialPayments;
    }

    public static int queueLength() {
        return WORKLIST.size();
    }

    /**
     * What this chunk has been told it is owed, over the whole run. 0 unless
     * {@link #setRecordRunningTotals} asked for the figure to be kept.
     */
    public static long owedFor(ServerLevel level, ChunkPos pos) {
        return owedTotal.getOrDefault(new Job(level.dimension(), pos.toLong()), 0L);
    }

    /**
     * What has actually been handed over for it, over the whole run. 0 unless
     * {@link #setRecordRunningTotals} asked for the figure to be kept.
     */
    public static long paidFor(ServerLevel level, ChunkPos pos) {
        return paidTotal.getOrDefault(new Job(level.dimension(), pos.toLong()), 0L);
    }

    /**
     * Test-only: keep the per-chunk running totals, at the cost of three map entries per chunk
     * ever swept that nothing evicts. See {@link #owedTotal} for why nothing evicts them.
     */
    public static void setRecordRunningTotals(boolean record) {
        recordRunningTotals = record;
        Meanwhile.LOGGER.info("[catchup] running totals | {}", record ? "kept" : "not kept");
    }

    /** Whether the per-chunk running totals are being kept. */
    public static boolean recordsRunningTotals() {
        return recordRunningTotals;
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
        // The time budget is put out of the way as well, so that a caller saying "I am setting
        // the work budget" gets a drain bounded by work alone. Without this, a test that varies
        // the work axis and reads worstDrainTicks would silently be reading a number the host's
        // speed had a hand in as soon as its arena grew a second machine — which is exactly the
        // host dependence ruling 6 removed, coming back by a route nobody is watching. The
        // survival of that gate is meant to be structural, not a property of one arena holding
        // one millstone (GAP_LOG G151).
        budgetNanos = Long.MAX_VALUE;
        Meanwhile.LOGGER.info("[catchup] budget | slice={} realTicks={} nanos=unbounded",
                slice, realTicks);
    }

    /**
     * Test-only: how long a level tick may spend, and where it reads the time from.
     *
     * <p>The clock is injected rather than mocked away so that the assertion stays on a counted
     * quantity: with a clock that advances a fixed amount per reading, "the walk stopped when it
     * ran out of time" is a claim about how many machines were carried, and it holds on any host.
     */
    public static void setBudgetNanos(long nanos, LongSupplier clock) {
        budgetNanos = nanos;
        nanoClock = clock;
        Meanwhile.LOGGER.info("[catchup] budget | nanos={} clock={}", nanos,
                clock == null ? "null" : clock.getClass().getName());
    }

    /** Test-only. See {@link #carryDebtAcrossReload}. */
    public static void setCarryDebtAcrossReload(boolean carry) {
        carryDebtAcrossReload = carry;
        Meanwhile.LOGGER.info("[catchup] carry debt across reload | {}", carry);
    }

    public static void restoreBudget() {
        setBudget(SLICE_TICKS, BUDGET_REAL_TICKS);
        setBudgetNanos(BUDGET_NANOS, System::nanoTime);
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
