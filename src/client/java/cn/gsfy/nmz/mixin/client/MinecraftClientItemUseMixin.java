package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.rightclick.RightClickFireOnly;
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
 * 忽略交互方块右键反应（右键修复·②）——右键方块不再触发反应，落到开枪。
 *
 * <p>开启时用 {@code @Redirect} 把 {@code doItemUse} 里的方块交互调用重定向为
 * {@code ActionResult.PASS}：既非 Success 也非 Fail，vanilla 便 fall-through 到
 * {@code interactItem}（开枪）。配合 {@link GameRendererCrosshairMixin}，右键只开枪。
 * 关闭时原样转发给 vanilla {@code ClientPlayerInteractionManager.interactBlock}。
 * 目标为 1.21.4 yarn 名。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientItemUseMixin {

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
        // 开启：跳过方块右键反应，让 vanilla 走 useItem（开枪）。
        return ActionResult.PASS;
    }
}
