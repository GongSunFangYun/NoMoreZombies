package cn.gsfy.nmz.client.feature.gamma;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;

/**
 * 伽马覆写（光照侧实现）——开启时把光照亮度顶到「全局配置」页的覆写值（默认 16，夜视级，
 * 不受原版亮度滑条 0~1 上限约束），离开 Zombies 自动还原玩家原亮度。
 *
 * <p><b>不再直写 {@code mc.options.getGamma()} 选项字段。</b>旧实现把覆写值（如 16）静默写进
 * vanilla 亮度选项，可那个选项的 codec 合法范围是 [0,1]——任何一次 {@code GameOptions.save()}
 * （调完设置自动保存 / 退出游戏）编码 16 都会越界失败（日志
 * {@code Error saving option Brightness: Value 16.0 outside of range [0.0:1.0]}），
 * 进而触发客户端资源重载 → 全屏黑屏。现在选项字段永远保持玩家原值，覆写只作用于光照消费端：
 * {@link cn.gsfy.nmz.mixin.client.LightmapBrightnessMixin} 在
 * {@code LightmapTextureManager.update()} 里把 BrightnessFactor（vanilla 算成
 * {@code max(0, gamma - darkness)}）换成 {@code max(0, override - darkness)}——原版 lightmap
 * shader 对任意 BrightnessFactor 最终都会 clamp 到 [0,1]，覆写值 &gt;1 就能安全地亮出夜视级，
 * 且不碰任何保存路径（选项字段永远合法）。
 *
 * <p>门控 = QoL 总开关 + Zombies 局内（同缩放惯例）：大厅/单机保持原亮度，进 Zombies 才覆写。
 */
public final class GammaOverride {

    private GammaOverride() {
    }

    /** 当前是否生效：总开关开 且 在 Zombies 局内（LightmapBrightnessMixin 每帧查这个）。 */
    public static boolean isActive() {
        return GlobalConfig.QoL.GAMMA_OVERRIDE_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}
