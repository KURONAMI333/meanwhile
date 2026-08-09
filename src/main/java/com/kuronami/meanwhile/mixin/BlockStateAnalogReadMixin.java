package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Settles a deferred target's account before anything reads it.
 *
 * <h3>Why this is a surface of its own</h3>
 * <p>A comparator takes nothing and changes nothing. It reads a container and reports a
 * number, so the hook that watches for writes never sees it, and the account is never
 * settled: the number the redstone acts on is the one the subject had when it stopped being
 * ticked. Getting the state right afterwards does not help, because a circuit that has
 * already fired cannot be un-fired. That is the failure this exists for, and it was measured
 * before any of this was built (_probe-tick-catchup/FINDINGS.md §2.5-(5)).
 *
 * <h3>Why here rather than in the comparator</h3>
 * <p>{@code ComparatorBlock#getInputSignal} is where the read is decided
 * (ComparatorBlock.java:97-118), and it reads the subject twice: directly at the block it
 * faces (:103) and, when that block is a redstone conductor, through it at the block beyond
 * (:110). Injecting there means re-deriving which of those two positions is being read, which
 * is duplicating vanilla's branch in a place that has to agree with it exactly. One step
 * downstream, {@code BlockStateBase#getAnalogOutputSignal} (BlockBehaviour.java:682-684) takes
 * that position as an argument, and both call sites go through it — those two are the only
 * callers in the game, by grep over the 6,315 patched sources. The read of a modded machine by
 * a modded comparator-alike arrives here too, without this having to know about it.
 *
 * <p>Injecting at HEAD, additively. The read proceeds either way, and reads the subject the
 * catch-up has just brought up to date because
 * {@code AbstractFurnaceBlock#getAnalogOutputSignal} takes its answer from
 * {@code level.getBlockEntity(pos)} at the moment it is called (AbstractFurnaceBlock.java:89)
 * rather than from the block state the caller was holding.
 *
 * <h3>Standing aside for catch-ups</h3>
 * <p>Not an optimisation, and not the same case as the write hook's. A furnace crossing the
 * lit boundary during its own catch-up writes its block state, which updates its neighbours,
 * which is exactly how an adjacent comparator is told to re-read it — so this hook is
 * re-entered from inside the catch-up it started. The ledger still holds the fingerprint from
 * before, so a nested reconcile would read the subject's own progress as somebody else having
 * reached it and distrust the position for the session, while the outer read still returns the
 * right number. {@link DeferralScheduler#isReconciling()} is what keeps that from happening,
 * and {@code ReadFaceGameTests} asserts the position comes out trusted rather than only
 * correct.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateAnalogReadMixin {

    @Inject(
            method = "getAnalogOutputSignal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I",
            at = @At("HEAD"))
    private void meanwhile$reconcileBeforeRead(
            Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {

        // Ordered by cost, as in LevelChunkWriteMixin: a volatile read, then a plain one, so
        // a world with the scheduler off pays nothing measurable per comparator read.
        if (!DeferralScheduler.isEnabled() || DeferralScheduler.isReconciling()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        DeferralScheduler scheduler = DeferralScheduler.of(serverLevel);
        if (scheduler.deferredCount() == 0 || !scheduler.isDeferred(pos)) {
            return;
        }

        DeferralScheduler.Reconcile reconcile = scheduler.reconcileIfDeferred(serverLevel, pos);
        Meanwhile.LOGGER.debug("[read] before read | pos={} elapsed={} real={} result={}",
                pos, reconcile.elapsedTicks(), reconcile.realTicks(), reconcile.result());
    }
}
