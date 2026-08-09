package com.kuronami.meanwhile;

import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The save hook, driven through the real serializer rather than by calling the scheduler.
 *
 * <p>The suite already contains a persistence comparison ({@code furnaceSaveMidWindow*} in
 * {@link HarnessGameTests}) and it proves something narrower than it looks: it flips a flag in
 * the harness and asks it to serialise a region. No chunk save is involved, so nothing it does
 * could tell you whether saving a chunk in a world would ever have caused a reconcile.
 * Everything here goes through {@code ChunkSerializer#write} or through the one method that
 * loop calls, and reads the answer out of the tag that came back.
 *
 * <h3>Why the assertions look at the tag and not at the world</h3>
 * <p>The measurement that matters is what would reach the disk, and this is the one face where
 * that is not the same thing as the state of the world afterwards. A hook placed after the tag
 * is built settles the account either way — the world ends up correct and the tag does not — so
 * every assertion about the subject's fields would hold while the region file still got the
 * state the furnace stopped ticking at. The read face measured the same trap from the other
 * side: moving its injector one step later left two of its three tests green
 * (FINDINGS §2.5-(5), and {@code ReadFaceGameTests#theReadIsAnsweredAfterTheCatchUpNotBefore}
 * exists because of it). Here the discriminating comparison is
 * {@code written} against {@code control}, both taken as NBT.
 *
 * <h3>Two batches</h3>
 * <p>Tests within a batch run side by side in one level, and a chunk save is not selective: it
 * settles every deferred subject in the chunk it is saving, which may include another test's.
 * The whole-chunk test therefore has a batch to itself, and the liveness test — whose whole
 * claim is about a subject nothing asked for — has one where nothing writes a whole chunk.
 */
public class PersistFaceGameTests {

    /** The whole-chunk serialisation, alone, for the reason in the class note. */
    static final String CHUNK_BATCH = "persistchunk";
    /** Everything that saves one position at a time. */
    static final String BATCH = "persistface";

    /**
     * Where the save falls. The same point {@code ReadFaceGameTests} takes its first reading
     * at, and for the same reason: about a hundred ticks into a two-hundred-tick smelt, so a
     * one-tick misalignment between the two arms cannot move an item across a slot boundary.
     */
    private static final int SAVE_AT = 700;

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;

    /**
     * The alignment the arms are allowed to differ by. One arm is driven by the chunk's block
     * entity tick loop and the other by a catch-up started from a test callback; see
     * {@code ReadFaceGameTests#ALIGNMENT_SLACK}. Two ticks on the counters, nothing at all on
     * the item counts or on the recipe ledger.
     */
    private static final int ALIGNMENT_SLACK = 2;

    /** Ticked, because of the hopper against it. The control the saved tag is compared to. */
    private static final BlockPos TICKED = new BlockPos(2, 1, 2);
    /** Deferred, and saved. */
    private static final BlockPos SAVED = new BlockPos(6, 1, 6);
    /** Deferred, and never asked for by any save. */
    private static final BlockPos UNSAVED = new BlockPos(6, 1, 2);

    @BeforeBatch(batch = CHUNK_BATCH)
    public static void enableForChunkBatch(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[save] chunk batch begin | enabled={}", DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = CHUNK_BATCH)
    public static void endChunkBatch(ServerLevel level) {
        Meanwhile.LOGGER.info("[save] chunk batch end | enabled={}", DeferralScheduler.isEnabled());
    }

    @BeforeBatch(batch = BATCH)
    public static void enableScheduler(ServerLevel level) {
        DeferralScheduler.setEnabled(true);
        Meanwhile.LOGGER.info("[save] batch begin | enabled={}", DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        Meanwhile.LOGGER.info("[save] batch end | enabled={}", DeferralScheduler.isEnabled());
    }

    // ---- the hook fires on the real path -------------------------------------------------

    /**
     * Serialising the chunk a deferred furnace sits in has to produce the tag a furnace the
     * game ticked for the same span would produce.
     *
     * <p>Driven through {@code ChunkSerializer#write}, which is the whole of what
     * {@code ChunkMap#save} does with a chunk before handing the tag to the IO thread
     * (ChunkMap.java:790) — so this is the production path down to the file write, and the tag
     * it returns is the bytes.
     *
     * <p>Three things are asserted about that tag, and each of them is the only one of the
     * three that catches a particular way of being wrong:
     *
     * <ul>
     *   <li><b>against the ticked control.</b> Catches a hook that never fired, and equally a
     *       hook that fired after the tag was built. Unlike the comparator's number, a
     *       furnace's saved fields separate the two states wide open: seven hundred ticks of
     *       smelting move BurnTime, CookTime, all three slots and the recipe ledger.</li>
     *   <li><b>against the subject re-read afterwards.</b> States the ordering requirement
     *       directly rather than by its consequences: at HEAD the tag is what the subject is,
     *       at RETURN the subject has moved past what the tag says.</li>
     *   <li><b>the level's catch-up counter moved.</b> The only evidence the save reached this
     *       position at all. Get the chunk wrong and every other assertion here still holds on
     *       a subject nothing serialised.</li>
     * </ul>
     *
     * <p>And a precondition under all three: the subject and the control have to actually
     * disagree before the save, or there is no window to close and the agreement afterwards is
     * free.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = CHUNK_BATCH, timeoutTicks = 3000)
    public static void chunkSaveOfDeferredSubjectWritesTheSettledState(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        placeFurnace(helper, TICKED, true);
        placeFurnace(helper, SAVED, false);
        load(helper, TICKED);
        load(helper, SAVED);

        helper.runAfterDelay(SAVE_AT, () -> {
            ServerLevel level = helper.getLevel();
            HolderLookup.Provider registries = level.registryAccess();
            BlockPos savedPos = helper.absolutePos(SAVED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);

            if (!scheduler.isDeferred(savedPos)) {
                helper.fail("the furnace is not deferred at tick " + SAVE_AT + ", so the save"
                        + " has nothing to settle first");
                return;
            }

            CompoundTag controlTag = liveTag(helper, TICKED, registries);
            CompoundTag staleTag = liveTag(helper, SAVED, registries);
            if (controlTag == null || staleTag == null) {
                helper.fail("a furnace is gone before the save");
                return;
            }
            Furnace control = Furnace.of(controlTag, registries);
            Furnace stale = Furnace.of(staleTag, registries);
            if (stale.agreesWith(control, ALIGNMENT_SLACK)) {
                helper.fail("the deferred furnace already agreed with the ticked one before the"
                        + " save (" + stale + " against " + control + "), so there was no window"
                        + " for the save to close and the comparison below proves nothing");
                return;
            }

            int windowsBefore = scheduler.caughtUpWindows();
            long elapsedBefore = scheduler.caughtUpElapsedTicks();
            int realBefore = scheduler.caughtUpRealTicks();

            // The save itself. Everything below the bracket reads the tag this returned; the
            // scheduler is not touched, so the counters moving is the save's doing.
            LevelChunk chunk = level.getChunkAt(savedPos);
            CompoundTag chunkTag = ChunkSerializer.write(level, chunk);

            int windows = scheduler.caughtUpWindows() - windowsBefore;
            long elapsed = scheduler.caughtUpElapsedTicks() - elapsedBefore;
            int real = scheduler.caughtUpRealTicks() - realBefore;

            CompoundTag written = blockEntityTagAt(chunkTag, savedPos);
            if (written == null) {
                helper.fail("the chunk tag has no block entity at " + savedPos + ", so the save"
                        + " never serialised the subject and nothing below is about it");
                return;
            }
            CompoundTag after = liveTag(helper, SAVED, registries);
            if (after == null) {
                helper.fail("the furnace is gone after the save");
                return;
            }
            Furnace writtenFurnace = Furnace.of(written, registries);
            Meanwhile.LOGGER.info("[save] chunk save at tick {} | control {} | stale {} ->"
                            + " written {} | windows={} elapsed={} real={} deferred={}"
                            + " distrusted={}",
                    SAVE_AT, control, stale, writtenFurnace, windows, elapsed, real,
                    scheduler.isDeferred(savedPos), scheduler.isDistrusted(savedPos));

            if (windows == 0 || elapsed <= 0L) {
                helper.fail("the chunk save caught nothing up (windows " + windows + ", elapsed "
                        + elapsed + "), so it never reached the furnace — check the chunk the"
                        + " subject is in — and the agreement below would be about a furnace"
                        + " nothing had saved");
                return;
            }
            if (!writtenFurnace.agreesWith(control, ALIGNMENT_SLACK)) {
                helper.fail("the chunk save wrote a stale furnace: " + writtenFurnace
                        + " against a ticked one at " + control + " (it was " + stale
                        + " before the save), so the state on disk is the one the subject"
                        + " stopped ticking at");
                return;
            }
            // Ordering, said directly. The tag is built from the subject's fields, so a hook
            // that settles the account afterwards leaves these two apart with the world right.
            CompoundTag writtenWithoutSaveFlag = written.copy();
            writtenWithoutSaveFlag.remove("keepPacked");
            if (!writtenWithoutSaveFlag.equals(after)) {
                helper.fail("the tag the save produced is not what the subject is now (tag "
                        + writtenFurnace + ", subject " + Furnace.of(after, registries) + "), so"
                        + " the account was settled after the tag was built rather than before"
                        + " it and the bytes on disk were never true");
                return;
            }
            if (scheduler.isDistrusted(savedPos)) {
                helper.fail("the furnace came out distrusted from a save only its own catch-up"
                        + " reached, so the hook re-entered that catch-up through the block"
                        + " state it wrote and read the subject's own progress as interference");
                return;
            }
            if (!scheduler.isDeferred(savedPos)) {
                helper.fail("the furnace left the ledger after being saved, so the saving stops"
                        + " at the first autosave");
                return;
            }
            helper.succeed();
        });
    }

    // ---- liveness ------------------------------------------------------------------------

    /**
     * A deferred furnace no save asked for must stay where it was left.
     *
     * <p>The failure the comparison above is blind to. A hook that settles every deferred
     * subject on any save anywhere writes correct tags and is not a scheduler: it hands back
     * the ticks it saved, on a timer, forever. Two furnaces are deferred and only one position
     * is asked for, through the same method the serializer's loop calls
     * (ChunkSerializer.java:359-364), so the other one's state is the measurement — it has to
     * still be sitting exactly where it was set aside.
     *
     * <p>What this measures is a subject nobody asked to serialise, not a subject in a chunk
     * nobody saved. Those differ: a real chunk save legitimately settles every deferred subject
     * in that chunk, because every one of them is about to be written.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID, batch = BATCH, timeoutTicks = 3000)
    public static void unsavedDeferredSubjectIsLeftAlone(GameTestHelper helper) {
        if (schedulerOff(helper)) {
            return;
        }
        placeFurnace(helper, SAVED, false);
        placeFurnace(helper, UNSAVED, false);
        load(helper, SAVED);
        load(helper, UNSAVED);

        helper.runAfterDelay(SAVE_AT, () -> {
            ServerLevel level = helper.getLevel();
            HolderLookup.Provider registries = level.registryAccess();
            BlockPos savedPos = helper.absolutePos(SAVED);
            BlockPos unsavedPos = helper.absolutePos(UNSAVED);
            DeferralScheduler scheduler = DeferralScheduler.of(level);

            if (!scheduler.isDeferred(savedPos) || !scheduler.isDeferred(unsavedPos)) {
                helper.fail("both furnaces have to be deferred for this to measure anything"
                        + " (saved=" + scheduler.isDeferred(savedPos)
                        + " unsaved=" + scheduler.isDeferred(unsavedPos) + ")");
                return;
            }

            int windowsBefore = scheduler.caughtUpWindows();
            LevelChunk chunk = level.getChunkAt(savedPos);
            CompoundTag written = chunk.getBlockEntityNbtForSaving(savedPos, registries);
            int windows = scheduler.caughtUpWindows() - windowsBefore;

            AbstractFurnaceBlockEntity unsaved = furnace(helper, UNSAVED);
            if (written == null || unsaved == null) {
                helper.fail("a furnace is gone");
                return;
            }
            Furnace writtenFurnace = Furnace.of(written, registries);
            Meanwhile.LOGGER.info("[save] one of two saved at tick {} | written {} | unsaved {}"
                            + " | windows={} unsavedStillDeferred={}",
                    SAVE_AT, writtenFurnace, describe(helper, UNSAVED), windows,
                    scheduler.isDeferred(unsavedPos));

            if (windows != 1) {
                helper.fail("the save caught up " + windows + " windows where exactly one"
                        + " position was asked for, so either it never reached its furnace or"
                        + " it settled a subject nothing was saving");
                return;
            }
            if (writtenFurnace.output() == 0) {
                helper.fail("the furnace that was saved was written empty-handed ("
                        + writtenFurnace + "), so this is not comparing a settled subject"
                        + " against an untouched one");
                return;
            }
            // The whole point. Untouched means untouched: no fuel burnt, no progress, nothing
            // smelted, and still on the ledger so the next window keeps accruing.
            if (unsaved.litTime != 0 || unsaved.cookingProgress != 0
                    || unsaved.getItem(0).getCount() != INPUT_COUNT
                    || unsaved.getItem(1).getCount() != FUEL_COUNT
                    || !unsaved.getItem(2).isEmpty()) {
                helper.fail("the furnace no save asked for was settled anyway ("
                        + describe(helper, UNSAVED) + "), so the hook fires on saves that never"
                        + " serialise its subject and the saving is given back");
                return;
            }
            if (!scheduler.isDeferred(unsavedPos)) {
                helper.fail("the furnace no save asked for left the ledger");
                return;
            }
            helper.succeed();
        });
    }

    // ---- what one furnace looks like on disk -----------------------------------------------

    /**
     * Everything {@code AbstractFurnaceBlockEntity#saveAdditional} writes
     * (AbstractFurnaceBlockEntity.java:267-276), read back out of the tag.
     *
     * <p>Read from NBT rather than from the block entity on purpose: the block entity is the
     * thing that is right either way, and the tag is the thing that is not.
     * {@code litDuration} is absent because vanilla does not save it — it is recomputed from
     * the fuel on load — so a comparison over it would be about a field no restart preserves.
     */
    private record Furnace(int burnTime, int cookTime, int cookTimeTotal,
                           int input, int fuel, int output, int recipesUsed) {

        static Furnace of(CompoundTag tag, HolderLookup.Provider registries) {
            NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items, registries);
            CompoundTag used = tag.getCompound("RecipesUsed");
            int total = 0;
            for (String key : used.getAllKeys()) {
                total += used.getInt(key);
            }
            return new Furnace(
                    tag.getInt("BurnTime"),
                    tag.getInt("CookTime"),
                    tag.getInt("CookTimeTotal"),
                    items.get(0).getCount(),
                    items.get(1).getCount(),
                    items.get(2).getCount(),
                    total);
        }

        /**
         * @param slack ticks the two counters may differ by, for the tick the two arms cannot
         *              be aligned to. The item counts and the recipe ledger get none: a stale
         *              subject is behind by a whole window, not by one tick, and the save point
         *              sits a hundred ticks clear of the boundary where one tick could move an
         *              item.
         */
        boolean agreesWith(Furnace other, int slack) {
            return cookTimeTotal == other.cookTimeTotal
                    && input == other.input
                    && fuel == other.fuel
                    && output == other.output
                    && recipesUsed == other.recipesUsed
                    && Math.abs(burnTime - other.burnTime) <= slack
                    && Math.abs(cookTime - other.cookTime) <= slack;
        }

        @Override
        public String toString() {
            return "BurnTime=" + burnTime + " CookTime=" + cookTime
                    + " CookTimeTotal=" + cookTimeTotal + " in=" + input + " fuel=" + fuel
                    + " out=" + output + " recipes=" + recipesUsed;
        }
    }

    /**
     * The subject serialised without going through the save face, for the two comparisons that
     * need to know what it is rather than what was written about it.
     *
     * <p>{@code saveWithFullMetadata} is the same call {@code getBlockEntityNbtForSaving} makes
     * (LevelChunk.java:416-421), minus the {@code keepPacked} flag that method adds afterwards.
     */
    @Nullable
    private static CompoundTag liveTag(GameTestHelper helper, BlockPos pos,
                                       HolderLookup.Provider registries) {
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
        return furnace == null ? null : furnace.saveWithFullMetadata(registries);
    }

    /** The entry in a chunk tag's {@code block_entities} list for one position. */
    @Nullable
    private static CompoundTag blockEntityTagAt(CompoundTag chunkTag, BlockPos pos) {
        ListTag list = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (tag.getInt("x") == pos.getX() && tag.getInt("y") == pos.getY()
                    && tag.getInt("z") == pos.getZ()) {
                return tag;
            }
        }
        return null;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String describe(GameTestHelper helper, BlockPos pos) {
        AbstractFurnaceBlockEntity furnace = furnace(helper, pos);
        if (furnace == null) {
            return "(no furnace)";
        }
        return "litTime=" + furnace.litTime + " progress=" + furnace.cookingProgress
                + " in=" + furnace.getItem(0).getCount() + " fuel=" + furnace.getItem(1).getCount()
                + " out=" + furnace.getItem(2).getCount();
    }

    private static boolean schedulerOff(GameTestHelper helper) {
        if (DeferralScheduler.isEnabled()) {
            return false;
        }
        helper.fail("the scheduler is off, so the batch hook that turns it on did not run"
                + " and none of these tests measure anything");
        return true;
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
