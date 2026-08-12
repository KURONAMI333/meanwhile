package com.kuronami.meanwhile;

import com.kuronami.meanwhile.elapsed.CatchUpTestAccess;
import com.kuronami.meanwhile.harness.WorldStateDigest;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The scheduler wired to the game's own tick dispatch, rather than to a harness.
 *
 * <p>Everything in {@link HarnessGameTests} drives a furnace by calling {@code serverTick}
 * itself, which proves the catch-up is correct and proves nothing about whether anything is
 * ever actually skipped in a running world. These tests never call a tick directly. They
 * place blocks, let real ticks happen, and read what the scheduler did to them.
 *
 * <p>Which makes the liveness half the hard part rather than the safety half. Declining to
 * defer is always safe, so an implementation that skips nothing passes every correctness
 * comparison here — the ticked answer is correct by definition. Each test that asserts a
 * deferred furnace comes out right is therefore paired with one that asserts it was
 * genuinely left un-ticked in the first place.
 *
 * <h3>Why its own batch</h3>
 * <p>The dispatch hook is global: once on, it applies to every block entity in the level,
 * including the arenas of every other test running beside it. Batches run one at a time, so
 * the scheduler is switched on for the length of this one and off again afterwards, and the
 * harness suite is measured against the same world it always was.
 */
public class SchedulerGameTests {

    static final String BATCH = "scheduler";

    /** Comfortably past the point a loaded furnace stops changing, which is around 1600. */
    private static final int WINDOW_TICKS = 2200;
    /** Where the split-window test reconciles the first time. Mid-smelt on purpose. */
    private static final int SPLIT_AT_TICKS = 1000;
    /** Long enough for a furnace to light and start cooking, short enough to stay pristine. */
    private static final int SETTLE_TICKS = 5;

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;
    /** One coal, burnt through. The rest is never reached because the input runs out first. */
    private static final int FUEL_LEFT_AT_REST = 3;

    /**
     * Far enough apart that neither furnace is adjacent to the other's hopper, since
     * adjacency is exactly what the deferral decision reads.
     */
    private static final BlockPos TICKED = new BlockPos(2, 1, 2);
    private static final BlockPos DEFERRED = new BlockPos(6, 1, 6);

    /**
     * The catch-up for a whole window costs about twenty real ticks. A generous ceiling, so
     * the assertion is about the shape of the cost rather than its exact value, which
     * {@code furnaceCostFollowsWorkNotElapsedTime} already pins down.
     */
    private static final int REAL_TICK_CEILING = 100;

    /**
     * How much progress a legitimately skipped furnace may be holding.
     *
     * <p>Not zero, because the save hook settles a deferred furnace whenever a chunk save
     * lands on its chunk, and an autosave inside the window is a normal thing for this mod to
     * do rather than a failure of the dispatch. The fold is as large as the stretch since the
     * furnace was deferred, which for a save early in the window is a handful of ticks — the
     * one observed was six. Far below the window, which is what separates catch-up from a
     * dispatch that never skipped anything.
     */
    private static final int ADVANCE_CEILING = 200;

    /** {@link #ticksRun} for a furnace that has visibly done a window's worth of work. */
    private static final int TOO_FAR = Integer.MAX_VALUE;

