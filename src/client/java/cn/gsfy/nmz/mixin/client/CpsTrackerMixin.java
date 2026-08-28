package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.cps.CpsTracker;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 左右键 CPS 统计——数出真实的每秒点击次数。
 *
 * <p>注入 {@code Mouse.onMouseButton(long, int, int, int)}（1.21.4 yarn 名），{@code @Inject}
 * 挂在 {@code @At("HEAD")}，直接挂钩鼠标物理按键事件：GLFW_PRESS（action == 1）才算一次真实按压，
 * 长按不重复计数。之所以不挂钩 doAttack/doItemUse：原版 {@code handleInputEvents} 会在长按时按
 * {@code itemUseCooldown} 间隔反复调用 doItemUse（约 4~5 次/秒），长按左/右键会被误统计成
 * ~5 CPS；挪到物理按键层计数才能规避。
 *
 * <p>统计范围：仅统计游戏内点击（无界面、无加载遮罩时），GUI 内点击不计；仅 Zombies 局内统计，
 * 与 CPS HUD 的门控保持一致，避免局外记录无意义数据。
 */
@Mixin(Mouse.class)
public abstract class CpsTrackerMixin {

    @Inject(method = "onMouseButton(JIII)V", at = @At("HEAD"))
    private void nmz$onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action != 1) { // GLFW_PRESS
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getOverlay() != null) {
            return; // 仅统计游戏内点击
        }
        // 仅 Zombies 局内统计（CPS HUD 由 TotalHUDRenderer.shouldRenderHud 门控；此处同步门控，避免局外记录无意义数据）
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
