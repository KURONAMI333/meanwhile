package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.guard.CatchUpGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A block entity that throws while it is being caught up is dropped from catch-up, and not
 * before the third time.
 *
 * <h3>The subject</h3>
 * <p>{@link Thrower} is a block entity carrying {@code minecraft:furnace}'s type while being
 * nothing of the kind. The block above it is a furnace, so the block's own ticker is handed out
 * exactly as it would be for a real one, and running it casts the block entity to
 * {@code AbstractFurnaceBlockEntity} and throws. That is a faithful stand-in for the failure this
 * guard exists for: a ticker that is fine on the route the game uses and not on the route this
 * mod added.
 *
 * <p>It is put into the chunk with {@link LevelChunk#setBlockEntity}, which does not register a
 * ticker, rather than {@code Level#setBlockEntity}, which does. Registering one would have
 * vanilla's own ticking loop run it, and vanilla's loop turns a throwing block entity into a
 * crash report — a real failure, but not this one, and it would end the run before the catch-up
 * ever reached the subject.
 *
 * <h3>The window</h3>
 * <p>The chunk is made to look as though it had been away for {@link #GAP} ticks, which is six
 * slices of the catch-up's {@code SLICE_TICKS}, so the subject is offered six windows. The first
 * three throw. Whether the fourth, fifth and sixth throw as well is the whole question:
 * {@link #isolatedOnTheThirdException} says they must not, and
 * {@link #withoutTheThresholdItIsOfferedEveryWindow} says that with the threshold taken away they
 * do — which is what makes the first a measurement of the threshold rather than of the subject.
 *
 * <p>No {@code @GameTestHolder}: registered from {@link Meanwhile}, like the other vanilla gates.
 */
public final class CatchUpGuardGameTests {

    private static final BlockPos SUBJECT = new BlockPos(3, 1, 3);

    /** Six slices of {@code ChunkCatchUp.SLICE_TICKS}, so there are windows left after the third. */
    private static final int GAP = 6000;

    private static final int EXPECTED_SLICES = 6;

    /** Ticks to let the arena settle before anything is recorded. */
    private static final int SETTLE = 3;

    /** Ticks to wait for six slices, which take one level tick each. */
    private static final int WATCH = 60;

    private CatchUpGuardGameTests() {
    }

    /**
     * The threshold as it ships. Three exceptions inside the window and the subject is out.
     *
     * <p>Both sides are asserted. That the third isolates is half the claim; that the first and
     * second do not is the other half, and without it the same log would be produced by a guard
     * that isolates on the first exception.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "catchupguard", timeoutTicks = 400)
    public static void isolatedOnTheThirdException(GameTestHelper helper) {
        run(helper, CatchUpGuard.THRESHOLD, true);
    }

    /**
     * Negative control: the same subject, throwing the same exception, with the threshold put out
     * of reach.
     *
     * <p>It has to keep being offered a window and keep throwing for all six slices. If it
     * stopped anyway, whatever stopped it in the paired test was not the threshold.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "catchupguardnc", timeoutTicks = 400)
    public static void withoutTheThresholdItIsOfferedEveryWindow(GameTestHelper helper) {
        run(helper, Integer.MAX_VALUE, false);
    }

    private static void run(GameTestHelper helper, int threshold, boolean expectIsolation) {
        Drive drive = new Drive(helper, threshold, expectIsolation);
        helper.startSequence()
                .thenExecuteFor(SETTLE + WATCH, drive::step)
                .thenExecute(drive::judge)
                .thenSucceed();
    }

    /** One reading of the guard, taken once a tick. */
    private record Sample(long gameTime, int exceptions, int isolations, int dispatches) {
    }

    private static final class Drive {

        private enum Step { SETTLING, OFFSET, ARM, WATCHING, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos pos;
        private final ChunkPos chunk;
        private final int threshold;
        private final boolean expectIsolation;
        private final List<Sample> samples = new ArrayList<>();

        private Step step = Step.SETTLING;
        private int countdown = SETTLE;
        private int dispatchesAtStart;

        private Drive(GameTestHelper helper, int threshold, boolean expectIsolation) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(SUBJECT);
            this.chunk = new ChunkPos(pos);
            this.threshold = threshold;
            this.expectIsolation = expectIsolation;
        }

        private void step() {
            switch (step) {
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    arm();
                    step = Step.OFFSET;
                }
                case OFFSET -> {
                    // One tick with the offset in force, so the chunk carries a stale stamp.
                    step = Step.ARM;
                }
                case ARM -> {
                    ChunkClock.setStampOffset(chunk, 0L);
                    ChunkClock.rearm(level, chunk);
                    step = Step.WATCHING;
                }
                case WATCHING -> samples.add(new Sample(level.getGameTime(),
                        CatchUpGuard.exceptions(), CatchUpGuard.isolations(),
                        ChunkCatchUp.dispatches() - dispatchesAtStart));
                case DONE -> {
                }
            }
        }

        /** Put the subject in the chunk and make the chunk look as though it had been away. */
        private void arm() {
            helper.setBlock(SUBJECT, Blocks.FURNACE);
            BlockState state = level.getBlockState(pos);
            LevelChunk holder = level.getChunkAt(pos);
            holder.setBlockEntity(new Thrower(pos, state));

            CatchUpGuard.reset();
            CatchUpGuard.setThreshold(threshold);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.restrictedTo(pos));
            dispatchesAtStart = ChunkCatchUp.dispatches();
            ChunkClock.setStampOffset(chunk, -GAP);
            Meanwhile.LOGGER.info("[guardgate] armed | pos={} chunk={} gap={} threshold={}"
                            + " blockEntity={}",
                    pos.toShortString(), chunk, GAP, threshold,
                    level.getBlockEntity(pos) == null ? "<none>"
                            : level.getBlockEntity(pos).getClass().getSimpleName());
        }

        private void judge() {
            step = Step.DONE;
            int exceptions = CatchUpGuard.exceptions();
            int isolations = CatchUpGuard.isolations();
            boolean isolated = CatchUpGuard.isIsolated(level.dimension(), pos);
            int slices = ChunkCatchUp.dispatches() - dispatchesAtStart;

            // The reading that decides the claim: how many exceptions had been counted at the
            // moment the first isolation appeared. Taken from the samples rather than from the
            // totals, because the totals cannot tell three-then-stop from stop-then-three.
            int isolatedAtException = -1;
            int exceptionsBeforeAnyIsolation = 0;
            for (Sample sample : samples) {
                if (sample.isolations() == 0) {
                    exceptionsBeforeAnyIsolation =
                            Math.max(exceptionsBeforeAnyIsolation, sample.exceptions());
                } else if (isolatedAtException < 0) {
                    isolatedAtException = sample.exceptions();
                }
            }

            Meanwhile.LOGGER.info("[guardgate] RESULT threshold={} | exceptions={} isolations={}"
                            + " isolated={} slices={} isolatedAtException={}"
                            + " maxExceptionsWhileNotIsolated={} drainFailures={}",
                    threshold, exceptions, isolations, isolated, slices, isolatedAtException,
                    exceptionsBeforeAnyIsolation, ChunkCatchUp.drainFailures());
            Meanwhile.LOGGER.info("[guardgate] TRACE threshold={} | {}", threshold, samples);

            try {
                if (slices < EXPECTED_SLICES) {
                    helper.fail("the chunk was offered " + slices + " windows, not "
                            + EXPECTED_SLICES + "; nothing about the threshold can be read off a"
                            + " run that stopped before reaching it");
                    return;
                }
                if (expectIsolation) {
                    judgeIsolating(exceptions, isolations, isolated, isolatedAtException,
                            exceptionsBeforeAnyIsolation);
                } else {
                    judgeControl(exceptions, isolations, isolated, slices);
                }
            } finally {
                restore();
            }
        }

        private void judgeIsolating(int exceptions, int isolations, boolean isolated,
                                    int isolatedAtException, int exceptionsBeforeAnyIsolation) {
            if (!isolated || isolations != 1) {
                helper.fail("a block entity that threw on every window it was offered was not"
                        + " dropped from catch-up: isolations=" + isolations + " isolated="
                        + isolated);
                return;
            }
            if (exceptionsBeforeAnyIsolation != CatchUpGuard.THRESHOLD - 1) {
                helper.fail("the first and second exceptions did not leave the subject in"
                        + " catch-up: the largest exception count seen with nothing isolated was "
                        + exceptionsBeforeAnyIsolation + ", expected "
                        + (CatchUpGuard.THRESHOLD - 1));
                return;
            }
            if (isolatedAtException != CatchUpGuard.THRESHOLD) {
                helper.fail("isolation did not happen on exception "
                        + CatchUpGuard.THRESHOLD + " but on " + isolatedAtException);
                return;
            }
            if (exceptions != CatchUpGuard.THRESHOLD) {
                helper.fail("the subject went on throwing after it was isolated, so it was still"
                        + " being caught up: exceptions=" + exceptions + " over "
                        + EXPECTED_SLICES + " windows");
                return;
            }
            // An isolation is permanent and keyed on a position, and the only thing that removes
            // one is the chunk going away. Asserted here, where a live isolation exists to be
            // removed: without it the set grows for as long as the process runs, and on an
            // integrated server it decides the fate of a machine at the same coordinates of the
            // next world. Only the guard's own half is exercised from here; that a chunk unload
            // reaches it is the wiring in ChunkCatchUp's forgetter.
            CatchUpGuard.forgetChunk(level.dimension(), chunk.toLong());
            if (CatchUpGuard.isIsolated(level.dimension(), pos)) {
                helper.fail("the chunk " + chunk + " holding the isolated " + pos.toShortString()
                        + " was forgotten and the isolation stayed behind, so nothing ever"
                        + " removes one");
                return;
            }
            helper.succeed();
        }

        private void judgeControl(int exceptions, int isolations, boolean isolated, int slices) {
            if (isolations != 0 || isolated) {
                helper.fail("the threshold was put out of reach and the subject was isolated"
                        + " anyway, so the paired test measures something else: isolations="
                        + isolations);
                return;
            }
            if (exceptions < slices) {
                helper.fail("with the threshold out of reach the subject should have thrown on"
                        + " every window: exceptions=" + exceptions + " windows=" + slices);
                return;
            }
            helper.succeed();
        }

        private void restore() {
            ChunkClock.setStampOffset(chunk, 0L);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            CatchUpTestAccess.forget(helper, level);
            CatchUpGuard.reset();
            helper.setBlock(SUBJECT, Blocks.AIR);
        }
    }

    /**
     * Carries {@code minecraft:furnace}'s block entity type and none of its class, so the
     * furnace's own ticker throws the moment it is handed this.
     */
    private static final class Thrower extends BlockEntity {

        private Thrower(BlockPos pos, BlockState state) {
            super(BlockEntityType.FURNACE, pos, state);
        }
    }
}
