package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Free-camera HUD data-source fix—without it, the health bar, hotbar and
 * hunger all read the stand-in's empty data.
 *
 * <p>The target is {@code net.minecraft.client.gui.hud.InGameHud}.
 * {@code InGameHud.getCameraPlayer()} (private in 1.21.4) returns the camera
 * entity, which under freecam is the stand-in (empty fields), and the HUD
 * would display empty values with it. Injects {@code @At("HEAD")}, cancellable:
 * when {@link FreeCameraHandler#isActive()} and the player is present,
 * returns {@code mc.player} directly so the HUD displays the player's real
 * state; when freecam is off the original return value is untouched.
 */
@Mixin(InGameHud.class)
public abstract class FreeCameraHudMixin {

    /** getCameraPlayer HEAD return swap: under freecam hand back the real
     *  player, so the HUD does not read the stand-in's empty values. */
    @Inject(method = "getCameraPlayer()Lnet/minecraft/entity/player/PlayerEntity;",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hudUsesRealPlayer(CallbackInfoReturnable<PlayerEntity> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreeCameraHandler.isActive() && mc.player != null) {
            cir.setReturnValue(mc.player);
        }
    }
}