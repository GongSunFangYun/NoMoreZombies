package cn.gsfy.nmz.client.features.spawntimes;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.JavaUtils;
import net.minecraft.client.MinecraftClient;

/**
 * Wave spawn sound notice (counterpart of the source SpawnNotice). At the
 * moment a wave should spawn, a cue sounds; the last wave also gets a 3-2-1
 * countdown—the pace is predictable by ear alone.
 *
 * <p>Very lightly driven: GameTickHandler calls {@link #onSpawn(int)} once
 * per whole second, comparing the current whole-second-aligned moment
 * against the per-wave table, and playing the configured sound on a hit
 * (sound ID / pitch on the global config page). Whether it plays is decided
 * by two gates: the four per-map switches (AA/DE/BB/Prison, each gating its
 * own map) plus the master switch {@code QoL.WAVE_SOUND_ENABLED}—master off
 * silences wave sounds on every map.
 */
public class SpawnNotice {

    /** The current round number: 0 means no round yet; onSpawn stays silent. */
    private static int currentRound;
    /** The current round's per-wave time table (seconds); an empty table means no data, onSpawn stays silent. */
    private static int[] currentRoundTimes = new int[0];

    /**
     * GameEventBus updates the round number and reloads the time table at
     * round boundaries; onSpawn trusts only the latest row loaded here
     * (load returns a direct reference to the data table row, not a copy).
     *
     * @param round the new round number; 0 means left the game or settlement
     *   (pass 0 on leaving to reset)
     */
    public static void update(int round) {
        currentRound = round;
        currentRoundTimes = load(round);
    }

    /**
     * Loads one round's per-wave time table (seconds) by map; an invalid
     * round, an unidentified map, or a missing data table gives an empty
     * table—an empty table keeps onSpawn silent throughout.
     */
    private static int[] load(int round) {
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
     * Called by GameTickHandler when a new whole second is crossed; the time
     * table stores seconds, so the passed value must be aligned to a whole
     * thousand milliseconds.
     *
     * @param tick local wall-clock milliseconds since this round started;
     *   the caller passes {@code second * 1000}
     */
    public static void onSpawn(int tick) {
        if (currentRound == 0 || currentRoundTimes.length == 0) {
            return;
        }
        if (MinecraftClient.getInstance().player == null) {
            return;
        }
        // Master switch: off stops normal / last-wave / 3-2-1 countdown sounds
        // entirely—the per-map switches, sound IDs and pitch all live on the
        // config page
        if (!GlobalConfig.QoL.WAVE_SOUND_ENABLED.getBooleanValue()) {
            return;
        }

        int finalWaveTime = currentRoundTimes[currentRoundTimes.length - 1];
        MapId map = LanguageUtils.getMap();
        boolean playSound = switch (map) {
            case ALIEN_ARCADIUM -> GlobalConfig.Spawntimes.WAVE_SOUND_AA.getBooleanValue();
            case DEAD_END      -> GlobalConfig.Spawntimes.WAVE_SOUND_DE.getBooleanValue();
            case BAD_BLOOD     -> GlobalConfig.Spawntimes.WAVE_SOUND_BB.getBooleanValue();
            case PRISON        -> GlobalConfig.Spawntimes.WAVE_SOUND_PRISON.getBooleanValue();
            default            -> false;
        };

        if (playSound) {
            for (int time : currentRoundTimes) {
                if (time * 1000 == tick) {
                    if (finalWaveTime * 1000 == tick) {
                        PlayerUtils.playSound(GlobalConfig.Spawntimes.LAST_WAVE_SOUND.getStringValue(), (float) GlobalConfig.Spawntimes.LAST_WAVE_PITCH.getDoubleValue());
                    } else {
                        PlayerUtils.playSound(GlobalConfig.Spawntimes.PRECEDED_WAVE_SOUND.getStringValue(), (float) GlobalConfig.Spawntimes.PRECEDED_WAVE_PITCH.getDoubleValue());
                    }
                    return;
                }
            }
        }

        if (GlobalConfig.Spawntimes.FINAL_WAVE_COUNTDOWN.getBooleanValue()) {
            if (tick == (finalWaveTime - 3) * 1000) {
                PlayerUtils.playSound(GlobalConfig.Spawntimes.COUNTDOWN_SOUND.getStringValue(), (float) GlobalConfig.Spawntimes.COUNTDOWN_PITCH.getDoubleValue());
            } else if (tick == (finalWaveTime - 2) * 1000) {
                PlayerUtils.playSound(GlobalConfig.Spawntimes.COUNTDOWN_SOUND.getStringValue(), (float) GlobalConfig.Spawntimes.COUNTDOWN_PITCH.getDoubleValue());
            } else if (tick == (finalWaveTime - 1) * 1000) {
                PlayerUtils.playSound(GlobalConfig.Spawntimes.COUNTDOWN_SOUND.getStringValue(), (float) GlobalConfig.Spawntimes.COUNTDOWN_PITCH.getDoubleValue());
            }
        }
    }

    private SpawnNotice() {
    }
}