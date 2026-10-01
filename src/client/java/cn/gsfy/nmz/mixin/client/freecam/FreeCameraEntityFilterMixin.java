package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Free-camera entity-filter fix—without it, the camera cannot see your own
 * character after turning around.
 *
 * <p>The target is {@code net.minecraft.client.render.WorldRenderer}. In
 * 1.21.4 {@code WorldRenderer.getEntitiesToRender} uses two rules to strip
 * the real player out of the render list: rule 3 (first-person does not
 * render your own body) and rule 4 (skip {@code ClientPlayerEntity} when the
 * focused entity is not the player). Under freecam the focused entity is the
 * stand-in camera entity, and rule 4 mistakenly kills the player body. Following
 * tweakeroo's approach, {@code getEntitiesToRender} uses {@code @Redirect}
 * to intercept two calls—{@code Camera.getFocusedEntity()} and
 * {@code Camera.isThirdPerson()}—so the focused entity is spoofed back to the
 * player and {@code isThirdPerson()} returns true, disabling both rules and
 * letting the player body render normally (opaque, showing the armed stance).
 * This closes the loop with {@link FreeCameraPlayerMixin}'s body freeze and
 * {@link FreeCameraRenderHandMixin}'s hand hiding.
 *
 * <p>Safety (javap-verified): both calls appear only inside
 * {@code getEntitiesToRender} (all 4 {@code getFocusedEntity} sites in rules
 * 3/4; {@code isThirdPerson} only 1 site); {@code OtherClientPlayerEntity} is
 * a subclass of {@code AbstractClientPlayerEntity}, not of
 * {@code ClientPlayerEntity}, so rule 4 hits only the real player and the
 * redirect cannot harm teammate rendering. Spoofing happens only while
 * {@code FreeCameraHandler.isActive()}; otherwise it returns the original
 * value, zero behavioral change.
 */
@Mixin(WorldRenderer.class)
public abstract class FreeCameraEntityFilterMixin {

    /**
     * Redirects getFocusedEntity (@At INVOKE): under freecam the focused
     * entity is swapped to the player, so rule 4's
     * {@code focused!=entity} no longer holds for the real player and the
     * player is not skipped.
     */
    @Redirect(method = "getEntitiesToRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;"))
    private Entity nmz$spoofFocusedEntity(Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.isActive() && mc.player != null) {
            return mc.player;
        }
        return camera.getFocusedEntity();
    }

    /**
     * Redirects isThirdPerson (@At INVOKE): under freecam always reports
     * true, so rule 3's {@code !isThirdPerson()} no longer holds and the
     * player is not skipped by the first-person rule.
     */
    @Redirect(method = "getEntitiesToRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;isThirdPerson()Z"))
    private boolean nmz$spoofThirdPerson(Camera camera) {
        if (FreeCameraHandler.isActive()) {
            return true;
        }
        return camera.isThirdPerson();
    }
}