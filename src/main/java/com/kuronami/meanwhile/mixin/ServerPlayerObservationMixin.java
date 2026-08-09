package com.kuronami.meanwhile.mixin;

import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Tells the scheduler which positions somebody currently has a screen open on.
 *
 * <p>Not a fourth reconcile surface, and the difference is the whole point. The three
 * surfaces each wrap one read and settle the subject before answering it. A screen is not
 * one read: it is re-sent from the block entity for as long as it stays open
 * ({@code ServerPlayer.java:518}, and the values come off the {@code ContainerData} at
 * {@code AbstractFurnaceBlockEntity.java:71}). Settling once at the moment it opens buys one
 * correct frame and then the bar stops, which is measured — a deferred furnace pushed the
 * client nothing across a hundred ticks while a ticked one pushed 208.
 *
 * <p>So what gets registered is the observation, not the read, and the scheduler refuses to
 * defer anything while an observation is live. The window is closed for as long as somebody
 * is looking.
 *
 * <p>{@code ServerPlayer#openMenu} is where every container screen on the server is opened
 * ({@code ServerPlayer.java:1147}). The position comes from the provider being the block
 * entity itself, which is what {@code BaseEntityBlock#getMenuProvider} hands back
 * ({@code BaseEntityBlock.java:42-45}). A provider that is not a block entity has no
 * position to protect and is left alone.
 *
 * <p>{@code doCloseContainer} is the matching end. Every route out arrives there: the client
 * closing the screen ({@code ServerGamePacketListenerImpl.java:1694}), the server closing it
 * because the player walked out of range ({@code ServerPlayer.java:519-521}), opening a
 * second screen ({@code ServerPlayer.java:1155}), and the player leaving the world, since
 * {@code PlayerList#remove} goes through {@code ServerLevel#removePlayerImmediately} into
 * {@code Player#remove}, which closes an open container ({@code Player.java:1457-1463}).
 * The scheduler prunes dead watchers on its own as well, so a route nobody enumerated costs
 * that position its optimisation rather than its correctness.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerObservationMixin {

    /** Where this player's open screen is looking, or null when nothing is open. */
    @Unique
    private BlockPos meanwhile$observing;

    @Inject(
            method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
            at = @At("RETURN"))
    private void meanwhile$beginObserving(
            MenuProvider menu,
            Consumer<?> extraDataWriter,
            CallbackInfoReturnable<OptionalInt> cir) {

        if (!DeferralScheduler.isEnabled() || cir.getReturnValue().isEmpty()) {
            return;
        }
        if (!(menu instanceof BlockEntity blockEntity)) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        BlockPos pos = blockEntity.getBlockPos().immutable();
        this.meanwhile$observing = pos;
        DeferralScheduler.of(serverLevel).beginObserving(serverLevel, pos, self);
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void meanwhile$endObserving(CallbackInfo ci) {
        BlockPos pos = this.meanwhile$observing;
        if (pos == null) {
            return;
        }
        this.meanwhile$observing = null;
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel) {
            DeferralScheduler.of(serverLevel).endObserving(pos, self);
        }
    }
}
