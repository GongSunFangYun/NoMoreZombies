package cn.gsfy.nmz.client.data;

import cn.gsfy.nmz.client.data.model.ZombiesStats;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Hypixel 原始 JSON 里的 Zombies 数据全部榨出来——遍历 {@code player.stats.Arcade}
 * 中所有含 "zombie" 的 key，按三种前缀正则分类后各自入座。
 *
 * <p>分类规则：{@code fastest_time_<N>_zombies[_map[_diff]]} 是最快回合记录（单位秒）；
 * {@code <敌人>_zombie_kills_zombies} 是敌人击杀（敌名剥掉尾缀 {@code _zombie}）；
 * {@code <stat>_zombies[_map[_diff]]} 是综合 / 各地图各难度统计；其余归杂项
 * （布尔 / 字符串等非数值项）。数值统一用 getAsLong 容错；label 走翻译 key（跟随客户端语言）。
 */
public final class ZombiesStatsParser {

    /** 取翻译文本——label 最终都走这里，跟随客户端语言显示。 */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    private static final Pattern P_FASTEST =
            Pattern.compile("^fastest_time_(\\d+)_zombies(?:_(alienarcadium|deadend|prison|badblood)(?:_(normal|hard|rip))?)?$");
    private static final Pattern P_ENEMY =
            Pattern.compile("^(.+)_zombie_kills_zombies$");
    private static final Pattern P_STAT =
            Pattern.compile("^(.*)_zombies(?:_(alienarcadium|deadend|prison|badblood)(?:_(normal|hard|rip))?)?$");

    /** 综合统计显示优先级——已知项按此处顺序，未知项排最后（按字母序兜底）。 */
    private static final List<String> OVERALL_ORDER = List.of(
            "wins", "best_round", "zombie_kills", "headshots", "bullets_shot", "bullets_hit",
            "deaths", "times_knocked_down", "players_revived", "windows_repaired",
            "doors_opened", "total_rounds_survived");

    /** 地图显示顺序——固定按 Hypixel 常见展示排，perMap 输出才不会乱跳。 */
    private static final List<String> MAP_ORDER =
            List.of("alienarcadium", "deadend", "prison", "badblood");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ZombiesStatsParser() {
    }

