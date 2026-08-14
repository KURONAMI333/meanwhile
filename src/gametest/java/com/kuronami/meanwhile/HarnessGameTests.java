package com.kuronami.meanwhile;

import com.kuronami.meanwhile.catchup.CropCatchUp;
import com.kuronami.meanwhile.elapsed.CatchUpTestAccess;
import com.kuronami.meanwhile.elapsed.ChunkCatchUp;
import com.kuronami.meanwhile.harness.CatchUpSubject;
import com.kuronami.meanwhile.harness.DifferentialHarness;
import com.kuronami.meanwhile.harness.DifferentialHarness.Effort;
import com.kuronami.meanwhile.harness.Disturbance;
import com.kuronami.meanwhile.harness.Verdict;
import com.kuronami.meanwhile.subject.CropSubject;
import com.kuronami.meanwhile.subject.FurnaceSubject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Function;

/**
 * Verification suite for skipped-tick catch-up.
 *
 * <p>Three things are established here, in order. That a clean window can be skipped at
 * all. That the cost of skipping follows the work done rather than the time elapsed, which
 * is the entire reason to do this. And that a window which is interrupted partway is either
 * handled correctly or is loudly wrong, never quietly wrong.
 *
 * <p>Every claim is paired with a run that must fail. A comparison never observed to reject
 * anything says nothing when it accepts, and "green while proving nothing" is the failure
 * mode that lets a scheduler ship convincing benchmarks over silently corrupted worlds.
 */
@GameTestHolder(Meanwhile.MODID)
public class HarnessGameTests {

    private static final int CROP_TRIALS = 3000;
    private static final int CROP_TICKS = 25;
    private static final int CROP_SPLIT_AT = 12;
    private static final double CROP_TOLERANCE = 0.20D;
    /**
     * Fewer trials for the disturbance comparisons. Their effects are large — shading a
     * plot stops growth outright — so the power to separate them is not the binding
     * constraint, while their resets rebuild blocks and rerun the light engine.
     */
    private static final int CROP_DISTURBANCE_TRIALS = 600;
    /** Independent seed pairs each stochastic comparison is repeated over. */
    private static final int SEED_PAIRS = 5;

    /** Long enough to cover lighting, several smelts, input running out, and going cold. */
    private static final int FURNACE_TICKS = 3000;
    private static final int FURNACE_SPLIT_AT = 1500;
    /** Mid-smelt, so the comparator has a partly filled output slot to report. */
    private static final int FURNACE_READ_AT = 1000;
    /**
     * After the starting load is exhausted, around tick 1600, so the interruption lands
     * while the furnace has gone quiet.
     *
     * <p>An earlier refill proved nothing. A furnace that never runs dry smelts the same
     * number of items in 3000 ticks whichever moment the extra ore arrives, so both arms
     * agreed and the comparison could not distinguish a scheduler that was told about the
     * interruption from one that was not. The interruption has to land where the catch-up
     * is actually taking a shortcut, which for a furnace is the stretch it has written off
     * as never changing again.
     */
    private static final int FURNACE_DISTURB_AT = 2200;
    /** Roughly two and a half real-time weeks of a furnace nobody visited. */
    private static final int LONG_WINDOW_TICKS = 1_200_000;

    /**
     * The most {@link #catchUpLeavesTheFurnaceTickableByTheGame} will accept as the work of
     * 400 real ticks, which is what makes the growth it reads attributable to the game's own
     * ticking rather than merely observed.
     *
     * <p>A furnace smelts one item per 200 ticks, so the game's dispatch can produce 2 across
     * that window and no more. A catch-up instalment is {@code SLICE_TICKS} = 1000 ticks wide
     * and, landing on the same furnace, produces far more in one go: the reading that exposed
     * this was 8.0 -> 16.0. Between the two the assertion has to sit somewhere it can tell
     * them apart, and the campaign's own logs say where. Of 601 recorded readings of this
     * gate, 592 grew by 2, one by 3, four by 8 (the defect), and four by 0 (the gate failing
     * as it should). The ceiling is above every legitimate reading ever taken and half of the
     * smallest illegitimate one.
     *
     * <p><b>Without it the gate passes while proving nothing.</b> Its only assertion was that
     * the output grew, and a catch-up jumping the furnace grows it with the game's dispatch
     * completely broken (GAP_LOG G163, G164 ruling 42). Dropping the arena's own debt before
     * the window stops that happening; this is what makes it say so when it happens anyway.
     */
    private static final double TICKED_GROWTH_CEILING = 4.0D;

    private static final long SEED_SIMULATED = 0x5EED_1111L;
    private static final long SEED_CATCH_UP = 0x5EED_2222L;

    private static final BlockPos CROP = new BlockPos(4, 2, 4);

