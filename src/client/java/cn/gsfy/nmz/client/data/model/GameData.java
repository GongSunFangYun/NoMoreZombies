package cn.gsfy.nmz.client.data.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的全部游戏数据——DataManager 从 {@code assets/nomorezombies/data/*.json}
 * 逐张表填充出来的只读快照。
 *
 * <p>各功能模块（波次 / 道具规律 / AA 指挥）只经 {@link DataManager#get()} 拿它，
 * 从不直接碰数据文件。
 */
public class GameData {

    /** 每张地图的波次时间表（单位秒），下标 [round-1][wave-1]。 */
    private final Map<MapId, int[][]> waveTimes = new HashMap<>();
    /** 每张地图的 boss 轮（来自 boss_rounds.json 的 boss_rounds 节点）。 */
    private final Map<MapId, int[]> bossRounds = new HashMap<>();
    /** AA 颜色预警：{kind -> {round -> waves}}，kind 取 giant_only / to1_only / to1_and_giant。 */
    private final Map<String, Map<Integer, List<Integer>>> colorAlert = new HashMap<>();
    /** 道具规律：每图 {道具 key -> 规律列表}，每条规律由 {rounds, digits} 组成。 */
    private final Map<MapId, Map<String, List<PowerupPattern>>> powerupPatterns = new HashMap<>();
    /** AA 每回合指挥详情：round -> 详情（来自 aa_round_details.json）。 */
    private final Map<Integer, AaRoundDetail> aaRoundDetails = new HashMap<>();

    // ==================== 按文件解析 ====================

    /** 读 wave_times.json：{version, maps:{map:{max_round,rounds}}}，maps 逐图落进 waveTimes。 */
    public void addWaveTimes(JsonObject root) {
        JsonObject maps = root.getAsJsonObject("maps");
        if (maps == null) {
            return;
        }
        for (String key : maps.keySet()) {
            MapId map = MapId.fromJsonKey(key);
            JsonObject mapObj = maps.getAsJsonObject(key);
            JsonArray rounds = mapObj.getAsJsonArray("rounds");
            if (rounds == null) {
                continue;
            }
            int[][] times = new int[rounds.size()][];
            for (int i = 0; i < rounds.size(); i++) {
                JsonArray row = rounds.get(i).getAsJsonArray();
                int[] rowArr = new int[row.size()];
                for (int j = 0; j < row.size(); j++) {
                    rowArr[j] = row.get(j).getAsInt();
                }
                times[i] = rowArr;
            }
            waveTimes.put(map, times);
        }
    }

    /** 读 boss_rounds.json：{version, boss_rounds:{map:[...]}, aa_color_alert:{kind:{round:[waves]}}}，两节分别填充。 */
    public void addBossRounds(JsonObject root) {
        JsonObject br = root.getAsJsonObject("boss_rounds");
        if (br != null) {
            for (String key : br.keySet()) {
                MapId map = MapId.fromJsonKey(key);
                bossRounds.put(map, toIntArray(br.getAsJsonArray(key)));
            }
        }
        JsonObject color = root.getAsJsonObject("aa_color_alert");
        if (color != null) {
            for (String kind : color.keySet()) {
                JsonObject kindObj = color.getAsJsonObject(kind);
                Map<Integer, List<Integer>> roundsMap = new HashMap<>();
                for (String round : kindObj.keySet()) {
                    List<Integer> waveList = new ArrayList<>();
                    for (JsonElement e : kindObj.getAsJsonArray(round)) {
                        waveList.add(e.getAsInt());
                    }
                    roundsMap.put(Integer.parseInt(round), waveList);
                }
                colorAlert.put(kind, roundsMap);
            }
        }
    }

    /** 读 powerup_patterns.json：{version, maps:{map:{道具 key:[{rounds:[...],digits:[...]}]}}}。 */
    public void addPowerupPatterns(JsonObject root) {
        JsonObject maps = root.getAsJsonObject("maps");
        if (maps == null) {
            return;
        }
        for (String key : maps.keySet()) {
            MapId map = MapId.fromJsonKey(key);
            JsonObject types = maps.getAsJsonObject(key);
            Map<String, List<PowerupPattern>> patternMap = new HashMap<>();
            for (String typeKey : types.keySet()) {
                List<PowerupPattern> patterns = new ArrayList<>();
                for (JsonElement e : types.getAsJsonArray(typeKey)) {
                    JsonObject pattern = e.getAsJsonObject();
                    JsonArray roundsArr = pattern.getAsJsonArray("rounds");
                    JsonArray digitsArr = pattern.getAsJsonArray("digits");
                    patterns.add(new PowerupPattern(
                            roundsArr == null ? new int[0] : toIntArray(roundsArr),
                            digitsArr == null ? new int[0] : toIntArray(digitsArr)));
                }
                patternMap.put(typeKey, patterns);
            }
            powerupPatterns.put(map, patternMap);
        }
    }

    /** 读 aa_round_details.json：{aa_rounds:[{round,recommendedSpots,hasGiant,hasOldOne,dangerLevel}]}。 */
    public void addAaRoundDetails(JsonObject root) {
        JsonArray rounds = root.getAsJsonArray("aa_rounds");
        if (rounds == null) {
            return;
        }
        for (JsonElement e : rounds) {
            JsonObject obj = e.getAsJsonObject();
            int round = obj.get("round").getAsInt();
            List<String> spots = new ArrayList<>();
            JsonArray spotsArr = obj.getAsJsonArray("recommendedSpots");
            if (spotsArr != null) {
                for (JsonElement s : spotsArr) {
                    String name = s.getAsString();
                    if (name != null && !name.isEmpty()) {
                        spots.add(name);
                    }
                }
            }
            boolean hasGiant = obj.get("hasGiant").getAsBoolean();
            boolean hasOldOne = obj.get("hasOldOne").getAsBoolean();
            int dangerLevel = obj.get("dangerLevel").getAsInt();
            aaRoundDetails.put(round, new AaRoundDetail(round, spots, hasGiant, hasOldOne, dangerLevel));
        }
    }

    // ==================== 便捷访问 ====================

    public int[][] getRoundTimes(MapId map) {
        return waveTimes.getOrDefault(map, EMPTY_WAVES);
    }

    public boolean hasRoundTimes(MapId map) {
        return waveTimes.containsKey(map);
    }

    public int[] getBossRounds(MapId map) {
        return bossRounds.getOrDefault(map, EMPTY_INTS);
    }

    public List<PowerupPattern> getPowerupPatterns(MapId map, String typeKey) {
        Map<String, List<PowerupPattern>> patterns = powerupPatterns.get(map);
        if (patterns == null) {
            return List.of();
        }
        return patterns.getOrDefault(typeKey, List.of());
    }

    public Map<Integer, List<Integer>> getColorAlertWaves(String kind) {
        return colorAlert.getOrDefault(kind, Map.of());
    }

    /** 取 AA 指定回合的指挥详情——数据表没覆盖到该回合就返回 null，调用方自行兜底。 */
    public AaRoundDetail getAaRoundDetail(int round) {
        return aaRoundDetails.get(round);
    }

    private static int[] toIntArray(JsonArray arr) {
        if (arr == null) {
            return new int[0];
        }
        int[] result = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = arr.get(i).getAsInt();
        }
        return result;
    }

    private static final int[][] EMPTY_WAVES = new int[0][];
    private static final int[] EMPTY_INTS = new int[0];
}
