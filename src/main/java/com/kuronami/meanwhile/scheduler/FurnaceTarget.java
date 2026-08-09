package com.kuronami.meanwhile.scheduler;

import com.kuronami.meanwhile.catchup.FurnaceCatchUp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Wires {@link FurnaceCatchUp} into the scheduler. Nothing but plumbing: every decision
 * about what is safe for a furnace lives in the catch-up, which the verification suite
 * measures directly.
 */
public final class FurnaceTarget implements CatchUpTarget {

    public static final FurnaceTarget INSTANCE = new FurnaceTarget();

    /**
     * The fingerprint of a position that no longer holds a furnace.
     *
     * <p>Deliberately a value the real fingerprint cannot produce, so a block entity that
     * vanished while deferred reads as a mismatch and the window is refused rather than
     * applied to whatever took its place.
     */
    private static final long NO_FURNACE = Long.MIN_VALUE;

    private FurnaceTarget() {
    }

    @Override
    public boolean canDefer(ServerLevel level, BlockPos pos) {
        return furnaceAt(level, pos) != null && FurnaceCatchUp.canCatchUp(level, pos);
    }

    /**
     * The catch-up's own fingerprint, plus the block state.
     *
     * <p>{@code FurnaceCatchUp} hashes the block entity: the two counters and the three
     * slots. That is everything the catch-up itself reads, and it was enough while the only
     * thing that could reach a deferred furnace was something changing its contents.
     *
     * <p>It is not enough once a write can land on the position without destroying it. Such
     * a write changes the block state and leaves the block entity alone, so a fingerprint
     * taken over the block entity says nothing happened — and the window then gets folded up
     * on the assumption that the state it was running under still holds. For a furnace the
     * property in reach is which way it faces, which changes nothing; for the modded
     * machines this is meant to generalise to, it is whichever properties that machine keeps
     * in its block state.
     *
     * <p>Which properties matter is not decided here, and deliberately so. Enumerating the
     * ones that affect behaviour is the same shape of problem as enumerating the routes by
     * which something can reach a container, and that enumeration is the thing this project
     * established cannot be finished. The whole state goes in. A furnace that somebody
     * rotates therefore stops being deferred, which costs that one furnace its optimisation
     * and costs correctness nothing.
     *
     * <p>{@code Block#getId} rather than the state's own hash, because {@code BlockState}
     * inherits identity hashing and two distinct states could collide within a run. The id
     * comes from the block state registry (Block.java:116-120) and is distinct per state.
     */
    @Override
    public long fingerprint(ServerLevel level, BlockPos pos) {
        AbstractFurnaceBlockEntity furnace = furnaceAt(level, pos);
        if (furnace == null) {
            return NO_FURNACE;
        }
        long hash = FurnaceCatchUp.fingerprint(furnace);
        return hash * 31L + Block.getId(level.getBlockState(pos));
    }

    @Override
    public int catchUp(ServerLevel level, BlockPos pos, int ticks) {
        AbstractFurnaceBlockEntity furnace = furnaceAt(level, pos);
        if (furnace == null || !FurnaceCatchUp.canCatchUp(level, pos)) {
            return DECLINED;
        }
        return FurnaceCatchUp.catchUp(level, pos, furnace, ticks);
    }

    @Nullable
    private static AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }
}
