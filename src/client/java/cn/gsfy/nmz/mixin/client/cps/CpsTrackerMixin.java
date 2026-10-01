package cn.gsfy.nmz.mixin.client.cps;

import cn.gsfy.nmz.client.features.cps.CpsTracker;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Left/right click CPS tracking—counts the real clicks per second.
 *
 * <p>The target is {@code net.minecraft.client.Mouse}. Injects
 * {@code Mouse.onMouseButton(long, int, int, int)} (1.21.4 yarn name) with
 * {@code @Inject} at {@code @At("HEAD")}, hooking the physical mouse button
 * event directly: only GLFW_PRESS (action==1) counts as a real press, and
 * holding the button does not repeat-count.
 *
 * <p>Why not hook doAttack/doItemUse: vanilla {@code handleInputEvents}
 * calls doItemUse repeatedly at the {@code itemUseCooldown} interval
 * (about 4–5 times/s) while a button is held, so holding left/right would
 * be counted as ~5 CPS; counting at the physical key layer avoids this.
 *
 * <p>Scope: only clicks inside a game (no screen, no loading overlay) are
 * counted; GUI clicks are not. In-game counts only inside a Zombies game;
 * a hit left/right button is forwarded to {@link CpsTracker} respectively,
 * and the mouse event is neither cancelled nor rewritten. It shares its
 * gate with the CPS HUD, avoiding recording useless data outside a game.
 */
@Mixin(Mouse.class)
public abstract class CpsTrackerMixin {

    /** Injection point: onMouseButton HEAD—GLFW_PRESS counts once, holding
     *  does not inflate CPS. */
    @Inject(method = "onMouseButton(JIII)V", at = @At("HEAD"))
    private void nmz$onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action != 1) { // GLFW_PRESS
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getOverlay() != null) {
            return; // In-game clicks only.
        }
        // In-game only: shares its gate with the CPS HUD, avoiding recording
        // useless data outside a game.
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (button == 0) {
            CpsTracker.onLeftClick();
        } else if (button == 1) {
            CpsTracker.onRightClick();
        }
    }
}