package cn.gsfy.nmz.mixin.client.freecam;

import cn.gsfy.nmz.client.features.freecam.CameraEntity;
import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Free-camera player-side rewrites—freeze the body, feign being the camera,
 * and block swinging, all three in one.
 *
 * <p>The target is {@code net.minecraft.client.network.ClientPlayerEntity};
 * every rewrite is gated by {@link FreeCameraHandler}'s active state and its
 * two sub-options.
 *
 * <p><b>Freeze input</b>: during the player tick, {@code this.input} is
 * swapped for an all-zero {@code Input}, so WASD cannot move the frozen
 * player; the real input is restored on RETURN. Why swapping the base
 * instance is safe: the {@code input.tick()} inside {@code tick()V} is a
 * polymorphic virtual call with no CHECKCAST, so a base instance is safe.
 * Effective only while "allow player movement" is off.
 *
 * <p><b>{@code isCamera()} spoof</b>: makes the player still believe it is
 * the camera—{@code sendMovementPackets} / {@code tickNewAi} /
 * {@code tickMovement} all read it to decide "alive, updating, sending
 * position packets", so the player does not freeze to death and the
 * server-side position is maintained. {@code ClientPlayerEntity} itself
 * never calls {@code setCameraEntity}, so forcing it to true is safe.
 *
 * <p><b>Ban swinging</b>: with "allow player input" off, {@code swingHand}
 * cancels outright, working alongside {@code FreeCameraInteractionMixin}'s
 * ban at the interaction source.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class FreeCameraPlayerMixin {

    /** Whether HEAD has already frozen (RETURN restores symmetrically
     *  based on it, preventing a mid-tick state flip from freezing input
     *  forever). */
    @Unique
    private boolean nmz$inputFrozen;

    /** tick HEAD freeze: swaps this.input for an all-zero instance, WASD
     *  cannot move the player. */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void nmz$freezeInputHead(CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventMovement()) {
            ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
            // The real input is recorded into the handler's static field; only
            // a non-frozen instance updates it (otherwise a previous tick's
            // exception leaving a frozen instance in input would mis-record it
            // as "the real input", and the restore could never get the real one back)
            FreeCameraHandler.captureRealInput(self.input);
            self.input = FreeCameraHandler.getFrozenInput();
            this.nmz$inputFrozen = true;
        }
    }

    /** tick RETURN restore: symmetric with HEAD (reads
     *  nmz$inputFrozen), restores the real input. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void nmz$freezeInputReturn(CallbackInfo ci) {
        if (this.nmz$inputFrozen) {
            // Restore the real input recorded by the handler (not the current
            // value at this tick's HEAD), preventing a leftover from polluting;
            // together with the handler's per-tick fallback, any leftover from
            // an interrupt self-heals
            ((ClientPlayerEntity) (Object) this).input = FreeCameraHandler.getRealInput();
            this.nmz$inputFrozen = false;
        }
    }

    /** isCamera HEAD spoof: makes the player still believe it is the camera,
     *  so it does not freeze to death. */
    @Inject(method = "isCamera()Z", at = @At("HEAD"), cancellable = true)
    private void nmz$fakeCamera(CallbackInfoReturnable<Boolean> cir) {
        if (FreeCameraHandler.isActive() && CameraEntity.originalCameraWasPlayer()) {
            cir.setReturnValue(true);
        }
    }

    /** swingHand HEAD cancel: no swing animation while player input is
     *  banned (a second safeguard alongside the interaction ban). */
    @Inject(method = "swingHand(Lnet/minecraft/util/Hand;)V", at = @At("HEAD"), cancellable = true)
    private void nmz$blockSwing(Hand hand, CallbackInfo ci) {
        if (FreeCameraHandler.shouldPreventInputs()) {
            ci.cancel();
        }
    }
}