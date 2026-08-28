package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.CameraEntity;
import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自由视角的玩家侧改造——冻结本体、装成相机、封住挥动，三件事一次做完。
 *
 * <p><b>冻结输入</b>：玩家 tick 期间把 {@code this.input} 换成全零 {@code Input}，WASD 挪不动
 * 冻结的玩家；RETURN 时再还原真输入。之所以敢换基类实例：tick()V 里的 {@code input.tick()} 是
 * 多态虚调用，无 CHECKCAST，基类实例安全。仅「允许玩家移动」关闭时生效。
 *
 * <p><b>{@code isCamera()} 欺骗</b>：让玩家自认仍是相机——{@code sendMovementPackets/
 * tickNewAi/tickMovement} 都以它决定「活着、更新着、发位置包」，冻结期间玩家不僵死、
 * 服务器侧位置保持。ClientPlayerEntity 自身从不调用 {@code setCameraEntity}，强制返回 true 安全。
 *
 * <p><b>封禁挥动</b>：「允许玩家输入」关闭时 {@code swingHand} 直接 cancel，配合
 * {@code FreeCameraInteractionMixin} 从交互源头封禁。
 */
@Mixin(ClientPlayerEntity.class)
public abstract class FreeCameraPlayerMixin {

    /** HEAD 是否已冻结（RETURN 依据它对称还原，防 tick 中途状态翻转导致输入永久冻结）。 */
    @Unique
    private boolean nmz$inputFrozen;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void nmz$freezeInputHead(CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventMovement()) {
            ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
            // 真输入记录到 handler 静态字段，且仅当当前输入非冻结实例时更新——若上次 tick 异常
            // 导致 input 残留为冻结实例，这里不会把它误存成「真输入」，RETURN 仍能恢复到真实例。
            FreeCameraHandler.captureRealInput(self.input);
            self.input = FreeCameraHandler.getFrozenInput();
            this.nmz$inputFrozen = true;
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void nmz$freezeInputReturn(CallbackInfo ci) {
        if (this.nmz$inputFrozen) {
            // 恢复 handler 记录的真输入（而非本 tick HEAD 的当前值），防残留污染；
            // 配合 handler 每 tick 兜底，任何中断导致的残留都能自愈。
            ((ClientPlayerEntity) (Object) this).input = FreeCameraHandler.getRealInput();
            this.nmz$inputFrozen = false;
        }
    }

    @Inject(method = "isCamera()Z", at = @At("HEAD"), cancellable = true)
    private void nmz$fakeCamera(CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.isActive() && CameraEntity.originalCameraWasPlayer()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "swingHand(Lnet/minecraft/util/Hand;)V", at = @At("HEAD"), cancellable = true)
    private void nmz$blockSwing(Hand hand, CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            ci.cancel();
        }
    }
}
