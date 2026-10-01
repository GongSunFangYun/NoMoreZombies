package cn.gsfy.nmz.client.config.hud;

import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.DifficultyId;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.features.gamehud.AAAutoCommand;
import cn.gsfy.nmz.client.features.gamehud.CpsRenderer;
import cn.gsfy.nmz.client.features.gamehud.GlobalOverviewRenderer;
import cn.gsfy.nmz.client.features.gamehud.LightningRodQueue;
import cn.gsfy.nmz.client.features.gamehud.PowerupRenderer;
import cn.gsfy.nmz.client.features.gamehud.RollStatsRenderer;
import cn.gsfy.nmz.client.features.gamehud.SpawnTimeRenderer;
import cn.gsfy.nmz.client.features.gamehud.StatusEffectHudRenderer;
import cn.gsfy.nmz.client.features.gamehud.TeamStatsRenderer;
import cn.gsfy.nmz.client.features.gamehud.TimeHudRenderer;
import cn.gsfy.nmz.client.features.sidebar.ScoreboardHudRenderer;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;
import cn.gsfy.nmz.client.utils.AvatarUtils;
import cn.gsfy.nmz.mixin.client.accessor.StatusEffectSpriteManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.List;
import cn.gsfy.nmz.client.config.GlobalConfig;

/**
 * Editor previews for every HUD, plus the mock data behind them
 *
 * <p>Preview content is <b>each HUD's own</b> knowledge (what to draw, how
 * wide and tall, which colors), so it lives in this class: the editor
 * should only drag, scale, and save. {@link RegisterHUD} wires each HUD
 * description to its preview factory, and the editor consumes only the
 * registry
 *
 * <p><b>All eleven previews are fixed samples that never read live game
 * state</b>: a preview is a catalog of "what this HUD looks like", not a
 * mirror of "what it looks like right now". So this class contains no reads
 * like {@code TeamStats.getPlayers()} / {@code CpsTracker} /
 * {@code CheckSpawnTimes.get()} / {@code PowerupParser} collections /
 * {@code client.player}--opening the editor inside a game and from the main
 * menu shows the exact same shape
 *
 * <p><b>The two deliberate exceptions are in the resolvers, not the
 * previews</b>: {@code CpsRenderer.hudWidth} and
 * {@code StatusEffectHudRenderer.hudWidth} measure width from live readings
 * / live effects, but only the default right-edge anchor resolution uses
 * that (so triple-digit CPS or long effect names cannot spill off screen);
 * the editor box still goes through {@code previewWidth}. The split of
 * duties is in README section 3.10
 *
 * <p><b>Size invariant (README section 3.10)</b>: every preview takes its
 * width and height from the renderer's {@code hudWidth}/{@code hudHeight}
 * (the preview-measuring pair is named {@code previewWidth}/
 * {@code previewHeight}); this class <b>never computes sizes itself</b>--it
 * just calls the renderer's two functions and hands the results to
 * {@link HudPreview}. Shadowed text must count the {@code TEXT_SHADOW}
 * spill; the scoreboard, drawn with shadowless {@code drawText}, does not
 *
 * <p><b>Preview data always flows through translation keys and renderer
 * constants</b>: a Chinese client sees a Chinese preview, with colors, row
 * order, and wording sharing one source with the live HUD (when changing
 * wording or row order, both the Chinese and English sets must change
 * together)
 */
public final class HudSampleData {

    private HudSampleData() {
    }

    /** Team stats column padding (matches {@code TeamStatsRenderer.drawTable}) so the preview size matches the real HUD */
    private static final int PAD = 8;

    /**
     * Avatar slot left of the team stats name column (matches
     * {@code TeamStatsRenderer}'s {@code AVATAR_W}/
     * {@code NAME_OFFSET}) so the preview does not shift
     */
    private static final int AVATAR_W = 8;
    private static final int NAME_OFFSET = AVATAR_W + 2;

    /** Status effect icon size and in-row gap (matches {@code StatusEffectHudRenderer}) */
    private static final int ICON_SIZE = 9;
    private static final int ICON_GAP = 3;

    /** Translation lookup (follows the client language): preview wording shares one source with the real HUD instead of being hardcoded */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    // ----Mock data: team stats and scoreboard share one set, so the two previews tell one story----

    /** Preview player names--four fixed placeholders, never real IDs captured in game, so players do not mistake them for their own teammates */
    private static final String[] MOCK_PLAYER_NAMES = {"PlayerA", "PlayerB", "PlayerC", "PlayerD"};

    /** The preview's four states, index-aligned with {@code MOCK_STATUS_COLOR}--A in combat, B downed, C dead, D left */
    private static final TeamStats.Status[] MOCK_STATUS = {
            TeamStats.Status.IN_COMBAT, TeamStats.Status.DOWNED, TeamStats.Status.DEAD, TeamStats.Status.LEFT
    };

