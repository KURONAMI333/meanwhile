package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * A furnace whose fire goes out inside the window, compared on the wider surface.
 *
 * <p>{@link WideStateDigest} looks at block states as well as block entities, and the reason it
 * does is a failure that only block states can show: carrying {@code BurnTime} down to zero by
 * arithmetic while leaving the block's {@code lit} property true. The tag says the fire is out,
 * the world says it is burning, and every comparison made on the tag alone agrees with itself.
 *
 * <p>The millstone the main gate uses cannot exercise that. A millstone's block state never
 * changes, so a comparison of it reports nothing about block states whether they are right or
 * wrong. This puts a subject in front of the instrument that does change: the furnace is lit with
 * enough fuel to burn out partway through the window and no more fuel to relight from, so
 * {@code lit} goes from true to false while the window is being spent, and the transition is
 * asserted rather than assumed.
 *
 * <p>Same shape as the main gate: three round trips, two of them identical, so that a difference
 * between the catch-up and real ticking is measured against how far two identical real-ticking
 * trips already differ.
 *
 * <p>Only the arena's own bounds are compared, for the reason set out on {@link WideStateDigest}:
 * anything pushed across a chunk boundary is outside what can be measured here.
 */
public final class FurnaceWideGameTests {

    private static final BlockPos FURNACE = new BlockPos(3, 1, 3);

    /** Long enough to contain a completed smelt and the fire going out. */
    private static final int WINDOW = 900;
    /** Fuel left on the clock when the window starts, so the fire dies partway through it. */
    private static final int LIT_TICKS = 300;
    private static final int COOK_TOTAL = 200;
    private static final int INPUT_COUNT = 8;

    private static final int SETTLE = 5;
    private static final int UNLOAD_WAIT = 200;
    private static final int BACK_WAIT = 200;
    private static final int GONE_FOR = 60;

