package cn.gsfy.nmz.mixin.client.render;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.invisibility.HideNearbyPlayer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player invisibility (the render side of HideNearbyPlayer)—close teammates
 * disappear from the picture.
 *
 * <p>The target is {@code net.minecraft.client.render.entity.EntityRenderer}.
 * Injects
 * {@code EntityRenderer.shouldRender(Entity, Frustum, double, double, double)Z}
 * with {@code @Inject} at {@code @At("HEAD")}, cancellable. Why
 * shouldRender: it is vanilla's only per-entity render gate, and a cancel
 * skips the whole section with zero draw cost. Every entity passes this
 * check before entering the render pipeline, and a hit on
 * {@link HideNearbyPlayer#shouldHide} {@code setReturnValue(false)}—that
 * player's render is skipped whole. Double gate: the master switch
 * {@code GlobalConfig.QoL.PLAYER_INVISIBLE} must be on first; the singleton
 * is null-checked via {@code get()} (null before init); {@code shouldHide}
 * itself passes only inside a Zombies game and requires the target to be a
 * non-self {@code PlayerEntity}, not sleeping, maxHealth&lt;100, and within
 * 1.4 blocks of the local player.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    /** Injection point: shouldRender HEAD—a hit returns false and the player
     *  is skipped whole. */
    @Inject(method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideNearbyPlayers(T entity, Frustum frustum, double x, double y, double z,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (GlobalConfig.QoL.PLAYER_INVISIBLE.getBooleanValue()
                && HideNearbyPlayer.get() != null
                && HideNearbyPlayer.get().shouldHide(entity)) {
            cir.setReturnValue(false);
        }
    }
}