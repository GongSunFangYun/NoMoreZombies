package cn.gsfy.nmz.client.features.noparticles;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;

/**
 * No particles: drops every client-world particle at the <b>enqueue point</b>
 * —muzzle flash, block-break dust, fire/smoke, item pickup, firework
 * explosions—all stop rendering.
 *
 * <h2>Why intercept at addParticle(Particle)</h2>
 * <p>The hijack point is
 * {@code ParticleManager.addParticle(Particle)} (no return value). All six
 * call sites of that method have been checked byte for byte: the sibling
 * {@code addParticle(ParticleEffect,…)},
 * {@code addBlockBreakingParticles}, the private helper
 * {@code method_34020}, {@code ClientWorld.addFireworkParticle},
 * {@code ClientPlayNetworkHandler.onItemPickupAnimation},
 * {@code FireworksSparkParticle$Explosion.tick}—every one of them
 * constructs a particle object then passes it in, and none relies on a
 * return value. So cancelling here is complete and breaks no caller.
 * Particles that emitters ({@code addEmitter}) generate each tick ultimately
 * go through {@code world.addParticle} as well; the sprite queue and
 * {@code renderCustomParticles} only consume particles that have already
 * entered the queue, so they are covered too.
 *
 * <h2>Why not also kill the createParticle object allocation</h2>
 * <p>Injecting at the HEAD of
 * {@code addParticle(ParticleEffect,DDDDDD)Particle} and returning
 * {@code null} would skip that object construction (the method itself is
 * null-safe: bytecode offset 20 {@code ifnull 32} goes straight to
 * {@code aconst_null/areturn}, and does not hand the null on to
 * {@code addParticle(Particle)}). But two call sites in
 * {@code FireworksSparkParticle$FireworkParticle} <b>dereference the return
 * value directly</b>—{@code addExplosionParticle} offsets 17→20→29
 * ({@code invokevirtual}→{@code checkcast} (null passes)→
 * {@code invokevirtual gis$c.a(Z)}) and {@code explodeBall} offsets 417→451
 * ({@code astore} then {@code invokevirtual gji.a(FFF)}); returning null
 * would NPE. Saving that allocation would require an extra null guard in
 * that native class—the gain is one object allocation, not worth touching
 * another vanilla class. Trade-off: intercept only the enqueue.
 *
 * <h2>Gate</h2>
 * <p>This switch on + {@link PlayerUtils#isInZombies()}. The Zombies gate
 * stays, consistent with the rest of the mod (in the lobby, other minigames,
 * and singleplayer, particles stay). Mixins query it statically; no wiring
 * in NoMoreZombiesClient.
 */
public final class ParticleSuppressor {

    private ParticleSuppressor() {
    }

    /**
     * Whether particles should be swallowed right now: switch on and in a
     * Zombies game. When player/world are not ready (startup, the instant of
     * a world switch), always swallow nothing—the null check goes before the
     * switch test, so "not ready" is an unconditional pass.
     */
    public static boolean shouldHide() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return false;
        }
        return GlobalConfig.QoL.NO_PARTICLES.getBooleanValue() && PlayerUtils.isInZombies();
    }
}