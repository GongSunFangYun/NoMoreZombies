package cn.gsfy.nmz.client.feature.nofireparticle;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.ZombiesGuns;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

import java.util.Set;

/**
 * 无发射粒子：在收包源头取消枪口火焰/烟雾/火花粒子（对应参考 NoGunFire）。
 *
 * <p>开枪瞬间服务端会下一批粒子包制造枪口火光，这里赶在 {@link ParticleS2CPacket} 落地前丢掉——
 * 粒子从包开始就没被创建，零渲染成本、不闪烁。cancel 的是入站包，只影响本地显示，不发任何包。
 *
 * <p>六重短路过滤链（任何一条不满足就不取消，免得误伤场景粒子）；跑在收包线程，只读判断、零分配。
 * 供 mixin 静态查询，不用在 NoMoreZombiesClient 接线。
 */
public final class NoFireParticle {

    /** 枪口粒子类型清单（参考同款 9 种：FLAME/SMOKE 是枪口火光与烟雾，LAVA/FIREWORK 是火花）。 */
    private static final Set<ParticleType<?>> GUN_FIRE_PARTICLES =
            Set.<ParticleType<?>>of(
                    ParticleTypes.FLAME,
                    ParticleTypes.SMALL_FLAME,
                    ParticleTypes.LAVA,
                    ParticleTypes.SMOKE,
                    ParticleTypes.LARGE_SMOKE,
                    ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                    ParticleTypes.FIREWORK,
                    ParticleTypes.POOF);

    /** 距离阈值（平方）：26.0 ≈ 实际半径 √26 ≈ 5.1 格（照参考原文语义，保守只罩住枪口）。
     * 注意这是「平方」比较——若本意是 26 格半径就该写 26*26=676，别随手改成 676。 */
    private static final double MAX_DISTANCE_SQ = 26.0;

    private NoFireParticle() {
    }

    public static boolean shouldCancel(ParticleS2CPacket packet) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return false;
        }
        if (!GlobalConfig.QoL.NO_GUN_FIRE.getBooleanValue()) {
            return false;
        }
        if (!PlayerUtils.isInZombies()) {
            return false;
        }
        if (!ZombiesGuns.isZombiesGun(mc.player.getMainHandStack())) {
            return false;
        }
        ParticleEffect options = packet.getParameters();
        if (options == null || !GUN_FIRE_PARTICLES.contains(options.getType())) {
            return false;
        }
        return mc.player.squaredDistanceTo(packet.getX(), packet.getY(), packet.getZ()) <= MAX_DISTANCE_SQ;
    }
}
