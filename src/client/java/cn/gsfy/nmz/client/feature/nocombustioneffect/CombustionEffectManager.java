package cn.gsfy.nmz.client.feature.nocombustioneffect;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;

/**
 * 无火焰效果：着火时完全移除屏幕火焰遮罩（对应参考 NoFireEffect，用户选择「完全去除」——
 * 单一开关，HEAD cancel 原渲染，不重绘、不可调透明度）。
 *
 * <p>只在 Zombies 局内生效；供 mixin 静态查询，不用在 NoMoreZombiesClient 接线。
 */
public final class CombustionEffectManager {

    private CombustionEffectManager() {
    }

    /** 当前是否应隐藏火焰遮罩：开关开 且 在 Zombies 局内。 */
    public static boolean shouldHide() {
        return GlobalConfig.QoL.NO_FIRE_EFFECT.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}
