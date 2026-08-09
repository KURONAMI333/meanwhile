package com.kuronami.meanwhile.chunkprobe;

import com.kuronami.meanwhile.Meanwhile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a GameTest can drive a real chunk unload and a real chunk reload.
 *
 * <p>The framework force-loads the chunks a test's arena occupies
 * ({@code StructureUtils.forceLoadChunks}) and does not release them until the whole batch is
 * over ({@code GameTestRunner}). This test releases them early, from inside itself, and then
 * watches for the events the game posts. It asserts nothing about what ought to happen; it
 * records what did, and the assertion at the end is only that the round trip completed with the
 * block entity intact.
 *
 * <h3>Why its own batch, with one test in it</h3>
 * <p>Releasing a ticket is not scoped to a test. Tests in one batch run beside each other in
 * neighbouring arenas, and a chunk released here is released for whoever else is standing in
 * it. Batches run one at a time, so a batch holding a single test keeps the release away from
 * any test running beside it. It does not keep the level quiet: chunks well outside the range a
 * forced ticket propagates over were seen unloading in the same two ticks as the release, and a
 * run without this test unloads chunks mid-run as well, so how much of that cascade belongs to
 * the release is not established.
 *
 * <h3>Why the ticket is dropped for the whole arena</h3>
 * <p>A forced chunk propagates its ticket level outwards, so an arena spanning two chunks with
 * only one released stays loaded through its neighbour. The chunks come from the arena's own
 * bounding box, which is what {@code forceLoadChunks} was handed in the first place — rather
 * than from {@code level.getForcedChunks()}, which is level-wide saved data that {@code run/}
 * carries between runs and would make it impossible to say which removal mattered.
 *
 * <h3>Why the furnace has a hopper on it</h3>
 * <p>The deferral scheduler is on by default in this mod, and a furnace nothing can reach is
 * exactly what it stops ticking. A hopper keeps it on the game's own tick path, so the state
 * that goes into the round trip is vanilla's and not this mod's.
 *
 * <p>Optional ({@code required = false}) on purpose. It exists to answer a question, and a
 * question answered "no" must not turn the standing suite red.
 *
 * <h3>Why a marker file rather than {@code @GameTestHolder}</h3>
 * <p>Same reason as {@code TickCostBench}: an annotated class is found by classpath scan and
 * would join the standing suite permanently. There is a second reason here. With this test
 * running, the server does not finish shutting down — it repeats unload, load and a deferred
 * reconcile on one position until the process is killed, and the run task reports failure after
 * every test has passed. Registering it by scan would put that in the way of the gate. Write
 * {@code meanwhile-chunkprobe.properties} next to the project or in {@code run/} to ask for it.
 *
 * <p>{@code manualOnly = true} is not an alternative: NeoForge's {@code gameTestServer} ran this
 * test anyway with it set.
 */
public class ChunkRoundTripGameTests {

    /** Written next to the project (and/or in the run directory) to ask for this probe. */
    private static final String MARKER = "meanwhile-chunkprobe.properties";

    static final String BATCH = "chunkprobe";

    private static final BlockPos FURNACE = new BlockPos(2, 1, 2);

    /** Long enough for the furnace to light and get some way into a smelt. */
    private static final int SETTLE_TICKS = 40;
    /** How long the arena is watched for an unload after its tickets are dropped. */
    private static final int UNLOAD_WAIT = 400;
    /** How long the chunk is watched for a load after something asks for it. */
    private static final int LOAD_WAIT = 40;

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;

    /**
     * Whether a run has asked for this probe.
     *
     * <p>A file rather than a system property because the run task's JVM arguments live in
     * {@code build.gradle}, which this probe is not allowed to touch.
     */
    public static boolean isRequested() {
        return markerPath() != null;
    }

