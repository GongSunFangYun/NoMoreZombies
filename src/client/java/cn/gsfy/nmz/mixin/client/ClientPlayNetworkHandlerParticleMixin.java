package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.nofireparticle.NoFireParticle;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 无发射粒子——把着火时的火星粒子掐在收包源头。
 *
 * <p>注入 {@code ClientPlayNetworkHandler.onParticle(ParticleS2CPacket)}，{@code @Inject} 挂在
 * {@code @At("HEAD")}、可取消：每个粒子数据包到达时，由 {@link NoFireParticle#shouldCancel}
 * 做六重过滤，命中就 cancel——粒子还没进渲染就被丢弃，零渲染成本。
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerParticleMixin {

    @Inject(method = "onParticle(Lnet/minecraft/network/packet/s2c/play/ParticleS2CPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
        if (NoFireParticle.shouldCancel(packet)) {
            ci.cancel();
        }
    }
}
