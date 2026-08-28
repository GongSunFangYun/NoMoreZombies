package cn.gsfy.nmz.client.feature.sneak;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;

/**
 * 永久潜行——在输入层把潜行标志钉死（见 {@code AlwaysSneakMixin}：在 {@code KeyboardInput.tick}
 * 之后重建 {@code playerInput} 并置 sneak=true），下蹲动画/防掉落边缘/缩小碰撞箱/潜行数据包
 * 全都走 vanilla 状态机，不做任何旁路。
 *
 * <p>门控 = QoL 总开关 + Zombies 局内（同缩放惯例）+ 默认不在 GUI 界面里强制（打开背包/容器时
 * 解除，除非「全局配置」页 {@code Sneak.ALLOW_IN_GUIS} 打开）。供 mixin 静态查询，
 * 不用在 NoMoreZombiesClient 接线。
 */
public final class AlwaysSneak {

    private AlwaysSneak() {
    }

    /** 当前是否生效：总开关开、在 Zombies 局内，且（允许界面潜行 或 当前没开界面）。 */
    public static boolean isActive() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return false;
        }
        return GlobalConfig.QoL.ALWAYS_SNEAK_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies()
                && (GlobalConfig.Sneak.ALLOW_IN_GUIS.getBooleanValue() || mc.currentScreen == null);
    }
}
