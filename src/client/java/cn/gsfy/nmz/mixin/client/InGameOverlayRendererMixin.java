package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.nocombustioneffect.CombustionEffectManager;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 无火焰效果——着火时不再糊一脸全屏火焰。
 *
 * <p>玩家着火时原版每帧绘制全屏火焰遮罩，注入 {@code InGameOverlayRenderer.renderFireOverlay}
 * 的 {@code @At("HEAD")} cancel：由 {@link CombustionEffectManager#shouldHide} 判定后取消，
 * 完全去除、不重绘。实现注意：目标是 private static 方法，handler 必须声明 static；
 * {@code @Inject} 可注入 private，无需 accesswidener。
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {

    @Inject(method = "renderFireOverlay(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"), cancellable = true)
    private static void nmz$renderFireOverlay(MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (CombustionEffectManager.shouldHide()) {
            ci.cancel();
        }
    }
}
