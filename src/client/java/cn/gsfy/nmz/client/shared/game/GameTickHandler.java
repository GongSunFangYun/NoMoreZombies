package cn.gsfy.nmz.client.shared.game;

import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.features.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * The game timer - accumulates real wall-clock time on a "1000 units = 1s"
 * scale.
 *
 * <p>Converted from nanoTime deltas inside the client tick, with no scheduler
 * on a background thread: it sidesteps cross-thread races and keeps the
 * "1000 units = 1s" scale. Every whole second fires
 * SpawnNotice.onSpawn(tick) once to check the wave spawn.
 *
 * <p>Two clocks with a division of labor: the round clock {@code gameTick}
 * advances purely locally and zeroes on disconnect/leave; the total clock
 * {@code totalGameTick} accumulates across rounds from entering the game and
 * is periodically calibrated against the scoreboard's server-authoritative
 * whole-second value ({@code syncTotalTimeFromScoreboard}), advancing by local
 * deltas between calibrations - calibration handles drift, the local clock
 * handles continuity. Settlement: when the game ends (clear or wipe) both
 * clocks freeze, keeping their current readings; when rejoining a game in
 * progress the round clock restores from the cache while the total clock does
 * not (waiting for scoreboard recalibration), and the round-start snapshot
 * {@code roundStartTotalMs} stays 0 until the restore completes, so the total
 * duration delta is unavailable in that window.
 */
public class GameTickHandler {

    private static GameTickHandler instance;

    private int gameTick;
    private boolean gameStarted;
    private long lastNano;
    private int lastSecond;
    /** This game's total duration (ms, from entering the game, accumulated across rounds without clearing; reset on a new game/disconnect). */
    private long totalGameTick;
    private boolean totalStarted;
    /** Round start: a snapshot of the total duration (ms) when this round began. The total is calibrated by the scoreboard's whole-second value,
     * advancing by local {@code nanoTime} deltas between calibrations; 0 = not yet calibrated or just rejoined,
     * when the total-duration delta is unavailable and callers fall back to the local round timer {@code gameTick}. */
    private long roundStartTotalMs;
    /** This game has ended (clear or wipe): round/total timers frozen (current values kept, no more ticking). */
    private boolean gameOver;
    /** Victory flag (distinguishes win/wipe for TeamStats' end fallback; the HUD no longer displays by it). */
    private boolean gameWon;
    /** Whether the previous tick confirmed being in-game. The leave reset runs only on "confirmed in -> confirmed out",
     * so the transient isInZombies() false at a round switch cannot trigger a full reset and cancel pending tasks (e.g. player queries). */
    private boolean wasInZombies;
    /**
     * The round number of this game's most recent round title; 0 = none seen
     * yet. Reset to 0 on confirmed leave (out-of-zombies) and on world switch
     * (world null), to distinguish "the local player rejoined a game in
     * progress" ({@code round>1} and a first round title) from an ordinary
     * round change - the test must not use isTotalStarted(): the world switch
     * at the rejoin instant resets it, which would shift rejoin detection into
     * the next round.
     */
    private int lastRoundSeen;

    public static GameTickHandler get() {
        return instance;
    }

