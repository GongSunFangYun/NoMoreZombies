package cn.gsfy.nmz.client.feature.sprint;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;

/**
 * 永久疾跑——把「疾跑键永远按住」落到代码里：在 {@code ClientPlayerEntity.tickMovement} 中
 * （原版疾跑判定之后、状态落定之前）只要满足原版疾跑约束就直接 {@code setSprinting(true)}
 * （见 {@code AlwaysSprintMixin}）。必须按 W 前进（{@code movementForward>=0.8}），
 * 饥饿/失明/泡水/手持使用物品这些原版疾跑停止条件全部保留，免得被服务器当成异常移动。不是「原地站桩也跑」。
 *
 * <p>门控 = QoL 总开关 + Zombies 局内（同缩放惯例）。供 mixin 静态查询，
 * 不用在 NoMoreZombiesClient 接线。
 */
public final class AlwaysSprint {

    private AlwaysSprint() {
    }

    /** 当前是否生效：总开关开 且 在 Zombies 局内。 */
    public static boolean isActive() {
        return GlobalConfig.QoL.ALWAYS_SPRINT_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}