    @BeforeBatch(batch = BATCH)
    public static void enableScheduler(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[scheduler] batch begin | enabled={} targets={}",
                DeferralScheduler.isEnabled(), com.kuronami.meanwhile.scheduler.TargetRegistry.size());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        Meanwhile.LOGGER.info("[scheduler] batch end | enabled={}", DeferralScheduler.isEnabled());
    }

    // ---- correctness ------------------------------------------------------------------

    /**
     * A furnace the scheduler stopped ticking, once caught up, must be indistinguishable
     * from one the game ticked for the same span.
     *
     * <p>The comparison is taken at a fixed point rather than mid-window. Both furnaces run
     * out of input around tick 1600 and then stop changing at all, so the answer no longer
     * depends on whether the two arms are aligned to the same tick — which they cannot be
     * made to be, since one is driven by the chunk's tick loop and the other by a reconcile
     * call from the test. A mid-window comparison would be off by one whenever the dispatch
     * order shifted, and would read as a correctness failure. The fixed point is asserted
     * explicitly on the ticked arm, so that if it is ever not reached the test says so
     * instead of quietly becoming timing-sensitive.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void deferredFurnaceMatchesTickedFurnace(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, DEFERRED, false);
        load(helper, TICKED);
        load(helper, DEFERRED);

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            String unsettled = notAtRest(helper, TICKED);
            if (unsettled != null) {
                helper.fail("the ticked furnace has not settled after " + WINDOW_TICKS
                        + " ticks (" + unsettled + "), so the comparison would be timing-sensitive");
                return;
            }

            DeferralScheduler.Reconcile reconcile = DeferralScheduler.of(level)
                    .reconcileIfDeferred(level, helper.absolutePos(DEFERRED));
            if (reconcile.result() != DeferralScheduler.Result.CAUGHT_UP) {
                helper.fail("reconciling the deferred furnace gave " + reconcile.result()
                        + " after " + reconcile.elapsedTicks() + " ticks, so it never caught up");
                return;
            }

            WorldStateDigest ticked = digest(helper, TICKED);
            WorldStateDigest deferred = digest(helper, DEFERRED);
            Meanwhile.LOGGER.info("[scheduler] deferred vs ticked | window={} elapsed={} real={}"
                            + " | ticked={} deferred={}",
                    WINDOW_TICKS, reconcile.elapsedTicks(), reconcile.realTicks(),
                    ticked.sha256(), deferred.sha256());

            String difference = ticked.firstDifference(deferred);
            if (difference != null) {
                helper.fail("the deferred furnace does not match the ticked one: " + difference);
                return;
            }
            helper.succeed();
        });
    }

    // ---- liveness ---------------------------------------------------------------------

    /**
     * The deferred furnace must actually have been left alone.
     *
     * <p>Without this, an implementation whose hook never skips anything passes the
     * comparison above every time, because the furnace it compares was ticked for real and a
     * real tick is correct by construction. The check is on the furnace itself rather than a
     * counter: after two thousand ticks in a loaded chunk it must still be holding exactly
     * what it was loaded with. One ticker call would have lit it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void deferredFurnaceIsActuallySkipped(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            AbstractFurnaceBlockEntity furnace = furnace(helper, DEFERRED);
            if (furnace == null) {
                helper.fail("the furnace is gone");
                return;
            }
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            BlockPos pos = helper.absolutePos(DEFERRED);

            // How far the furnace got, which is not the same question as who moved it. The
            // save hook settles a deferred furnace whenever a chunk save lands, and an
            // autosave inside this window therefore leaves a legitimately skipped furnace
            // holding a few ticks of progress. Reading "it moved" as "the dispatch ticked it"
            // makes this test fail for the one reason it is not about, which is what it did.
            int advanced = ticksRun(furnace);
            if (advanced < 0) {
                helper.fail("cannot account for the furnace's state, so whether the dispatch"
                        + " skipped anything is undecidable (litTime=" + furnace.litTime
                        + " litDuration=" + furnace.litDuration
                        + " progress=" + furnace.cookingProgress
                        + " input=" + furnace.getItem(0).getCount()
                        + " fuel=" + furnace.getItem(1).getCount()
                        + " output=" + furnace.getItem(2).getCount() + ")");
                return;
            }
            if (advanced > ADVANCE_CEILING) {
                helper.fail("the furnace advanced " + (advanced == TOO_FAR ? "past accounting" : advanced + " ticks")
                        + " during " + WINDOW_TICKS
                        + " real ticks (litTime=" + furnace.litTime
                        + " progress=" + furnace.cookingProgress
                        + " input=" + furnace.getItem(0).getCount()
                        + " output=" + furnace.getItem(2).getCount()
                        + "), which is too much to be catch-up alone, so the dispatch hook was"
                        + " ticking it and every comparison beside this one is measuring an"
                        + " ordinary ticked furnace");
                return;
            }
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace did not advance and is not in the ledger either,"
                        + " so it is not being deferred, it is simply not ticking");
                return;
            }

            DeferralScheduler.Reconcile reconcile = scheduler.reconcileIfDeferred(level, pos);
            Meanwhile.LOGGER.info("[scheduler] skipped ticks | window={} elapsed={} real={} result={}",
                    WINDOW_TICKS, reconcile.elapsedTicks(), reconcile.realTicks(), reconcile.result());

            if (reconcile.result() != DeferralScheduler.Result.CAUGHT_UP) {
                helper.fail("reconciling gave " + reconcile.result());
                return;
            }
            // Every tick of the window has to be accounted for, and by the ledger rather than
            // by the dispatch: what the furnace already ran plus what it still owes must come
            // to the window. A hook that skipped nothing leaves nothing owed, so it fails here
            // even if the ceiling above were generous; a hook that skipped everything and was
            // settled once by a passing chunk save still adds up.
            long accounted = advanced + reconcile.elapsedTicks();
            if (Math.abs(accounted - WINDOW_TICKS) > 10) {
                helper.fail("the furnace ran " + advanced + " ticks and the ledger still owes "
                        + reconcile.elapsedTicks() + ", which comes to " + accounted
                        + " against a window of " + WINDOW_TICKS + ", so the two do not describe"
                        + " the same window");
                return;
            }
            if (reconcile.realTicks() > REAL_TICK_CEILING) {
                helper.fail("catching up " + reconcile.elapsedTicks() + " ticks cost "
                        + reconcile.realTicks() + " real ticks, so nothing was saved");
                return;
            }
            String unsettled = notAtRest(helper, DEFERRED);
            if (unsettled != null) {
                helper.fail("the caught-up furnace is not where a ticked one would be: " + unsettled);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * How many ticks this furnace has been through, from its own state.
     *
     * <p>A furnace loaded with fuel lights on its first tick and burns one tick of it per
     * tick after that, so {@code litDuration - litTime + 1} is the count, whether those ticks
     * came from the dispatch or from a catch-up folding them up. Untouched is zero.
     *
     * @return -1 when the state is past the point this arithmetic holds — a second piece of
     *         fuel taken, or lit with no duration — because guessing there would be worse
     *         than saying the question cannot be answered
     */
    private static int ticksRun(AbstractFurnaceBlockEntity furnace) {
        if (furnace.litTime == 0
                && furnace.cookingProgress == 0
                && furnace.getItem(0).getCount() == INPUT_COUNT
                && furnace.getItem(1).getCount() == FUEL_COUNT
                && furnace.getItem(2).isEmpty()) {
            return 0;
        }
        if (furnace.getItem(2).getCount() > 0 || furnace.getItem(0).getCount() < INPUT_COUNT - 1) {
            // Smelting finished, or more than one item consumed. A window this mod skipped is
            // folded up in one catch-up whose cost is bounded, so a furnace that got this far
            // was being ticked; there is no need to know exactly how many times.
            return TOO_FAR;
        }
        if (furnace.litTime <= 0 || furnace.litDuration <= 0
                || furnace.getItem(1).getCount() < FUEL_LEFT_AT_REST) {
            return -1;
        }
        return furnace.litDuration - furnace.litTime + 1;
    }

    /**
     * The decision to defer has to track something real, in both directions.
     *
     * <p>An implementation that defers everything and one that defers nothing are both
     * self-consistent, and each passes half the suite. Only checking both sides in one place
     * rules out both: a furnace with a hopper against it keeps ticking and visibly gets on
     * with its work, and an otherwise identical furnace nothing can reach is set aside and
     * visibly does not.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 200)
    public static void reachableFurnaceIsNotDeferred(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, DEFERRED, false);
        load(helper, TICKED);
        load(helper, DEFERRED);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            boolean reachableDeferred = scheduler.isDeferred(helper.absolutePos(TICKED));
            boolean unreachableDeferred = scheduler.isDeferred(helper.absolutePos(DEFERRED));
            AbstractFurnaceBlockEntity reachable = furnace(helper, TICKED);
            AbstractFurnaceBlockEntity unreachable = furnace(helper, DEFERRED);
            if (reachable == null || unreachable == null) {
                helper.fail("a furnace is gone");
                return;
            }

            Meanwhile.LOGGER.info("[scheduler] reachability | hopper: deferred={} litTime={}"
                            + " | alone: deferred={} litTime={}",
                    reachableDeferred, reachable.litTime,
                    unreachableDeferred, unreachable.litTime);

            if (reachableDeferred) {
                helper.fail("a furnace with a hopper against it was deferred");
                return;
            }
            if (!unreachableDeferred) {
                helper.fail("a furnace nothing can reach was not deferred,"
                        + " so nothing is ever actually skipped");
                return;
            }
            // The reachable one has to be demonstrably still running, not merely absent from
            // the ledger. A hook that skipped it without recording it would look identical.
            if (reachable.litTime == 0 || reachable.cookingProgress == 0) {
                helper.fail("the reachable furnace was not deferred but did not tick either"
                        + " in " + SETTLE_TICKS + " ticks (litTime=" + reachable.litTime
                        + " progress=" + reachable.cookingProgress + ")");
                return;
            }
            if (unreachable.litTime != 0 || unreachable.cookingProgress != 0) {
                helper.fail("the deferred furnace ticked anyway (litTime=" + unreachable.litTime
                        + " progress=" + unreachable.cookingProgress + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A window reconciled twice must come out where a window reconciled once does.
     *
     * <p>The first catch-up moves the furnace's counters and slots a long way under its own
     * power, and it is still deferred afterwards. A ledger that does not retake the
     * fingerprint at that moment sees its own subject's progress as interference and refuses
     * the second half, which is the failure a single-window test cannot express: with only
     * one reconcile the fingerprint is never stale, so collapsing it to a constant breaks
     * nothing.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void splitWindowKeepsTheFurnaceDeferred(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, DEFERRED, false);
        load(helper, TICKED);
        load(helper, DEFERRED);

        helper.runAfterDelay(SPLIT_AT_TICKS, () -> {
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler.Reconcile first = scheduler.reconcileIfDeferred(level, pos);
            Meanwhile.LOGGER.info("[scheduler] split first half | elapsed={} real={} result={}",
                    first.elapsedTicks(), first.realTicks(), first.result());
            if (first.result() != DeferralScheduler.Result.CAUGHT_UP) {
                helper.fail("the first half gave " + first.result());
                return;
            }
            if (!scheduler.isDeferred(pos)) {
                helper.fail("a clean catch-up dropped the furnace out of the ledger,"
                        + " so the second half would just be an ordinary ticked furnace");
            }
        });

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            String unsettled = notAtRest(helper, TICKED);
            if (unsettled != null) {
                helper.fail("the ticked furnace has not settled (" + unsettled + ")");
                return;
            }
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler.Reconcile second = scheduler.reconcileIfDeferred(level, pos);
            Meanwhile.LOGGER.info("[scheduler] split second half | elapsed={} real={} result={}",
                    second.elapsedTicks(), second.realTicks(), second.result());

            if (second.result() == DeferralScheduler.Result.LOST_TRACK) {
                helper.fail("a furnace nobody touched was reported as changed behind its back"
                        + " on the second reconcile, so the ledger reads its own subject's"
                        + " progress as interference and a window can never be split");
                return;
            }
            if (second.result() != DeferralScheduler.Result.CAUGHT_UP) {
                helper.fail("the second half gave " + second.result());
                return;
            }

            WorldStateDigest ticked = digest(helper, TICKED);
            WorldStateDigest deferred = digest(helper, DEFERRED);
            Meanwhile.LOGGER.info("[scheduler] split total | ticked={} deferred={}",
                    ticked.sha256(), deferred.sha256());
            String difference = ticked.firstDifference(deferred);
            if (difference != null) {
                helper.fail("the twice-reconciled furnace does not match the ticked one: "
                        + difference);
                return;
            }
            helper.succeed();
        });
    }

    // ---- coming back ---------------------------------------------------------------------

    /**
     * A furnace the ledger has stopped trusting goes back to being ticked by the game, and
     * smelts.
     *
     * <p>The safety claim this design rests on is that every window either accounts for
     * itself or is ticked for real. The second half of that is a liveness claim and nothing
     * else here measures it: {@code furnaceNoticesBeingChangedBehindItsBack} asserts the
     * ledger refuses to apply a window it cannot account for, which leaves open the outcome
     * where the subject is refused and then also never ticked again. A furnace in that state
     * is not corrupt. It is stopped, permanently, and nothing about the world says why.
     *
     * <p>Driven the whole way through real surfaces. The furnace is set aside by the
     * dispatch, reached without notification the way a hopper or another mod would reach it,
     * and then read through {@code getAnalogOutputSignal} — the vanilla method a comparator
     * calls, which the read hook is injected into. Nothing calls the scheduler directly.
     *
     * <p>The assertion is that it smelts, not that a flag flipped. A furnace that is being
     * ticked produces iron; one that is skipped does not, whatever the ledger says about it.
     *
     * <h3>Where the iron has to come from</h3>
     * <p>Ticking is not the only thing that can put iron in this furnace. The arena's chunks
     * arrive owing the catch-up tens of thousands of ticks — GameTest stands each arena on
     * ground an earlier one used — and an instalment reaching this furnace inside the window
     * smelts too. The output would grow with the dispatch never resuming at all, and this test
     * asserts only that it grew. That is the shape found in this gate's sibling,
     * {@link HarnessGameTests#catchUpLeavesTheFurnaceTickableByTheGame}, where it was measured
     * at 8.0 -> 16.0 in 2 runs of 18 and reproduced deliberately (GAP_LOG G163, G164 ruling
     * 43). So the arena's debt is dropped before the window opens, the same way and for the
     * same reason.
     *
     * <p><b>Prevented here rather than attributed.</b> The sibling also carries a ceiling on
     * the growth, which fails rather than passes when something else moves the furnace. This
     * one has no such ceiling: 600 ticks of smelting is exactly 3 items and the reading has
     * been 3 in 77 of the 78 recorded runs, so any ceiling tight enough to catch an instalment
     * would sit one item above a value that already lands on a completion boundary. An
     * assertion nobody has watched fail is not evidence that it can fail, and this class is
     * registered only when the loaded-scheme marker asks for it, so no run in the standing
     * suite could watch it (GAP_LOG G165).
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 1200)
    public static void distrustedFurnaceGoesBackToBeingTickedByTheGame(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace was never set aside, so nothing below is about a"
                        + " subject the ledger stopped trusting");
                return;
            }
            AbstractFurnaceBlockEntity furnace = furnace(helper, DEFERRED);
            if (furnace == null) {
                helper.fail("the furnace is gone");
                return;
            }
            if (furnace.getItem(2).getCount() != 0) {
                helper.fail("the furnace had already smelted before it was set aside, so"
                        + " output growing later would not mean it had been resumed");
                return;
            }

            // Reached without anything being told: the case the fingerprint exists for.
            furnace.setItem(1, new ItemStack(Items.COAL, FUEL_COUNT + 1));
            // The vanilla method a comparator calls, which is where the read hook sits.
            level.getBlockState(pos).getAnalogOutputSignal(level, pos);

            if (!scheduler.isDistrusted(pos)) {
                helper.fail("the ledger did not catch the furnace being changed, so what"
                        + " follows would be about an ordinary deferred furnace");
                return;
            }
            if (scheduler.isDeferred(pos)) {
                helper.fail("the furnace is still set aside after the ledger stopped"
                        + " trusting it");
                return;
            }

            // Immediately before the window, for the reason set out above: from here the arena
            // owes the catch-up nothing and has nothing queued, so the output this window reads
            // cannot have been put there by an instalment.
            CatchUpTestAccess.forget(helper, level);

            helper.runAfterDelay(600L, () -> {
                AbstractFurnaceBlockEntity resumed = furnace(helper, DEFERRED);
                if (resumed == null) {
                    helper.fail("the furnace is gone");
                    return;
                }
                int output = resumed.getItem(2).getCount();
                Meanwhile.LOGGER.info("[scheduler] distrusted resumes | out={} litTime={}"
                                + " cookingProgress={} deferred={} distrusted={}",
                        output, resumed.litTime, resumed.cookingProgress,
                        scheduler.isDeferred(pos), scheduler.isDistrusted(pos));
                if (output <= 0) {
                    helper.fail("the furnace smelted nothing in 600 real ticks after the"
                            + " ledger stopped trusting it (output " + output + "), so being"
                            + " refused a window left it stopped rather than ticked");
                    return;
                }
                if (scheduler.isDeferred(pos)) {
                    helper.fail("the furnace was set aside again after the ledger had"
                            + " stopped trusting it");
                    return;
                }
                helper.succeed();
            });
        });
    }

    // ---- helpers ----------------------------------------------------------------------

    private static boolean schedulerOff(GameTestHelper helper) {
        if (DeferralScheduler.isEnabled()) {
            return false;
        }
        helper.fail("the scheduler is off, so the batch hook that turns it on did not run"
                + " and none of these tests measure anything");
        return true;
    }

    /**
     * @param hopperAbove makes the furnace reachable from outside, which is what the deferral
     *                    decision reads. The hopper is empty and stays empty, so it moves no
     *                    items; all it does is keep the furnace on the ordinary tick path.
     */
    private static void placeFurnace(GameTestHelper helper, BlockPos pos, boolean hopperAbove) {
        helper.setBlock(pos, Blocks.FURNACE);
        if (hopperAbove) {
            helper.setBlock(pos.above(), Blocks.HOPPER);
        }
    }

    /**
     * The same starting load the harness uses: enough to light, smelt eight times, run out of
     * input while still lit, and go cold.
     *
     * <p>The input slot is emptied before being filled because vanilla only recomputes the
     * cook time when the slot receives a different item, and a furnace with a cook time of
     * zero burns its fuel through without ever completing a smelt.
     */
    private static void load(GameTestHelper helper, BlockPos pos) {
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
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
        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));
    }

    /**
     * Why a furnace is not yet at the state it stops changing in, or null when it is.
     */
    @Nullable
    private static String notAtRest(GameTestHelper helper, BlockPos pos) {
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
        if (furnace == null) {
            return "there is no furnace at " + pos;
        }
        if (furnace.litTime != 0) {
            return "still lit, litTime=" + furnace.litTime;
        }
        if (furnace.cookingProgress != 0) {
            return "still cooking, progress=" + furnace.cookingProgress;
        }
        if (!furnace.getItem(0).isEmpty()) {
            return "input not exhausted, " + furnace.getItem(0).getCount() + " left";
        }
        if (furnace.getItem(2).getCount() != INPUT_COUNT) {
            return "smelted " + furnace.getItem(2).getCount() + " of " + INPUT_COUNT;
        }
        if (furnace.getItem(1).getCount() != FUEL_LEFT_AT_REST) {
            return "fuel is " + furnace.getItem(1).getCount()
                    + ", expected " + FUEL_LEFT_AT_REST;
        }
        return null;
    }

    /** The single block, so nothing a neighbour does can enter the comparison. */
    private static WorldStateDigest digest(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return WorldStateDigest.capture(helper.getLevel(),
                BoundingBox.fromCorners(absolute, absolute));
    }

    @Nullable
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos))
                instanceof AbstractFurnaceBlockEntity found ? found : null;
    }
}
