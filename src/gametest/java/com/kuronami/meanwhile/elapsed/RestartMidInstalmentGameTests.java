package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * A chunk saved half way through an instalment does not hand the front of its walk the same
 * slice twice.
 *
 * <h3>The state this is about</h3>
 * <p>An instalment is one payment of the chunk's debt however many level ticks it is spread
 * over, and {@link ChunkCatchUp#pay} moves the balance exactly once, at the end. So between the
 * level tick where the budget runs out and the one where the walk finishes, the chunk is in a
 * state where <b>the machines at the front of the walk have been advanced and the balance says
 * nothing was paid</b>. The resume position that reconciles the two used to live only in
 * {@code Pending}, which is a static map.
 *
 * <p>Save the chunk there, stop the server, start it again: the advanced machines come back
 * advanced, the full balance comes back with them, and the resume position is gone. The next
 * instalment starts at the front of the walk and offers those machines the same slice a second
 * time. Over-advancing is the one direction this design forbids, and unlike the ordinary
 * failures it leaves nothing behind to notice — no exception, no counter, and a debt that
 * settles to zero exactly as it should (GAP_LOG G172 ruling 52).
 *
 * <h3>What is asserted, and why it is a ceiling rather than a comparison</h3>
 * <p>The subject is four furnaces the game does not tick, so every item in them was put there by
 * a catch-up window and by nothing else. The chunk is told it is behind twice, {@link #WINDOW}
 * ticks each time, with the walk interrupted between the two, and no furnace may end up holding
 * more than {@code 2 * WINDOW} ticks' worth of smelting. That is arithmetic on what the chunk was
 * owed rather than one furnace measured against another: a furnace short of its window is this
 * mod being wrong in the direction it is allowed to be, and a comparison between machines would
 * fail on that as loudly as on the defect.
 *
 * <p>Which of the four the walk stops on is not fixed. GameTest arenas are 9 wide and chunks are
 * 16, so the walk covers whatever else the suite left in this chunk as well, and the stopping
 * point moves with it. The drive waits for a state where at least one and not all of this test's
 * furnaces have been carried, and fails saying so if it never sees one — the ceiling would
 * otherwise pass on a run that never reproduced the state it is about.
 *
 * <h3>The restart, and why the tag is read</h3>
 * <p>{@link CatchUpTestAccess#dropInFlightState} drops the queue and the half-built bookkeeping
 * for this arena and touches nothing on the chunks, which is what a restart leaves. On its own
 * that only shows the resume position is read back from an attachment — an attachment that was
 * never written to disk would pass it, and a resume position that does not survive a save is the
 * defect again one layer down. So before the drop, the chunk is put through
 * {@code ChunkSerializer#write}, the same call the save makes, and the position is looked for in
 * the tag it produces.
 */
public final class RestartMidInstalmentGameTests {

    /** Furnaces this test puts in the measured chunk. */
    private static final int MACHINES = 4;

    /**
     * The window each absence is worth. Both absences are the same size, so what the chunk is
     * owed in total is exactly twice this.
     */
    private static final int WINDOW = ChunkCatchUp.SLICE_TICKS;

    /** Ticks the chunk is owed across the whole test, over both absences. */
    private static final int TOTAL = 2 * WINDOW;

    /** Ticks a furnace takes over one iron ingot. Vanilla's {@code cookingTotalTime}. */
    private static final int SMELT_TICKS = 200;

    /**
     * The most any one furnace may hold at the end.
     *
     * <p>Arithmetic, not a tolerance: {@link #TOTAL} ticks of smelting is {@code TOTAL /
     * SMELT_TICKS} ingots and a furnace that starts cold gets fewer, because the tick that lights
     * it is not a tick it cooks in. A furnace handed one extra {@link #WINDOW} — which is what
     * the lost resume position costs it — clears this by half the ceiling again.
     */
    private static final int OUTPUT_CEILING = TOTAL / SMELT_TICKS;

    /** Ticks the fake clock adds per reading. */
    private static final long CLOCK_STEP = 250_000L;

    /**
     * The time budget the drain is measured against, in the units of the fake clock.
     *
     * <p>Two steps, which is the smallest budget that pays anything at all: the drain reads the
     * clock once to start, once to enter the loop, and once before each machine after the first.
     * At two steps the third reading is already over, so <b>exactly one machine is carried per
     * level tick</b>. That is what makes the interruption land inside this test's own furnaces
     * rather than skipping past them — the drive sees every intermediate state there is.
     */
    private static final long FAKE_BUDGET = CLOCK_STEP * 2L;

    /** Enough to be behind at all; the window itself is fixed rather than taken from this. */
    private static final int STALE_BY = 100;

    private static final int SETTLE = 3;
    /** Level ticks the drive is given. One machine is carried per tick, and the chunk is shared. */
    private static final int WATCH = 2000;

    private static final int INPUT_COUNT = 64;
    private static final int FUEL_COUNT = 64;

    private RestartMidInstalmentGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "restart", timeoutTicks = 2400)
    public static void aRestartMidInstalmentDoesNotPayTheFrontOfTheWalkTwice(
            GameTestHelper helper) {
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed, so this run measures nothing");
            return;
        }
        Drive drive = new Drive(helper);
        helper.startSequence()
                .thenExecuteFor(SETTLE + WATCH, drive::step)
                .thenExecute(drive::judge)
                .thenSucceed();
    }

    private static final class Drive {

        private enum Step { SETTLING, BUILD, OFFSET, ARM, PAYING, OFFSET_AGAIN, ARM_AGAIN,
            SETTLING_UP, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos chunk;
        private final List<BlockPos> subjects;

        private Step step = Step.SETTLING;
        private int countdown = SETTLE;
        private int waited;
        @Nullable
        private SteppingClock clock;
        @Nullable
        private String failure;

        /** How many of this test's furnaces had been carried when the restart was staged. */
        private int carriedAtRestart = -1;
        /** The resume position the chunk was holding at that moment. */
        private long markAtRestart = Long.MIN_VALUE;
        /** Whether the tag the save produces carried that position. */
        private boolean markWasInTheTag;
        private final Map<BlockPos, Integer> outputAtRestart = new LinkedHashMap<>();

        private Drive(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.chunk = busiest(helper);
            this.subjects = spots(helper, chunk);
        }

        /**
         * The arena chunk holding the most of this test's interior positions.
         *
         * <p>Not the chunk the structure block is in. A 9-wide arena at an arbitrary offset
         * straddles a 16-wide chunk, and taking the corner's chunk makes how many of the placed
         * machines the drain is measured on a property of where the run put the arena
         * (GAP_LOG G151).
         */
        private static ChunkPos busiest(GameTestHelper helper) {
            Map<ChunkPos, Integer> counts = new HashMap<>();
            for (BlockPos pos : interior(helper)) {
                counts.merge(new ChunkPos(pos), 1, Integer::sum);
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(new ChunkPos(helper.absolutePos(BlockPos.ZERO)));
        }

        private static List<BlockPos> interior(GameTestHelper helper) {
            List<BlockPos> all = new ArrayList<>();
            for (int x = 1; x < 8; x++) {
                for (int z = 1; z < 8; z++) {
                    all.add(helper.absolutePos(new BlockPos(x, 1, z)));
                }
            }
            return all;
        }

        /** The first {@link #MACHINES} interior positions that land in the measured chunk. */
        private static List<BlockPos> spots(GameTestHelper helper, ChunkPos chunk) {
            List<BlockPos> spots = new ArrayList<>();
            for (BlockPos pos : interior(helper)) {
                if (spots.size() >= MACHINES) {
                    break;
                }
                if (new ChunkPos(pos).equals(chunk)) {
                    spots.add(pos);
                }
            }
            spots.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
            return spots;
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
                }
                case OFFSET -> step = Step.ARM;
                case ARM -> {
                    ChunkClock.setStampOffset(chunk, 0L);
                    ChunkClock.rearm(level, chunk);
                    waited = 0;
                    step = Step.PAYING;
                }
                case PAYING -> pay();
                case OFFSET_AGAIN -> step = Step.ARM_AGAIN;
                case ARM_AGAIN -> {
                    ChunkClock.setStampOffset(chunk, 0L);
                    ChunkClock.rearm(level, chunk);
                    waited = 0;
                    step = Step.SETTLING_UP;
                }
                case SETTLING_UP -> settleUp();
                case DONE -> {
                }
            }
        }

        /**
         * Four furnaces the game will not tick, so that every item in one of them came from a
         * catch-up window.
         *
         * <p>The block entity is replaced after the block is placed. The chunk's ticker list
         * still points at the one the block placement made, so the game runs that one and this
         * one is never reached; the catch-up resolves its ticker off the block state and reaches
         * it perfectly well. The same construction {@code ScaffoldGameTests} uses.
         */
        private void build() {
            if (subjects.size() < MACHINES) {
                fail("only " + subjects.size() + " of this test's positions landed in " + chunk
                        + ", so there is no walk to interrupt in the middle of");
                return;
            }
            for (BlockPos pos : subjects) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
                BlockState state = level.getBlockState(pos);
                LevelChunk holder = level.getChunkAt(pos);
                holder.setBlockEntity(new FurnaceBlockEntity(pos, state));
                if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                    fail("the hand-placed furnace at " + pos.toShortString() + " is not there");
                    return;
                }
                furnace.litTime = 0;
                furnace.litDuration = 0;
                furnace.cookingProgress = 0;
                furnace.cookingTotalTime = 0;
                furnace.recipesUsed.clear();
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));
                furnace.setItem(1, new ItemStack(Items.COAL, FUEL_COUNT));
                furnace.setItem(2, ItemStack.EMPTY);
                furnace.setChanged();
            }

            // A clean chunk to start from: whatever the suite owed this arena before now is not
            // part of what this test is counting.
            CatchUpTestAccess.forget(helper, level);
            // Both axes pinned to this test's own configuration, in the order setBudget's
            // contract requires: it puts the time budget out of the way for callers driving the
            // work axis, so the clock goes in second.
            ChunkCatchUp.setBudget(ChunkCatchUp.SLICE_TICKS, ChunkCatchUp.BUDGET_REAL_TICKS);
            clock = new SteppingClock();
            ChunkCatchUp.setBudgetNanos(FAKE_BUDGET, clock);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT.withFixedWindow(WINDOW));
            ChunkClock.setStampOffset(chunk, -STALE_BY);
            Meanwhile.LOGGER.info("[restart] armed | chunk={} subjects={} window={} slice={}"
                            + " ceiling={} items",
                    chunk, describe(), WINDOW, ChunkCatchUp.SLICE_TICKS, OUTPUT_CEILING);
            step = Step.OFFSET;
        }

        /**
         * Waits for the walk to stop inside this test's furnaces, then stages the restart.
         *
         * <p>One machine is carried per level tick, so every intermediate state is visible from
         * here and the first one that qualifies is taken.
         */
        private void pay() {
            if (++waited > WATCH / 2) {
                fail("the instalment never stopped inside this test's furnaces: carried="
                        + carried() + " of " + MACHINES + ", debt="
                        + ChunkCatchUp.debtFor(level, chunk) + " after " + waited + " ticks");
                return;
            }
            if (ChunkCatchUp.debtFor(level, chunk) != WINDOW) {
                // Nothing is owed yet, or the whole instalment has already settled. The second
                // is a failure of this test's setup and is caught by the timeout above rather
                // than here: a settled debt with every furnace carried is a run that never
                // reproduced the state, and saying which of the two it was needs the counts.
                return;
            }
            int carried = carried();
            if (carried == 0 || carried >= MACHINES) {
                return;
            }

            // The mark this whole change is about, read off the chunk.
            markAtRestart = CatchUpTestAccess.paidUpToFor(level, chunk);
            if (markAtRestart == Long.MIN_VALUE) {
                fail("the walk stopped after " + carried + " of this test's " + MACHINES
                        + " furnaces and the chunk is holding no resume position, so a restart"
                        + " here would start the next instalment at the front of the walk and"
                        + " hand those furnaces the same slice again");
                return;
            }
            // A position read back out of a field proves the field is read, not that it
            // survives. This is the call the chunk save makes.
            markWasInTheTag = markIsInTheSavedTag(markAtRestart);
            if (!markWasInTheTag) {
                fail("the resume position " + BlockPos.of(markAtRestart).toShortString()
                        + " is not in the tag ChunkSerializer#write produced for " + chunk
                        + ", so it is held in memory only and a restart loses it exactly as"
                        + " before");
                return;
            }

            carriedAtRestart = carried;
            for (BlockPos pos : subjects) {
                outputAtRestart.put(pos, output(pos));
            }
            Meanwhile.LOGGER.info("[restart] interrupted | chunk={} carried={} of {} debt={}"
                            + " mark={} inSavedTag={} | {}",
                    chunk, carried, MACHINES, ChunkCatchUp.debtFor(level, chunk),
                    BlockPos.of(markAtRestart).toShortString(), markWasInTheTag, describe());

            // The restart. The queue and the half-built bookkeeping go; the chunk keeps its
            // balance and its resume position, which is what the save wrote out.
            CatchUpTestAccess.dropInFlightState(helper, level);
            ChunkClock.setStampOffset(chunk, -STALE_BY);
            step = Step.OFFSET_AGAIN;
        }

        /** Waits for both absences to be paid off in full. */
        private void settleUp() {
            if (++waited > WATCH / 2) {
                fail("the chunk still owed " + ChunkCatchUp.debtFor(level, chunk)
                        + " of " + TOTAL + " after " + waited + " ticks | " + describe());
                return;
            }
            if (ChunkCatchUp.debtFor(level, chunk) != 0L) {
                return;
            }
            step = Step.DONE;
        }

        /**
         * Whether the position is in the tag the chunk save writes.
         *
         * <p>{@code ChunkSerializer#write} is what {@code ChunkMap} calls, and the attachment
         * block it produces is the one a reload reads back.
         */
        private boolean markIsInTheSavedTag(long mark) {
            LevelChunk holder = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            if (holder == null) {
                return false;
            }
            CompoundTag chunkTag = ChunkSerializer.write(level, holder);
            CompoundTag attachments =
                    chunkTag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
            String key = Meanwhile.MODID + ":catch_up_paid_up_to";
            Meanwhile.LOGGER.info("[restart] saved tag | chunk={} attachments={} {}={}",
                    chunk, attachments.getAllKeys(), key,
                    attachments.contains(key) ? attachments.getLong(key) : "<absent>");
            return attachments.contains(key) && attachments.getLong(key) == mark;
        }

        /** How many of this test's furnaces have been given a window. */
        private int carried() {
            int carried = 0;
            for (BlockPos pos : subjects) {
                if (output(pos) > 0) {
                    carried++;
                }
            }
            return carried;
        }

        private int output(BlockPos pos) {
            return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                    ? furnace.getItem(2).getCount()
                    : -1;
        }

        private String describe() {
            StringBuilder text = new StringBuilder();
            for (BlockPos pos : subjects) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(pos.toShortString()).append('=').append(output(pos));
            }
            return text.toString();
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            // Read before the teardown, not after. restore() takes the furnaces out, and a
            // reading taken from a position that no longer holds one is -1 for every subject:
            // below the ceiling, so the gate passes having measured nothing.
            List<Integer> outputs = new ArrayList<>();
            for (BlockPos pos : subjects) {
                outputs.add(output(pos));
            }
            String finalState = describe();
            restore();
            if (failure != null) {
                helper.fail(failure);
                return;
            }

            int highest = outputs.stream().mapToInt(Integer::intValue).max().orElse(-1);
            int lowest = outputs.stream().mapToInt(Integer::intValue).min().orElse(-1);
            Meanwhile.LOGGER.info("[restart] RESULT chunk={} owed={} ceiling={} | carried at the"
                            + " restart={} of {} mark={} inSavedTag={} | outputs {} -> {}"
                            + " | lowest={} highest={}",
                    chunk, TOTAL, OUTPUT_CEILING, carriedAtRestart, MACHINES,
                    markAtRestart == Long.MIN_VALUE
                            ? "<none>" : BlockPos.of(markAtRestart).toShortString(),
                    markWasInTheTag, outputAtRestart.values(), outputs, lowest, highest);

            if (carriedAtRestart <= 0 || carriedAtRestart >= MACHINES) {
                helper.fail("the restart was staged with " + carriedAtRestart + " of " + MACHINES
                        + " furnaces carried, so the walk was not interrupted inside this test's"
                        + " own machines and the ceiling below says nothing about resuming");
                return;
            }
            if (lowest <= 0) {
                helper.fail("a furnace holds " + lowest + " after being owed " + TOTAL
                        + " ticks, so nothing was spent on it and the ceiling below is not"
                        + " evidence about anything: " + describe());
                return;
            }
            if (highest > OUTPUT_CEILING) {
                helper.fail("a furnace holds " + highest + " ingots where " + TOTAL
                        + " ticks of smelting is at most " + OUTPUT_CEILING + ", so it was"
                        + " advanced by more than its chunk was ever owed — the instalment that"
                        + " was interrupted at " + BlockPos.of(markAtRestart).toShortString()
                        + " paid the front of the walk a second time: " + finalState);
                return;
            }
            helper.succeed();
        }

        private void restore() {
            step = Step.DONE;
            ChunkClock.setStampOffset(chunk, 0L);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.restoreBudget();
            CatchUpTestAccess.forget(helper, level);
            for (BlockPos pos : subjects) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
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
}
