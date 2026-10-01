package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.DifficultyId;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.data.model.PowerupPattern;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.utils.DifficultyUtils;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.RoundUtils;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Global overview HUD—a three-row, six-column scrolling round table: a
 * round row, a boss row, a powerup row. One glance tells you "over the next
 * few rounds, when a boss comes and when a powerup comes."
 *
 * <p>Window = current round + the next five; the current round's column is
 * all green and always leftmost. Each new round shifts the whole block one
 * column left (a scrolling window).
 *
 * <p>Every value comes from a static table (DataManager) and does not
 * depend on powerup detection's commit-once runtime state—so even with no
 * powerup armor stand observed this game, the whole prediction table is
 * already drawn: the boss row reads boss_rounds.json through
 * {@code map->difficulty->rounds} (difficulty from the scoreboard
 * identification, falling back to normal when unidentified; see
 * {@link RoundUtils#isBossRound(MapId, int, DifficultyId)}). The powerup row
 * is the union of every type's every pattern in powerup_patterns.json
 * (explicit table + ones-digit extrapolation).
 *
 * <p>Colors: round row white / boss row red / powerup row blue, labels
 * gold; the current round's whole column is overpainted green. The window
 * is always current + five, six columns. The round cap comes first from
 * wave_times's {@code max_round}, falling back to the map's {@code rows}
 * count only when the field is missing (AA=105 / DE·BB·Prison=30)—columns
 * past the cap are left blank, so the powerup row's ones-digit
 * extrapolation never predicts a cell at round 31 on a regular map.
 *
 * <p>Cell text is always {@code [XX]} (100+ is {@code [100]}, brackets
 * kept). Column width is measured per cell from that cell's own text, so
 * three-digit rounds stay complete while low rounds do not get a layout
 * stretched wide; travel comes from this frame's real width, so a right-hug
 * HUD stays on screen without a right-edge clamp. Gate =
 * {@link GlobalConfig.Hud#globalOverviewOn()} (master switch + placed + this
 * HUD's own switch) + the base class's isInZombies.
 */
public class GlobalOverviewRenderer extends TotalHUDRenderer {

    /** Window is fixed at 6 columns: current round + the next five. */
    private static final int WINDOW = 6;
    /** Gap between adjacent columns (px). Column width is measured per cell
     *  (see {@link #columnWidth}); the gap is added only between columns. */
    private static final int COLUMN_GAP = 1;
    /** Colors: round row white / boss row red / powerup row blue / current
     *  round's column green / row labels gold (same source as the AA
     *  command HUD). */
    private static final int COLOR_ROUND = 0xFFFFFF;
    private static final int COLOR_BOSS = 0xFF5555;
    private static final int COLOR_POWERUP = 0x5555FF;
    private static final int COLOR_CURRENT = 0x55FF55;
    private static final int COLOR_LABEL = 0xFFAA00;

    /** The three row labels' lang key prefix (.round/.boss/.powerup),
     *  shared by preview and real rendering. */
    static final String LABEL_KEY_PREFIX = "nomorezombies.globaloverview.";

    /** Window start = current round (scrolling): shows [current, current+5],
     *  shifting one column left each new round. */
    static int windowStart(int currentRound) {
        return currentRound;
    }

    /**
     * One column's width: that round's cell text's actual width + the column
     * gap. A cell is always "{@code [}+ round zero-padded to at least two
     * digits + {@code ]}", so a two-digit round is {@code [07]} (20px) and a
     * three-digit round is {@code [100]} (26px), and the column width changes
     * naturally with the digit count—a three-digit column fits only when it
     * has to, and low rounds don't get the layout stretched wide the way a
     * "reserve for three digits uniformly" scheme would.
     *
     * <p><b>public</b>: the editor preview steps column by column through the
     * same function, so it cannot misalign with the in-game render.
     */
    public static int columnWidth(TextRenderer tr, int round) {
        return tr.getWidth(cellText(round)) + COLUMN_GAP;
    }

    /** The widest pixel width among the three row labels (round / boss /
     *  powerup)—one accounting shared by rendering, preview, and total width. */
    private static int labelWidth(TextRenderer tr) {
        int labelW = 0;
        for (String key : new String[]{"round", "boss", "powerup"}) {
            labelW = Math.max(labelW, tr.getWidth(Text.translatable(LABEL_KEY_PREFIX + key).getString()));
        }
        return labelW;
    }

    /**
     * Nominal total width: measured from <b>two-digit</b> cells
     * ({@code [07]})—used by the default right-hug resolution and the editor
     * preview.
     *
     * <p>Why not measure total width from "the widest three-digit cell":
     * rounds 1–99's real window is two-digit wide, and a resolver that
     * reserved for three digits would shift every player's tuned position
     * left by a stretch, for a shape that mostly never appears. A true
     * three-digit round's real width is measured by {@link #windowWidth}.
     */
    public static int hudWidth(TextRenderer tr) {
        return labelWidth(tr) + WINDOW * columnWidth(tr, 0) - COLUMN_GAP;
    }

    /**
     * The current window's real total width: measured column by column
     * ({@value #WINDOW} consecutive rounds from {@code start}). Rounds 1–99
     * equal {@link #hudWidth}; it only widens when 100+ appears in the
     * window.
     */
    static int windowWidth(TextRenderer tr, int start) {
        int w = labelWidth(tr);
        for (int i = 0; i < WINDOW; i++) {
            w += columnWidth(tr, start + i);
        }
        return w - COLUMN_GAP;
    }

    /** The editor sample width: equivalent to {@link #hudWidth} (a two-digit
     *  window over rounds 1–99), plus the shadow overhang—sample and real
     *  content are the same width; the default right-hug resolution
     *  ({@code getXGlobalOverview}) also goes through this. */
    public static int previewWidth(TextRenderer tr) {
        return hudWidth(tr) + TotalHUDRenderer.TEXT_SHADOW;
    }

    /**
     * The editor sample height: three text rows + the last row's shadow
     * overhang.
     */
    public static int previewHeight(TextRenderer tr) {
        return tr.fontHeight * 3 + TotalHUDRenderer.TEXT_SHADOW;
    }

    /**
     * Draws the global overview table each frame: reads the current round and
     * map to fix the window start, resolves the difficulty once and passes it
     * to the whole table. Gate =
     * {@link GlobalConfig.Hud#globalOverviewOn()} (master switch + placed +
     * this element's visibility).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.globalOverviewOn()) {
            return;
        }
        CheckSpawnTimes spawn = CheckSpawnTimes.get();
        if (spawn == null) {
            return;
        }
        int currentRound = spawn.getCurrentRound();
        if (currentRound <= 0) {
            return;
        }
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            return;
        }

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int start = windowStart(currentRound);
        float scale = (float) GlobalConfig.Hud.SCALE_GLOBAL_OVERVIEW.getDoubleValue();

        // Content width measured column by column from this frame's real window
        // (three-digit rounds' columns are wider), not the nominal two-digit
        // width: anchor travel = screen width - content width - reserve, so
        // "right-hug" hugs the right edge at any round with no right-edge clamp.
        int hudW = visibleSize(windowWidth(textRenderer, start) + TEXT_SHADOW, scale);
        int hudH = visibleSize(previewHeight(textRenderer), scale);
        int absoluteX = anchorPixels(GlobalConfig.getXGlobalOverview(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYGlobalOverview(), screenHeight, hudH, 0);

        // Resolve difficulty once and pass it to the whole table: all 6 columns
        // share one tier, avoiding per-column queries that could leave half the
        // batch on an old tier and half on a new one.
        DifficultyId difficulty = DifficultyUtils.getDifficulty();
        final int drawX = absoluteX;
        drawScaled(context, absoluteX, absoluteY, scale,
                () -> drawOverview(context, drawX, absoluteY, map, start, currentRound, difficulty));
    }

    /** The three-row table: the round row is always 6 columns; boss/powerup
     *  rows draw a cell when the static table hits, and the current round's
     *  whole column is overpainted green. */
    private void drawOverview(DrawContext context, int x, int y, MapId map, int start, int currentRound,
                              DifficultyId difficulty) {
        TextRenderer tr = textRenderer;
        int fh = tr.fontHeight;

        String roundLabel = Text.translatable(LABEL_KEY_PREFIX + "round").getString();
        String bossLabel = Text.translatable(LABEL_KEY_PREFIX + "boss").getString();
        String powerupLabel = Text.translatable(LABEL_KEY_PREFIX + "powerup").getString();
        int labelCol = labelWidth(tr);

        int bossY = y + fh;
        int powerupY = y + fh * 2;

        // Column start accumulated per cell: each column's width follows its
        // own digit count (two digits 20px / three digits 26px), so the "100"
        // cell never gets shoved by a previous column that was still two digits.
        int cx = x + labelCol;
        for (int i = 0; i < WINDOW; i++) {
            int round = start + i;
            if (round <= maxRound(map)) {
                // Round cap (AA=105, three maps=30, max_round authoritative):
                // cells past the cap are left blank—the ones-digit extrapolation
                // predicts beyond round 31, which the game itself no longer has.
                boolean isCurrent = round == currentRound;

                // Row 0: round number, always 6 columns.
                drawCell(context, tr, cx, y, round, isCurrent ? COLOR_CURRENT : COLOR_ROUND);

                // Row 1: boss rounds—read boss_rounds (map->difficulty->rounds);
                // when unidentified, fall back to normal (see RoundUtils).
                if (RoundUtils.isBossRound(map, round, difficulty)) {
                    drawCell(context, tr, cx, bossY, round, isCurrent ? COLOR_CURRENT : COLOR_BOSS);
                }

                // Row 2: powerup refresh rounds—the union of every type's patterns
                // (extrapolation included).
                if (isPowerupRound(map, round)) {
                    drawCell(context, tr, cx, powerupY, round, isCurrent ? COLOR_CURRENT : COLOR_POWERUP);
                }
            }
            // Over-cap cells still step forward: leaving a blank is "don't draw this
            // column," not "shove every later column forward."
            cx += columnWidth(tr, round);
        }

        context.drawTextWithShadow(tr, roundLabel, x, y, COLOR_LABEL);
        context.drawTextWithShadow(tr, bossLabel, x, bossY, COLOR_LABEL);
        context.drawTextWithShadow(tr, powerupLabel, x, powerupY, COLOR_LABEL);
    }

    /**
     * Draws one cell: {@code [}+round (padded to at least two digits)+{@code ]},
     * brackets always kept. 1–99 → {@code [07]}, 100–105 → {@code [100]};
     * the column width is measured by {@link #columnWidth} from the same text,
     * so a digit-count change neither spills into the next column nor clips
     * the brackets.
     */
    private void drawCell(DrawContext context, TextRenderer tr, int x, int y, int round, int color) {
        context.drawTextWithShadow(tr, cellText(round), x, y, color);
    }

    /**
     * Cell text: pad to at least two digits, three or more as-is, brackets
     * always around. <b>public</b>: the editor preview uses the same format
     * (renderer and preview live in different packages), and exposing it for
     * sharing prevents the "renderer changed, preview forgot" drift.
     */
    public static String cellText(int round) {
        return "[" + String.format("%02d", round) + "]";
    }

    /** Static powerup-round check: any of the map's pattern types
     *  (insta_kill/max_ammo/shopping_spree) hitting counts. */
    private static boolean isPowerupRound(MapId map, int round) {
        for (String typeKey : new String[]{"insta_kill", "max_ammo", "shopping_spree"}) {
            List<PowerupPattern> patterns = DataManager.get().getPowerupPatterns(map, typeKey);
            for (PowerupPattern pattern : patterns) {
                if (pattern.matchesRound(round)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A map's round cap (inclusive): reads the max_round field from
     * wave_times (AA=105 / DE·BB·Prison=30, falling back to row count when
     * missing), matching the game's true round count. When the table lacks
     * the map, returns {@code Integer.MAX_VALUE} (unlimited): the cell blank
     * is a "narrowing" display, and a missing datum must not erase rounds
     * that could have been drawn.
     */
    private static int maxRound(MapId map) {
        return DataManager.get().getMaxRound(map);
    }
}