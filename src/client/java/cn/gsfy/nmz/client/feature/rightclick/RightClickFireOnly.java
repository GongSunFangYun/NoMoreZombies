package cn.gsfy.nmz.client.feature.rightclick;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;

/**
 * 屏蔽除开火外的右键操作：全息字穿透 + 忽略交互方块的右键反应。
 *
 * <p>Hypixel 僵尸末日里，门价/机器提示/道具字样这些漂浮文字全是隐形盔甲架，准星射线先撞上它们，
 * 右键就走「与盔甲架交互」而不是「使用物品（开枪）」，枪打不出去、还卡手。开启后：
 *   ① 准星实体射线排除盔甲架（见 {@code GameRendererCrosshairMixin}）；
 *   ② 交互方块（机器/按钮/门）的右键反应跳过，右键只用来开枪（见 {@code MinecraftClientItemUseMixin}）。
 *
 * <p>开启期间没法操作机器/门这类交互方块（买枪/开门前得临时关掉开关）。仅 Zombies 生效。
 * 供 mixin 静态查询，不用在 NoMoreZombiesClient 接线。
 */
public final class RightClickFireOnly {

    private RightClickFireOnly() {
    }

    /** 当前是否生效：总开关开 且 在 Zombies 局内。 */
    public static boolean isActive() {
        return GlobalConfig.QoL.RIGHT_CLICK_FIRE_ONLY.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}
