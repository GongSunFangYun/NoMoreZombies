package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 自由视角的实体过滤修复——不然相机回头看不到自己的角色。
 *
 * <p>1.21.4 {@code WorldRenderer.getEntitiesToRender} 用两条规则把真实玩家从渲染列表剔除：
 * 规则 3（第一人称不渲染自己身体）与规则 4（聚焦实体≠玩家时跳过 {@code ClientPlayerEntity}）。
 * 自由视角下聚焦实体是替身相机实体，规则 4 会把玩家本体误杀。照 tweakeroo 的做法，在
 * {@code getEntitiesToRender} 里用 {@code @Redirect} 拦下 {@code Camera.getFocusedEntity()} 与
 * {@code Camera.isThirdPerson()} 两处调用——聚焦实体欺骗回玩家 + {@code isThirdPerson()} 返回
 * true，两条规则同时失效，玩家本体正常渲染（不透明，可看到持械站姿）。
 *
 * <p>安全性（javap 验证）：两处调用都只在 {@code getEntitiesToRender} 内部（getFocusedEntity 4 处全在
 * 规则 3/4；isThirdPerson 仅 1 处）；{@code OtherClientPlayerEntity} 是 {@code AbstractClientPlayerEntity}
 * 子类而非 {@code ClientPlayerEntity}，故规则 4 只命中真实玩家，重定向不会误伤队友渲染。
 * 仅在 {@code FreeCameraHandler.isActive()} 时欺骗，平时原样返回，零行为变化。
 */
@Mixin(WorldRenderer.class)
public abstract class FreeCameraEntityFilterMixin {

    /** 聚焦实体欺骗回玩家：规则 4 的 {@code focused != entity} 对真实玩家不再成立，玩家不被跳过。 */
    @Redirect(method = "getEntitiesToRender",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;"))
    private Entity nmz$spoofFocusedEntity(Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.isActive() && mc.player != null) {
            return mc.player;
        }
        return camera.getFocusedEntity();
    }

    /** 假装第三人称：规则 3 的 {@code !isThirdPerson()} 不成立，玩家不被第一人称规则跳过。 */
    @Redirect(method = "getEntitiesToRender",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;isThirdPerson()Z"))
    private boolean nmz$spoofThirdPerson(Camera camera) {
        if (FreeCameraHandler.isActive()) {
            return true;
        }
        return camera.isThirdPerson();
    }
}
