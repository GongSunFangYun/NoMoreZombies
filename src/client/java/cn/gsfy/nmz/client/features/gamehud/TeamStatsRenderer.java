package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.utils.AvatarUtils;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Map;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Team-stats HUD (movable / zoomable)—pins the squad's status as a table on
 * screen: one row per player (avatar + name left-aligned), then six aligned
 * columns on the right (health / status / kills / downs / deaths / gold),
 * up to four players.
 *
 * <p>Data is read from {@link TeamStats}; this class only lays out. Avatars
 * are drawn by {@link AvatarUtils}; the values area starts to the right of
 * the name column. Health is colored by threshold (high green / low yellow /
 * dying red); a data set restored on rejoin is shown directly, with no
 * placeholder state.
 *
 * <p>Layout details: column widths are measured per column from this
 * frame's real values (tableLayout), never guessed from the sample—when a
 * player name or gold count grows, the frame grows with it, and a
 * four-digit gold count never sticks out of the table. Unknown health
 * (negative sentinel) shows "?" rather than 0: 0 is "genuinely empty
 * health," "?" is "could not read it," and the two must not be conflated.
 * Status word and health each get their own color; hover does nothing, pure
 * display.
 */
public class TeamStatsRenderer extends TotalHUDRenderer {

    private static final int PAD = 8; // Column gap (px): one between the name and value columns and between each pair of value columns; none after the last.
    /** The avatar area's width on the left of the name column: 8px avatar + 2px gap. */
    private static final int AVATAR_W = 8;
    private static final int NAME_OFFSET = AVATAR_W + 2; // Name column offset from the component's left edge (px): equals the avatar area's width, so the name clears the avatar.

    /**
     * Component pixel width: avatar area + name column + six value columns
     * (each taking the larger of header and column data) + the last
     * column's shadow overhang.
     *
     * <p>The accounting matches {@link #drawTable} exactly—the column gap is
     * added only <b>between columns</b>, never after the last one, so the
     * frame's right edge is the content's right edge.
     *
     * <p>Used for the <b>editor sample</b> only (passing the sample's four
     * names and sample values); the in-game anchor positioning uses the
     * overload named {@code tableWidth(TextRenderer, Collection, String[][])},
     * measured from this game's real names and values.
     *
     * @param tr the text renderer
     * @param names the names to measure (preview passes the sample's four
     *   names; rendering passes this game's roster)
     * @param values the column-aligned cell text (may be empty, in which
     *   case column widths are measured from headers alone)
     * @return table pixel width (shadow overhang included)
     */
    public static int tableWidth(net.minecraft.client.font.TextRenderer tr,
                                 java.util.Collection<String> names, String[][] values) {
        int nameCol = 0;
        for (String name : names) {
            nameCol = Math.max(nameCol, tr.getWidth(name));
        }
        int width = NAME_OFFSET + nameCol + PAD;
        for (int i = 0; i < HEADER_KEYS.length; i++) {
            int colWidth = tr.getWidth(Text.translatable(HEADER_KEYS[i]).getString());
            for (String[] row : values) {
                if (i < row.length) {
                    colWidth = Math.max(colWidth, tr.getWidth(row[i]));
                }
            }
            width += colWidth;
            if (i != HEADER_KEYS.length - 1) {
                width += PAD;
            }
        }
        return width + TEXT_SHADOW;
    }

    /** Component pixel height: header row (font height + 2) + each row (font height + 1) + the last row's shadow overhang. */
    public static int tableHeight(net.minecraft.client.font.TextRenderer tr, int rowCount) {
        return tr.fontHeight + 2 + rowCount * (tr.fontHeight + 1) + TEXT_SHADOW;
    }

    /** Header lang keys (order = column order)—measuring and drawing share this one table, so neither side writes its own column order. */
    private static final String[] HEADER_KEYS = {
            "nomorezombies.teamstats.header.hp",
            "nomorezombies.teamstats.header.status",
            "nomorezombies.teamstats.header.kills",
            "nomorezombies.teamstats.header.downs",
            "nomorezombies.teamstats.header.deaths",
            "nomorezombies.teamstats.header.gold"
    };

