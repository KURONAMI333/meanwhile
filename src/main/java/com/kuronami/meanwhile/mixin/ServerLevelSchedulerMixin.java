package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import com.kuronami.meanwhile.scheduler.SchedulerHolder;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives each server level its own scheduler, as a field on the level.
 *
 * <p>The dispatch hook asks for the scheduler once for every ticking block entity on every
 * tick, before it knows whether the block entity is one the scheduler has any interest in. A
 * map keyed by level makes that a hash lookup, and a map that has to be locked makes it a
 * lock as well — a per-tick cost on a mod whose entire purpose is to remove per-tick costs,
 * and one that would sit inside any measurement taken of it.
 *
 * <p>Assigned where it is declared, so the field is set by the level's own constructor and
 * the one-per-level invariant needs no lock to hold: there is no window in which two callers
 * can each find nothing and each make one.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSchedulerMixin implements SchedulerHolder {

    @Unique
    private final DeferralScheduler meanwhile$scheduler = DeferralScheduler.create();

    @Override
    public DeferralScheduler meanwhile$scheduler() {
        return this.meanwhile$scheduler;
    }
}
