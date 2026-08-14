package com.kuronami.meanwhile.elapsed;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * The measurement-only entry points of {@link ChunkCatchUp} and {@link ChunkClock}, reachable by a
 * gate that does not live in this package.
 *
 * <p>Four methods stayed {@code public} on the product classes after the rest were narrowed, and
 * none of them stayed public for the product's sake — it calls no one of them. What kept them
 * public was <b>where the gate calling them lives</b>: {@code compat} arms a chunk clock that
 * belongs to {@code elapsed}. A jar consumer could reach all four, which is a wider surface than
 * the mod means to have (GAP_LOG G157).
 *
 * <p>This class is on the {@code gametest} source set and shares the product's package name, so
 * package-private is enough for the product and there is nothing to reach in the jar. A gate
 * outside this package imports this instead.
 *
 * <h3>One naming site for the reset</h3>
 * <p>{@link #forget} is the only route to {@link ChunkCatchUp#forget}, in-package gates included.
 * The reset refuses when the drain has work in flight and names the gate that called it, and that
 * name has to be worked out from the stack. Routing every caller through here is what let the
 * {@link StackWalker} leave the product class: it is one place, on the source set that ships
 * nowhere, rather than a diagnostic compiled into the mod for the benefit of tests.
 */
public final class CatchUpTestAccess {

    private CatchUpTestAccess() {
    }

    /** See {@link ChunkClock#rearm}. */
    public static void rearm(ServerLevel level, ChunkPos pos) {
        ChunkClock.rearm(level, pos);
    }

    /** See {@link ChunkClock#setStampOffset}. */
    public static void setStampOffset(ChunkPos pos, long offset) {
        ChunkClock.setStampOffset(pos, offset);
    }

    /** See {@link ChunkCatchUp#setMode}. */
    public static void setMode(ChunkCatchUp.Mode next) {
        ChunkCatchUp.setMode(next);
    }

    /**
     * Drops the outstanding jobs and the debts of this gate's own chunks. See
     * {@link ChunkCatchUp#forget}.
     *
     * <p>With no chunks named this is the caller's whole arena, which is the widest set it owns.
     * Name chunks to go narrower — the Nether half of {@code DimensionKeyGameTests} owns the one
     * chunk position it forced there and no more.
     *
     * <h3>The refusal</h3>
     * <p><b>A chunk outside the caller's arena is not the caller's to forget</b>, and asking for
     * one throws. The reach of {@code forget} is the chunks it is handed: their debt is zeroed
     * and their queued work dropped. Handed another gate's chunk mid-window, it destroys work
     * that is then re-queued and paid off inside whatever window is open at the time, and
     * neither gate fails — the shape this campaign found four times (GAP_LOG G156, G157, G158).
     *
     * <p>This replaces a refusal on "is any work in flight anywhere". That one called a state
     * that is normal in production — a debt whose chunk has gone away, waiting for it to come
     * back — an error, and killed 2 runs in 7 (G159, G160).
     *
     * @throws IllegalStateException if a named chunk is not in the caller's arena
     */
    public static void forget(GameTestHelper helper, ServerLevel level, ChunkPos... chunks) {
        List<ChunkPos> arena = arenaChunks(helper);
        List<ChunkPos> owned = chunks.length == 0 ? arena : List.of(chunks);
        for (ChunkPos pos : owned) {
            if (!arena.contains(pos)) {
                throw new IllegalStateException("ChunkCatchUp.forget("
                        + level.dimension().location() + ") was asked for chunk " + pos
                        + ", which does not belong to " + caller() + ": its arena is " + arena
                        + ". forget() zeroes a chunk's debt and drops its queued work, so a chunk"
                        + " owned by another gate would have its window destroyed mid-flight and"
                        + " neither gate would fail -- see GAP_LOG G156, G158 and G160. Forget"
                        + " your own arena.");
            }
        }
        ChunkCatchUp.forget(level, caller(), owned);
    }

    /**
     * Drops what the catch-up holds in memory about this gate's own chunks and leaves what the
     * chunks carry alone. See {@link ChunkCatchUp#dropInFlightState}.
     *
     * <p>The reach and the refusal are {@link #forget}'s, for the same reasons. This one is
     * narrower in what it destroys, not in whose chunks it may be pointed at.
     *
     * @throws IllegalStateException if a named chunk is not in the caller's arena
     */
    public static void dropInFlightState(GameTestHelper helper, ServerLevel level,
                                         ChunkPos... chunks) {
        List<ChunkPos> arena = arenaChunks(helper);
        List<ChunkPos> owned = chunks.length == 0 ? arena : List.of(chunks);
        for (ChunkPos pos : owned) {
            if (!arena.contains(pos)) {
                throw new IllegalStateException("ChunkCatchUp.dropInFlightState("
                        + level.dimension().location() + ") was asked for chunk " + pos
                        + ", which does not belong to " + caller() + ": its arena is " + arena
                        + ". Dropping another gate's queued work re-queues it into whatever"
                        + " window is open at the time and neither gate fails -- see GAP_LOG"
                        + " G156, G158 and G160.");
            }
        }
        ChunkCatchUp.dropInFlightState(level, caller(), owned);
    }

    /** See {@link ChunkCatchUp#paidUpToFor}. */
    public static long paidUpToFor(ServerLevel level, ChunkPos pos) {
        return ChunkCatchUp.paidUpToFor(level, pos);
    }

    /**
     * The chunks the framework force-loaded for this arena, from the bounding box
     * {@code StructureUtils.forceLoadChunks} was handed, plus the structure block's own chunk in
     * case it sits outside. A forced ticket propagates outwards, so an arena with one chunk still
     * held stays loaded through its neighbour.
     *
     * <p>This is what a gate owns. GameTest places arenas wherever the run puts them, so a gate
     * cannot name its chunks as constants; the bounds are the only source that moves with them.
     */
    public static List<ChunkPos> arenaChunks(GameTestHelper helper) {
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

    /**
     * The first frame outside this class, as {@code Class#method}.
     *
     * <p>Diagnostic only: which gate asked for the global reset, and from which of its own
     * methods. Both the refusal and the log line carry it, and the difference between an arm path
     * and a teardown path is what G156 turned on.
     */
    private static String caller() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(frame -> !frame.getClassName()
                                .equals(CatchUpTestAccess.class.getName()))
                        .findFirst()
                        .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                        .orElse("<unknown>"));
    }
}
