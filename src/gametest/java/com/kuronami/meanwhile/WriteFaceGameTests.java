package com.kuronami.meanwhile;

import com.kuronami.meanwhile.scheduler.CatchUpTarget;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import com.kuronami.meanwhile.scheduler.TargetRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The write hook, driven through the game's own block-writing path rather than by calling
 * the scheduler.
 *
 * <p>The distinction matters here more than anywhere else so far. A test that reconciles a
 * furnace itself and then checks the answer proves the catch-up is right and says nothing
 * about whether a player breaking that furnace would ever have triggered one. So nothing
 * below calls {@code reconcileIfDeferred}. They call {@code destroyBlock} and
 * {@code setBlock}, which is what a pickaxe, a piston and an explosion all end up calling,
 * and then read what fell on the floor.
 *
 * <h3>Its own batch</h3>
 * <p>Same reason as {@link SchedulerGameTests}: the dispatch hook is global once on, and
 * batches run one at a time. Sharing that class's batch would change the composition of a
 * batch whose numbers are already recorded.
 */
public class WriteFaceGameTests {

    static final String BATCH = "writeface";

    /** Comfortably past the point a loaded furnace stops changing, which is around 1600. */
    private static final int WINDOW_TICKS = 2200;
    /** Long enough for the scheduler to have seen the furnace and set it aside. */
    private static final int SETTLE_TICKS = 5;
    /** Mid-window on purpose: late enough to be deferred, early enough to still matter. */
    private static final int DISTURB_AT_TICKS = 1000;

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;
    /** One coal, burnt through. The rest is never reached because the input runs out first. */
    private static final int FUEL_LEFT_AT_REST = 3;

    private static final BlockPos TICKED = new BlockPos(2, 1, 2);
    private static final BlockPos DEFERRED = new BlockPos(6, 1, 6);
    /** Somewhere else entirely in the same arena, for writes that must change nothing. */
    private static final BlockPos ELSEWHERE = new BlockPos(2, 1, 6);
    /** For the test-only target, clear of every other arena position. */
    private static final BlockPos SWAPPER = new BlockPos(4, 1, 4);

    @BeforeBatch(batch = BATCH)
    public static void enableScheduler(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[write] batch begin | enabled={}", DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        // A net under the test that registers one: a failure between registering and the
        // removal in its finally block would otherwise leave the production table changed
        // for every batch after this one.
        unregisterTestTarget();
        Meanwhile.LOGGER.info("[write] batch end | enabled={} targets={}",
                DeferralScheduler.isEnabled(), TargetRegistry.size());
    }

    // ---- the hook fires on the real path ------------------------------------------------

