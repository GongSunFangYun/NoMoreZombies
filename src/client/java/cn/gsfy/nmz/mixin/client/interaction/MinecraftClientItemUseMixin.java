package cn.gsfy.nmz.mixin.client.interaction;

import cn.gsfy.nmz.client.features.rightclick.RightClickFireOnly;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips interactable blocks' right-click response—right-clicking a block no
 * longer triggers a response and falls through to firing.
 *
 * <p>The target is {@code net.minecraft.client.MinecraftClient}. While
 * active, {@code @Redirect} redirects the block-interaction call inside
 * {@code doItemUse} to {@code ActionResult.PASS}: neither Success nor Fail,
 * so vanilla falls through to {@code interactItem} (firing). Together with
 * {@link GameRendererCrosshairMixin}, right-click only fires. When off, the
 * call is forwarded as-is to vanilla's
 * {@code ClientPlayerInteractionManager.interactBlock}. Target is the
 * 1.21.4 yarn name.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientItemUseMixin {

    /** Injection point: doItemUse interactBlock @Redirect—while active,
     *  returns PASS and falls through to firing. */
    @Redirect(
            method = "doItemUse()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;interactBlock(Lnet/minecraft/client/network/ClientPlayerEntity;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"
            )
    )
    private ActionResult nmz$skipBlockReactions(ClientPlayerInteractionManager manager,
                                                ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!RightClickFireOnly.isActive()) {
            return manager.interactBlock(player, hand, hit);
        }
        // Active: skip the block's right-click response, letting vanilla
        // fall through to interactItem (firing)
        return ActionResult.PASS;
    }
}