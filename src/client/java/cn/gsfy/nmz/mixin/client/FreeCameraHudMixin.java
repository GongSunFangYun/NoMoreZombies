package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自由视角 HUD 数据来源修正——不然血条、快捷栏、饥饿值全读替身空数据。
 *
 * <p>{@code InGameHud.getCameraPlayer()}（1.21.4 为 private）返回的是相机实体，自由视角下就是
 * 替身（空字段），HUD 会跟着显示空值。注入 {@code @At("HEAD")}、可取消：当
 * {@link FreeCameraHandler#isActive()} 且玩家在场时，直接返回 {@code mc.player}，
 * HUD 照常显示玩家真实状态。
 */
@Mixin(InGameHud.class)
public abstract class FreeCameraHudMixin {

    @Inject(method = "getCameraPlayer()Lnet/minecraft/entity/player/PlayerEntity;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hudUsesRealPlayer(CallbackInfoReturnable<PlayerEntity> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.isActive() && mc.player != null) {
            cir.setReturnValue(mc.player);
        }
    }
}