    /**
     * Breaking a deferred furnace must drop what it would have held, not what it was holding
     * when it stopped being ticked.
     *
     * <p>Nothing here asks the scheduler to do anything. {@code Level#destroyBlock} runs the
     * ordinary sequence — drop the block's own loot, write air into the chunk, and let the
     * old state's {@code onRemove} empty the container onto the floor — and the only place a
     * hook can get between the last two is inside {@code LevelChunk#setBlockState}. So the
     * items on the floor are the whole assertion: eight iron ingots means the window was
     * settled first, eight raw iron means it was not.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void writeToDeferredSubjectReconcilesFirst(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);

            String notSetAside = notSetAside(helper, scheduler, pos, DEFERRED);
            if (notSetAside != null) {
                helper.fail(notSetAside);
                return;
            }

            // The write itself. Nothing below this line touches the scheduler.
            boolean destroyed = level.destroyBlock(pos, true);
            if (!destroyed) {
                helper.fail("destroyBlock did nothing, so the write path was never entered");
                return;
            }

            Map<Item, Integer> drops = dropsAround(level, pos);
            Meanwhile.LOGGER.info("[write] drops after breaking a deferred furnace | window={} {}",
                    WINDOW_TICKS, drops);

            int ingots = drops.getOrDefault(Items.IRON_INGOT, 0);
            int raw = drops.getOrDefault(Items.RAW_IRON, 0);
            if (raw > 0 || ingots != INPUT_COUNT) {
                helper.fail("breaking the deferred furnace dropped " + ingots + " iron ingot(s)"
                        + " and " + raw + " raw iron, where a furnace that had run for "
                        + WINDOW_TICKS + " ticks holds " + INPUT_COUNT + " ingots and no raw"
                        + " iron — the window was not settled before the container was emptied");
                return;
            }
            if (drops.getOrDefault(Items.COAL, 0) != FUEL_LEFT_AT_REST) {
                helper.fail("the fuel does not match a furnace that ran the window: "
                        + drops.getOrDefault(Items.COAL, 0) + " coal, expected "
                        + FUEL_LEFT_AT_REST);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A write the furnace survives must go through untouched.
     *
     * <p>A rotation changes the block state and nothing else: {@code onRemove} short-circuits
     * on {@code !state.is(newState.getBlock())} (AbstractFurnaceBlock.java:62), the block
     * entity is valid for the incoming state so it is not dropped (LevelChunk.java:288-291),
     * and the container is never emptied. There is nothing here for a hook whose job is to
     * settle the account before something destroys the subject, so it must stand aside — and
     * standing aside has to be visible, which is what the untouched contents are for.
     *
     * <p>Cheap and early on purpose. The furnace has barely been set aside, so if anything
     * did reconcile it the difference would be unmistakable rather than marginal.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 400)
    public static void sameBlockWriteIsPassedThrough(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(SETTLE_TICKS * 10, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace is not deferred, so there is nothing to stand aside for");
                return;
            }

            BlockState before = level.getBlockState(pos);
            Direction turned = before.getValue(AbstractFurnaceBlock.FACING) == Direction.EAST
                    ? Direction.WEST : Direction.EAST;
            level.setBlock(pos, before.setValue(AbstractFurnaceBlock.FACING, turned), 3);

            AbstractFurnaceBlockEntity furnace = furnace(helper, DEFERRED);
            BlockState after = level.getBlockState(pos);
            if (furnace == null) {
                helper.fail("the rotation removed the furnace, so this measures nothing");
                return;
            }
            Meanwhile.LOGGER.info("[write] rotation passed through | facing {} -> {} | out={}"
                            + " litTime={} deferred={} distrusted={}",
                    before.getValue(AbstractFurnaceBlock.FACING),
                    after.getValue(AbstractFurnaceBlock.FACING),
                    furnace.getItem(2).getCount(), furnace.litTime,
                    scheduler.isDeferred(pos), scheduler.isDistrusted(pos));

            if (after.getValue(AbstractFurnaceBlock.FACING) != turned) {
                helper.fail("the rotation did not land, so nothing was written at all");
                return;
            }
            if (!furnace.getItem(2).isEmpty() || furnace.litTime != 0) {
                helper.fail("the rotation settled the window (out=" + furnace.getItem(2).getCount()
                        + " litTime=" + furnace.litTime + "), so the hook reconciled a write"
                        + " that destroys nothing, and the block state the caller wrote is now"
                        + " sitting on top of one the catch-up wrote");
                return;
            }
            if (scheduler.isDistrusted(pos)) {
                helper.fail("the furnace was distrusted by a write that never reconciled it");
                return;
            }
            helper.succeed();
        });
    }

    // ---- liveness ----------------------------------------------------------------------

    /**
     * A write somewhere else must not settle anything.
     *
     * <p>The failure this rules out is the one every safety comparison in the suite is blind
     * to: a hook that reconciles on any write at all is correct in every arm of every test,
     * because a caught-up furnace is a correct furnace. What it is not is a scheduler — it
     * gives back the ticks it saved. The check is on the furnace rather than a counter,
     * because a counter can be moved without the furnace being touched and the reverse is
     * what actually matters.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void writeElsewhereLeavesTheWindowOpen(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);

            String notSetAside = notSetAside(helper, scheduler, pos, DEFERRED);
            if (notSetAside != null) {
                helper.fail(notSetAside);
                return;
            }

            level.setBlock(helper.absolutePos(ELSEWHERE), Blocks.STONE.defaultBlockState(), 3);

            AbstractFurnaceBlockEntity furnace = furnace(helper, DEFERRED);
            if (furnace == null) {
                helper.fail("the furnace is gone");
                return;
            }
            Meanwhile.LOGGER.info("[write] unrelated write | litTime={} progress={} input={}"
                            + " output={} deferred={}",
                    furnace.litTime, furnace.cookingProgress, furnace.getItem(0).getCount(),
                    furnace.getItem(2).getCount(), scheduler.isDeferred(pos));

            if (furnace.litTime != 0 || furnace.cookingProgress != 0
                    || furnace.getItem(0).getCount() != INPUT_COUNT
                    || !furnace.getItem(2).isEmpty()) {
                helper.fail("a write four blocks away settled the furnace's window"
                        + " (litTime=" + furnace.litTime + " progress=" + furnace.cookingProgress
                        + " input=" + furnace.getItem(0).getCount()
                        + " output=" + furnace.getItem(2).getCount() + "), so the hook fires on"
                        + " writes that never reach its subject and the saving is given back");
                return;
            }
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace left the ledger without anything reaching it");
                return;
            }
            helper.succeed();
        });
    }

    // ---- what the position-only hook costs ----------------------------------------------

    /**
     * The measurement behind the decision to look only at the written position.
     *
     * <p>A hopper placed against a deferred furnace writes to the hopper's position, not the
     * furnace's, so this hook does not see it. The hopper then feeds a furnace that is still
     * set aside, and the window becomes unaccountable: the ledger refuses it at the next
     * reconcile rather than applying it. This test does not assert that outcome is good. It
     * pins down what it costs, next to a furnace that stayed on the tick path through the
     * same disturbance.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void neighbourWriteCostsTheWindow(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, DEFERRED, false);
        load(helper, TICKED);
        load(helper, DEFERRED);

        // gameTime when the furnace was first seen set aside, and when the window was refused.
        long[] span = new long[2];

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            span[0] = level.getGameTime();
            if (!DeferralScheduler.of(level).isDeferred(helper.absolutePos(DEFERRED))) {
                helper.fail("the furnace was never deferred, so there is no window to lose");
            }
        });

        helper.runAfterDelay(DISTURB_AT_TICKS, () -> {
            // One raw iron into each arm's hopper. The ticked furnace has had one against it
            // from the start, which is why it was never deferred; the deferred one gets its
            // hopper now, after the decision was already taken.
            helper.setBlock(DEFERRED.above(), Blocks.HOPPER);
            feedHopper(helper, TICKED.above());
            feedHopper(helper, DEFERRED.above());
        });

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            AbstractFurnaceBlockEntity deferred = furnace(helper, DEFERRED);
            AbstractFurnaceBlockEntity ticked = furnace(helper, TICKED);
            if (deferred == null || ticked == null) {
                helper.fail("a furnace is gone");
                return;
            }

            // The disturbance has to have landed, or this measures an undisturbed window.
            if (deferred.getItem(0).getCount() <= INPUT_COUNT) {
                helper.fail("the hopper never fed the deferred furnace (input is "
                        + deferred.getItem(0).getCount() + "), so nothing was disturbed and"
                        + " this test is measuring an ordinary window");
                return;
            }
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace stopped being deferred before the write, so the"
                        + " refusal below is not the one being measured");
                return;
            }

            span[1] = level.getGameTime();
            int lostWork = ticked.getItem(2).getCount();
            // Breaking it: the first write since the hopper arrived that takes the furnace
            // away, and so the first one this hook acts on.
            if (!level.destroyBlock(pos, true)) {
                helper.fail("destroyBlock did nothing, so the write path was never entered");
                return;
            }

            Map<Item, Integer> drops = dropsAround(level, pos);
            Meanwhile.LOGGER.info("[write] neighbour hopper | window>={} ticks | deferred drops"
                            + " {} distrusted={} | ticked: input={} output={}",
                    span[1] - span[0], drops, scheduler.isDistrusted(pos),
                    ticked.getItem(0).getCount(), lostWork);

            if (!scheduler.isDistrusted(pos)) {
                helper.fail("a furnace fed behind the scheduler's back was not distrusted,"
                        + " so the fingerprint did not catch the hopper");
                return;
            }
            if (drops.getOrDefault(Items.IRON_INGOT, 0) != 0) {
                helper.fail("the refused window was applied anyway: "
                        + drops.getOrDefault(Items.IRON_INGOT, 0) + " iron ingot(s) dropped");
                return;
            }
            if (drops.getOrDefault(Items.RAW_IRON, 0) != INPUT_COUNT + 1) {
                helper.fail("the furnace dropped " + drops.getOrDefault(Items.RAW_IRON, 0)
                        + " raw iron, where a furnace frozen with the hopper's one extra holds "
                        + (INPUT_COUNT + 1));
                return;
            }
            if (lostWork == 0) {
                helper.fail("the ticked furnace smelted nothing either, so there is no"
                        + " lost work to measure against");
                return;
            }
            helper.succeed();
        });
    }

    // ---- what a mid-window write does to the block state --------------------------------

    /**
     * A write that keeps the block, taken while a ticked furnace would be burning, must
     * leave the block state and the block entity agreeing with each other.
     *
     * <p>This is the measurement that decided the shape of the hook, run the other way
     * round. Reconciling on a write like this used to produce a lost update: the caller
     * derives its state from a read taken beforehand (Level.java:231, delegating at :250),
     * the reconcile then ticks the furnace and the tick writes a block state of its own
     * (AbstractFurnaceBlockEntity.java:334-337), and the caller's write lands on top of it.
     * The furnace came out burning with {@code LIT=false} on the block — and since the
     * fingerprint was taken over the block entity, nothing recorded it. Measured at
     * {@code litTime=602} against {@code LIT=false}, with {@code distrusted=false}.
     *
     * <p>Standing aside on writes the subject survives removes the mechanism rather than
     * papering over it: no reconcile means no block state to lose. The furnace stays where
     * it was left, which is consistent by construction — {@code litTime=0} and
     * {@code LIT=false} are the same claim.
     *
     * <p>The ticked arm is not decoration. Both furnaces are consistent when neither is
     * burning, so without evidence that this sample point is one where a running furnace
     * would be lit, the assertion would hold for an entirely idle world.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void midWindowWriteKeepsTheBlockStateHonest(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, DEFERRED, false);
        load(helper, TICKED);
        load(helper, DEFERRED);

        helper.runAfterDelay(DISTURB_AT_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace is not deferred at the sample point");
                return;
            }
            probeLog(helper, "mid before", TICKED, DEFERRED);
            rotate(helper, TICKED, Direction.WEST);
            rotate(helper, DEFERRED, Direction.WEST);
            probeLog(helper, "mid after", TICKED, DEFERRED);

            AbstractFurnaceBlockEntity ticked = furnace(helper, TICKED);
            AbstractFurnaceBlockEntity deferred = furnace(helper, DEFERRED);
            if (ticked == null || deferred == null) {
                helper.fail("a furnace is gone");
                return;
            }
            // Evidence that this sample point is mid-burn. Without it the agreement below
            // would also hold in a world where nothing was ever lit.
            if (ticked.litTime == 0 || !level.getBlockState(helper.absolutePos(TICKED))
                    .getValue(AbstractFurnaceBlock.LIT)) {
                helper.fail("the ticked furnace is not burning at tick " + DISTURB_AT_TICKS
                        + " (litTime=" + ticked.litTime + "), so the sample point is wrong");
                return;
            }

            boolean litInState = level.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
            if (litInState != (deferred.litTime > 0)) {
                helper.fail("the block state and the block entity disagree: LIT=" + litInState
                        + " against litTime=" + deferred.litTime + ". A write that destroys"
                        + " nothing reconciled anyway, and the state the caller wrote rolled"
                        + " back the one the catch-up wrote");
                return;
            }
            if (scheduler.isDistrusted(pos)) {
                helper.fail("the rotation distrusted the furnace before anything reconciled it");
                return;
            }
        });

        helper.runAfterDelay(DISTURB_AT_TICKS + 200, () -> {
            probeLog(helper, "mid +200", TICKED, DEFERRED);
            AbstractFurnaceBlockEntity deferred = furnace(helper, DEFERRED);
            boolean lit = helper.getLevel().getBlockState(helper.absolutePos(DEFERRED))
                    .getValue(AbstractFurnaceBlock.LIT);
            if (deferred == null) {
                helper.fail("the furnace is gone");
                return;
            }
            if (lit != (deferred.litTime > 0)) {
                helper.fail("the block state and the block entity disagree 200 ticks later:"
                        + " LIT=" + lit + " against litTime=" + deferred.litTime);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A block state changed mid-window must cost the furnace its deferral, loudly.
     *
     * <p>The other half of standing aside. Passing the write through means the window keeps
     * running under a premise that has just changed, so the fingerprint has to cover the
     * block state — otherwise the next reconcile folds the window up as though the change
     * never happened, which is the quiet kind of wrong this whole design exists to avoid.
     *
     * <p>What the fingerprint cannot do is say whether the property that changed mattered.
     * A furnace's facing does not, and this test rotates one and watches it lose its
     * deferral anyway. That is the intended trade: which properties affect behaviour is the
     * same unenumerable question as which routes can reach a container, so the whole state
     * goes in and the cost is paid in optimisation rather than in correctness. Declining is
     * always safe; folding a window up on a stale premise is not.
     *
     * <p>The assertion is on the items rather than only on the ledger flag, so that a
     * refusal which is recorded but not honoured cannot pass: eight raw iron on the floor is
     * a window that was genuinely never applied.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void midWindowBlockStateChangeIsCaught(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        placeFurnace(helper, DEFERRED, false);
        load(helper, DEFERRED);

        helper.runAfterDelay(DISTURB_AT_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace is not deferred, so there is no window to invalidate");
                return;
            }
            rotate(helper, DEFERRED, Direction.WEST);
            if (scheduler.isDistrusted(pos)) {
                helper.fail("the rotation itself distrusted the furnace, so the refusal below"
                        + " would not be the fingerprint catching a changed premise");
            }
        });

        helper.runAfterDelay(WINDOW_TICKS, () -> {
            BlockPos pos = helper.absolutePos(DEFERRED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            if (!scheduler.isDeferred(pos)) {
                helper.fail("the furnace stopped being deferred on its own before the write");
                return;
            }
            if (!level.destroyBlock(pos, true)) {
                helper.fail("destroyBlock did nothing, so the write path was never entered");
                return;
            }

            Map<Item, Integer> drops = dropsAround(level, pos);
            Meanwhile.LOGGER.info("[write] block state changed mid-window | drops {}"
                    + " distrusted={}", drops, scheduler.isDistrusted(pos));

            if (!scheduler.isDistrusted(pos)) {
                helper.fail("a furnace whose block state changed mid-window was not distrusted,"
                        + " so the fingerprint does not cover the block state and the window"
                        + " was folded up on a premise that no longer held");
                return;
            }
            if (drops.getOrDefault(Items.IRON_INGOT, 0) != 0) {
                helper.fail("the refused window was applied anyway: "
                        + drops.getOrDefault(Items.IRON_INGOT, 0) + " iron ingot(s) dropped");
                return;
            }
            if (drops.getOrDefault(Items.RAW_IRON, 0) != INPUT_COUNT) {
                helper.fail("the furnace dropped " + drops.getOrDefault(Items.RAW_IRON, 0)
                        + " raw iron, where one frozen at the point it was set aside holds "
                        + INPUT_COUNT);
                return;
            }
            helper.succeed();
        });
    }

    private static void rotate(GameTestHelper helper, BlockPos relative, Direction facing) {
        BlockPos pos = helper.absolutePos(relative);
        BlockState current = helper.getLevel().getBlockState(pos);
        if (current.getBlock() instanceof AbstractFurnaceBlock) {
            helper.getLevel().setBlock(pos, current.setValue(AbstractFurnaceBlock.FACING, facing), 3);
        }
    }

    private static void probeLog(GameTestHelper helper, String stage, BlockPos ticked, BlockPos deferred) {
        DeferralScheduler scheduler = DeferralScheduler.of(helper.getLevel());
        Meanwhile.LOGGER.info("[probe] {} | ticked: {} | deferred: {} deferredFlag={} distrusted={}",
                stage, arm(helper, ticked), arm(helper, deferred),
                scheduler.isDeferred(helper.absolutePos(deferred)),
                scheduler.isDistrusted(helper.absolutePos(deferred)));
    }

    /** "LIT=<block state> litTime=<block entity> progress=.. out=.." for one furnace. */
    private static String arm(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        BlockState state = helper.getLevel().getBlockState(pos);
        AbstractFurnaceBlockEntity furnace = furnace(helper, relative);
        String lit = state.getBlock() instanceof AbstractFurnaceBlock
                ? String.valueOf(state.getValue(AbstractFurnaceBlock.LIT)) : "n/a";
        if (furnace == null) {
            return "LIT=" + lit + " (no block entity)";
        }
        return "LIT=" + lit + " litTime=" + furnace.litTime
                + " progress=" + furnace.cookingProgress
                + " in=" + furnace.getItem(0).getCount()
                + " out=" + furnace.getItem(2).getCount();
    }

