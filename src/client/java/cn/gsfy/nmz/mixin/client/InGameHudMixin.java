package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.sidebar.SidebarEnhancer;
import cn.gsfy.nmz.client.shared.GameEventBus;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 InGameHud——标题检测 + 侧边栏改造，两个功能共用这一个 mixin。
 *
 * <p>先是 {@code setTitle}：回合开始 / 游戏结束标题一出现就上报给 {@link GameEventBus}，供波次、
 * 计时等模块联动。再是 {@code renderScoreboardSidebar}：在侧边栏渲染时做行文本增强
 * （过滤玩家行 / 原生时间行剥离对应段），让 mod 的 HUD 与原生侧边栏不打架。
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void nmz$onSetTitle(Text title, CallbackInfo ci) {
        if (title != null) {
            GameEventBus.onSetTitle(title.getString());
        }
    }

    /** 隐藏原生计分板（右侧侧边栏）：开启且 Zombies 生效时取消整个 renderScoreboardSidebar。
     * 只影响渲染层，不影响 mod 读取/修改计分板数据（ScoreboardManager 轮询、SidebarEnhancer 均走数据层）。 */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$hideScoreboard(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
        if (GlobalConfig.QoL.HIDE_SCOREBOARD.getBooleanValue() && PlayerUtils.isInZombies()) {
            ci.cancel();
        }
    }

    /** 修改侧边栏每行的名字文本（drawText 的第 2 个 drawText 调用点 = 行名）。 */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 1),
            index = 1)
    private Text nmz$modifySidebarLine(Text text) {
        return SidebarEnhancer.enhanceLine(text);
    }

    /** 从原生计分板过滤：队伍统计 HUD 开启 → 移除玩家行与空行；原生时间行（时间段+Kills 同行）按
     * 各 HUD 开关选择性剥离对应段（两个都开则整行移除）。均与 HUD 显示开关二元对立（HUD 显示时隐藏原生对应数据）。 */
    @Redirect(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/Scoreboard;getScoreboardEntries(Lnet/minecraft/scoreboard/ScoreboardObjective;)Ljava/util/Collection;"))
    private Collection<ScoreboardEntry> nmz$filterPlayerRows(Scoreboard scoreboard, ScoreboardObjective objective) {
        // 过滤逻辑统一在 SidebarEnhancer.filterSidebar（编辑器保存时的 DEBUG dump 与渲染共用同一逻辑）。
        return SidebarEnhancer.filterSidebar(scoreboard, objective, scoreboard.getScoreboardEntries(objective));
    }

    /** 行名 x 坐标左移，给追加的血量/冷却腾位置（金币保持右对齐不受影响）。 */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 1),
            index = 2)
    private int nmz$shiftNameX(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }

    /** 标题 x 坐标左移一半（保持居中于变宽的框）。 */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 0),
            index = 2)
    private int nmz$shiftTitleX(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer) / 2;
    }

    /** 背景框 x 左移，撑大以容纳追加文本。 */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V",
                    ordinal = 0),
            index = 0)
    private int nmz$shiftFillX0(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }

    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V",
                    ordinal = 1),
            index = 0)
    private int nmz$shiftFillX1(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }
}