    /** Builds the singleton and hooks the per-tick callback: zeroed outside a world, reset on leave, and the stopwatch advance plus wave checks in-game all funnel here.
     *
     *  <p><b>Both leave paths must run the same reset</b>: "world gone"
     *  ({@code world == null}, including world replacement / returning to the
     *  title screen) and "confirmed leaving Zombies" share the same
     *  {@link #resetOnLeaveZombies()}; and the world-gone path runs
     *  <b>unconditionally</b> -
     *  it also clears {@code wasInZombies}, so were the reset hung behind the
     *  "was confirmed in" condition, one transition through {@code world == null}
     *  would skip the whole reset -
     *  entering Limbo would leave currentRound / map cache / pending tasks all
     *  in place.
     */
    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                // Outside a world: zero all timing and state, plus one full leave reset
                gameTick = 0;
                gameStarted = false;
                lastNano = 0;
                lastSecond = 0;
                totalGameTick = 0;
                totalStarted = false;
                roundStartTotalMs = 0;
                gameOver = false;
                gameWon = false;
                wasInZombies = false;
                lastRoundSeen = 0;
                resetOnLeaveZombies();
                return;
            }
            boolean inZombies = PlayerUtils.isInZombies();
            // Leaving Zombies (back to lobby / another game) resets all timing and state;
            // only when previously confirmed in and now confirmed out (a transient false at a
            // round switch does not trigger)
            if (!inZombies) {
                if (wasInZombies && (gameStarted || gameTick != 0 || totalStarted)) {
                    gameTick = 0;
                    gameStarted = false;
                    lastSecond = 0;
                    totalGameTick = 0;
                    totalStarted = false;
                    roundStartTotalMs = 0;
                    gameOver = false;
                    gameWon = false;
                    resetOnLeaveZombies();
                }
                wasInZombies = false;
                lastRoundSeen = 0;
                lastNano = System.nanoTime();
                return;
            }
            wasInZombies = true;
            long now = System.nanoTime();
            if (lastNano != 0) {
                int elapsedMs = (int) ((now - lastNano) / 1_000_000L);
                // Game over (gameOver): round/total timers freeze together at their current
                // values (clear or wipe are both game-end signals)
                if (elapsedMs > 0 && !gameOver) {
                    gameTick += elapsedMs;
                    if (totalStarted) {
                        totalGameTick += elapsedMs;
                    }
                }
            }
            lastNano = now;

            int second = gameTick / 1000;
            if (gameStarted && second != lastSecond) {
                lastSecond = second;
                if (second > 0 && MinecraftClient.getInstance().world != null) {
                    // Pass the tick aligned to the whole second so SpawnNotice's exact matching hits
                    SpawnNotice.onSpawn(second * 1000);
                }
            }
        });
    }

    /**
     * The leave reset - the segment shared by leaving Zombies and "world
     * gone".
     *
     * <p>It clears what is <b>not the stopwatch but survives across games</b>
     * all the same: the wave table's current round (the global overview HUD's
     * render premise), the {@code shouldRender} batch visibility, wave
     * notices, the map/difficulty caches, and pending delayed tasks
     * (leftover tasks would run wild in the next game / lobby: player query
     * requests were carried out exactly that way).
     *
     * <p><b>Resets only; never touches timing semantics</b>: the timing fields
     * are zeroed by each path's own call site; the freeze semantics and the
     * round-title processing order live elsewhere and cannot be altered by
     * this method.
     *
     * <p>Only two call sites, both already testing {@code world}/
     * {@code wasInZombies}: the "was confirmed in" gate on confirmed leave,
     * and the unconditional call when the world is {@code null}.
     */
    private void resetOnLeaveZombies() {
        CheckSpawnTimes.get().setCurrentRound(0);
        TotalHUDRenderer.setShouldRender(false);
        cn.gsfy.nmz.client.features.spawntimes.SpawnNotice.update(0);
        // Leaving Zombies: the map cache invalidates with the leave - keeps the previous map's
        // cache out of the next map
        // (2026-08-19 field proof: after a DE game ended, the cache carried DE into an AA game;
        // invalidating at the new game's start is the cure)
        LanguageUtils.invalidateMapCache();
        // Leaving Zombies: all pending delayed tasks cancelled (no cross-game leftovers running)
        if (DelayedTaskScheduler.get() != null) {
            DelayedTaskScheduler.get().cancelAll();
        }
    }

    /** Called at a round start / new round (split): waits to start if not started, otherwise zeroes and restarts the timing.
     *  A new round lifts the game-over freeze (new game or continuation). */
    public void startOrSplit() {
        gameStarted = true;
        gameTick = 0;
        lastSecond = 0;
        lastNano = System.nanoTime();
        gameOver = false;
        gameWon = false;
        // Snapshot the current total as the round start: it is calibrated by the scoreboard's
        // whole-second value and advances by the local wall clock between syncs
        roundStartTotalMs = totalGameTick;
    }

    /**
     * Disconnect cleanup may explicitly rewrite the started state; setting
     * {@code false} also clears the round/total timing and the end flags.
     *
     * @param flag the new game-started state
     */
    public void setGameStarted(boolean flag) {
        this.gameStarted = flag;
        if (!flag) {
            gameTick = 0;
            lastSecond = 0;
            totalGameTick = 0;
            totalStarted = false;
            roundStartTotalMs = 0;
            gameOver = false;
            gameWon = false;
        }
    }

    /**
     * Freezes both timers when the title or the final boss sound confirms the
     * settlement, keeping the values for the HUD and stats to read.
     *
     * @param won {@code true} for a victory, {@code false} for a team wipe
     */
    public void onGameEnd(boolean won) {
        gameOver = true;
        gameWon = won;
    }

    /** Whether the game has ended (set by onGameEnd; timers freeze afterwards). */
    public boolean isGameOver() {
        return gameOver;
    }

    /** Whether the ended game was a victory (set by onGameEnd, for the end fallback decision). */
    public boolean isGameWon() {
        return gameWon;
    }

    /** Called at this game's start / mid-game join: the total duration counts from this instant and zeroes (round gameTick untouched). */
    public void startGameTimer() {
        totalStarted = true;
        totalGameTick = 0;
    }

    /** This game's total duration (ms, accumulated across rounds from entering). */
    public long getTotalGameTick() {
        return totalGameTick;
    }

    /** Whether the total-duration timer started (set by startGameTimer / scoreboard sync;
     *  the world switch at the rejoin instant resets it - rejoin detection cannot rely on it). */
    public boolean isTotalStarted() {
        return totalStarted;
    }

    /**
     * This round's elapsed time from the total-duration channel: both the
     * round start and the current value are calibrated by the scoreboard's
     * whole-second reading, and advance by local {@code nanoTime} deltas
     * between syncs - so it is not a continuously delivered server clock.
     *
     * @return the delta (ms); {@code -1} when not yet calibrated or just rejoined (start is 0)
     */
    public long getRoundElapsedFromTotal() {
        if (!totalStarted || roundStartTotalMs <= 0) {
            return -1;
        }
        long elapsed = totalGameTick - roundStartTotalMs;
        return elapsed > 0 ? elapsed : 0;
    }

    /**
     * Calibrates the total duration with the scoreboard's {@code Time: mm:ss}
     * polled every 5 ticks: on a mid-game join or rejoin, local accumulation
     * from entry runs low, so the sidebar's whole-second value is adopted
     * first; between calibrations the client tick keeps advancing by
     * {@code nanoTime} deltas. Frozen after game end; later calibrations are
     * refused.
     *
     * <p>The scoreboard gives only second-grade readings with network latency,
     * so no claim of a continuous server-authoritative clock is made.
     *
     * @param totalMs the total duration parsed from the scoreboard (ms)
     */
    public void syncTotalTimeFromScoreboard(long totalMs) {
        if (gameOver) {
            return; // game-end freeze: round/total timers hold their current values
        }
        totalGameTick = totalMs;
        totalStarted = true;
    }

    /** This round's elapsed duration (locally accumulated wall-clock ms, 1000 = 1s). */
    public int getGameTick() {
        return gameTick;
    }

    /** The round number of this game's most recent round title; 0 = none seen yet (GameEventBus updates on each round title). */    public int getLastRoundSeen() {
        return lastRoundSeen;
    }

    /**
     * GameEventBus records the round number after recognizing each round
     * title, letting rejoin detection tell a first title from an ordinary
     * round change.
     *
     * @param round the round number parsed from the current title; 0 means no round title seen yet
     */
    public void setLastRoundSeen(int round) {
        this.lastRoundSeen = round;
    }

    /**
     * Restores the round timing on rejoin (called by GameCache after restoring
     * the cache). The total duration is no longer restored from the cache -
     * it is recalibrated by the scoreboard's {@code Time: mm:ss} row
     * ({@link #syncTotalTimeFromScoreboard(long)}); restoring the cached value
     * too would fight the scoreboard reading that arrives afterwards and cause
     * a brief jump back.
     * The round duration restores only while still in the cached round
     * (otherwise that round has ended and the cached value is the previous
     * round's remaining time, which does not apply).
     *
     * @param roundMs the cached round time (ms)
     * @param sameRound whether the rejoin is still in the cached round
     */
    public void restoreTimers(int roundMs, boolean sameRound) {
        if (sameRound) {
            gameTick = roundMs;
        }
    }
}