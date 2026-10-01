package cn.gsfy.nmz.client.features.spawntimes;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.utils.JavaUtils;
import cn.gsfy.nmz.client.utils.LanguageUtils;

import java.util.List;
import java.util.Map;

/**
 * Wave-time core logic (counterpart of the source SpawnTimes). Holds the
 * current round's per-wave projected time table roundTimes, and gathers
 * "round number -> time table" loading, current-wave advance, and the small
 * predicates the HUD coloring asks for into one place—rendering and sound
 * both consult only this class.
 *
 * <p>Units are pinned: roundTimes stores seconds while gameTick is wall-clock
 * milliseconds—the current wave is computed by first multiplying by 1000 to
 * unify into milliseconds, then comparing; a conversion off by one order
 * makes the arrow point at the wrong wave. The table is loaded from the data
 * table by map at setCurrentRound; an unidentified map or an out-of-range
 * round gets an empty table.
 */
public class CheckSpawnTimes {

    private static CheckSpawnTimes instance;

    private int currentRound;
    private int[] roundTimes = new int[0];
    private int currentWave;

    /** The global singleton: non-null after init(), fetched by rendering and sound. */
    public static CheckSpawnTimes get() {
        return instance;
    }

    /**
     * Builds the singleton—pure instantiation, no event subscription; table
     * advance is driven by the outside calling
     * {@link #setCurrentRound(int)} explicitly at round boundaries.
     */
    public void init() {
        instance = this;
    }

    /**
     * Called by GameEventBus at a round title or on rejoin restore; loading
     * the matching time table whenever the round changes.
     *
     * @param round the new round number; invalid values yield an empty time table
     */
    public void setCurrentRound(int round) {
        this.currentRound = round;
        this.roundTimes = loadRoundTimes(round);
    }

    /**
     * Loads one round's time table (seconds) by map; an invalid round, an
     * unidentified map, or a map missing from the data table gives an empty
     * table.
     */
    private int[] loadRoundTimes(int round) {
        MapId map = LanguageUtils.getMap();
        if (round <= 0 || map == MapId.NULL || !DataManager.get().hasRoundTimes(map)) {
            return new int[0];
        }
        int[][] all = DataManager.get().getRoundTimes(map);
        if (!JavaUtils.isValidIndex(all, round - 1, 0)) {
            return new int[0];
        }
        return all[round - 1];
    }

    /**
     * How many waves have spawned so far: the time table is reloaded on
     * demand first (seconds x1000 to milliseconds), then the current wall
     * clock finds the insertion point. On an empty table it lazily reloads
     * right here—this is the self-healing entry after a rejoin restore or an
     * out-of-range round, retrying the load on every query without waiting
     * for an external event to drive it again.
     */
    public int getCurrentWave() {
        if (roundTimes.length == 0) {
            this.roundTimes = loadRoundTimes(currentRound);
        }
        int[] roundTicks = new int[roundTimes.length];
        for (int i = 0; i < roundTicks.length; i++) {
            roundTicks[i] = roundTimes[i] * 1000;
        }
        return currentWave = JavaUtils.findInsertPosition(roundTicks, GameTickHandler.get().getGameTick());
    }

    /** Wave {@code wave}'s projected time (seconds); an invalid map or index returns 0. */
    public int getWaveTime(int wave) {
        MapId map = LanguageUtils.getMap();
        if (map == null || !JavaUtils.isValidIndex(roundTimes, wave - 1)) {
            return 0;
        }
        return roundTimes[wave - 1];
    }

    /** The next wave's number: the current wave is the last, stays put; otherwise +1. */
    public int getNextWave() {
        return roundTimes.length == currentWave ? currentWave : currentWave + 1;
    }

    /**
     * Wave row coloring: the next wave yellow (when AA's color alert is on,
     * it can be replaced by red/green/blue special marks), spawned waves
     * gray, upcoming waves dark gray. The upcoming waves' three dark tiers
     * (purple/cyan/brown) only apply when AA's color alert is on: color the
     * wave by type before it spawns, so the player knows in advance that a
     * giant/to1 comes this wave.
     */
    public int getColor(int wave) {
        boolean aa = LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM;
        boolean colorAlert = GlobalConfig.Spawntimes.COLOR_ALERT.getBooleanValue();
        if (wave == getNextWave()) {
            if (colorAlert && aa) {
                if (isGiantOnlyWave(currentRound, wave)) return 0x0099FF;
                if (isTo1OnlyWave(currentRound, wave)) return 0x00FF00;
                if (isTo1GiantWave(currentRound, wave)) return 0xFF0000;
            }
            return 0xFFFF00;
        } else if (wave < getNextWave()) {
            return 0x5A5A5A;
        } else {
            if (colorAlert && aa) {
                if (isGiantOnlyWave(currentRound, wave)) return 0x663399;
                if (isTo1OnlyWave(currentRound, wave)) return 0x006666;
                if (isTo1GiantWave(currentRound, wave)) return 0x783300;
            }
            return 0x808080;
        }
    }

    /** AA color alert: queries the kind="giant_only" tier—giant-only wave. */
    private boolean isGiantOnlyWave(int round, int wave) {
        return containsWave("giant_only", round, wave);
    }

    /** AA color alert: queries the kind="to1_only" tier—this wave is to1-only. */
    private boolean isTo1OnlyWave(int round, int wave) {
        return containsWave("to1_only", round, wave);
    }

    /** AA color alert: queries the kind="to1_and_giant" tier—to1 and giant share this wave. */
    private boolean isTo1GiantWave(int round, int wave) {
        return containsWave("to1_and_giant", round, wave);
    }

    /** The three is*Wave predicates' shared implementation: queries the aa_color_alert table's tier, checks whether the round's wave is in it. */
    private boolean containsWave(String kind, int round, int wave) {
        Map<Integer, List<Integer>> table = DataManager.get().getColorAlertWaves(kind);
        List<Integer> waves = table.get(round);
        return waves != null && waves.contains(wave);
    }

    /** The current round number: updated by setCurrentRound; 0 means no round started yet. */
    public int getCurrentRound() {
        return currentRound;
    }

    /**
     * The current round's per-wave seconds table: index {@code [wave-1]}; an
     * unidentified round returns an empty table. Rendering reads its length
     * to count waves; each feature backs off on an empty table.
     */
    public int[] getRoundTimes() {
        return roundTimes;
    }
}