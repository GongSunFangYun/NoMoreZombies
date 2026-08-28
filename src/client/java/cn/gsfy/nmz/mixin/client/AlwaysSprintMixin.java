package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.sprint.AlwaysSprint;
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
 * 永久疾跑——移动时自动保持冲刺，省得一直按着 Ctrl+W。
 *
 * <p>注入 {@code ClientPlayerEntity.tickMovement}，{@code @Inject} 取 {@code falling} 字段的
 * {@code @At("FIELD")} PUTFIELD（offset 922，位于所有 {@code setSprinting} 调用之后、无条件执行）——
 * 也就是等原版疾跑判定全部落定之后，每次移动刻结算、约束满足时调用 {@code setSprinting(true)}。
 * 效果等价「疾跑键永远按住」，但原版疾跑停止条件一条不丢：仍必须按 W 前进
 * （{@code movementForward >= 0.8}），饥饿、失明、泡水、手持使用物品等一律照旧，
 * 免得被服务器判定为异常移动（非原地站着也跑）。
 *
 * <p>实现方式：借 {@code extends AbstractClientPlayerEntity}（父类构造器
 * {@code (ClientWorld, GameProfile)}，javap 实证）直接调用继承的 public 方法，无需逐个 @Shadow。
 */
@Mixin(ClientPlayerEntity.class)
public abstract class AlwaysSprintMixin extends AbstractClientPlayerEntity {

    /** {@code ClientPlayerEntity} 上的公开字段（javap 实证），用于读取移动输入。 */
    @Shadow
    public Input input;

    public AlwaysSprintMixin(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

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
