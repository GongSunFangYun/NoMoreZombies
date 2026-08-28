package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.zoom.ZoomHandler;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 缩放激活时鼠标滚轮热调倍率——不写配置的会话级调整（Zoomify 式）。
 *
 * <p>注入 {@code Mouse.onMouseScroll} 的 {@code @At("HEAD")}、可取消：{@link ZoomHandler#onMouseScroll}
 * 返回 true（缩放中）就消费事件——每格滚轮 ±1 倍率（1~10）调整当前缩放，且不再执行原版热键栏切换；
 * 缩放完全收回后热调倍率清零，下次激活回到配置初始倍率。未缩放时滚轮照常切热键栏。
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void nmz$scrollZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ZoomHandler.INSTANCE.onMouseScroll(vertical)) {
            ci.cancel();
        }
    }
}
