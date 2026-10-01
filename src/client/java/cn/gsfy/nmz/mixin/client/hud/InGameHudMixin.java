package cn.gsfy.nmz.mixin.client.hud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.hud.HUDEditor;
import cn.gsfy.nmz.client.features.sidebar.ScoreboardHudRenderer;
import cn.gsfy.nmz.client.features.sidebar.SidebarEnhancer;
import cn.gsfy.nmz.client.shared.game.GameEventBus;
import cn.gsfy.nmz.client.utils.PlayerUtils;
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
 * Injects into {@code net.minecraft.client.gui.hud.InGameHud}—title
 * detection plus sidebar modification, two features sharing one mixin.
 *
 * <p>First, {@code setTitle}'s {@code @Inject @At("HEAD")}: the moment a
 * round-start / game-end title appears it is reported to
 * {@link GameEventBus} for wave, timing and other modules to react; the
 * title itself is not cancelled. Then
 * {@code renderScoreboardSidebar}'s HEAD gate: inside a Zombies game the
 * original method is cancelled throughout; when the switch allows, drawing
 * is handed to {@link ScoreboardHudRenderer}; when off, or the editor is
 * open, the sidebar is simply hidden.
 *
 * <p>The six subsequent {@code @ModifyArg} / {@code @Redirect} hooks are
 * the fallback/compat entries for the native render path: under the current
 * gate, a Zombies game is always cancelled at HEAD and they take no part in
 * the self-drawn path; an out-of-game path is let through as-is by
 * {@link SidebarEnhancer}. The two hook groups are fallback rewrites of the
 * native method, not a parallel in-game rendering path.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    /** Injection point: setTitle HEAD—the title is reported to GameEventBus
     *  the moment it appears. */
    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void nmz$onSetTitle(Text title, CallbackInfo ci) {
        if (title != null) {
            GameEventBus.onSetTitle(title.getString());
        }
    }

    /**
     * The sidebar render gate (top to bottom, falling through in order):
     * <ol>
     *  <li>Not in a Zombies game → let the native render through (not one
     *  character of another server's sidebar is touched);</li>
     *  <li>In-game but {@link GlobalConfig.Hud#scoreboardOn()} is false
     *  (master switch off / not placed on the canvas / the scoreboard
     *  element's own switch off—any of the three) → cancel the render, the
     *  whole sidebar is hidden.
     *  "Not placed" and "visibility off" have the same consequence on this
     *  element: the whole sidebar is hidden either way
     *  (the old standalone "hide vanilla scoreboard" switch has been merged
     *  into the element switch, no duplicated responsibility);</li>
     *  <li>In-game and all three hold → cancel the native render and let
     *  {@link ScoreboardHudRenderer} redraw at the configured anchor /
     *  scale; while the editor is open, only skip (the editor preview
     *  already draws the same thing—drawing again would ghost)</li>
     * </ol>
     * Affects the render layer only, never the mod's reading / modifying of
     * scoreboard data (ScoreboardManager's poll and SidebarEnhancer both go
     * through the data layer).
     */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void nmz$scoreboardGate(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (!GlobalConfig.Hud.scoreboardOn()) {
            ci.cancel();
            return;
        }
        if (!HUDEditor.IS_OPEN) {
            ScoreboardHudRenderer.render(context, objective);
        }
        ci.cancel();
    }

    /**
     * Fallback/compat entry: hands the native row name to the enhancer
     * (executed only when HEAD did not cancel).
     */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 1),
            index = 1)
    private Text nmz$modifySidebarLine(Text text) {
        return SidebarEnhancer.enhanceLine(text);
    }

    /**
     * Fallback/compat entry: native-path row filtering is delegated to the
     * enhancer (out of game it returns the entries as-is).
     */
    @Redirect(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/Scoreboard;getScoreboardEntries(Lnet/minecraft/scoreboard/ScoreboardObjective;)Ljava/util/Collection;"))
    private Collection<ScoreboardEntry> nmz$filterPlayerRows(Scoreboard scoreboard, ScoreboardObjective objective) {
        return SidebarEnhancer.filterSidebar(scoreboard, objective, scoreboard.getScoreboardEntries(objective));
    }

    /**
     * Fallback/compat entry: the native row name's x offset (making room
     * for the columns the enhancer widened).
     */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 1),
            index = 2)
    private int nmz$shiftNameX(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }

    /**
     * Fallback/compat entry: the native title's x offset.
     */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
                    ordinal = 0),
            index = 2)
    private int nmz$shiftTitleX(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer) / 2;
    }

    /**
     * Fallback/compat entry: the first native background block's x offset.
     */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V",
                    ordinal = 0),
            index = 0)
    private int nmz$shiftFillX0(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }

    /**
     * Fallback/compat entry: the second native background block's x offset.
     */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V",
                    ordinal = 1),
            index = 0)
    private int nmz$shiftFillX1(int x) {
        return x - SidebarEnhancer.getAddedWidth(MinecraftClient.getInstance().textRenderer);
    }
}