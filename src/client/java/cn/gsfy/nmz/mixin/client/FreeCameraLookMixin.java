package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自由视角鼠标转向转发——把视角增量从玩家转到替身相机，玩家朝向不动。
 *
 * <p>1.21.4 {@code Mouse.updateMouse} 直接对 {@code client.player.changeLookDirection} 调增量
 * （而非 getCameraEntity()），于是拦截 {@link Entity#changeLookDirection}：玩家那次调用在
 * {@code @At("HEAD")} 取消，增量转发给替身相机实体（{@code FreeCameraHandler.rotateCamera}）。
 *
 * <p>递归天然免疫：转发内部走 {@code camera.changeLookDirection}，也会命中本注入，但
 * {@code this != mc.player} 直接放行（相机由 vanilla 逻辑旋转，含 0.15 灵敏度与 pitch 夹取）。
 */
@Mixin(Entity.class)
public abstract class FreeCameraLookMixin {

    @Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"), cancellable = true)
    private void nmz$redirectLookToCamera(double yawChange, double pitchChange, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.shouldPreventMovement() && (Object) this == mc.player) {
            FreeCameraHandler.rotateCamera((float) yawChange, (float) pitchChange);
            ci.cancel();   // 玩家朝向不被改
        }
    }
}
