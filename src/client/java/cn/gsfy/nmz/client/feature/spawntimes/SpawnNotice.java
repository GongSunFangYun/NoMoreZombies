package cn.gsfy.nmz.client.feature.spawntimes;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import cn.gsfy.nmz.client.util.JavaUtils;
import net.minecraft.client.MinecraftClient;

/**
 * 波次出生音效提醒（对应源 SpawnNotice）。
 * 到波次该刷怪的时刻就放一声提示音，末波还有 3-2-1 倒计时音，光靠耳朵就能预判节奏。
 *
 * <p>驱动方式很轻：GameTickHandler 每整秒调一次 {@link #onSpawn(int)}，把当前墙钟 tick
 * 拿去和每波时刻表比对，命中就按配置播对应音效（音效 ID / 音高都在全局配置页）。
 * 两道门控：范围开关（全部 / 仅 AA / 仅其他图）与总开关（QoL.WAVE_SOUND_ENABLED）。
 */
public class SpawnNotice {

    /** 当前回合号：0 表示还没进回合，onSpawn 直接不响。 */
    private static int currentRound;
    /** 当前回合的每波时刻表（秒）；空表表示没数据，onSpawn 不响。 */
    private static int[] currentRoundTimes = new int[0];

    /** 回合推进时更新当前回合号并重载时间表——波次音效只认这一份最新数据。 */
    public static void update(int round) {
        currentRound = round;
        currentRoundTimes = load(round);
    }

    /** 按地图加载某回合的每波时间表（秒）；回合非法 / 地图未识别 / 数据表缺失时给空表。 */
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

    /** 整秒驱动入口（GameTickHandler 每整秒调一次）：tick 命中某波时刻就播音，末波另有 3-2-1 倒计时。 */
    public static void onSpawn(int tick) {
        if (currentRound == 0 || currentRoundTimes.length == 0) {
            return;
        }
        if (MinecraftClient.getInstance().player == null) {
            return;
        }
        // 波次出生音效总开关：一关，普通/末波/3-2-1 倒计时音效全不播——范围、音效 ID、音高都另在全局配置页
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
