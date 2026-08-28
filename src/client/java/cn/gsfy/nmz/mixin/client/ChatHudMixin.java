package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.filter.ChatFilter;
import cn.gsfy.nmz.client.util.StringUtils;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天过滤——把纯噪音消息掐在进 HUD 之前的源头。
 *
 * <p>注入 {@code ChatHud.addMessage(Text, MessageSignatureData, MessageIndicator)}，
 * {@code @Inject} 挂在 {@code @At("HEAD")}、可取消：每条聊天消息进 HUD 之前，命中
 * {@link ChatFilter#shouldHide} 就 cancel——消息彻底不进 HUD。之所以挑这里下手：
 * Fabric GAME 事件不可取消（消息已进 HUD），只能在源头拦；且只取消纯噪音消息，
 * mod 解析所需消息因匹配器隔离 + isActivatedMessage 保险闸不受影响，GAME 事件照常触发。
 *
 * <p>优先级说明：priority 500 < 1000（Fabric 默认），保证本 cancel 在 Fabric GAME 事件之后执行——
 * 被隐藏的消息仍先走 {@code ClientReceiveMessageEvents.GAME}（mod 解析），随后才被取消渲染。
 */
@Mixin(value = ChatHud.class, priority = 500)
public abstract class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideNoiseMessages(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (message != null && ChatFilter.shouldHide(StringUtils.getRaw(message))) {
            ci.cancel();
        }
    }
}
