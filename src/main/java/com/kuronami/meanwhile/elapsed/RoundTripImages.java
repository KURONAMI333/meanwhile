package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;

/**
 * What one chunk's block entities looked like as it went out, and again as it came back.
 *
 * <p>Separate from everything that decides anything. {@link ChunkCatchUp} sees a chunk that has
 * already been ticked at least once since it reloaded, so it cannot say whether the round trip
 * through disk preserved anything; this can, because it reads on the two events themselves with
 * no tick in between.
 *
 * <p>Both reads are taken from the chunk the event carries, never from the level. A
 * {@code level.getBlockEntity} inside an unload handler pulls the chunk back to FULL and re-posts
 * {@code Load}, which is the loop of GAP_LOG G58 — the same reason
 * {@code chunkprobe.ChunkEventProbe} takes its image the same way.
 *
 * <p>One chunk at a time, named by whoever is watching. A recorder that kept every chunk would be
 * a leak in a long run and would make "the chunk under test never went anywhere" hard to see.
 */
public final class RoundTripImages {

    /** The serialised block entities of a chunk at one instant, by position. */
    public record Images(long gameTime, Map<BlockPos, CompoundTag> tags) {

        public String describe() {
            List<String> parts = new ArrayList<>();
            List<BlockPos> positions = new ArrayList<>(tags.keySet());
            positions.sort(Comparator.comparingLong(BlockPos::asLong));
            for (BlockPos pos : positions) {
                parts.add(pos.toShortString() + "=" + tags.get(pos));
            }
            return "t=" + gameTime + " " + parts;
        }
    }

    private static volatile boolean installed;

    private static volatile long watched = Long.MIN_VALUE;
    @Nullable
    private static volatile Images atUnload;
    @Nullable
    private static volatile Images atLoad;
    private static volatile long unloadAt = -1L;
    private static volatile long loadAt = -1L;
    private static volatile int unloads;
    private static volatile int loads;

    /**
     * Whether the deliberate revival is armed. Consumed before the call it authorises, so that a
     * cascade cannot spend it twice and the loop it demonstrates cannot run more than one lap.
     */
    private static volatile boolean resurrectProbeArmed;
    private static volatile String resurrectResult = "<not armed>";