    /** Preview health: B/C/D all show 0--the mod has no "unknown health" state; only distant teammates render as {@code ?} */
    private static final int[] MOCK_HEALTH = {20, 0, 0, 0};

    /**
     * Preview text for the health column: a negative value is the "unknown
     * health" placeholder, matching {@code TeamStatsRenderer}'s live
     * behavior; the current mock table is all non-negative, so in practice
     * only the number branch runs
     *
     * @param health preview health value
     * @return the display text for that health value
     */
    private static String mockHealthText(int health) {
        return health < 0 ? "?" : String.valueOf(health);
    }

    /**
     * Health column colors: same thresholds as
     * {@code TeamStatsRenderer.healthColor}
     * (over 10 green / over 5 yellow / otherwise red)
     */
    private static final int[] MOCK_HEALTH_COLOR = {0x55FF55, 0xFF5555, 0xFF5555, 0xFF5555};
    /** Status column colors: tier-by-tier aligned with {@code TeamStatsRenderer.statusColor}, so preview and live HUD agree */
    private static final int[] MOCK_STATUS_COLOR = {0x55FF55, 0xFFFF55, 0xFF5555, 0xAA0000};
    /** Preview kills/downs/deaths columns--the four rows together play each state of the status machine once */
    private static final int[] MOCK_KILLS = {12, 9, 5, 3};
    private static final int[] MOCK_DOWNS = {0, 1, 2, 0};
    private static final int[] MOCK_DEATHS = {0, 0, 1, 2};
    /**
     * Gold: fills both the team stats gold column and PlayerA's value row
     * in the scoreboard preview. Plain integers without thousands
     * separators--same shape as the team stats preview, so the two previews
     * match (and the digits never shift with the locale)
     */
    private static final int[] MOCK_GOLD = {1200, 950, 700, 300};
    /** Gold color (orange): shared by the team stats gold column and PlayerA's value, so both read as the same kind of data at a glance */
    private static final int MOCK_GOLD_COLOR = 0xFFAA00;
    /** Player name color (gray): names share one gray in the sidebar, not one color per name */
    private static final int MOCK_NAME_COLOR = 0xAAAAAA;
    /** Preview title color (yellow): the vanilla sidebar title is exactly this yellow; the preview pins it explicitly so it does not drift with other code */
    private static final int MOCK_TITLE_COLOR = 0xFFFF55;
    /**
     * Whether the preview title is bold: the vanilla title is {@code §l}
     * bold and the preview follows--width is measured ({@code getWidth})
     * with bold applied too, so the box is never pushed out by the bolder
     * text
     */
    private static final boolean MOCK_TITLE_BOLD = true;

    /** Preview status text: via lang keys, switching with the client language (same keys as the real team stats HUD) */
    private static String mockStatusLabel(int row) {
        String key = switch (MOCK_STATUS[clampMockRow(row)]) {
            case IN_COMBAT -> "nomorezombies.teamstats.status.combat";
            case DOWNED -> "nomorezombies.teamstats.status.downed";
            case DEAD -> "nomorezombies.teamstats.status.dead";
            case LEFT -> "nomorezombies.teamstats.status.left";
        };
        return Text.translatable(key).getString();
    }

    /**
     * Row clamp for the mock data: the four arrays are equal length and any
     * out-of-range row is clamped to the last row, so callers need no own
     * bounds checks. (Deliberately not {@code Math.clamp}: it throws when
     * the upper bound is below the lower one, and here the upper bound comes
     * from the array length--an empty array would make it -1; lifting the
     * lower bound first and then clamping is the more forgiving order)
     */
    private static int clampMockRow(int row) {
        return Math.max(0, Math.min(MOCK_PLAYER_NAMES.length - 1, row));
    }

    /**
     * Preview player name, shared by the team stats and scoreboard
     * previews--fixed PlayerA through PlayerD, never real IDs from game
     * logs, so players do not mistake them for their own teammates
     *
     * @param row row number 0-3; out-of-range values clamp to the last row
     * @return the placeholder player name for that row
     */
    public static String mockPlayerName(int row) {
        return MOCK_PLAYER_NAMES[clampMockRow(row)];
    }

