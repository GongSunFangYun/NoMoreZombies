package cn.gsfy.nmz.client.feature.invisibility;

import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 玩家隐身（对应源 PlayerInvisibility）。
 *
 * <p>当前实现：近距离（&lt;1.4）、非睡觉且 maxHealth&lt;100 的玩家直接取消渲染——
 * 贴脸挡视线的玩家眼不见为净；透明度渐变（VCP 包装）留作后续增强项。
 */
public class HideNearbyPlayer {

    private static HideNearbyPlayer instance;

    public static HideNearbyPlayer get() {
        return instance;
    }

    public void init() {
        instance = this;
    }

    /** 是否应隐藏该玩家：EntityRendererMixin.shouldRender 调进来，只在 Zombies 局内生效。 */
    public boolean shouldHide(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (!PlayerUtils.isInZombies()) {
            return false;
        }
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        if (player == client.player) {
            return false;
        }
        if (player.isSleeping()) {
            return false;
        }
        if (player.getMaxHealth() >= 100) {
            return false;
        }
        return client.player.squaredDistanceTo(player) < 1.4 * 1.4;
    }

    public HideNearbyPlayer() {
    }
}
