package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.invisibility.HideNearbyPlayer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家隐身（HideNearbyPlayer 的渲染侧）——近距离的队友直接从画面里消失。
 *
 * <p>注入 {@code EntityRenderer.shouldRender(Entity, Frustum, double, double, double)Z}，
 * {@code @Inject} 挂在 {@code @At("HEAD")}、可取消：每个实体进入渲染管线前都要过这道判定，
 * 命中 {@link HideNearbyPlayer#shouldHide} 就 {@code setReturnValue(false)}——该玩家的渲染
 * 整段被跳过。门控双保险：总开关 {@link GlobalConfig.QoL.PLAYER_INVISIBLE} 必须先开；
 * {@code shouldHide} 内部也只在 Zombies 局内放行，且要求目标是非本人的 {@code PlayerEntity}、
 * 没睡觉、maxHealth&lt;100、距本地玩家不足 1.4 格。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

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
