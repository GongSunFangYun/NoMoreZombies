package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自由视角隐藏第一人称手——不然替身的手会穿帮。
 *
 * <p>相机实体第一人称视角若渲染玩家的手（其实是替身的手，玩家本体又不透明渲染）会穿帮。注入
 * {@link GameRenderer#renderHand(Camera, float, Matrix4f)}（1.21.4 为 private 3 参签名）的
 * {@code @At("HEAD")} cancel——与 {@code mc.gameRenderer.setRenderHand(false)} 双保险。
 */
@Mixin(GameRenderer.class)
public abstract class FreeCameraRenderHandMixin {

    @Inject(method = "renderHand(Lnet/minecraft/client/render/Camera;FLorg/joml/Matrix4f;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideFirstPersonHand(Camera camera, float tickDelta, Matrix4f matrix4f, CallbackInfo ci) {
        if (FreeCameraHandler.isActive()) {
            ci.cancel();
        }
    }
}
