package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 实体 ESP / 血条的目标判定——回答「一个实体该不该被框 / 显示血条」，
 * 口径始终与队伍状态机 {@link TeamStats} 对齐（状态机是权威）。
 *
 * <p>敌对生物走一条直线：实现 {@link Monster} 接口的（僵尸 / 猪灵 / 烈焰人 / 末影螨 /
 * 蠹虫 / 巨人 / 史莱姆 / 岩浆怪 / 恶魂等）+ 狼 + 铁傀儡（僵尸模式的召唤物）→ 红框 / 血条。
 * 玩家是三态：名单内 IN_COMBAT → 绿色线框；名单外随机名倒地身体（倒地瞬间按实体 ID 建立
 * 坐标关联，经 {@link TeamStats#getDownedBodyOwner} 反查）→ 黄色线框，且仅当该名单玩家
 * 状态为 DOWNED（以状态机为权威）；自己倒地 / 死亡 / 退出 → 不框。
 *
 * <p>死亡旁观（伪旁观）时服务器会把死者尸体实体也发回本地，光看名单会把同样死亡的
 * 队友也框上，于是除名单状态（IN_COMBAT）外再叠加两道过滤：一是本地死亡瞬间仍在战斗
 * 的队友白名单（{@link TeamStats#wasAliveWhenSelfDied}）——尸体即使名单被误判为战斗中
 * 也不框；二是实体存活校验（血量 &gt; 0）——血量 0 的尸体实体直接排除（对普通战斗中
 * 玩家无影响）。仅在 Zombies 游戏中生效；纯客户端渲染，不向服务器发送任何数据。
 */
public final class EntityEsp {

    /**
     * 判定一个实体是否为 ESP / 血条目标——渲染器逐实体调用，命中即框选 / 显示血条。
     *
     * @param entity 待判定实体
     * @return {@code true} 表示该实体应被框选 / 显示血条
     */
    public static boolean isTarget(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.getGameProfile() == null) {
                return false;
            }
            String name = player.getGameProfile().getName();
            TeamStats.Status status = TeamStats.getStatus(name);
            if (status == null) {
                // 名单外玩家实体：只有「随机名倒地身体」才框——倒地瞬间已按实体 ID 建好
                // 坐标关联，否则按名字根本查不到。自己倒地不框（自身身体从不做关联），
                // 旁观 / 杂项实体没有关联也不框。
                String owner = TeamStats.getDownedBodyOwner(player.getId());
                if (owner == null) {
                    return false;
                }
                // 以状态机为权威：仅倒地期间才框，防身体清理延迟或实体 ID 复用造成误框。
                return TeamStats.getStatus(owner) == TeamStats.Status.DOWNED;
            }
            if (status == TeamStats.Status.IN_COMBAT) {
                // ① 死亡旁观过滤：本地处于死亡旁观（伪旁观）时，只框「本地死亡瞬间仍在战斗」
                //    的队友；尸体即使名单被误判为战斗中也不框。本地存活时这道白名单不生效。
                boolean selfSpectating  = TeamStats.isSelfSpectating();
                boolean wasAliveAtDeath = TeamStats.wasAliveWhenSelfDied(name);
                boolean corpseGuard     = selfSpectating && !wasAliveAtDeath;

                // ② 实体存活兜底：血量 0 的尸体实体直接不框（对普通战斗中玩家无影响）。
                boolean entityAlive = player.isAlive() && player.getHealth() > 0.0F;

                return !corpseGuard && entityAlive;
            }
            // DOWNED（自己倒地时自身实体在场）/ DEAD / LEFT → 不框
            return false;
        }
        // 敌对生物（Monster 接口，含史莱姆/岩浆怪/恶魂等非 HostileEntity 的怪）
        // + 狼 + 铁傀儡（僵尸模式召唤物）→ 红框/血条
        if (entity instanceof Monster || entity instanceof WolfEntity || entity instanceof IronGolemEntity) {
            return PlayerUtils.isInZombies();
        }
        return false;
    }

    private EntityEsp() {
    }
}
