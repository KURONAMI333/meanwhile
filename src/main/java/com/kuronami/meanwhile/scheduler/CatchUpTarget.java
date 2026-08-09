package com.kuronami.meanwhile.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What the scheduler needs to know about a kind of tick target to be allowed to stop
 * ticking it.
 *
 * <p>The verification suite has its own subject interface, which takes a
 * {@code GameTestHelper} and knows how to build an arena. This one is the production
 * counterpart: it speaks only in live world types, so the same three questions can be asked
 * from inside the tick dispatch itself.
 *
 * <p>All three are asked about a position rather than a block entity instance, because the
 * instance at a position is not stable — it can be removed and replaced while the position
 * stays in the ledger.
 */
public interface CatchUpTarget {

    /**
     * Returned by {@link #catchUp} when the target refuses to account for the window.
     *
     * <p>Declining is always safe: the caller drops the deferral and the target goes back to
     * being ticked, which is by definition correct. It is the fail-safe the whole design
     * leans on, so it has to be cheap to express.
     */
    int DECLINED = -1;

    /**
     * Whether anything outside can reach this target, in which case it must keep ticking.
     *
     * <p>Erring towards false is safe; erring towards true is silent corruption.
     */
    boolean canDefer(ServerLevel level, BlockPos pos);

    /**
     * A hash of everything about this target that something outside could change.
     *
     * <p>Taken when the target is set aside and compared before reconciling. Enumerating the
     * routes by which something can reach it cannot be finished, so the scheduler compares
     * state on the way back instead of arranging to be told.
     */
    long fingerprint(ServerLevel level, BlockPos pos);

    /**
     * Advance the target as though it had been ticked {@code ticks} times.
     *
     * @return how many ticks were actually run for real, which is the cost, or
     *         {@link #DECLINED} when the target refuses
     */
    int catchUp(ServerLevel level, BlockPos pos, int ticks);
}
