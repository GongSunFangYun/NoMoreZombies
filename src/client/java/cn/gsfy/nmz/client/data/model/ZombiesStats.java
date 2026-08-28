package cn.gsfy.nmz.client.data.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家 Zombies 模式完整数据——ZombiesStatsParser 从 Hypixel {@code player.stats.Arcade}
 * 里所有含 "zombie" 的 key 榨出来的显示态结果。
 *
 * <p>字段全部是界面可直接渲染的现成形态：label 已翻译、数值已带千分位，
 * 渲染层不需要再碰原始 JSON。
 */
public final class ZombiesStats {

    // ---- 玩家概览 ----
    /** 无连字符 UUID——请求时带入，原样带回，方便知道这份数据属于谁。 */
    public String uuid = "";
    /** 当前显示名——displayname 缺失时退回 playername。 */
    public String displayName = "";
    public long karma;
    public long networkExp;
    /** 网络等级——由 networkExp 换算，展示在概览区。 */
    public int networkLevel;
    /** 首次游玩时间（epoch ms，0 = 没有记录）。 */
    public long firstLogin;
    /** 最近登录时间（epoch ms）。 */
    public long lastLogin;
    /** 最近下线时间（epoch ms）。 */
    public long lastLogout;

    /** 综合统计（无地图后缀的 {@code X_zombies}）：按优先级排好的 (label, value) 行。 */
    public final List<Row> overall = new ArrayList<>();
    /** 各地图统计：地图 label → 有序行，每行按难度分列（综合 / 普通 / 困难 / RIP）。 */
    public final Map<String, List<MapStat>> perMap = new LinkedHashMap<>();
    /** 敌人击杀：敌名 label → 数量，已按数量降序排好。 */
    public final Map<String, Long> enemyKills = new LinkedHashMap<>();
    /** 最快回合记录：回合数(10/20/30) → (范围 label → 秒)。范围 label「全局」= 不限地图。 */
    public final TreeMap<Integer, Map<String, Long>> fastestTimes = new TreeMap<>();
    /** 杂项——非数值项（隐藏教程 / 排行榜设置之类），原样展示在末尾一栏。 */
    public final List<Row> misc = new ArrayList<>();

    /** 一行数据：label 左灰、value 右白——界面渲染的最小单位。 */
    public static final class Row {
        public final String label;
        public final String value;

        public Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** 某统计项在各难度下的取值——普通 / 困难 / RIP 一个 key 家族并成一行。 */
    public static final class MapStat {
        public final String label;
        /** 难度 label → 数值（综合 / 普通 / 困难 / RIP）。 */
        public final LinkedHashMap<String, Long> values = new LinkedHashMap<>();

        public MapStat(String label) {
            this.label = label;
        }
    }
}
