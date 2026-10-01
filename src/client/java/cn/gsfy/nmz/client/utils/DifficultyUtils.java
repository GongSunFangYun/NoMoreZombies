package cn.gsfy.nmz.client.utils;

import cn.gsfy.nmz.client.data.model.DifficultyId;
import cn.gsfy.nmz.client.shared.game.ScoreboardManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Current game difficulty identification (Normal/Hard/RIP)—the scoreboard
 * sidebar is the only source.
 *
 * <p>Same shape as {@link LanguageUtils#getMap()}: the sidebar text is polled
 * every 5 ticks by {@link ScoreboardManager}, this class only decides the
 * difficulty row's value and caches the result until the game ends; the
 * cache invalidation follows the map cache
 * ({@link LanguageUtils#invalidateMapCache()} calls it along the way), and
 * disconnect / a new game re-identifies automatically.
 *
 * <p>Measured format: the scoreboard has a fixed row
 * {@code Difficulty: <value>} (Hypixel's i18n only localizes the value; the
 * label is always English). Values—English clients Normal/Hard/RIP; Chinese
 * clients 普通模式/Hard/安息. Note that the Chinese Normal carries a "模式"
 * suffix while RIP does not; the name table is written by stem, so do not pin
 * down the whole string.
 *
 * <p>The scan anchors on the "Difficulty" label: the sidebar's other rows are
 * player names / area names / website text—free text—and without the anchor
 * a word like "Hard" would be read as a difficulty.
 *
 * <p>An unrecognizable window (outside a game, the sidebar has not written
 * the difficulty row yet) returns {@link DifficultyId#NULL} and does not
 * cache; the next call retries. The sidebar fingerprint (title + rows) only
 * serves to skip repeated scans on an unchanged window.
 * This class logs nothing.
 */
public final class DifficultyUtils {

    /** Difficulty row label: both Chinese and English clients use the English
     *  label "Difficulty"; only the value is localized. */
    private static final String LABEL = "difficulty";

    /** Difficulty detection order: testing the most specific tier first is
     *  the most robust (RIP→Hard→Normal); it pairs one-to-one by index with
     *  {@link #DIFFICULTY_NAMES}. */
    private static final DifficultyId[] DIFFICULTY_ORDER = {
            DifficultyId.RIP, DifficultyId.HARD, DifficultyId.NORMAL
    };
    /** Difficulty name table: the i-th row's name hitting means the
     *  {@link #DIFFICULTY_ORDER} i-th tier.
     *  Chinese names are written by stem: 普通⊂普通模式, while RIP's Chinese
     *  name "安息" carries no suffix—pinning the suffix would drop one of
     *  them. Hard is not translated on the Chinese client either and has
     *  only one variant. After checking the word lists, the three are
     *  mutually exclusive; under stem / word-boundary matching, the Normal
     *  and Hard stems cannot swallow RIP. */
    private static final String[][] DIFFICULTY_NAMES = {
            {"RIP", "安息"},
            {"Hard"},
            {"Normal", "普通"}
    };

    /** The authoritative cache: the identified difficulty; not recomputed
     *  within a game. */
    private static DifficultyId cachedDifficulty = DifficultyId.NULL;
    private static boolean cacheValid = false;
    /** The last scan's sidebar fingerprint and result: an unchanged content
     *  skips the rescan—the render loop asks once per frame. */
    private static String lastScanFingerprint = null;
    private static DifficultyId lastScanResult = DifficultyId.NULL;
    /** Latin word-boundary regex cache (key = name): compiled once and reused,
     *  not per frame. */
    private static final Map<String, Pattern> WORD_PATTERNS = new HashMap<>();

    /**
     * Returns the current difficulty—scans the scoreboard Difficulty row
     * only inside a Zombies game; on success the result is cached until the
     * map cache invalidates; when unidentified it returns
     * {@link DifficultyId#NULL} without caching, and later calls retry once
     * the sidebar content changes.
     *
     * @return the identified difficulty, or {@link DifficultyId#NULL} when
     *  unidentified; never {@code null}
     */
    public static DifficultyId getDifficulty() {
        if (cacheValid) {
            return cachedDifficulty;
        }
        DifficultyId scoreboard = detectByScoreboard();
        if (scoreboard != DifficultyId.NULL) {
            cachedDifficulty = scoreboard;
            cacheValid = true;
        }
        return scoreboard;
    }

    /** Invalidates the difficulty cache: on disconnect / a new game it
     *  invalidates along with the map cache (called by
     *  {@link LanguageUtils#invalidateMapCache()}). */
    public static void invalidateCache() {
        cachedDifficulty = DifficultyId.NULL;
        cacheValid = false;
        lastScanFingerprint = null;
        lastScanResult = DifficultyId.NULL;
    }

    // ----Sidebar detection (the only source)----

    /** Scans the sidebar (rows then title) for the difficulty row: outside a
     *  game it does not scan, so another server's sidebar is not mistaken for
     *  this game's difficulty. */
    private static DifficultyId detectByScoreboard() {
        if (!PlayerUtils.isInZombies()) {
            return DifficultyId.NULL;
        }
        ScoreboardManager sb = ScoreboardManager.get();
        if (sb == null) {
            return DifficultyId.NULL;
        }
        String fingerprint = fingerprint(sb);
        if (fingerprint.equals(lastScanFingerprint)) {
            return lastScanResult;
        }
        lastScanFingerprint = fingerprint;

        for (int row = 1; row <= sb.getSize(); row++) {
            String line = sb.getContent(row);
            DifficultyId hit = matchDifficultyLine(line);
            if (hit != DifficultyId.NULL) {
                lastScanResult = hit;
                return lastScanResult;
            }
        }
        // Only when the rows miss does it fall back to the title; the result
        // is recorded into lastScanResult too (later same-kind windows reuse
        // it directly)
        lastScanResult = matchDifficultyLine(sb.getTitle());
        return lastScanResult;
    }

    /** Single-line difficulty match: first recognize the "Difficulty" label
     *  (the sidebar's other rows are player names / area names / free text,
     *  and without the label anchor it would misjudge), then look up the
     *  difficulty name by {@link #DIFFICULTY_ORDER}. Latin names match on
     *  word boundaries, case-insensitive (so "rip" cannot hit "strip");
     *  Chinese names match by stem containment. */
    private static DifficultyId matchDifficultyLine(String line) {
        if (line == null || line.isEmpty() || !hasLabel(line)) {
            return DifficultyId.NULL;
        }
        for (int i = 0; i < DIFFICULTY_NAMES.length; i++) {
            for (String name : DIFFICULTY_NAMES[i]) {
                boolean hit = isLatin(name) ? matchesWord(line, name) : line.contains(name);
                if (hit) {
                    return DIFFICULTY_ORDER[i];
                }
            }
        }
        return DifficultyId.NULL;
    }

    /** Whether the line has the difficulty label (case-insensitive). */
    private static boolean hasLabel(String line) {
        return line.toLowerCase(Locale.ROOT).contains(LABEL);
    }

    /** Latin word match on word boundaries (case-insensitive); Chinese names
     *  (containing non-ASCII) go through containment at the caller. */
    private static boolean matchesWord(String line, String name) {
        Pattern pattern = WORD_PATTERNS.computeIfAbsent(name, ignored -> Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        return pattern.matcher(line).find();
    }

    /** Whether the name is pure ASCII (Latin): decides word-boundary vs
     *  containment matching. */
    private static boolean isLatin(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** The sidebar content fingerprint (title + rows): checks "is it the same
     *  as the last scan". */
    private static String fingerprint(ScoreboardManager sb) {
        StringBuilder builder = new StringBuilder(sb.getTitle());
        for (int row = 1; row <= sb.getSize(); row++) {
            builder.append('\n').append(sb.getContent(row));
        }
        return builder.toString();
    }

    private DifficultyUtils() {
    }
}