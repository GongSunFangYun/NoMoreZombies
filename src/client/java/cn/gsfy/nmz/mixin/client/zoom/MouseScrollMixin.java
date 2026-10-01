package cn.gsfy.nmz.mixin.client.zoom;

import cn.gsfy.nmz.client.features.zoom.ZoomHandler;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While zoom is active, the mouse wheel hot-adjusts zoom—a session-level
 * adjustment that does not write config (Zoomify-style).
 *
 * <p>The target is {@code net.minecraft.client.Mouse}. Injects
 * {@code Mouse.onMouseScroll} at {@code @At("HEAD")}, cancellable: when
 * {@link ZoomHandler#onMouseScroll(double)} returns true (feature enabled
 * and currently zooming), the event is consumed—each wheel notch adjusts
 * the current zoom by ±1 (1–10) and the vanilla hotbar switch is skipped.
 * Once the zoom fully retracts, the hot-adjust zoom zeroes; the next
 * activation starts from the config's initial zoom. While not zooming, the
 * wheel switches the hotbar as usual.
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {

    /** Injection point: onMouseScroll HEAD—while zooming, hot-adjust zoom;
     *  otherwise let the hotbar switch through. */
    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void nmz$scrollZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ZoomHandler.INSTANCE.onMouseScroll(vertical)) {
            ci.cancel();
        }
    }
}