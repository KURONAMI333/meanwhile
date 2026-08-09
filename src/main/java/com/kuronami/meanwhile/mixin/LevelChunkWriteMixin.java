package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Settles a deferred target's account before anything overwrites it.
 *
 * <h3>Why here</h3>
 * <p>Every way of writing a block into a loaded world — a player breaking it, an explosion,
 * a piston, a mod calling {@code setBlockAndUpdate} — arrives at
 * {@code LevelChunk#setBlockState}. {@code Level#setBlock} is the documented entry point
 * (Level.java:231) and delegates here (Level.java:250), and this is where the old state's
 * {@code onRemove} runs (LevelChunk.java:273). That last part is what makes the depth
 * matter: a furnace's contents are dropped from {@code AbstractFurnaceBlock#onRemove}
 * (AbstractFurnaceBlock.java:62-67), so a hook has to be upstream of it or the items on the
 * floor are the ones the furnace held before the window it never got to run.
 *
 * <p>Injecting at HEAD, additively. The write proceeds either way.
 *
 * <h3>Which writes it acts on</h3>
 * <p>Only the ones that take the subject away. A write the subject survives — a rotation,
 * anything that keeps the block and a block entity valid for it — is passed straight
 * through, because reconciling there would settle the window against a block state the
 * caller is about to roll back, and neither the block entity nor the ledger would record
 * that it happened. The condition and the reasoning are on {@link #keepsTheSubject}. Such a
 * write is not free to the subject either: it changes the block state, the block state is in
 * the fingerprint, and the next reconcile refuses the window rather than folding it up under
 * a premise that has since changed.
 *
 * <h3>What it does not do</h3>
 * <p>Only the written position is looked at. A write to a neighbour — a hopper placed
 * against a deferred furnace — is not treated as reaching it. That is not free: the hopper
 * then feeds a furnace that is still set aside, the fingerprint stops matching, and the next
 * reconcile refuses the window instead of applying it. What it buys is that the cost of this
 * hook stays one map lookup per block write rather than one per neighbour per block write,
 * and the failure it leaves behind is the loud one the ledger was built to produce rather
 * than a quiet wrong answer.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkWriteMixin {

    @Shadow
    public abstract Level getLevel();

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void meanwhile$reconcileBeforeWrite(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
            CallbackInfoReturnable<BlockState> cir) {

        // Ordered by cost. The first is a volatile read and the second a plain one, so a
        // world with the scheduler off pays nothing measurable per block write.
        //
        // The second one is not an optimisation. A catch-up ticks its subject for real and a
        // real tick writes — a furnace crossing the lit boundary calls Level#setBlock on its
        // own position — so without it this hook re-enters whatever catch-up is in progress
        // and reads the subject's own progress as somebody having reached it.
        if (!DeferralScheduler.isEnabled() || DeferralScheduler.isReconciling()) {
            return;
        }
        if (!(this.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        DeferralScheduler scheduler = DeferralScheduler.of(serverLevel);
        // deferredCount first: it is an int read, where isDeferred has to make the position
        // immutable before it can look it up, and most writes happen in worlds where nothing
        // is deferred at all.
        if (scheduler.deferredCount() == 0 || !scheduler.isDeferred(pos)) {
            return;
        }
        if (keepsTheSubject(serverLevel, pos, state)) {
            return;
        }

        DeferralScheduler.Reconcile reconcile = scheduler.reconcileIfDeferred(serverLevel, pos);
        Meanwhile.LOGGER.debug("[write] before write | pos={} into={} elapsed={} real={} result={}",
                pos, state.getBlock(), reconcile.elapsedTicks(), reconcile.realTicks(),
                reconcile.result());
    }

    /**
     * Whether this write leaves the deferred subject standing.
     *
     * <p>The two ways a write takes a block entity away are the two conditions here, read
     * off the method this is injected into. A write that changes the block runs the old
     * state's {@code onRemove}, which is where a container empties itself onto the floor
     * (AbstractFurnaceBlock.java:62 guards on {@code !state.is(newState.getBlock())}). A
     * write that keeps the block can still drop the block entity, if the incoming state is
     * one that entity is not valid for (LevelChunk.java:288-291). Anything that passes both
     * is a write the subject survives.
     *
     * <p>Standing aside for those is what keeps the window honest. Reconciling ticks the
     * subject for real, and a real tick writes a block state of its own, which the write in
     * progress is about to land on top of carrying a value its caller read beforehand — so
     * reconciling here produces a block entity that has moved on and a block state that has
     * not, with no way for either to notice. Nothing is being destroyed, so there is nothing
     * to settle first.
     *
     * <p>The window is not simply given away. A write that keeps the block still changes the
     * block state, and the block state is in the fingerprint, so the next reconcile refuses
     * the window rather than folding it up under a premise that no longer holds.
     */
    @Unique
    private static boolean keepsTheSubject(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).is(state.getBlock()) || !state.hasBlockEntity()) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity != null && blockEntity.isValidBlockState(state);
    }
}
