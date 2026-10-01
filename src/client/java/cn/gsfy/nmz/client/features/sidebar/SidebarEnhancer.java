package cn.gsfy.nmz.client.features.sidebar;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sidebar line enhancement - tidies the vanilla scoreboard rows already
 * covered by dedicated HUDs: removes player rows (the team stats HUD shows
 * them), and strips the duplicated segment of the vanilla time row according
 * to the "team stats HUD / time HUD" binary state. Waves / next wave belong
 * to the wave HUD ported from ShowSpawnTime; the sidebar no longer repeats
 * them.
 *
 * <p>Currently {@code InGameHudMixin} cancels the vanilla path at HEAD inside
 * a Zombies game and reroutes to {@link ScoreboardHudRenderer}, so
 * {@link #filterSidebar} can actually only be reached from the un-cancelled
 * vanilla path; that path is outside a game, and the method's Zombies gate
 * passes it through untouched.
 * The real in-game filtering is done by {@link ScoreboardHudRenderer} calling
 * {@link #filterLine(String, Set, boolean, boolean)}.
 */
public final class SidebarEnhancer {

    /**
     * The vanilla game-time row ("Time:M:SS Kills:N" / "时间:M:SS Kills:N"),
     * with the Kills segment always English. The time value accepts both
     * full/half-width colons (M:SS or H:MM:SS), so player rows like
     * "Time:1200" are never harmed.
     */
    private static final Pattern TIME_LINE = Pattern.compile("^(Time|时间|時間)\\s*[:：]\\s*\\d+[:：]\\d{2}");

    /**
     * The Kills segment inside a time row ("Kills:N"), codes-stripped, for
     * fallback matching: case-insensitive, thousands separators allowed.
     */
    private static final Pattern KILLS_LINE = Pattern.compile("(?i)Kills\\s*[:：]\\s*[\\d,]+");

    /**
     * The Kills segment's start (raw text; codes may sit between the word's
     * letters and before the colon, e.g. the English {@code K§j§fills:}).
     */
    private static final Pattern KILLS_START = Pattern.compile(
            "(?i)(?:§[0-9a-zA-Z])*" + codeTolerant("Kills") + "(?:§[0-9a-zA-Z])*\\s*[:：]");

    /** The Kills segment whole (raw text, codes kept - including the {@code §a} before the digits; the green depends on it). */
    private static final Pattern KILLS_LINE_RAW = Pattern.compile(
            "(?i)(?:§[0-9a-zA-Z])*" + codeTolerant("Kills") + "(?:§[0-9a-zA-Z])*\\s*[:：]\\s*"
                    + "(?:§[0-9a-zA-Z])*[\\d,]+");

    /**
     * Builds a regex fragment allowing any § code between the letters.
     *
     * <p>Hypixel's anti-scrape stuffs invalid codes into the <b>middle</b> of
     * words, at different spots in each language: English measures
     * {@code §fK§j§fills:} (the code between K and ills), Chinese
     * {@code §fKill§j§fs:} (between Kill and s). A pattern allowing only
     * "Kill contiguous, code after it" matches the Chinese but not the
     * English, falling into the codes-stripped fallback - there the text has
     * had every § code washed by {@code StringUtils.trim}, losing even the
     * green of {@code §a4}, and the symptom is "the number never turns green".
     */
    private static String codeTolerant(String word) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            if (i > 0) {
                builder.append("(?:§[0-9a-zA-Z])*");
            }
            // Quote per character: codes can sit between any two letters, so only single
            // characters are safe to quote
            builder.append(Pattern.quote(String.valueOf(word.charAt(i))));
        }
        return builder.toString();
    }

    /**
     * Called by InGameHudMixin's @ModifyArg; returns the enhanced line text.
     *
     * <p>Currently a no-op holding the entry, sharing {@link #filterSidebar}'s
     * fate: InGameHudMixin's gate cancels vanilla rendering inside a Zombies
     * game, so this hook never runs in-game; only if the gate someday lets the
     * vanilla path through does it have work again.
     */
    public static Text enhanceLine(Text line) {
        return line;
    }

    /** Whether a row is the vanilla game-time row: hidden by the binary logic while the time HUD is on. */
    public static boolean isTimeRow(String display) {
        String t = StringUtils.trim(display);
        return !t.isEmpty() && TIME_LINE.matcher(t).find();
    }

    /**
     * Decides the vanilla time row's display by the "team stats HUD / time
     * HUD" binary state machine (the trick: remove the original row, then
     * re-insert an overwrite in the original format):
     * <pre>
     *   team stats   time HUD    time row shows
     *   off          off         keep the vanilla whole row "time: M:SS Kills: N"
     *   off          on          "Kills: X"          (the duration belongs to the time HUD)
     *   on           off         "time: M:SS"        (the kills belong to the team stats HUD)
     *   on           on          whole row removed   (both segments belong to their HUDs)
     * </pre>
     *
     * @return {@code null} = remove the whole row; {@code ""} = no overwrite (keep the original); anything else = overwrite the row with that text
     */
    public static String timeRowOverride(String raw, boolean teamStatsOn, boolean gameTimeOn) {
        if (!isTimeRow(raw)) {
            return "";
        }
        if (teamStatsOn && gameTimeOn) {
            return null;
        }
        if (teamStatsOn) {
            return timeOnly(raw);
        }
        if (gameTimeOn) {
            return killsOnly(raw);
        }
        return "";
    }

    /** Keeps only the time segment of a time row (hides the Kills segment, codes kept); non-time rows return unchanged. */
    public static String timeOnly(String raw) {
        if (!isTimeRow(raw)) {
            return raw;
        }
        Matcher m = KILLS_START.matcher(raw);
        if (m.find()) {
            return trimCodes(raw.substring(0, m.start()));
        }
        // Fallback: truncate on the codes-stripped text
        String stripped = StringUtils.trim(raw);
        int end = stripped.toLowerCase(Locale.ROOT).indexOf("kills");
        return end >= 0 ? stripped.substring(0, end).trim() : stripped;
    }

    /** Keeps only the Kills segment of a time row (hides the time segment, codes kept); non-time rows return unchanged. */
    public static String killsOnly(String raw) {
        if (!isTimeRow(raw)) {
            return raw;
        }
        Matcher m = KILLS_LINE_RAW.matcher(raw);
        if (m.find()) {
            return trimCodes(m.group());
        }
        // Fallback: match on the codes-stripped text
        Matcher m2 = KILLS_LINE.matcher(StringUtils.trim(raw));
        return m2.find() ? m2.group() : StringUtils.trim(raw);
    }

    /** Strips trailing whitespace and dangling codes (in-line colors kept). */
    private static String trimCodes(String s) {
        return s.replaceAll("(?:§[0-9a-zA-Z]|\\s)+$", "");
    }

    /**
     * Whether the team stats HUD is active - delegates directly to
     * {@link GlobalConfig.Hud#teamStatsOn()} (master switch {@code &&} placed
     * {@code &&} this HUD's visibility).
     *
     * <p>A method because both the in-game self-drawing and the row-level
     * convenience overloads and the offline sample must judge by the same
     * accounting: {@link #filterLine(String, Set)}'s render filtering and
     * {@code ScoreboardHudRenderer}'s offline sample.
     *
     * <p><b>"Placed" is included on purpose</b>: the sidebar gives its player
     * rows / time segment to the team stats on the premise that the team
     * stats is really on screen; eating the sidebar's rows with nothing on the
     * canvas reads as "information vanished into thin air" with nothing taking
     * over.
     */
    public static boolean teamStatsOn() {
        return GlobalConfig.Hud.teamStatsOn();
    }

    /**
     * Whether the time HUD is active - same source and reasoning as
     * {@link #teamStatsOn()}, delegating to {@link GlobalConfig.Hud#gameTimeOn()}.
     */
    public static boolean gameTimeOn() {
        return GlobalConfig.Hud.gameTimeOn();
    }

    /**
     * Row-level filtering - the <b>single decision core</b> of sidebar
     * filtering. Real rendering and the editor's offline sample share this one
     * logic, so the row set seen in the editor matches the game verbatim (no
     * two approximate implementations drifting).
     *
     * <p>Returns {@code null} = remove the whole row; a text return = the
     * row's final display content (same as {@code raw} means keep as is).
     * <b>No in-game check on purpose</b>: in-game self-drawing calls here
     * directly from {@code ScoreboardHudRenderer}, while the offline sample
     * also needs to "pretend to be in-game" and filter by config; the
     * compatibility entry {@link #filterSidebar} handles the out-of-game early
     * return itself - leaving the scenario gate to the caller keeps previews
     * working.
     *
     * <p>The two switches are passed explicitly (not read from config inside)
     * so the HUD editor can feed in the <b>unsaved workspace</b> visibility
     * state - unchecking the "time HUD" must update the sample immediately,
     * not after Save.
     *
     * @param raw the row's original text (with § codes, unwashed)
     * @param knownPlayers the known player-name set (for the player-row test);
     *  {@code null} skips the roster test
     * @param teamStatsOn whether the team stats HUD is active
     * @param gameTimeOn whether the time HUD is active
     */
    public static String filterLine(String raw, Set<String> knownPlayers,
                                    boolean teamStatsOn, boolean gameTimeOn) {
        if (!teamStatsOn && !gameTimeOn) {
            return raw;
        }
        String display = StringUtils.trim(raw);
        if (teamStatsOn) {
            // Empty rows (Hypixel filler) and player rows are both removed
            if (display.isEmpty()) {
                return null;
            }
            if (isPlayerRow(display)) {
                return null;
            }
            // Known player names skip directly (covers downed/dead/left and any other state)
            if (knownPlayers != null) {
                int colon = display.indexOf(':');
                int colonZh = display.indexOf('：');
                int idx = colon >= 0 ? colon : colonZh;
                if (idx > 0 && knownPlayers.contains(display.substring(0, idx).trim())) {
                    return null;
                }
            }
        }
        if (isTimeRow(display)) {
            // The vanilla time row (time segment + Kills segment on one line) goes through the
            // state machine, three outcomes:
            // (null = remove the row / "" = no overwrite / other = overwrite with that text,
            // keeping vanilla codes)
            String override = timeRowOverride(raw, teamStatsOn, gameTimeOn);
            if (override == null) {
                return null;
            }
            if (!override.isEmpty()) {
                return override;
            }
        }
        return raw;
    }

    /**
     * Judges rows by the live config (the render path): the two switches come
     * from {@link #teamStatsOn()}/{@link #gameTimeOn()}.
     */
    public static String filterLine(String raw, Set<String> knownPlayers) {
        return filterLine(raw, knownPlayers, teamStatsOn(), gameTimeOn());
    }

    /**
     * {@link #filterLine(String, Set)}'s convenience overload (the render
     * path): fetches the known player names itself.
     */
    public static String filterLine(String raw) {
        return filterLine(raw, knownPlayerNames());
    }

    /**
     * Filters the rows to render out of the vanilla scoreboard - the
     * compatibility/fallback entry used when the HEAD gate does not cancel:
     * <ul>
     *  <li>with the team stats HUD on, removes empty and player rows;</li>
     *  <li>the vanilla time row keeps the whole row, splits out a segment, or
     *  is removed entirely by the two HUD switches</li>
     * </ul>
     *
     * <p>Under the current {@code InGameHudMixin} gate, vanilla rendering is
     * always cancelled at HEAD inside a Zombies game and rerouted to
     * {@link ScoreboardHudRenderer}, so on the existing call chain this is
     * only reached out of game and returns {@code entries} unchanged. In-game
     * self-drawing and the editor sample both reuse {@link #filterLine}
     * directly, not this method. Only if the gate someday lets the vanilla
     * path through does the filtering below actually run inside a Zombies
     * game.
     *
     * <p>Row decisions all delegate to {@link #filterLine}; this only handles
     * "the out-of-game early return" and "packing results back into entries":
     * a kept entry keeps its team prefix/suffix decoration; only overwritten
     * rows swap to a team-less synthetic owner.
     */
    public static Collection<ScoreboardEntry> filterSidebar(Scoreboard scoreboard, ScoreboardObjective objective,
                                                            Collection<ScoreboardEntry> entries) {
        // Not in Zombies: not one character of another server's sidebar may be touched
        if (!PlayerUtils.isInZombies()) {
            return entries;
        }
        Set<String> knownPlayers = knownPlayerNames();
        List<ScoreboardEntry> filtered = new ArrayList<>();
        for (ScoreboardEntry entry : entries) {
            Team team = scoreboard.getScoreHolderTeam(entry.name().getString());
            String prefix = team != null ? team.getPrefix().getString() : "";
            String suffix = team != null ? team.getSuffix().getString() : "";
            String raw = prefix + entry.name().getString() + suffix;
            String result = filterLine(raw, knownPlayers);
            if (result == null) {
                continue;
            }
            if (result.equals(raw)) {
                filtered.add(entry); // kept as is: the original entry stays, team decoration intact
            } else {
                filtered.add(replaceDisplay(entry, result)); // overwritten: swap the owner
            }
        }
        return filtered;
    }

    /**
     * Builds a new entry with the same value/format but the row text replaced
     * by newDisplay (for time-row segment stripping). The owner must be
     * swapped to a team-less one:
     * Hypixel splits each row's visible text across the team's prefix/suffix,
     * and at render time InGameHud.method_55439 wraps the name with the team
     * (prefix+name+suffix). Keeping the original owner would wrap the override
     * back with the vanilla prefix/suffix, stacking "vanilla time/kills +
     * override" twice.
     * A nonexistent owner -> getScoreHolderTeam returns null
     * -> decorateName returns the override untouched.
     */
    public static ScoreboardEntry replaceDisplay(ScoreboardEntry entry, String newDisplay) {
        return new ScoreboardEntry("nmz_replace_" + entry.owner(), entry.value(), Text.literal(newDisplay),
                entry.numberFormatOverride());
    }

    /** The sidebar gains no prefix text, so the x compensation is always 0 (the entry stays for the mixin to call). */
    public static int getAddedWidth(net.minecraft.client.font.TextRenderer tr) {
        return 0;
    }

    /**
     * Whether a row is a player row (for removal from the vanilla scoreboard):
     * 1. the player name is in the known world-player list;
     * 2. an ASCII username + a numeric/status value (covers left and the like).
     */
    public static boolean isPlayerRow(String display) {
        int colon = display.indexOf(':');
        int colonZh = display.indexOf('：');
        if (colon < 0 && colonZh < 0) {
            return false;
        }
        int idx = (colon >= 0 && (colonZh < 0 || colon < colonZh)) ? colon : colonZh;
        if (idx <= 0) {
            return false;
        }
        String namePart = display.substring(0, idx).trim();
        String valuePart = display.substring(idx + 1).trim();
        // The kills row is not a player row
        if (namePart.equals("Kills") || namePart.equals("Kill") || namePart.equals("击杀") || namePart.equals("杀敌")) {
            return false;
        }
        // Known world player -> player row
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (PlayerEntity p : client.world.getPlayers()) {
                if (namePart.equals(p.getGameProfile().getName())) {
                    return true;
                }
            }
        }
        // Heuristic: ASCII username + numeric/status value
        if (!namePart.matches("[A-Za-z0-9_]{1,16}")) {
            return false;
        }
        if (valuePart.isEmpty()) {
            return false;
        }
        return isNumeric(valuePart) || isStatusWord(valuePart);
    }

    /**
     * The known player-name set (for filtering): world players + the team
     * stats roster. Must include left/dead players - they have no entities,
     * and the world entity list alone cannot identify their scoreboard rows.
     */
    public static Set<String> knownPlayerNames() {
        Set<String> names = new HashSet<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (PlayerEntity p : client.world.getPlayers()) {
                names.add(p.getGameProfile().getName());
            }
        }
        names.addAll(TeamStats.getPlayers().keySet());
        return names;
    }

    /** Whether the value segment is purely numeric (verified per character after stripping thousands commas) -
     *  the numeric half-step of the player-row heuristic. */
    private static boolean isNumeric(String s) {
        for (char c : s.replace(",", "").toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the value segment is a left/downed/dead status word (Chinese and English) -
     *  the fallback half-step of the player-row heuristic. {@code quit} is the English leave word
     *  actually captured (a sidebar row like {@code §7Maur0ccvp§f: §t§cQUIT});
     *  without it that row would depend entirely on the roster, and the offline sample has no
     *  real roster - hence the word list. */
    private static boolean isStatusWord(String s) {
        return s.contains("退出") || s.contains("倒地") || s.contains("死亡")
                || s.toLowerCase().contains("left") || s.toLowerCase().contains("downed")
                || s.toLowerCase().contains("knocked") || s.toLowerCase().contains("dead")
                || s.toLowerCase().contains("died") || s.toLowerCase().contains("quit");
    }

    private SidebarEnhancer() {
    }
}