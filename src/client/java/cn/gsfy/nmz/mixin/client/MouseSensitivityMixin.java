package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.zoom.ZoomHandler;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FOV 缩放补偿鼠标灵敏度——放大后准星跟手，不飘。
 *
 * <p>Minecraft 的灵敏度不随 FOV 缩放变化（只有原版望远镜路径单独降 8 倍），放大后同样的
 * 鼠标位移在屏幕上移动更远，手感过灵。于是在 {@code Mouse.updateMouse} 应用视角旋转时用
 * {@code @Redirect} 把 deltas 除以当前缩放除数（linear 1/D，与 Zoomify {@code relativeSensitivity=100}
 * 的意图一致）——放大后准星跟随手感与未缩放时一致；除数随缩放动画平滑过渡，灵敏度无跳变。
 */
@Mixin(Mouse.class)
public abstract class MouseSensitivityMixin {

    @Redirect(method = "updateMouse",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void nmz$scaleLookForZoom(ClientPlayerEntity player, double dx, double dy) {
        // 未缩放 / 非 Zombies 时 divisor = 1.0，除法是恒等，天然无副作用
        double divisor = ZoomHandler.INSTANCE.getCurrentZoomDivisor();
        player.changeLookDirection(dx / divisor, dy / divisor);
    }
}