    private FurnaceWideGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "furnacewide", timeoutTicks = 8000)
    public static void furnaceGoingOutIsReproducedIncludingItsBlockState(GameTestHelper helper) {
        RoundTripImages.install();
        com.kuronami.meanwhile.generic.GenericCatchUp.setRewindDistinction(!rawTurnoverRequested());
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed; write meanwhile-catchup.properties");
            return;
        }
        Trip trip = new Trip(helper);
        helper.startSequence()
                .thenExecuteFor(7200, trip::step)
                .thenExecute(trip::judge)
                .thenSucceed();
    }

    /**
     * Does an item entity survive a chunk going away and coming back?
     *
     * <p>Asked on its own, with no catch-up and no comparison, because three attempts to make the
     * entity surface of {@link WideStateDigest} report a synthetic difference produced nothing and
     * the reason was not established. Either an item put in the arena is still there after the
     * round trip — in which case the surface ought to be able to see it and the earlier failures
     * have some other cause — or it is not, in which case nothing in this harness can move that
     * surface and saying so is the honest end of it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "entitysurvival", timeoutTicks = 2400)
    public static void itemEntitySurvivesAChunkRoundTrip(GameTestHelper helper) {
        RoundTripImages.install();
        Survival probe = new Survival(helper);
        helper.startSequence()
                .thenExecuteFor(1800, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    private static final class Survival {

        private enum Step { DROPPING, WAITING, RELEASED, GONE, BACK, COUNTING, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final BlockPos pos;
        private final List<ChunkPos> arena;
        private BoundingBox box = BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO);

        private Step step = Step.DROPPING;
        private int countdown = 3;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        private int before = -1;
        private int after = -1;
        @Nullable
        private String failure;

        private Survival(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(FURNACE);
            this.target = new ChunkPos(pos);
            this.arena = UnloadedCatchUpGameTests.arenaChunks(helper);
        }

        private int count() {
            return level.getEntities((net.minecraft.world.entity.Entity) null,
                    net.minecraft.world.phys.AABB.of(box), e -> e instanceof ItemEntity).size();
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case DROPPING -> {
                    box = UnloadedCatchUpGameTests.arenaBox(helper);
                    ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5,
                            pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(Items.STICK, 3));
                    boolean added = level.addFreshEntity(dropped);
                    Meanwhile.LOGGER.info("[survival] dropped | added={} at={} box={}",
                            added, pos.toShortString(), box);
                    step = Step.WAITING;
                }
                case WAITING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    before = count();
                    Meanwhile.LOGGER.info("[survival] before the round trip | items={}", before);
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("no ChunkEvent.Unload in " + UNLOAD_WAIT);
                    }
                }
                case GONE -> {
                    if (now - unloadAt < GONE_FOR) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    askedAt = now;
                    countdown = 5;
                    step = Step.BACK;
                }
                case BACK -> {
                    if (level.getChunkSource().getChunkNow(target.x, target.z) == null) {
                        if (now - askedAt > BACK_WAIT) {
                            fail("the chunk did not come back");
                        }
                        return;
                    }
                    if (--countdown > 0) {
                        return;
                    }
                    step = Step.COUNTING;
                }
                case COUNTING -> {
                    after = count();
                    Meanwhile.LOGGER.info("[survival] RESULT | itemsBefore={} itemsAfter={}"
                                    + " survived={} unloadAt={} loadAt={}",
                            before, after, after >= before && before > 0, unloadAt,
                            RoundTripImages.loadAt());
                    step = Step.DONE;
                }
                default -> {
                }
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            RoundTripImages.stopWatching();
            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (before <= 0) {
                helper.fail("the item was not in the arena even before the round trip, so this"
                        + " measures nothing: before=" + before);
                return;
            }
            // Whether it survives is the measurement, not a requirement. Both answers are
            // recorded above; the test only insists the question was actually asked.
            helper.succeed();
        }
    }

    /** One arm's readings. */
    private record Arm(String name, String hash, List<String> lines, boolean litAtStart,
                       boolean litAtWindowStart, int burnAtWindowStart, boolean litAtEnd,
                       int burnAtEnd) {
    }

    private static final class Trip {

        private enum Step { SETTLING, RESET, RELEASED, GONE, BACK, DONE }

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final BlockPos pos;
        private final List<ChunkPos> arena;
        private BoundingBox box = BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO);

        private Step step = Step.SETTLING;
        private int countdown = SETTLE;
        private int index;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        private final Arm[] arms = new Arm[3];
        private boolean litAtStart;
        @Nullable
        private String failure;

        private Trip(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(FURNACE);
            this.target = new ChunkPos(pos);
            this.arena = UnloadedCatchUpGameTests.arenaChunks(helper);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    box = UnloadedCatchUpGameTests.arenaBox(helper);
                    step = Step.RESET;
                }
                case RESET -> {
                    arm();
                    // Dropped here rather than in the sweep: an entity added during a level tick
                    // is queued and is not returned by getEntities until the next one, so an item
                    // spawned at the moment of capture is invisible to the very reading it is
                    // meant to disturb (measured: addFreshEntity=true, inBoxNow=0).
                    if (index == 2 && spawnItemRequested()) {
                        ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5,
                                pos.getY() + 1.0, pos.getZ() + 0.5, new ItemStack(Items.STICK));
                        boolean added = level.addFreshEntity(dropped);
                        Meanwhile.LOGGER.warn("[furnacewide] NC | one item entity dropped into"
                                + " the arena on this arm only | added={}", added);
                    }
                    litAtStart = level.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
                    ChunkCatchUp.setMode((index < 2
                            ? ChunkCatchUp.Mode.TICK_INTERLEAVED
                            : ChunkCatchUp.Mode.PRODUCT)
                            .restrictedTo(pos).withFixedWindow(WINDOW));
                    ChunkCatchUp.setObserver(new Capture());
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("arm " + index + ": no ChunkEvent.Unload in " + UNLOAD_WAIT);
                    }
                }
                case GONE -> {
                    if (now - unloadAt < GONE_FOR) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    askedAt = now;
                    step = Step.BACK;
                }
                case BACK -> {
                    if (arms[index] != null) {
                        ChunkCatchUp.setObserver(null);
                        if (++index >= arms.length) {
                            step = Step.DONE;
                            return;
                        }
                        step = Step.RESET;
                        return;
                    }
                    if (now - askedAt > BACK_WAIT) {
                        fail("arm " + index + ": the chunk came back at " + askedAt
                                + " but nothing spent its window");
                    }
                }
                default -> {
                }
            }
        }

        /** Lit, part-burned, with nothing left to relight from. */
        private void arm() {
            BlockState lit = Blocks.FURNACE.defaultBlockState()
                    .setValue(AbstractFurnaceBlock.LIT, true);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, lit, 3);
            if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                fail("no furnace block entity at " + FURNACE);
                return;
            }
            furnace.litTime = LIT_TICKS;
            furnace.litDuration = 1600;
            furnace.cookingProgress = 0;
            furnace.cookingTotalTime = COOK_TOTAL;
            furnace.recipesUsed.clear();
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));
            furnace.setItem(1, ItemStack.EMPTY);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
        }

        /** Reads the arena the moment the window is finished, before anything runs on. */
        private final class Capture implements ChunkCatchUp.Observer {

            private boolean litAtWindowStart;
            private int burnAtWindowStart = -1;

            /**
             * Read at the moment the window opens, not before the chunk was dropped.
             *
             * <p>What has to be inside the window is the fire going out. Reading the state before
             * the round trip only says it was lit then; the ticks between arming and the window
             * opening are real ticks, and if the fire went out during them the window contains
             * nothing to reproduce and a match across block states means nothing.
             */
            @Override
            public void beforeSweep(ServerLevel swept, LevelChunk chunk, int dispatched,
                                    List<BlockPos> positions) {
                if (arms[index] != null || chunk.getPos().toLong() != target.toLong()) {
                    return;
                }
                litAtWindowStart = level.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
                burnAtWindowStart =
                        level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                                ? furnace.litTime : -1;
            }

            @Override
            public void afterSweep(ServerLevel swept, LevelChunk chunk, ChunkCatchUp.Sweep sweep) {
                if (arms[index] != null || chunk.getPos().toLong() != target.toLong()) {
                    return;
                }
                // The negative control, applied only to the arm under test: put the block state
                // back to lit while leaving the block entity exactly as the catch-up left it.
                // Nothing that reads the tag can tell; only a comparison that reads the world can.
                if (index == 2 && desyncRequested()) {
                    BlockState state = level.getBlockState(pos);
                    boolean was = state.getValue(AbstractFurnaceBlock.LIT);
                    level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, !was), 3);
                    Meanwhile.LOGGER.warn("[furnacewide] NC | block state lit {} -> {} with the"
                            + " block entity untouched", was, !was);
                }
                if (index == 2 && scheduleTickRequested()) {
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 40);
                    Meanwhile.LOGGER.warn("[furnacewide] NC | one block tick booked at {} on this"
                            + " arm only", pos.toShortString());
                }
                WideStateDigest wide = WideStateDigest.capture(level, box);
                boolean litNow = level.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
                int burn = level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                        ? furnace.litTime : -1;
                arms[index] = new Arm(switch (index) {
                    case 0 -> "B1a(round trip + real ticks)";
                    case 1 -> "B1b(round trip + real ticks, again)";
                    default -> "B2(round trip + catch-up)";
                }, wide.sha256(), wide.lines(), litAtStart, litAtWindowStart, burnAtWindowStart,
                        litNow, burn);
                Meanwhile.LOGGER.info("[furnacewide] {} | hash={} litAtArm={} litAtWindowStart={}"
                                + " burnAtWindowStart={} litAtEnd={} burnAtEnd={} realTicks={}"
                                + " jumps={} jumpedTicks={} | {}",
                        arms[index].name(), wide.sha256(), litAtStart, litAtWindowStart,
                        burnAtWindowStart, litNow, burn, sweep.realTicks(), sweep.jumped(),
                        sweep.jumpedTicks(), wide.shape());
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            ChunkCatchUp.setObserver(null);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(level);
            RoundTripImages.stopWatching();

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            for (int i = 0; i < arms.length; i++) {
                if (arms[i] == null) {
                    helper.fail("arm " + i + " never finished");
                    return;
                }
            }
            Arm b1a = arms[0];
            Arm b1b = arms[1];
            Arm b2 = arms[2];

            List<String> noise = differences(b1a.lines(), b1b.lines());
            List<String> signal = differences(b1a.lines(), b2.lines());
            List<String> beyond = new ArrayList<>(signal);
            beyond.removeAll(noise);
            List<String> beyondBlockStates = new ArrayList<>();
            List<String> beyondEntities = new ArrayList<>();
            List<String> beyondScheduled = new ArrayList<>();
            for (String line : beyond) {
                if (line.contains(": block ")) {
                    beyondBlockStates.add(line);
                } else if (line.contains(": entity ")) {
                    beyondEntities.add(line);
                } else if (line.contains(": blocktick ") || line.contains(": fluidtick ")) {
                    beyondScheduled.add(line);
                }
            }

            Meanwhile.LOGGER.info("[furnacewide] RESULT | hashes B1a={} B1b={} B2={} match={}"
                            + " noise={} signal={} beyond={} beyondBlockStates={}"
                            + " beyondEntities={} beyondScheduledTicks={}",
                    b1a.hash(), b1b.hash(), b2.hash(), b1a.hash().equals(b2.hash()),
                    noise.size(), signal.size(), beyond.size(), beyondBlockStates.size(),
                    beyondEntities.size(), beyondScheduled.size());
            for (String line : beyond.subList(0, Math.min(8, beyond.size()))) {
                Meanwhile.LOGGER.info("[furnacewide] beyond-noise | {}", line);
            }

            // The transition has to have happened, or the block state surface was never asked a
            // question and a match across it says nothing.
            for (Arm arm : arms) {
                Meanwhile.LOGGER.info("[furnacewide] lit | {} atArm={} atWindowStart={}"
                                + " burnAtWindowStart={} atEnd={} burnAtEnd={}",
                        arm.name(), arm.litAtStart(), arm.litAtWindowStart(),
                        arm.burnAtWindowStart(), arm.litAtEnd(), arm.burnAtEnd());
            }
            if (!b1a.litAtWindowStart() || b1a.litAtEnd() || b1a.burnAtWindowStart() <= 0) {
                helper.fail("the fire did not go out inside the window on the ticked arm"
                        + " (lit at the window opening=" + b1a.litAtWindowStart()
                        + " with " + b1a.burnAtWindowStart() + " ticks of fuel, lit at the end="
                        + b1a.litAtEnd() + "), so the block state was never asked to change"
                        + " inside the window and comparing it proves nothing");
                return;
            }
            if (!beyond.isEmpty()) {
                helper.fail("the caught-up furnace differs from the ticked one on "
                        + beyond.size() + " line(s) that two identical ticked trips agree on, of"
                        + " which " + beyondBlockStates.size() + " are block states: "
                        + beyond.subList(0, Math.min(4, beyond.size()))
                        + " (noise floor " + noise.size() + " lines)");
                return;
            }
            helper.succeed();
        }
    }

    private static List<String> differences(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>();
        Set<String> mine = new LinkedHashSet<>(left);
        Set<String> theirs = new LinkedHashSet<>(right);
        for (String line : left) {
            if (!theirs.contains(line)) {
                out.add("ticked  : " + line);
            }
        }
        for (String line : right) {
            if (!mine.contains(line)) {
                out.add("caughtup: " + line);
            }
        }
        return out;
    }

    private static boolean marker(String name) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(name),
                cwd.resolve("run").resolve(name),
                cwd.getParent() == null ? cwd.resolve(name) : cwd.getParent().resolve(name))) {
            if (Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Leave the block state saying the fire is lit while the block entity says it is out. */
    private static boolean desyncRequested() {
        return marker("meanwhile-nc-blockstate-desync.properties");
    }

    /**
     * Drop one item into the arena on one arm only.
     *
     * <p>Nothing the catch-up does produces an item here, so the entity surface has never had to
     * report anything and its power to report is untested. This is a synthetic difference: not a
     * measurement of the mod, a measurement of the instrument.
     */
    private static boolean spawnItemRequested() {
        return marker("meanwhile-nc-spawn-item.properties");
    }

    /** Book one block tick on one arm only. Same purpose as the item. */
    private static boolean scheduleTickRequested() {
        return marker("meanwhile-nc-schedule-tick.properties");
    }

    /** Count every fall of a counter as a turnover, which is what produced the peak of 100. */
    static boolean rawTurnoverRequested() {
        return marker("meanwhile-nc-raw-turnover.properties");
    }
}
