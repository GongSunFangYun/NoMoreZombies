package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * The power-up HUD (the original PowerupRenderer) - one fixed row per
 * power-up type, rows never jump around.
 *
 * <p>Every row reads as three parts: the <b>power-up name</b> -> the
 * <code>-</code> separator (always light blue) -> <b>Round (status)</b>.
 * Right-side text is always white, with one exception, the timer: an active
 * effect's remaining seconds change color by urgency
 * (&gt;10s green / &gt;3s gold / otherwise red), while a dropped item's
 * vanish countdown uses <b>cyan</b> - cyan deliberately stands apart from
 * the separator's light blue, and once the remainder is &lt;=
 * {@value #DROP_FLASH_SECONDS}s the segment flips between <b>cyan and
 * white</b> every second (cyan-white-cyan-white...), writing "grab it or
 * lose it" right into the time.
 * The separator stays light blue so the six rows visually align into a
 * "name | status" two-column look - names are colored per power-up type, and
 * a separator that changed color with the status would make the block look
 * noisy.
 *
 * <p>The state machine has only four displayable shapes, high to low;
 * <b>anything else does not render the row at all</b>:
 * <ol>
 *  <li>{@code Round (Active [X.Xs])} - picked up, real-duration countdown running;</li>
 *  <li>{@code Round (Active)} - the confirmation window of an instant
 *  power-up picked up (e.g. Max Ammo);</li>
 *  <li>{@code Round (Dropped [00:SS])} - the power-up is on the ground, not
 *  yet picked up, SS = remaining vanish seconds (cyan, flashing when about to
 *  vanish);</li>
 *  <li>{@code Round (Undropped)} - due this round but not yet on the ground
 *  (only predictable types reach this state)</li>
 * </ol>
 * "A vanished power-up (effect ended after pickup, or expired on the ground)
 * drops the whole row" - no "vanished / picked up" placeholder wording
 * anymore; those states carry no information and would just hold a row for
 * nothing.
 *
 * <p><b>Row content and size share one source</b>: each frame
 * {@link #collectRows(TextRenderer, long)} first spreads the state machine's
 * result into a {@link Row} list (name column + status segments), and both
 * rendering and {@link #rowsWidth}/{@link #rowsHeight} read only that list -
 * the component's size therefore always equals "what this frame actually
 * draws": it neither reserves blank space for the widest shape nor draws the
 * countdown outside the component when a power-up activates and its status
 * string grows.
 * The editor preview uses the same scheme: inside a game it draws the live
 * rows (what you see is what you get); outside, it falls back to the six-row
 * demo sample at {@link #sampleRows}.
 *
 * <p>Power-up names and status wording all go through lang keys (the
 * {@link #KEY_SEP} family) and follow the client language; hard-coded Chinese
 * would pin English environments to Chinese too.
 */
public class PowerupRenderer extends TotalHUDRenderer {

    /** Render order (fixed, rows never jump): power-ups always stack top to bottom in this order, regardless of pickup time. */
    private static final List<PowerupParser.PowerupType> ORDER = List.of(
            PowerupParser.PowerupType.INSTA_KILL,
            PowerupParser.PowerupType.MAX_AMMO,
            PowerupParser.PowerupType.SHOPPING_SPREE,
            PowerupParser.PowerupType.DOUBLE_GOLD,
            PowerupParser.PowerupType.BONUS_GOLD,
            PowerupParser.PowerupType.CARPENTER);

    // ====================Lang keys====================

    /**
     * The separator between name and status (always light blue). Its own key:
     * its color differs from both sides and must not blend into other
     * wording.
     */
    public static final String KEY_SEP = "nomorezombies.powerup.hud.sep";
    /** The round label, between the separator and the status parentheses (Chinese "本回合" / English "Round"). */
    public static final String KEY_ROUND = "nomorezombies.powerup.hud.round";
    /** Undropped status, shaped like {@code (Undropped)}. */
    public static final String KEY_UNDROPPED = "nomorezombies.powerup.hud.undropped";
    /** Dropped status, shaped like {@code (Dropped [%s])} - {@code %s} is the cyan vanish countdown. */
    public static final String KEY_DROPPED = "nomorezombies.powerup.hud.dropped";
    /** Active (real duration) status, shaped like {@code (Active [%s s])} -
     *  {@code %s} is the urgency-colored remaining seconds. */
    public static final String KEY_ACTIVE = "nomorezombies.powerup.hud.active";
    /** Active (instant power-up) status, shaped like {@code (Active)} - no countdown, the whole segment white. */
    public static final String KEY_ACTIVE_INSTANT = "nomorezombies.powerup.hud.activeInstant";

    // ====================Palette====================

    /** The separator's light blue - one color for all six rows; whatever the right-side status does leaves it alone. */
    private static final int COLOR_SEPARATOR = 0x99CCFF;
    /** Status text white (everything except timers uses it). */
    private static final int COLOR_TEXT = 0xFFFFFF;
    /**
     * The dropped countdown's <b>cyan</b> - deliberately apart from the
     * separator's light blue: the countdown is "a fact still changing" while
     * the separator is just a typographic mark; the same color would read as
     * punctuation and hide that time is running.
     */
    private static final int COLOR_DROP_TIMER = 0x00FFFF;
    /**
     * The dropped countdown's flash color when it is about to vanish:
     * alternating with cyan once per second (cyan-white-cyan-white...),
     * white being the "flash" frame.
     */
    private static final int COLOR_DROP_FLASH = 0xFFFFFF;
    /** Flashing starts at or below this many remaining seconds (one flip per second, in step with the ticking countdown). */
    private static final int DROP_FLASH_SECONDS = 10;
    /** Active countdown's urgency palette: >10s green / >3s gold / otherwise red. */
    private static final int URGENT_AMBER_SECONDS = 10;
    private static final int URGENT_RED_SECONDS = 3;
    private static final int COLOR_SAFE = 0x55FF55;
    private static final int COLOR_AMBER = 0xFFAA00;
    private static final int COLOR_DANGER = 0xFF5555;

    // ====================Sample values====================

    /** The vanish countdown used for the sample / width measuring: a power-up just landed has 60s, so {@code 00:60} is the width upper bound. */
    public static final String SAMPLE_COUNTDOWN = "00:60";
    /** The sample's active remaining seconds: {@code 30.0} (green), {@code 10.0} (gold), {@code 1.0} (red) - all three shapes demonstrated. */
    public static final String SAMPLE_ACTIVE_LONG = "30.0";
    public static final String SAMPLE_ACTIVE_MID = "10.0";
    public static final String SAMPLE_ACTIVE_SHORT = "1.0";

    /**
     * One segment within a row: text + color. The status string is cut into
     * "white prefix / colored timer / white suffix"; segmentation lets
     * "measuring" and "drawing" read the same data - measure the segments'
     * sum, draw the same string.
     */
    public record Segment(String text, int color) {
    }

    /**
     * One power-up row: the type + the status segments after the name column.
     *
     * <p>Only types with a displayable status appear in the row list; vanished
     * types are absent, so component height = row count x row height - no
     * extra constant, no placeholder blank rows.
     * Time-varying effects like flashing do not enter the record - they only
     * change a segment's color, never the text or width, so
     * {@link #collectRows} picks the color from the current second.
     */
    public record Row(PowerupParser.PowerupType type, List<Segment> segments) {
    }

    /**
     * The render order's immutable static list (six types, fixed row order) -
     * the editor's offline sample lays out rows in the same order, shared
     * with rendering so the two sides cannot diverge.
     *
     * @return the immutable list of power-up types in fixed order (a {@code List.of} static list)
     */
    public static List<PowerupParser.PowerupType> renderOrder() {
        return ORDER;
    }

    /** Name column width = the widest of the six power-up names (Chinese and English differ in width; never guess by character count). */
    public static int nameColumnWidth(TextRenderer tr) {
        int width = 0;
        for (PowerupParser.PowerupType type : ORDER) {
            width = Math.max(width, tr.getWidth(powerupName(type)));
        }
        return width;
    }

    /**
     * Splits a status template into segments: the {@code %s} slot is replaced
     * by the timer text and colored alone; the prefix and suffix stay white.
     * Without a {@code %s} (undropped / instant active) the whole string is
     * one white segment.
     *
     * <p>The template is split only here: a scattered split would make the
     * measured width disagree with the drawn glyphs.
     *
     * @param template the language template (with an optional {@code %s})
     * @param timer the timer text (ignored when the template has no {@code %s})
     * @param timerColor the timer text's color
     * @return one to three segments for this status
     */
    private static List<Segment> templated(String template, String timer, int timerColor) {
        int marker = template.indexOf("%s");
        if (marker < 0) {
            return List.of(new Segment(template, COLOR_TEXT));
        }
        String pre = template.substring(0, marker);
        String post = template.substring(marker + 2);
        return List.of(new Segment(pre, COLOR_TEXT),
                new Segment(timer, timerColor),
                new Segment(post, COLOR_TEXT));
    }

    /**
     * Assembles a row's right-side segments: {@code -} (light blue) + the
     * round label (white) + the status (white / timer color).
     *
     * @param sep the separator text ({@link #KEY_SEP})
     * @param roundLabel the round label text ({@link #KEY_ROUND})
     * @param state the status segments ({@link #templated}'s product)
     * @return the row's complete right-side segment list
     */
    private static List<Segment> statusLine(String sep, String roundLabel, List<Segment> state) {
        List<Segment> segments = new ArrayList<>(state.size() + 2);
        segments.add(new Segment(sep, COLOR_SEPARATOR));
        segments.add(new Segment(roundLabel, COLOR_TEXT));
        segments.addAll(state);
        return segments;
    }

    /** Total pixel width of one row's status segments (segments butt together, no extra spacing). */
    public static int segmentsWidth(TextRenderer tr, List<Segment> segments) {
        int width = 0;
        for (Segment segment : segments) {
            width += tr.getWidth(segment.text());
        }
        return width;
    }

    /**
     * Row-list pixel width: name column + the widest row's status segments
     * in <b>this</b> row list + shadow overhang.
     *
     * <p>The key is "this row list": the width is not reserved for "the
     * longest status" but computed from the rows actually being drawn, so the
     * component is narrow when statuses are short and the right side stays
     * clean; when one type activates and its status string grows, that row
     * itself joins the max and the countdown lands inside the component (never
     * clipped by the frame).
     *
     * @param tr the text renderer
     * @param rows the rows to draw this frame (or the sample)
     * @return the component's pixel width; {@code 0} for an empty row list
     */
    public static int rowsWidth(TextRenderer tr, List<Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int nameCol = nameColumnWidth(tr);
        int statusCol = 0;
        for (Row row : rows) {
            statusCol = Math.max(statusCol, segmentsWidth(tr, row.segments()));
        }
        return nameCol + statusCol + TEXT_SHADOW;
    }

    /**
     * Row-list pixel height: row count x row height + the last row's shadow
     * overhang.
     *
     * @param tr the text renderer
     * @param rows the rows to draw this frame (or the sample)
     * @return the component's pixel height; {@code 0} for an empty row list
     */
    public static int rowsHeight(TextRenderer tr, List<Row> rows) {
        return rows.isEmpty() ? 0 : rows.size() * tr.fontHeight + TEXT_SHADOW;
    }

    /**
     * The editor's component width / default-position resolution width:
     * <b>always measured from the six-row sample list</b>.
     *
     * <p><b>Unrelated to in-game state</b> - the editor sample is a catalog of
     * "what this HUD looks like", not a mirror of "what I look like right
     * now": using live rows, the sample would tick second by second with the
     * real game state (countdowns) and the component frame would jitter;
     * worse, the default right-hug resolution also reads it, so the same
     * config file would resolve to different default positions between games.
     *
     * <p>The six-row sample includes the longest status shape
     * ("Active (30.0s)"), so its width fits any real row and never overflows
     * the screen.
     *
     * @param tr the text renderer
     * @return the component's pixel width
     */
    public static int previewWidth(TextRenderer tr) {
        return rowsWidth(tr, sampleRows(tr));
    }

    /** The editor's component height / default-position resolution height: same as {@link #previewWidth}, always measured from the six-row sample.
     *
     * @param tr the text renderer
     * @return the component's pixel height
     */
    public static int previewHeight(TextRenderer tr) {
        return rowsHeight(tr, sampleRows(tr));
    }

    /**
     * The offline sample row list - <b>the only source for the editor sample
     * and default-position resolution</b>: one row per type, covering all four
     * displayable shapes and all three urgency colors.
     *
     * <p>The order matches the real row order exactly ({@link #ORDER}), so the
     * relative positions in the editor are the relative positions in game.
     * The dropped row uses the full {@value #SAMPLE_COUNTDOWN}
     * (&gt;{@value #DROP_FLASH_SECONDS}s), so it shows the <b>steady cyan</b> -
     * the flash shape is decided per second by {@link #colorForDropSeconds(int)},
     * which the sample cannot capture.
     *
     * <p>Assembled from fixed literals: it <b>reads none</b> of
     * {@link PowerupParser}'s collections and does not look at whether a
     * Zombies game is running, for two reasons: the editor sample wants "the
     * finalized look", not "my current power-ups"; and
     * {@link #collectRows}'s first line expires stale entries - the editor
     * calling that once per frame for the sample would amount to a state
     * mutation done for power-up detection from the UI thread.
     *
     * @param tr the text renderer (same signature as {@link #collectRows} so callers pass one; this method measures fixed wording and does not use it)
     * @return the six-row sample
     */
    public static List<Row> sampleRows(TextRenderer tr) {
        // These six "local = Text.translatable(KEY_...)" lines plus statusLine(sep, roundLabel,
        // ...) below are the sample's sole source; deliberately not extracted into small
        // objects / a constant pool
        String sep = Text.translatable(KEY_SEP).getString();
        String roundLabel = Text.translatable(KEY_ROUND).getString();
        String undropped = Text.translatable(KEY_UNDROPPED).getString();
        String dropped = Text.translatable(KEY_DROPPED).getString();
        String active = Text.translatable(KEY_ACTIVE).getString();
        String activeInstant = Text.translatable(KEY_ACTIVE_INSTANT).getString();
        List<Row> rows = new ArrayList<>(ORDER.size());
        rows.add(new Row(ORDER.get(0),
                statusLine(sep, roundLabel, templated(undropped, "", COLOR_TEXT))));
        rows.add(new Row(ORDER.get(1),
                statusLine(sep, roundLabel, templated(active, SAMPLE_ACTIVE_LONG, COLOR_SAFE))));
        rows.add(new Row(ORDER.get(2),
                statusLine(sep, roundLabel, templated(active, SAMPLE_ACTIVE_MID, COLOR_AMBER))));
        rows.add(new Row(ORDER.get(3),
                statusLine(sep, roundLabel, templated(active, SAMPLE_ACTIVE_SHORT, COLOR_DANGER))));
        rows.add(new Row(ORDER.get(4),
                statusLine(sep, roundLabel, templated(dropped, SAMPLE_COUNTDOWN, colorForDropSeconds(60)))));
        rows.add(new Row(ORDER.get(5),
                statusLine(sep, roundLabel, templated(activeInstant, "", COLOR_TEXT))));
        return rows;
    }

    /**
     * The dropped countdown's color: at or below
     * {@value #DROP_FLASH_SECONDS} remaining seconds it flips between
     * <b>cyan and white</b> by the second's parity (cyan-white-cyan-white...),
     * otherwise it stays cyan.
     *
     * <p>Why parity rather than wall-clock modulo: the countdown number
     * changes every second, and a parity flip is naturally in step with the
     * ticking (exactly one flip per second, never "the number did not change
     * but the color flashed twice"). Color takes no part in width measuring,
     * so picking it here never jitters the component size.
     *
     * @param seconds the on-ground power-up's remaining whole seconds
     * @return the countdown color for that second
     */
    public static int colorForDropSeconds(int seconds) {
        return seconds <= DROP_FLASH_SECONDS && seconds % 2 == 0 ? COLOR_DROP_FLASH : COLOR_DROP_TIMER;
    }

    /**
     * Runs the state machine and spreads the six types' current shapes into a
     * row list (expiring entries cleaned on the way).
     *
     * <p>Reads {@link PowerupParser}'s four collections and mutates nothing
     * except the expiry cleanup. Decision order is priority:
     * active (timed/instant) -> picked up and gone (not rendered) -> dropped
     * (on-ground countdown) -> undropped; no match, no row - the row simply
     * leaves the HUD.
     *
     * <p><b>Serves in-game rendering only</b>: the editor sample and
     * default-position resolution go through {@link #sampleRows} (the fixed
     * sample). The first line's {@code activePowerups.removeIf(...)} is a
     * write; the editor path must never call it.
     *
     * @param tr the text renderer (same signature as {@link #sampleRows}, passed uniformly by callers; unused here)
     * @param now the current wall-clock ms
     * @return the rows to draw this frame (possibly empty: no type has a displayable status)
     */
    public static List<Row> collectRows(TextRenderer tr, long now) {
        PowerupParser.activePowerups.removeIf(a -> a.getExpireMs() <= now);
        String sep = Text.translatable(KEY_SEP).getString();
        String roundLabel = Text.translatable(KEY_ROUND).getString();
        String undropped = Text.translatable(KEY_UNDROPPED).getString();
        String dropped = Text.translatable(KEY_DROPPED).getString();
        String active = Text.translatable(KEY_ACTIVE).getString();
        String activeInstant = Text.translatable(KEY_ACTIVE_INSTANT).getString();
        List<Row> rows = new ArrayList<>(ORDER.size());

        for (PowerupParser.PowerupType type : ORDER) {
            // --State 1: active (highest priority - the current value the player cares about)--
            PowerupParser.ActivePowerup current = activeFor(type, now);
            if (current != null) {
                if (current.isTimed()) {
                    long leftMs = current.getRemainingMs(now);
                    int seconds = (int) (leftMs / 1000);
                    int color = seconds > URGENT_AMBER_SECONDS ? COLOR_SAFE
                            : (seconds > URGENT_RED_SECONDS ? COLOR_AMBER : COLOR_DANGER);
                    rows.add(new Row(type, statusLine(sep, roundLabel,
                            templated(active, formatSeconds(leftMs), color))));
                } else {
                    rows.add(new Row(type, statusLine(sep, roundLabel,
                            templated(activeInstant, "", COLOR_TEXT))));
                }
                continue;
            }
            // --State 2: picked up this round (effect ended) -> the whole row vanishes--
            // Without this gate, a just-picked instant power-up would fall back to "dropped"
            // for the few frames before the armor stand is recycled, reading as if it dropped
            // again. Gone is gone, no fallback
            if (PowerupParser.pickedUpRound.contains(type)) {
                continue;
            }
            // --State 3: dropped (a same-type armor stand is still on the ground) -> attach its vanish countdown--
            PowerupParser sitting = sittingFor(type);
            if (sitting != null) {
                int seconds = sitting.getOffsetTime() / 20;
                // The countdown is cyan; at <=10s it flips cyan-white each second
                // (cyan-white-cyan-white...) to say "grab it or lose it". The name no longer
                // flashes - it is the time that flashes, not the power-up name
                rows.add(new Row(type, statusLine(sep, roundLabel,
                        templated(dropped, "00:" + String.format("%02d", seconds),
                                colorForDropSeconds(seconds)))));
                continue;
            }
            // --State 4: undropped (due this round per the prediction but not yet landed; only predictable types reach here)--
            PowerupParser inc = incFor(type);
            if (inc != null && !inc.isDropped()) {
                rows.add(new Row(type, statusLine(sep, roundLabel,
                        templated(undropped, "", COLOR_TEXT))));
            }
            // --Anything else (unpredicted, or predicted and already gone) -> no row, it does not exist--
        }
        return rows;
    }

    /**
     * Draws the power-up table each frame: rows come from
     * {@link #collectRows}, names at the component's left edge and status
     * segments right after the name column.
     * Gating = the editor IS_OPEN short-circuit + {@link GlobalConfig.Hud#powerupOn()}
     * (master switch + placed + this element's visibility);
     * unlike the wave HUD, {@code shouldRender} is not read here - row
     * visibility is decided by the switches.
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.powerupOn()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_POWERUP.getDoubleValue();
        // Fetch the row list first: the width/height queries read the same list - same-frame
        // state agrees, so the frame and the drawn text necessarily line up
        List<Row> rows = collectRows(textRenderer, System.currentTimeMillis());
        // Content size measured from the same row list: anchor positioning shares a source with
        // the drawn text, so right-hug keeps the countdown inside the component
        int hudW = visibleSize(rowsWidth(textRenderer, rows), scale);
        int hudH = visibleSize(rowsHeight(textRenderer, rows), scale);
        int absoluteX = anchorPixels(GlobalConfig.getXPowerup(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYPowerup(), screenHeight, hudH, 0);

        drawScaled(context, absoluteX, absoluteY, scale, () -> drawRows(context, absoluteX, absoluteY, rows));
    }

    /** Lays out rows: names always take their type color (no flashing), status segments drawn in order from the name column's right edge. */
    private void drawRows(DrawContext context, int absoluteX, int absoluteY, List<Row> rows) {
        int nameCol = nameColumnWidth(textRenderer);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = absoluteY + textRenderer.fontHeight * i;
            String name = row.type().getColorCode() + powerupName(row.type());
            context.drawTextWithShadow(textRenderer, name, absoluteX, rowY, COLOR_TEXT);
            int x = absoluteX + nameCol;
            for (Segment segment : row.segments()) {
                context.drawTextWithShadow(textRenderer, segment.text(), x, rowY, segment.color());
                x += textRenderer.getWidth(segment.text());
            }
        }
    }

    /** The type's currently active, unexpired entry; {@code null} means the next state gets its turn. */
    private static PowerupParser.ActivePowerup activeFor(PowerupParser.PowerupType type, long now) {
        for (PowerupParser.ActivePowerup a : new ArrayList<>(PowerupParser.activePowerups)) {
            if (a.getPowerupType() == type && a.getExpireMs() > now) {
                return a;
            }
        }
        return null;
    }

    /** The type's prediction entry for this round; {@code null} means the prediction table has no entry, so this round is not its turn. */
    private static PowerupParser incFor(PowerupParser.PowerupType type) {
        for (PowerupParser p : new ArrayList<>(PowerupParser.incPowerups)) {
            if (p.getPowerupType() == type) {
                return p;
            }
        }
        return null;
    }

    /** Among the on-ground armor stands, the entry of this type nearest to vanishing - the dropped row's 00:SS reads it. */
    private static PowerupParser sittingFor(PowerupParser.PowerupType type) {
        PowerupParser best = null;
        for (PowerupParser p : new ArrayList<>(PowerupParser.powerups.values())) {
            if (p.getPowerupType() == type && (best == null || p.getOffsetTime() < best.getOffsetTime())) {
                best = p;
            }
        }
        return best;
    }

    /** Power-up names go through lang keys (PowerupParser.keyFor), Chinese/English following the client language. */
    private static String powerupName(PowerupParser.PowerupType type) {
        return Text.translatable(PowerupParser.keyFor(type)).getString();
    }

    /** ms -> X.X seconds (one decimal); the readout dedicated to active effects' remaining time. */
    private static String formatSeconds(long ms) {
        int seconds = (int) (ms / 1000);
        int tenths = (int) ((ms % 1000) / 100);
        return seconds + "." + tenths;
    }
}