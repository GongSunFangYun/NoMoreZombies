package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.powerups.PowerupDetect;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 道具出生识别（Powerup）——从实体元数据里嗅出盔甲架的显示名，认出道具出生。
 *
 * <p>注入 {@code ClientPlayNetworkHandler.onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket)}，
 * {@code @Inject} 挂在 {@code @At("HEAD")}：每个实体元数据更新包到达时，从包里读取盔甲架的
 * 自定义名字交给 {@link PowerupDetect} 识别道具出生。执行顺序是层层收窄：先做空值与世界检查 →
 * 仅 Zombies 局内跟踪 → 取实体并限定为盔甲架 → {@code PowerupDetect} 就绪后，
 * 遍历更新条目中的 Text 型自定义名交给 detectArmorstand。
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onEntityTrackerUpdate(Lnet/minecraft/network/packet/s2c/play/EntityTrackerUpdateS2CPacket;)V", at = @At("HEAD"))
    private void nmz$onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        if (packet == null || MinecraftClient.getInstance().world == null) {
            return;
        }
        // 仅 Zombies 局内跟踪道具盔甲架：对齐 PowerupDetect.scanArmorStands 的门控，
        // 避免局外 round 被污染时误触发掉落提示/命令。
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        Entity entity = MinecraftClient.getInstance().world.getEntityById(packet.id());
        if (!(entity instanceof ArmorStandEntity)) {
            // 只关心盔甲架实体，其它实体类型直接忽略。
            return;
        }
        if (PowerupDetect.get() == null) {
            return;
        }
        for (DataTracker.SerializedEntry<?> entry : packet.trackedValues()) {
            if (entry.value() instanceof Text customName) {
                PowerupDetect.get().detectArmorstand(customName.getString(), packet.id());
            }
        }
    }
}
