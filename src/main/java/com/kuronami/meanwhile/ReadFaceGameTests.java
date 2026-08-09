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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The read hook, driven through a real comparator rather than by calling the scheduler.
 *
 * <p>The suite already contains a read comparison ({@code furnaceReadMidWindow*} in
 * {@link HarnessGameTests}), and it proves something narrower than it looks: it flips a flag
 * in the harness and asks the catch-up for a number. No comparator is involved, so nothing it
 * does could tell you whether a comparator in a world would ever have caused a reconcile.
 * Everything here places a comparator against the subject and makes the game read it.
 *
 * <p>What the comparator itself reports turns out to be nearly useless as evidence, which is
 * measured rather than assumed: a furnace's analog signal is container fullness, and smelting
 * takes one item out of a slot and puts one into another, so the signal reads the same whether
 * the subject was caught up or not (FINDINGS §2.5-(5): "分離したのは lit だけ"). The assertions
 * therefore compare the whole set of things visible from outside — the signal, the comparator's
 * powered state, the block's {@code LIT}, and the four block entity fields — against a furnace
 * the game ticked for the same span.
 *
 * <h3>Its own batch</h3>
 * <p>Same reason as {@link SchedulerGameTests} and {@link WriteFaceGameTests}: the dispatch
 * hook is global once on, and batches run one at a time, so the harness suite is measured
 * against the world it always was.
 */
public class ReadFaceGameTests {

    static final String BATCH = "readface";

    /**
     * Where the window is split. Both land about a hundred ticks into a two-hundred-tick
     * smelt, so a one-tick misalignment between the two arms cannot move an item across a
     * slot boundary and turn a timing difference into a correctness failure.
     */
    private static final int FIRST_READ_AT = 700;
    private static final int SECOND_READ_AT = 1300;

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;

    /**
     * The alignment the arms are allowed to differ by.
     *
     * <p>One arm is driven by the chunk's block entity tick loop and the other by a catch-up
     * started from a test callback, and where those two sit within a server tick is not
     * something either can control — the same reason {@code SchedulerGameTests} takes its
     * comparison at a fixed point rather than mid-window. Two ticks of slack on the counters,
     * nothing at all on the item counts, which is where a real divergence would show: a
     * subject that was never caught up is stale by hundreds of ticks, not by one.
     */
    private static final int ALIGNMENT_SLACK = 2;

    /** Ticked, because of the hopper against it. Its comparator sits to the east. */
    private static final BlockPos TICKED = new BlockPos(2, 1, 2);
    private static final BlockPos TICKED_COMPARATOR = new BlockPos(3, 1, 2);
    /** Deferred, and read. Its comparator sits to the west. */
    private static final BlockPos READ = new BlockPos(6, 1, 6);
    private static final BlockPos READ_COMPARATOR = new BlockPos(5, 1, 6);
    /** Deferred, and never read by anything. Nothing is placed against it. */
    private static final BlockPos UNREAD = new BlockPos(6, 1, 2);
    /** The test-only subject whose signal moves when it is caught up, and its comparator. */
    private static final BlockPos BREWER = new BlockPos(4, 1, 4);
    private static final BlockPos BREWER_COMPARATOR = new BlockPos(3, 1, 4);

    /** Three bottles' worth of window at the fill rate below, with room to spare. */
    private static final int FILL_READ_AT = 400;

    @BeforeBatch(batch = BATCH)
    public static void enableScheduler(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[read] batch begin | enabled={}", DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        // A net under the test that registers one: a failure between registering and the
        // removal in its finally block would otherwise leave the production table changed
        // for every batch after this one.
        unregisterTestTarget();
        Meanwhile.LOGGER.info("[read] batch end | enabled={} targets={}",
                DeferralScheduler.isEnabled(), TargetRegistry.size());
    }

    // ---- the hook fires on the real path ------------------------------------------------

