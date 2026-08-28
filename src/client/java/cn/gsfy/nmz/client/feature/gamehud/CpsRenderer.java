package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.cps.CpsTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * CPS 统计 HUD——把左右键点按频率实时钉在屏幕上，供练手速、盯输出节奏用：
 *   左键一行、右键一行，左红右绿、数字白、单位灰。
 * 仅 Zombies 局内渲染；位置 / 缩放走 HUD 编辑器。
 *
 * <p>数据源是 {@link CpsTracker}，这里只负责摆字：每行按「标签 + 数值 + 单位」
 * 三段顺次推进，第二行沿用同一左锚 x、行距错开 fontHeight，两行自然对齐。
 */
public class CpsRenderer extends TotalHUDRenderer {

    private static final int LEFT_COLOR = 0xFF5555;
    private static final int RIGHT_COLOR = 0x55FF55;
    private static final int VALUE_COLOR = 0xFFFFFF;
    private static final int UNIT_COLOR = 0xAAAAAA;

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || !GlobalConfig.Hud.VISIBLE_CPS.getBooleanValue()) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int x = (int) (GlobalConfig.getXCps(screenWidth) * screenWidth);
        int y = (int) (GlobalConfig.getYCps(screenHeight) * screenHeight);
        float scale = (float) GlobalConfig.Hud.SCALE_CPS.getDoubleValue();

        drawScaled(context, x, y, scale, () -> {
            String leftLabel = Text.translatable("nomorezombies.cps.left").getString();
            String rightLabel = Text.translatable("nomorezombies.cps.right").getString();
            String unit = Text.translatable("nomorezombies.cps.unit").getString();
            String leftCps = Integer.toString(CpsTracker.getLeftCps());
            String rightCps = Integer.toString(CpsTracker.getRightCps());
            int lineHeight = textRenderer.fontHeight;

            int cx = x;
            cx = drawText(context, leftLabel, cx, y, LEFT_COLOR);
            cx = drawText(context, " " + leftCps, cx, y, VALUE_COLOR);
            drawText(context, " " + unit, cx, y, UNIT_COLOR);

            cx = x;
            cx = drawText(context, rightLabel, cx, y + lineHeight, RIGHT_COLOR);
            cx = drawText(context, " " + rightCps, cx, y + lineHeight, VALUE_COLOR);
            drawText(context, " " + unit, cx, y + lineHeight, UNIT_COLOR);
        });
    }

    /** 画一段文字并返回下一个字符应落的 X 坐标——行内从左往右铺字全靠这个返回值接力。 */
    private int drawText(DrawContext context, String s, int x, int y, int color) {
        context.drawTextWithShadow(textRenderer, s, x, y, color);
        return x + textRenderer.getWidth(s);
    }
}