    private RoundTripImages() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(RoundTripImages::onUnload);
        NeoForge.EVENT_BUS.addListener(RoundTripImages::onLoad);
        Meanwhile.LOGGER.info("[roundtrip] recording block entity images across chunk events");
    }

    /** Start over on a chunk. Everything previously recorded is dropped. */
    public static void watch(ChunkPos pos) {
        watched = pos.toLong();
        atUnload = null;
        atLoad = null;
        unloadAt = -1L;
        loadAt = -1L;
        unloads = 0;
        loads = 0;
        Meanwhile.LOGGER.info("[roundtrip] watching | chunk={}", pos);
    }

    public static void stopWatching() {
        watched = Long.MIN_VALUE;
    }

    // ---- the two events ------------------------------------------------------------------

    private static void onUnload(ChunkEvent.Unload event) {
        capture(event, true);
    }

    private static void onLoad(ChunkEvent.Load event) {
        capture(event, false);
    }

    private static void capture(ChunkEvent event, boolean unload) {
        long key = watched;
        if (key == Long.MIN_VALUE || !(event.getChunk() instanceof LevelChunk chunk)
                || chunk.getPos().toLong() != key
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();

        Map<BlockPos, CompoundTag> tags = new LinkedHashMap<>();
        List<String> live = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            tags.put(entry.getKey().immutable(),
                    entry.getValue().saveWithoutMetadata(level.registryAccess()));
            // Read off the object rather than the tag: which branch of a Create kinetic block
            // entity's read runs is decided by a field that is not serialised, so a chunk event
            // is the only place the value on a freshly deserialised one can be seen.
            String moved = fieldOf(entry.getValue(), "wasMoved");
            if (!"<absent>".equals(moved)) {
                live.add(entry.getKey().toShortString() + " wasMoved=" + moved
                        + " lastStressApplied=" + fieldOf(entry.getValue(), "lastStressApplied"));
            }
        }
        if (!live.isEmpty()) {
            Meanwhile.LOGGER.info("[roundtrip] live fields at {} | chunk={} {}",
                    unload ? "unload" : "load", chunk.getPos(), live);
        }
        Images images = new Images(now, tags);

        if (unload) {
            unloads++;
            // Every one, not only the first: what went to disk is the last state seen leaving,
            // and a chunk that bounces would otherwise be compared against an image from an
            // earlier lap.
            atUnload = images;
            unloadAt = now;
            Meanwhile.LOGGER.info("[roundtrip] unload | chunk={} n={} {}",
                    chunk.getPos(), unloads, images.describe());
            fireResurrectProbe(level, chunk);
            return;
        }
        loads++;
        atLoad = images;
        loadAt = now;
        Meanwhile.LOGGER.info("[roundtrip] load | chunk={} n={} {}",
                chunk.getPos(), loads, images.describe());
    }

    /** A named field off a block entity as text, or {@code <absent>} when it has none. */
    private static String fieldOf(BlockEntity blockEntity, String name) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return String.valueOf(field.get(blockEntity));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Declared further up, or not readable here.
            }
        }
        return "<absent>";
    }

    // ---- the hazard the second guard is about --------------------------------------------

    /**
     * Asks the level for a block entity from inside the unload handler, once, and reports whether
     * the chunk came back to life for it.
     *
     * <p>This is the thing {@link ChunkCatchUp} refuses to do. Doing it deliberately, once, is
     * what turns "the guard is there" into a measured statement about what the guard is for. The
     * arming flag is consumed before the call rather than after, so that the {@code Load} this
     * may provoke — and the {@code Unload} after it — cannot re-enter here.
     */
    private static void fireResurrectProbe(ServerLevel level, LevelChunk chunk) {
        if (!resurrectProbeArmed) {
            return;
        }
        resurrectProbeArmed = false;

        ChunkPos pos = chunk.getPos();
        int loadsBefore = loads;
        BlockPos probe = chunk.getBlockEntities().keySet().stream().findFirst()
                .orElse(pos.getWorldPosition());
        Meanwhile.LOGGER.warn("[roundtrip] RESURRECT PROBE | chunk={} pos={} loadsBefore={}"
                        + " asking the level for a block entity from inside ChunkEvent.Unload",
                pos, probe.toShortString(), loadsBefore);

        BlockEntity revived = level.getBlockEntity(probe);
        int loadsAfter = loads;
        boolean loaded = level.getChunkSource().getChunkNow(pos.x, pos.z) != null;

        resurrectResult = "loadsBefore=" + loadsBefore + " loadsAfter=" + loadsAfter
                + " rePosted=" + (loadsAfter > loadsBefore)
                + " blockEntity=" + (revived == null ? "<null>" : revived.getClass().getSimpleName())
                + " chunkLoadedAfter=" + loaded;
        Meanwhile.LOGGER.warn("[roundtrip] RESURRECT PROBE RESULT | chunk={} {}",
                pos, resurrectResult);
    }

    public static void armResurrectProbe() {
        resurrectProbeArmed = true;
        resurrectResult = "<armed, not yet fired>";
        Meanwhile.LOGGER.warn("[roundtrip] resurrect probe armed (one shot)");
    }

    public static String resurrectResult() {
        return resurrectResult;
    }

    // ---- what a test may ask ---------------------------------------------------------------

    @Nullable
    public static Images atUnload() {
        return atUnload;
    }

    @Nullable
    public static Images atLoad() {
        return atLoad;
    }

    public static long unloadAt() {
        return unloadAt;
    }

    public static long loadAt() {
        return loadAt;
    }

    public static int unloads() {
        return unloads;
    }

    public static int loads() {
        return loads;
    }

    /**
     * What the round trip did to the block entities inside one box, split by what it means.
     *
     * <p>The three are not the same kind of event and collapsing them loses the point. A value
     * that came back different, or a position that did not come back at all, is state the disk
     * did not preserve — nothing downstream can be trusted about that machine. A key that appears
     * only after the load is state the machine re-derives on being read, and Create's
     * {@code NeedsSpeedUpdate} is exactly that: a marker saying "work my speed out again", which
     * is not information that was lost.
     */
    public record Report(List<String> lost, List<String> changed, List<String> added) {

        public boolean preserved() {
            return lost.isEmpty() && changed.isEmpty();
        }

        public String summary() {
            return "lost=" + lost.size() + " changed=" + changed.size() + " added=" + added;
        }
    }

    /**
     * The round trip judged over one box only.
     *
     * <p>A whole chunk carries whatever the world put there — a beacon whose cached pyramid level
     * is recomputed on load, command blocks, the arena's own structure block — and none of that is
     * the machine under measurement. The box is the machine.
     */
    public static Report reportWithin(net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        List<String> lost = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        Images before = atUnload;
        Images after = atLoad;
        if (before == null || after == null) {
            lost.add(before == null ? "nothing was captured as the chunk unloaded"
                    : "nothing was captured as the chunk loaded");
            return new Report(lost, changed, added);
        }
        for (Map.Entry<BlockPos, CompoundTag> entry : before.tags().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!box.isInside(pos)) {
                continue;
            }
            CompoundTag mirror = after.tags().get(pos);
            if (mirror == null) {
                lost.add(pos.toShortString() + " went out and did not come back");
                continue;
            }
            CompoundTag out = entry.getValue();
            for (String key : out.getAllKeys()) {
                if (!mirror.contains(key)) {
                    lost.add(pos.toShortString() + "." + key + " = " + out.get(key));
                } else if (!mirror.get(key).equals(out.get(key))) {
                    changed.add(pos.toShortString() + "." + key + " out=" + out.get(key)
                            + " back=" + mirror.get(key));
                }
            }
            for (String key : mirror.getAllKeys()) {
                if (!out.contains(key)) {
                    added.add(pos.toShortString() + "." + key + " = " + mirror.get(key));
                }
            }
        }
        return new Report(lost, changed, added);
    }

    /**
     * Every position whose serialised form is not the same on both sides of the round trip, or an
     * empty list when the trip preserved all of them.
     */
    public static List<String> roundTripDifferences() {
        Images before = atUnload;
        Images after = atLoad;
        if (before == null) {
            return List.of("nothing was captured as the chunk unloaded");
        }
        if (after == null) {
            return List.of("nothing was captured as the chunk loaded");
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : before.tags().entrySet()) {
            CompoundTag mirror = after.tags().get(entry.getKey());
            if (mirror == null) {
                out.add(entry.getKey().toShortString() + " went out and did not come back");
            } else if (!mirror.equals(entry.getValue())) {
                out.add(entry.getKey().toShortString() + " out=" + entry.getValue()
                        + " back=" + mirror);
            }
        }
        for (BlockPos pos : after.tags().keySet()) {
            if (!before.tags().containsKey(pos)) {
                out.add(pos.toShortString() + " came back without having gone out");
            }
        }
        return out;
    }
}
