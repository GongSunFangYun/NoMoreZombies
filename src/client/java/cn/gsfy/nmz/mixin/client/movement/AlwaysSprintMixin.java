package cn.gsfy.nmz.mixin.client.movement;

import cn.gsfy.nmz.client.features.sprint.AlwaysSprint;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Permanent sprint—keeps sprinting while moving, saving a held Ctrl+W.
 *
 * <p>The target is {@code net.minecraft.client.network.ClientPlayerEntity}.
 * Injects {@code ClientPlayerEntity.tickMovement} at the {@code @At("FIELD")}
 * PUTFIELD of the {@code falling} field—that PUTFIELD sits in the method's
 * late bytecode, after every {@code setSprinting} call and executed
 * unconditionally (bytecode offset ≈922), i.e. after the vanilla sprint
 * test has fully settled; only when {@link AlwaysSprint#isActive()} and the
 * remaining constraints hold does it call {@code setSprinting(true)}.
 * The effect is equivalent to "the sprint key is held forever," but not a
 * single vanilla sprint-stop condition is dropped: W must still be held
 * forward ({@code movementForward>=0.8}), and hunger, blindness, water,
 * holding a use item all stay—otherwise the server would read it as
 * abnormal movement (it is not "sprint while standing still").
 *
 * <p>Implementation: extending
 * {@code AbstractClientPlayerEntity} (superclass constructor
 * {@code (ClientWorld, GameProfile)}, javap-verified) allows calling the
 * inherited public methods directly, without {@code @Shadow}-ing each.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class AlwaysSprintMixin extends AbstractClientPlayerEntity {

    /** Public field on {@code ClientPlayerEntity} (javap-verified), read
     *  for the movement input. */
    @Shadow
    public Input input;

    public AlwaysSprintMixin(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    /** Injection point: tickMovement falling PUTFIELD—while the constraints
     *  hold, add setSprinting(true). */
    @Inject(
            method = "tickMovement()V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;falling:Z",
                    opcode = Opcodes.PUTFIELD))
    private void nmz$alwaysSprint(CallbackInfo ci) {
        if (AlwaysSprint.isActive()
                && !this.isSprinting()
                && !this.isUsingItem()
                && this.input.movementForward >= 0.8F
                && (this.getHungerManager().getFoodLevel() > 6.0F || this.getAbilities().allowFlying)
                && !this.hasStatusEffect(StatusEffects.BLINDNESS)
                && !this.isTouchingWater()) {
            this.setSprinting(true);
        }
    }
}