package cn.gsfy.nmz.mixin.client.event;

import cn.gsfy.nmz.client.features.powerups.PowerupDetect;
import cn.gsfy.nmz.client.utils.PlayerUtils;
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
 * Power-up spawn detection—sniffs an armor stand's custom name out of the
 * entity metadata packet to recognize a power-up spawn.
 *
 * <p>The injection target is
 * {@code net.minecraft.client.network.ClientPlayNetworkHandler}; {@code @Inject}
 * at {@code @At("HEAD")} of
 * {@code onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket)}: when each
 * entity metadata update packet arrives, the packet's armor stand custom
 * name is read and handed to {@link PowerupDetect} for power-up spawn
 * recognition.
 *
 * <p>The implementation narrows layer by layer: null/world checks first →
 * track only inside a Zombies game (gate aligned with
 * {@code PowerupDetect.scanArmorStands}, so an out-of-game round being
 * polluted cannot fire a spurious drop notice/command) → resolve the entity
 * and restrict to armor stands → once {@code PowerupDetect} is ready,
 * iterate the update entries' Text-typed custom names and hand each to
 * detectArmorstand.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    /** Injection point: onEntityTrackerUpdate HEAD—hand the armor stand name
     *  to PowerupDetect for power-up recognition. */
    @Inject(method = "onEntityTrackerUpdate(Lnet/minecraft/network/packet/s2c/play/EntityTrackerUpdateS2CPacket;)V", at = @At("HEAD"))
    private void nmz$onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        if (packet == null || MinecraftClient.getInstance().world == null) {
            return;
        }
        // Track power-up armor stands only inside a Zombies game: gate
        // aligned with PowerupDetect.scanArmorStands, so an out-of-game round
        // being polluted cannot fire a spurious drop notice/command.
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        Entity entity = MinecraftClient.getInstance().world.getEntityById(packet.id());
        if (!(entity instanceof ArmorStandEntity)) {
            // Armor stands only; other entity types are ignored outright.
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