    /**
     * Value column of the sidebar preview's player rows--the <b>final
     * shape</b>: only PlayerA shows gold, the other three rows show status
     * words, one color per row, so all four team states are visible at a
     * glance:
     * <pre>
     *   PlayerA: 1200    orange (gold)
     *   PlayerB: DOWNED  yellow (downed)
     *   PlayerC: DIED    red (dead)
     *   PlayerD: QUIT    dark red (left)
     * </pre>
     *
     * <p>Why not just "gold + QUIT": the status words are what back
     * {@code SidebarEnhancer}'s player-row heuristic
     * ({@code isPlayerRow} accepts digits or words like {@code quit}/
     * {@code downed}/{@code dead}), and three status rows cover every
     * spelling that heuristic can match; one gold row is enough to show
     * the numeric column's shape
     *
     * <p>Chinese clients get the localized spellings of the same words
     * (downed / dead / left)--the word list is bilingual by design and the
     * preview must follow, otherwise Chinese players would see an English
     * preview that never occurs in a real game
     *
     * @param row row number 0-3; out-of-range values clamp to the last row
     * @return the text that row shows in the sidebar
     */
    public static String mockSidebarStatus(int row) {
        int index = clampMockRow(row);
        return switch (MOCK_STATUS[index]) {
            case IN_COMBAT -> String.valueOf(MOCK_GOLD[index]);
            case DOWNED -> GlobalConfig.Hud.isChineseClient() ? "倒地" : "DOWNED";
            case DEAD -> GlobalConfig.Hud.isChineseClient() ? "死亡" : "DIED";
            case LEFT -> GlobalConfig.Hud.isChineseClient() ? "退出" : "QUIT";
        };
    }

    /**
     * Value colors for the sidebar preview's player rows (one per shape of
     * {@link #mockSidebarStatus(int)}, same order): A gold orange, B downed
     * yellow, C dead red, D left dark red--the same palette as the team
     * stats HUD's status colors, so both previews tell one story
     *
     * @param row row number 0-3; out-of-range values clamp to the last row
     * @return the ARGB color for that row's value
     */
    public static int mockSidebarValueColor(int row) {
        return switch (MOCK_STATUS[clampMockRow(row)]) {
            case IN_COMBAT -> MOCK_GOLD_COLOR;
            case DOWNED -> MOCK_STATUS_COLOR[1];
            case DEAD -> MOCK_STATUS_COLOR[2];
            case LEFT -> MOCK_STATUS_COLOR[3];
        };
    }

    /** Sidebar preview player name color (gray): one gray for all names, not split by self/teammate */
    public static int mockPlayerNameColor() {
        return MOCK_NAME_COLOR;
    }

    /** Number of preview player rows ({@code ScoreboardHudRenderer} uses it to tell which rows are player rows) */
    public static int mockPlayerNameCount() {
        return MOCK_PLAYER_NAMES.length;
    }

    /** Preview title color (yellow): the vanilla title uses this same yellow; the preview just pins it explicitly */
    public static int mockTitleColor() {
        return MOCK_TITLE_COLOR;
    }

    /** Whether the preview title is bold: the vanilla scoreboard title is bold, so the preview is too, and its width is measured bold as well */
    public static boolean mockTitleBold() {
        return MOCK_TITLE_BOLD;
    }

    // ----Preview factories: each takes its size from the renderer and only decides what to draw----

