package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.rightclick.RightClickFireOnly;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/**
 * 全息字穿透（右键修复·①）——让准星射线绕开隐形盔甲架，右键不再被全息字吃掉。
 *
 * <p>注入点：{@link GameRenderer#findCrosshairTarget} 内对 {@code ProjectileUtil.raycast(...)}
 * 的调用，用 {@code @ModifyArg} 改其第 5 个参数（index 4）——实体命中过滤器（原值为
 * {@code EntityPredicates.CAN_HIT}）。开启时在原过滤器上追加「排除 ArmorStandEntity」：
 * 准星 raycast 永远命中不了盔甲架，右键就不再走「与盔甲架交互」分支，落到开枪/后方方块。
 * 关闭时原样返回，行为与 vanilla 完全一致。目标为 1.21.4 yarn 名。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCrosshairMixin {

    @ModifyArg(
            method = "findCrosshairTarget(Lnet/minecraft/entity/Entity;DDF)Lnet/minecraft/util/hit/HitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/projectile/ProjectileUtil;raycast(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;D)Lnet/minecraft/util/hit/EntityHitResult;"
            ),
            index = 4
    )
    private static Predicate<Entity> nmz$ignoreHolograms(Predicate<Entity> original) {
        if (!RightClickFireOnly.isActive()) {
            return original;
        }
        // 在原过滤器（EntityPredicates.CAN_HIT）基础上追加排除盔甲架（隐形全息字），不整体替换。
        return original.and(e -> !(e instanceof ArmorStandEntity));
    }
}
