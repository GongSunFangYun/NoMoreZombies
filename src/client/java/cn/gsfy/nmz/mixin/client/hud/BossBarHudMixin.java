package cn.gsfy.nmz.mixin.client.hud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla boss bar (a practical feature)—the top bar is big and
 * blocks the view.
 *
 * <p>The target is {@code net.minecraft.client.gui.hud.BossBarHud}. Injects
 * {@code BossBarHud.render(DrawContext)} with {@code @Inject} at
 * {@code @At("HEAD")}, cancellable: before each frame's boss-bar render,
 * when {@code GlobalConfig.QoL.HIDE_BOSS_BAR} is on and in Zombies mode,
 * this render is cancelled—the vanilla boss bar is not drawn at all, and the
 * mod's other rendering is unaffected; other games and the lobby keep the
 * vanilla display. No replacement bar is provided here; the class only hides
 * behind the config + in-game gate.
 */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {

    /** Injection point: render HEAD—when every gate is on, cancel; the
     *  vanilla bar is not drawn for the whole frame. */
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
    private void nmz$onRenderBossBar(DrawContext context, CallbackInfo ci) {
        if (GlobalConfig.QoL.HIDE_BOSS_BAR.getBooleanValue() && PlayerUtils.isInZombies()) {
            ci.cancel();
        }
    }
}