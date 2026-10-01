package cn.gsfy.nmz.client.features.sidebar;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.hud.HudSampleData;
import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The draggable native scoreboard - once enabled, it takes over the vanilla
 * sidebar's rendering.
 *
 * <h2>Why self-drawn</h2>
 * <p>Vanilla {@code InGameHud.renderScoreboardSidebar} is private and
 * computes all coordinates inside the method body, with no parameter or field
 * to inject position/scale from outside; {@code InGameHudMixin} therefore
 * cancels the vanilla render at HEAD and calls
 * {@link #render(DrawContext, ScoreboardObjective)} to redraw at the
 * configured anchor. Names go through {@code Team.decorateName} and scores
 * through {@code entry.formatted(...)}, so Hypixel's team prefix/suffix and
 * score formatting survive - custom rendering only changes "where and how
 * big"; what is drawn is pixel-identical to vanilla.
 *
 * <h2>The row set converges strictly in vanilla order</h2>
 * <p>Filter hidden first, then sort by the vanilla comparator and truncate to
 * 15 rows; only then does {@link #liveRawLines(ScoreboardObjective)} turn the
 * result into raw material, and only afterwards does {@code rowsFromRaw} call
 * {@link SidebarEnhancer#filterLine(String, Set, boolean, boolean)} per row.
 * The enhancement filter must not move before sorting or truncation, or
 * deleting player rows would pull in content from beyond row 15 and the final
 * row set would no longer match the vanilla sidebar.
 *
 * <h2>The data flow is deliberately two-stage</h2>
 * <p>{@link #liveRawLines} takes the <b>pre-filter</b> raw material and
 * {@code rowsFromRaw} filters it into rows by the switches. The split lets
 * the HUD editor feed in the <b>not-yet-saved</b> workspace visibility state
 * (the teamStatsOn/gameTimeOn parameters of {@link #sampleView}), so
 * unchecking the "time HUD" updates the sample instantly; in-game rendering
 * passes the live config. Both channels share one filter, so the hot preview
 * and the saved result necessarily agree.
 *
 * <h2>Two raw material sets: live vs fixed sample</h2>
 * <p>In-game rendering uses {@link #liveRawLines} (the frame's real sidebar);
 * the editor sample uses {@link #sampleRawLines} (one Chinese and one English
 * set, copied verbatim from real logs).
 * <b>Neither falls back to the other</b>: if the sample mixed in live in-game
 * content, what the player sees while arranging the layout would not be what
 * their own sidebar should look like. The sample is a catalog, not a mirror.
 *
 * <h2>Geometry computed once, consumed three times</h2>
 * <p>{@link #boxWidth}/{@link #boxHeight} serve three consumers: in-game
 * rendering, the {@link HudSampleData editor sample} (registered into the
 * registry via {@code RegisterHUD}), and the default-position resolution of
 * {@link GlobalConfig#getXScoreboard()}/
 * {@link GlobalConfig#getYScoreboard()} -
 * one accounting for all three, the anchor always the component's top-left,
 * stable across resolutions.
 * The editor sample goes through {@link #drawPreview} (one extra layer of
 * sample-palette recoloring), the game goes through {@link #draw} (server
 * colors untouched).
 *
     * <p>The offline sample {@link #sampleRawLines()} copies the real sidebar's
     * text verbatim; calibrating it needs a log that shows § color codes. This
     * mod emits no such diagnostic output: once the sample is finalized it is
     * no longer needed, and keeping it would only flood the log every game.
     */
public final class ScoreboardHudRenderer {

    /** Row height (vanilla also 9, so line pitch matches). */
    public static final int ROW_H = 9;
    /** Title band height: title text takes 9px + 1px top margin; total = TITLE_H + ROW_H x rows. */
    public static final int TITLE_H = 10;
    /** Text padding left/right (the vanilla background box extends 2px left of the text; the right aligns with the score's right edge). */
    public static final int PAD_X = 2;
    /** Max rendered rows (vanilla also 15). */
    public static final int MAX_ROWS = 15;

    /** Vanilla's own sort: score descending, ties by owner case-insensitive lexicographic order. */
    private static final Comparator<ScoreboardEntry> ENTRY_COMPARATOR =
            Comparator.comparingInt(ScoreboardEntry::value).reversed()
                    .thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER);

    /**
     * One row's render material: name (team prefix/suffix already applied) +
     * score text + score pixel width (0 = this row draws no score).
     */
    public record Row(Text name, Text score, int scoreWidth) {
    }

    /**
     * One screen of sidebar content: a title + filtered, sorted rows.
     *
     * <p>The title's <b>styling is already attached</b>: a live in-game title
     * is taken from the server as is (with its own color), while the offline
     * sample title is built in the sample style (yellow + bold, see
     * {@link HudSampleData#mockTitleColor()}) -
     * styling must be one and the same object between "measuring width" and
     * "drawing"; bold changes glyph widths, and building it twice would shift
     * the default position by a pixel or two.
     */
    public record View(Text title, List<Row> rows) {
    }

    /**
     * Renders at the configured anchor: the anchor is the component's
     * top-left, scaling via {@link TotalHUDRenderer#drawScaled} around it.
     * Gating (in-game / master switch / placed / element switch) is decided
     * by {@code InGameHudMixin} before the call; this method only draws, and
     * filters with the <b>live config</b>
     * (the editor does not take this path - it has its own fixed-sample
     * channel at {@link #sampleView}).
     */
    public static void render(DrawContext context, ScoreboardObjective objective) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        Text title = objective.getDisplayName();
        List<RawLine> raw = liveRawLines(objective);
        // Rows computed once: measuring and drawing share one View, avoiding "measure by
        // objective A, draw by objective B"
        List<Row> rows = rowsFromRaw(raw, SidebarEnhancer.knownPlayerNames(),
                SidebarEnhancer.teamStatsOn(), SidebarEnhancer.gameTimeOn());
        float scale = (float) GlobalConfig.Hud.SCALE_SCOREBOARD.getDoubleValue();
        // Content size uses this screen's real row count: when rows change (players leave /
        // switches flip), right-hug and center both recompute, and the sidebar is never pushed
        // off screen.
        // The 1px reserve makes "right-hug" coincide pixel-for-pixel with vanilla's right
        // boundary (W-1)
        int hudW = TotalHUDRenderer.visibleSize(boxWidth(tr, title, rows), scale);
        int hudH = TotalHUDRenderer.visibleSize(boxHeight(rows), scale);
        // Reserve X=1 puts ratio=1.0 at screenW - width - 1, i.e. the vanilla background box's
        // right boundary; Y has no reserve, ratio=0.5 is mid-travel (vanilla centers the text
        // rows, and the whole box sits about 14px lower - that difference is left to the player
        // via "draggable"; see the scoreboard section in GlobalConfig.Hud)
        int ax = TotalHUDRenderer.anchorPixels(GlobalConfig.getXScoreboard(), screenWidth, hudW, RESERVE_X);
        int ay = TotalHUDRenderer.anchorPixels(GlobalConfig.getYScoreboard(), screenHeight, hudH, RESERVE_Y);
        TotalHUDRenderer.drawScaled(context, ax, ay, scale,
                () -> draw(context, tr, title, rows, ax, ay));
    }

    // ----Data----

    /**
     * One pre-filter raw row: the original text (with § codes) + score column
     * material. The score column is always empty on Hypixel (the objective's
     * number format is blank) but stays in the structure, keeping "row text
     * overwritten by the state machine" and "score as usual" independent.
     */
    private record RawLine(String raw, Text score, int scoreWidth) {
    }

    /**
     * Takes one screen of <b>pre-filter</b> raw material from the scoreboard:
     * hidden filter -> vanilla sort -> truncate to {@link #MAX_ROWS} ->
     * assemble {@link RawLine}s. Only afterwards may
     * {@link #rowsFromRaw(List, Set, boolean, boolean)} call
     * {@link SidebarEnhancer#filterLine(String, Set, boolean, boolean)} for
     * the enhancement filter;
     * the order must not move earlier, or removed rows within the first 15
     * would let entries beyond the truncation line slip in and the row set
     * would drift from vanilla's. Vanilla {@code renderScoreboardSidebar}
     * also filters hidden after {@code getScoreboardEntries}, then sorts and
     * truncates - this mirrors it step for step.
     *
     * <p>Returns raw text, not final rows: <b>filtering waits until the
     * switches are known</b> - the game uses the live config, the editor the
     * unsaved workspace config, and both go through {@link #rowsFromRaw}, so
     * preview and game share one source.
     */
    private static List<RawLine> liveRawLines(ScoreboardObjective objective) {
        Scoreboard scoreboard = objective.getScoreboard();
        NumberFormat numberFormat = objective.getNumberFormatOr(StyledNumberFormat.RED);
        List<ScoreboardEntry> entries = new ArrayList<>();
        for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
            if (!entry.hidden()) {
                entries.add(entry);
            }
        }
        entries.sort(ENTRY_COMPARATOR);
        int count = Math.min(entries.size(), MAX_ROWS);
        List<RawLine> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScoreboardEntry entry = entries.get(i);
            // Team looked up by owner (as vanilla does): the visible text is split across the
            // team's prefix/suffix
            Team team = scoreboard.getScoreHolderTeam(entry.owner());
            Text rawName = Team.decorateName(team, entry.name());
            Text score = entry.formatted(numberFormat);
            lines.add(new RawLine(rawName.getString(), score, scoreWidthOf(score)));
        }
        return lines;
    }

    /**
     * Raw material -> final rows: each line passes
     * {@link SidebarEnhancer#filterLine(String, Set, boolean, boolean)}
     * (both switches passed explicitly, so the editor can feed in unsaved
     * visibility state); {@code null} rows are dropped.
     *
     * <p>The single funnel: in-game rendering and the editor sample both pass
     * only through here, so the two row sets cannot disagree. Row text is
     * rebuilt uniformly with {@link Text#literal} - the § codes are parsed at
     * render and measure time by vanilla's
     * {@code TextVisitFactory.visitFormatted}
     * (verified: both {@code TextRenderer.getWidth} and
     * {@code Text.asOrderedText} route through it), so colors/anti-scrape
     * codes behave pixel-identically to vanilla.
     *
     * <p><b>The known-player set comes from the caller</b>: the game passes
     * {@link SidebarEnhancer#knownPlayerNames()} (the real roster, covering
     * downed/dead/left), the editor sample passes an empty set - the sample's
     * player rows are PlayerA~D placeholders, and judging them against the
     * real roster is both meaningless and would make the sample
     * "behave differently per who is online".
     *
     * @param rawLines pre-filter raw material
     * @param knownPlayers known player-name set (empty = skip the roster test)
     * @param teamStatsOn whether the team stats HUD is active
     * @param gameTimeOn whether the time HUD is active
     * @return the filtered rows
     */
    private static List<Row> rowsFromRaw(List<RawLine> rawLines, Set<String> knownPlayers,
                                         boolean teamStatsOn, boolean gameTimeOn) {
        List<Row> rows = new ArrayList<>(rawLines.size());
        for (RawLine line : rawLines) {
            String filtered = SidebarEnhancer.filterLine(line.raw(), knownPlayers, teamStatsOn, gameTimeOn);
            if (filtered != null) {
                rows.add(new Row(Text.literal(filtered), line.score(), line.scoreWidth()));
            }
        }
        return rows;
    }

    /** Score pixel width (0 when the textRenderer is not ready, equivalent to "no score column"). */
    private static int scoreWidthOf(Text score) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        return tr != null ? tr.getWidth(score) : 0;
    }

    /**
     * The one screen the editor sample draws - <b>always the fixed offline
     * sample</b>, entirely independent of "are you in a game".
     *
     * <p>The row material has exactly one source: {@link #sampleRawLines()}
     * (one Chinese and one English set of 15 rows, copied verbatim from real
     * logs), regardless of "are you in a game" - the sample is a catalog, not
     * a mirror.
     *
     * <p>The two filter switches still come from the caller (the editor
     * passes the unsaved workspace state), so the four shapes (full 15 rows /
     * with Kills / with Time / minimal 6 rows) still hot-preview; what changed
     * is only that the row material is constant and no longer jumps with
     * in-game data.
     *
     * <p>The known-player set is empty: {@link #rowsFromRaw}'s roster test is
     * meaningless on the offline sample (its player rows are PlayerA~D
     * placeholders), and a real-world name sneaking in would make the sample
     * "behave differently per who is online" - so the offline sample's known
     * player set is always empty.
     *
     * @param teamStatsOn whether the team stats HUD counts as active (on the canvas and enabled)
     * @param gameTimeOn whether the time HUD counts as active (on the canvas and enabled)
     * @return the fixed offline sample's view
     */
    public static View sampleView(boolean teamStatsOn, boolean gameTimeOn) {
        return new View(sampleTitle(),
                rowsFromRaw(sampleRawLines(), Set.of(), teamStatsOn, gameTimeOn));
    }

    /**
     * Offline sample material: <b>one Chinese and one English</b> 15-row
     * sidebar, representing the full vanilla shape with "team stats off + time
     * off". After each line passes
     * {@link SidebarEnhancer#filterLine(String, Set, boolean, boolean)}, the
     * four switch combinations yield the correct 15/15/7/6-row shapes.
     *
     * <p>The structural content (date row, round row, zombies left, Difficulty
     * row, time row, area row, footer) is copied character for character from
     * in-game logs, including § section marks and trailing spaces -
     * Hypixel's colors and anti-scrape codes all hide here, and <b>Chinese and
     * English hide them differently</b> (English {@code K§j§fills}, Chinese
     * {@code Kill§j§fs}, difficulty values {@code No§p§armal} /
     * {@code 普通§p§a模式}).
     * These are measured facts, not derivable from the translation table, and
     * can only be copied; {@link SidebarEnhancer}'s per-letter code-tolerant
     * regex depends on them to avoid the "strip-colors fallback" dropping the
     * green text.
     *
     * <p><b>Player rows carry placeholder names, not real IDs</b>: names are
     * filled to PlayerA~D by {@link HudSampleData#mockPlayerName(int)}, with
     * values/status from {@link HudSampleData#mockSidebarStatus(int)} (the
     * same mock data as the team stats sample) - real IDs would make players
     * think those are their teammates.
     * The finalized shape is "one gold row + three status rows
     * (downed/dead/left)": the status words are the last-resort evidence of
     * {@link SidebarEnhancer}'s player-row heuristic, and all three spellings
     * must be covered. Segment colors are not baked into the text; they are
     * re-applied on the <b>preview path</b> by {@link #drawPreview} using
     * {@link HudSampleData}'s sample palette - in-game rendering goes through
     * {@link #draw}, where server colors are untouched.
     *
     * <p>Language chosen by {@link GlobalConfig.Hud#isChineseClient()}.
     */
    private static List<RawLine> sampleRawLines() {
        String[] raw = GlobalConfig.Hud.isChineseClient() ? RAW_SIDEBAR_CN : RAW_SIDEBAR_EN;
        List<RawLine> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            // scoreWidth always 0: Hypixel sets the objective's number format to blank, so that
            // column never draws
            lines.add(new RawLine(fillPlayerSlots(raw[i], i), Text.empty(), 0));
        }
        return lines;
    }

    /**
     * Slot filling for the player rows (the four consecutive rows from
     * {@link #SAMPLE_FIRST_PLAYER_ROW}): {@code %1$s} name, {@code %2$s}
     * value; other rows have no slots and return unchanged.
     */
    private static String fillPlayerSlots(String line, int index) {
        if (line.indexOf('%') < 0) {
            return line;
        }
        int player = index - SAMPLE_FIRST_PLAYER_ROW;
        return line.formatted(HudSampleData.mockPlayerName(player), HudSampleData.mockSidebarStatus(player));
    }

    /**
     * The sample title: one wording per language, styled straight to the
     * sample's final look (yellow + bold) - styling is given here so that
     * "measuring width" and "drawing" use the same text object: bold widens
     * glyphs, and measuring plain text then drawing bold would shift the
     * default position by a pixel or two.
     */
    private static Text sampleTitle() {
        return Text.literal(GlobalConfig.Hud.isChineseClient() ? "僵尸末日" : "ZOMBIES")
                .styled(style -> style
                        .withColor(HudSampleData.mockTitleColor())
                        .withBold(HudSampleData.mockTitleBold()));
    }

    /** Index of the first player row in the sample array (player rows are four consecutive rows). */
    private static final int SAMPLE_FIRST_PLAYER_ROW = 5;

    /**
     * The English sidebar sample (15 rows; player rows leave {@code %1$s}
     * name / {@code %2$s} value slots).
     * Player rows carry <b>no § codes</b>: coloring is applied by the preview
     * path in the final palette, and leaving codes in would be overridden by
     * the style colors anyway.
     */
    private static final String[] RAW_SIDEBAR_EN = {
            "§709/12/26  §8m1§!§812BF",
            "  §z",
            "§c§lRound 5§y",
            "Zombies Left: §a§x§a10",
            "     §w",
            "%1$s§f§v§f: %2$s ",
            "%1$s§f: %2$s ",
            "%1$s§f: %2$s",
            "%1$s§s§f: %2$s ",
            "          §q",
            "Difficulty: §aNo§p§armal",
            "Time: §a0:29 §fK§j§fills: §a12",
            "Area: §aAlley§i",
            "              §h",
            "§ewww.hypixel.ne§g§et"
    };

    /**
     * The Chinese sidebar sample (same shape; note {@code Kill§j§fs} differs
     * from the English {@code K§j§fills}).
     */
    private static final String[] RAW_SIDEBAR_CN = {
            "§709/12/26  §8m9§!§83AN",
            "  §z",
            "§c§l第5§c§l回合§y",
            "剩余僵尸：§a7§x",
            "       §w",
            "%1$s§f§v§f: %2$s ",
            "%1$s§f: %2$s ",
            "%1$s§f: %2$s",
            "%1$s§s§f: %2$s ",
            "            §q",
            "Difficulty: §a普通§p§a模式",
            "时间：§a0:18 §fKill§j§fs: §a12",
            "区域： §aAlley§i",
            "                §h",
            "§ewww.hypixel.ne§g§et"
    };

    // ----Geometry (shared by rendering / resolvers / editor preview)----

    /**
     * Edge-hug reserve (virtual screen pixels) - the same value as the
     * {@code reserve(1, 0)} this HUD declares in {@code RegisterHUD}.
     *
     * <p>The 1px puts anchor {@code 1.0} at {@code screenW - width - 1},
     * exactly the vanilla sidebar background box's right boundary
     * ({@code W-1}); under the anchor accounting it is a constant that does
     * not vary with screen width.
     */
    private static final int RESERVE_X = 1;
    private static final int RESERVE_Y = 0;

    /**
     * Component pixel width = max(title width,
     * per-row name width + (score width &gt; 0 ? ":" width + score width : 0))
     * + 2x{@link #PAD_X}, the same accounting as the vanilla background box's
     * width ({@code l+4}).
     */
    public static int boxWidth(TextRenderer tr, Text title, List<Row> rows) {
        if (tr == null) {
            return 0;
        }
        int joiner = tr.getWidth(": ");
        int contentWidth = title == null ? 0 : tr.getWidth(title);
        for (Row row : rows) {
            int width = tr.getWidth(row.name());
            if (row.scoreWidth() > 0) {
                width += joiner + row.scoreWidth();
            }
            contentWidth = Math.max(contentWidth, width);
        }
        return contentWidth + PAD_X * 2;
    }

    /**
     * Component pixel height = {@link #TITLE_H} + {@link #ROW_H} x rows
     * (0 rows still gives vanilla's title-only 10px box).
     *
     * <p>Vanilla counts the line pitch into the box height: the background is
     * "title band 10px + 9px per row", i.e.
     * {@code TITLE_H + ROW_H x rows} - not "the last row's text baseline".
     * That is where vanilla's line pitch comes from; copying it keeps the box
     * from being shorter than vanilla
     * (shorter would shift bottom-hug/center positions up as a whole, and the
     * last row would touch the box edge).
     */
    public static int boxHeight(List<Row> rows) {
        return TITLE_H + ROW_H * rows.size();
    }

    // ----Drawing----

    /** No color override for this row (keep the original § codes). */
    private static final int NO_OVERRIDE = 0;

    /**
     * Draws at the top-left (ax, ay): title band + body backdrops, title
     * centered, names left-aligned, scores right-aligned - pixel-identical to
     * vanilla.
     * Text always uses shadow=false - vanilla's sidebar text also has no
     * shadow; copying it is what makes them line up.
     * <b>Shared by in-game rendering and the editor preview</b>: what the
     * editor shows is what the game gets; row content (colors included) comes
     * from the server as is.
     */
    public static void draw(DrawContext context, TextRenderer tr, Text title, List<Row> rows, int ax, int ay) {
        drawInternal(context, tr, title, rows, ax, ay, false);
    }

    /**
     * Editor-preview-only drawing - the same code as {@link #draw} plus one
     * layer of "offline sample palette override": the sample is the only
     * shape the player sees while arranging the layout, so the title is
     * yellow+bold, player names gray, and values colored by the four states
     * (final palette in {@link HudSampleData}). <b>In-game rendering does not
     * take this path</b>; server colors are untouched.
     *
     * @param context draw context
     * @param tr text renderer
     * @param title title already carrying the sample style ({@code null} draws only the backdrop, no title)
     * @param rows filtered rows
     * @param ax component top-left x
     * @param ay component top-left y
     */
    public static void drawPreview(DrawContext context, TextRenderer tr, Text title, List<Row> rows,
                                   int ax, int ay) {
        drawInternal(context, tr, title, rows, ax, ay, true);
    }

    /**
     * Drawing body: two backdrops + title + rows (text always shadow=false,
     * as vanilla does).
     *
     * @param sampleStyling {@code true} re-colors the sample's player rows in the final palette
     *  (editor preview path only); {@code false} uses each row's own style as is (in-game path)
     */
    private static void drawInternal(DrawContext context, TextRenderer tr, Text title, List<Row> rows,
                                     int ax, int ay, boolean sampleStyling) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        int width = boxWidth(tr, title, rows);
        int height = boxHeight(rows);
        int titleBg = client.options.getTextBackgroundColor(0.4F);
        int bodyBg = client.options.getTextBackgroundColor(0.3F);
        context.fill(ax, ay, ax + width, ay + ROW_H, titleBg);
        context.fill(ax, ay + ROW_H, ax + width, ay + height, bodyBg);
        if (title != null) {
            context.drawText(tr, title, ax + width / 2 - tr.getWidth(title) / 2, ay + 1, Colors.WHITE, false);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = ay + TITLE_H + ROW_H * i;
            int override = sampleStyling ? sampleRowValueColor(row) : NO_OVERRIDE;
            if (override == NO_OVERRIDE) {
                context.drawText(tr, row.name(), ax + PAD_X, y, Colors.WHITE, false);
            } else {
                drawSampleRowText(context, tr, row, override, ax + PAD_X, y);
            }
            if (row.scoreWidth() > 0) {
                context.drawText(tr, row.score(), ax + width - row.scoreWidth(), y, Colors.WHITE, false);
            }
        }
    }

    /**
     * Whether this row is one of the sample's player rows; if so returns its
     * final value color, otherwise {@link #NO_OVERRIDE}.
     *
     * <p>Recognized by <b>the player name in the row text</b>, not the row
     * index: filtering removes empty and time rows, so post-filter indices
     * have long drifted from the sample's original indices (with team stats
     * + time both on, 15 rows shrink to 6) - an index lookup would misattribute
     * colors. The name is the only stable anchor.
     */
    private static int sampleRowValueColor(Row row) {
        String plain = stripCodes(row.name().getString());
        int colon = plain.indexOf(':');
        if (colon <= 0) {
            return NO_OVERRIDE;
        }
        String name = plain.substring(0, colon).trim();
        for (int i = 0; i < HudSampleData.mockPlayerNameCount(); i++) {
            if (HudSampleData.mockPlayerName(i).equals(name)) {
                return HudSampleData.mockSidebarValueColor(i);
            }
        }
        return NO_OVERRIDE;
    }

    /**
     * Re-colors a sample player row: "name + colon" drawn gray, the value
     * after the colon drawn in the row's status color.
     *
     * <p>Split at the colon rather than coloring the whole row, because the
     * finalized shape is "names uniformly gray, values colored by status";
     * the colon follows the name, and visually it still reads as one
     * "who: what" line.
     *
     * <p><b>The § codes must be stripped before coloring</b>:
     * {@code Text.literal} parses {@code §x} into the text's own style, and
     * when {@code TextRenderer} renders, a style color takes priority over
     * the externally passed color - without stripping, the sample text's
     * {@code §a}/{@code §6} would override the final palette here and the
     * change would do nothing.
     * Stripping happens only on this <b>preview</b> path; in-game rendering
     * still uses the coded text.
     */
    private static void drawSampleRowText(DrawContext context, TextRenderer tr, Row row, int valueColor,
                                          int x, int y) {
        String plain = stripCodes(row.name().getString());
        int colon = plain.indexOf(':');
        if (colon < 0) {
            context.drawText(tr, Text.literal(plain).styled(s -> s.withColor(valueColor)),
                    x, y, Colors.WHITE, false);
            return;
        }
        Text namePart = Text.literal(plain.substring(0, colon + 1))
                .styled(s -> s.withColor(HudSampleData.mockPlayerNameColor()));
        Text valuePart = Text.literal(plain.substring(colon + 1))
                .styled(s -> s.withColor(valueColor));
        context.drawText(tr, namePart, x, y, Colors.WHITE, false);
        context.drawText(tr, valuePart, x + tr.getWidth(namePart), y, Colors.WHITE, false);
    }

    /** Strips all {@code §x} codes (Hypixel private codes included) - used before recoloring the sample preview. */
    private static String stripCodes(String text) {
        return text.replaceAll("§[0-9a-zA-Z]", "");
    }

    private ScoreboardHudRenderer() {
    }
}
