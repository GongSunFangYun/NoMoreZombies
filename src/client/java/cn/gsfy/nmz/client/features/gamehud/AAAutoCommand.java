package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.AaRoundDetail;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.game.DelayedTaskScheduler;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Alien Arcadium auto commander (AA auto commander)—watches each AA round's
 * intel for you: does this round spawn Giants / Old Ones, how dangerous is
 * it, which points to hold. One glance at the HUD answers it.
 *
 * <p>The HUD renders only inside an AA game; the gate is the utility HUD
 * master switch + the map identified as AA. Inside an AA game it is
 * force-shown and its independent visibility is bypassed, so the window
 * between map identification and visibility sync cannot make the HUD
 * flicker away.
 *
 * <p>Chat output ranks alongside the HUD: at each AA round's start it
 * auto-broadcasts the command advice in the configured output mode and
 * template from the global config page. The four variables
 * {round}/{point}/{boss}/{difficulty} are substituted from the round data;
 * an empty template or unknown variables fall back to the default template
 * without erroring. Gated by QoL.AA_AUTO_COMMAND_ENABLED. Round data is
 * read from assets/nomorezombies/data/aa_round_details.json, hot-reloaded
 * by DataManager—editing the data table needs no client restart.
 */
public class AAAutoCommand extends TotalHUDRenderer {

    /**
     * The whitelist of legal template variables; an empty template or one
     * containing an unknown {@code {xxx}} is invalid and falls back to the
     * default template (no error).
     */
    private static final Set<String> KNOWN_VARS = Set.of("round", "point", "boss", "difficulty");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** The round danger level's HUD colors: green/dark green/yellow/red/purple
     *  (index = dangerLevel - 1). */
    private static final int[] DIFFICULTY_COLORS = {0x55FF55, 0x00AA00, 0xFFFF55, 0xFF5555, 0xAA55FF};
    /** Round danger level Roman numerals (index = dangerLevel - 1); HUD and chat share one copy. */
    private static final String[] DIFFICULTY_ROMAN = {"I", "II", "III", "IV", "V"};
    /** Round danger level's chat colors
     *  (positionally aligned with {@link #DIFFICULTY_COLORS}: green/dark green/yellow/red/purple). */
    private static final Formatting[] DIFFICULTY_FORMATTING = {
            Formatting.GREEN, Formatting.DARK_GREEN, Formatting.YELLOW, Formatting.RED, Formatting.LIGHT_PURPLE
    };
    private static final int LABEL_COLOR = 0xFFAA00;
    private static final int VALUE_COLOR = 0xFFFFFF;
    /** The recommended point's "#N" marker color: blue (§9), so point numbers stand out in the list. */
    private static final int POINT_COLOR = 0x5555FF;
    private static final int YES_COLOR = 0xFF5555;
    private static final int NO_COLOR = 0x55FF55;
    private static final int UNKNOWN_COLOR = 0xAAAAAA;

    // ====================HUD====================

    /** Fixed intel row count (round / Giant / Old One / danger level) -
     *  the points row wraps as needed and is not among these. */
    private static final int INFO_ROWS = 4;
    /** Max rows the points section may spread over (first row with label, the rest continuations):
     *  height cap = {@link #INFO_ROWS} + this value. */
    private static final int MAX_POINT_ROWS = 3;
    /**
     * The points section's max pixel width (label excluded) - overflow wraps.
     *
     * <p>The value is a compromise: at GUI scale 2 a 1920-physical-pixel
     * screen is only 480 logical px wide, and point names vary in length - an
     * unbounded points row would stretch the whole HUD extremely wide, across
     * the screen. With a cap around half the screen, overlong rows wrap to the
     * next line (at most {@link #MAX_POINT_ROWS} rows), and the HUD stays "a
     * small block".
     */
    private static final int MAX_POINT_WIDTH = 240;

    /**
     * The editor sample's point segments ({@code {"#N", name}}) - sample
     * drawing and measuring share this one list, so the editor's frame always
     * hugs the two visible segments and never widens because "it measured a
     * different shape".
     */
    public static final String[][] PREVIEW_POINTS = {{"#1", "windows"}, {"#2", "mid"}, {"#3", "rc"}};

