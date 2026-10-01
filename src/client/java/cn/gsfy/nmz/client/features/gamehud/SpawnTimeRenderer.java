package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Wave-time HUD (counterpart of the source SpawnTimeRenderer). Lists each
 * wave's projected time for the current round, marks the next wave with the
 * "➤" arrow, and colors wave rows by proximity via
 * {@link CheckSpawnTimes#getColor(int)}.
 *
 * <p>Rendering reads the core logic's
 * {@link CheckSpawnTimes} settlement only; it does not project on its own.
 * Gating layers from outside: shouldRender master switch + utility HUD
 * master switch + this HUD's own switch. Position/scale go through the HUD
 * editor (Hud.X/Y/SCALE_SPAWN_TIME).
 */
public class SpawnTimeRenderer extends TotalHUDRenderer {

    /** The next-wave arrow (the same string constant as real rendering; drawn in magenta 0xCC00CC). */
    public static final String PREVIEW_ARROW = "➤ ";

    /**
     * The editor sample's wave rows—measuring and drawing share this one
     * copy, so the frame cannot misalign with the drawn text. Six rows is
     * AA's wave cap; each row is "W+wave+space+MM:SS", measured per row and
     * taking the widest.
     */
    public static final String[] PREVIEW_LINES = {
            "W1 00:12", "W2 00:18", "W3 00:24", "W4 00:30", "W5 00:36", "W6 00:44"
    };

    /** Wave row count (shared by the sample and the height accounting). */
    public static final int PREVIEW_ROWS = 6;

    /**
     * The real row count to draw (0 rows when the wave table is empty)—
     * shared by anchor positioning and drawing.
     *
     * <p>{@link #hudHeight} measures the sample's 6 rows, but in game the
     * wave table may not have arrived yet (0 rows) or may only have the
     * first few waves; anchor positioning must compute travel from
     * <b>this frame's real row count</b>, or a bottom-hug wave HUD with only
     * three waves would float half a block above the edge.
     *
     * @param spawnTimes the current wave table (may be {@code null})
     * @return this frame's row count
     */
    private static int liveRows(CheckSpawnTimes spawnTimes) {
        return spawnTimes == null ? 0 : spawnTimes.getRoundTimes().length;
    }

    /**
     * This frame's content height: computed from {@link #liveRows}, 0 for an
     * empty table.
     *
     * @param tr the text renderer
     * @param rows this frame's row count
     * @return this frame's content pixel height (shadow overhang included)
     */
    private static int liveHeight(TextRenderer tr, int rows) {
        return rows <= 0 ? 0 : rows * tr.fontHeight + TEXT_SHADOW;
    }

    /**
     * The editor sample width: arrow column + the widest wave row + shadow
     * overhang.
     *
     * <p>The sample rows are shaped exactly like real rows
     * ("W+wave+space+MM:SS"), and in Minecraft's default font digits are
     * monospaced, so <b>a real row is never wider than the sample</b>: the
     * wave number is one digit and the time is always {@code MM:SS}
     * (zero-padded, so two minutes and two seconds forever). Because it
     * cannot grow wider, it also serves as the width reference for log
     * dumps.
     */
    public static int hudWidth(net.minecraft.client.font.TextRenderer tr) {
        int arrowW = tr.getWidth(PREVIEW_ARROW);
        int lineW = 0;
        for (String line : PREVIEW_LINES) {
            lineW = Math.max(lineW, tr.getWidth(line));
        }
        return arrowW + lineW + TEXT_SHADOW;
    }

    /** The editor sample height: 6 text rows (AA has a W6) + the last row's shadow overhang. */
    public static int hudHeight(net.minecraft.client.font.TextRenderer tr) {
        return tr.fontHeight * PREVIEW_ROWS + TEXT_SHADOW;
    }

    /**
     * Draws each wave's projected time row by row, with the "➤" arrow at the
     * next wave (drawn on line {@code fontHeight x (nextWave - 1)}); other
     * rows take {@link CheckSpawnTimes}'s color by proximity.
     *
     * <p>Gating order: editor IS_OPEN short-circuit → {@code shouldRender} →
     * {@link GlobalConfig.Hud#spawnTimeOn()} (master switch + placed + this
     * element's visibility). When {@link CheckSpawnTimes#get()} is not yet
     * initialized ({@code null}), that frame is skipped.
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!shouldRender || !GlobalConfig.Hud.spawnTimeOn()) {
            return;
        }
        CheckSpawnTimes spawnTimes = CheckSpawnTimes.get();
        if (spawnTimes == null) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_SPAWN_TIME.getDoubleValue();
        // Content width by the sample (a real row is "W + a one-digit wave +
        // MM:SS", never wider than the sample); height by this frame's real
        // wave row count—bottom-hug then lands the whole block on that edge
        // instead of floating it mid-air.
        int rows = liveRows(spawnTimes);
        int hudW = visibleSize(hudWidth(textRenderer), scale);
        int hudH = visibleSize(liveHeight(textRenderer, rows), scale);
        // Reserve 0: the hotbar only occupies the screen's lower middle, and
        // reserving a full 26px on every edge would keep this table out of the
        // bottom corners. Whether it overlaps the hotbar is left to the
        // player's eyes—the default X/Y (1.0/1.0) already sits in the
        // bottom-right corner.
        int absoluteX = anchorPixels(GlobalConfig.getXSpawnTime(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYSpawnTime(), screenHeight, hudH, 0);

        drawScaled(context, absoluteX, absoluteY, scale, () -> {
            // This call is the entry point that advances the current wave; it
            // is not a useless fetch: the return value is unused, but
            // currentWave is refreshed by it against this frame's wall clock,
            // and getNextWave/getColor read that field—delete it and the arrow
            // sticks at wave 1 forever (the IDE's "return value never used" is
            // a false positive here).
            spawnTimes.getCurrentWave();
            int waveAmount = spawnTimes.getRoundTimes().length;
            if (spawnTimes.getCurrentRound() != 0 && waveAmount == 0) {
                return;
            }
            String arrow = PREVIEW_ARROW;
            int widthW = textRenderer.getWidth(arrow);
            if (waveAmount != 0) {
                context.drawTextWithShadow(textRenderer, arrow,
                        absoluteX,
                        absoluteY + textRenderer.fontHeight * (spawnTimes.getNextWave() - 1),
                        0xCC00CC);
            }
            for (int i = 0; i < waveAmount; i++) {
                int wave = i + 1;
                String line = "W" + wave + " " + getTime(spawnTimes.getWaveTime(wave));
                context.drawTextWithShadow(textRenderer, line,
                        absoluteX + widthW,
                        absoluteY + textRenderer.fontHeight * (wave - 1),
                        spawnTimes.getColor(wave));
            }
        });
    }

    /** Seconds → "MM:SS" (zero-padded); time≤0 is treated as 00:00. */
    private String getTime(int time) {
        if (time <= 0) {
            return "00:00";
        }
        int seconds = time % 60;
        int minutes = time / 60;
        String strSeconds = seconds < 10 ? "0" + seconds : String.valueOf(seconds);
        String strMinutes = minutes < 10 ? "0" + minutes : String.valueOf(minutes);
        return strMinutes + ":" + strSeconds;
    }
}