package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 隐藏原生 Boss 血条（实用功能）——顶部的血条又大又挡视野。
 *
 * <p>注入 {@code BossBarHud.render(DrawContext)}，{@code @Inject} 挂在 {@code @At("HEAD")}、
 * 可取消：每帧 Boss 血条渲染前，只要 {@link GlobalConfig.QoL.HIDE_BOSS_BAR} 开着且处于
 * Zombies 模式，就 cancel 掉这次渲染——原生 Boss 血条整体不画，mod 其它渲染不受影响；
 * 其他游戏 / 大厅保持原生显示。
 */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
    private void nmz$onRenderBossBar(DrawContext context, CallbackInfo ci) {
        if (GlobalConfig.QoL.HIDE_BOSS_BAR.getBooleanValue() && PlayerUtils.isInZombies()) {
            ci.cancel();
        }
    }
}