    /**
     * 把 Hypixel 的 player 对象解析成 {@link ZombiesStats} 显示态数据；
     * 缺 stats / Arcade 节点时返回只含概览的空壳，不抛错。
     *
     * @param player       Hypixel 响应的 player 对象
     * @param uuidNoHyphen 请求所用的无连字符 UUID
     */
    public static ZombiesStats parse(JsonObject player, String uuidNoHyphen) {
        ZombiesStats s = new ZombiesStats();
        s.uuid = uuidNoHyphen;
        s.displayName = firstString(player, "displayname", "playername");
        s.karma = getLong(player, "karma");
        s.networkExp = getLong(player, "networkExp");
        s.networkLevel = networkLevel(s.networkExp);
        s.firstLogin = getLong(player, "firstLogin");
        s.lastLogin = getLong(player, "lastLogin");
        s.lastLogout = getLong(player, "lastLogout");

        JsonElement statsEl = player.get("stats");
        if (statsEl == null || !statsEl.isJsonObject()) {
            return s;
        }
        JsonElement arcadeEl = statsEl.getAsJsonObject().get("Arcade");
        if (arcadeEl == null || !arcadeEl.isJsonObject()) {
            return s;
        }
        JsonObject arcade = arcadeEl.getAsJsonObject();

        // 中间态两层 map：地图 key → 统计 key → MapStat，把普通 / 困难 / RIP 三档
        // 并进同一行（MapStat.values 按难度分列），等全部读完再落盘。
        Map<String, Map<String, ZombiesStats.MapStat>> mapStatAcc = new LinkedHashMap<>();
        // 综合统计先暂存 {statKey, Row}，最后统一按 OVERALL_ORDER 排序输出。
        List<Object[]> overallAcc = new ArrayList<>();

        for (Map.Entry<String, JsonElement> e : arcade.entrySet()) {
            String key = e.getKey();
            if (!key.contains("zombie")) {
                continue;
            }
            JsonElement value = e.getValue();
            if (!value.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive p = value.getAsJsonPrimitive();

            Matcher mFast = P_FASTEST.matcher(key);
            if (mFast.matches()) {
                if (p.isNumber()) {
                    int rounds = Integer.parseInt(mFast.group(1));
                    String map = mFast.group(2);
                    String diff = mFast.group(3);
                    String scope = fastestScope(map, diff);
                    s.fastestTimes.computeIfAbsent(rounds, k -> new LinkedHashMap<>())
                            .put(scope, p.getAsLong());
                }
                continue;
            }

            Matcher mEnemy = P_ENEMY.matcher(key);
            if (mEnemy.matches()) {
                if (p.isNumber()) {
                    s.enemyKills.put(enemyLabel(mEnemy.group(1)), p.getAsLong());
                }
                continue;
            }

            Matcher mStat = P_STAT.matcher(key);
            if (mStat.matches()) {
                if (!p.isNumber()) {
                    continue;
                }
                String stat = mStat.group(1);
                String map = mStat.group(2);
                String diff = mStat.group(3);
                long num = p.getAsLong();
                if (map == null) {
                    overallAcc.add(new Object[]{stat, new ZombiesStats.Row(statLabel(stat), fmt(num))});
                } else {
                    Map<String, ZombiesStats.MapStat> diffRows =
                            mapStatAcc.computeIfAbsent(map, k -> new LinkedHashMap<>());
                    ZombiesStats.MapStat ms = diffRows.computeIfAbsent(stat, k -> new ZombiesStats.MapStat(statLabel(stat)));
                    ms.values.put(diffLabel(diff), num);
                }
                continue;
            }

            // 杂项：非数值项（布尔 / 字符串）单独收一栏，label 用 pretty 兜底。
            s.misc.add(new ZombiesStats.Row(pretty(key), primitiveDisplay(p)));
        }

        // 综合统计按优先级排序——orderOf 对未知项返回最大数，自然落到末尾。
        overallAcc.sort(Comparator.comparingInt(a -> orderOf((String) a[0])));
        for (Object[] a : overallAcc) {
            s.overall.add((ZombiesStats.Row) a[1]);
        }

        // 地图统计按 MAP_ORDER 固定顺序输出——顺序稳定，界面才不随解析抖动。
        for (String mapKey : MAP_ORDER) {
            Map<String, ZombiesStats.MapStat> diffRows = mapStatAcc.get(mapKey);
            if (diffRows == null) {
                continue;
            }
            s.perMap.put(mapLabel(mapKey), new ArrayList<>(diffRows.values()));
        }

        // 敌人击杀按数量降序——排序结果重灌进 LinkedHashMap 保住顺序，界面直接遍历。
        List<Map.Entry<String, Long>> kills = new ArrayList<>(s.enemyKills.entrySet());
        kills.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        s.enemyKills.clear();
        for (Map.Entry<String, Long> en : kills) {
            s.enemyKills.put(en.getKey(), en.getValue());
        }

        return s;
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                return e.getAsString();
            }
        }
        return "";
    }

    private static long getLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsLong();
        }
        return 0L;
    }

    /** 网络等级换算（沿用 Hypixel 公式）：level = floor(sqrt(2*exp + 30625)/50 - 2.5)。 */
    private static int networkLevel(long exp) {
        if (exp <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.sqrt(2 * exp + 30625) / 50 - 2.5);
    }

    private static String statLabel(String stat) {
        return switch (stat) {
            case "wins" -> trans("nomorezombies.query.stat.wins");
            case "best_round" -> trans("nomorezombies.query.stat.best_round");
            case "zombie_kills" -> trans("nomorezombies.query.stat.zombie_kills");
            case "headshots" -> trans("nomorezombies.query.stat.headshots");
            case "bullets_shot" -> trans("nomorezombies.query.stat.bullets_shot");
            case "bullets_hit" -> trans("nomorezombies.query.stat.bullets_hit");
            case "deaths" -> trans("nomorezombies.query.stat.deaths");
            case "times_knocked_down" -> trans("nomorezombies.query.stat.times_knocked_down");
            case "players_revived" -> trans("nomorezombies.query.stat.players_revived");
            case "windows_repaired" -> trans("nomorezombies.query.stat.windows_repaired");
            case "doors_opened" -> trans("nomorezombies.query.stat.doors_opened");
            case "total_rounds_survived" -> trans("nomorezombies.query.stat.total_rounds_survived");
            default -> pretty(stat);
        };
    }

    private static String mapLabel(String map) {
        return switch (map) {
            case "alienarcadium" -> trans("nomorezombies.query.map.alienarcadium");
            case "deadend" -> trans("nomorezombies.query.map.deadend");
            case "prison" -> trans("nomorezombies.query.map.prison");
            case "badblood" -> trans("nomorezombies.query.map.badblood");
            default -> pretty(map);
        };
    }

    private static String diffLabel(String diff) {
        if (diff == null || diff.isEmpty()) {
            return trans("nomorezombies.query.diff.total");
        }
        return switch (diff) {
            case "normal" -> trans("nomorezombies.query.diff.normal");
            case "hard" -> trans("nomorezombies.query.diff.hard");
            case "rip" -> "RIP";
            default -> diff;
        };
    }

    private static String fastestScope(String map, String diff) {
        if (map == null) {
            return trans("nomorezombies.query.scope.overall");
        }
        return mapLabel(map) + " " + diffLabel(diff);
    }

    private static String enemyLabel(String internal) {
        String s = internal;
        if (s.endsWith("_zombie")) {
            s = s.substring(0, s.length() - "_zombie".length());
        }
        return pretty(s);
    }

    /** 下划线转空格、首字母大写——没翻译 key 的英文 label 兜底显示。 */
    private static String pretty(String key) {
        if (key == null || key.isEmpty()) {
            return key == null ? "" : key;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private static String primitiveDisplay(JsonPrimitive p) {
        if (p.isNumber()) {
            return fmt(p.getAsLong());
        }
        if (p.isBoolean()) {
            return p.getAsBoolean() ? trans("nomorezombies.query.bool.yes") : trans("nomorezombies.query.bool.no");
        }
        return p.getAsString();
    }

    private static int orderOf(String statKey) {
        int idx = OVERALL_ORDER.indexOf(statKey);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** 秒 → m:ss / h:mm:ss——最快回合记录在界面上的统一格式。 */
    public static String formatTime(long seconds) {
        if (seconds >= 3600) {
            return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
