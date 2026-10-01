package cn.gsfy.nmz.client.data.model;

import cn.gsfy.nmz.NoMoreZombies;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All parsed game data—the runtime container DataManager fills table by
 * table from {@code assets/nomorezombies/data/*.json}.
 *
 * <p>Feature modules (waves, powerup patterns, AA command) reach it only
 * through {@link cn.gsfy.nmz.client.data.DataManager#get()} and never touch
 * the data files directly. To avoid allocation on hot paths, accessors
 * return the internal arrays, lists, and maps by reference—in-place
 * mutation writes back into the data tables, so treat them as read-only.
 */
public class GameData {

    /** Wave time table per map, in seconds. Indexed {@code [round-1][wave-1]}. */
    private final Map<MapId, int[][]> waveTimes = new HashMap<>();
    /** Round cap per map (the {@code max_round} field in wave_times.json):
     *  AA=105, DE/BB/Prison=30. Today every map's row count matches its
     *  cap, but the field is kept separate to honor the resource contract
     *  and to tolerate a future where the two diverge. */
    private final Map<MapId, Integer> maxRounds = new HashMap<>();
    /** Boss rounds per map (from the {@code boss_rounds} node in
     *  boss_rounds.json): {@code map -> difficulty -> ascending rounds}.
     *  Single-difficulty maps register only one tier (AA is normal-only);
     *  a missing tier falls back to normal. */
    private final Map<MapId, Map<DifficultyId, int[]>> bossRounds = new HashMap<>();
    /** AA color alerts: {@code {kind -> {round -> waves}}}, where kind is
     *  one of giant_only/to1_only/to1_and_giant. */
    private final Map<String, Map<Integer, List<Integer>>> colorAlert = new HashMap<>();
    /** Powerup patterns: per map, {@code {type key -> pattern list}}, each
     *  pattern made of {@code {rounds, digits}}. */
    private final Map<MapId, Map<String, List<PowerupPattern>>> powerupPatterns = new HashMap<>();
    /** AA per-round command detail, {@code round -> detail} (from
     *  aa_round_details.json). */
    private final Map<Integer, AaRoundDetail> aaRoundDetails = new HashMap<>();

    // ==================== Parsing per file ====================

    /** Reads wave_times.json:
     *  {@code {version, maps:{map:{max_round,rounds}}}}; each map lands in
     *  waveTimes and maxRounds. */
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
            JsonElement maxRound = mapObj.get("max_round");
            if (maxRound != null && maxRound.isJsonPrimitive()) {
                maxRounds.put(map, maxRound.getAsInt());
            }
        }
    }

    /** Reads boss_rounds.json:
     *  {@code {version, boss_rounds:{map:{difficulty:[...]}}, aa_color_alert:{kind:{round:[waves]}}}}.
     *  The two sections parse independently—one malformed section loses
     *  only itself, so a boss-table failure never wipes out the color
     *  alerts. */
    public void addBossRounds(JsonObject root) {
        try {
            parseBossRounds(root);
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("Failed to parse boss_rounds section; keeping it empty", e);
        }
        try {
            parseAaColorAlert(root);
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("Failed to parse aa_color_alert section; keeping it empty", e);
        }
    }

    /** Parses boss rounds map by map ({@code map -> difficulty -> round
     *  array}). A malformed map loses only itself (logged and skipped);
     *  one bad map never takes down the whole table. */
    private void parseBossRounds(JsonObject root) {
        JsonObject br = root.getAsJsonObject("boss_rounds");
        if (br == null) {
            return;
        }
        for (String mapKey : br.keySet()) {
            MapId map = MapId.fromJsonKey(mapKey);
            if (map == MapId.NULL) {
                NoMoreZombies.LOGGER.warn("[数据] boss_rounds 含未知地图键 {}，已跳过", mapKey);
                continue;
            }
            try {
                JsonObject byDifficulty = br.getAsJsonObject(mapKey);
                Map<DifficultyId, int[]> table = new HashMap<>();
                for (String difficultyKey : byDifficulty.keySet()) {
                    DifficultyId difficulty = DifficultyId.fromJsonKey(difficultyKey);
                    if (difficulty == DifficultyId.NULL) {
                        NoMoreZombies.LOGGER.warn("[数据] boss_rounds 地图 {} 含未知难度键 {}，已跳过", mapKey, difficultyKey);
                        continue;
                    }
                    table.put(difficulty, toIntArray(byDifficulty.getAsJsonArray(difficultyKey)));
                }
                bossRounds.put(map, table);
                if (!table.containsKey(DifficultyId.NORMAL)) {
                    NoMoreZombies.LOGGER.warn("[数据] boss_rounds 地图 {} 未登记 normal 档，难度未识别时该图查不到首领轮", mapKey);
                }
            } catch (Exception e) {
                NoMoreZombies.LOGGER.error("[数据] boss_rounds 地图 {} 解析失败，该图整表跳过", mapKey, e);
            }
        }
    }

    /** Reads aa_color_alert ({@code {kind:{round:[waves]}}}). Wave-level
     *  data for AA wave color alerts, independent of difficulty by nature. */
    private void parseAaColorAlert(JsonObject root) {
        JsonObject color = root.getAsJsonObject("aa_color_alert");
        if (color == null) {
            return;
        }
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

    /** Reads powerup_patterns.json:
     *  {@code {version, maps:{map:{type key:[{rounds:[...],digits:[...]}]}}}}. */
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

    /** Reads aa_round_details.json:
     *  {@code {aa_rounds:[{round,recommendedPoints,hasGiant,hasOldOne,dangerLevel}]}}. */
    public void addAaRoundDetails(JsonObject root) {
        JsonArray rounds = root.getAsJsonArray("aa_rounds");
        if (rounds == null) {
            return;
        }
        for (JsonElement e : rounds) {
            JsonObject obj = e.getAsJsonObject();
            int round = obj.get("round").getAsInt();
            List<String> points = new ArrayList<>();
            JsonArray pointsArr = obj.getAsJsonArray("recommendedPoints");
            if (pointsArr != null) {
                for (JsonElement s : pointsArr) {
                    String name = s.getAsString();
                    if (name != null && !name.isEmpty()) {
                        points.add(name);
                    }
                }
            }
            boolean hasGiant = obj.get("hasGiant").getAsBoolean();
            boolean hasOldOne = obj.get("hasOldOne").getAsBoolean();
            int dangerLevel = obj.get("dangerLevel").getAsInt();
            aaRoundDetails.put(round, new AaRoundDetail(round, points, hasGiant, hasOldOne, dangerLevel));
        }
    }

    // ==================== Convenience accessors ====================

    /**
     * Wave time table for a map, in seconds, indexed
     * {@code [round-1][wave-1]}. A missing map returns a shared empty table.
     * On a hit, returns the internal 2D array directly, with no defensive
     * copy; callers treat it as read-only.
     *
     * @param map map id; may be {@code null}
     * @return the internal wave table or a shared empty table, never {@code null}
     */
    public int[][] getRoundTimes(MapId map) {
        return waveTimes.getOrDefault(map, EMPTY_WAVES);
    }

    /** Whether the data table has a wave time table for a map. Unlike
     *  {@link #getRoundTimes}'s empty-table fallback, this tells apart
     *  "genuinely no data". */
    public boolean hasRoundTimes(MapId map) {
        return waveTimes.containsKey(map);
    }

    /** Round cap for a map, inclusive. Comes from the {@code max_round}
     *  field in wave_times.json (AA=105, DE/BB/Prison=30). {@code max_round}
     *  wins; a missing field falls back to the wave table's row count; if
     *  both are missing, returns {@code Integer.MAX_VALUE}—callers use the
     *  cap to narrow the display, and a missing datum is better left
     *  unlimited than made to erase rounds by mistake. */
    public int getMaxRound(MapId map) {
        Integer max = maxRounds.get(map);
        if (max != null) {
            return max;
        }
        int[][] times = waveTimes.get(map);
        return times != null && times.length > 0 ? times.length : Integer.MAX_VALUE;
    }

    /**
     * Boss rounds for a map at a difficulty. An unidentified difficulty or a
     * missing tier falls back to normal; a map with no data returns a shared
     * empty array. On a hit, returns the internal array directly, with no
     * defensive copy.
     *
     * @param map map id; may be {@code null}
     * @param difficulty difficulty id; may be {@code null}, treated as unidentified
     * @return the internal round array or a shared empty array, never {@code null}
     */
    public int[] getBossRounds(MapId map, DifficultyId difficulty) {
        Map<DifficultyId, int[]> table = bossRounds.get(map);
        if (table == null || table.isEmpty()) {
            return EMPTY_INTS;
        }
        if (difficulty != DifficultyId.NULL) {
            int[] rounds = table.get(difficulty);
            if (rounds != null) {
                return rounds;
            }
        }
        return table.getOrDefault(DifficultyId.NORMAL, EMPTY_INTS);
    }

    /**
     * All patterns for a powerup on a map. On a hit, returns the internal
     * mutable list; a missing map or type returns an immutable empty list.
     * Neither result is {@code null}.
     *
     * @param map map id; may be {@code null}
     * @param typeKey powerup key; may be {@code null}
     * @return the internal pattern list or an immutable empty list
     */
    public List<PowerupPattern> getPowerupPatterns(MapId map, String typeKey) {
        Map<String, List<PowerupPattern>> patterns = powerupPatterns.get(map);
        if (patterns == null) {
            return List.of();
        }
        return patterns.getOrDefault(typeKey, List.of());
    }

    /**
     * All round → wave mappings for one color-alert kind. On a hit, returns
     * the internal mutable map; a missing or {@code null} kind returns an
     * immutable empty map.
     *
     * @param kind alert kind key
     * @return the internal alert map or an immutable empty map, never {@code null}
     */
    public Map<Integer, List<Integer>> getColorAlertWaves(String kind) {
        return colorAlert.getOrDefault(kind, Map.of());
    }

    /**
     * AA command detail for one round. Returns the internal object directly;
     * a round the table doesn't cover returns {@code null}.
     *
     * @param round round number
     * @return the detail, or {@code null} when not covered
     */
    public AaRoundDetail getAaRoundDetail(int round) {
        return aaRoundDetails.get(round);
    }

    /** JsonArray to int[]; null gives an empty array. Every table parser
     *  goes through this to pull an int sequence. */
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