package com.kuronami.meanwhile.scheduler;

/**
 * The scheduler a {@link net.minecraft.server.level.ServerLevel} carries.
 *
 * <p>Implemented by a mixin on {@code ServerLevel}, which is what lets
 * {@link DeferralScheduler#of} be a field read. The dispatch asks for the scheduler once per
 * ticking block entity per tick, so what that lookup costs is paid by every machine in the
 * world whether or not the scheduler has any interest in it — the one place in this mod where
 * a constant factor is the whole cost.
 *
 * <p>The names carry the mod's prefix because they are added to a class the game and every
 * other mod also own.
 */
public interface SchedulerHolder {

    /** Never null on a level that has finished being constructed. */
    DeferralScheduler meanwhile$scheduler();
}
