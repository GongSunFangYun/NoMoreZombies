package cn.gsfy.nmz.client.shared.game;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.gamehud.AAAutoCommand;
import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.features.querydata.QueryDataManager;
import cn.gsfy.nmz.client.features.powerups.PowerupDetect;
import cn.gsfy.nmz.client.features.powerups.PowerupPredict;
import cn.gsfy.nmz.client.features.rolls.RollStats;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.features.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.features.stats.TeamStatsManager;
import cn.gsfy.nmz.client.features.recorder.TimeRecorder;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.RoundUtils;
import cn.gsfy.nmz.client.utils.StringUtils;

import java.util.ArrayList;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;

/**
 * The game state bus - "round start / game end / world sound" signals all
 * funnel here; mixins only forward raw signals and carry no business logic.
 *
 * <p>The three signal kinds are orchestrated in order at one entry: record
 * the previous round first, then reset state, then fire each feature's
 * callback - whoever runs first is the causal order.
 * The chat channel does not go through here - {@code NoMoreZombiesClient}
 * registers the message callback directly and dispatches to PowerupDetect,
 * TeamStatsManager and RollStats.
 * Timing traps (a round title later than the scoreboard refresh, a rejoin
 * without a new round title, etc.) are noted in each branch's comments.
 *
 * <p>Boundary: every method is called on the client thread only (titles come
 * from rendering, sounds from the world, round advancement in
 * END_CLIENT_TICK), so no concurrent containers inside; rejoin detection
 * relies on {@code GameTickHandler.lastRoundSeen} rather than the total-timer
 * flag (the world switch at the rejoin instant resets the latter); the
 * end-of-game summary title shares the word "round" with ordinary round
 * titles, so the test must check gameEnd before isRound - reversed, the
 * summary would be taken as a new round and zero the timing.
 */
public final class GameEventBus {

