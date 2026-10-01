package cn.gsfy.nmz.client.data.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Full Zombies stats for one player, in the display-ready form
 * {@code ZombiesStatsParser} extracts from every {@code zombie*} key under
 * Hypixel's {@code player.stats.Arcade}.
 *
 * <p>Every field is ready for the UI to render directly: labels are already
 * translated and some numbers are pre-formatted. So the parser can fill them
 * one by one, the public fields and collections stay mutable on purpose—
 * {@code final} on a collection fixes the reference, not its content—and
 * callers get the internal containers themselves, with no defensive copying.
 */
public final class ZombiesStats {

    // ---- Player overview ----
    /** Hyphen-less UUID. Sent with the request and returned as-is, so it's
     *  clear whose data this is. */
    public String uuid = "";
    /** Current display name. Falls back to {@code playername} when
     *  {@code displayname} is missing; an empty string when both are. */
    public String displayName = "";
    /** Raw Hypixel karma count. 0 when the field is missing or not a number. */
    public long karma;
    /** Raw Hypixel networkExp. 0 when the field is missing or not a number. */
    public long networkExp;
    /** Network level, computed from {@code networkExp}. Non-positive XP gives 0. */
    public int networkLevel;
    /** First play time, in epoch ms. 0 means no record. */
    public long firstLogin;
    /** Last login time, in epoch ms. */
    public long lastLogin;
    /** Last logout time, in epoch ms. */
    public long lastLogout;

    /** Overall stats. Internal mutable list, ordered by known-item priority;
     *  unknown items keep the order they were parsed in. */
    public final List<Row> overall = new ArrayList<>();
    /** Per-map stats. Internal mutable map; maps appear in a fixed table order,
     *  and each map's rows follow the parser's {@code STAT_ORDER}. Unknown
     *  items outside that table go after, in JSON order. */
    public final Map<String, List<MapStat>> perMap = new LinkedHashMap<>();
    /** Enemy kills. Internal mutable map, sorted by count descending when
     *  parsing finishes; ties keep JSON order. */
    public final Map<String, Long> enemyKills = new LinkedHashMap<>();
    /** Fastest round records. Internal mutable tree map:
     *  round → (range label → seconds). */
    public final TreeMap<Integer, Map<String, Long>> fastestTimes = new TreeMap<>();

    /** One row: label on the left in grey, value on the right in white.
     *  The smallest unit the UI renders. */
    public static final class Row {
        public final String label;
        public final String value;

        /** Both values arrive already prepared by the parser. */
        public Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** One stat's values across difficulties. Normal/hard/RIP keys of the
     *  same family merge into one row. */
    public static final class MapStat {
        public final String label;
        /** Difficulty label → value (overall/normal/hard/RIP). */
        public final LinkedHashMap<String, Long> values = new LinkedHashMap<>();

        public MapStat(String label) {
            this.label = label;
        }
    }
}