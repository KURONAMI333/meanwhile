package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.chunkprobe.ChunkEventProbe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Whether the number of ticks a chunk was gone for comes out right.
 *
 * <p>The chunk really goes: its forced tickets are dropped, the game posts
 * {@code ChunkEvent.Unload}, the chunk is saved and dropped, and it is asked for again some
 * chosen number of ticks later. Three round trips with three different waits run in sequence, so
 * one number cannot be a coincidence of one arena.
 *
 * <p>What is asserted:
 * <ul>
 * <li>the chunk was seen going out and seen coming back — otherwise the whole measurement is of
 *     a chunk that never left, which is the way this test fails silently;</li>
 * <li>the time the chunk carried back is <em>the same value</em> the test read off it before it
 *     went, so the round trip through NBT preserved it rather than the attachment quietly
 *     falling back to a default;</li>
 * <li>the elapsed count equals current tick minus that value, exactly.</li>
 * </ul>
 *
 * <p>The witness for "it really unloaded" is {@link ChunkEventProbe}, which is a separate
 * recorder of the game's own events and is not part of what is being measured. Asking the clock
 * whether the clock saw an unload would prove nothing.
 *
 * <h3>Why the arena holds no block entity</h3>
 * <p>The loaded-side scheduler is still live in this build, and a deferred block entity inside a
 * chunk that unloads re-arms the resurrect loop of GAP_LOG G58. Nothing here needs a machine —
 * the chunk itself is the subject — so there is none, and the loop has nothing to catch on.
 *
 * <h3>Why its own batch</h3>
 * <p>Dropping a forced ticket is not scoped to a test. Tests in one batch stand in neighbouring
 * arenas and a chunk released here is released for them too. One test, one batch, and the
 * standing suite is untouched.
 */
@GameTestHolder(Meanwhile.MODID)
public final class ChunkClockGameTests {

    static final String BATCH = "chunkclock";

    /** How long the chunk is left out of the world, per round trip. */
    private static final int[] WAITS = {40, 120, 300};

    /** Ticks of running before the tickets are dropped, so there is a stored time to lose. */
    private static final int SETTLE_TICKS = 10;
    /** How long an unload is waited for. Measured at 2-8 ticks (GAP_LOG G56). */
    private static final int UNLOAD_WAIT = 200;
    /** How long a reconcile is waited for after the chunk is asked for again. */
    private static final int BACK_WAIT = 100;

    private static final int RUN_TICKS = 1400;

    private static boolean probeInstalled;

    private ChunkClockGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void beginBatch(ServerLevel level) {
        if (!probeInstalled) {
            probeInstalled = true;
            ChunkEventProbe.install();
        }
        Meanwhile.LOGGER.info("[clock] batch begin | forced={}", forcedChunks(level));
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", batch = BATCH, timeoutTicks = 2400)
    public static void elapsedTicksMatchHowLongTheChunkWasGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos target = new ChunkPos(helper.absolutePos(new BlockPos(2, 1, 2)));
        List<ChunkPos> arena = arenaChunks(helper);
        Cycle cycle = new Cycle(level, target, arena);

        Meanwhile.LOGGER.info("[clock] arena | structureBlock={} target={} chunks={} forced={}",
                helper.absolutePos(BlockPos.ZERO).toShortString(), target, arena,
                forcedChunks(level));

        helper.startSequence()
                .thenExecuteFor(RUN_TICKS, cycle::step)
                .thenExecute(() -> {
                    // Put the arena back the way the framework left it, whatever happened, so the
                    // batch teardown releases the same set it added.
                    for (ChunkPos pos : arena) {
                        level.setChunkForced(pos.x, pos.z, true);
                    }
                    Meanwhile.LOGGER.info("[clock] restored | forced={}", forcedChunks(level));

                    for (String line : cycle.report()) {
                        Meanwhile.LOGGER.info("[clock] RESULT {}", line);
                    }
                    String failure = cycle.failure();
                    if (failure != null) {
                        helper.fail(failure);
                    }
                })
                .thenSucceed();
    }

    // ---- the round trips ----------------------------------------------------------------

    private enum Phase { SETTLE, RELEASED, GONE, BACK, DONE }

    private static final class Cycle {

        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final List<String> report = new ArrayList<>();

        private Phase phase = Phase.SETTLE;
        private int index;
        private int settleLeft = SETTLE_TICKS;

        @Nullable
        private Long seenBefore;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;

        @Nullable
        private String failure;

        private Cycle(ServerLevel level, ChunkPos target, List<ChunkPos> arena) {
            this.level = level;
            this.target = target;
            this.arena = arena;
        }