    /**
     * The InGameHud.setTitle hook hands round and settlement titles here,
     * advancing timing, caches and each feature's state in causal order.
     *
     * <p>The test checks gameEnd before isRound: an end-of-game summary title
     * (e.g. "Zombies-12:34 (Round 15)") also contains the word "round", and
     * testing isRound first would take the summary for a new round (running
     * startOrSplit and zeroing), so isRound must exclude with !gameEnd.
     *
     * @param title the raw title; de-formatted then recognized across languages inside
     */
    public static void onSetTitle(String title) {
        String trimmed = StringUtils.trim(title);
        boolean gameEnd = LanguageUtils.isGameEnd(trimmed) || LanguageUtils.isGameEndSummary(trimmed);
        boolean isRound = !gameEnd && LanguageUtils.isRoundTitle(trimmed);
        if (isRound || gameEnd) {
            // Order matters: record the elapsed time first (gameTick is not yet zeroed and
            // currentRound is still the previous round)
            TimeRecorder.recordGameTime();

            if (gameEnd) {
                // Game end: freeze the timers (clear/wipe both freeze round + total at current
                // values) without calling startOrSplit()
                // - do not reset gameTick; keep the frozen values with gameStarted true so the
                // HUD keeps rendering them.
                // won idempotence: when the dragon death sound (onWorldPlaySound) already set
                // gameWon=true, the title no longer overwrites the victory flag
                boolean won = GameTickHandler.get().isGameOver()
                        ? GameTickHandler.get().isGameWon()
                        : LanguageUtils.isWinTitle(trimmed);
                GameTickHandler.get().setGameStarted(true);
                if (!GameTickHandler.get().isGameOver()) {
                    GameTickHandler.get().onGameEnd(won);
                }
                // Defeat (team wipe): every non-left player marked dead, so the game cannot end
                // with "in combat" still showing
                TeamStatsManager.onGameEnd(won);
                // Game over: destroy the player query cache (re-requested next game)
                if (QueryDataManager.get() != null) {
                    QueryDataManager.get().clearCache();
                }
                // Game over: the scoreboard cache clears with the game (re-polled next game)
                if (ScoreboardManager.get() != null) {
                    ScoreboardManager.get().clear();
                }
                // Game over: the map cache invalidates with the game (re-identified next game /
                // map switch, keeping AA's cache out of non-AA maps)
                LanguageUtils.invalidateMapCache();
            } else {
                GameTickHandler.get().setGameStarted(true);
                GameTickHandler.get().startOrSplit();
            }
            int round = gameEnd ? 0 : LanguageUtils.getRoundNumber(trimmed);

            // These must run before the isInZombiesTitle check, so player state resets correctly
            // across games/rounds
            if (round > 0) {
                // Is this the game's first round title: lastRoundSeen resets to 0 on confirmed
                // leave and world switch, and stays non-0 after every round title - use it to
                // tell "the local player rejoined a game in progress" from an ordinary round
                // change; the test and reasoning are in GameTickHandler.lastRoundSeen's comment
                boolean firstRoundTitle = GameTickHandler.get().getLastRoundSeen() == 0;
                if (round == 1) {
                    TeamStatsManager.onNewGame(); // new game: clear the old roster, reset everyone (cache file included)
                    // New game: roll stats zeroed - this game's counts only mean something for
                    // the current game,
                    // and even a same-map continuation must start from 0
                    RollStats.reset();
                    // New game start: the map cache invalidates with it (2026-08-19 field proof -
                    // invalidating only at the previous game's end/disconnect let the previous
                    // map leak into the new one: after a DE game ended, entering AA kept getMap()
                    // returning the cached DEAD_END all game, making the AA command HUD, rod HUD,
                    // wave table and power-up predictions all wrong). round==1 is always a new
                    // game (a same-map continuation re-identifies too, correct and harmless)
                    LanguageUtils.invalidateMapCache();
                } else {
                    TeamStatsManager.onNewRound(); // new round: revive non-left players, into the protection window
                }
                GameTickHandler.get().setLastRoundSeen(round);
                // Round 1 = a new game counting from 0; a mid-game join counts from the join
                // moment (round may be > 1)
                if (!GameTickHandler.get().isTotalStarted()) {
                    // Mid-game join (round > 1): this client's first round title is a new game -
                    // invalidate the map cache and re-identify too
                    LanguageUtils.invalidateMapCache();
                    GameTickHandler.get().startGameTimer();
                }
                // The local player rejoined a game in progress: restore this game's cumulative
                // data and durations from the disk cache; the trigger condition and the division
                // between the two restore entries are in GameCache's class comment;
                // placed after startGameTimer() so the restored total is not overwritten by the
                // zeroing
                if (round > 1 && firstRoundTitle) {
                    GameCache.restore(round);
                }
            }

            // The wave HUD depends on currentRound/shouldRender, neither of which depends on the
            // scoreboard title (titles may update late):
            // advance on any valid round title, so "rejoin after disconnect, scoreboard title
            // later than the first round title" cannot leave the wave HUD dark all game
            CheckSpawnTimes.get().setCurrentRound(round);
            TotalHUDRenderer.setShouldRender(round > 0);
            // Alien Arcadium auto command: fires each round's start (internally delayed 20 ticks
            // for AA map identification; switch/map gate themselves)
            AAAutoCommand.onRoundStarted(round);
            // The following only apply in Zombies (the scoreboard title may update late and must
            // not block the resets above)
            if (!PlayerUtils.isInZombiesTitle()) {
                return;
            }
            SpawnNotice.update(round);
            handlePowerupsOnRound(round);
            // Player queries (network requests) are scheduled only inside a Zombies game: 3s
            // after each round, onGameStart idempotent
            // (cached/in-flight automatically skipped), healing the race where the round-1 query
            // task got cancelled by a misjudged leave; querying teammates who joined mid-game
            // moved inside the isInZombiesTitle gate, so a "Round" title outside Zombies never
            // fires a network request
            if (round > 0 && QueryDataManager.get() != null) {
                DelayedTaskScheduler.get().runTaskLater(60, QueryDataManager.get()::fetchInGamePlayers);
            }
        }
    }

