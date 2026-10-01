package cn.gsfy.nmz.mixin.client.zoom;

import cn.gsfy.nmz.client.features.zoom.ZoomHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooth zoom (a simplified Zoomify)—zoom = FOV division.
 *
 * <p>The target is {@code net.minecraft.client.render.GameRenderer}. Injects
 * {@link GameRenderer#getFov(Camera, float, boolean)} (private float,
 * 1.21.4 yarn name, called exactly once per frame's renderWorld) at
 * {@code @At("RETURN")}: the return value feeds the projection matrix
 * directly, and while active the FOV is divided by the divisor to zoom in
 * (divisor &gt;1, computed by
 * {@link ZoomHandler#getZoomDivisor(float)})—the world and the held item
 * zoom together. The crosshair does not depend on FOV, keeps its pixel size,
 * and the hit ray direction does not change with FOV, so
 * {@code GameRendererCrosshairMixin} is unaffected.
 */
@Mixin(GameRenderer.class)
public abstract class ZoomMixin {

    /** getFov RETURN rewrite: while zooming, divides the FOV by the divisor,
     *  world and hand zoom together. */
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F",
            at = @At("RETURN"), cancellable = true)
    private void nmz$zoomDivisor(Camera camera, float tickDelta, boolean changingFov,
                                 CallbackInfoReturnable<Float> cir) {
        if (ZoomHandler.isActive()) {
            cir.setReturnValue(cir.getReturnValue() / ZoomHandler.INSTANCE.getZoomDivisor(tickDelta));
        }
    }
}