        private void step() {
            if (phase == Phase.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (phase) {
                case SETTLE -> settle(now);
                case RELEASED -> released(now);
                case GONE -> gone(now);
                case BACK -> back(now);
                default -> {
                }
            }
        }

        /** Let the chunk run, watching the value the clock is writing onto it. */
        private void settle(long now) {
            capturePeek();
            if (--settleLeft > 0) {
                return;
            }
            if (seenBefore == null) {
                fail("cycle " + index + ": nothing was written onto chunk " + target
                        + " in " + SETTLE_TICKS + " ticks of running, so there is no stored time"
                        + " for the round trip to preserve");
                return;
            }
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, false);
            }
            releasedAt = now;
            phase = Phase.RELEASED;
        }

        /** Wait for the game to actually drop it, still watching the stored value. */
        private void released(long now) {
            capturePeek();
            long seen = ChunkEventProbe.firstSightingAfter(target, true, releasedAt);
            if (seen >= 0) {
                unloadAt = seen;
                phase = Phase.GONE;
                return;
            }
            if (now - releasedAt > UNLOAD_WAIT) {
                fail("cycle " + index + ": chunk " + target + " posted no ChunkEvent.Unload in "
                        + UNLOAD_WAIT + " ticks after its forced tickets were dropped");
            }
        }

        /** Stay away for the chosen number of ticks, then ask for it back. */
        private void gone(long now) {
            if (now - unloadAt < WAITS[index]) {
                return;
            }
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, true);
            }
            askedAt = now;
            phase = Phase.BACK;
        }

        /** Read the arithmetic the clock did on the first tick it was running again. */
        private void back(long now) {
            ChunkClock.Reconciliation result = ChunkClock.lastReconciliation(level, target);
            if (result == null || result.at() < askedAt) {
                if (now - askedAt > BACK_WAIT) {
                    fail("cycle " + index + ": chunk " + target + " came back at " + askedAt
                            + " but the clock had not worked anything out " + BACK_WAIT
                            + " ticks later (last=" + result + ")");
                }
                return;
            }

            long before = seenBefore;
            Long atUnload = ChunkClock.stampAtUnload(level, target);
            long loadAt = ChunkEventProbe.firstSightingAfter(target, false, askedAt);
            report.add("cycle=" + index + " wait=" + WAITS[index]
                    + " releasedAt=" + releasedAt + " unloadAt=" + unloadAt
                    + " askedAt=" + askedAt + " loadAt=" + loadAt
                    + " | seenBefore=" + before + " stampAtUnload=" + atUnload
                    + " lastSeen=" + result.lastSeen() + " at=" + result.at()
                    + " elapsed=" + result.elapsed()
                    + " | priorPresent=" + result.priorPresent()
                    + " gone=" + (result.at() - unloadAt)
                    + " noticedAfter=" + (now - result.at()));

            if (loadAt < 0) {
                fail("cycle " + index + ": chunk " + target + " posted no ChunkEvent.Load after"
                        + " being asked for at " + askedAt);
                return;
            }
            if (!result.priorPresent()) {
                fail("cycle " + index + ": chunk " + target + " came back with no stored time at"
                        + " all, so nothing survived the round trip");
                return;
            }
            if (result.lastSeen() != before) {
                fail("cycle " + index + ": chunk " + target + " went out carrying " + before
                        + " and came back carrying " + result.lastSeen());
                return;
            }
            if (result.elapsed() != result.at() - before) {
                fail("cycle " + index + ": chunk " + target + " was reconciled at " + result.at()
                        + " against a stored " + before + ", which is "
                        + (result.at() - before) + " ticks, but the clock said "
                        + result.elapsed());
                return;
            }

            if (++index >= WAITS.length) {
                phase = Phase.DONE;
                return;
            }
            seenBefore = null;
            settleLeft = SETTLE_TICKS;
            releasedAt = -1L;
            unloadAt = -1L;
            askedAt = -1L;
            phase = Phase.SETTLE;
        }

        private void capturePeek() {
            Long stored = ChunkClock.peek(level, target);
            if (stored != null) {
                seenBefore = stored;
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            phase = Phase.DONE;
        }

        private List<String> report() {
            if (report.isEmpty()) {
                report.add("no round trip completed");
            }
            return report;
        }

        @Nullable
        private String failure() {
            if (failure != null) {
                return failure;
            }
            if (index < WAITS.length) {
                return "only " + index + " of " + WAITS.length + " round trips finished in "
                        + RUN_TICKS + " ticks";
            }
            return null;
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    /**
     * The chunks the framework force-loaded for this arena, from the same bounding box
     * {@code StructureUtils.forceLoadChunks} was handed, plus the structure block's own chunk in
     * case it sits outside. A forced ticket propagates outwards, so an arena with one chunk still
     * held stays loaded through its neighbour.
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

    private static String forcedChunks(ServerLevel level) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (long packed : level.getForcedChunks().toLongArray()) {
            chunks.add(new ChunkPos(packed));
        }
        return chunks.toString();
    }
}
