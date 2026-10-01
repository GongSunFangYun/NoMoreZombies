package cn.gsfy.nmz.client.shared.game;

import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.features.rolls.RollStats;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This game's data cache (the fallback) - writes team stats HUD data plus
 * game/round durations in real time to {@code %temp%/nmz_gamecache.json},
 * specifically to survive rejoining a game in progress / crash restarts.
 *
 * <p>In normal play the HUD follows the in-memory state (the total duration is
 * additionally calibrated by the scoreboard's whole-second value); this file
 * is read exactly once when rejoining a game in progress, for temporary
 * recovery: at game end the whole team's totals can be reviewed and the
 * durations resume from the breakpoint.
 * A new round's {@code resetStatuses} goes through the game's forced sync,
 * after which cached data is overwritten and takes no part in normal
 * rendering.
 *
 * <p>Read-before-write is this fallback's only ordering constraint: when
 * rejoining a game in progress, before the round title appears, every tick's
 * {@link #tick()} would overwrite the file with "empty post-rejoin data", so
 * {@link #capture()} must, at the entry instant, first read the old game's
 * last complete write into an in-memory snapshot; afterwards
 * {@link #restore(int)} and the rejoin probe recognize only that snapshot and
 * never re-read the file - with the order reversed, "total duration = 0,
 * gold = 0" polluted data would be restored as this game's data.
 *
 * <p>Lifecycle: written every 1s in-game (only when a roster exists or rolls
 * happened); a new game (round==1) deletes it via {@link #reset()}; on rejoin
 * {@link #restore(int)} uses the entry-instant memory snapshot from
 * {@link #capture()} - expired ({@link #MAX_AGE_MS}) or round-regressed (no
 * longer the same game) snapshots are discarded.
 *
 * <p>The {@code rolls} section (see {@link RollStats#encode()}) shares the
 * team data's lifecycle and staleness criteria: the roll stats are a pure
 * client-side accumulator with no file of their own and would reset on an
 * accidental exit - riding this snapshot is their only fallback.
 */
public final class GameCache {

    /** Cache file path: the system temp directory (%temp%). */
    private static final Path FILE = Paths.get(System.getProperty("java.io.tmpdir"), "nmz_gamecache.json");
    /** The cache's max valid age (ms): on rejoin, older than this counts as cross-session and is discarded (keeps the previous game's data out of the new one). */
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    /** Write throttle (ticks): once every 20 ticks (1s) - the crash-recovery granularity; 1s is plenty and disk cost is negligible. */
    private static final int WRITE_INTERVAL_TICKS = 20;

    private static final Gson GSON = new Gson();
    private static int ticksSinceWrite = 0;
    /**
     * The in-memory snapshot read from disk at the instant of entering a
     * Zombies game: {@link #restore(int)} and the rejoin probe prefer it.
     * The snapshot takes the old game's last complete write before the
     * overwrite window - why not read the file directly is in the class
     * comment.
     */
    private static Dto snapshot;
    /** Whether this "entered the Zombies world" already captured a snapshot (leave -> entry captures once, so in-world transient flickers never re-read the already-overwritten file). */
    private static boolean capturedThisEntry;
    /** Whether this game already ran a cache restore (shared by the round-title restore and the entry probe, so scoreboard-corrected values are not overwritten twice). */
    private static boolean restoreDone;
    /** The rejoin probe's attempt count: at the entry instant the scoreboard may not have refreshed to the Zombies game; retry a few times before giving up. */
    private static int rejoinProbeCount;
    /** The rejoin probe's max retries (3 ticks each, within about 1s). */
    private static final int REJOIN_PROBE_MAX = 6;

    private GameCache() {
    }

    /** Called each tick (in a Zombies game only): once the throttle interval is met and there is roster data, writes this game's data to disk. */
    public static void tick() {
        ticksSinceWrite++;
        if (ticksSinceWrite < WRITE_INTERVAL_TICKS) {
            return;
        }
        ticksSinceWrite = 0;
        // Empty roster but rolls happened still writes: in spectate / all-teammates-left states
        // the roster can be empty while roll stats still change
        if (TeamStats.getPlayers().isEmpty() && RollStats.isEmpty()) {
            return;
        }
        write();
    }

    /** Called by {@link #tick()} every 20 ticks (1s): assembles one Dto frame and overwrites the file;
     *  write failures stay silent (the fallback is not on the critical path and must not affect the game). */
    private static void write() {
        try {
            Dto dto = new Dto();
            dto.writtenAt = System.currentTimeMillis();
            dto.round = CheckSpawnTimes.get().getCurrentRound();
            dto.totalGameTickMs = GameTickHandler.get().getTotalGameTick();
            dto.gameTickMs = GameTickHandler.get().getGameTick();
            for (Map.Entry<String, TeamStats.PlayerStats> e : TeamStats.getPlayers().entrySet()) {
                TeamStats.PlayerStats s = e.getValue();
                PlayerDto p = new PlayerDto();
                p.status = s.status.name();
                p.health = s.health;
                p.gold = s.gold;
                p.kills = s.kills;
                p.downed = s.downed;
                p.deaths = s.deaths;
                dto.players.put(e.getKey(), p);
            }
            dto.rolls = RollStats.encode();
            Files.writeString(FILE, GSON.toJson(dto), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Cache write failures stay silent: the fallback is not on the critical path
        }
    }

    /**
     * Called at the instant of entering a Zombies game (the false -> true
     * edge, polled each tick): reads the disk cache into the in-memory
     * snapshot for {@link #restore(int)} and the rejoin probe.
     * Must run before {@link #tick()}'s first overwrite - see the class
     * comment's "read before write".
     * After leaving ({@link #onLeaveZombies()}) it re-arms and captures again
     * on the next entry.
     */
    public static void capture() {
        if (capturedThisEntry) {
            return;
        }
        capturedThisEntry = true;
        snapshot = null;
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            Dto dto = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Dto.class);
            if (dto != null && dto.players != null) {
                snapshot = dto;
            }
            // A valid snapshot starts the rejoin probe: why it exists and how it excludes the
            // round-title restore is in tryRestoreOnRejoin(). "Something to restore" checks both
            // sections: a non-empty roster or non-empty roll stats either way (a rolls-only game
            // with an empty roster also deserves the fallback)
            if (snapshot != null && !isEmptySnapshot(snapshot) && !restoreDone
                    && DelayedTaskScheduler.get() != null) {
                rejoinProbeCount = 0;
                tryRestoreOnRejoin();
            }
        } catch (Exception e) {
            // Read failures stay silent: the snapshot is only a fallback
        }
    }

    /** Whether the snapshot has nothing recoverable in either section (roster and roll stats both empty). */
    private static boolean isEmptySnapshot(Dto dto) {
        boolean noPlayers = dto.players == null || dto.players.isEmpty();
        boolean noRolls = dto.rolls == null || dto.rolls.items == null || dto.rolls.items.isEmpty();
        return noPlayers && noRolls;
    }

    /** Called when leaving Zombies (back to lobby / world switch): resets this entry's capture state so the next entry captures again;
     *  also clears the restore-done flag and the probe count - re-armed for the next entry (rejoining the same game can restore again). */
    public static void onLeaveZombies() {
        capturedThisEntry = false;
        snapshot = null;
        restoreDone = false;
        rejoinProbeCount = 0;
    }

    /**
     * Called when the local player rejoins a game in progress: restores this
     * game's cumulative data and round timing from the entry-instant memory
     * snapshot.
     * Must be called after this round's timing was zeroed (GameEventBus calls
     * it after {@code startOrSplit()} clears gameTick), so the round-duration
     * restore takes effect afterwards.
     * The total duration is not restored; it waits for the scoreboard's Time
     * row to recalibrate.
     * With no snapshot (no valid cache file at entry) it returns directly,
     * never re-reading the file - the reason is in the class comment.
     *
     * @param joinedRound the round number at rejoin (for the "round regressed = crossed games" test)
     */
    public static void restore(int joinedRound) {
        Dto dto = snapshot;
        if (dto == null || dto.players == null) {
            return;
        }
        // The entry probe already restored this game's data: skip, so the scoreboard-corrected
        // gold is not overwritten back to the cache's stale value
        if (restoreDone) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long ageMs = now - dto.writtenAt;
            if (ageMs > MAX_AGE_MS) {
                return; // expired: treated as cross-session; old game data is not restored
            }
            if (dto.round > 0 && joinedRound < dto.round) {
                return; // round regressed: no longer the same game (a new game restarts low)
            }
            doRestore(dto, joinedRound);
        } catch (Exception e) {
            // Restore exceptions stay silent: the fallback is not on the critical path
        }
    }

    /**
     * The rejoin probe: when the local player rejoins a game in progress and
     * the game is already mid-round (no new round title), the round-title
     * restore would never fire.
     * After capture reads a valid snapshot, this probe delays and polls the
     * scoreboard, and on "no round title seen this game
     * {@code &&} the scoreboard already shows a Zombies round" restores player
     * data and round timing directly.
     * A normal new game / same game (a round==1 title already arrived ->
     * setLastRoundSeen) short-circuits the probe, leaving the round-title
     * restore in charge.
     * isTotalStarted() is not used as the test: the scoreboard Time row's
     * calibration sets totalStarted true (syncTotalTimeFromScoreboard), and on
     * rejoin it is true too - indistinguishable from "rejoined a game in
     * progress".
     */
    private static void tryRestoreOnRejoin() {
        Dto dto = snapshot;
        if (dto == null || dto.players == null || isEmptySnapshot(dto)) {
            return;
        }
        if (restoreDone) {
            return;
        }
        // A round title was already seen this game (lastRoundSeen resets to 0 on a world-null
        // rejoin): the round-title restore has taken over or is about to; the probe keeps out
        if (GameTickHandler.get().getLastRoundSeen() != 0) {
            return;
        }
        int sbRound = roundFromScoreboard();
        if (sbRound < 1) {
            // The scoreboard has not refreshed to the Zombies game yet (at the entry instant it
            // may still hold pre-leave / lobby data) -> retry after a short
            // interval (3 ticks = 150ms; the scoreboard polls every 5 ticks, so the retry window
            // must cover one refresh)
            if (++rejoinProbeCount <= REJOIN_PROBE_MAX) {
                DelayedTaskScheduler.get().runTaskLater(3, GameCache::tryRestoreOnRejoin);
            }
            return;
        }
        long ageMs = System.currentTimeMillis() - dto.writtenAt;
        if (ageMs > MAX_AGE_MS) {
            return;
        }
        if (dto.round > 0 && sbRound < dto.round) {
            return; // round regressed: the cache is the previous game's data, games crossed
        }
        doRestore(dto, sbRound);
    }

    /** Parses the round number from the current sidebar ("Round N" / "第N回合" row); 0 when unrecognized. */
    private static int roundFromScoreboard() {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return 0;
        }
        for (int i = 1; i <= sm.getSize(); i++) {
            String line = sm.getContent(i);
            if (line == null || line.isEmpty()) {
                continue;
            }
            int r = LanguageUtils.getRoundNumber(line);
            if (r > 0) {
                return r;
            }
        }
        return 0;
    }

    /**
     * Restores the snapshot data (players + round timing) and marks this game
     * restored. Shared by both triggers: the round-title {@link #restore(int)}
     * and the entry probe {@link #tryRestoreOnRejoin()}, with restoreDone
     * guarding against doubles.
     *
     * @param dto the in-memory snapshot
     * @param currentRound the current round number (title scenario = the title's round; probe scenario = the scoreboard's round),
     *  used for the "same round only restores gameTick" test
     */
    private static void doRestore(Dto dto, int currentRound) {
        restoreDone = true;
        // Restore round timing: the total duration is not restored (recalibrated by the
        // scoreboard's Time row, see restoreTimers javadoc).
        // The round duration restores only while still in the cached round (otherwise that
        // round has ended and does not apply)
        GameTickHandler.get().restoreTimers(dto.gameTickMs, dto.round == currentRound);
        // Restore player data (roster exists -> backfill cumulative data only; not rebuilt ->
        // full restore)
        for (Map.Entry<String, PlayerDto> e : dto.players.entrySet()) {
            PlayerDto p = e.getValue();
            TeamStats.restoreCachedPlayer(e.getKey(), p.status, p.health, p.gold, p.kills, p.downed, p.deaths);
        }
        // Restore this game's roll stats: the same snapshot and staleness decision as the team
        // data, replaced wholesale rather than added
        RollStats.decode(dto.rolls);
        // Restore the wave HUD's display for this round: currentRound/shouldRender only update
        // on a round title (GameEventBus), and rejoining a game in progress has no new title ->
        // set them from the scoreboard's round number,
        // so the wave HUD immediately shows this round's table, and getCurrentWave computes from
        // the restored gameTick every frame -> the arrow lands on the current wave by itself.
        // In the round-title restore scenario (GameEventBus already set the same values) this is
        // idempotent and harmless
        if (currentRound > 0) {
            CheckSpawnTimes.get().setCurrentRound(currentRound);
            TotalHUDRenderer.setShouldRender(true);
        }
    }

    /** Called when a new game starts (round==1): deletes the cache file and clears the snapshot
     *  (the end of the previous game's staging lifecycle). */
    public static void reset() {
        // capturedThisEntry stays true: if a new game reset it to false after deleting the file,
        // the next tick's capture() would read the deleted file (no snapshot), set true again and
        // lock in "no snapshot" - after that, any rejoin in this world has no snapshot to restore.
        // Keeping true means: this world already captured, a new game has no old data to restore,
        // and TeamStats.tick rebuilds the cache by writing every 1s
        snapshot = null;
        restoreDone = false;
        rejoinProbeCount = 0;
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException e) {
            // Delete failures stay silent: the next game rewrites and rebuilds
        }
    }

    // ----Serialization DTOs----

    /** The persisted root object (serialized/deserialized by Gson directly): one game's durations + the full player snapshot + roll stats. */
    static class Dto {
        /** When this cache was written (wall-clock ms): on rejoin, older than {@link #MAX_AGE_MS} counts as cross-session and is discarded. */
        long writtenAt;
        /** The round at write time (for the round-regression test of whether the restore still belongs to the same game). */
        int round;
        /** This game's total duration (ms, accumulated across rounds, from entering the game). */
        long totalGameTickMs;
        /** This round's elapsed duration (ms). */
        int gameTickMs;
        /** Each present player's field snapshot, keyed by player name. */
        Map<String, PlayerDto> players = new LinkedHashMap<>();
        /** This game's roll stats snapshot; {@code null} when nothing was rolled this game
         *  (older cache files lack the section and are handled as {@code null} on read). */
        RollStats.CachedRolls rolls;
    }

    /** One player's field snapshot - status stores the enum name (TeamStats.Status), mapped back on restore. */
    static class PlayerDto {
        String status;
        int health;
        int gold;
        int kills;
        int downed;
        int deaths;
    }
}
