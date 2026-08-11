package com.kuronami.meanwhile.elapsed;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

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
     * Drops every outstanding job and every debt the run has written down. See
     * {@link ChunkCatchUp#forget}, including what it throws and why.
     */
    public static void forget(ServerLevel level) {
        ChunkCatchUp.forget(level, caller());
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
