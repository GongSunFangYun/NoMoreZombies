package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free-camera mouse-look forwarding—routes the look delta from the player to
 * the stand-in camera, so the player's orientation does not move.
 *
 * <p>The target is {@code net.minecraft.entity.Entity}. In 1.21.4
 * {@code Mouse.updateMouse} calls {@code client.player.changeLookDirection}
 * directly with the delta (rather than fetching
 * {@code getCameraEntity()} first), so intercepting the getCameraEntity
 * path cannot catch the look delta; the only place is
 * {@link Entity#changeLookDirection} itself: the player's call is cancelled
 * at {@code @At("HEAD")}, and the delta is forwarded to the stand-in camera
 * entity ({@code FreeCameraHandler.rotateCamera}).
 *
 * <p>Naturally immune to recursion: this rewrite fires only when
 * {@link FreeCameraHandler#shouldPreventMovement()} is true and the current
 * entity is the player; the forwarding calls
 * {@code camera.changeLookDirection} internally, which also hits this
 * injection, but {@code this!=mc.player} lets it pass through (the camera
 * is rotated by vanilla logic, including the 0.15 sensitivity and pitch
 * clamping).
 */
@Mixin(Entity.class)
public abstract class FreeCameraLookMixin {

    /** changeLookDirection HEAD interception: while frozen, forwards the
     *  look delta to the camera. */
    @Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"), cancellable = true)
    private void nmz$redirectLookToCamera(double yawChange, double pitchChange, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.shouldPreventMovement() && (Object) this == mc.player) {
            FreeCameraHandler.rotateCamera((float) yawChange, (float) pitchChange);
            ci.cancel();   // Player orientation is not changed.
        }
    }
}