    /** The five row labels (order = row order) - shared by measuring and drawing, so the "measured rows" and "drawn rows" never misalign. */
    public static String[] hudLabels() {
        return new String[]{
                Text.translatable("nomorezombies.aaautocommand.hud.round").getString(),
                Text.translatable("nomorezombies.aaautocommand.hud.giant").getString(),
                Text.translatable("nomorezombies.aaautocommand.hud.oldone").getString(),
                Text.translatable("nomorezombies.aaautocommand.hud.difficulty").getString(),
                Text.translatable("nomorezombies.aaautocommand.hud.points").getString()
        };
    }

    /**
     * Wraps the point segments into one or more lines by "wrap when it does
     * not fit", at most {@link #MAX_POINT_ROWS} rows - segments beyond the cap
     * are dropped. Drawing and measuring both call it, so "the measured
     * width" is always the width of "the segments actually drawn".
     *
     * @param tr the text renderer
     * @param segments point segments ({@code {"#N", name}}, order preserved)
     * @return each line's segment list; empty when there are no segments
     */
    public static List<List<String[]>> wrapPointSegments(TextRenderer tr, List<String[]> segments) {
        List<List<String[]>> rows = new ArrayList<>();
        List<String[]> row = new ArrayList<>();
        int width = 0;
        for (String[] segment : segments) {
            int segWidth = tr.getWidth(segment[0]) + tr.getWidth(" ") + tr.getWidth(segment[1]);
            int sep = row.isEmpty() ? 0 : tr.getWidth(" ");
            if (!row.isEmpty() && width + sep + segWidth > MAX_POINT_WIDTH) {
                rows.add(row);
                if (rows.size() >= MAX_POINT_ROWS) {
                    return rows;
                }
                row = new ArrayList<>();
                width = 0;
                sep = 0;
            }
            row.add(segment);
            width += sep + segWidth;
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    /** One row's total pixel width (one space between segments, none after the last) - measuring and drawing share the same algorithm. */
    private static int rowWidth(TextRenderer tr, List<String[]> row) {
        int width = 0;
        for (int i = 0; i < row.size(); i++) {
            width += tr.getWidth(row.get(i)[0]) + tr.getWidth(" ") + tr.getWidth(row.get(i)[1]);
            if (i != row.size() - 1) {
                width += tr.getWidth(" ");
            }
        }
        return width;
    }

    /** The sample points row split by the real wrapping rules (shared by editor drawing and measuring). */
    public static List<List<String[]>> previewPointRows(TextRenderer tr) {
        return wrapPointSegments(tr, previewSegments());
    }

    /** The sample's point segment list (a list view of {@link #PREVIEW_POINTS}). */
    private static List<String[]> previewSegments() {
        return new ArrayList<>(Arrays.asList(PREVIEW_POINTS));
    }

    /**
     * The editor sample's points section pixel width: the <b>widest wrapped
     * line</b> - matching {@code drawPointsLine}'s advancing character for
     * character, both calling {@link #wrapPointSegments}, so the frame cannot
     * misalign with the drawn segments.
     */
    public static int previewPointsWidth(TextRenderer tr) {
        int width = 0;
        for (List<String[]> row : previewPointRows(tr)) {
            width = Math.max(width, rowWidth(tr, row));
        }
        return width;
    }

    /**
     * The editor sample width: the widest of the five "label + sample value"
     * rows + shadow overhang.
     *
     * <p>Measures only <b>what the sample actually draws</b>: the editor's
     * frame wraps the sample, and measuring another shape (say the worst-case
     * long point-name list) would leave the frame wider than the visible text -
     * exactly "frame not hugging content". The real HUD's edge-hug anchor does
     * not need this width (AA defaults to left-hug X=0), so no worst-case
     * reservation is needed for the game; the game's wrapping cap is held
     * separately by {@link #MAX_POINT_WIDTH} and {@link #hudHeight}.
     */
    public static int previewWidth(TextRenderer tr) {
        String[] labels = hudLabels();
        String yes = Text.translatable("nomorezombies.aaautocommand.yes").getString();
        String no = Text.translatable("nomorezombies.aaautocommand.no").getString();
        // The sample round is r105: AA tops out at round 105 and three digits is the real shape
        // (a frame measured from r15 runs narrow)
        String[] values = {"r105", yes, no, "III"};
        int width = 0;
        for (int i = 0; i < values.length; i++) {
            width = Math.max(width, tr.getWidth(labels[i]) + tr.getWidth(values[i]));
        }
        width = Math.max(width, tr.getWidth(labels[INFO_ROWS]) + previewPointsWidth(tr));
        return width + TEXT_SHADOW;
    }

    /** The editor sample height: intel rows + sample point rows + the last row's shadow overhang
     *  (the same accounting as {@link #previewWidth}). */
    public static int previewHeight(TextRenderer tr) {
        return (INFO_ROWS + previewPointRows(tr).size()) * tr.fontHeight + TEXT_SHADOW;
    }

    /**
     * The in-game component's pixel height cap: intel rows +
     * {@link #MAX_POINT_ROWS} point rows + the last row's shadow overhang.
     *
     * <p>Only for the default bottom-hug resolution
     * ({@code GlobalConfig.getYAAAutoCommand}) to reserve space: in game the
     * point row count varies with round data (1-3 rows), while the editor
     * measures the sample's single row's actual height - different purposes,
     * different accountings.
     */
    public static int hudHeight(TextRenderer tr) {
        return (INFO_ROWS + MAX_POINT_ROWS) * tr.fontHeight + TEXT_SHADOW;
    }

    /**
     * This frame's real content width: the intel rows take the widest "label +
     * this round's real value", the points row the widest wrapped line - the
     * same source as what {@link #drawHud} actually lays out.
     *
     * <p>Anchor positioning wants <b>this frame's content size</b> (travel =
     * screen width - content width - reserve); pinning to the right edge with
     * {@link #previewWidth}'s sample width would overflow on a true
     * "r105 + three rows of long point names" round: the sample measures the
     * three short windows/mid/rc segments.
     *
     * <p>The points' wrapping rule comes uniformly from
     * {@link #wrapPointSegments}, so the width measured here cannot misalign
     * with the drawn segments.
     *
     * @param tr the text renderer
     * @param round the current round number (joined into the real value text)
     * @param detail the round's data (may be {@code null}; values fall back to "?")
     * @return this frame's content pixel width (shadow overhang included)
     */
    public static int hudWidth(TextRenderer tr, int round, AaRoundDetail detail) {
        String yes = Text.translatable("nomorezombies.aaautocommand.yes").getString();
        String no = Text.translatable("nomorezombies.aaautocommand.no").getString();
        String[] labels = hudLabels();
        String[] values = {
                "r" + round,
                detail == null ? "?" : (detail.hasGiant() ? yes : no),
                detail == null ? "?" : (detail.hasOldOne() ? yes : no),
                detail == null ? "?" : difficultyRoman(detail.getDangerLevel())
        };
        int width = 0;
        for (int i = 0; i < values.length; i++) {
            width = Math.max(width, tr.getWidth(labels[i]) + tr.getWidth(values[i]));
        }
        List<List<String[]>> rows = wrapPointSegments(tr, buildPointSegments(detail));
        int pointsWidth = 0;
        for (List<String[]> row : rows) {
            pointsWidth = Math.max(pointsWidth, rowWidth(tr, row));
        }
        width = Math.max(width, tr.getWidth(labels[INFO_ROWS]) + pointsWidth);
        return width + TEXT_SHADOW;
    }

    /** The gate: render only inside an AA game - when the map is unidentified (chunks not loaded), getMap is not AA and the HUD is naturally kept out. */
    @Override
    protected boolean shouldRenderHud() {
        return PlayerUtils.isInZombies() && LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM;
    }

    /**
     * Draws the AA round intel: round / Giant / Old One / danger level /
     * recommended points, the danger level colored in Roman numerals; without
     * round data the whole block falls back to "?".
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        // Force-shown inside AA: "AA mode auto-enables" is hard semantics, so this HUD's
        // independent visibility switch is bypassed in AA - but the master switch and "placed
        // on the canvas by the player" cannot be bypassed, composed into one predicate
        // (see GlobalConfig.Hud#aaAutoCommandOn). The predicate takes "is the current map AA"
        // as a parameter rather than re-querying: map determination funnels through
        // LanguageUtils, and the predicate only composes gates.
        if (!GlobalConfig.Hud.aaAutoCommandOn(LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM)) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round <= 0) {
            return;
        }
        AaRoundDetail detail = DataManager.get().getAaRoundDetail(round);

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_AA_AUTO_COMMAND.getDoubleValue();
        // Content width by this frame's real content (intel rows + this round's wrapped point
        // rows), not the sample width - the sample's three short point names cannot hold
        // "r105 + long point names" rounds
        int hudW = visibleSize(hudWidth(textRenderer, round, detail), scale);
        // Height positioned by the cap (intel rows + up to 3 point rows): the position must not
        // jump up and down with a round's point row count
        int hudH = visibleSize(hudHeight(textRenderer), scale);
        // Reserve 0: the hotbar only occupies the screen's lower middle, and reserving the whole
        // edge would keep this command board out of the bottom corners; whether it overlaps the
        // hotbar is left to the player's eyes
        int x = anchorPixels(GlobalConfig.getXAAAutoCommand(), screenWidth, hudW, 0);
        int y = anchorPixels(GlobalConfig.getYAAAutoCommand(), screenHeight, hudH, 0);

        drawScaled(context, x, y, scale, () -> drawHud(context, x, y, round, detail));
    }

    /** Draws the five intel rows: labels orange, values colored by state; without round data the whole block falls back to gray "?". */
    private void drawHud(DrawContext context, int x, int y, int round, AaRoundDetail detail) {
        String yes = Text.translatable("nomorezombies.aaautocommand.yes").getString();
        String no = Text.translatable("nomorezombies.aaautocommand.no").getString();
        int fh = textRenderer.fontHeight;

        drawLine(context, x, y,
                Text.translatable("nomorezombies.aaautocommand.hud.round").getString(),
                "r" + round, VALUE_COLOR);
        y += fh;

        String giantVal = detail == null ? "?" : (detail.hasGiant() ? yes : no);
        int giantColor = detail == null ? UNKNOWN_COLOR : (detail.hasGiant() ? YES_COLOR : NO_COLOR);
        drawLine(context, x, y,
                Text.translatable("nomorezombies.aaautocommand.hud.giant").getString(), giantVal, giantColor);
        y += fh;

        String oldoneVal = detail == null ? "?" : (detail.hasOldOne() ? yes : no);
        int oldoneColor = detail == null ? UNKNOWN_COLOR : (detail.hasOldOne() ? YES_COLOR : NO_COLOR);
        drawLine(context, x, y,
                Text.translatable("nomorezombies.aaautocommand.hud.oldone").getString(), oldoneVal, oldoneColor);
        y += fh;

        drawLine(context, x, y,
                Text.translatable("nomorezombies.aaautocommand.hud.difficulty").getString(),
                detail == null ? "?" : difficultyRoman(detail.getDangerLevel()),
                detail == null ? UNKNOWN_COLOR : difficultyColor(detail.getDangerLevel()));
        y += fh;

        drawPointsLine(context, x, y, detail);
    }

    /** One "label + value" row: the orange label first, the value drawn right at the label's right edge in the given color;
     *  the row width is their sum. */
    private void drawLine(DrawContext context, int x, int y, String label, String value, int valueColor) {
        context.drawTextWithShadow(textRenderer, label, x, y, LABEL_COLOR);
        context.drawTextWithShadow(textRenderer, value, x + textRenderer.getWidth(label), y, valueColor);
    }

    /** The recommended points row: after the label, blue "#N" markers + white point names per the wrapping rules;
     *  with no data a "?" placeholder is drawn. Continuation rows do not repeat the label -
     *  they align with the first row's points area (the label-width gap), reading as one
     *  continuous list. */
    private void drawPointsLine(DrawContext context, int x, int y, AaRoundDetail detail) {
        String label = Text.translatable("nomorezombies.aaautocommand.hud.points").getString();
        int labelW = textRenderer.getWidth(label);
        context.drawTextWithShadow(textRenderer, label, x, y, LABEL_COLOR);
        List<List<String[]>> rows = wrapPointSegments(textRenderer, buildPointSegments(detail));
        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "?", x + labelW, y, VALUE_COLOR);
            return;
        }
        for (int r = 0; r < rows.size(); r++) {
            // The first row follows the label; continuations step back into the label-width gap -
            // the list reads as one block, never running under the label
            int cx = x + labelW;
            int rowY = y + textRenderer.fontHeight * r;
            List<String[]> row = rows.get(r);
            for (int i = 0; i < row.size(); i++) {
                String[] segment = row.get(i);
                String marker = segment[0] + " ";
                context.drawTextWithShadow(textRenderer, marker, cx, rowY, POINT_COLOR);
                cx += textRenderer.getWidth(marker);
                context.drawTextWithShadow(textRenderer, segment[1], cx, rowY, VALUE_COLOR);
                cx += textRenderer.getWidth(segment[1]);
                if (i != row.size() - 1) {
                    context.drawTextWithShadow(textRenderer, " ", cx, rowY, VALUE_COLOR);
                    cx += textRenderer.getWidth(" ");
                }
            }
        }
    }

