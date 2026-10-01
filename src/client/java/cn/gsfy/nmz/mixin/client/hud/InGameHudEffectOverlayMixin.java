package cn.gsfy.nmz.mixin.client.hud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the vanilla status-effect HUD (the top-right effect icon grid)
 * —inside a Zombies game only.
 *
 * <p>The target is {@code net.minecraft.client.gui.hud.InGameHud}. Injects
 * {@code InGameHud.renderStatusEffectOverlay(DrawContext, RenderTickCounter)}
 * (a private method, {@code @Inject} works directly, no accesswidener
 * needed), cancellable at {@code @At("HEAD")}: both gates passing (inside a
 * Zombies game + {@link GlobalConfig.Hud#statusEffectsOn()}: master switch +
 * placed + independent switch) cancels the vanilla render, replaced by
 * {@code StatusEffectHudRenderer}'s text-list custom HUD—icon/name/level
 * as before, plus the remaining-duration number the vanilla one does not
 * show.
 *
 * <p>If any gate is off (master switch off / the editor hides this HUD /
 * not placed on the canvas / not a Zombies game), nothing is cancelled and
 * the vanilla HUD is restored as is—consistent with the hide-fallback
 * semantics of {@code BossBarHudMixin}. That fallback is exactly what "not
 * placed means no takeover" should look like: the custom HUD is not on
 * screen, so the vanilla one must not be hidden either, or the player sees
 * neither.
 *
 * <p>The injection point is inside HUD rendering, naturally entered on the
 * client main thread only—no cross-thread issues.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudEffectOverlayMixin {

    /** Injection point: renderStatusEffectOverlay HEAD—only when both gates
     *  are on does it cancel. */
    @Inject(method = "renderStatusEffectOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$onRenderStatusEffectOverlay(CallbackInfo ci) {
        if (PlayerUtils.isInZombies() && GlobalConfig.Hud.statusEffectsOn()) {
            ci.cancel();
        }
    }
}