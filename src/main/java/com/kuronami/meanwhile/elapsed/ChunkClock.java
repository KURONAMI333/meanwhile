package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * How long a chunk was gone.
 *
 * <p>Two halves. While a chunk is running, the game time of every tick it runs is written onto
 * the chunk, so that the last one survives into the saved file. When a chunk comes back, the
 * first tick it is running again subtracts that stored time from the current one, and the
 * difference is the number of ticks the chunk was not there for.
 *
 * <p>Nothing is caught up here. This produces the number and stops.
 *
 * <h3>Where the two halves are hooked, and why not where the design says</h3>
 * <p>The design asks for "every chunk tick" and "the first chunk tick after a load".
 * NeoForge 21.1 has no per-chunk tick event — {@code net.neoforged.neoforge.event.tick} holds
 * exactly {@code EntityTickEvent}, {@code LevelTickEvent}, {@code PlayerTickEvent} and
 * {@code ServerTickEvent} — so this is a per-level sweep on {@link LevelTickEvent.Post},
 * filtered by {@link ServerLevel#shouldTickBlocksAt(long)}.
 *
 * <p>That predicate is the one block entities themselves are gated on: {@code Level#tickBlockEntities}
 * ({@code Level.java:568}) ticks a registered ticker only when
 * {@code this.shouldTickBlocksAt(tickingblockentity.getPos())} holds, and
 * {@code ServerLevel.java:440} resolves it to {@code inBlockTickingRange}. So a chunk this sweep
 * declines to stamp is a chunk whose machines are not running either, and the count cannot
 * include ticks the chunk was working through.
 *
 * <p>The condition {@code ServerChunkCache#tickChunks} puts in front of {@code tickChunk} —
 * spawn proximity or a forced ticket — is deliberately not used. That one gates random ticks
 * and spawning, not whether a chunk's machines are alive.
 *
 * <p>{@link LevelTickEvent.Post} keeps the reason the design gave for not using the load event:
 * it fires after the level has finished its tick, well outside the chunk generation pipeline
 * that posts {@code ChunkEvent.Load} ({@code ChunkStatusTasks.java:215}).
 *
 * <h3>What is deliberately not touched</h3>
 * <p>No block entity is fetched, anywhere. A {@code getBlockEntity} call against a chunk that is
 * on its way out pulls it back to FULL and posts {@code Load} again, which is the loop that kept
 * a server from finishing its shutdown (GAP_LOG G58). The unload handler reads nothing from the
 * chunk but its position, and the reconcile runs a tick later on a chunk that is running again.
 */
public final class ChunkClock {

    /**
     * One reconcile, kept so a test can read the arithmetic that was done rather than infer it
     * from a log line.
     *
     * @param priorPresent whether the chunk carried a stored time at all
     * @param lastSeen     the stored time, meaningless when {@code priorPresent} is false
     * @param at           the game time of the tick this was worked out on
     * @param elapsed      {@code at - lastSeen}, or 0 when there was nothing stored
     */
    public record Reconciliation(long chunkPos, boolean priorPresent, long lastSeen, long at,
                                 long elapsed) {
    }

    /**
     * Below this many ticks there is nothing worth catching up, and saying so every time a chunk
     * changes hands would drown the log. The number is a placeholder for the skeleton; what it
     * ought to be is a product decision that belongs with the per-tick budget (D5).
     */
    public static final int THRESHOLD_TICKS = 20;

    /** What is currently loaded, per level. Populated and emptied by the chunk events only. */
    private static final Map<ResourceKey<Level>, Map<Long, Tracked>> TRACKED =
            new ConcurrentHashMap<>();

    /**
     * What is told about a chunk that came back behind, if anything is.
     *
     * <p>The clock itself only ever produces the number. Whoever acts on it is handed the running
     * chunk, on the tick the difference was worked out, from inside the sweep — which is the only
     * place a block entity may be fetched at all (GAP_LOG G58).
     */
    public interface Reconciler {
        void reconciled(ServerLevel level, LevelChunk chunk, long lastSeen, long at, long elapsed);
    }

    /**
     * Run once per level tick, after every chunk has been looked at.
     *
     * <p>Separate from {@link Reconciler} because of where it is allowed to do work. Noticing an
     * absence happens inside a walk over the loaded chunks; acting on one must not, since acting
     * means running tickers and a ticker may load a chunk.
     */
    public interface Drainer {
        void drain(ServerLevel level);
    }

    /** The last reconcile per chunk, per level. */
    private static final Map<ResourceKey<Level>, Map<Long, Reconciliation>> RECONCILED =
            new ConcurrentHashMap<>();

    /** The value last written onto a chunk before it went away, per chunk, per level. */
    private static final Map<ResourceKey<Level>, Map<Long, Long>> STAMP_AT_UNLOAD =
            new ConcurrentHashMap<>();

    /**
     * A ceiling on the record above. Unlike the other two it is not emptied by anything the game
     * does — a chunk that unloads and is never asked for again leaves its entry behind — so a
     * long session would otherwise accumulate one per chunk ever visited. It exists to be read
     * back by a test, not by the mod.
     */
    private static final int MAX_STAMPS_KEPT = 4096;

    private static volatile boolean installed;

    @Nullable
    private static volatile Reconciler reconciler;

    @Nullable
    private static volatile Drainer drainer;

    /**
     * Whether control is currently inside the level sweep.
     *
     * <p>Read by whoever the clock hands a behind chunk to, so that a call arriving from anywhere
     * else — the unload path above all — can be refused before it fetches anything. The server
     * thread is the only one that runs the sweep, and this is only ever read from it.
     */
    private static boolean inSweep;

    /** Test-only: the one chunk whose stamp is written wrong, and by how much. */
    private static volatile long stampOffsetChunk = Long.MIN_VALUE;
    private static volatile long stampOffset;

    private ChunkClock() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(ChunkClock::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(ChunkClock::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(ChunkClock::onLevelTick);
        NeoForge.EVENT_BUS.addListener(ChunkClock::onLevelUnload);
        Meanwhile.LOGGER.info("[clock] recording chunk running time | threshold={} ticks",
                THRESHOLD_TICKS);
    }

    // ---- the two halves ---------------------------------------------------------------

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<Long, Tracked> tracked = TRACKED.get(level.dimension());
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        inSweep = true;
        try {
            for (Map.Entry<Long, Tracked> entry : tracked.entrySet()) {
                long key = entry.getKey();
                if (!level.shouldTickBlocksAt(key)) {
                    continue;
                }
                Tracked chunk = entry.getValue();
                if (!chunk.reconciled) {
                    reconcile(level, key, chunk, now);
                    chunk.reconciled = true;
                }
                stamp(key, chunk, now);
            }
            // Outside the walk above, deliberately. Whoever acts on a difference runs block
            // entity tickers, one of which may load a chunk, and a load writes into the very map
            // the walk is iterating. Nothing in the loop does more than write a number down; the
            // acting happens here, where an arriving chunk lands in a queue instead.
            Drainer sink = drainer;
            if (sink != null) {
                sink.drain(level);
            }
        } finally {
            inSweep = false;
        }
    }

    /** Read what the chunk brought back with it, and say how far behind it is. */
    private static void reconcile(ServerLevel level, long key, Tracked chunk, long now) {
        Long prior = chunk.chunk.getExistingDataOrNull(ChunkClockAttachments.LAST_SEEN_GAME_TIME);
        boolean priorPresent = prior != null;
        long lastSeen = priorPresent ? prior : 0L;
        long elapsed = priorPresent ? now - lastSeen : 0L;

        RECONCILED.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .put(key, new Reconciliation(key, priorPresent, lastSeen, now, elapsed));

        if (priorPresent && elapsed >= THRESHOLD_TICKS) {
            Meanwhile.LOGGER.info("[clock] elapsed | chunk={} dim={} lastSeen={} at={} elapsed={}",
                    new ChunkPos(key), level.dimension().location(), lastSeen, now, elapsed);
        }

        // Only a chunk that brought a time back with it is behind. One that carries nothing has
        // never been seen running, and there is no window to spend.
        Reconciler listener = reconciler;
        if (priorPresent && listener != null) {
            listener.reconciled(level, chunk.chunk, lastSeen, now, elapsed);
        }
    }

    /** Write down that the chunk was running on this tick. */
    private static void stamp(long key, Tracked chunk, long now) {
        long written = key == stampOffsetChunk ? now + stampOffset : now;
        chunk.chunk.setData(ChunkClockAttachments.LAST_SEEN_GAME_TIME, written);
        // Required for chunks: the attachment is only written out when the chunk is dirty
        // (AttachmentType javadoc, ChunkAccess-exclusive behaviour; ChunkMap.java:771 drops the
        // save of a chunk that is not unsaved). Marking it every tick is the cost of never
        // persisting a time that is older than the last tick the chunk actually ran, which is
        // the direction that over-counts.
        chunk.chunk.setUnsaved(true);
        chunk.lastStamp = written;
    }

    // ---- bookkeeping ------------------------------------------------------------------

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        TRACKED.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .put(chunk.getPos().toLong(), new Tracked(chunk));
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // getPos() and nothing else. The chunk is between setLoaded(false) and its save
        // (ChunkMap.java:541), and anything that asks the level for its contents here revives it.
        long key = event.getChunk().getPos().toLong();
        Map<Long, Tracked> tracked = TRACKED.get(level.dimension());
        if (tracked == null) {
            return;
        }
        Tracked gone = tracked.remove(key);

        // The chunk is not there to be reconciled against any more, and the next load makes a
        // fresh one. Dropping it here keeps this bounded by what is loaded rather than by how
        // much of the world has ever been visited.
        Map<Long, Reconciliation> reconciled = RECONCILED.get(level.dimension());
        if (reconciled != null) {
            reconciled.remove(key);
        }

        if (gone != null && gone.lastStamp != Long.MIN_VALUE) {
            Map<Long, Long> stamps = STAMP_AT_UNLOAD.computeIfAbsent(
                    level.dimension(), ignored -> new ConcurrentHashMap<>());
            if (stamps.size() < MAX_STAMPS_KEPT || stamps.containsKey(key)) {
                stamps.put(key, gone.lastStamp);
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TRACKED.remove(level.dimension());
            RECONCILED.remove(level.dimension());
            STAMP_AT_UNLOAD.remove(level.dimension());
        }
    }

    // ---- what a test may ask ------------------------------------------------------------

    /**
     * The time stored on the chunk right now, read without loading anything. {@code null} when
     * the chunk is not in memory or carries no stored time.
     */
    @Nullable
    public static Long peek(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        return chunk == null
                ? null
                : chunk.getExistingDataOrNull(ChunkClockAttachments.LAST_SEEN_GAME_TIME);
    }

    @Nullable
    public static Reconciliation lastReconciliation(ServerLevel level, ChunkPos pos) {
        Map<Long, Reconciliation> perLevel = RECONCILED.get(level.dimension());
        return perLevel == null ? null : perLevel.get(pos.toLong());
    }

    /** The last time this clock wrote onto the chunk before it unloaded, or {@code null}. */
    @Nullable
    public static Long stampAtUnload(ServerLevel level, ChunkPos pos) {
        Map<Long, Long> perLevel = STAMP_AT_UNLOAD.get(level.dimension());
        return perLevel == null ? null : perLevel.get(pos.toLong());
    }

    public static boolean isTracked(ServerLevel level, ChunkPos pos) {
        Map<Long, Tracked> tracked = TRACKED.get(level.dimension());
        return tracked != null && tracked.containsKey(pos.toLong());
    }

    /** Whoever is handed a chunk that came back behind. Null puts the clock back to measuring. */
    public static void setReconciler(@Nullable Reconciler next) {
        reconciler = next;
    }

    /** Whoever works off what the reconciler wrote down, once per level tick. */
    public static void setDrainer(@Nullable Drainer next) {
        drainer = next;
    }

    /** Whether the sweep is what is currently running. */
    public static boolean inSweep() {
        return inSweep;
    }

    /**
     * Test-only: write a wrong time onto one chunk, to produce the stored-time-in-the-future case
     * a crash leaves behind. An offset of zero puts it back.
     */
    public static void setStampOffset(ChunkPos pos, long offset) {
        stampOffsetChunk = offset == 0L ? Long.MIN_VALUE : pos.toLong();
        stampOffset = offset;
        Meanwhile.LOGGER.info("[clock] stamp offset | chunk={} offset={}", pos, offset);
    }

    private static final class Tracked {
        private final LevelChunk chunk;
        private boolean reconciled;
        private long lastStamp = Long.MIN_VALUE;

        private Tracked(LevelChunk chunk) {
            this.chunk = chunk;
        }
    }
}
