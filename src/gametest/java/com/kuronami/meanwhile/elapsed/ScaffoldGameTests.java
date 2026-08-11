package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import java.util.List;
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
 * <p>No {@code @GameTestHolder}: registered from {@link Meanwhile}, like the other vanilla gates.
 */
public final class ScaffoldGameTests {

    private static final BlockPos SUBJECT = new BlockPos(3, 1, 3);

    /** Three slices of {@code ChunkCatchUp.SLICE_TICKS}, and several smelts. */
    private static final int WINDOW = 3000;

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
        private int dispatchesAtArmStart;
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
            // No ChunkCatchUp.forget here. It empties the global worklist, so an arm built while
            // another gate still had jobs queued destroyed that work -- measured at 3 queued and
            // 3 pending in 2 of 5 runs on unchanged code (GAP_LOG G157). Nothing was lost by
            // dropping it: what this arm reads is paid/dispatches as a delta from the two
            // snapshots taken immediately below, so another chunk's leftover debt cannot enter
            // the measurement. restore() still clears up after the test, where the queue is
            // this gate's own and empty.
            ChunkCatchUp.setObserver(index == 0 ? new Silent() : null);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.restrictedTo(pos)
                    .withFixedWindow(WINDOW));
            paidAtArmStart = ChunkCatchUp.paidFor(level, chunk);
            dispatchesAtArmStart = ChunkCatchUp.dispatches();
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
            if (ChunkCatchUp.dispatches() == dispatchesAtArmStart) {
                return;
            }
            if (ChunkCatchUp.debtFor(level, chunk) != 0L) {
                return;
            }
            arms[index] = new Arm(index == 0 ? "observer installed" : "observer null", tag(),
                    String.valueOf(level.getBlockState(pos)),
                    ChunkCatchUp.paidFor(level, chunk) - paidAtArmStart,
                    ChunkCatchUp.dispatches() - dispatchesAtArmStart);
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
            ChunkCatchUp.forget(level);
            helper.setBlock(SUBJECT, Blocks.AIR);
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