    /**
     * Ticks the light engine is given to darken the buried crop in {@link #darkCropDoesNotGrow},
     * and the one place that says why the figure is what it is.
     *
     * <p>A precondition, not a measurement. The test goes on to assert that a dark crop does not
     * advance, and asserts nothing about how long the darkness took to arrive. A cell that never
     * goes dark still fails the test, hard, and says how long it waited.
     *
     * <p><b>Why it is this large.</b> The same reason {@link UnloadWatch#ALLOWANCE_TICKS} is:
     * what is being waited for is bounded by real time on background executors, not by ticks,
     * and {@code GameTestServer} overrides {@code waitUntilNextTick} with {@code runAllTasks} and
     * never sleeps, so this runner turns roughly 3,850 ticks a second. The old ceiling of twenty
     * consecutive polls was about 5ms of real time; two mailbox hops under forty arenas of chunk
     * churn do not reliably complete inside that, which is why eleven runs in fourteen failed
     * having never once seen the engine hold work (G139, {@code ucu_g137_final*.log}). This
     * figure is roughly two seconds of real time on this runner.
     *
     * <p>Burnt only when the wait fails: the poll stops at the first dark reading, so a passing
     * run costs what the propagation actually took and no more.
     */
    private static final int DARK_WAIT_TICKS = 8000;
    /** Ticks between light polls. Coarse enough not to schedule thousands of runnables. */
    private static final int DARK_POLL_EVERY = 10;
    /** The framework's own timeout, behind the allowance so the allowance reports first. */
    private static final int DARK_TIMEOUT_TICKS = DARK_WAIT_TICKS + 400;

    // ---- can a clean window be skipped? ----------------------------------------------

