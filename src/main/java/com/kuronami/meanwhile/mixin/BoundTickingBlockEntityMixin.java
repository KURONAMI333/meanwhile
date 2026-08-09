package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one place every block entity in the world gets its tick.
 *
 * <p>{@code BlockEntityTicker} is a functional interface, so it cannot be mixed into
 * directly. Vanilla calls it from the {@code BoundTickingBlockEntity} adapter inside
 * {@code LevelChunk}, and a modded machine has no way to be ticked that does not go through
 * there: registering a ticker against a {@code BlockEntityType} is the only route in. That
 * is what makes a scheduler possible without patching a single other mod.
 *
 * <p>Wrapping rather than overwriting or redirecting. Other mods wrap this same call — the
 * exception guard in free-server-saver does — and wrapping composes where the other two do
 * not.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private <T extends BlockEntity> void meanwhile$deferBlockEntityTick(
            BlockEntityTicker<T> instance,
            Level level,
            BlockPos pos,
            BlockState state,
            T blockEntity,
            Operation<Void> original) {

        if (!DeferralScheduler.isEnabled()
                || blockEntity == null
                || !(level instanceof ServerLevel serverLevel)) {
            original.call(instance, level, pos, state, blockEntity);
            return;
        }

        if (DeferralScheduler.of(serverLevel).shouldSkipTick(serverLevel, pos, blockEntity)) {
            return;
        }
        original.call(instance, level, pos, state, blockEntity);
    }
}
