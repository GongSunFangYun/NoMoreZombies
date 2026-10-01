package cn.gsfy.nmz.mixin.client.interaction;

import cn.gsfy.nmz.client.features.rightclick.RightClickFireOnly;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/**
 * Hologram pass-through—the crosshair ray avoids invisible armor stands, so
 * right-click is no longer eaten by holograms.
 *
 * <p>The target is {@code net.minecraft.client.render.GameRenderer}. The
 * injection point is {@link GameRenderer#findCrosshairTarget}'s call to
 * {@code ProjectileUtil.raycast(...)}; {@code @ModifyArg} changes its 5th
 * parameter (index 4)—the entity hit filter (originally
 * {@code EntityPredicates.CAN_HIT}). While active, "exclude
 * ArmorStandEntity" is appended to the original filter: the crosshair
 * raycast can never hit an armor stand, so the right-click no longer goes
 * to the "interact with armor stand" branch and falls through to firing /
 * the block behind. When off, the original filter is returned and behavior
 * is identical to vanilla. Target is the 1.21.4 yarn name.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCrosshairMixin {

    /** Injection point: findCrosshairTarget raycast @ModifyArg—filter
     *  appends "exclude armor stands". */
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
        // Append "exclude armor stands" (invisible holograms) to
        // EntityPredicates.CAN_HIT; do not replace the whole filter
        return original.and(e -> !(e instanceof ArmorStandEntity));
    }
}