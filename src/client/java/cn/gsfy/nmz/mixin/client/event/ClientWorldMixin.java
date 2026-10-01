package cn.gsfy.nmz.mixin.client.event;

import cn.gsfy.nmz.client.shared.game.GameEventBus;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World sound event reporting—hearing when a round starts and ends.
 *
 * <p>The target is {@code net.minecraft.client.world.ClientWorld}. Injects
 * {@code ClientWorld.playSound(double,double,double,SoundEvent,SoundCategory,
 * float,float,boolean)} with {@code @Inject} at {@code @At("HEAD")}: every
 * time the client world plays a sound, its path and pitch are forwarded to
 * {@link GameEventBus#onWorldPlaySound(String, float)}; the mixin neither
 * cancels nor rewrites the sound, and the Zombies gate plus the
 * round-start / game-end state write-back are both left to the event bus.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    /** Injection target and @At point per the class comment: forwards the
     *  sound path and pitch to GameEventBus for state recognition. */
    @Inject(method = "playSound(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V", at = @At("HEAD"))
    private void nmz$onPlaySound(double x, double y, double z, SoundEvent sound, SoundCategory category,
                                 float volume, float pitch, boolean distanceDelay, CallbackInfo ci) {
        if (sound != null) {
            GameEventBus.onWorldPlaySound(sound.id().getPath(), pitch);
        }
    }
}