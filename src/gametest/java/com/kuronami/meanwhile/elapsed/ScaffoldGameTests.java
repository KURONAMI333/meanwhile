package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import java.util.List;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The window a chunk owes is spent the same way whether or not anything is watching.
 *
 * <p>Most of what a slice used to cost was not the catch-up. Every block entity was serialised
 * before and after, its block named through the registry, its class named, and the ticker the
 * game would have handed out resolved a second time — all of it to fill in an
 * {@code Attempt} that the product never reads. That is now conditional on an
 * {@code Observer} being installed, and this is the measurement that says taking it away does not
 * change the machine that comes out.
 *
 * <p>Both arms are the same furnace, rebuilt from nothing, given the same fuel, and offered the
 * same window through the same reconciliation. The only difference is whether an observer is
 * installed. What is compared is the furnace's own tag and block state afterwards, read by this
 * test rather than handed to it by the observer — the observer is the thing under test and cannot
 * also be the instrument.
 *
 * <h3>Why the furnace is put into the chunk by hand</h3>
 * <p>{@link LevelChunk#setBlockEntity} does not register a ticker;
 * {@code LevelChunk#addAndRegisterBlockEntity}, which placing a block goes through, does. A
 * furnace installed the first way is invisible to the game's own ticking loop and visible to the
 * catch-up, which resolves the ticker itself. That takes the vanilla ticks that fall between
 * arming and the last slice out of the comparison, so a difference between the arms can only be
 * the catch-up.
 *
 * <h3>Why the instalment count is a figure this file decides</h3>
 * <p>Every number this gate writes down is read <b>per chunk</b> and over a window and an
 * instalment this test sets: {@code slices = ceil(WINDOW / SLICE)}, numerator from
 * {@link ChunkCatchUp.Mode#withFixedWindow}, denominator from {@link ChunkCatchUp#setBudget}.
 * Nothing in that expression names the worklist, so the value cannot move because another gate
 * left something queued.
 *
 * <p>It used to. The count came off {@link ChunkCatchUp#dispatches()}, which every job in the run
 * bumps, and it read 3 only because this gate emptied the global worklist before each arm — which
 * is the cross-gate corruption {@link ChunkCatchUp#forget} now refuses (GAP_LOG G156, G158). Stop
 * emptying it and the same line reads 3, 18 or 6 by the run while every assertion stays green.
 * A frozen observation whose value is a property of how crowded the queue was is the class of
 * quantity ruling 6 kept out of required assertions, and it does not belong in a baseline either.
 *
 * <p>No {@code @GameTestHolder}: registered from {@link Meanwhile}, like the other vanilla gates.
 */
public final class ScaffoldGameTests {

    private static final BlockPos SUBJECT = new BlockPos(3, 1, 3);

    /**
     * The instalment this test pays its window off in, set here rather than inherited.
     *
     * <p>{@code sliceTicks} is global mutable state — {@link ChunkCatchUp#setBudget} writes it and
     * several gates do — so a test that reads an instalment count without setting the divisor is
     * reading whatever the gate before it left behind. This is the product's own value, so the
     * figure is the product's; what setting it buys is that the figure is a function of this
     * file rather than of the suite's order.
     */
    private static final int SLICE = ChunkCatchUp.SLICE_TICKS;

    /** Three slices of {@link #SLICE}, and several smelts. */
    private static final int WINDOW = 3 * SLICE;

    /** Ticks the fake clock adds per reading. See {@link #FAKE_BUDGET}. */
    private static final long CLOCK_STEP = 250_000L;

    /**
     * The time budget the drain is measured against while this gate runs, in the units of the
     * fake clock rather than of the host.
     *
     * <p>The same shape {@code CrowdedChunkGameTests} uses, and for the same reason: how much a
     * level tick gets through must not be a property of how fast this machine is. Sixteen steps
     * rather than four, because unlike that gate this one is not measuring the stopping point —
     * it needs its own chunk reached every level tick even when the suite's standing backlog is
     * ahead of it in the queue, and under {@link ChunkCatchUp.Mode#restrictedTo} a backlog job
     * walks no block entities and costs one clock reading.
     */
    private static final long FAKE_BUDGET = CLOCK_STEP * 16L;

    /** Enough to be behind at all; the window itself is fixed rather than taken from this. */
    private static final int STALE_BY = 100;

    private static final int SETTLE = 3;
    private static final int WATCH = 120;

    private static final int INPUT_COUNT = 64;
    private static final int FUEL_COUNT = 64;

    private ScaffoldGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "scaffold", timeoutTicks = 600)
    public static void theWindowIsSpentTheSameWayWithNothingWatching(GameTestHelper helper) {
        Drive drive = new Drive(helper);
        helper.startSequence()
                .thenExecuteFor(SETTLE + WATCH, drive::step)
                .thenExecute(drive::judge)
                .thenSucceed();
    }

    /** What one arm ended at. */
    private record Arm(String name, @Nullable CompoundTag tag, String state, long paid,
                       int slices) {
    }

    private static final class Drive {

        private enum Step { SETTLING, BUILD, OFFSET, ARM, PAYING, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos pos;
        private final ChunkPos chunk;
        private final Arm[] arms = new Arm[2];

        private Step step = Step.SETTLING;
        private int countdown = SETTLE;
        private int index;
        private long paidAtArmStart;
        private int waited;
        @Nullable
        private String failure;

        private Drive(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(SUBJECT);
            this.chunk = new ChunkPos(pos);
        }

        private void step() {
            switch (step) {
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    step = Step.BUILD;
                }
                case BUILD -> {
                    build();
                    step = Step.OFFSET;
                }
                case OFFSET -> step = Step.ARM;
                case ARM -> {
                    ChunkClock.setStampOffset(chunk, 0L);
                    ChunkClock.rearm(level, chunk);
                    waited = 0;
                    step = Step.PAYING;
                }
                case PAYING -> pay();
                case DONE -> {
                }
            }
        }

        /** A furnace the game will not tick, loaded the same way for both arms. */
        private void build() {
            helper.setBlock(SUBJECT, Blocks.AIR);
            helper.setBlock(SUBJECT, Blocks.FURNACE);
            BlockState state = level.getBlockState(pos);
            LevelChunk holder = level.getChunkAt(pos);
            holder.setBlockEntity(new FurnaceBlockEntity(pos, state));

            if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                failure = "arm " + index + ": the hand-placed furnace is not there";
                step = Step.DONE;
                return;
            }
            furnace.litTime = 0;
            furnace.litDuration = 0;
            furnace.cookingProgress = 0;
            furnace.cookingTotalTime = 0;
            furnace.recipesUsed.clear();
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setItem(1, new ItemStack(Items.COAL, FUEL_COUNT));
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));
            furnace.setChanged();

            // Cold both times: the first furnace in a world has to watch a counter turn over
            // before it may jump one, and a warm table would make the second arm a different
            // measurement.
            GenericCatchUp.forgetPeaks();
            ChunkCatchUp.setObserver(index == 0 ? new Silent() : null);
            // Both axes of the drain pinned to this test's own configuration: the instalment by
            // setBudget, the level tick's stopping point by a clock this test drives. setBudget
            // puts the time budget out of the way for callers driving the work axis, so the
            // order matters and is the one CrowdedChunkGameTests uses.
            ChunkCatchUp.setBudget(SLICE, ChunkCatchUp.BUDGET_REAL_TICKS);
            ChunkCatchUp.setBudgetNanos(FAKE_BUDGET, new SteppingClock());
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.restrictedTo(pos)
                    .withFixedWindow(WINDOW));
            paidAtArmStart = ChunkCatchUp.paidFor(level, chunk);
            ChunkClock.setStampOffset(chunk, -STALE_BY);
            Meanwhile.LOGGER.info("[scaffold] armed | arm={} observer={} pos={} window={}"
                            + " startTag={}",
                    index, index == 0 ? "installed" : "none", pos.toShortString(), WINDOW,
                    tag());
        }

        private void pay() {
            if (++waited > WATCH - 10) {
                failure = "arm " + index + ": the window was never paid off";
                step = Step.DONE;
                return;
            }
            // Both readings are of this chunk. ChunkCatchUp.dispatches() is a run-wide counter --
            // every job in the worklist bumps it, whoever owns the chunk -- so a delta taken off
            // it measures how crowded the suite's queue was during this window and not what this
            // arm did. It stood in for "has this arm's own work started yet?" and for the
            // instalment count, and it was wrong for both: with another gate's chunk in the
            // queue it moves on the first level tick, which would let this loop record an arm
            // that had been paid nothing (GAP_LOG G158).
            long paid = ChunkCatchUp.paidFor(level, chunk) - paidAtArmStart;
            if (paid < WINDOW) {
                return;
            }
            if (ChunkCatchUp.debtFor(level, chunk) != 0L) {
                return;
            }
            arms[index] = new Arm(index == 0 ? "observer installed" : "observer null", tag(),
                    String.valueOf(level.getBlockState(pos)), paid,
                    ChunkCatchUp.slicesFor(level, chunk));
            Meanwhile.LOGGER.info("[scaffold] arm | {} | slices={} paid={} state={} tag={}",
                    arms[index].name(), arms[index].slices(), arms[index].paid(),
                    arms[index].state(), arms[index].tag());
            if (++index >= arms.length) {
                step = Step.DONE;
                return;
            }
            step = Step.BUILD;
        }

        @Nullable
        private CompoundTag tag() {
            HolderLookup.Provider registries = level.registryAccess();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            return blockEntity == null ? null : blockEntity.saveWithoutMetadata(registries);
        }

        private void judge() {
            restore();
            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (arms[0] == null || arms[1] == null) {
                helper.fail("an arm never finished: " + arms[0] + " / " + arms[1]);
                return;
            }
            Arm watched = arms[0];
            Arm unwatched = arms[1];
            boolean same = watched.state().equals(unwatched.state())
                    && watched.tag() != null && watched.tag().equals(unwatched.tag());
            Meanwhile.LOGGER.info("[scaffold] GATE | window={} match={} | watched slices={}"
                            + " paid={} | unwatched slices={} paid={}",
                    WINDOW, same, watched.slices(), watched.paid(), unwatched.slices(),
                    unwatched.paid());
            Meanwhile.LOGGER.info("[scaffold] RESULT | watched   state={} tag={}",
                    watched.state(), watched.tag());
            Meanwhile.LOGGER.info("[scaffold] RESULT | unwatched state={} tag={}",
                    unwatched.state(), unwatched.tag());

            if (watched.paid() != WINDOW || unwatched.paid() != WINDOW) {
                helper.fail("the two arms did not spend the same window: watched paid "
                        + watched.paid() + ", unwatched paid " + unwatched.paid() + " of "
                        + WINDOW);
                return;
            }
            if (!same) {
                helper.fail("the furnace the catch-up produced with nothing watching is not the"
                        + " one it produced with an observer installed: watched=" + watched.state()
                        + " " + watched.tag() + " unwatched=" + unwatched.state() + " "
                        + unwatched.tag());
                return;
            }
            helper.succeed();
        }

        private void restore() {
            step = Step.DONE;
            ChunkClock.setStampOffset(chunk, 0L);
            ChunkCatchUp.setObserver(null);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.restoreBudget();
            ChunkCatchUp.forget(level);
            helper.setBlock(SUBJECT, Blocks.AIR);
        }
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

    /**
     * An observer that reads nothing and asserts nothing.
     *
     * <p>Its only job is to be installed, because being installed is what turns the recording
     * back on. An observer that took a reading of its own would put that reading's cost and its
     * side effects into the arm it is supposed to be a control for.
     */
    private static final class Silent implements ChunkCatchUp.Observer {

        @Override
        public void beforeSweep(ServerLevel level, LevelChunk chunk, int dispatched,
                                List<BlockPos> positions) {
        }

        @Override
        public void afterSweep(ServerLevel level, LevelChunk chunk, ChunkCatchUp.Sweep sweep) {
        }
    }
}
