package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The same chunk coordinates in two dimensions are two chunks, and each is owed its own window.
 *
 * <p>What this catches. The worklist entry, the running totals and the instalment count were all
 * keyed on the packed chunk position alone, while the job carries a dimension. Overworld (5,5)
 * and Nether (5,5) pack to the same long, so the second dimension to fall behind found the
 * first one's entry already standing, added its window to that one's total, and never queued a
 * job of its own — leaving a chunk carrying a balance that nothing was going to pay (GAP_LOG
 * G139).
 *
 * <p>Both halves of that are asserted: the totals are read back per dimension and each must equal
 * the window that dimension alone was behind by, and the balance on each chunk must reach zero.
 * Conflated bookkeeping fails the first — both dimensions read the sum — and a job that was never
 * queued fails the second.
 *
 * <h3>How the two chunks are made to fall behind</h3>
 * <p>{@link ChunkClock#setStampOffset} writes a time into the past onto whatever chunk carries
 * that packed position, and {@link ChunkClock#rearm} puts one chunk back into the state it is in
 * on the tick after it loads. Together they reproduce an arrival without a real unload, which is
 * what {@link ScaffoldGameTests} already relies on. That the offset is dimension-blind is
 * convenient here rather than a defect being worked around: it is what puts a stale stamp on both
 * chunks at the same coordinates. The two are rearmed separately and from different offsets, so
 * the two windows are different numbers and their sum cannot be mistaken for either of them.
 *
 * <p>The Overworld is rearmed first and the Nether while the Overworld is still paying, which is
 * the order that reproduces the collision: an entry has to be standing for the second one to
 * find.
 *
 * <h3>Why the sweep is restricted to one position</h3>
 * <p>What is under test is the bookkeeping, not what a window does to a machine. Restricting the
 * mode to a position that holds no block entity makes every slice cost nothing and takes whatever
 * the Nether happens to generate at these coordinates out of the measurement, so the test says
 * the same thing whatever the terrain is.
 *
 * <p>No {@code @GameTestHolder}: registered from {@code MeanwhileGates}, like the other vanilla
 * gates. Its own batch, because it forces a chunk in another dimension and writes the global mode
 * and stamp offset, none of which is scoped to a test.
 */
public final class DimensionKeyGameTests {

    static final String BATCH = "dimensionkey";

    /** Ticks the Overworld chunk is made to be behind by. Above {@link ChunkClock#THRESHOLD_TICKS}. */
    private static final int OVERWORLD_STALE = 2500;
    /**
     * And the Nether. Different from the Overworld's on purpose: with two different windows,
     * bookkeeping that adds them together reads a number that is neither, so the failure names
     * itself instead of looking like a rounding argument.
     */
    private static final int NETHER_STALE = 4500;

    /**
     * Ticks given to the whole cycle. Most of it is room for a Nether chunk to generate, which
     * happens on worker threads and is a quantity of real time rather than of ticks. The cycle
     * finishes as soon as both balances reach zero, so a passing run does not spend this.
     */
    private static final int RUN_TICKS = 12_000;

    /** Ticks a stale stamp is given to be written before the chunk is rearmed. */
    private static final int STAMP_SETTLE = 3;

    /** A position inside the arena that holds no block entity. */
    private static final BlockPos NOTHING = new BlockPos(1, 1, 1);

    private DimensionKeyGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH,
            timeoutTicks = RUN_TICKS + 1000)
    public static void debtsAtTheSameCoordinatesInTwoDimensionsBothDrain(GameTestHelper helper) {
        Cycle cycle = new Cycle(helper);
        helper.startSequence()
                .thenExecuteFor(RUN_TICKS, cycle::step)
                // Only reached if the cycle ran the window out without judging, which it treats
                // as a failure of its own. Here so that the window expiring can never be silent.
                .thenExecute(cycle::finish)
                .thenSucceed();
    }

    private enum Step { WAIT_FOR_NETHER, ARM_OVERWORLD, REARM_OVERWORLD, READ_OVERWORLD,
                        ARM_NETHER, REARM_NETHER, READ_NETHER, DRAINING, DONE }

    private static final class Cycle {

        private final GameTestHelper helper;
        private final ServerLevel overworld;
        private final ChunkPos target;
        private final BlockPos restrictTo;

        @Nullable
        private ServerLevel nether;

        private Step step = Step.WAIT_FOR_NETHER;
        private int countdown;
        private int waited;
        private boolean finished;
        private long rearmedAt = -1L;

        private long owedBeforeOverworld;
        private long paidBeforeOverworld;
        private long owedBeforeNether;
        private long paidBeforeNether;

        private long windowOverworld = -1L;
        private long windowNether = -1L;

        @Nullable
        private String failure;

        private Cycle(GameTestHelper helper) {
            this.helper = helper;
            this.overworld = helper.getLevel();
            this.restrictTo = helper.absolutePos(NOTHING);
            this.target = new ChunkPos(this.restrictTo);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            if (++waited > RUN_TICKS - 10) {
                fail("the cycle never finished; it was stuck at " + step);
                return;
            }
            switch (step) {
                case WAIT_FOR_NETHER -> waitForNether();
                case ARM_OVERWORLD -> armOverworld();
                case REARM_OVERWORLD -> rearm(overworld, Step.READ_OVERWORLD);
                case REARM_NETHER -> rearm(requireNether(), Step.READ_NETHER);
                case READ_OVERWORLD -> readOverworld();
                case ARM_NETHER -> armNether();
                case READ_NETHER -> readNether();
                case DRAINING -> draining();
                case DONE -> {
                }
            }
            if (step == Step.DONE) {
                finish();
            }
        }

        /**
         * Force the same coordinates in the Nether and wait until the clock has written a time
         * onto that chunk. Generation runs on worker threads, so how long that takes is a
         * property of the host and is waited for rather than assumed.
         */
        private void waitForNether() {
            if (nether == null) {
                nether = overworld.getServer().getLevel(Level.NETHER);
                if (nether == null) {
                    fail("this server has no Nether, so two dimensions at one chunk position"
                            + " cannot be set up and nothing here would be measured");
                    return;
                }
                nether.setChunkForced(target.x, target.z, true);
                Meanwhile.LOGGER.info("[dimkey] forcing | chunk={} dim={}", target,
                        nether.dimension().location());
                return;
            }
            Long stamp = ChunkClock.peek(nether, target);
            if (stamp == null) {
                return;
            }
            Meanwhile.LOGGER.info("[dimkey] nether chunk running | chunk={} stamp={}"
                    + " afterTicks={}", target, stamp, waited);
            step = Step.ARM_OVERWORLD;
        }

        private void armOverworld() {
            ServerLevel hell = requireNether();
            // Wait for the level to go quiet before resetting anything global. This arm used to
            // clear the worklist while other gates still had jobs in it — measured at 3 queued
            // and 3 pending (GAP_LOG G157) — which threw that work away and had it re-queued and
            // paid off inside the window this test was about to open. Nothing here failed when
            // it happened; other gates moved. Staying in this step costs ticks off the RUN_TICKS
            // budget, so a level that never goes quiet is reported rather than waited on for ever.
            if (ChunkCatchUp.workInFlight()) {
                return;
            }
            ChunkCatchUp.forget(overworld);
            ChunkCatchUp.forget(hell);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.restrictedTo(restrictTo));
            owedBeforeOverworld = ChunkCatchUp.owedFor(overworld, target);
            paidBeforeOverworld = ChunkCatchUp.paidFor(overworld, target);
            owedBeforeNether = ChunkCatchUp.owedFor(hell, target);
            paidBeforeNether = ChunkCatchUp.paidFor(hell, target);
            ChunkClock.setStampOffset(target, -OVERWORLD_STALE);
            countdown = STAMP_SETTLE;
            step = Step.REARM_OVERWORLD;
        }

        private void armNether() {
            ChunkClock.setStampOffset(target, -NETHER_STALE);
            countdown = STAMP_SETTLE;
            step = Step.REARM_NETHER;
        }

        /** Give the stale stamp a few ticks to be written, then put the chunk back on arrival. */
        private void rearm(ServerLevel level, Step next) {
            if (--countdown > 0) {
                return;
            }
            rearmedAt = level.getGameTime();
            ChunkClock.rearm(level, target);
            step = next;
        }

        private void readOverworld() {
            Long window = windowOf(overworld);
            if (window == null) {
                return;
            }
            windowOverworld = window;
            Meanwhile.LOGGER.info("[dimkey] overworld behind | chunk={} elapsed={} balance={}",
                    target, window, ChunkCatchUp.debtFor(overworld, target));
            step = Step.ARM_NETHER;
        }

        private void readNether() {
            ServerLevel hell = requireNether();
            Long window = windowOf(hell);
            if (window == null) {
                return;
            }
            windowNether = window;
            Meanwhile.LOGGER.info("[dimkey] nether behind | chunk={} elapsed={} balance={}",
                    target, window, ChunkCatchUp.debtFor(hell, target));
            step = Step.DRAINING;
        }

        /** The elapsed count the clock worked out on the rearm, once it has worked one out. */
        @Nullable
        private Long windowOf(ServerLevel level) {
            ChunkClock.Reconciliation result = ChunkClock.lastReconciliation(level, target);
            if (result == null || result.at() < rearmedAt || result.elapsed() <= 0L) {
                return null;
            }
            return result.elapsed();
        }

        private void draining() {
            if (ChunkCatchUp.debtFor(overworld, target) != 0L
                    || ChunkCatchUp.debtFor(requireNether(), target) != 0L) {
                return;
            }
            step = Step.DONE;
            judge();
        }

        private void judge() {
            ServerLevel hell = requireNether();
            long owedOverworld = ChunkCatchUp.owedFor(overworld, target) - owedBeforeOverworld;
            long paidOverworld = ChunkCatchUp.paidFor(overworld, target) - paidBeforeOverworld;
            long owedNether = ChunkCatchUp.owedFor(hell, target) - owedBeforeNether;
            long paidNether = ChunkCatchUp.paidFor(hell, target) - paidBeforeNether;

            Meanwhile.LOGGER.info("[dimkey] RESULT | chunk={} || overworld: behind={} owed={}"
                            + " paid={} slices={} || nether: behind={} owed={} paid={} slices={}",
                    target, windowOverworld, owedOverworld, paidOverworld,
                    ChunkCatchUp.slicesFor(overworld, target), windowNether, owedNether,
                    paidNether, ChunkCatchUp.slicesFor(hell, target));

            if (windowOverworld == windowNether) {
                fail("both dimensions were behind by " + windowOverworld + " ticks, so a total"
                        + " that added them together would be indistinguishable from one of them"
                        + " and this test would prove nothing");
                return;
            }
            if (owedOverworld != windowOverworld) {
                fail("the Overworld chunk " + target + " was behind by " + windowOverworld
                        + " ticks but is recorded as owing " + owedOverworld
                        + "; the Nether chunk at the same position was behind by " + windowNether
                        + ", and " + (windowOverworld + windowNether) + " is the two added"
                        + " together, which is what one key for two dimensions produces");
                return;
            }
            if (owedNether != windowNether) {
                fail("the Nether chunk " + target + " was behind by " + windowNether
                        + " ticks but is recorded as owing " + owedNether
                        + "; the Overworld chunk at the same position was behind by "
                        + windowOverworld + ", and " + (windowOverworld + windowNether)
                        + " is the two added together");
                return;
            }
            if (paidOverworld < windowOverworld) {
                fail("the Overworld chunk " + target + " owed " + windowOverworld
                        + " ticks and only " + paidOverworld + " were paid");
                return;
            }
            if (paidNether < windowNether) {
                fail("the Nether chunk " + target + " owed " + windowNether + " ticks and only "
                        + paidNether + " were paid, which is what a job that was never queued"
                        + " leaves behind");
            }
        }

        /** Puts back everything this test set, whatever happened, and reports once. */
        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            ChunkClock.setStampOffset(target, 0L);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(overworld);
            if (nether != null) {
                ChunkCatchUp.forget(nether);
                nether.setChunkForced(target.x, target.z, false);
            }
            if (failure == null && windowNether < 0L) {
                failure = "the cycle never got as far as making the Nether chunk fall behind";
            }
            if (failure != null) {
                helper.fail(failure);
                return;
            }
            helper.succeed();
        }

        private ServerLevel requireNether() {
            ServerLevel hell = nether;
            if (hell == null) {
                throw new IllegalStateException("the Nether was read before it was found");
            }
            return hell;
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }
    }
}
