package cn.gsfy.nmz.mixin.client.render;

import cn.gsfy.nmz.client.features.fireoverlay.FireOverlayManager;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No-fire effect—while burning, the screen fire overlay no longer floods the
 * view.
 *
 * <p>The target is {@code net.minecraft.client.gui.hud.InGameOverlayRenderer}.
 * While the player is burning, vanilla draws a full-screen fire overlay each
 * frame; injecting at {@code @At("HEAD")} of
 * {@code InGameOverlayRenderer.renderFireOverlay} cancels it:
 * {@link FireOverlayManager#shouldHide()} applies the config and Zombies gate,
 * a hit removes it completely with no redraw; a miss lets the vanilla overlay
 * through. Note: the target is a private static method, so the handler must
 * be declared static; {@code @Inject} reaches private methods without an
 * accesswidener.
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {

    /** Injection point: renderFireOverlay HEAD (static)—a hit cancels the
     *  full-screen fire. */
    @Inject(method = "renderFireOverlay(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"), cancellable = true)
    private static void nmz$renderFireOverlay(MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (FireOverlayManager.shouldHide()) {
            ci.cancel();
        }
    }
}