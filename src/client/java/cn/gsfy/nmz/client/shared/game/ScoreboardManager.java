package cn.gsfy.nmz.client.shared.game;

import cn.gsfy.nmz.client.utils.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Scoreboard poller—reads the sidebar once every 5 client ticks, caching the
 * title and the row-by-row content.
 *
 * <p>Always stores the raw data, without Zombies-title filtering: raw content
 * is easier to debug (compare against F3) and lets each feature parse it on
 * demand (round number / total time / team stats); the filter belongs to the
 * consumer, which is more robust.
 *
 * <p><b>It is also the single source of truth for "is the player in a Zombies
 * game right now"</b>: {@code PlayerUtils.computeInZombies()} reads exactly
 * this cache's title and rows. So this class carries an easily overlooked
 * obligation—<b>the cache must be cleared the moment the sidebar
 * disappears</b>. A live cache with no sidebar is lying to the upper layers
 * that "you are still in that game", and the consequence is that every
 * side-effect gated by {@code isInZombies()} (eleven HUDs, chat filter,
 * ESP…) keeps running uselessly—the typical scenario being "after being
 * knocked into Limbo the HUDs don't disappear". All three disappearance
 * shapes must clear: the sidebar objective is {@code null}, the world is
 * {@code null}, and explicit disconnect / game end.
 */
public class ScoreboardManager {

    private static ScoreboardManager instance;
    private String title = "";
    /** The sidebar's raw rows (reverse-ordered, # rows filtered, prefixes/suffixes washed); replaced wholesale on each poll. */
    private final List<String> content = new ArrayList<>();
    private int tick;

    /** The global singleton; {@code null} before {@link #init()}. */
    public static ScoreboardManager get() {
        return instance;
    }

    /** Initializes the singleton and registers the every-5-ticks sidebar poll:
     *  polls only in-game and not singleplayer—singleplayer has no server
     *  sidebar semantics (all sidebar signals come from the Hypixel server),
     *  and scanning would just read the local world's scoreboard, so it is
     *  skipped outright.
     *
     *  <p><b>The cache must be cleared before the early return</b>: this is
     *  not throttling, it is the maintenance of the fact "where is the
     *  current sidebar". When the world is gone (back to the title screen /
     *  world replaced / mid-disconnect), the previous game's sidebar is no
     *  longer "the current sidebar"; leaving it pinned would keep
     *  {@code PlayerUtils.isInZombies()} true forever via the previous game's
     *  Zombies title and Zombies Left row, and the on-screen HUDs would stay
     *  forever too—being knocked into Limbo is exactly how TimeHud / CPS /
     *  RollStats / TeamStats / GlobalOverview stayed on screen.
     */
    public void init() {
        instance = this;
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world == null || client.isInSingleplayer() || client.player == null) {
                clear();
                return;
            }
            if (++tick % 5 == 0) {
                updateScoreboardContent();
            }
        });
    }

    /**
     * Reads the sidebar once: caches the title + the scoreboard rows not
     * starting with {@code #}, reverse-ordered row by row.
     *
     * <p><b>No sidebar → clear, no early return</b>: Hypixel's Limbo / lobby
     * / other minigames do not guarantee a sidebar objective
     * ({@code getObjectiveForSlot(SIDEBAR)} returns {@code null}), and "no
     * sidebar" is exactly one shape of "this game has ended"—an early return
     * would leave the cache holding the previous game's Zombies title and
     * rows, {@code isInZombies()} would stay true forever, and
     * {@code TotalHUDRenderer.shouldRenderHud()}'s single environment gate
     * would pass forever—the eleven HUDs whose content is always non-empty
     * (time / rolls / team stats / global overview / CPS) would stay on
     * screen, and the consumers of the same downstream cache
     * (LanguageUtils's map, DifficultyUtils's difficulty, GameCache's round
     * number) would read the previous game's stale values too.
     *
     * <p>Does clearing misjudge leaving? A "target briefly missing" shorter
     * than about 400ms is absorbed by {@code PlayerUtils}'s
     * "200ms throttle × three consecutive confirmations"—the cache empties
     * but refills fast, and {@code computeInZombies()} never reads an empty
     * value; only a genuinely long absence of the sidebar flips it.
     */
    public void updateScoreboardContent() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            clear();
            return;
        }
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) {
            clear();
            return;
        }
        String newTitle = StringUtils.trim(objective.getDisplayName().getString());

        List<String> newLines = new ArrayList<>();
        Collection<ScoreboardEntry> entries = scoreboard.getScoreboardEntries(objective);
        List<ScoreboardEntry> filtered = new ArrayList<>();
        for (ScoreboardEntry entry : entries) {
            if (entry.name() != null && !entry.name().getString().startsWith("#")) {
                filtered.add(entry);
            }
        }
        Collections.reverse(filtered);
        for (ScoreboardEntry entry : filtered) {
            Team team = scoreboard.getScoreHolderTeam(entry.name().getString());
            String prefix = team != null ? team.getPrefix().getString() : "";
            String suffix = team != null ? team.getSuffix().getString() : "";
            newLines.add(StringUtils.trim(prefix + entry.name().getString() + suffix));
        }
        this.title = newTitle;
        this.content.clear();
        this.content.addAll(newLines);
    }

    /**
     * Clears the title and content cache (called when the sidebar disappears
     * / game ends / disconnects; the next game re-polls).
     *
     * <p><b>It is the single landing point of the fact "left the game"</b>:
     * {@code PlayerUtils.isInZombies()} reads this cache directly, so when
     * the cache clears is exactly when the player is judged no longer in a
     * Zombies game—hence the call sites cover all three disappearance shapes:
     * the sidebar objective gone, the world gone, and explicit disconnect
     * ({@code NoMoreZombiesClient}) and game end ({@code GameEventBus}).
     */
    public void clear() {
        this.title = "";
        this.content.clear();
    }

    /** The sidebar title as is (multi-language, unwashed; round number / map identification decide directly here). */
    public String getTitle() {
        return title;
    }

    /**
     * Reads one cached sidebar row: round number / total time / difficulty
     * and other parsers read by row number; out-of-range returns an empty
     * string, so callers need no null check.
     *
     * @param row the sidebar row number, starting at 1
     * @return the cached row; an empty string when out of range or the cache is empty
     */
    public String getContent(int row) {
        if (row < 1 || row > content.size()) {
            return "";
        }
        return content.get(row - 1);
    }

    /** The sidebar's current row count (starting at 1); 0 when not yet polled or cleared by {@link #clear()}. */
    public int getSize() {
        return content.size();
    }
}