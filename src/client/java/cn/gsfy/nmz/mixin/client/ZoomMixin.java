package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.zoom.ZoomHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 平滑缩放（Zoomify 简化版）——缩放 = FOV 除法。
 *
 * <p>注入 {@link GameRenderer#getFov(Camera, float, boolean)}（private float，1.21.4 yarn 名，
 * 每帧 renderWorld 恰好调用一次）的 {@code @At("RETURN")}：返回值直接喂投影矩阵，开启时把 FOV
 * 除以除数实现放大（除数 &gt;1 由 {@link ZoomHandler#getZoomDivisor} 计算）——世界与手持物品同时缩放；
 * 十字准星不依赖 FOV，像素尺寸不变，命中射线方向也不随 FOV 变，不影响 {@code GameRendererCrosshairMixin}。
 */
@Mixin(GameRenderer.class)
public abstract class ZoomMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F",
            at = @At("RETURN"), cancellable = true)
    private void nmz$zoomDivisor(Camera camera, float tickDelta, boolean changingFov,
                                 CallbackInfoReturnable<Float> cir) {
        if (ZoomHandler.isActive()) {
            cir.setReturnValue(cir.getReturnValue() / ZoomHandler.INSTANCE.getZoomDivisor(tickDelta));
        }
    }
}