    /**
     * Wave table preview: 6 rows (AA has W6, keeping the hitbox from being
     * pushed out) plus the arrow column; width and height match the
     * renderer. Row text comes from the renderer's {@code PREVIEW_LINES}
     * (measuring and drawing share one source, so the box cannot misalign
     * with the text)
     */
    public static HudPreview spawnTime() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int arrowW = tr.getWidth(SpawnTimeRenderer.PREVIEW_ARROW);
        String[] lines = SpawnTimeRenderer.PREVIEW_LINES;
        int[] colors = {0x5A5A5A, 0x5A5A5A, 0xFFFF00, 0x808080, 0x808080, 0x808080};
        final int fh = tr.fontHeight;
        // Width and height come from the renderer's hudWidth/hudHeight: the
        // default right-edge resolution uses the same function, so the box
        // measured in the editor must be exactly the in-game footprint
        // (shadow spill included)
        final int w = SpawnTimeRenderer.hudWidth(tr);
        final int h = SpawnTimeRenderer.hudHeight(tr);
        return new HudPreview(w, h, (ctx, x, y) -> {
            ctx.drawTextWithShadow(tr, SpawnTimeRenderer.PREVIEW_ARROW, x, y + fh * 2, 0xCC00CC);
            for (int i = 0; i < lines.length; i++)
                ctx.drawTextWithShadow(tr, lines[i], x + arrowW, y + fh * i, colors[i]);
        });
    }

    /**
     * Powerup HUD preview: draws the <b>fixed sample</b> provided by
     * {@code PowerupRenderer.sampleRows}, independent of what powerups are
     * actually held--the editor always shows the same final shape
     *
     * <p>All eleven previews are fixed samples--a preview catalogs "what
     * this HUD looks like", it does not mirror "what it looks like right
     * now"
     *
     * <p>Width and height share one source with the row table
     * ({@code rowsWidth}/{@code rowsHeight}), so the box always equals what
     * that row table really paints: with only short statuses the box stays
     * narrow (no wide blank area); when a powerup activates and the status
     * string grows, that row joins the max and the countdown is never
     * clipped by the box. Names start at x=0, so the widget's left edge is
     * the name's left edge (no indent)
     */
    public static HudPreview powerup() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final List<PowerupRenderer.Row> rows = PowerupRenderer.sampleRows(tr);
        final int nameCol = PowerupRenderer.nameColumnWidth(tr);
        final int fh = tr.fontHeight;
        return new HudPreview(PowerupRenderer.rowsWidth(tr, rows), PowerupRenderer.rowsHeight(tr, rows),
                (ctx, x, y) -> {
            for (int i = 0; i < rows.size(); i++) {
                PowerupRenderer.Row row = rows.get(i);
                int rowY = y + fh * i;
                String name = row.type().getColorCode() + powerupLabel(row.type());
                ctx.drawTextWithShadow(tr, name, x, rowY, 0xFFFFFF);
                int cx = x + nameCol;
                for (PowerupRenderer.Segment segment : row.segments()) {
                    ctx.drawTextWithShadow(tr, segment.text(), cx, rowY, segment.color());
                    cx += tr.getWidth(segment.text());
                }
            }
        });
    }

    /** Powerup type name (via lang key, Chinese/English follows the client language)--same key table as the renderer's {@code powerupName} */
    private static String powerupLabel(PowerupParser.PowerupType type) {
        return Text.translatable(PowerupParser.keyFor(type)).getString();
    }

    /** Team stats preview: a gold header row plus 4 player rows (avatar + name + six columns); column widths match the real renderer */
    public static HudPreview teamStats() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String[] headers = new String[]{
                trans("nomorezombies.teamstats.header.hp"),
                trans("nomorezombies.teamstats.header.status"),
                trans("nomorezombies.teamstats.header.kills"),
                trans("nomorezombies.teamstats.header.downs"),
                trans("nomorezombies.teamstats.header.deaths"),
                trans("nomorezombies.teamstats.header.gold")
        };
        int count = MOCK_PLAYER_NAMES.length;

        int nameCol = 0;
        for (String n : MOCK_PLAYER_NAMES) nameCol = Math.max(nameCol, tr.getWidth(n));
        String[][] values   = new String[count][headers.length];
        int[][]    colors   = new int[count][headers.length];
        int[]      colWidth = new int[headers.length];
        for (int r = 0; r < count; r++) {
            values[r][0] = mockHealthText(MOCK_HEALTH[r]);
            colors[r][0] = MOCK_HEALTH_COLOR[r];
            values[r][1] = mockStatusLabel(r);
            colors[r][1] = MOCK_STATUS_COLOR[r];
            values[r][2] = String.valueOf(MOCK_KILLS[r]);   colors[r][2] = 0xFFFFFF;
            values[r][3] = String.valueOf(MOCK_DOWNS[r]);   colors[r][3] = 0xFFFFFF;
            values[r][4] = String.valueOf(MOCK_DEATHS[r]);  colors[r][4] = 0xFFFFFF;
            values[r][5] = String.valueOf(MOCK_GOLD[r]);    colors[r][5] = MOCK_GOLD_COLOR;
            for (int i = 0; i < headers.length; i++) {
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(headers[i]));
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(values[r][i]));
            }
        }
        int[] colX = new int[headers.length];
        int cx2 = NAME_OFFSET + nameCol + PAD;
        // No trailing gap after the last column--character-for-character the same
        // shape as TeamStatsRenderer.tableWidth/drawTable. That trailing PAD
        // does not affect column origins (cx2 is unused after the loop), but
        // keeping all three sites identical is what prevents drift
        for (int i = 0; i < headers.length; i++) {
            colX[i] = cx2;
            cx2 += colWidth[i];
            if (i != headers.length - 1) {
                cx2 += PAD;
            }
        }
        // Width and height come from the renderer (TeamStatsRenderer.tableWidth /
        // tableHeight): that one function serves both the live layout and the
        // editor box, and a shared function is the only way to avoid "a box a
        // ring smaller than its content"
        final int totalW = TeamStatsRenderer.tableWidth(tr, Arrays.asList(MOCK_PLAYER_NAMES), values);
        final int totalH = TeamStatsRenderer.tableHeight(tr, count);
        return new HudPreview(totalW, totalH, (ctx, x, y) -> {
            for (int i = 0; i < headers.length; i++)
                ctx.drawTextWithShadow(tr, headers[i], x + colX[i], y, 0xFFFF55);
            int yy = y + tr.fontHeight + 2;
            for (int r = 0; r < count; r++) {
                // Cropped avatar before the name: the sample uses the local
                // default_avatar.png, so previews work offline
                AvatarUtils.drawDefaultHead(ctx, x, yy + (tr.fontHeight - AVATAR_W) / 2, AVATAR_W);
                // Names in one uniform gray--same color as the scoreboard preview's
                // player names, so both previews group names the same way
                ctx.drawTextWithShadow(tr, MOCK_PLAYER_NAMES[r], x + NAME_OFFSET, yy, MOCK_NAME_COLOR);
                for (int i = 0; i < headers.length; i++)
                    ctx.drawTextWithShadow(tr, values[r][i], x + colX[i], yy, colors[r][i]);
                yy += tr.fontHeight + 1;
            }
        });
    }

    /** Time HUD preview: two rows of second-precision H:MM:SS; width and height match the renderer / the default right-edge resolution */
    public static HudPreview gameTime() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String gameLabel  = Text.translatable("nomorezombies.timehud.game").getString();
        String roundLabel = Text.translatable("nomorezombies.timehud.round").getString();
        final int fh = tr.fontHeight;
        // The preview matches the real render's second-precision H:MM:SS with no
        // millisecond digits: the scoreboard only has whole seconds and arrives
        // delayed, so decimals would jitter
        final String gameSample = "00:12:34";
        final String roundSample = "00:45:12";
        return new HudPreview(TimeHudRenderer.hudWidth(tr), TimeHudRenderer.hudHeight(tr), (ctx, x, y) -> {
            ctx.drawTextWithShadow(tr, gameLabel,   x, y,       0xFFAA00);
            ctx.drawTextWithShadow(tr, gameSample,  x + tr.getWidth(gameLabel),  y,      0xFFFFFF);
            ctx.drawTextWithShadow(tr, roundLabel,  x, y + fh,  0xFFAA00);
            ctx.drawTextWithShadow(tr, roundSample, x + tr.getWidth(roundLabel), y + fh, 0xFFFFFF);
        });
    }

    /**
     * Lightning rod queue preview: four 26x34 slot tiles (2 cooling blue +
     * 2 ready green), width 4x26 + 3x3 = 113px. Width and height come from
     * the renderer's hudWidth/hudHeight: the default centered resolution
     * uses the same function
     */
    public static HudPreview lrQueue() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final int tileW = 26, tileH = 34, gap = 3;
        ItemStack cooldownIcon = new ItemStack(Items.GRAY_DYE);
        ItemStack readyIcon = new ItemStack(Items.BLAZE_ROD);
        return new HudPreview(LightningRodQueue.hudWidth(), LightningRodQueue.hudHeight(), (ctx, x, y) -> {
            for (int i = 0; i < 4; i++) {
                int tx = x + i * (tileW + gap);
                boolean cooling = i < 2;
                int border = cooling ? 0xFF41A5FF : 0xFF46DC78;
                int textColor = cooling ? 0xFFEBF5FF : 0xFF64FF91;
                String status = cooling ? (i == 0 ? "20" : "12") : trans("nomorezombies.lrqueue.ready");
                ctx.fill(tx, y, tx + tileW, y + tileH, 0xBE0D1117);
                ctx.fill(tx, y, tx + tileW, y + 1, border);
                ctx.fill(tx, y + tileH - 1, tx + tileW, y + tileH, border);
                ctx.fill(tx, y, tx + 1, y + tileH, border);
                ctx.fill(tx + tileW - 1, y, tx + tileW, y + tileH, border);
                ctx.drawItem(cooling ? cooldownIcon : readyIcon, tx + (tileW - 16) / 2, y + 2);
                if (cooling) {
                    ctx.fill(tx + 4, y + 2, tx + tileW - 4, y + 20, 0x9B05080D);
                }
                ctx.drawTextWithShadow(tr, Integer.toString(i + 1), tx + 2, y + 2, 0xFFAFBECD);
                ctx.drawTextWithShadow(tr, status, tx + (tileW - tr.getWidth(status)) / 2, y + 21, textColor);

                // Bottom progress bar, same as LightningRodQueue.drawSlot: proportional
                // while cooling, full when ready
                float progress = cooling ? (i == 0 ? 1.0f : 0.6f) : 1.0f;
                int progressColor = cooling ? 0xFF37B4FF : 0xFF46DC78;
                int progressWidth = Math.round((tileW - 2) * progress);
                ctx.fill(tx + 1, y + tileH - 3, tx + 1 + progressWidth, y + tileH - 1, progressColor);
            }
        });
    }

    /**
     * AA auto command preview: info rows plus point rows (points wrap by
     * the renderer's wrapping rules, at most 3 rows); width and height
     * match the renderer (measuring and drawing both call
     * {@code AAAutoCommand.wrapPointSegments})
     */
    public static HudPreview aaAutoCommand() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String yes = trans("nomorezombies.aaautocommand.yes");
        String no = trans("nomorezombies.aaautocommand.no");
        String[] labels = AAAutoCommand.hudLabels();
        // Values only reach row 4: row 5 (recommended points) draws via
        // pointSegments, not via values
        String[] values = {"r105", yes, no, "III"};
        int[] colors = {0xFFFFFF, 0xFF5555, 0x55FF55, 0xFFFF55};

        // Point rows are colored per segment, kept in sync with the real HUD's
        // drawPointsLine/buildPointsText
        final List<List<String[]>> pointRows = AAAutoCommand.previewPointRows(tr);
        final int INFO_ROWS = 4;

        final int fh = tr.fontHeight;
        final int totalW = AAAutoCommand.previewWidth(tr);
        final int totalH = AAAutoCommand.previewHeight(tr);
        return new HudPreview(totalW, totalH, (ctx, x, y) -> {
            for (int i = 0; i < INFO_ROWS; i++) {
                int cx = x + tr.getWidth(labels[i]);
                ctx.drawTextWithShadow(tr, labels[i], x, y + fh * i, 0xFFAA00);
                ctx.drawTextWithShadow(tr, values[i], cx, y + fh * i, colors[i]);
            }
            // Point rows: label plus the wrapped segments, advanced the same way
            // as drawPointsLine
            int labelW = tr.getWidth(labels[INFO_ROWS]);
            ctx.drawTextWithShadow(tr, labels[INFO_ROWS], x, y + fh * INFO_ROWS, 0xFFAA00);
            for (int r = 0; r < pointRows.size(); r++) {
                int rowY = y + fh * (INFO_ROWS + r);
                int cx = x + labelW;
                List<String[]> row = pointRows.get(r);
                for (int s = 0; s < row.size(); s++) {
                    String[] segment = row.get(s);
                    // "#N" in blue 0x5555FF (= §9 = Formatting.BLUE) + point name in white 0xFFFFFF
                    String marker = segment[0] + " ";
                    ctx.drawTextWithShadow(tr, marker, cx, rowY, 0x5555FF);
                    cx += tr.getWidth(marker);
                    ctx.drawTextWithShadow(tr, segment[1], cx, rowY, 0xFFFFFF);
                    cx += tr.getWidth(segment[1]);
                    if (s != row.size() - 1) {
                        ctx.drawTextWithShadow(tr, " ", cx, rowY, 0xFFFFFF);
                        cx += tr.getWidth(" ");
                    }
                }
            }
        });
    }

    /** CPS preview: two rows, left and right click, each "label value unit"; width and height match the renderer (the right-edge resolution has its own live-measuring variant) */
    public static HudPreview cps() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String left = trans("nomorezombies.cps.left");
        String right = trans("nomorezombies.cps.right");
        String unit = trans("nomorezombies.cps.unit");
        final int h = CpsRenderer.hudHeight(tr);
        return new HudPreview(CpsRenderer.previewWidth(tr), h, (ctx, x, y) -> {
            int cx = x;
            ctx.drawTextWithShadow(tr, left, cx, y, 0xFF5555);
            cx += tr.getWidth(left);
            ctx.drawTextWithShadow(tr, " 12", cx, y, 0xFFFFFF);
            cx += tr.getWidth(" 12");
            ctx.drawTextWithShadow(tr, " " + unit, cx, y, 0xAAAAAA);

            cx = x;
            ctx.drawTextWithShadow(tr, right, cx, y + tr.fontHeight, 0x55FF55);
            cx += tr.getWidth(right);
            ctx.drawTextWithShadow(tr, " 8", cx, y + tr.fontHeight, 0xFFFFFF);
            cx += tr.getWidth(" 8");
            ctx.drawTextWithShadow(tr, " " + unit, cx, y + tr.fontHeight, 0xAAAAAA);
        });
    }

    /**
     * Global overview preview: a 3-row, 6-column scrolling table (current
     * round plus the next 5 rounds: round / boss / powerup); width and
     * height match the renderer's hudWidth/columnWidth
     */
    public static HudPreview globalOverview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String roundLabel = trans("nomorezombies.globaloverview.round");
        String bossLabel = trans("nomorezombies.globaloverview.boss");
        String powerupLabel = trans("nomorezombies.globaloverview.powerup");
        final int fh = tr.fontHeight;
        // The preview uses real Dead End Normal data (the boss row is queried
        // live from the boss_rounds table, refreshing on resource reload);
        // scroll window [05]..[10] (current round 5, always column 0)--
        // Normal's boss row has only [10]; powerup rows [06][08][09]
        // (insta_kill + max_ammo union, the preview keeps those literals)
        final int currentCol = 0;
        final int startRound = 5;
        final int labelCol = Math.max(tr.getWidth(roundLabel),
                Math.max(tr.getWidth(bossLabel), tr.getWidth(powerupLabel)));
        final int[] bossRounds = DataManager.get().getBossRounds(MapId.DEAD_END, DifficultyId.NORMAL);
        final int[] powerupRounds = {6, 8, 9};
        // Column widths are measured per cell and accumulated by position
        // (2-digit 20px / 3-digit 26px) through the same function as the live
        // render: even though the preview window is 2-digit, the size
        // convention must share one source, otherwise the hitbox runs narrow
        // at 100+ rounds
        final int[] colX = new int[6];
        int acc = labelCol;
        for (int i = 0; i < 6; i++) {
            colX[i] = acc;
            acc += GlobalOverviewRenderer.columnWidth(tr, startRound + i);
        }
        // Width and height come from the renderer: the default right-edge
        // resolution uses the same source (shadow spill included), so acc-1
        // is not recomputed here
        final int w = GlobalOverviewRenderer.previewWidth(tr);
        final int h = GlobalOverviewRenderer.previewHeight(tr);
        return new HudPreview(w, h, (ctx, x, y) -> {
            ctx.drawTextWithShadow(tr, roundLabel, x, y, 0xFFAA00);
            ctx.drawTextWithShadow(tr, bossLabel, x, y + fh, 0xFFAA00);
            ctx.drawTextWithShadow(tr, powerupLabel, x, y + fh * 2, 0xFFAA00);
            for (int i = 0; i < 6; i++) {
                int round = startRound + i;
                int cx = x + colX[i];
                boolean current = i == currentCol;
                // Cell text via the renderer's cellText: preview and live HUD share
                // one format, so they cannot drift
                ctx.drawTextWithShadow(tr, GlobalOverviewRenderer.cellText(round),
                        cx, y, current ? 0x55FF55 : 0xFFFFFF);
                if (contains(bossRounds, round)) {
                    ctx.drawTextWithShadow(tr, GlobalOverviewRenderer.cellText(round),
                            cx, y + fh, current ? 0x55FF55 : 0xFF5555);
                }
                if (contains(powerupRounds, round)) {
                    ctx.drawTextWithShadow(tr, GlobalOverviewRenderer.cellText(round),
                            cx, y + fh * 2, current ? 0x55FF55 : 0x5555FF);
                }
            }
        });
    }

    /** Linear search: whether a round should light up a boss/powerup cell in the preview (the arrays are tiny, binary search is unnecessary) */
    private static boolean contains(int[] array, int value) {
        for (int v : array) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** Status effect preview: three rounds of "icon + name row + countdown row"; icons are real sprites from the mob_effects atlas by effect id */
    public static HudPreview statusEffects() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final int fh = tr.fontHeight;
        // Width and height are measured from the preview itself
        // (previewWidth/previewHeight), the same convention as the three
        // rounds actually drawn below, so the box wraps exactly them; the
        // live render's default right-edge anchoring goes through hudWidth
        // (live measuring) instead--each path to its own job
        final int w = StatusEffectHudRenderer.previewWidth(tr);
        final int h = StatusEffectHudRenderer.previewHeight(tr);
        final String[][] rows = StatusEffectHudRenderer.PREVIEW_ROWS;
        // Preview icons: three real sprites from the vanilla mob_effects atlas,
        // drawn the same way as the live render (drawSpriteStretched)--
        // placeholder color blocks look wrong next to the real HUD.
        // getSprite(Identifier) is a protected method of SpriteAtlasHolder and
        // the manager's public overload only takes RegistryEntry, so an
        // accessor exposes the protected method and fetches directly by
        // effect id (no registry instance needed). Icon order must match the
        // PREVIEW_ROWS row order one to one (mining fatigue / regeneration /
        // speed); a mismatch draws each round with the neighbor effect's icon
        Identifier[] iconIds = {
                Identifier.ofVanilla("mining_fatigue"),
                Identifier.ofVanilla("regeneration"),
                Identifier.ofVanilla("speed")
        };
        // Rows per effect and the icon offset share one source with the live
        // render: with two separate copies of the numbers, changing the
        // renderer's row count would leave the preview box one layer off the
        // actual strokes
        final int rowsPerEffect = StatusEffectHudRenderer.rowsPerEffect();
        return new HudPreview(w, h, (ctx, x, y) -> {
            StatusEffectSpriteManagerAccessor manager =
                    (StatusEffectSpriteManagerAccessor) MinecraftClient.getInstance().getStatusEffectSpriteManager();
            for (int i = 0; i < rows.length; i++) {
                int cy = y + fh * i * rowsPerEffect;
                // Icon centered vertically in its two-row block: the same formula as
                // the live render (sharing rowsPerEffect), so the preview never
                // shifts
                Sprite sprite = manager.nmz$getSprite(iconIds[i]);
                ctx.drawSpriteStretched(RenderLayer::getGuiTextured, sprite,
                        x, cy + (rowsPerEffect * fh - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);
                String name = StatusEffectHudRenderer.previewName(i);
                String level = rows[i][1];
                String title = level.isEmpty() ? name : name + " " + level;
                int tx = x + ICON_SIZE + ICON_GAP;
                // Name row white, countdown on its own line in light blue: matches
                // the live render's layout and colors
                ctx.drawTextWithShadow(tr, title, tx, cy, 0xFFFFFF);
                ctx.drawTextWithShadow(tr, StatusEffectHudRenderer.previewTime(i), tx, cy + fh, 0x99CCFF);
            }
        });
    }

    /**
     * Scoreboard preview--<b>one face</b>: the tooltip and the canvas show
     * the same shape
     *
     * <p>The shape depends only on whether the <b>team stats HUD</b> and the
     * <b>in-game time HUD</b> are actually on duty on the canvas
     * ({@code workspaceActive} = on the canvas <b>and</b> enabled). Four
     * combinations, four shapes:
     * <ul>
     *  <li>neither: the full 15 rows (time row carries
     *  {@code Time: 0:29 Kills: 12}, all four player rows present);</li>
     *  <li>time only: Kills kept (time row reduces to {@code Kills: 12});</li>
     *  <li>team stats only: Time kept (time row reduces to
     *  {@code Time: 0:29}, player and blank rows absorbed);</li>
     *  <li>both: the minimal 6 rows (time row removed entirely)</li>
     * </ul>
     * Whether the scoreboard HUD itself is enabled <b>does not touch</b> the
     * row set--it decides "draw the sidebar or not", not "which rows to
     * draw"
     *
     * <p><b>Row content is always the fixed offline sample</b>
     * ({@link ScoreboardHudRenderer#sampleView}), independent of whether you
     * are in a game or who your teammates are: a preview catalogs "what
     * this HUD looks like", not "what my sidebar looks like right now"
     *
     * <p><b>In-game rendering is unaffected by this method</b>: the two live
     * switches are
     * {@link GlobalConfig.Hud#teamStatsOn()}/{@link GlobalConfig.Hud#gameTimeOn()}
     * (master switch {@code &&} placed {@code &&} visible), unrelated to
     * the editor workspace's unsaved checkboxes
     *
     * @param ctx preview context (supplies the other two HUDs' on-canvas state)
     * @return the scoreboard preview
     */
    public static HudPreview scoreboard(HudEntry.PreviewContext ctx) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        // Full version (in the tooltip): both switches treated as off, so neither
        // filter applies. The shape depends only on whether the other two HUDs
        // are on duty on the canvas--not on the scoreboard's own switch, nor on
        // the master switch: the canvas is a self-consistent picture of what I
        // placed, not of what my config has on
        ScoreboardHudRenderer.View view = ScoreboardHudRenderer.sampleView(
                ctx.workspaceActive("teamstats"),
                ctx.workspaceActive("gametime"));
        final Text title = view.title();
        final List<ScoreboardHudRenderer.Row> rows = view.rows();
        final int w = ScoreboardHudRenderer.boxWidth(tr, title, rows);
        final int h = ScoreboardHudRenderer.boxHeight(rows);
        // Always goes through the preview draw path: title yellow + bold, names
        // gray, values colored by status. With fixed offline content there is
        // no "apply preview colors or not" question left to answer
        return new HudPreview(w, h,
                (ctx2, x, y) -> ScoreboardHudRenderer.drawPreview(ctx2, tr, title, rows, x, y));
    }

    /**
     * Roll stats preview--a fixed two-row sample (lightning gun 2 / gold
     * miner 1, 3 total); item names translate with the client language and
     * the icons are stand-in items
     *
     * <p>Content and size both come from the renderer's
     * {@link RollStatsRenderer#sampleLayout}; this class computes no
     * geometry: width must be measured by the same function as in game,
     * otherwise "the box fits in the editor but drops a column in game".
     * The only way it differs from the live HUD is that this preview
     * <b>does not read</b> {@code RollStats} counts, so the main menu and
     * in-game show the same shape
     *
     * @return the roll stats preview
     */
    public static HudPreview rollStats() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final RollStatsRenderer.Layout layout = RollStatsRenderer.sampleLayout(tr);
        return new HudPreview(RollStatsRenderer.hudWidth(tr, layout), RollStatsRenderer.hudHeight(tr, layout),
                (ctx, x, y) -> RollStatsRenderer.draw(ctx, tr, x, y, layout));
    }
}