    // ---- the re-entry the same-block bail cannot catch -----------------------------------

    /**
     * A catch-up that replaces its own block must not be re-entered by the write hook.
     *
     * <p>The hook stands aside for writes that keep the block, which happens to cover the
     * only writes a furnace's catch-up makes: lighting one rewrites the same block with a
     * different {@code LIT}. So for every target registered today, the bail alone would do,
     * and the claim {@code reconcileIfDeferred} takes around the catch-up is never the thing
     * that saves it — measured, by removing the claim and watching all 32 tests pass anyway.
     *
     * <p>That equivalence is a property of furnaces, not of the design. A catch-up whose
     * subject turns into a different block on the way — chorus flower, bamboo, a machine that
     * swaps itself — writes something the bail cannot stand aside for, because that write
     * really does take the subject away. Without the claim the hook then reconciles a subject
     * whose catch-up is halfway through, reads the progress it has already made as
     * interference, and distrusts the position for the session, while the outer call still
     * returns {@code CAUGHT_UP} and the items still come out right.
     *
     * <p>No such target is registered, so this test brings its own: a target that steps its
     * own fingerprint and then swaps its block out and back. It is put into the registry for
     * the length of the test and taken out again, so the production table is what it was.
     * The registry has no API for that — it is not something production code should be able
     * to do — hence the reflection, which is confined to here.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 400)
    public static void catchUpThatReplacesItsOwnBlockIsNotReentered(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockSwappingTarget target = new BlockSwappingTarget();
        try {
            registryTargets().put(BlockEntityType.BREWING_STAND, target);
        } catch (ReflectiveOperationException e) {
            helper.fail("could not register the test-only target: " + e);
            return;
        }
        helper.setBlock(SWAPPER, Blocks.BREWING_STAND);

        helper.runAfterDelay(SETTLE_TICKS * 4, () -> {
            BlockPos pos = helper.absolutePos(SWAPPER);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            try {
                if (!scheduler.isDeferred(pos)) {
                    helper.fail("the brewing stand was never set aside, so no catch-up runs"
                            + " and the re-entry this test is about cannot happen");
                    return;
                }

                DeferralScheduler.Reconcile reconcile = scheduler.reconcileIfDeferred(level, pos);
                Meanwhile.LOGGER.info("[write] block-swapping catch-up | elapsed={} real={}"
                                + " result={} swaps={} deferred={} distrusted={} block={}",
                        reconcile.elapsedTicks(), reconcile.realTicks(), reconcile.result(),
                        target.swaps, scheduler.isDeferred(pos), scheduler.isDistrusted(pos),
                        level.getBlockState(pos).getBlock());

                if (target.swaps == 0) {
                    helper.fail("the catch-up never ran (result " + reconcile.result()
                            + ", elapsed " + reconcile.elapsedTicks() + "), so no block was"
                            + " replaced and nothing could have re-entered");
                    return;
                }
                if (reconcile.result() != DeferralScheduler.Result.CAUGHT_UP) {
                    helper.fail("the catch-up gave " + reconcile.result());
                    return;
                }
                if (scheduler.isDistrusted(pos)) {
                    helper.fail("a subject nobody but its own catch-up touched came out"
                            + " distrusted: the write hook re-entered the catch-up through the"
                            + " block it replaced and read that subject's own progress as"
                            + " interference");
                    return;
                }
                if (!scheduler.isDeferred(pos)) {
                    helper.fail("the subject left the ledger during its own catch-up");
                    return;
                }
                if (!level.getBlockState(pos).is(Blocks.BREWING_STAND)) {
                    helper.fail("the catch-up did not put its block back, so the swap this"
                            + " test relies on did not happen as written");
                    return;
                }
                helper.succeed();
            } finally {
                unregisterTestTarget();
            }
        });
    }

    /**
     * A target whose catch-up replaces the block it lives in.
     *
     * <p>The fingerprint moves before the block does, on purpose. A re-entering hook has to
     * find a subject that has already made progress — which is what makes it call that
     * progress interference — and it also stops the re-entry recursing instead of resolving.
     */
    private static final class BlockSwappingTarget implements CatchUpTarget {

