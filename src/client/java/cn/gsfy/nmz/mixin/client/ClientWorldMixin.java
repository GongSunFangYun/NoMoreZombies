package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.shared.GameEventBus;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界音效事件上报——用耳朵听出回合什么时候开始、结束。
 *
 * <p>注入 {@code ClientWorld.playSound(double, double, double, SoundEvent, SoundCategory,
 * float, float, boolean)}，{@code @Inject} 挂在 {@code @At("HEAD")}：客户端世界每次播放音效，
 * 就把音效路径与音高转发给 {@link GameEventBus#onWorldPlaySound}，由它用世界内音效识别游戏状态
 * （回合开始/结束）。
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    @Inject(method = "playSound(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V", at = @At("HEAD"))
    private void nmz$onPlaySound(double x, double y, double z, SoundEvent sound, SoundCategory category,
                                 float volume, float pitch, boolean distanceDelay, CallbackInfo ci) {
        if (sound != null) {
            GameEventBus.onWorldPlaySound(sound.id().getPath(), pitch);
        }
    }
}