    /**
     * A comparator reading a deferred furnace must be answered by a furnace that is up to
     * date, twice in the same window.
     *
     * <p>Read twice rather than once on purpose. A single read is a weaker test than it
     * appears: the subject is reconciled once, from the fingerprint taken when it was set
     * aside, so an implementation that never re-took the fingerprint would pass. The second
     * read arrives against a subject that has moved under its own power since, and only
     * passes if the first reconcile re-armed the ledger.
     *
     * <p>Two guards keep it from passing while measuring nothing. Before each read, the two
     * arms have to actually disagree — if the deferred furnace were already up to date there
     * would be no window to close and the agreement afterwards would be free. After each
     * read, the level's catch-up counter has to have moved, which is the only evidence that
     * the read reached this position at all: get the comparator's facing wrong and it reads
     * the block on its other side, the hook never fires, and every other assertion here would
     * still hold.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void comparatorReadOfDeferredSubjectClosesTheWindow(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, READ, false);
        placeComparator(helper, TICKED_COMPARATOR, Direction.WEST);
        placeComparator(helper, READ_COMPARATOR, Direction.EAST);
        load(helper, TICKED);
        load(helper, READ);

        helper.runAfterDelay(FIRST_READ_AT, () -> readAndCompare(helper, "first", FIRST_READ_AT));
        helper.runAfterDelay(SECOND_READ_AT, () -> {
            if (readAndCompare(helper, "second", SECOND_READ_AT)) {
                helper.succeed();
            }
        });
    }

    /** @return whether everything held, so the caller knows whether to go on. */
    private static boolean readAndCompare(GameTestHelper helper, String which, int at) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(READ);
        DeferralScheduler scheduler = DeferralScheduler.of(level);

        if (!scheduler.isDeferred(pos)) {
            helper.fail("the " + which + " read has nothing to settle: the furnace is not"
                    + " deferred at tick " + at);
            return false;
        }

        Arm tickedBefore = arm(helper, TICKED, TICKED_COMPARATOR);
        Arm readBefore = arm(helper, READ, READ_COMPARATOR);
        if (tickedBefore == null || readBefore == null) {
            helper.fail("a furnace or its comparator is gone before the " + which + " read");
            return false;
        }
        // No window, no test. A deferred furnace that already agrees with the ticked one has
        // nothing for the read to close, and the comparison afterwards would pass for free.
        if (tickedBefore.agreesWith(readBefore, ALIGNMENT_SLACK)) {
            helper.fail("the deferred furnace was already up to date before the " + which
                    + " read (" + readBefore + " against " + tickedBefore + "), so there was no"
                    + " window for the read to close and the comparison below proves nothing");
            return false;
        }

        int windowsBefore = scheduler.caughtUpWindows();
        long elapsedBefore = scheduler.caughtUpElapsedTicks();
        int realBefore = scheduler.caughtUpRealTicks();

        // The read itself: the same call the block tick scheduler makes on a comparator
        // (ComparatorBlock.java:190-192), which reaches the furnace through
        // refreshOutputState -> calculateOutputSignal -> getInputSignal. Nothing below the
        // bracket touches the scheduler, so the counters moving is the read's doing.
        BlockPos comparatorPos = helper.absolutePos(READ_COMPARATOR);
        level.getBlockState(comparatorPos).tick(level, comparatorPos, level.getRandom());

        int windows = scheduler.caughtUpWindows() - windowsBefore;
        long elapsed = scheduler.caughtUpElapsedTicks() - elapsedBefore;
        int real = scheduler.caughtUpRealTicks() - realBefore;

        Arm tickedAfter = arm(helper, TICKED, TICKED_COMPARATOR);
        Arm readAfter = arm(helper, READ, READ_COMPARATOR);
        Meanwhile.LOGGER.info("[read] {} read at tick {} | ticked {} | deferred {} -> {}"
                        + " | windows={} elapsed={} real={} deferred={} distrusted={}",
                which, at, tickedAfter, readBefore, readAfter, windows, elapsed, real,
                scheduler.isDeferred(pos), scheduler.isDistrusted(pos));