        private int swaps;

        @Override
        public boolean canDefer(ServerLevel level, BlockPos pos) {
            return true;
        }

        @Override
        public long fingerprint(ServerLevel level, BlockPos pos) {
            return swaps;
        }

        @Override
        public int catchUp(ServerLevel level, BlockPos pos, int ticks) {
            swaps++;
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, Blocks.BREWING_STAND.defaultBlockState(), 3);
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockEntityType<?>, CatchUpTarget> registryTargets()
            throws ReflectiveOperationException {
        Field field = TargetRegistry.class.getDeclaredField("TARGETS");
        field.setAccessible(true);
        return (Map<BlockEntityType<?>, CatchUpTarget>) field.get(null);
    }

    private static void unregisterTestTarget() {
        try {
            registryTargets().remove(BlockEntityType.BREWING_STAND);
        } catch (ReflectiveOperationException e) {
            Meanwhile.LOGGER.error("[write] could not unregister the test-only target", e);
        }
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
     * Why the furnace is not in the state these tests need before they write to it, or null
     * when it is.
     *
     * <p>Two conditions, and both are load-bearing. It has to be in the ledger, or the hook
     * has nothing to react to. And it has to be untouched after {@link #WINDOW_TICKS} real
     * ticks in a loaded chunk, which is the only evidence available that the dispatch really
     * withheld the whole window rather than the test having caught a furnace that was ticking
     * normally: one ticker call would have lit it.
     */
    @Nullable
    private static String notSetAside(GameTestHelper helper, DeferralScheduler scheduler,
                                      BlockPos absolute, BlockPos relative) {
        if (scheduler.deferredCount() == 0) {
            return "nothing at all is deferred, so the dispatch hook is not running";
        }
        if (!scheduler.isDeferred(absolute)) {
            return "the furnace is not deferred, so the write below has nothing to settle";
        }
        AbstractFurnaceBlockEntity furnace = furnace(helper, relative);
        if (furnace == null) {
            return "there is no furnace at " + relative;
        }
        if (furnace.litTime != 0 || furnace.cookingProgress != 0
                || furnace.getItem(0).getCount() != INPUT_COUNT
                || furnace.getItem(1).getCount() != FUEL_COUNT
                || !furnace.getItem(2).isEmpty()) {
            return "the furnace advanced during " + WINDOW_TICKS + " real ticks (litTime="
                    + furnace.litTime + " progress=" + furnace.cookingProgress + " input="
                    + furnace.getItem(0).getCount() + " output=" + furnace.getItem(2).getCount()
                    + "), so it was being ticked normally and the write has nothing to do";
        }
        return null;
    }

    private static void placeFurnace(GameTestHelper helper, BlockPos pos, boolean hopperAbove) {
        helper.setBlock(pos, Blocks.FURNACE);
        if (hopperAbove) {
            helper.setBlock(pos.above(), Blocks.HOPPER);
        }
    }

    /** One raw iron, which the hopper pushes down into the furnace's input slot. */
    private static void feedHopper(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof Container hopper) {
            hopper.setItem(0, new ItemStack(Items.RAW_IRON, 1));
        }
    }

    /** The same starting load the rest of the suite uses. See {@code SchedulerGameTests}. */
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

    /** Everything on the floor within a couple of blocks, tallied by item. */
    private static Map<Item, Integer> dropsAround(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(2.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
        Map<Item, Integer> tally = new HashMap<>();
        for (ItemEntity entity : items) {
            ItemStack stack = entity.getItem();
            tally.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return tally;
    }

    @Nullable
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos))
                instanceof AbstractFurnaceBlockEntity found ? found : null;
    }
}
