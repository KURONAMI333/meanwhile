package com.kuronami.meanwhile.chunkprobe;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Counts, tick by tick, the ticks on which the game did not run a watched chunk's block
 * entities.
 *
 * <p>This exists because "how long the chunk was gone" cannot be asked of the thing under test.
 * {@link com.kuronami.meanwhile.elapsed.ChunkClock} answers it by subtracting the time it wrote
 * onto the chunk from the time it is reading now, so any expectation built out of that stored
 * time is the same arithmetic written twice and cannot disagree with it. What can disagree is a
 * count kept from outside: one entry per tick, of whether the game would have ticked anything in
 * that chunk on that tick.
 *
 * <p>The condition is the game's own, and not the clock's. {@code Level#tickBlockEntities}
 * ({@code Level.java:568}) runs a registered ticker only when
 * {@code this.shouldTickBlocksAt(tickingblockentity.getPos())} holds, and a ticker is registered
 * only for a chunk that is in memory. So "the chunk is in the chunk source now, and the level
 * says blocks tick there" is the pair of facts that decide whether a machine in it moved, and
 * neither of them is read off the chunk's stored time.
 *
 * <p>{@code getChunkNow} is used rather than anything that can load: a chunk that is not there
 * answers null and stays gone, which is the whole point of the tick being counted as missed.
 *
 * <h3>What it cannot see</h3>
 * <p>The reading is taken on {@link LevelTickEvent.Post}, after the level has ticked, and
 * {@code tickBlockEntities} ran earlier in that same tick. A chunk whose ticking condition
 * changed in between — between the block entities being ticked and the level tick finishing —
 * is recorded on the later of the two readings. Nothing in an arena that is driven only by
 * forced tickets moves in that gap, since ticket levels are resolved in the chunk source before
 * either point.
 */
public final class ChunkTickProbe {

    /**
     * A run of ticks the chunk's block entities did not run for.
     *
     * @param lastRunning the last tick before the gap on which the chunk was ticking
     * @param nextRunning the first tick after it on which it was ticking again
     * @param missed      how many ticks are between the two, which is what the chunk lost
     */
    public record Gap(long lastRunning, long nextRunning, long missed) {

        @Override
        public String toString() {
            return "lastRunning=" + lastRunning + " nextRunning=" + nextRunning
                    + " missed=" + missed;
        }
    }

    /** A ceiling, so a long run cannot turn the record into a leak. */
    private static final int MAX_GAPS = 256;

    private static final List<Gap> GAPS = new ArrayList<>();

    private static volatile boolean installed;

    @Nullable
    private static volatile ChunkPos watchedChunk;
    @Nullable
    private static volatile ResourceKey<Level> watchedDimension;

    /** The last tick the watched chunk was seen ticking, or {@link Long#MIN_VALUE}. */
    private static volatile long lastRunning = Long.MIN_VALUE;

    /** How many ticks the watched chunk has been seen running, for a sanity line. */
    private static volatile long runningTicks;

    private ChunkTickProbe() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(ChunkTickProbe::onLevelTick);
        Meanwhile.LOGGER.info("[chunktick] counting the ticks a watched chunk does not run for");
    }

    /** Start counting for one chunk, forgetting whatever was counted for the last one. */
    public static void watch(ServerLevel level, ChunkPos pos) {
        synchronized (GAPS) {
            GAPS.clear();
        }
        lastRunning = Long.MIN_VALUE;
        runningTicks = 0L;
        watchedDimension = level.dimension();
        watchedChunk = pos;
        Meanwhile.LOGGER.info("[chunktick] watching | chunk={} dim={} t={}", pos,
                level.dimension().location(), level.getGameTime());
    }

    public static void stopWatching() {
        watchedChunk = null;
        watchedDimension = null;
    }

    /**
     * The gap the watched chunk came out of on {@code tick}, or null when it was not seen
     * starting to run again on that tick.
     */
    @Nullable
    public static Gap gapEndingAt(long tick) {
        synchronized (GAPS) {
            for (Gap gap : GAPS) {
                if (gap.nextRunning() == tick) {
                    return gap;
                }
            }
        }
        return null;
    }

    /** Every gap seen so far, for a report. */
    public static List<Gap> gaps() {
        synchronized (GAPS) {
            return new ArrayList<>(GAPS);
        }
    }

    public static long runningTicks() {
        return runningTicks;
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        ChunkPos pos = watchedChunk;
        ResourceKey<Level> dimension = watchedDimension;
        if (pos == null || dimension == null
                || !(event.getLevel() instanceof ServerLevel level)
                || !level.dimension().equals(dimension)) {
            return;
        }
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null
                || !level.shouldTickBlocksAt(pos.toLong())) {
            return;
        }
        long now = level.getGameTime();
        long previous = lastRunning;
        if (previous != Long.MIN_VALUE && now > previous + 1) {
            Gap gap = new Gap(previous, now, now - previous - 1);
            synchronized (GAPS) {
                if (GAPS.size() < MAX_GAPS) {
                    GAPS.add(gap);
                }
            }
            Meanwhile.LOGGER.info("[chunktick] gap | chunk={} dim={} {}", pos,
                    dimension.location(), gap);
        }
        lastRunning = now;
        runningTicks++;
    }
}
