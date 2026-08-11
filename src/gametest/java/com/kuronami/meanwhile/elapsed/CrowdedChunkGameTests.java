package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * A chunk that comes back holding a crowd, and what one level tick spends settling it.
 *
 * <h3>What this arena is, and what it is not</h3>
 * <p>The machines are placed inside this test's own bounds, but <b>the walk this measures is the
 * product's, and the product walks the whole chunk</b>. GameTest spaces arenas 14 blocks apart and
 * a chunk is 16 wide, so this arena shares its chunk with whatever the suite put next to it, and
 * the machines this test placed are not the only ones the drain is offered. That is not a defect
 * to be fixed by narrowing the walk — narrowing the walk would stop measuring the product
 * (GAP_LOG G129 scoped the <em>comparator</em> to arena bounds and deliberately left the
 * production walk chunk-wide).
 *
 * <p>What it means is that a number from this test describes a population that depends on what
 * else is in the suite. So every figure below is reported with <b>both</b> counts: {@code
 * intended=} what this test placed, and {@code offered=} what the drain was actually handed. A
 * reading where those two differ is not wrong, it is a reading of a different chunk than the one
 * this file describes, and a threshold tuned against it would not survive the suite changing
 * (GAP_LOG G150 ruling 27).
 *
 * <h3>The two things asserted</h3>
 * <ul>
 *   <li><b>The walk stops inside the chunk.</b> Before the mid-chunk stop, the worst single level
 *       tick was (machines in the chunk) x (the slice) and there was no way to interrupt it. The
 *       assertion is on a counted quantity — that an instalment was carried across more than one
 *       level tick — not on how long anything took.</li>
 *   <li><b>The time budget bounds the walk, asserted without consulting a clock.</b> The drain
 *       reads time through an injected supplier; this installs one that advances a fixed amount
 *       per reading, so "it stopped when it ran out of time" becomes a claim about how many
 *       machines were carried. That holds on a loaded host and on an idle one, which a
 *       microsecond comparison would not (GAP_LOG G130 ruling 6).</li>
 * </ul>
 */
public final class CrowdedChunkGameTests {

    /** How many machines this test means to put in the chunk. Reported next to every figure. */
    private static final int INTENDED = 100;

    /**
     * The window each machine is carried by. Small enough that a hundred of them is a crowd
     * rather than a test that runs for a minute, and far enough above the budget that the walk has
     * to stop somewhere.
     */
    private static final int WINDOW = 20000;

    /**
     * The fewest machines in the measured chunk this test will report a figure for.
     *
     * <p>Not {@link #INTENDED}: a 9-wide arena placed at an arbitrary offset straddles a 16-wide
     * chunk, so how many of the placed machines land in the chunk the drain is measured on is a
     * property of where the run put the arena. The floor is what makes the reading a crowd rather
     * than a couple; the exact number is reported next to every figure and is not asserted on.
     */
    private static final int FLOOR = 60;

    /** Ticks the fake clock adds per reading. */
    private static final long CLOCK_STEP = 250_000L;

    /**
     * The time budget the fake clock is measured against: four of its steps.
     *
     * <p>Chosen in the units of the fake clock rather than of the host, which is the whole point
     * of injecting it.
     */
    private static final long FAKE_BUDGET = CLOCK_STEP * 4L;