    /**
     * Round-start power-up logic: clears the previous round's predictions and
     * pickup records, claims on-field power-ups on boss rounds, then builds
     * prediction entries for the types whose committed pattern hits this round
     * (with prediction on, extrapolates later rounds after a 40-tick delay).
     *
     * <p>Clearing {@code incPowerups} (prediction entries) and
     * {@code pickedUpRound} (round-level pickup records) is the first link of
     * the causal chain: both settle per round - a prediction entry only means
     * something for its own round; the pickup records support the HUD's
     * commit-once state machine, and leaving them would carry the previous
     * round's "about to drop / picked up" display into the new round.
     *
     * <p>Drops on a boss round get cleared away, so on-field leftovers are
     * claimed before this round's prediction entries are built, letting
     * prediction start from a clean field - claim zeroes the countdown, removes
     * from powerups, and puts the entity on a 20-tick short blacklist.
     * Copy then iterate: claim removes from powerups internally, and iterating
     * directly would throw ConcurrentModificationException.
     */
    private static void handlePowerupsOnRound(int round) {
        if (PowerupDetect.get() == null) {
            return;
        }
        if (round <= 0) {
            return;
        }
        PowerupParser.incPowerups.clear();
        PowerupParser.pickedUpRound.clear();

        if (isBossRound(round)) {
            new ArrayList<>(PowerupParser.powerups.values()).forEach(PowerupParser::claim);
        }
        PowerupDetect detect = PowerupDetect.get();
        boolean insta = detect.isPowerupRound(PowerupParser.PowerupType.INSTA_KILL, round);
        boolean maxAmmo = detect.isPowerupRound(PowerupParser.PowerupType.MAX_AMMO, round);
        boolean ss = detect.isPowerupRound(PowerupParser.PowerupType.SHOPPING_SPREE, round);
        if (insta) {
            PowerupParser.deserialize(PowerupParser.PowerupType.INSTA_KILL);
        }
        if (maxAmmo) {
            PowerupParser.deserialize(PowerupParser.PowerupType.MAX_AMMO);
        }
        if (ss) {
            PowerupParser.deserialize(PowerupParser.PowerupType.SHOPPING_SPREE);
        }
        if (GlobalConfig.Powerups.POWERUP_PREDICT.getBooleanValue()) {
            DelayedTaskScheduler.get().runTaskLater(40, PowerupPredict::detectNextPowerupRound);
        }
    }

    /** Whether this round is a boss round: reads the shared verdict (the boss_rounds table + current difficulty),
     *  treating an unidentified map as non-boss. */
    private static boolean isBossRound(int round) {
        return RoundUtils.isBossRound(LanguageUtils.getMap(), round);
    }

    /**
     * The ClientWorld.playSound hook supplements the title state signals with
     * the wither spawn and ender dragon death sounds; sounds outside a game
     * are ignored directly.
     *
     * @param path the sound event path, e.g. {@code entity.ender_dragon.death}
     * @param pitch the raw pitch; recognition reads only the path, the parameter kept to match the hook signature
     */
    public static void onWorldPlaySound(String path, float pitch) {
        // Zombies games only: a singleplayer / ordinary server's wither spawn or dragon death
    	// must not pollute the game state machine
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (path.equals("entity.wither.spawn")) {
            GameTickHandler.get().setGameStarted(true);
        } else if (path.equals("entity.ender_dragon.death")) {
            // The final boss dying = a clear: freeze the timers (values kept, not reset, matching
            // the game-end title branch) and settle the team stats in the same breath - the sound
            // may arrive before the settlement title, and downed teammates cannot wait for the
            // title to be revived
            GameTickHandler.get().setGameStarted(true);
            GameTickHandler.get().onGameEnd(true);
            TeamStatsManager.onGameEnd(true);
        }
    }

    private GameEventBus() {
    }
}