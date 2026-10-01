package cn.gsfy.nmz.mixin.client.zoom;

import cn.gsfy.nmz.client.features.zoom.ZoomHandler;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mouse-sensitivity compensation for FOV zoom—after zoom-in the crosshair
 * keeps up without drift.
 *
 * <p>The target is {@code net.minecraft.client.Mouse}. Minecraft's
 * sensitivity does not scale with FOV (only the vanilla spyglass path has a
 * separate 8x reduction), and after zoom-in the same mouse delta moves
 * farther on screen—feels overly sensitive. So {@code Mouse.updateMouse},
 * when applying the look rotation, uses {@code @Redirect} to divide the
 * deltas by the current zoom divisor (linear 1/D, matching Zoomify's
 * {@code relativeSensitivity=100} intent)—only while {@link ZoomHandler}'s
 * current divisor is above 1 does it actually reduce the delta; after
 * zoom-in the crosshair-follow feel matches the un-zoomed feel. With the
 * master switch off or outside Zombies, the divisor is 1 and the rewrite is
 * the identity; the divisor transitions smoothly during the zoom animation,
 * so sensitivity never jumps. It shares the same zoom source as
 * {@link ZoomMixin}.
 */
@Mixin(Mouse.class)
public abstract class MouseSensitivityMixin {

    /** Injection point: updateMouse changeLookDirection @Redirect—deltas
     *  divided by the zoom divisor. */
    @Redirect(method = "updateMouse",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void nmz$scaleLookForZoom(ClientPlayerEntity player, double dx, double dy) {
        // Unzoomed / non-Zombies gives divisor=1.0, so the division is the
        // identity and naturally has no side effects
        double divisor = ZoomHandler.INSTANCE.getCurrentZoomDivisor();
        player.changeLookDirection(dx / divisor, dy / divisor);
    }
}