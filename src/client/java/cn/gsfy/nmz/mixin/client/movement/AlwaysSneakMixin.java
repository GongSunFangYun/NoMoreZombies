package cn.gsfy.nmz.mixin.client.movement;

import cn.gsfy.nmz.client.features.sneak.AlwaysSneak;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Permanent sneak—welds the sneak key down, saving a held Shift.
 *
 * <p>The target is {@code net.minecraft.client.input.KeyboardInput}.
 * Injects {@code KeyboardInput.tick()V} (a no-arg method, the stage where
 * input state settles) at {@code @At("RETURN")} rather than HEAD: vanilla
 * keyboard input must settle first (the tick assembles {@code playerInput}),
 * then sneak is overwritten at the end—writing at HEAD would be overwritten
 * by the later assembly. After each keyboard-input settlement, when
 * {@link AlwaysSneak#isActive()} is on, {@code playerInput} is rebuilt with
 * sneak=true. Why rebuild the whole thing: in 1.21.4 {@code Input} has no
 * standalone {@code sneaking} field, and the sneak flag lives in the
 * {@code PlayerInput} record—the crouch animation, edge-fall protection,
 * hitbox shrink and sneak packet all go through the vanilla state machine
 * (equivalent to holding the sneak key). Pure client input layer, no extra
 * packets sent.
 *
 * <p>Implementation note: {@code playerInput} is declared on the superclass
 * {@code Input}, so <b>{@code @Shadow} cannot be used</b>—loom's
 * compile-time field-name remapping for a {@code @Shadow} without an owner
 * maps to the wrong class (measured: mapped to PlayerInput's field_54155),
 * and at runtime the field is not found in KeyboardInput's inheritance
 * chain, causing an immediate startup crash (InvalidMixinException,
 * reproduced 2026-08-19). The workaround: {@code (Input)(Object)this}
 * fetches the public field directly—the owner is explicit and loom remaps
 * exactly by owner.
 */
@Mixin(KeyboardInput.class)
public abstract class AlwaysSneakMixin {

    /** Injection point: tick RETURN—when sneak is active, rebuild
     *  playerInput with sneak=true. */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void nmz$alwaysSneak(CallbackInfo ci) {
        if (AlwaysSneak.isActive()) {
            Input input = (Input) (Object) this;
            PlayerInput p = input.playerInput;
            if (p != null && !p.sneak()) {
                input.playerInput = new PlayerInput(
                        p.forward(), p.backward(), p.left(), p.right(), p.jump(), true, p.sprint());
            }
        }
    }
}