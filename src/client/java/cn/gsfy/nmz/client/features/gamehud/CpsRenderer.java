package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.cps.CpsTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * CPS HUD—pins the left/right click rate on screen for hand-speed practice
 * and output pacing: one row per button, red left, green right, white
 * numbers, gray unit. Renders only inside a Zombies game; position and scale
 * go through the HUD editor.
 *
 * <p>The data source is {@link CpsTracker}; this class only lays out text.
 * Each row advances through "label + value + unit" in order, and the second
 * row reuses the same left anchor and steps down one fontHeight, so the two
 * rows align naturally.
 */
public class CpsRenderer extends TotalHUDRenderer {

    private static final int LEFT_COLOR = 0xFF5555;
    private static final int RIGHT_COLOR = 0x55FF55;
    private static final int VALUE_COLOR = 0xFFFFFF;
    private static final int UNIT_COLOR = 0xAAAAAA;

    /** The sample value: two digits is the common shape, and the width is
     *  measured from it—the editor frame and the default right-hug
     *  resolution share one accounting. */
    private static final String SAMPLE_LEFT_CPS = "12";
    private static final String SAMPLE_RIGHT_CPS = "8";

    /**
     * The editor sample width: the wider of the two rows' "label value unit"
     * using the <b>sample values</b> (two digits), plus the shadow overhang.
     * The editor frame follows the sample—measuring the text you can see
     * keeps the frame from running wider than its content.
     */
    public static int previewWidth(net.minecraft.client.font.TextRenderer tr) {
        return Math.max(lineWidth(tr, "left", SAMPLE_LEFT_CPS), lineWidth(tr, "right", SAMPLE_RIGHT_CPS))
                + TEXT_SHADOW;
    }

    /**
     * Component pixel width (for default right-hug resolution): the larger of
     * "the sample width" and "the current live readout width".
     *
     * <p>Why not pure live: CPS changes every second, and measuring pure live
     * would make the default right-hug position jitter per second (the whole
     * block jumps left and right as it crosses between two and three digits).
     * Why not pure sample: three-digit CPS (not rare when grinding hand speed)
     * is wider than the sample, and the extra part would overflow the right
     * edge when anchored right. Taking the larger of the two: the common
     * two-digit range equals the sample width exactly (no jitter), and only a
     * true three-digit reading lets it grow (no overflow).
     */
    public static int hudWidth(net.minecraft.client.font.TextRenderer tr) {
        int live = Math.max(lineWidth(tr, "left", Integer.toString(CpsTracker.getLeftCps())),
                lineWidth(tr, "right", Integer.toString(CpsTracker.getRightCps())));
        return Math.max(previewWidth(tr), live + TEXT_SHADOW);
    }

    /** One "label + space value + space unit" row's pixel width;
     *  {@code key} is {@code left} / {@code right}. */
    private static int lineWidth(net.minecraft.client.font.TextRenderer tr, String key, String value) {
        String unit = Text.translatable("nomorezombies.cps.unit").getString();
        return tr.getWidth(Text.translatable("nomorezombies.cps." + key).getString())
                + tr.getWidth(" " + value + " " + unit);
    }

    /** Component pixel height: two text rows + the last row's shadow overhang. */
    public static int hudHeight(net.minecraft.client.font.TextRenderer tr) {
        return tr.fontHeight * 2 + TEXT_SHADOW;
    }

    /**
     * Draws the left and right CPS rows: red left, green right, white
     * numbers, gray unit; text lays out left to right within a row, and the
     * second row reuses the same left anchor. The anchor rounds the same way
     * as the other renderers: {@code anchorPixels} multiplies the ratio by
     * the travel and rounds to an integer, so edge-hugging does not come out
     * 1px off.
     *
     * <p>Gate = {@link GlobalConfig.Hud#cpsOn()} (master switch + placed +
     * this element's visibility).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.cpsOn()) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_CPS.getDoubleValue();
        // Content width by "this frame's live readout vs. the sample, whichever
        // is larger": the position is self-consistent per frame, and three-digit
        // CPS never gets pushed off the right edge (see hudWidth).
        int hudW = visibleSize(hudWidth(textRenderer), scale);
        int hudH = visibleSize(hudHeight(textRenderer), scale);
        int x = anchorPixels(GlobalConfig.getXCps(), screenWidth, hudW, 0);
        int y = anchorPixels(GlobalConfig.getYCps(), screenHeight, hudH, 0);

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

    /** Draws one text segment and returns the X the next character should
     *  land on—the in-row left-to-right layout relays on this return. */
    private int drawText(DrawContext context, String s, int x, int y, int color) {
        context.drawTextWithShadow(textRenderer, s, x, y, color);
        return x + textRenderer.getWidth(s);
    }
}