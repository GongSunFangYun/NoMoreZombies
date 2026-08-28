package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
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
 * 自由视角封禁玩家输入——防止盲视角误点把攻击/挖掘/使用/交互打在玩家当前位置。
 *
 * <p>默认「允许玩家输入」关闭时，相机飞行中的盲视角误点可能在玩家当前位置触发攻击/挖掘/使用/
 * 交互。于是每个交互方法都在 {@link ClientPlayerInteractionManager} 源头用 {@code @Inject} 挂
 * {@code @At("HEAD")}、可取消，全量封禁：有返回值的全部短路成 false/PASS，void 的直接 cancel。
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class FreeCameraInteractionMixin {

    @Inject(method = "attackBlock(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateBlockBreakingProgress(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockUpdateBlockBreaking(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        // isSelfTarget：freecam 下准星可指向自己冻结的身体，攻击自身会被服务器硬拒踢出
        // （「Cannot interact with self!」，涉嫌反作弊，封号风险）——无条件封禁，与允许输入无关。
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(target)) {
            ci.cancel();
        }
    }

    @Inject(method = "interactBlock(Lnet/minecraft/client/network/ClientPlayerEntity;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                        CallbackInfoReturnable<ActionResult> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "interactEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractEntity(PlayerEntity player, Entity entity, Hand hand,
                                         CallbackInfoReturnable<ActionResult> cir) {
        // isSelfTarget：与攻击同理，交互自身（右键自己）也会被服务器硬拒踢出——无条件封禁。
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(entity)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "interactEntityAtLocation(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/hit/EntityHitResult;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractEntityAtLocation(PlayerEntity player, Entity entity, EntityHitResult hitResult,
                                                   Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        // isSelfTarget：带位置精度的实体交互，自身同样会被硬拒——无条件封禁。
        if (FreeCameraHandler.shouldPreventInputs() || FreeCameraHandler.isSelfTarget(entity)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "interactItem(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "stopUsingItem(Lnet/minecraft/entity/player/PlayerEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$blockStopUsingItem(PlayerEntity player, CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            ci.cancel();
        }
    }
}