    @Nullable
    private static Path markerPath() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(MARKER),
                cwd.resolve("run").resolve(MARKER),
                cwd.getParent() == null ? cwd.resolve(MARKER) : cwd.getParent().resolve(MARKER))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @PrefixGameTestTemplate(false)
    // templateNamespace is load-bearing, not decoration: without @GameTestHolder the namespace
    // a test is filtered by falls back to "minecraft", and neoforge.enabledGameTestNamespaces is
    // set to this mod, so the test is registered and then silently dropped
    // (GameTestRegistry.register / GameTestHooks.getTemplateNamespace).
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200, required = false)
    public static void arenaChunkUnloadsAndComesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos furnacePos = helper.absolutePos(FURNACE);
        ChunkPos target = new ChunkPos(furnacePos);
        List<ChunkPos> arena = arenaChunks(helper);

        helper.setBlock(FURNACE, Blocks.FURNACE);
        helper.setBlock(FURNACE.above(), Blocks.HOPPER);
        loadFurnace(helper);
        ChunkEventProbe.watch(furnacePos);

        long[] unforcedAt = {-1L};
        long[] unloadAt = {-1L};
        long[] touchedAt = {-1L};
        long[] loadAt = {-1L};
        String[] lastTicket = {""};

        Meanwhile.LOGGER.info("[chunkprobe] arena | structureBlock={} furnace={} target={} chunks={}"
                        + " forcedBefore={}",
                helper.absolutePos(BlockPos.ZERO).toShortString(), furnacePos.toShortString(),
                target, arena, forcedChunks(level));

        helper.startSequence()
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    Meanwhile.LOGGER.info("[chunkprobe] before release | t={} furnace={} nbt={}",
                            level.getGameTime(), describe(furnace(helper)), image(level, furnacePos));
                    for (ChunkPos pos : arena) {
                        boolean removed = level.setChunkForced(pos.x, pos.z, false);
                        Meanwhile.LOGGER.info("[chunkprobe] release ticket | chunk={} removed={}",
                                pos, removed);
                    }
                    unforcedAt[0] = level.getGameTime();
                    Meanwhile.LOGGER.info("[chunkprobe] released | t={} forcedAfter={} ticket={}",
                            unforcedAt[0], forcedChunks(level), ticketLevel(level, target));
                })
                // Deliberately a fixed window rather than a wait-until, so a chunk that never
                // goes anywhere produces a stated number of ticks waited instead of a timeout.
                .thenExecuteFor(UNLOAD_WAIT, () -> {
                    String now = ticketLevel(level, target);
                    if (!now.equals(lastTicket[0])) {
                        Meanwhile.LOGGER.info("[chunkprobe] ticket level | t={} (+{}) chunk={}"
                                        + " level={}",
                                level.getGameTime(), level.getGameTime() - unforcedAt[0],
                                target, now);
                        lastTicket[0] = now;
                    }
                    if (unloadAt[0] < 0) {
                        unloadAt[0] = ChunkEventProbe.firstSightingAfter(target, true, unforcedAt[0]);
                    }
                })
                .thenExecute(() -> {
                    unloadAt[0] = ChunkEventProbe.firstSightingAfter(target, true, unforcedAt[0]);
                    Meanwhile.LOGGER.info("[chunkprobe] RESULT unload | releasedAt={} unloadAt={}"
                                    + " delta={} waited={} ticket={} topTicket={} events={}",
                            unforcedAt[0], unloadAt[0],
                            unloadAt[0] < 0 ? "none" : (unloadAt[0] - unforcedAt[0]),
                            UNLOAD_WAIT, ticketLevel(level, target),
                            topTicket(level, target), ChunkEventProbe.countFor(target));
                    for (ChunkPos pos : arena) {
                        Meanwhile.LOGGER.info("[chunkprobe] arena chunk after wait | chunk={}"
                                        + " ticket={} topTicket={}",
                                pos, ticketLevel(level, pos), topTicket(level, pos));
                    }
                })
                .thenExecute(() -> {
                    touchedAt[0] = level.getGameTime();
                    // The ordinary way anything asks for a chunk. Synchronous full load.
                    level.getChunk(target.x, target.z);
                    Meanwhile.LOGGER.info("[chunkprobe] asked for the chunk | t={} ticket={}",
                            touchedAt[0], ticketLevel(level, target));
                })
                .thenExecuteFor(LOAD_WAIT, () -> {
                    if (loadAt[0] < 0) {
                        loadAt[0] = ChunkEventProbe.firstSightingAfter(target, false, touchedAt[0]);
                    }
                })
                .thenExecute(() -> {
                    loadAt[0] = ChunkEventProbe.firstSightingAfter(target, false, touchedAt[0]);
                    String before = ChunkEventProbe.imageAtUnload();
                    String after = image(level, furnacePos);
                    Meanwhile.LOGGER.info("[chunkprobe] RESULT load | askedAt={} loadAt={} delta={}"
                                    + " waited={} ticket={}",
                            touchedAt[0], loadAt[0],
                            loadAt[0] < 0 ? "none" : (loadAt[0] - touchedAt[0]),
                            LOAD_WAIT, ticketLevel(level, target));
                    Meanwhile.LOGGER.info("[chunkprobe] RESULT block entity | same={}"
                                    + " atUnload(t={})={} afterLoad(t={})={} furnace={}",
                            before != null && before.equals(after),
                            ChunkEventProbe.imageAtUnloadTime(), before,
                            level.getGameTime(), after, describe(furnace(helper)));

                    ChunkEventProbe.stopWatching();
                    helper.setBlock(FURNACE.above(), Blocks.AIR);
                    helper.setBlock(FURNACE, Blocks.AIR);
                    // Put the arena back the way the framework left it, so the batch teardown
                    // that releases forced chunks has the same set to release that it added.
                    for (ChunkPos pos : arena) {
                        level.setChunkForced(pos.x, pos.z, true);
                    }
                    Meanwhile.LOGGER.info("[chunkprobe] restored | forced={}", forcedChunks(level));

                    if (unloadAt[0] < 0) {
                        helper.fail("the arena chunk " + target + " posted no ChunkEvent.Unload in "
                                + UNLOAD_WAIT + " ticks after its forced tickets were released"
                                + " (ticket level " + ticketLevel(level, target)
                                + ", top ticket " + topTicket(level, target) + ")");
                        return;
                    }
                    if (loadAt[0] < 0) {
                        helper.fail("the arena chunk " + target + " unloaded at " + unloadAt[0]
                                + " but posted no ChunkEvent.Load in " + LOAD_WAIT
                                + " ticks after being asked for");
                        return;
                    }
                    if (before == null) {
                        helper.fail("nothing was captured from the furnace as its chunk unloaded,"
                                + " so the round trip cannot be compared");
                        return;
                    }
                    if (!before.equals(after)) {
                        helper.fail("the furnace does not come back the way it went out:"
                                + " at unload " + before + ", after load " + after);
                        return;
                    }
                })
                .thenSucceed();
    }

    // ---- helpers ----------------------------------------------------------------------

    /**
     * The chunks the framework force-loaded for this arena, taken from the same bounding box
     * {@code StructureUtils.forceLoadChunks} was given, plus the structure block's own chunk in
     * case it sits outside.
     */
    private static List<ChunkPos> arenaChunks(GameTestHelper helper) {
        AABB bounds = helper.getBounds();
        BoundingBox box = BoundingBox.fromCorners(
                BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0, bounds.maxZ - 1.0));
        List<ChunkPos> chunks = new ArrayList<>();
        box.intersectingChunks().forEach(chunks::add);
        ChunkPos structureBlock = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        if (!chunks.contains(structureBlock)) {
            chunks.add(structureBlock);
        }
        return chunks;
    }

    /** The ticket level the chunk map is holding this chunk at, or {@code null} when it is gone. */
    private static String ticketLevel(ServerLevel level, ChunkPos pos) {
        String data = level.getChunkSource().getChunkDebugData(pos);
        int newline = data.indexOf('\n');
        return newline < 0 ? data : data.substring(0, newline);
    }

    /**
     * The highest-priority ticket on this chunk, which is what says who is holding it. Reached
     * by reflection because the accessor is protected and this probe does not touch the build.
     */
    private static String topTicket(ServerLevel level, ChunkPos pos) {
        try {
            DistanceManager manager = level.getChunkSource().chunkMap.getDistanceManager();
            var method = DistanceManager.class.getDeclaredMethod("getTicketDebugString", long.class);
            method.setAccessible(true);
            return String.valueOf(method.invoke(manager, pos.toLong()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable(" + e + ")";
        }
    }

    private static String forcedChunks(ServerLevel level) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (long packed : level.getForcedChunks().toLongArray()) {
            chunks.add(new ChunkPos(packed));
        }
        return chunks.toString();
    }

    /** The block entity's persisted form, which is the thing a round trip has to preserve. */
    private static String image(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null
                ? "<no block entity>"
                : blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
    }

    private static String describe(@Nullable AbstractFurnaceBlockEntity furnace) {
        if (furnace == null) {
            return "<gone>";
        }
        return "litTime=" + furnace.litTime + " litDuration=" + furnace.litDuration
                + " progress=" + furnace.cookingProgress + " total=" + furnace.cookingTotalTime
                + " in=" + furnace.getItem(0).getCount()
                + " fuel=" + furnace.getItem(1).getCount()
                + " out=" + furnace.getItem(2).getCount();
    }

    /** The same starting load the rest of the suite uses. */
    private static void loadFurnace(GameTestHelper helper) {
        AbstractFurnaceBlockEntity furnace = furnace(helper);
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
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(FURNACE))
                instanceof AbstractFurnaceBlockEntity found ? found : null;
    }
}
