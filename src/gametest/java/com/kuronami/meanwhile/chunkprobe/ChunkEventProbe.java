package com.kuronami.meanwhile.chunkprobe;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Writes down every {@link ChunkEvent.Load} and {@link ChunkEvent.Unload} the server posts.
 *
 * <p>Nothing here decides anything. It records what the game did and when, so a test can ask
 * afterwards whether a particular chunk was seen going out and coming back, and at which game
 * tick. The whole stream is logged rather than only the chunk under test, because the case this
 * exists to settle includes "the arena chunk never appeared at all", and a filter placed before
 * the evidence is collected is how that case becomes indistinguishable from a bug in the filter.
 *
 * <p>The block entity image is taken from the chunk carried by the unload event, never from the
 * level: a {@code level.getBlockEntity} call inside the handler would pull the chunk straight
 * back in and there would be nothing left to observe.
 */
public final class ChunkEventProbe {

    /** One posted event. {@code chunkPos} is a packed {@link ChunkPos}. */
    public record Sighting(boolean unload, long chunkPos, long gameTime, String dimension) {}

    /** A ceiling, so a long run cannot turn the record into a leak. */
    private static final int MAX_SIGHTINGS = 60000;

    private static final List<Sighting> SIGHTINGS = new ArrayList<>();

    @Nullable
    private static volatile BlockPos watched;
    @Nullable
    private static volatile String imageAtUnload;
    private static volatile long imageAtUnloadTime = -1L;

    private ChunkEventProbe() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ChunkEventProbe::onLoad);
        NeoForge.EVENT_BUS.addListener(ChunkEventProbe::onUnload);
        Meanwhile.LOGGER.info("[chunkprobe] listening for ChunkEvent.Load and ChunkEvent.Unload");
    }

    /** Take the block entity's persisted form at this position when its chunk unloads. */
    public static void watch(BlockPos pos) {
        imageAtUnload = null;
        imageAtUnloadTime = -1L;
        watched = pos;
    }

    public static void stopWatching() {
        watched = null;
    }

    @Nullable
    public static String imageAtUnload() {
        return imageAtUnload;
    }

    public static long imageAtUnloadTime() {
        return imageAtUnloadTime;
    }

    /**
     * The game time of the first sighting of {@code pos} at or after {@code since}, or -1 when
     * there is none.
     */
    public static long firstSightingAfter(ChunkPos pos, boolean unload, long since) {
        long key = pos.toLong();
        synchronized (SIGHTINGS) {
            for (Sighting sighting : SIGHTINGS) {
                if (sighting.unload() == unload && sighting.chunkPos() == key
                        && sighting.gameTime() >= since) {
                    return sighting.gameTime();
                }
            }
        }
        return -1L;
    }

    /** How many events of either kind have been seen for {@code pos}, for a sanity line. */
    public static int countFor(ChunkPos pos) {
        long key = pos.toLong();
        int count = 0;
        synchronized (SIGHTINGS) {
            for (Sighting sighting : SIGHTINGS) {
                if (sighting.chunkPos() == key) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void onLoad(ChunkEvent.Load event) {
        record(event, false, event.isNewChunk());
    }

    private static void onUnload(ChunkEvent.Unload event) {
        captureIfWatched(event);
        record(event, true, false);
    }

    private static void captureIfWatched(ChunkEvent.Unload event) {
        BlockPos pos = watched;
        if (pos == null || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!chunk.getPos().equals(new ChunkPos(pos))) {
            return;
        }
        ServerLevel level = serverLevel(event.getLevel());
        if (level == null) {
            return;
        }
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        if (blockEntity == null) {
            Meanwhile.LOGGER.info("[chunkprobe] watched position holds no block entity at unload"
                    + " | pos={}", pos.toShortString());
            return;
        }
        String image = blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
        long now = level.getGameTime();
        // Only the first one. A chunk nothing is holding goes straight back out after it is
        // pulled in, and a later image would silently become the thing the round trip is
        // compared against, which is not the image the round trip started from.
        if (imageAtUnload == null) {
            imageAtUnload = image;
            imageAtUnloadTime = now;
        }
        Meanwhile.LOGGER.info("[chunkprobe] image at unload | pos={} t={} kept={} nbt={}",
                pos.toShortString(), now, imageAtUnloadTime == now, image);
    }

    private static void record(ChunkEvent event, boolean unload, boolean newChunk) {
        ServerLevel level = serverLevel(event.getLevel());
        long time = level == null ? -1L : level.getGameTime();
        String dimension = level == null ? "non-server" : level.dimension().location().toString();
        ChunkPos pos = event.getChunk().getPos();

        synchronized (SIGHTINGS) {
            if (SIGHTINGS.size() < MAX_SIGHTINGS) {
                SIGHTINGS.add(new Sighting(unload, pos.toLong(), time, dimension));
            }
        }

        if (unload) {
            Meanwhile.LOGGER.info("[chunkprobe] unload chunk={} t={} dim={}", pos, time, dimension);
        } else {
            Meanwhile.LOGGER.info("[chunkprobe] load chunk={} t={} dim={} new={}",
                    pos, time, dimension, newChunk);
        }
    }

    @Nullable
    private static ServerLevel serverLevel(LevelAccessor accessor) {
        return accessor instanceof ServerLevel level ? level : null;
    }
}
