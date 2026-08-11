package com.kuronami.meanwhile.compat;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.elapsed.ChunkCatchUp;
import com.kuronami.meanwhile.elapsed.CatchUpTestAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * A furnace is declined when another mod is catching furnaces up, and caught up when none is.
 *
 * <h3>What is under test, and what is not</h3>
 *
 * <p>The seam is {@link CompatibilityCoordinator}'s flag, and the flag is what these two tests
 * set. <b>Unloaded Activity is not installed in this run.</b> What is shown is that the flag
 * decides the behaviour — that a furnace on the product's own walk is left untouched when it is
 * set and advanced when it is not. What is <em>not</em> shown is that the other mod's presence
 * sets the flag: that is the single {@code ModList.isLoaded} line in
 * {@link CompatibilityCoordinator#onServerStarting}, and putting a second mod into the test run
 * to exercise it would be a measurement of NeoForge's mod list rather than of this mod.
 *
 * <h3>Both halves are asserted, on two surfaces</h3>
 *
 * <p>The bookkeeping surface is the {@link ChunkCatchUp.Attempt} the drain records: declined with
 * a named reason and no real ticks, against not declined with a jump. The behavioural surface is
 * the furnace itself — whether iron came out of it. The second is the one that would still be
 * right if the bookkeeping lied: a deferral that recorded a decline and advanced the machine
 * anyway would pass the first assertion and fail this one.
 *
 * <p>The window is {@link #GAP} ticks, ten smelts at two hundred ticks each. The deferred arm is
 * watched for {@link #WATCH} ticks afterwards, which is less than one smelt, so a furnace that
 * was genuinely left alone cannot have finished anything — while the same furnace handed the
 * window produces several ingots. That gap between the two arms is what makes "nothing came out"
 * a measurement rather than an artefact of not waiting long enough.
 *
 * <p>No {@code @GameTestHolder}: registered from {@code MeanwhileGates}, like the other gates.
 */
public final class FurnaceDeferralGameTests {

    private static final BlockPos FURNACE = new BlockPos(3, 1, 3);

    /** Two slices of {@code ChunkCatchUp.SLICE_TICKS}, and ten smelts at 200 ticks each. */
    private static final int GAP = 2000;

    /** Ticks to let the arena settle before the chunk is made to look absent. */
    private static final int SETTLE = 3;

    /**
     * Ticks watched after the window is handed over.
     *
     * <p>Long enough for both slices to be drained, an instalment being one level tick, and short
     * enough that a furnace nobody caught up cannot have finished a smelt on its own.
     */
    private static final int WATCH = 60;

    private static final int INPUT_COUNT = 64;
    private static final int FUEL_COUNT = 64;

    /**
     * Ingots a caught-up furnace has to have produced.
     *
     * <p>Ten smelts fit in the window. Asserting two rather than ten keeps the test about "the
     * window was spent on it" instead of about how much of the window a jump is currently able
     * to skip, which is {@code FurnaceSpanGameTests}' subject and moves when that improves.
     */
    private static final int CAUGHT_UP_AT_LEAST = 2;

    private FurnaceDeferralGameTests() {
    }

    /**
     * The ordinary case: nothing overlapping is installed, so the furnace is this mod's job.
     *
     * <p>This is the arm that would go green on its own if the deferral did nothing at all, which
     * is why its pair is the measurement and this one is the control that gives the pair meaning.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "compatdeferoff", timeoutTicks = 600)
    public static void withoutTheOtherModAFurnaceIsCaughtUp(GameTestHelper helper) {
        run(helper, false);
    }

    /** The deferral: with the flag set, the same furnace is walked past untouched. */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "compatdeferon", timeoutTicks = 600)
    public static void withTheOtherModAFurnaceIsDeclined(GameTestHelper helper) {
        run(helper, true);
    }

    private static void run(GameTestHelper helper, boolean defer) {
        Drive drive = new Drive(helper, defer);
        helper.startSequence()
                .thenExecuteFor(SETTLE + WATCH, drive::step)
                .thenExecute(drive::judge)
                .thenSucceed();
    }

    private static final class Drive {

        private enum Step { SETTLING, OFFSET, REARM, WATCHING, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos pos;
        private final ChunkPos chunk;
        private final boolean defer;

        private Step step = Step.SETTLING;
        private int countdown = SETTLE;
        private int dispatchesAtStart;
        private boolean deferralBefore;

        private Drive(GameTestHelper helper, boolean defer) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(FURNACE);
            this.chunk = new ChunkPos(pos);
            this.defer = defer;
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
                    // One tick with the offset in force, so that the chunk's stamp is actually
                    // written the window into the past. The offset biases what the clock writes
                    // down, not what it reads back.
                    step = Step.REARM;
                }
                case REARM -> {
                    // The clock reconciles a chunk once and latches it, so a chunk that never
                    // left is never behind however old its stamp is. This reproduces the arrival
                    // with the arena intact, which is what makes the stale stamp count.
                    CatchUpTestAccess.setStampOffset(chunk, 0L);
                    CatchUpTestAccess.rearm(level, chunk);
                    step = Step.WATCHING;
                }
                case WATCHING, DONE -> {
                }
            }
        }

        /** Build the furnace, light it, and make the chunk look as though it had been away. */
        private void arm() {
            helper.setBlock(FURNACE, Blocks.FURNACE);
            load();

            // Set before the window is handed over, and the only thing that differs between the
            // two arms. Everything below this line is identical in both. What it was is kept, so
            // that leaving puts it back rather than forcing it off: a run where the other mod is
            // genuinely installed must not have its deferral switched off for good by this gate
            // happening to run early.
            deferralBefore = CompatibilityCoordinator.defersFurnaces();
            CompatibilityCoordinator.setDeferringFurnaces(defer);

            // The arenas of other tests stand in this chunk and hand out windows of millions of
            // ticks, which are still being paid off in instalments long after the batch that
            // asked for them has finished. A window queued behind one of those settles far too
            // late to be read here, and what `lastSweep` hands back in the meantime is the other
            // test's — measured, not supposed: the first run of this gate read a sweep of
            // `elapsed=120000` with no attempts in it (`ucu_g155_idle1.log`, chunk
            // [-147949, 383034]).
            CatchUpTestAccess.setMode(ChunkCatchUp.Mode.PRODUCT.restrictedTo(pos));
            dispatchesAtStart = ChunkCatchUp.dispatches();
            CatchUpTestAccess.setStampOffset(chunk, -GAP);
            Meanwhile.LOGGER.info("[compat] armed | pos={} chunk={} gap={} deferring={}",
                    pos.toShortString(), chunk, GAP, defer);
        }

        private void judge() {
            step = Step.DONE;
            try {
                assess();
            } finally {
                // In a finally because a flag left set makes every furnace in whatever runs next
                // decline, which would come back as a wall of unrelated red.
                restore();
            }
        }

        private void assess() {
            if (ChunkCatchUp.dispatches() == dispatchesAtStart) {
                helper.fail("the chunk was never offered to the catch-up, so neither arm"
                        + " measured anything");
                return;
            }
            ChunkCatchUp.Sweep sweep = ChunkCatchUp.lastSweep(level, chunk);
            if (sweep == null) {
                helper.fail("no sweep on this chunk settled inside the watch");
                return;
            }
            // The sweep this chunk holds is not necessarily the one this arm handed over: other
            // arenas share the chunk and settle their own. A sweep whose window is not the one
            // that was set up is somebody else's, and reading it would let this gate pass on
            // evidence about a furnace it never built.
            if (sweep.elapsed() < GAP || sweep.elapsed() > GAP + SETTLE + WATCH) {
                helper.fail("the settled sweep is for a window of " + sweep.elapsed()
                        + " ticks, not the " + GAP + " this arm handed over; it belongs to"
                        + " another arena and says nothing about the deferral");
                return;
            }
            ChunkCatchUp.Attempt attempt = null;
            for (ChunkCatchUp.Attempt each : sweep.attempts()) {
                if (each.pos().equals(pos)) {
                    attempt = each;
                }
            }
            if (attempt == null) {
                helper.fail("the sweep did not reach the furnace this arm built: "
                        + sweep.attempts());
                return;
            }
            int produced = producedCount();
            Meanwhile.LOGGER.info("[compat] RESULT deferring={} | declined={} reason={}"
                            + " realTicks={} jumps={} jumpedTicks={} produced={} dispatched={}",
                    defer, attempt.declined(), attempt.declineReason(), attempt.realTicks(),
                    attempt.jumps(), attempt.jumpedTicks(), produced, sweep.dispatched());

            if (defer) {
                assessDeferred(attempt, produced);
            } else {
                assessCaughtUp(attempt, produced);
            }
        }

        private void assessDeferred(ChunkCatchUp.Attempt attempt, int produced) {
            if (!attempt.declined()) {
                helper.fail("a furnace was caught up while the deferral was in force: "
                        + attempt.summary());
                return;
            }
            String reason = attempt.declineReason();
            if (reason == null || !reason.contains(CompatibilityCoordinator.UNLOADED_ACTIVITY)) {
                helper.fail("the furnace declined for some other reason than the deferral,"
                        + " so this run says nothing about the deferral: " + reason);
                return;
            }
            if (attempt.realTicks() != 0 || attempt.jumps() != 0) {
                helper.fail("declining cost the furnace ticks, so it was not walked past:"
                        + " realTicks=" + attempt.realTicks() + " jumps=" + attempt.jumps());
                return;
            }
            if (produced != 0) {
                helper.fail("the deferred furnace smelted " + produced + " item(s), so"
                        + " something advanced it through the window the other mod owns");
                return;
            }
            helper.succeed();
        }

        private void assessCaughtUp(ChunkCatchUp.Attempt attempt, int produced) {
            if (attempt.declined()) {
                helper.fail("the furnace was declined with nothing overlapping installed: "
                        + attempt.summary());
                return;
            }
            if (attempt.jumps() == 0) {
                helper.fail("no part of the window was skipped, so this arm is not the"
                        + " behaviour its pair is compared against: " + attempt.summary());
                return;
            }
            if (produced < CAUGHT_UP_AT_LEAST) {
                helper.fail("the caught-up furnace produced " + produced + " item(s), fewer"
                        + " than the " + CAUGHT_UP_AT_LEAST + " a spent window has to leave;"
                        + " the deferred arm's zero would then show nothing");
                return;
            }
            helper.succeed();
        }

        /** What the furnace has finished smelting. Zero unless somebody spent its window. */
        private int producedCount() {
            AbstractFurnaceBlockEntity furnace = furnace();
            return furnace == null ? -1 : furnace.getItem(2).getCount();
        }

        private void load() {
            AbstractFurnaceBlockEntity furnace = furnace();
            if (furnace == null) {
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
        }

        @Nullable
        private AbstractFurnaceBlockEntity furnace() {
            return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity found
                    ? found : null;
        }

        private void restore() {
            CompatibilityCoordinator.setDeferringFurnaces(deferralBefore);
            CatchUpTestAccess.setStampOffset(chunk, 0L);
            CatchUpTestAccess.setMode(ChunkCatchUp.Mode.PRODUCT);
            CatchUpTestAccess.forget(level);
            helper.setBlock(FURNACE, Blocks.AIR);
        }
    }
}
