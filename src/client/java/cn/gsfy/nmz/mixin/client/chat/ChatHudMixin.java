package cn.gsfy.nmz.mixin.client.chat;

import cn.gsfy.nmz.client.features.filter.ChatFilter;
import cn.gsfy.nmz.client.utils.StringUtils;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat filtering—cuts pure-noise messages at the source, before the HUD sees
 * them.
 *
 * <p>The target is {@code net.minecraft.client.gui.hud.ChatHud}. Injects
 * {@code ChatHud.addMessage(Text, MessageSignatureData, MessageIndicator)}
 * with {@code @Inject} at {@code @At("HEAD")}, cancellable: before each
 * chat message enters the HUD, a hit on
 * {@link ChatFilter#shouldHide(String)} cancels it—the message never
 * reaches the HUD.
 *
 * <p>Why this particular spot: the Fabric GAME event is not cancellable
 * (the message has already entered the HUD), so the only place to cut is
 * the source. And only pure-noise messages are cancelled—messages the mod
 * needs to parse are unaffected thanks to the matchers' isolation plus the
 * {@code isActivatedMessage} safety catch, and the GAME event still fires
 * as usual.
 *
 * <p>The priority note: priority 500 is below 1000 (Fabric's default), so
 * this cancel runs after the Fabric GAME event—a hidden message still goes
 * through {@code ClientReceiveMessageEvents.GAME} first (for mod parsing),
 * and is cancelled from rendering afterwards.
 */
@Mixin(value = ChatHud.class, priority = 500)
public abstract class ChatHudMixin {

    /** Injection point: addMessage HEAD—a filter hit cancels, noise never
     *  reaches the HUD. */
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideNoiseMessages(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (message != null && ChatFilter.shouldHide(StringUtils.getRaw(message))) {
            ci.cancel();
        }
    }
}