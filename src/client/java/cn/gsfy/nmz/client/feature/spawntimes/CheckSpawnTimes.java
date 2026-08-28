package cn.gsfy.nmz.client.feature.spawntimes;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.util.JavaUtils;
import cn.gsfy.nmz.client.util.LanguageUtils;

import java.util.List;
import java.util.Map;

/**
 * 波次时间核心逻辑（对应源 SpawnTimes）。
 * 持有当前回合的每波预计时间表 roundTimes，把「回合号 → 时间表」的加载、当前波数推进、
 * 以及 HUD 上色要问的各种小判据都收拢在这一处，渲染与音效都只认它。
 *
 * <p>单位约定要钉死：roundTimes 存的是秒，而 gameTick 是墙钟毫秒——判当前波数时
 * 先乘 1000 统一成毫秒再比对，换算错一位，箭头就指错波。时间表在 setCurrentRound
 * 时按地图从数据表加载，地图未识别或回合越界时给空表。
 */
public class CheckSpawnTimes {

    private static CheckSpawnTimes instance;

    private int currentRound;
    private int[] roundTimes = new int[0];
    private int currentWave;

    public static CheckSpawnTimes get() {
        return instance;
    }

    public void init() {
        instance = this;
    }

    /** 设置当前回合并重新加载对应的时间表——回合一推进，波次数据就跟着换新。 */
    public void setCurrentRound(int round) {
        this.currentRound = round;
        this.roundTimes = loadRoundTimes(round);
    }

    /** 按地图加载某回合的时间表（秒）；回合非法 / 地图未识别 / 数据表没有该地图时返回空表。 */
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

    /** 当前刷到第几波（已刷过的波数）：先把时间表按需补加载（秒×1000 转毫秒），再用当前墙钟找插入位。 */
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

    /** 第 wave 波的预计时间（秒）；地图或索引非法时返回 0。 */
    public int getWaveTime(int wave) {
        MapId map = LanguageUtils.getMap();
        if (map == null || !JavaUtils.isValidIndex(roundTimes, wave - 1)) {
            return 0;
        }
        return roundTimes[wave - 1];
    }

    /** 下一波的波数：当前波已是最后一波就停在这，否则 +1。 */
    public int getNextWave() {
        return roundTimes.length == currentWave ? currentWave : currentWave + 1;
    }

    /** 波次行配色：下一波黄（AA 图开启颜色警示时可换成红/绿/蓝特殊标注），已刷过的灰，没到的暗灰。 */
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

    private boolean isGiantOnlyWave(int round, int wave) {
        return containsWave("giant_only", round, wave);
    }

    private boolean isTo1OnlyWave(int round, int wave) {
        return containsWave("to1_only", round, wave);
    }

    private boolean isTo1GiantWave(int round, int wave) {
        return containsWave("to1_and_giant", round, wave);
    }

    private boolean containsWave(String kind, int round, int wave) {
        Map<Integer, List<Integer>> table = DataManager.get().getColorAlertWaves(kind);
        List<Integer> waves = table.get(round);
        return waves != null && waves.contains(wave);
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int[] getRoundTimes() {
        return roundTimes;
    }
}