    private CrowdedChunkGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "crowdedchunk", timeoutTicks = 14400)
    public static void spikeIsBoundedOnACrowdedChunk(GameTestHelper helper) {
        RoundTripImages.install();
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed, so this run measures nothing");
            return;
        }
        Crowd probe = new Crowd(helper);
        helper.startSequence()
                .thenExecuteFor(13400, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    /** A clock that moves only when it is read, by a fixed amount. */
    private static final class SteppingClock implements LongSupplier {

        private long now;

        @Override
        public long getAsLong() {
            long value = now;
            now += CLOCK_STEP;
            return value;
        }
    }

    private static final class Crowd {

        private enum Step { PLACING, RELEASED, GONE, BACK, PAYING, DONE }

        private static final int WAIT = 60;
        private static final int BACK_WAIT = 400;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;

        private Step step = Step.PLACING;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        private long payingSince = -1L;

        private int phase;
        private int placed;
        private final int[] offered = new int[2];
        private final int[] worstTicks = new int[2];
        private final long[] worstMicros = new long[2];
        private final int[] partials = new int[2];
        @Nullable
        private String failure;

        private Crowd(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.arena = UnloadedCatchUpGameTests.arenaChunks(helper);
            this.target = busiest(helper);
        }

        /**
         * The arena chunk holding the most of this test's interior positions.
         *
         * <p>Not the chunk the structure block is in. A 9-wide arena placed at an arbitrary
         * offset straddles a 16-wide chunk, and taking the corner's chunk made the measured
         * population a lottery: three runs of this test placed 100, 24 and 12 machines in it
         * (GAP_LOG G151). A figure whose population changes by eight times between runs describes
         * nothing. The busiest chunk is the one the arena has most of, so the count is stable
         * whatever the offset, and it is still reported rather than assumed.
         */
        private ChunkPos busiest(GameTestHelper helper) {
            Map<ChunkPos, Integer> counts = new HashMap<>();
            for (BlockPos pos : interior(helper)) {
                counts.merge(new ChunkPos(pos), 1, Integer::sum);
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(new ChunkPos(helper.absolutePos(BlockPos.ZERO)));
        }

        /** Every position this test is willing to put a machine at, in arena order. */
        private static List<BlockPos> interior(GameTestHelper helper) {
            List<BlockPos> all = new ArrayList<>();
            for (int x = 1; x < 8; x++) {
                for (int z = 1; z < 8; z++) {
                    for (int y = 1; y < 5; y++) {
                        all.add(helper.absolutePos(new BlockPos(x, y, z)));
                    }
                }
            }
            return all;
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    placed = phase == 0 ? fill() : placed;
                    if (placed < FLOOR) {
                        fail("only " + placed + " machines went into the measured chunk, under the"
                                + " floor of " + FLOOR + "; the arena straddled a chunk boundary"
                                + " badly enough that this is not a crowd, and a spike measured"
                                + " over that population is not the one this test is named for");
                        return;
                    }
                    ChunkCatchUp.resetCounters();
                    ChunkCatchUp.setBudget(ChunkCatchUp.SLICE_TICKS,
                            ChunkCatchUp.BUDGET_REAL_TICKS);
                    // Phase 0 is the measurement and runs on the real clock with the product's
                    // own time budget, because a figure in microseconds taken off a clock this
                    // test is driving describes the fake clock and nothing else. Phase 1 is the
                    // deterministic gate and installs the stepping clock. setBudget deliberately
                    // puts the time budget out of the way for callers driving the work axis, so
                    // both are set here, in that order.
                    if (phase == 0) {
                        ChunkCatchUp.setBudgetNanos(ChunkCatchUp.BUDGET_NANOS, System::nanoTime);
                    } else {
                        ChunkCatchUp.setBudgetNanos(FAKE_BUDGET, new SteppingClock());
                    }
                    ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.withFixedWindow(WINDOW));
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                    }
                }
                case GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    askedAt = now;
                    step = Step.BACK;
                }
                case BACK -> {
                    if (ChunkCatchUp.owedFor(level, target) > 0) {
                        payingSince = now;
                        step = Step.PAYING;
                        return;
                    }
                    if (now - askedAt > BACK_WAIT) {
                        fail("the chunk came back at " + askedAt + " and was never told it owed"
                                + " anything, so nothing was measured");
                    }
                }
                case PAYING -> {
                    if (ChunkCatchUp.debtFor(level, target) > 0) {
                        if (now - payingSince > 4000) {
                            fail("still owing " + ChunkCatchUp.debtFor(level, target)
                                    + " after 4000 ticks");
                        }
                        return;
                    }
                    ChunkCatchUp.Sweep sweep = ChunkCatchUp.lastSweep(level, target);
                    offered[phase] = sweep == null ? 0 : sweep.attempted();
                    worstTicks[phase] = ChunkCatchUp.worstDrainTicks();
                    worstMicros[phase] = ChunkCatchUp.worstDrainMicros();
                    partials[phase] = ChunkCatchUp.partialPayments();
                    Meanwhile.LOGGER.info("[crowd] phase {} | clock={} intended={} placed={}"
                                    + " offered={} worstDrainTicks={} partPayments={}",
                            phase, phase == 0 ? "real" : "stepping", INTENDED, placed,
                            offered[phase], worstTicks[phase], partials[phase]);
                    if (++phase >= 2) {
                        step = Step.DONE;
                        return;
                    }
                    ChunkCatchUp.forget(level);
                    RoundTripImages.stopWatching();
                    step = Step.PLACING;
                }
                default -> {
                }
            }
        }

        /**
         * Fills the arena with lit, fuelled furnaces, <b>only at positions inside the chunk this
         * test measures</b>. Returns how many went in.
         *
         * <p>A GameTest arena is 9 wide and placed wherever the run puts it, so it straddles a
         * chunk boundary more often than not, and machines placed by arena coordinates land in
         * two or three different chunks. The drain is per chunk, so a spike measured on one chunk
         * over machines spread across three is a spike over a fraction of what was placed — the
         * first run of this test placed 100 and the measured chunk held 18 of them (GAP_LOG
         * G151). Filtering here is what makes the placed count and the measured count the same
         * population.
         */
        private int fill() {
            List<BlockPos> spots = new ArrayList<>();
            for (BlockPos abs : interior(helper)) {
                if (spots.size() >= INTENDED) {
                    break;
                }
                if (new ChunkPos(abs).equals(target)) {
                    spots.add(abs);
                }
            }
            int in = 0;
            for (BlockPos pos : spots) {
                BlockState lit = Blocks.FURNACE.defaultBlockState()
                        .setValue(AbstractFurnaceBlock.LIT, true);
                level.setBlock(pos, lit, 3);
                if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                    continue;
                }
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
                furnace.setItem(1, new ItemStack(Items.COAL, 64));
                furnace.setItem(2, ItemStack.EMPTY);
                in++;
            }
            return in;
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            ChunkCatchUp.restoreBudget();
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(level);
            RoundTripImages.stopWatching();

            // Both counts, on every line, always. See the class comment. The microsecond
            // figure is reported for phase 0 only: phase 1 reads a clock this test is driving,
            // so its microseconds describe the stepping clock and not the host.
            Meanwhile.LOGGER.info("[crowd] RESULT intended={} placed={} | phase0 real clock:"
                            + " offered={} worstDrainTicks={} worstDrain={}us partPayments={}"
                            + " | phase1 stepping clock: offered={} worstDrainTicks={}"
                            + " partPayments={} | window={} slice={} ceiling={} budgetNanos={}"
                            + " fakeBudget={} clockStep={}",
                    INTENDED, placed, offered[0], worstTicks[0], worstMicros[0], partials[0],
                    offered[1], worstTicks[1], partials[1], WINDOW, ChunkCatchUp.SLICE_TICKS,
                    ChunkCatchUp.BUDGET_REAL_TICKS, ChunkCatchUp.BUDGET_NANOS, FAKE_BUDGET,
                    CLOCK_STEP);

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            int bound = ChunkCatchUp.BUDGET_REAL_TICKS + ChunkCatchUp.SLICE_TICKS;
            for (int i = 0; i < 2; i++) {
                if (offered[i] < placed) {
                    helper.fail("phase " + i + ": the drain was offered " + offered[i]
                            + " machines and this test placed " + placed + " in that chunk; a"
                            + " spike measured over fewer machines than were placed is not the"
                            + " crowd it is named for (intended " + INTENDED + ")");
                    return;
                }
                // 1. The walk stopped inside the chunk and went on later. Counted, not timed.
                if (partials[i] < 1) {
                    helper.fail("phase " + i + ": no instalment was carried across more than one"
                            + " level tick, so the walk paid all " + offered[i] + " machines in"
                            + " one go and the mid-chunk stop was not exercised");
                    return;
                }
                // 2. What one drain did is bounded, in a unit that does not depend on the host.
                // The overshoot allowed for is one machine carried by one slice: the walk stops
                // on a machine boundary and cannot see inside GenericCatchUp's window, because
                // the state that authorises a jump is built over that window and is not
                // resumable without reopening it (GAP_LOG G137, G151).
                if (worstTicks[i] > bound) {
                    helper.fail("phase " + i + ": one drain ticked " + worstTicks[i]
                            + " times, over the ceiling of " + ChunkCatchUp.BUDGET_REAL_TICKS
                            + " plus the one-machine overshoot of " + ChunkCatchUp.SLICE_TICKS
                            + " = " + bound + ", over " + offered[i] + " machines offered ("
                            + placed + " placed, " + INTENDED + " intended)");
                    return;
                }
            }
            helper.succeed();
        }
    }
}