    // ====================Chat output (auto command)====================

    /**
     * The round-start hook (called by GameEventBus): broadcasts after a
     * 20-tick delay. Why wait: the round title often arrives before map
     * resolution, and an immediate broadcast would fail to qualify while the
     * map is unidentified; 20 ticks give map identification time to finish,
     * and at the deadline {@link #fireIfDue(int)} runs, backed by "retry every
     * 20 ticks, at most 15 times" if the map is still unrecognized.
     */
    public static void onRoundStarted(int round) {
        if (round <= 0) {
            return;
        }
        DelayedTaskScheduler scheduler = DelayedTaskScheduler.get();
        if (scheduler == null) {
            return;
        }
        scheduler.runTaskLater(20, () -> fireIfDue(round));
    }

    /** Invoked by the scheduler when the 20-tick delay expires: enters the guarded version with attempt 0, which formally decides whether to broadcast. */
    private static void fireIfDue(int round) {
        fireIfDue(round, 0);
    }

    /**
     * The broadcast guard - splits "should it speak" into four gates: the
     * master switch, the round number not having advanced, still in Zombies,
     * and the map truly AA; all pass before speaking. An unidentified map must
     * not give up outright: the round title often arrives before map
     * resolution, so it retries every 20 ticks and only gives up this
     * broadcast after 15 tries (about 15s) without identification.
     */
    private static void fireIfDue(int round, int attempt) {
        if (!GlobalConfig.QoL.AA_AUTO_COMMAND_ENABLED.getBooleanValue()) {
            return;
        }
        // A new round started within the 20-tick delay window: that broadcast is stale - skip
        // this one, guarding against duplicate broadcasts
        if (CheckSpawnTimes.get().getCurrentRound() != round) {
            return;
        }
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            // Map unidentified: retry up to the cap, then give up this broadcast (until the next
            // round title)
            if (attempt < 15) {
                DelayedTaskScheduler.get().runTaskLater(20, () -> fireIfDue(round, attempt + 1));
            }
            return;
        }
        if (map != MapId.ALIEN_ARCADIUM) {
            return;
        }
        Text message = buildMessageText(round);
        Text text = Text.literal("[NoMoreZombies] ").formatted(Formatting.GOLD)
                .copy()
                .append(message);
        PlayerUtils.sendMessage(text, getOutput());
    }

    /** Reads the broadcast output from the global config: falls back to SELF on a mistyped config value - the fallback must guarantee the broadcast always has somewhere to go. */
    private static GlobalConfig.AlertOutput getOutput() {
        return GlobalConfig.AAAutoCommand.OUTPUT.getOptionListValue() instanceof GlobalConfig.AlertOutput o
                ? o : GlobalConfig.AlertOutput.SELF;
    }

    /** Builds this round's command message from the template: an invalid template falls back to the default,
     *  and missing round data fills the relevant fields with "?". */
    public static Text buildMessageText(int round) {
        AaRoundDetail detail = DataManager.get().getAaRoundDetail(round);
        String template = resolveTemplate();
        MutableText result = Text.literal("");
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            result.append(Text.literal(template.substring(last, m.start())));
            switch (m.group(1)) {
                case "round" -> result.append(Text.literal(Integer.toString(round)).formatted(Formatting.YELLOW));
                case "point" -> result.append(buildPointsText(detail));
                case "boss" -> result.append(buildBossText(detail));
                case "difficulty" -> result.append(buildDifficultyText(detail));
                default -> result.append(Text.literal(m.group()));
            }
            last = m.end();
        }
        result.append(Text.literal(template.substring(last)));
        return result;
    }

    /** Fetches the output template: an invalid config template (empty or with unknown variables)
     *  falls back to the lang key's localized default template. */
    private static String resolveTemplate() {
        String template = GlobalConfig.AAAutoCommand.TEMPLATE.getStringValue();
        // Invalid template -> localized default; the default goes through a lang key and follows
        // the client language
        return isValidTemplate(template) ? template
                : Text.translatable("nomorezombies.aaautocommand.defaultTemplate").getString();
    }

    /** Template validity: non-empty and every {@code {xxx}} variable inside the {@code KNOWN_VARS} whitelist, otherwise the whole template is invalid. */
    private static boolean isValidTemplate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return false;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!KNOWN_VARS.contains(m.group(1))) {
                return false;
            }
        }
        return true;
    }

    /** Splits the recommended points into segments: [{"#N", name}, ...], colored per segment by the HUD;
     *  returns an empty list (not null) with no data. */
    private static List<String[]> buildPointSegments(AaRoundDetail detail) {
        if (detail == null) {
            return List.of();
        }
        List<String> points = detail.getRecommendedPoints();
        if (points.isEmpty()) {
            return List.of();
        }
        List<String[]> segments = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            segments.add(new String[]{"#" + (i + 1), points.get(i)});
        }
        return segments;
    }

    /** The recommended points (chat version): blue "#N" markers + white point names; "?" with no data. */
    public static Text buildPointsText(AaRoundDetail detail) {
        List<String[]> segments = buildPointSegments(detail);
        if (segments.isEmpty()) {
            return Text.literal("?");
        }
        MutableText result = Text.literal("");
        for (int i = 0; i < segments.size(); i++) {
            String[] segment = segments.get(i);
            result.append(Text.literal(segment[0]).formatted(Formatting.BLUE));
            result.append(Text.literal(" " + segment[1]).formatted(Formatting.WHITE));
            if (i != segments.size() - 1) {
                result.append(Text.literal(" "));
            }
        }
        return result;
    }

    /** The boss description wording: Giant / Old One / both / none -
     *  via lang keys, following the client language. */
    public static String buildBoss(AaRoundDetail detail) {
        if (detail == null) {
            return "?";
        }
        boolean giant = detail.hasGiant();
        boolean oldOne = detail.hasOldOne();
        if (giant && oldOne) {
            return Text.translatable("nomorezombies.aaautocommand.boss.both").getString();
        }
        if (giant) {
            return Text.translatable("nomorezombies.aaautocommand.boss.giant").getString();
        }
        if (oldOne) {
            return Text.translatable("nomorezombies.aaautocommand.boss.oldone").getString();
        }
        return Text.translatable("nomorezombies.aaautocommand.boss.none").getString();
    }

    /** The boss description (chat version): red when bosses come, green when none -
     *  whether to defend this round at a glance; "?" with no data. */
    public static Text buildBossText(AaRoundDetail detail) {
        if (detail == null) {
            return Text.literal("?");
        }
        boolean hasBoss = detail.hasGiant() || detail.hasOldOne();
        return Text.literal(buildBoss(detail)).formatted(hasBoss ? Formatting.RED : Formatting.GREEN);
    }

    /** The round danger level's HUD color (1-5 -> green/dark green/yellow/red/purple); out of range falls back to white. */
    public static int difficultyColor(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_COLORS.length) {
            return VALUE_COLOR;
        }
        return DIFFICULTY_COLORS[dangerLevel - 1];
    }

    /** The round danger level's Roman numeral (1-5 -> I/II/III/IV/V); out of range falls back to "?". */
    public static String difficultyRoman(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_ROMAN.length) {
            return "?";
        }
        return DIFFICULTY_ROMAN[dangerLevel - 1];
    }

    /** The round danger level's chat color (one-to-one with the HUD's difficultyColor); out of range falls back to white. */
    public static Formatting difficultyFormatting(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_FORMATTING.length) {
            return Formatting.WHITE;
        }
        return DIFFICULTY_FORMATTING[dangerLevel - 1];
    }

    /** The difficulty (chat version): Roman numeral colored by danger level; a white "?" placeholder with no data. */
    public static Text buildDifficultyText(AaRoundDetail detail) {
        if (detail == null) {
            return Text.literal("?").formatted(Formatting.WHITE);
        }
        return Text.literal(difficultyRoman(detail.getDangerLevel()))
                .formatted(difficultyFormatting(detail.getDangerLevel()));
    }
}