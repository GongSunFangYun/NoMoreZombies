package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Free-camera player-input ban—prevents a blind-view misclick from firing
 * attack / mine / use / interact at the player's current position.
 *
 * <p>The target is {@code net.minecraft.client.network.ClientPlayerInteractionManager}.
 * Why ban at the interactionManager source: it is the only exit for client
 * interaction packets—attack, mine, right-click and item use all build their
 * packets here before the server sees them, more thorough than intercepting
 * GUI / key layers (upper layers always have bypasses, and they cannot catch
 * continuous-mining loops). With "allow player input" off by default, a blind
 * misclick while the camera flies could trigger attack / mine / use /
 * interact at the player's position. So each interaction method gets
 * {@code @Inject} at {@code @At("HEAD")}, cancellable, uniformly banning
 * everything: value-returning methods all short-circuit to false/PASS, void
 * ones cancel outright.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class FreeCameraInteractionMixin {

    /** Bans starting to mine a block: on shouldPreventInputs, short-circuits
     *  to false, no mine request is sent. */
    @Inject(method = "attackBlock(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    /** Bans breaking a block for real: on shouldPreventInputs, short-circuits
     *  to false, the break does not settle. */
    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    /** Bans continuous mining: on shouldPreventInputs, short-circuits to
     *  false, mining progress no longer accumulates. */
    @Inject(method = "updateBlockBreakingProgress(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockUpdateBlockBreaking(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    /** Bans attacking an entity: cancels on shouldPreventInputs or a self
     *  target (anti-kick). */
    @Inject(method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        // isSelfTarget: under freecam the crosshair can point at your own
        // frozen body, and attacking yourself gets kicked by the server;
        // "Cannot interact with self!" risks an anti-cheat flag - banned
        // unconditionally, independent of "allow player input"
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(target)) {
            ci.cancel();
        }
    }

    /** Bans right-clicking a block: on shouldPreventInputs, short-circuits to
     *  PASS, no blind misclick opens doors or buttons. */
    @Inject(method = "interactBlock(Lnet/minecraft/client/network/ClientPlayerEntity;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                        CallbackInfoReturnable<ActionResult> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    /** Bans right-clicking an entity: short-circuits to PASS on
     *  shouldPreventInputs or a self target. */
    @Inject(method = "interactEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractEntity(PlayerEntity player, Entity entity, Hand hand,
                                         CallbackInfoReturnable<ActionResult> cir) {
        // isSelfTarget: like attacking, interacting with yourself is also
        // hard-rejected and kicked by the server - banned unconditionally
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(entity)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    /** Bans position-precise entity interaction: short-circuits to PASS on
     *  shouldPreventInputs or a self target. */
    @Inject(method = "interactEntityAtLocation(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/hit/EntityHitResult;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractEntityAtLocation(PlayerEntity player, Entity entity, EntityHitResult hitResult,
                                                   Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        // isSelfTarget: a position-precise entity interaction, self is still
        // hard-rejected - banned unconditionally
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(entity)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    /** Bans item use: on shouldPreventInputs, short-circuits to PASS, no
     *  blind eating. */
    @Inject(method = "interactItem(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    /** Bans stopping item use: on shouldPreventInputs, cancels, no release
     *  packet is sent. */
    @Inject(method = "stopUsingItem(Lnet/minecraft/entity/player/PlayerEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockStopUsingItem(PlayerEntity player, CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            ci.cancel();
        }
    }
}