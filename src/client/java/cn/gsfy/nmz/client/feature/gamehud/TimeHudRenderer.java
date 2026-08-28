package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 常驻计时 HUD——两行，右上角默认：游戏时长（跨回合累计）与回合计时（每回合清零），
 * 金色标签 + 白色整秒时间。进 Zombies 局内即渲染，不等回合标题，
 * 重进进行中的局同样显示。位置/缩放走 HUD 编辑器（xGameTime/yGameTime/scaleGameTime）。
 *
 * <p>显示口径是整秒（M:SS / H:MM:SS），不用小数，避免数字每秒跳动晃眼。
 * 局末 gameOver 时计分板被清空、isInZombies 随之失效，于是用 gameOver 兜底，
 * 让冻结的最终时长一直留到离场复位——通关/团灭审查都靠这一下。
 */
public class TimeHudRenderer extends TotalHUDRenderer {

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        GameTickHandler g = GameTickHandler.get();
        // 进 Zombies 局内即显示（isInZombies，含重进进行中的局）；gameOver 兜底——
        // 局末计分板被清空、isInZombies 随之失效，但冻结的最终时长要留住（通关/团灭审查），
        // 等离场复位 gameOver 后再隐藏
        if (g == null || !(PlayerUtils.isInZombies() || g.isGameOver())
                || !GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || !GlobalConfig.Hud.VISIBLE_GAME_TIME.getBooleanValue()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float absoluteX = (float) GlobalConfig.getXGameTime(screenWidth) * screenWidth;
        float absoluteY = (float) GlobalConfig.getYGameTime(screenHeight) * screenHeight;
        float scale = (float) GlobalConfig.Hud.SCALE_GAME_TIME.getDoubleValue();

        drawScaled(context, (int) absoluteX, (int) absoluteY, scale, () -> {
            String gameLabel = Text.translatable("nomorezombies.timehud.game").getString();
            String roundLabel = Text.translatable("nomorezombies.timehud.round").getString();
            int lineHeight = textRenderer.fontHeight;

            int x = (int) absoluteX;
            int y = (int) absoluteY;
            // 行 0：游戏时长（跨回合累计，金色标签 + 白色数值）
            context.drawTextWithShadow(textRenderer, gameLabel, x, y, 0xFFAA00);
            context.drawTextWithShadow(textRenderer, formatMs(g.getTotalGameTick()),
                    x + textRenderer.getWidth(gameLabel), y, 0xFFFFFF);
            // 行 1：本回合（正常走秒；游戏结束——通关或团灭——都冻结在当前值）
            y += lineHeight;
            String roundTime = formatMs(g.getGameTick());
            context.drawTextWithShadow(textRenderer, roundLabel, x, y, 0xFFAA00);
            context.drawTextWithShadow(textRenderer, roundTime,
                    x + textRenderer.getWidth(roundLabel), y, 0xFFFFFF);
        });
    }

    /** 毫秒 → 整秒时钟格式：M:SS，满 1 小时进到 H:MM:SS——整数除法直接截掉小数。 */
    static String formatMs(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
