package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free-camera first-person hand hiding—without it, the stand-in's hand gives
 * the game away.
 *
 * <p>The target is {@code net.minecraft.client.render.GameRenderer}. If the
 * camera entity's first-person view renders the player's hand (which is
 * really the stand-in's hand, while the player body itself renders opaquely),
 * it would give the illusion away. Injects
 * {@link GameRenderer#renderHand(Camera, float, Matrix4f)} (private 3-arg
 * signature in 1.21.4) at {@code @At("HEAD")} with cancel—effective only
 * while {@link FreeCameraHandler#isActive()}, as a second safeguard alongside
 * {@code mc.gameRenderer.setRenderHand(false)}; after disabling, vanilla hand
 * rendering resumes as usual.
 */
@Mixin(GameRenderer.class)
public abstract class FreeCameraRenderHandMixin {

    /** renderHand HEAD cancel: under freecam no first-person hand is drawn,
     *  so the stand-in's hand does not give it away. */
    @Inject(method = "renderHand(Lnet/minecraft/client/render/Camera;FLorg/joml/Matrix4f;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideFirstPersonHand(Camera camera, float tickDelta, Matrix4f matrix4f, CallbackInfo ci) {
        if (FreeCameraHandler.isActive()) {
            ci.cancel();
        }
    }
}