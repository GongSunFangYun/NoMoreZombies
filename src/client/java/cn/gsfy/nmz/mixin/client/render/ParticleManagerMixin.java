package cn.gsfy.nmz.mixin.client.render;

import cn.gsfy.nmz.client.features.noparticles.ParticleSuppressor;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No particles—drops every client-world particle at the enqueue point.
 *
 * <p>The target is {@code net.minecraft.client.particle.ParticleManager}.
 * Injects the void {@code addParticle(Particle)} at {@code @At("HEAD")},
 * cancellable: this is the only enqueue sink for all particles, and every
 * caller ignores the return value, so a cancel is complete and breaks no
 * caller; on a hit the particle never enters the sprite queue, is never
 * ticked, never rendered.
 *
 * <p>Why not also kill {@code addParticle(ParticleEffect,…)}—the bytecode
 * evidence and gating rules are in {@link ParticleSuppressor}'s class
 * comment.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    /**
     * Injection point: addParticle(Particle) HEAD—a gate hit cancels.
     */
    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$suppressParticle(Particle particle, CallbackInfo ci) {
        if (ParticleSuppressor.shouldHide()) {
            ci.cancel();
        }
    }
}