        if (tickedAfter == null || readAfter == null) {
            helper.fail("a furnace or its comparator is gone after the " + which + " read");
            return false;
        }
        if (windows == 0 || elapsed <= 0L) {
            helper.fail("the " + which + " comparator read caught nothing up (windows "
                    + windows + ", elapsed " + elapsed + "), so it never reached the furnace"
                    + " — check that the comparator faces it — and the agreement below would"
                    + " be about a furnace nothing had read");
            return false;
        }
        if (!tickedAfter.agreesWith(readAfter, ALIGNMENT_SLACK)) {
            helper.fail("the " + which + " comparator read was answered by a stale furnace: "
                    + readAfter + " against a ticked one at " + tickedAfter);
            return false;
        }
        if (scheduler.isDistrusted(pos)) {
            helper.fail("the furnace came out distrusted from a read only its own catch-up"
                    + " reached, so the hook re-entered that catch-up through the block state"
                    + " it wrote and read the subject's own progress as interference");
            return false;
        }
        if (!scheduler.isDeferred(pos)) {
            helper.fail("the furnace left the ledger after the " + which + " read, so the"
                    + " saving stops at the first thing that looks at it");
            return false;
        }
        return true;
    }

    // ---- liveness ----------------------------------------------------------------------

    /**
     * A deferred furnace nobody reads must stay where it was left.
     *
     * <p>The failure every safety comparison in this file is blind to. A hook that reconciles
     * on every read of anything, anywhere, answers every comparator correctly and is not a
     * scheduler: it hands back the ticks it saved. Two furnaces are deferred and only one has
     * a comparator against it, so the second one's state is the measurement — it has to still
     * be sitting at the point it was set aside while the first has been brought forward
     * several hundred ticks.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void unreadDeferredSubjectIsLeftAlone(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        placeFurnace(helper, READ, false);
        placeFurnace(helper, UNREAD, false);
        placeComparator(helper, READ_COMPARATOR, Direction.EAST);
        load(helper, READ);
        load(helper, UNREAD);

        helper.runAfterDelay(FIRST_READ_AT, () -> {
            ServerLevel level = helper.getLevel();
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            BlockPos readPos = helper.absolutePos(READ);
            BlockPos unreadPos = helper.absolutePos(UNREAD);

            if (!scheduler.isDeferred(readPos) || !scheduler.isDeferred(unreadPos)) {
                helper.fail("both furnaces have to be deferred for this to measure anything"
                        + " (read=" + scheduler.isDeferred(readPos)
                        + " unread=" + scheduler.isDeferred(unreadPos) + ")");
                return;
            }

            int windowsBefore = scheduler.caughtUpWindows();
            BlockPos comparatorPos = helper.absolutePos(READ_COMPARATOR);
            level.getBlockState(comparatorPos).tick(level, comparatorPos, level.getRandom());
            int windows = scheduler.caughtUpWindows() - windowsBefore;

            AbstractFurnaceBlockEntity read = furnace(helper, READ);
            AbstractFurnaceBlockEntity unread = furnace(helper, UNREAD);
            if (read == null || unread == null) {
                helper.fail("a furnace is gone");
                return;
            }
            Meanwhile.LOGGER.info("[read] one of two read at tick {} | read: {} | unread: {}"
                            + " | windows={} unreadStillDeferred={}",
                    FIRST_READ_AT, arm(helper, READ, READ_COMPARATOR), describe(helper, UNREAD),
                    windows, scheduler.isDeferred(unreadPos));

            if (windows != 1) {
                helper.fail("the read caught up " + windows + " windows where exactly one was"
                        + " read, so either the comparator never reached its furnace or the"
                        + " hook settled a subject nothing looked at");
                return;
            }
            if (read.getItem(2).getCount() == 0) {
                helper.fail("the furnace that was read is still empty-handed, so this test is"
                        + " not comparing a settled subject against an untouched one");
                return;
            }
            // The whole point. Untouched means untouched: no fuel burnt, no progress, nothing
            // smelted, and still on the ledger so the next window keeps accruing.
            if (unread.litTime != 0 || unread.cookingProgress != 0
                    || unread.getItem(0).getCount() != INPUT_COUNT
                    || unread.getItem(1).getCount() != FUEL_COUNT
                    || !unread.getItem(2).isEmpty()) {
                helper.fail("the furnace nobody read was settled anyway ("
                        + describe(helper, UNREAD) + "), so the hook fires on reads that never"
                        + " reach its subject and the saving is given back");
                return;
            }
            if (!scheduler.isDeferred(unreadPos)) {
                helper.fail("the furnace nobody read left the ledger");
                return;
            }
            helper.succeed();
        });
    }

    // ---- before, not after ---------------------------------------------------------------

    /**
     * The number the comparator writes down has to be the one the caught-up subject gives.
     *
     * <p>Nothing above can tell that apart from reconciling immediately after the read. A
     * furnace's analog signal is blind to it by construction, not by an unlucky sample:
     * {@code getRedstoneSignalFromContainer} sums how full the slots are
     * (AbstractContainerMenu.java:758-773) and smelting takes one item out of one slot and
     * puts one into another, so the total is conserved and both arms read 1 at every sample
     * point. That is the measurement in FINDINGS §2.5-(5), reproduced by the logs of the test
     * above. So an implementation that read first and settled afterwards would pass every
     * assertion in this file, while leaving the redstone acting on a number that was never
     * true.
     *
     * <p>Separating the two needs a subject whose signal moves when it is caught up, and no
     * such subject is registered — so this test brings one: a brewing stand whose catch-up
     * fills its bottle slots in proportion to the time it accounts for. It goes into the
     * registry for the length of the test and comes out again in a finally block, with the
     * batch teardown as a net, so the production table is what it was.
     *
     * <p>The stale and settled signals are computed from the container directly rather than
     * by asking the block, because asking the block is a read and a read is the thing under
     * test. Both are asserted: that they differ is what stops this passing on a subject that
     * happened to look the same either way.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 600)
    public static void theReadIsAnsweredAfterTheCatchUpNotBefore(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        try {
            registryTargets().put(BlockEntityType.BREWING_STAND, new FillingTarget());
        } catch (ReflectiveOperationException e) {
            helper.fail("could not register the test-only target: " + e);
            return;
        }
        helper.setBlock(BREWER, Blocks.BREWING_STAND);
        placeComparator(helper, BREWER_COMPARATOR, Direction.EAST);

        helper.runAfterDelay(FILL_READ_AT, () -> {
            BlockPos pos = helper.absolutePos(BREWER);
            BlockPos comparatorPos = helper.absolutePos(BREWER_COMPARATOR);
            DeferralScheduler scheduler = DeferralScheduler.of(level);
            try {
                if (!scheduler.isDeferred(pos)) {
                    helper.fail("the brewing stand is not deferred, so there is no window and"
                            + " nothing for the read to be early or late for");
                    return;
                }

                int stale = signalOf(level, pos);
                level.getBlockState(comparatorPos).tick(level, comparatorPos, level.getRandom());
                int settled = signalOf(level, pos);
                int recorded = level.getBlockEntity(comparatorPos)
                        instanceof ComparatorBlockEntity comparator
                        ? comparator.getOutputSignal() : -1;
                Meanwhile.LOGGER.info("[read] ordering at tick {} | stale={} settled={}"
                                + " recorded={} deferred={} distrusted={}",
                        FILL_READ_AT, stale, settled, recorded, scheduler.isDeferred(pos),
                        scheduler.isDistrusted(pos));

                if (stale == settled) {
                    helper.fail("the subject reads the same caught up (" + settled + ") as it"
                            + " did stale, so this cannot tell when the catch-up ran");
                    return;
                }
                if (recorded != settled) {
                    helper.fail("the comparator wrote down " + recorded + " where the subject"
                            + " it had just been caught up to reads " + settled + " (stale: "
                            + stale + "), so the account was settled after the read rather"
                            + " than before it and the circuit acted on a number that was"
                            + " never true");
                    return;
                }
                if (scheduler.isDistrusted(pos)) {
                    helper.fail("the subject came out distrusted from its own catch-up");
                    return;
                }
                helper.succeed();
            } finally {
                unregisterTestTarget();
            }
        });
    }

    /** The container's own signal, taken without going through the block and so without the hook. */
    private static int signalOf(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container container
                ? AbstractContainerMenu.getRedstoneSignalFromContainer(container) : -1;
    }

    /**
     * A target whose analog signal is a clock.
     *
     * <p>Bottles are used rather than a stackable item because the signal is fullness against
     * the maximum stack size, and a potion's is one — three of them move a five-slot stand
     * from 0 to 9, which no rounding can blur. The fill is proportional to the time accounted
     * for rather than to the number of catch-ups, so the incidental reads a comparator does
     * when it is first placed leave it at zero and the sample point still has a window to
     * close.
     */
    private static final class FillingTarget implements CatchUpTarget {

        private static final int TICKS_PER_BOTTLE = 100;
        private static final int BOTTLES = 3;

        private int accounted;

        @Override
        public boolean canDefer(ServerLevel level, BlockPos pos) {
            return true;
        }

        /** Moves only when this target moves it, so nothing reads as interference. */
        @Override
        public long fingerprint(ServerLevel level, BlockPos pos) {
            return accounted;
        }

        @Override
        public int catchUp(ServerLevel level, BlockPos pos, int ticks) {
            accounted += ticks;
            if (level.getBlockEntity(pos) instanceof Container container) {
                int filled = Math.min(BOTTLES, accounted / TICKS_PER_BOTTLE);
                for (int slot = 0; slot < BOTTLES; slot++) {
                    container.setItem(slot, slot < filled
                            ? new ItemStack(Items.POTION) : ItemStack.EMPTY);
                }
            }
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
            Meanwhile.LOGGER.error("[read] could not unregister the test-only target", e);
        }
    }

    // ---- what one arm looks like from outside -------------------------------------------

    /**
     * Everything about one furnace that something outside it can see.
     *
     * <p>Deliberately more than the comparator's own number. That number is what a redstone
     * circuit acts on, so it has to be here, but it is a coarse quantity — a furnace reports
     * how full it is, and smelting moves an item from one slot to another — and it was
     * measured reading the same on a subject that had been caught up and one that had not.
     * A comparison built on it alone would be green whatever the hook did.
     */
    private record Arm(int signal, boolean powered, boolean lit, int litTime, int progress,
                       int input, int fuel, int output) {

        /**
         * @param slack ticks the two counters may differ by, for the tick the two arms cannot
         *              be aligned to. The item counts get none: a stale subject is behind by
         *              a whole window, and the read points sit a hundred ticks clear of the
         *              boundary where one tick could move an item.
         */
        boolean agreesWith(Arm other, int slack) {
            return signal == other.signal
                    && powered == other.powered
                    && lit == other.lit
                    && input == other.input
                    && fuel == other.fuel
                    && output == other.output
                    && Math.abs(litTime - other.litTime) <= slack
                    && Math.abs(progress - other.progress) <= slack;
        }

        @Override
        public String toString() {
            return "signal=" + signal + " powered=" + powered + " LIT=" + lit
                    + " litTime=" + litTime + " progress=" + progress
                    + " in=" + input + " fuel=" + fuel + " out=" + output;
        }
    }

    /**
     * Reads one arm without going through the hook.
     *
     * <p>The comparator's number is taken from its block entity, which is where
     * {@code refreshOutputState} leaves it (ComparatorBlock.java:167-187), rather than by
     * asking the furnace again — asking again would be a read, and a read is the thing under
     * test.
     */
    @Nullable
    private static Arm arm(GameTestHelper helper, BlockPos furnacePos, BlockPos comparatorPos) {
        ServerLevel level = helper.getLevel();
        AbstractFurnaceBlockEntity furnace = furnace(helper, furnacePos);
        BlockState comparatorState = level.getBlockState(helper.absolutePos(comparatorPos));
        if (furnace == null || !comparatorState.is(Blocks.COMPARATOR)) {
            return null;
        }
        int signal = level.getBlockEntity(helper.absolutePos(comparatorPos))
                instanceof ComparatorBlockEntity comparator ? comparator.getOutputSignal() : -1;
        return new Arm(
                signal,
                comparatorState.getValue(BlockStateProperties.POWERED),
                level.getBlockState(helper.absolutePos(furnacePos))
                        .getValue(AbstractFurnaceBlock.LIT),
                furnace.litTime,
                furnace.cookingProgress,
                furnace.getItem(0).getCount(),
                furnace.getItem(1).getCount(),
                furnace.getItem(2).getCount());
    }

    /** A furnace with no comparator against it. */
    private static String describe(GameTestHelper helper, BlockPos pos) {
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
        if (furnace == null) {
            return "(no furnace)";
        }
        return "LIT=" + helper.getLevel().getBlockState(helper.absolutePos(pos))
                .getValue(AbstractFurnaceBlock.LIT)
                + " litTime=" + furnace.litTime + " progress=" + furnace.cookingProgress
                + " in=" + furnace.getItem(0).getCount() + " fuel=" + furnace.getItem(1).getCount()
                + " out=" + furnace.getItem(2).getCount();
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
     * A comparator whose input is the block one step along {@code towardsSubject}.
     *
     * <p>Set rather than placed. {@code FACING} on a diode is the direction its input lies in
     * (ComparatorBlock.java:99-101), and {@code getStateForPlacement} derives it from where
     * the player is standing (DiodeBlock.java:175-177) — which is a fact about placement, not
     * about reading, and getting it backwards would leave the comparator reading empty air
     * with every other assertion in this file still passing.
     */
    private static void placeComparator(GameTestHelper helper, BlockPos pos,
                                        Direction towardsSubject) {
        helper.setBlock(pos.below(), Blocks.STONE);
        helper.setBlock(pos, Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, towardsSubject));
    }

    private static void placeFurnace(GameTestHelper helper, BlockPos pos, boolean hopperAbove) {
        helper.setBlock(pos, Blocks.FURNACE);
        if (hopperAbove) {
            helper.setBlock(pos.above(), Blocks.HOPPER);
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

    @Nullable
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos))
                instanceof AbstractFurnaceBlockEntity found ? found : null;
    }
}
