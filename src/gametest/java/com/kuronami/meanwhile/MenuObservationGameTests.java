package com.kuronami.meanwhile;

import com.kuronami.meanwhile.elapsed.CatchUpTestAccess;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * What a player holding a furnace's screen open is shown, and what the scheduler does about
 * it.
 *
 * <p>A reconcile surface wraps one read. A screen is not one read: the server re-sends it
 * from the block entity for as long as it stays open ({@code ServerPlayer.java:518} into
 * {@code AbstractContainerMenu#broadcastChanges}, reading the {@code ContainerData} at
 * {@code AbstractFurnaceBlockEntity.java:71}). Settling the subject when the screen opens
 * therefore buys one correct frame and nothing after it. Measured before it was designed
 * around: a deferred furnace pushed its screen nothing across a hundred ticks while a ticked
 * one pushed 208.
 *
 * <p>So the scheduler is told about the observation rather than about the read, and refuses
 * to defer anything while somebody is looking at it.
 *
 * <p>Which needs measuring in both directions in the same test, for the reason the whole
 * suite is built around: refusing to defer is always safe, so "never defer anything" passes
 * every correctness assertion here. The unwatched furnace is what makes the watched one mean
 * something. So is the ticked control — without it, a recording of zero pushes is equally
 * consistent with the recorder never having been wired up.
 */
public final class MenuObservationGameTests {

    /**
     * Its own batch. A placed player keeps chunks loaded and is ticked alongside everything
     * else in its batch, and the harness batch's numbers are the thing under gate.
     */
    static final String BATCH = "menuprobe";

    /** Loses its hopper partway through, and is watched. Nothing may set it aside. */
    private static final BlockPos WATCHED = new BlockPos(3, 1, 4);
    /** Keeps its hopper, so the scheduler declines it and the game ticks it throughout. */
    private static final BlockPos CONTROL = new BlockPos(6, 1, 4);
    /** Loses its hopper too, and nobody looks at it. This one has to be set aside. */
    private static final BlockPos UNWATCHED = new BlockPos(3, 1, 7);

    /** Where each watcher stands. Within the four blocks a container menu stays valid for. */
    private static final BlockPos WATCHED_SEAT = new BlockPos(3, 1, 3);
    private static final BlockPos CONTROL_SEAT = new BlockPos(6, 1, 3);

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;

    /** Long enough for the hoppered furnaces to be lit and part way into a smelt. */
    private static final int WARM_UP = 60;
    /** After the hoppers come off, so the dispatch has had a tick to decide. */
    private static final int OPEN_AT = WARM_UP + 5;
    /** Ticks after opening at which the screens are read. */
    private static final int[] SAMPLE_AFTER_OPEN = {1, 25, 50, 75, 100};
    private static final int LAST_SAMPLE = SAMPLE_AFTER_OPEN[SAMPLE_AFTER_OPEN.length - 1];

    @BeforeBatch(batch = BATCH)
    public static void beginBatch(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[menu] batch begin | enabled={}", DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        Meanwhile.LOGGER.info("[menu] batch end | enabled={}", DeferralScheduler.isEnabled());
    }

    /**
     * A furnace with a screen open on it keeps being ticked, and its screen keeps moving.
     *
     * <p>Three furnaces, one difference each. The watched one has nothing against it, so
     * every reachability test the scheduler runs says it may be set aside; the only thing
     * standing in the way is that somebody is looking at it. The unwatched one is identical
     * apart from nobody looking, and has to end up set aside. The control keeps its hopper
     * and is never a candidate, which is what proves the recording works at all.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 400)
    public static void watchedFurnaceKeepsTicking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        place(helper, WATCHED, true);
        place(helper, CONTROL, true);
        place(helper, UNWATCHED, true);

        ServerPlayer watcher = seat(helper, WATCHED_SEAT);
        ServerPlayer controlWatcher = seat(helper, CONTROL_SEAT);

        Recorder watchedRecord = new Recorder("watched");
        Recorder controlRecord = new Recorder("control");
        // Written by the callback that opens and read by the one that ends: [watched,
        // unwatched]. Both furnaces have cooked during the warm-up, so what separates them
        // afterwards is which one keeps going, not which one has a nonzero counter.
        int[] progressAtOpen = new int[2];

        helper.runAfterDelay(WARM_UP, () -> {
            // All three are still ticking here, because all three still have a hopper.
            report(helper, "warm-up", WATCHED, "watched", null, null);
            report(helper, "warm-up", CONTROL, "control", null, null);
            report(helper, "warm-up", UNWATCHED, "unwatched", null, null);
            AbstractFurnaceBlockEntity warmed = furnace(helper, WATCHED);
            if (warmed == null || warmed.cookingProgress <= 0) {
                helper.fail("the furnaces never started cooking, so a frozen one would look"
                        + " the same as a running one");
                return;
            }
            // From the next dispatch on, the only thing separating the watched furnace from
            // the unwatched one is that somebody is about to look at it.
            helper.setBlock(WATCHED.above(), Blocks.AIR);
            helper.setBlock(UNWATCHED.above(), Blocks.AIR);
        });

        helper.runAfterDelay(OPEN_AT, () -> {
            open(helper, watcher, WATCHED, watchedRecord);
            open(helper, controlWatcher, CONTROL, controlRecord);
            AbstractFurnaceBlockEntity opened = furnace(helper, WATCHED);
            progressAtOpen[0] = opened == null ? -1 : opened.cookingProgress;
            AbstractFurnaceBlockEntity ignored = furnace(helper, UNWATCHED);
            progressAtOpen[1] = ignored == null ? -1 : ignored.cookingProgress;
            report(helper, "open+0", WATCHED, "watched", watcher, watchedRecord);
            report(helper, "open+0", CONTROL, "control", controlWatcher, controlRecord);
            report(helper, "open+0", UNWATCHED, "unwatched", null, null);
            // Immediately before the window this gate judges over. Everything below turns on
            // cookingProgress having moved on one furnace and not on another, and a catch-up
            // instalment reaching either of them moves it without the game's dispatch having
            // anything to do with it — the shape found in
            // HarnessGameTests#catchUpLeavesTheFurnaceTickableByTheGame, where it was measured
            // and reproduced (GAP_LOG G164 ruling 43, G172 ruling 53). GameTest stands each
            // arena on ground an earlier one used, so these chunks arrive owing tens of
            // thousands of ticks. From here this arena owes nothing and has nothing queued.
            CatchUpTestAccess.forget(helper, level);
        });

        for (int after : SAMPLE_AFTER_OPEN) {
            helper.runAfterDelay(OPEN_AT + after, () -> {
                report(helper, "open+" + after, WATCHED, "watched", watcher, watchedRecord);
                report(helper, "open+" + after, CONTROL, "control", controlWatcher, controlRecord);
                report(helper, "open+" + after, UNWATCHED, "unwatched", null, null);

                BlockPos watchedPos = helper.absolutePos(WATCHED);
                if (DeferralScheduler.of(level).isDeferred(watchedPos)) {
                    helper.fail("the furnace was set aside while a screen was open on it, so"
                            + " the screen is showing a frame that stopped at open+" + after);
                    return;
                }
                if (!(watcher.containerMenu instanceof AbstractFurnaceMenu)) {
                    helper.fail("the screen closed itself by open+" + after + ", so what the"
                            + " scheduler did while it was open was not measured");
                }
            });
        }

        helper.runAfterDelay(OPEN_AT + LAST_SAMPLE + 5, () -> {
            Meanwhile.LOGGER.info("[menu] totals | watched pushes={} | control pushes={}",
                    watchedRecord.total(), controlRecord.total());

            AbstractFurnaceBlockEntity watched = furnace(helper, WATCHED);
            AbstractFurnaceBlockEntity unwatched = furnace(helper, UNWATCHED);
            if (watched == null || unwatched == null) {
                helper.fail("a furnace is gone");
                return;
            }
            if (watched.cookingProgress <= progressAtOpen[0]) {
                helper.fail("the watched furnace made no progress across " + LAST_SAMPLE
                        + " ticks with its screen open (cookingProgress stayed at "
                        + watched.cookingProgress + ")");
                return;
            }
            if (controlRecord.total() <= 0) {
                helper.fail("the ticked control pushed nothing to its screen, so the"
                        + " recording says nothing about the watched furnace");
                return;
            }
            if (watchedRecord.total() <= 0) {
                helper.fail("the watched furnace pushed nothing to its screen across "
                        + LAST_SAMPLE + " ticks, so its bar did not move");
                return;
            }
            if (!DeferralScheduler.of(level).isDeferred(helper.absolutePos(UNWATCHED))) {
                helper.fail("the furnace nobody was looking at was not set aside either, so"
                        + " the watched one staying ticked says nothing about observation");
                return;
            }
            if (unwatched.cookingProgress != progressAtOpen[1]) {
                helper.fail("the unwatched furnace kept cooking across " + LAST_SAMPLE
                        + " ticks (cookingProgress " + progressAtOpen[1] + " -> "
                        + unwatched.cookingProgress + "), so it was not actually skipped and"
                        + " the watched one staying ticked says nothing about observation");
                return;
            }
            leave(level, watcher);
            leave(level, controlWatcher);
            helper.succeed();
        });
    }

    // ---- the watchers -------------------------------------------------------------------

    private static ServerPlayer seat(GameTestHelper helper, BlockPos seat) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos absolute = helper.absolutePos(seat);
        player.teleportTo(absolute.getX() + 0.5D, absolute.getY(), absolute.getZ() + 0.5D);
        return player;
    }

    private static void open(GameTestHelper helper, ServerPlayer player, BlockPos pos,
                             Recorder recorder) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        BlockState state = level.getBlockState(absolute);
        MenuProvider provider = state.getMenuProvider(level, absolute);
        if (provider == null) {
            Meanwhile.LOGGER.info("[menu] {} | no menu provider at {}", recorder.name, absolute);
            return;
        }
        player.openMenu(provider);
        // After opening, so sendInitialData has already gone out through the real
        // synchronizer. What is being counted is what the screen is told from here on.
        player.containerMenu.setSynchronizer(recorder);
    }

    private static void leave(ServerLevel level, ServerPlayer player) {
        player.closeContainer();
        level.getServer().getPlayerList().remove(player);
    }

    // ---- what a sample is ---------------------------------------------------------------

    private static void report(GameTestHelper helper, String at, BlockPos pos, String which,
                               @Nullable ServerPlayer watcher, @Nullable Recorder recorder) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
        if (furnace == null) {
            Meanwhile.LOGGER.info("[menu] {} {} | gone", which, at);
            return;
        }

        String menu = "not-open";
        if (watcher != null) {
            AbstractContainerMenu open = watcher.containerMenu;
            if (open instanceof AbstractFurnaceMenu furnaceMenu) {
                menu = String.format("lit=%s litProgress=%.4f burnProgress=%.4f out=%d",
                        furnaceMenu.isLit(), furnaceMenu.getLitProgress(),
                        furnaceMenu.getBurnProgress(),
                        furnaceMenu.getSlot(2).getItem().getCount());
            } else {
                // The server closes a menu whose block went out of range or away
                // (ServerPlayer.java:519-521), and a closed menu reads exactly like a frozen
                // one from the recording. Say which it is.
                menu = "closed(" + open.getClass().getSimpleName() + ")";
            }
        }

        Meanwhile.LOGGER.info(
                "[menu] {} {} | deferred={} litTime={} cookingProgress={} in={} fuel={} out={}"
                        + " | menu {} | pushes={}",
                which, at,
                DeferralScheduler.of(level).isDeferred(absolute),
                furnace.litTime, furnace.cookingProgress,
                furnace.getItem(0).getCount(), furnace.getItem(1).getCount(),
                furnace.getItem(2).getCount(),
                menu,
                recorder == null ? -1 : recorder.total());
    }

    /** Counts what the server pushes to one screen, without touching what it pushes. */
    private static final class Recorder implements ContainerSynchronizer {

        private final String name;
        private int slotChanges;
        private int dataChanges;

        private Recorder(String name) {
            this.name = name;
        }

        private int total() {
            return this.slotChanges + this.dataChanges;
        }

        @Override
        public void sendInitialData(AbstractContainerMenu container, NonNullList<ItemStack> items,
                                    ItemStack carriedItem, int[] initialData) {
            Meanwhile.LOGGER.info("[menu] {} initial | data={} {} {} {}", this.name,
                    initialData.length > 0 ? initialData[0] : -1,
                    initialData.length > 1 ? initialData[1] : -1,
                    initialData.length > 2 ? initialData[2] : -1,
                    initialData.length > 3 ? initialData[3] : -1);
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu container, int slot, ItemStack itemStack) {
            this.slotChanges++;
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu containerMenu, ItemStack stack) {
        }

        @Override
        public void sendDataChange(AbstractContainerMenu container, int id, int value) {
            this.dataChanges++;
        }
    }

    // ---- arena --------------------------------------------------------------------------

    private static void place(GameTestHelper helper, BlockPos pos, boolean hopperAbove) {
        helper.setBlock(pos, Blocks.FURNACE);
        if (hopperAbove) {
            helper.setBlock(pos.above(), Blocks.HOPPER);
        }
        load(helper, pos);
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
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        return blockEntity instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private MenuObservationGameTests() {
    }
}