    /** The six column headers—via this key table, Chinese/English switching with the client language. */
    private static String[] headerLabels() {
        String[] labels = new String[HEADER_KEYS.length];
        for (int i = 0; i < HEADER_KEYS.length; i++) {
            labels[i] = Text.translatable(HEADER_KEYS[i]).getString();
        }
        return labels;
    }

    /**
     * Draws the team stats table: gold header, name + avatar left-aligned,
     * six columns aligned, health colored by threshold, up to four players.
     * Gate = {@link GlobalConfig.Hud#teamStatsOn()} (master switch + placed +
     * this element's visibility).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.teamStatsOn()) {
            return;
        }
        Map<String, TeamStats.PlayerStats> players = TeamStats.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_TEAM_STATS.getDoubleValue();
        // Content width must be measured from the real roster: the table's
        // column widths come from the current player count and value digit
        // count, and measuring with sample names would push "right-hug" off
        // screen on a four-digit-gold game (drawTable's layout accounting was
        // extracted into tableLayout; the two are one source).
        TableLayout layout = tableLayout(textRenderer, players);
        int hudW = visibleSize(tableWidth(layout), scale);
        int hudH = visibleSize(tableHeight(textRenderer, layout.displayCount()), scale);
        int x = anchorPixels(GlobalConfig.getXTeamStats(), screenWidth, hudW, 0);
        int y = anchorPixels(GlobalConfig.getYTeamStats(), screenHeight, hudH, 0);
        drawScaled(context, x, y, scale, () -> drawTable(context, x, y, layout));
    }

    /**
     * One frame's table layout: column widths, cell text and colors, the
     * names that participate in display.
     *
     * <p>Extracted so that <b>measuring and drawing share one copy</b>:
     * anchor positioning needs "how much this frame actually occupies"
     * while drawing needs the same column widths; computing them twice
     * inevitably diverges by 1px or a whole column on edge cases like
     * "four-digit gold" or "long Chinese name".
     *
     * @param headers the six header texts (this frame's language)
     * @param playerNames the names participating in display (at most four)
     * @param valueRows each row's cell text per column
     * @param valueColors the color of each cell, matching {@code valueRows} cell-for-cell
     * @param colWidth each of the six columns' pixel width (the larger of header and column data)
     * @param nameCol the name column's pixel width
     * @param displayCount the actually displayed row count (≤4)
     */
    private record TableLayout(String[] headers, String[] playerNames, String[][] valueRows,
                               int[][] valueColors, int[] colWidth, int nameCol, int displayCount) {
    }