    /**
     * Repeated over several seed pairs rather than one.
     *
     * <p>The seeds are fixed so the suite does not flake, but a fixed seed means a single
     * realization of the comparison replayed forever. One seed pair cannot distinguish a
     * tolerance sized to the real spread from one that happens to suit that draw, in either
     * direction: whether the correct implementation passes, or whether a broken one is
     * caught.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void cropDistributionMatches(GameTestHelper helper) {
        acrossSeeds(helper, seed -> DifferentialHarness.compareDistributions(
                helper, new CropSubject(), CROP_TICKS, cropEffort(seed)));
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void cropComparisonDetectsOffByOne(GameTestHelper helper) {
        acrossSeeds(helper, seed -> DifferentialHarness.requireDetects(
                "crop probability off by one, seed pair " + seed,
                DifferentialHarness.compareDistributions(
                        helper, new CropSubject(1), CROP_TICKS, cropEffort(seed))));
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceStateMatchesExactly(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.compareExact(
                helper, new FurnaceSubject(), FURNACE_TICKS, SEED_SIMULATED));
    }

    /**
     * The negative control for the comparison above, and the one test here that builds a machine
     * the game cannot resolve.
     *
     * <p>The broken arm carries {@code cookingProgress} across {@code cookingTotalTime} in one
     * jump, and vanilla's completion test is {@code ==}. A furnace left standing on the far side
     * of that boundary never satisfies it again and counts upward for as long as it stays lit, so
     * it has to come out of the world on every path, including every failure — a test that leaves
     * one behind is teaching the rest of the run a value no furnace reaches on its own (GAP_LOG
     * G137 §1, G139).
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceComparisonDetectsMissedTransitions(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject(true);
        try {
            DifferentialHarness.assertVerdict(helper, DifferentialHarness.requireDetects(
                    "furnace jumped past its state changes",
                    DifferentialHarness.compareExact(
                            helper, subject, FURNACE_TICKS, SEED_SIMULATED)));
        } finally {
            subject.clear(helper);
        }
    }

    // ---- is skipping actually cheaper? -----------------------------------------------

    /**
     * The reason any of this is worth doing: the cost of catching up is set by how much the
     * furnace did, not by how long it was left alone.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceCostFollowsWorkNotElapsedTime(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject();
        subject.setup(helper);
        String blocked = subject.precondition(helper);
        if (blocked != null) {
            helper.fail("furnace precondition unmet: " + blocked);
            return;
        }

        subject.reset(helper);
        subject.catchUp(helper, FURNACE_TICKS, RandomSource.create(SEED_CATCH_UP));
        int shortWindow = subject.lastRealTicks();

        subject.reset(helper);
        subject.catchUp(helper, LONG_WINDOW_TICKS, RandomSource.create(SEED_CATCH_UP));
        int longWindow = subject.lastRealTicks();

        Meanwhile.LOGGER.info("[harness] furnace cost | {} ticks -> {} real | {} ticks -> {} real",
                FURNACE_TICKS, shortWindow, LONG_WINDOW_TICKS, longWindow);

        // Not merely bounded: once the furnace has burnt through its input it stops, so the
        // longer window must reach that same stopping point without a single extra tick.
        // Asserting only a loose bound would permit a regression already proven absent.
        if (longWindow > shortWindow) {
            helper.fail("a " + (LONG_WINDOW_TICKS / FURNACE_TICKS) + "x longer window cost "
                    + longWindow + " real ticks against " + shortWindow
                    + ", so the cost still depends on elapsed time");
            return;
        }
        helper.succeed();
    }

    // ---- can a window be split? ------------------------------------------------------

    /**
     * Skipping a window in two pieces must equal skipping it in one.
     *
     * <p>Everything the scheduler does about interruptions rests on this. Without it there
     * is no way to stop at the moment a hopper touches a furnace, and the only safe policy
     * for anything reachable from outside would be to never defer it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceCatchUpComposes(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.compareSegmented(
                helper, new FurnaceSubject(), FURNACE_TICKS, FURNACE_SPLIT_AT,
                Effort.exact(SEED_SIMULATED)));
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void cropCatchUpComposes(GameTestHelper helper) {
        acrossSeeds(helper, seed -> DifferentialHarness.compareSegmented(
                helper, new CropSubject(), CROP_TICKS, CROP_SPLIT_AT, cropEffort(seed)));
    }

    // ---- what happens when the window is interrupted? --------------------------------

    /**
     * Catching up to the interruption, letting it land, and resuming must give the same
     * answer as never having skipped at all. This is what a scheduler that is told about
     * the interruption would do, so it had better be right.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceSurvivesNotifiedDisturbance(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject();
        for (Disturbance disturbance : subject.disturbances()) {
            Verdict verdict = DifferentialHarness.compareWithDisturbance(
                    helper, subject, disturbance, FURNACE_TICKS, FURNACE_DISTURB_AT,
                    Effort.exact(SEED_SIMULATED));
            if (!verdict.passed()) {
                helper.fail(verdict.summary() + " || " + verdict.detail());
                return;
            }
        }
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void cropSurvivesNotifiedDisturbance(GameTestHelper helper) {
        CropSubject subject = new CropSubject();
        for (Disturbance disturbance : subject.disturbances()) {
            Verdict failed = firstFailureAcrossSeeds(seed -> DifferentialHarness.compareWithDisturbance(
                    helper, subject, disturbance, CROP_TICKS, CROP_SPLIT_AT, cropDisturbanceEffort(seed)));
            if (failed != null) {
                helper.fail(failed.summary() + " || " + failed.detail());
                return;
            }
        }
        helper.succeed();
    }

    /**
     * An interruption nobody told the scheduler about must produce a measurably wrong
     * answer.
     *
     * <p>This is the one comparison that is supposed to fail, and it is the point of the
     * whole group. It turns "the scheduler must be notified before anything touches a
     * deferred subject" from an assumption into a measured requirement, and the size of the
     * divergence is how much silent corruption that requirement is holding back.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceUnnotifiedDisturbanceIsWrong(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject();
        for (Disturbance disturbance : subject.disturbances()) {
            Verdict verdict = DifferentialHarness.requireDivergesWithoutNotification(
                    helper, subject, disturbance, FURNACE_TICKS, FURNACE_DISTURB_AT,
                    Effort.exact(SEED_SIMULATED));
            if (!verdict.passed()) {
                helper.fail(verdict.summary() + " || " + verdict.detail());
                return;
            }
        }
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void cropUnnotifiedDisturbanceIsWrong(GameTestHelper helper) {
        CropSubject subject = new CropSubject();
        for (Disturbance disturbance : subject.disturbances()) {
            Verdict failed = firstFailureAcrossSeeds(
                    seed -> DifferentialHarness.requireDivergesWithoutNotification(
                            helper, subject, disturbance, CROP_TICKS, CROP_SPLIT_AT, cropDisturbanceEffort(seed)));
            if (failed != null) {
                helper.fail(failed.summary() + " || " + failed.detail());
                return;
            }
        }
        helper.succeed();
    }

    // ---- being read while deferred ---------------------------------------------------

    /**
     * A comparator that reads the furnace partway through a skipped window must see what it
     * would have seen had the furnace been ticking.
     *
     * <p>A hazard of a different shape from every other one here. The rest ask what the
     * world looks like once the window is over; this asks what it answered while the window
     * was still running. A deferred furnace can reconcile perfectly at the end and still
     * have driven a redstone circuit for a thousand ticks on a number that was never true,
     * and the final state agreeing afterwards does not undo what the circuit did.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceReadMidWindowIsCurrent(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.compareMidWindowRead(
                helper, new FurnaceSubject(), FURNACE_TICKS, FURNACE_READ_AT, true,
                Effort.exact(SEED_SIMULATED)));
    }

    /** Answering that read without catching up first must be measurably wrong. */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceStaleReadIsWrong(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.requireStaleReadDiverges(
                helper, new FurnaceSubject(), FURNACE_TICKS, FURNACE_READ_AT,
                Effort.exact(SEED_SIMULATED)));
    }

    /**
     * What is on disk if the chunk saves partway through a skipped window?
     *
     * <p>The worst of the three hazards, because nothing can hook it from the mutation side
     * and because its damage is permanent. Chunk save serialises a block entity's live
     * fields with no dirty check, so a subject that has not reconciled has its stale state
     * written out verbatim and kept across the restart that would otherwise hide it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceSaveMidWindowIsCurrent(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.compareMidWindowPersist(
                helper, new FurnaceSubject(), FURNACE_READ_AT, true, SEED_SIMULATED));
    }

    /** Serialising without flushing first must be measurably wrong. */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceStaleSaveIsWrong(GameTestHelper helper) {
        DifferentialHarness.assertVerdict(helper, DifferentialHarness.requireStalePersistDiverges(
                helper, new FurnaceSubject(), FURNACE_READ_AT, SEED_SIMULATED));
    }

    // ---- catching yourself having lost track ----------------------------------------

    /**
     * When something reaches a deferred furnace without telling anyone, the catch-up must
     * notice and decline rather than invent a history.
     *
     * <p>The enumeration this replaces cannot be finished. {@code Container} and
     * {@code IItemHandler} both hand out the live {@code ItemStack} objects a block entity
     * holds, so anything with a reference can grow or shrink one in place and no hook fires
     * anywhere; NeoForge's own {@code ItemStackHandler.onContentsChanged} is empty by
     * default, which is the base class most modded machines are built on. No list of routes
     * closes that.
     *
     * <p>Comparing the state against what was left behind needs no list. It cannot recover
     * the lost window — the change happened at an unknown moment and the history is gone —
     * but it does convert silent corruption into a refusal, which is the line worth holding.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void furnaceNoticesBeingChangedBehindItsBack(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject();
        subject.setup(helper);

        for (Disturbance disturbance : subject.disturbances()) {
            subject.reset(helper);
            subject.beginDeferral(helper);
            disturbance.apply(helper);

            boolean reconciled = subject.catchUp(helper, FURNACE_TICKS,
                    RandomSource.create(SEED_CATCH_UP));
            Meanwhile.LOGGER.info("[harness] lost track({}) | reconciled={} lostTrack={}",
                    disturbance.name(), reconciled, subject.lostTrack());

            if (reconciled || !subject.lostTrack()) {
                helper.fail("the furnace was changed behind its back by " + disturbance.name()
                        + " and reconciled anyway, inventing a history it cannot have known");
                return;
            }
        }

        // The other half, and the one that is easy to write vacuously: the check must stay
        // quiet when nothing external happened. Reconciling twice makes that non-trivial,
        // because the first catch-up advances the furnace's own counters and slots by a lot.
        // A fingerprint that is not retaken, or that cannot tell self-progress from
        // interference, reports this untouched furnace as lost.
        subject.reset(helper);
        subject.beginDeferral(helper);
        RandomSource random = RandomSource.create(SEED_CATCH_UP);
        boolean first = subject.catchUp(helper, FURNACE_SPLIT_AT, random);
        boolean second = subject.catchUp(helper, FURNACE_TICKS - FURNACE_SPLIT_AT, random);
        if (!first || !second || subject.lostTrack()) {
            helper.fail("a furnace nobody touched was reported as changed behind its back"
                    + " (first=" + first + " second=" + second + "), so the check rejects its own"
                    + " subject's progress and nothing is ever skipped");
            return;
        }
        helper.succeed();
    }

    /**
     * After a catch-up, the furnace must still be tickable by the game itself.
     *
     * <p>Every other test here drives the furnace by calling {@code serverTick} directly,
     * which never touches the per-chunk ticker the game actually dispatches through. That
     * blind spot matters next to Lithium, whose sleeping block entities work by swapping
     * that ticker for a no-op once a furnace is unlit with no progress — precisely the state
     * a catch-up jumps the furnace into, without running the vanilla tick that would have
     * told Lithium about it.
     *
     * <p>So this one refills the furnace and then lets real ticks happen. If anything has
     * left the furnace detached from the game's dispatch, it never smelts again and the
     * output stops growing.
     *
     * <p><b>The growth has to come from the game's ticking and nothing else.</b> The arena's own
     * chunks arrive owing time — GameTest stands each arena on ground an earlier one used, and
     * the chunk comes back tens of thousands of ticks stale — and that debt is paid off in
     * instalments that can still be running when this window opens. An instalment that reaches
     * this furnace grows the output because <i>the catch-up</i> jumped it, which is the one
     * thing this test must not accept as evidence: the growth check would pass with the game's
     * dispatch completely broken. That is not hypothetical. It was measured in 2 runs of 18,
     * both reading 8.0 -> 16.0 against a usual 10.0, and reproduced deliberately by arming a
     * stale stamp on this arena's own chunk, which reproduced the 16.0 exactly while all 62
     * required tests still passed (GAP_LOG G163, {@code ucu_g163_ctrlA2_noforget.log}). So the
     * arena's own debt is dropped before the window opens, and the same control with the drop
     * in place cleared it and read 10.0 ({@code ucu_g163_ctrlB_forget.log}).
     *
     * <p>The drop keeps it from happening; {@link #TICKED_GROWTH_CEILING} is what makes the
     * gate say so if it happens anyway. Preventing the one mechanism that was found leaves the
     * gate's judgement — output grew, therefore the game resumed — as vacuous as it was, and
     * any other route to the same furnace would pass it just as silently.
     *
     * <h3>What the ceiling still lets through, and what closes it</h3>
     * <p>The ceiling separates the two mechanisms by size, so it only fires on a catch-up large
     * enough to clear it. A debt of a few hundred ticks lands one extra ingot in this furnace and
     * reads as growth of 3 — under the ceiling, and green while the game's ticker may have done
     * none of it. Staged deliberately, that is exactly what it did (GAP_LOG G173).
     *
     * <p>So the growth is attributed rather than only bounded: the arena's owed total is read
     * either side of the window and must not have moved. The drop above clears this arena's
     * pending bookkeeping, so any payment inside the window has to be preceded by the chunk being
     * told it is owed — which moves that figure before a tick is spent, and moves it for a part
     * payment as much as for a whole one. Zero across the window is the statement that no
     * catch-up did any work in it, at any size.
     *
     * <p>This is <b>not</b> the arm-time reset G155 caught moving readings. That one was global
     * and destroyed every other gate's window; this one reaches the caller's own arena and
     * throws if handed anything else.
     *
     * <p>Runs with the scheduler off, in its own batch. What is being measured is a property
     * of the catch-up: that jumping a furnace forward leaves the game's own dispatch able to
     * carry it on. With the scheduler on, this furnace is one nothing can reach, so the
     * scheduler stops ticking it on purpose and the measurement asks whether the mod fails to
     * do the thing it exists to do. The batch keeps the flag off the tests running beside it.
     * {@code SchedulerGameTests#distrustedFurnaceGoesBackToBeingTickedByTheGame} is where the
     * scheduler's own version of this question is asked.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", batch = CatchUpPrimitiveBatch.BATCH, timeoutTicks = 600)
    public static void catchUpLeavesTheFurnaceTickableByTheGame(GameTestHelper helper) {
        FurnaceSubject subject = new FurnaceSubject();
        subject.setup(helper);
        subject.reset(helper);
        // After the reset, so the furnace's state is this test's own, and before anything is
        // measured: from here the arena owes nothing and has nothing queued, and it cannot be
        // handed a fresh debt without being unloaded first.
        CatchUpTestAccess.forget(helper, helper.getLevel());
        if (!ChunkCatchUp.recordsRunningTotals()) {
            helper.fail("the per-chunk running totals are not being kept, so the reading below"
                    + " that attributes this window's growth would be 0 whatever happened in it");
            return;
        }
        long owedBeforeTheWindow = owedToTheArena(helper);

        // Run it to the end of its load: unlit, nothing cooking, input gone.
        subject.catchUp(helper, FURNACE_TICKS, RandomSource.create(SEED_CATCH_UP));
        double outputAfterCatchUp = subject.observe(helper)[0];
        if (outputAfterCatchUp <= 0) {
            helper.fail("the catch-up smelted nothing, so this proves nothing about what follows");
            return;
        }

        // Refill through the normal container API, which is what a hopper would do.
        subject.disturbances().forEach(disturbance -> disturbance.apply(helper));

        helper.runAfterDelay(400L, () -> {
            double outputNow = subject.observe(helper)[0];
            Meanwhile.LOGGER.info("[harness] tickable after catch-up | output {} -> {}",
                    outputAfterCatchUp, outputNow);
            // Attribution, and it comes first. The drop above cleared this arena's pending
            // bookkeeping, so anything paid to it inside the window had to be told it was owed
            // first, and being told is what moves this figure -- before a tick of it is spent,
            // and whether or not the instalment ever finished. A part payment moves no balance
            // and would leave `paidFor` at zero having advanced the furnace anyway.
            long owedNow = owedToTheArena(helper);
            Meanwhile.LOGGER.info("[attrib] tickable after catch-up | arena owed {} -> {}",
                    owedBeforeTheWindow, owedNow);
            if (owedNow != owedBeforeTheWindow) {
                helper.fail("the arena was told it was owed " + (owedNow - owedBeforeTheWindow)
                        + " ticks inside the 400-tick window (" + owedBeforeTheWindow + " -> "
                        + owedNow + "), so a catch-up had work to do on this furnace while the"
                        + " window was open and the growth below cannot be attributed to the"
                        + " game's own ticking. Output read " + outputAfterCatchUp + " -> "
                        + outputNow);
                return;
            }
            if (outputNow <= outputAfterCatchUp) {
                helper.fail("the furnace smelted nothing in 400 real ticks after being caught up"
                        + " (output stayed at " + outputNow + "), so the catch-up left it"
                        + " detached from the game's own ticking");
                return;
            }
            // Growth alone would be satisfied by a catch-up instalment reaching this furnace,
            // which is not what is being measured and would read green with the game's dispatch
            // broken. 400 ticks of smelting is 2 items; anything beyond the ceiling was put
            // there by something other than the ticker, and this gate has no claim to make
            // about that.
            if (outputNow - outputAfterCatchUp > TICKED_GROWTH_CEILING) {
                helper.fail("the furnace's output grew by "
                        + (outputNow - outputAfterCatchUp) + " in 400 real ticks ("
                        + outputAfterCatchUp + " -> " + outputNow + "), past the "
                        + TICKED_GROWTH_CEILING + " that 400 ticks of smelting can produce, so"
                        + " something other than the game's own ticking moved this furnace and"
                        + " the growth above says nothing about whether the dispatch survived");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * What the catch-up has been told this arena is owed, over the whole run.
     *
     * <p>The figure moves when a chunk is reconciled, which is before anything is spent on it,
     * so a delta of zero across a window is the strongest statement available here that no
     * catch-up did any work in it. What has actually been handed over is a weaker reading for
     * this purpose: it only moves when an instalment finishes, and an instalment that stopped
     * half way through the chunk has still advanced everything in front of where it stopped.
     *
     * <p>Every chunk the arena owns, because the furnace's chunk is not the only one a forced
     * ticket keeps loaded and the walk is per chunk.
     */
    private static long owedToTheArena(GameTestHelper helper) {
        long owed = 0L;
        for (ChunkPos chunk : CatchUpTestAccess.arenaChunks(helper)) {
            owed += ChunkCatchUp.owedFor(helper.getLevel(), chunk);
        }
        return owed;
    }

    // ---- the fail-safe: declining to skip -------------------------------------------

    /**
     * A furnace that can tell it is reachable from outside declines to be skipped, and
     * therefore comes out right in the very scenario that corrupts an unguarded one.
     *
     * <p>Same disturbance, same arms, same comparison as
     * {@link #furnaceUnnotifiedDisturbanceIsWrong}. The only difference is a hopper against
     * the furnace, which the catch-up notices. That flips the outcome from silent
     * divergence to an exact match, because declining falls back to ticking for real.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void reachableFurnaceDeclinesAndStaysCorrect(GameTestHelper helper) {
        FurnaceSubject guarded = new FurnaceSubject(false, true);
        for (Disturbance disturbance : guarded.disturbances()) {
            Verdict verdict = DifferentialHarness.compareWithoutNotification(
                    helper, guarded, disturbance, FURNACE_TICKS, FURNACE_DISTURB_AT,
                    Effort.exact(SEED_SIMULATED));
            if (!verdict.passed()) {
                helper.fail(verdict.summary() + " || " + verdict.detail());
                return;
            }
        }
        if (guarded.canDefer(helper)) {
            helper.fail("the furnace agreed to be deferred, so the match above says nothing"
                    + " about the guard");
            return;
        }
        helper.succeed();
    }

    /**
     * The decision to decline has to track something real, in both directions.
     *
     * <p>Declining is always safe, because it just means ticking, so no safety comparison in
     * this file can catch a catch-up that refuses everything — such an implementation would
     * pass all of them while skipping nothing. Only this can, and only by checking both
     * sides: a furnace nothing can reach agrees to be deferred and then genuinely skips, and
     * an otherwise identical furnace with a hopper against it does not.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 400)
    public static void deferDecisionTracksReachability(GameTestHelper helper) {
        FurnaceSubject reachable = new FurnaceSubject(false, true);
        reachable.setup(helper);
        if (reachable.canDefer(helper)) {
            helper.fail("a furnace with a hopper against it agreed to be deferred");
            return;
        }

        FurnaceSubject unreachable = new FurnaceSubject();
        unreachable.setup(helper);
        if (!unreachable.canDefer(helper)) {
            helper.fail("a furnace nothing can reach declined to be deferred,"
                    + " so nothing is ever actually skipped");
            return;
        }

        unreachable.reset(helper);
        unreachable.catchUp(helper, FURNACE_TICKS, RandomSource.create(SEED_CATCH_UP));
        Meanwhile.LOGGER.info("[harness] defer decision | reachable=declined"
                + " unreachable=deferred, {} real ticks of {}",
                unreachable.lastRealTicks(), FURNACE_TICKS);
        if (unreachable.lastRealTicks() >= FURNACE_TICKS) {
            helper.fail("the catch-up agreed to skip and then ran " + unreachable.lastRealTicks()
                    + " real ticks of " + FURNACE_TICKS + ", so it skipped nothing");
            return;
        }
        helper.succeed();
    }

    // ---- crop edge cases the distribution comparison cannot reach --------------------

    /**
     * Crop growth does not depend on the time of day, so a window that spans nightfall
     * needs no special handling.
     *
     * <p>Worth pinning down because the opposite is the intuitive guess and it would change
     * the classification of every surface farm. {@code CropBlock} gates on
     * {@code getRawBrightness(pos, 0)}, and that second argument is how much to subtract
     * from sky light for the time of day. Zero means subtract nothing, so the gate reads
     * {@code max(blockLight, skyLight)} and the clock never enters it. This is also why
     * crops grow at night in vanilla.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = 100)
    public static void cropGateIgnoresTimeOfDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        new CropSubject().setup(helper);
        BlockPos pos = helper.absolutePos(CROP);

        long originalTime = level.getDayTime();
        int byDay = level.getRawBrightness(pos, 0);

        level.setDayTime(18000L);
        level.updateSkyBrightness();
        int skyDarken = level.getSkyDarken();
        int byNight = level.getRawBrightness(pos, 0);

        level.setDayTime(originalTime);
        level.updateSkyBrightness();

        Meanwhile.LOGGER.info("[harness] crop gate | day={} night={} skyDarken@midnight={}",
                byDay, byNight, skyDarken);

        if (skyDarken < 10) {
            helper.fail("midnight did not darken the sky (skyDarken " + skyDarken
                    + "), so this proves nothing about the clock");
            return;
        }
        if (byNight != byDay) {
            helper.fail("brightness moved with the clock: day " + byDay + ", night " + byNight);
            return;
        }
        if (byNight < 9) {
            helper.fail("the plot is below the growth threshold at " + byNight
                    + ", so the comparison is vacuous");
            return;
        }
        helper.succeed();
    }

    /**
     * A crop below the light threshold must not advance, however long it was skipped.
     *
     * <p>Getting a genuinely dark cell takes more than a roof. Skylight spreads sideways
     * losing one level per block, and farmland is not a full cube so it lets light up from
     * below: a crop with stone on all four sides and overhead still measured brightness 12.
     * The crop is therefore buried in stone and only then carved out. The assertion is also
     * deferred, because the light engine propagates at end of tick and would otherwise
     * report the stale value.
     *
     * <h3>What decides when to stop waiting</h3>
     * <p>The light engine cannot be driven on demand — {@code runLightUpdates} throws on the
     * server engine (ThreadedLevelLightEngine.java:54) — and the real work runs on a mailbox
     * thread which is only scheduled from
     * {@code ServerChunkCache.MainThreadExecutor#pollTask}, behind an early return taken
     * whenever {@code runDistanceManagerUpdates()} still has something to do. With forty
     * arenas loading chunks, that gate is closed for long stretches and the light work simply
     * waits. So the poll calls {@code tryScheduleUpdate()} itself, ungated: the same call the
     * game makes, from the same thread, taking the light engine out of competition with chunk
     * loading.
     *
     * <p><b>{@code hasLightWork()} cannot decide when to stop.</b> It reports the sky and block
     * engine queues, which is the last of three stages: {@code checkBlock} posts the change to
     * the priority sorter's mailbox, the sorter releases it to the light mailbox, and only then
     * does it land where that method can see it. Both hops are on background executors. So
     * {@code false} reads identically whether the propagation has finished or has not been
     * scheduled yet, and idle runs of nineteen polls occur in the middle of a propagation that
     * is genuinely in flight (G139, {@code ucu_g137_final06.log}). It is printed as a
     * diagnostic and nothing turns on it.
     *
     * <p>What stops the wait is the precondition itself: the first poll at which the cell is
     * dark runs the assertion. A lit cell stays lit, so waiting longer can never turn a failure
     * into a pass. The only bound is the allowance below, and a wait that runs out fails the
     * test, hard, saying how long it waited in both units and how often the engine was ever
     * seen holding work — which separates "never enqueued" from "started and stalled".
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", timeoutTicks = DARK_TIMEOUT_TICKS)
    public static void darkCropDoesNotGrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    helper.setBlock(CROP.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        helper.setBlock(CROP.below(), Blocks.FARMLAND);
        helper.setBlock(CROP, Blocks.WHEAT);

        // Local rather than fields, so the unfreezing of this one method leaves the rest of
        // the class byte-identical.
        final long pollEvery = DARK_POLL_EVERY;
        final long pollUntil = DARK_WAIT_TICKS;

        long startedNanos = System.nanoTime();
        boolean[] settled = {false};
        int[] busySightings = {0};
        int[] polls = {0};
        for (long delay = pollEvery; delay <= pollUntil; delay += pollEvery) {
            boolean last = delay + pollEvery > pollUntil;
            long at = delay;
            helper.runAfterDelay(delay, () -> {
                if (settled[0]) {
                    return;
                }
                ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
                boolean busy = lightEngine.hasLightWork();
                if (busy) {
                    busySightings[0]++;
                }
                polls[0]++;
                // Take the light engine out of competition with chunk loading. The server only
                // reaches this call when the distance manager has nothing left to do
                // (ServerChunkCache.MainThreadExecutor#pollTask: runDistanceManagerUpdates()
                // returning true short-circuits before it), so while forty arenas are churning
                // chunks the light work is never scheduled at all. This is the same call, from
                // the same thread, ungated.
                lightEngine.tryScheduleUpdate();

                BlockPos pos = helper.absolutePos(CROP);
                int brightness = level.getRawBrightness(pos, 0);
                long waitedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
                Meanwhile.LOGGER.info("[dark] poll t={} waitedMs={} brightness={} busy={}"
                        + " busySeen={}", at, waitedMs, brightness, busy, busySightings[0]);
                if (brightness >= 9) {
                    // Nothing here reads busy. Only running out of the allowance ends the wait.
                    if (last) {
                        settled[0] = true;
                        helper.fail("the crop never went dark: brightness " + brightness
                                + " after " + at + " ticks (" + waitedMs + "ms, " + polls[0]
                                + " polls), and the light engine was seen holding work "
                                + busySightings[0] + " times — zero means the update was never"
                                + " scheduled at all, rather than scheduled and stalled. The"
                                + " dark path was never exercised");
                    }
                    return;
                }
                settled[0] = true;
                Meanwhile.LOGGER.info("[dark] went dark at tick {} of {} | waitedMs={}"
                                + " brightness={} polls={} busySeen={}",
                        at, pollUntil, waitedMs, brightness, polls[0], busySightings[0]);

                BlockState fresh = wheat.getStateForAge(0);
                int age = CropCatchUp.catchUpAge(level, pos, fresh, wheat, 1_000_000,
                        RandomSource.create(SEED_CATCH_UP));
                if (age != 0) {
                    helper.fail("dark crop advanced to age " + age);
                    return;
                }
                helper.succeed();
            });
        }
    }

    /** Skipping a very long window saturates at max age rather than overshooting. */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9")
    public static void longSkipSaturatesAtMaxAge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        CatchUpSubject subject = new CropSubject();
        subject.setup(helper);

        BlockPos pos = helper.absolutePos(CROP);
        BlockState fresh = wheat.getStateForAge(0);
        RandomSource random = RandomSource.create(SEED_CATCH_UP);

        int age = CropCatchUp.catchUpAge(level, pos, fresh, wheat, 1_000_000, random);
        if (age != wheat.getMaxAge()) {
            helper.fail("long skip gave age " + age + ", expected " + wheat.getMaxAge());
            return;
        }

        int unchanged = CropCatchUp.catchUpAge(level, pos, fresh, wheat, 0, random);
        if (unchanged != 0) {
            helper.fail("zero-tick skip changed age to " + unchanged);
            return;
        }
        helper.succeed();
    }

    // ---- helpers ---------------------------------------------------------------------

    private static Effort cropEffort(int seed) {
        return new Effort(CROP_TRIALS, CROP_TOLERANCE, SEED_SIMULATED + seed, SEED_CATCH_UP + seed);
    }

    private static Effort cropDisturbanceEffort(int seed) {
        return new Effort(CROP_DISTURBANCE_TRIALS, CROP_TOLERANCE,
                SEED_SIMULATED + seed, SEED_CATCH_UP + seed);
    }

    private static void acrossSeeds(GameTestHelper helper, Function<Integer, Verdict> comparison) {
        Verdict failed = firstFailureAcrossSeeds(comparison);
        if (failed != null) {
            helper.fail(failed.summary() + " || " + failed.detail());
            return;
        }
        helper.succeed();
    }

    private static Verdict firstFailureAcrossSeeds(Function<Integer, Verdict> comparison) {
        for (int seed = 0; seed < SEED_PAIRS; seed++) {
            Verdict verdict = comparison.apply(seed);
            if (!verdict.passed()) {
                return verdict;
            }
        }
        return null;
    }
}
