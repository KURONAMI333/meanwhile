package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Settles a deferred target's account before it is written to disk.
 *
 * <h3>Why this is a surface of its own</h3>
 * <p>A chunk save takes nothing and changes nothing, so the hook that watches for writes never
 * sees it, and it reads no signal, so the hook that watches for comparator reads never sees it
 * either. It serialises the block entity's raw fields unconditionally — there is no dirty gate
 * to hang anything on. A deferred subject saved before its account is settled therefore has the
 * state it stopped ticking at burnt into the region file, and unlike a comparator misreading by
 * one tick, that survives a restart: the ledger is deliberately not persisted, so on load the
 * subject is ticked normally from whatever was written. Measured before any of this was built
 * (_probe-tick-catchup/FINDINGS.md §2.5-(6): {@code 4702e59997ec3aee} either way when flushed
 * first, {@code f4822e2189c4ad61} when not).
 *
 * <h3>Why here rather than at the serializer</h3>
 * <p>{@code ChunkSerializer#write} is where a chunk becomes a tag, and it reaches every block
 * entity through one loop (ChunkSerializer.java:359-364) that calls
 * {@code ChunkAccess#getBlockEntityNbtForSaving} — the only call to that method in the game, by
 * grep over the 6,315 patched sources, and {@code ChunkSerializer#write} in turn has one caller,
 * {@code ChunkMap#save} (ChunkMap.java:790). So the two are the same coverage, and the one
 * downstream takes the position as an argument instead of making this iterate the chunk to find
 * out which of its block entities are deferred. Same reasoning as the read hook sitting at
 * {@code BlockStateBase#getAnalogOutputSignal} rather than inside the comparator.
 *
 * <p>Injecting at HEAD, additively, on {@link LevelChunk}'s override
 * (LevelChunk.java:414-435). It has to be HEAD rather than RETURN: the tag is built from the
 * block entity's fields at the moment the method runs, so a catch-up that happens afterwards
 * leaves the world right and the tag stale, which is precisely the failure this exists for and
 * is invisible to any assertion that looks at the world after the save. {@code PersistFaceGameTests}
 * compares the returned tag rather than the subject for that reason.
 *
 * <p>Reconciling from inside the serializer's loop is safe because
 * {@code ChunkAccess#getBlockEntitiesPos} hands out a fresh {@code HashSet} rather than a view
 * of the live maps (ChunkAccess.java:154-158), so a catch-up that adds or removes a block entity
 * cannot break the iteration that called it.
 *
 * <h3>Standing aside for catch-ups</h3>
 * <p>Same reason as the other two faces, and it costs nothing to keep: a catch-up ticks its
 * subject for real, a real tick can write, and a write can dirty and re-save. See the note on
 * {@link DeferralScheduler#isReconciling()}.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSaveMixin {

    @Shadow
    public abstract Level getLevel();

    @Inject(
            method = "getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"))
    private void meanwhile$reconcileBeforeSave(
            BlockPos pos, HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {

        // Ordered by cost, as in the other two faces: a volatile read, then a plain one, so a
        // world with the scheduler off pays nothing measurable per block entity per save.
        if (!DeferralScheduler.isEnabled() || DeferralScheduler.isReconciling()) {
            return;
        }
        if (!(this.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        DeferralScheduler scheduler = DeferralScheduler.of(serverLevel);
        // deferredCount first: an int read, where isDeferred has to make the position immutable
        // before it can look it up, and most saves happen with nothing deferred at all.
        if (scheduler.deferredCount() == 0 || !scheduler.isDeferred(pos)) {
            return;
        }

        DeferralScheduler.Reconcile reconcile = scheduler.reconcileIfDeferred(serverLevel, pos);
        Meanwhile.LOGGER.debug("[save] before save | pos={} elapsed={} real={} result={}",
                pos, reconcile.elapsedTicks(), reconcile.realTicks(), reconcile.result());
    }
}