    /**
     * Measures this frame's layout (column widths / cells / row count) with
     * {@link #drawTable}'s algorithm.
     *
     * @param tr the text renderer
     * @param players this game's squad
     * @return the layout (at most four rows)
     */
    private TableLayout tableLayout(TextRenderer tr, Map<String, TeamStats.PlayerStats> players) {
        String[] headers = headerLabels();
        int nameCol = 0;
        for (String name : players.keySet()) {
            nameCol = Math.max(nameCol, tr.getWidth(name));
        }
        int displayCount = Math.min(players.size(), 4);
        String[][] valueRows = new String[displayCount][headers.length];
        int[][] valueColors = new int[displayCount][headers.length];
        String[] playerNames = new String[displayCount];
        int[] colWidth = new int[headers.length];
        int row = 0;
        for (Map.Entry<String, TeamStats.PlayerStats> e : players.entrySet()) {
            if (row >= 4) {
                break;
            }
            playerNames[row] = e.getKey();
            TeamStats.PlayerStats st = e.getValue();
            // A data set restored on rejoin is shown immediately—the cache is
            // only a fallback and will be overwritten by the scoreboard/entity
            // snapshot's authoritative value, so no placeholder state.
            valueRows[row][0] = st.health < 0 ? "?" : String.valueOf(st.health);
            valueColors[row][0] = healthColor(st.health);
            valueRows[row][1] = statusLabel(st.status);
            valueColors[row][1] = statusColor(st.status);
            valueRows[row][2] = String.valueOf(st.kills);
            valueColors[row][2] = 0xFFFFFF;
            valueRows[row][3] = String.valueOf(st.downed);
            valueColors[row][3] = 0xFFFFFF;
            valueRows[row][4] = String.valueOf(st.deaths);
            valueColors[row][4] = 0xFFFFFF;
            valueRows[row][5] = String.valueOf(st.gold);
            valueColors[row][5] = 0xFFFFFF;
            for (int i = 0; i < headers.length; i++) {
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(headers[i]));
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(valueRows[row][i]));
            }
            row++;
        }
        return new TableLayout(headers, playerNames, valueRows, valueColors, colWidth, nameCol, displayCount);
    }

    /** The layout's pixel width (matching {@link #tableWidth}'s algorithm word for word). */
    private static int tableWidth(TableLayout layout) {
        int width = NAME_OFFSET + layout.nameCol() + PAD;
        for (int i = 0; i < layout.headers().length; i++) {
            width += layout.colWidth()[i];
            if (i != layout.headers().length - 1) {
                width += PAD;
            }
        }
        return width + TEXT_SHADOW;
    }

    /**
     * Lays out the whole table: column widths were computed in
     * {@link #tableLayout}; here it only draws.
     */
    private void drawTable(DrawContext context, int x, int y, TableLayout layout) {
        String[] headers = layout.headers();
        int nameCol = layout.nameCol();
        String[] playerNames = layout.playerNames();
        String[][] valueRows = layout.valueRows();
        int[][] valueColors = layout.valueColors();
        int[] colWidth = layout.colWidth();

        // The name column gives way to the avatar area on its left: headers
        // and value columns shift right by NAME_OFFSET, aligning with the name
        // and keeping the avatar visible.
        int nameX = x + NAME_OFFSET;
        int[] colX = new int[headers.length];
        int cx = nameX + nameCol + PAD;
        // The column gap is added only "between columns," never after the last
        // one—that is exactly tableWidth's accounting (written there as "add
        // PAD only if i != length-1"). The trailing PAD here is dead
        // arithmetic (after the loop nobody uses cx, and no column start
        // moved), but the three accountings must stay isomorphic: leaving it
        // in, someone will eventually use cx as the total width, and then the
        // frame and content would really misalign.
        for (int i = 0; i < headers.length; i++) {
            colX[i] = cx;
            cx += colWidth[i];
            if (i != headers.length - 1) {
                cx += PAD;
            }
        }

        for (int i = 0; i < headers.length; i++) {
            context.drawTextWithShadow(textRenderer, headers[i], colX[i], y, 0xFFFF55);
        }
        y += textRenderer.fontHeight + 2;

        int fh = textRenderer.fontHeight;
        for (int r = 0; r < playerNames.length; r++) {
            // The avatar takes the frontal face crop, vertically centered on
            // this row; the name gives way to NAME_OFFSET to clear the avatar
            // (fh=9, AVATAR_W=8, so the center offset is always 0—written out
            // to state the "centered" intent).
            AvatarUtils.drawHead(context, playerNames[r], null, x, y);
            context.drawTextWithShadow(textRenderer, playerNames[r], nameX, y, 0xFFFFFF);
            for (int i = 0; i < headers.length; i++) {
                context.drawTextWithShadow(textRenderer, valueRows[r][i], colX[i], y, valueColors[r][i]);
            }
            y += fh + 1;
        }
    }

    /** Status enum → the status column's wording, following lang keys and the client language. */
    private String statusLabel(TeamStats.Status status) {
        return switch (status) {
            case IN_COMBAT -> Text.translatable("nomorezombies.teamstats.status.combat").getString();
            case DOWNED -> Text.translatable("nomorezombies.teamstats.status.downed").getString();
            case DEAD -> Text.translatable("nomorezombies.teamstats.status.dead").getString();
            case LEFT -> Text.translatable("nomorezombies.teamstats.status.left").getString();
        };
    }

    /** Status column color: combat green / downed yellow / dead red / left dark red; danger level at a glance. */
    private int statusColor(TeamStats.Status status) {
        return switch (status) {
            case IN_COMBAT -> 0x55FF55;
            case DOWNED -> 0xFFFF55;
            case DEAD -> 0xFF5555;
            case LEFT -> 0xAA0000;
        };
    }

    /**
     * Health column color: &gt;10 green / &gt;5 yellow / otherwise red,
     * a negative (unknown) value returns white—paired with the "?" placeholder.
     */
    private int healthColor(int health) {
        if (health < 0) {
            return 0xFFFFFF;
        }
        if (health > 10) return 0x55FF55;
        if (health > 5) return 0xFFFF55;
        return 0xFF5555;
    }
}