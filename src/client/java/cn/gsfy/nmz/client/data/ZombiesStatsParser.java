package cn.gsfy.nmz.client.data;

import cn.gsfy.nmz.client.data.model.ZombiesStats;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Squeezes all Zombies data out of Hypixel's raw JSON - walks every key in
 * {@code player.stats.Arcade} containing "zombie", classifies by three
 * prefix regexes, and files each into its slot.
 *
 * <p>Classification: {@code fastest_time_<N>_zombies[_map[_diff]]} is a
 * fastest-round record (in seconds); {@code <enemy>_zombie_kills_zombies} is
 * an enemy kill (the enemy name strips its {@code _zombie} suffix);
 * {@code <stat>_zombies[_map[_diff]]} is an overall / per-map per-difficulty
 * stat. Everything else is skipped: misc keys (non-numeric / unregistered
 * primitives) have neither stable semantics nor any reader.
 *
 * <p>Boundaries and accounting: parsing is <b>lenient</b> - a missing section
 * only yields an empty shell, never an exception; the display layer digests
 * missing data with fixed rows and placeholders. Row order is locked by three
 * ordering tables: {@code STAT_ORDER} (nine rows per map, best round first),
 * {@code DIFF_ORDER} (normal/hard/RIP/total) and {@code SCOPE_ORDER} (the
 * time stats' ten items); the raw JSON's key enumeration order is unstable,
 * and without ordering the same data could parse into different row orders.
 * Labels all go through translation keys ({@code nomorezombies.query.*}) so
 * the UI follows the client language.
 */
public final class ZombiesStatsParser {

    /** Resolves translation text - every label ends up here, following the client language. */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    private static final Pattern P_FASTEST =
            Pattern.compile("^fastest_time_(\\d+)_zombies(?:_(alienarcadium|deadend|prison|badblood)(?:_(normal|hard|rip))?)?$");
    private static final Pattern P_ENEMY =
            Pattern.compile("^(.+)_zombie_kills_zombies$");
    private static final Pattern P_STAT =
            Pattern.compile("^(.*)_zombies(?:_(alienarcadium|deadend|prison|badblood)(?:_(normal|hard|rip))?)?$");

    /** Overall-stats display priority - known items in this order, unknown ones last (keeping parse encounter order). */
    private static final List<String> OVERALL_ORDER = List.of(
            "wins", "best_round", "zombie_kills", "headshots", "bullets_shot", "bullets_hit",
            "deaths", "times_knocked_down", "players_revived", "windows_repaired",
            "doors_opened", "total_rounds_survived");

    /**
     * Map display order - <b>Dead End / Bad Blood / Alien Arcadium /
     * Prison</b>.
     *
     * <p>This is not Hypixel's raw order but the display order the UI adopted
     * everywhere: the query panel's overall summary, the expanded map nodes,
     * and the per-map stat iteration all follow it - one dimension, one
     * ordering in the UI. Changing this must be synced with
     * {@code QueryDataOverview.MAPS} and {@link #SCOPE_ORDER}
     * (the regression script compares verbatim).
     */
    private static final List<String> MAP_ORDER =
            List.of("deadend", "badblood", "alienarcadium", "prison");

    /**
     * Internal keys of {@link #MAP_ORDER}, for the UI to build
     * <b>language-independent</b> node paths.
     *
     * <p>Positionally aligned with {@code QueryDataOverview.MAPS} (same
     * order, same length); changing one means changing the other - the
     * regression script compares both tables verbatim.
     */
    public static final List<String> MAP_INTERNAL_KEYS = List.copyOf(MAP_ORDER);

    /**
     * Within one map, the stat items' display order - <b>best round first,
     * the rest grouped by the cumulative accounting</b>.
     *
     * <p>Best round measures "which round was reached on this map", a
     * different dimension from the other eight "how much was done" items, so
     * it takes the first row alone; the other eight match the order of
     * {@code QueryDataOverview.SUM_STATS}' cumulative items, so the order read
     * in "cumulative data" matches the per-map detail.
     *
     * <p>The parser reorders each map's stat list by this table, and the UI
     * iterates directly without re-sorting; stat items outside the table (new
     * Hypixel items) keep their original order appended at the end - better
     * one extra row than a silent drop.
     */
    private static final List<String> STAT_ORDER = List.of(
            "best_round", "total_rounds_survived", "wins", "zombie_kills",
            "players_revived", "times_knocked_down", "deaths",
            "windows_repaired", "doors_opened");

    /**
     * The separator in fastest-round "map+difficulty" labels - <b>a hyphen,
     * not a space</b>.
     *
     * <p>English map names carry spaces ({@code Dead End}); separating by
     * space would blur the boundary, so the hyphen becomes the single
     * boundary marker the UI splits on.
     */
    public static final String MAP_DIFF_SEP = "-";

    /**
     * The display order of fastest-round "map+difficulty" scopes.
     *
     * <p>One-to-one with {@code fastest_time_<N>_zombies_<map>_<diff>}:
     * DE three tiers / BB three tiers / AA Normal only / Prison three tiers,
     * ten items total - <b>AA has no Hard or RIP</b>, so the table gives it no
     * such rows. The parser reorders each round's scope table by this table
     * and the UI iterates directly without re-sorting.
     *
     * <p>No {@code global} tier: that is a cross-map total, not "which map at
     * which difficulty", and is dropped for display anyway (see
     * {@code fastestScope}).
     */
    private static final List<String[]> SCOPE_ORDER = List.of(
            new String[]{"deadend", "normal"},
            new String[]{"deadend", "hard"},
            new String[]{"deadend", "rip"},
            new String[]{"badblood", "normal"},
            new String[]{"badblood", "hard"},
            new String[]{"badblood", "rip"},
            new String[]{"alienarcadium", "normal"},
            new String[]{"prison", "normal"},
            new String[]{"prison", "hard"},
            new String[]{"prison", "rip"});

    private ZombiesStatsParser() {
    }

    /**
     * The ten fixed "map-difficulty" scopes per round in the fastest-round
     * records (translations), i.e. {@link #SCOPE_ORDER}'s translated list.
     *
     * <p>For the UI's "missing items still get a row": an uncleared scope
     * draws {@code --:--} instead of the row disappearing. Same format as the
     * scope labels in parse results (joined by {@link #MAP_DIFF_SEP}), so it
     * can be used for lookups directly.
     *
     * @return scope labels in {@link #SCOPE_ORDER} order; read-only for callers
     */
    public static List<String> fastestScopeLabels() {
        List<String> labels = new ArrayList<>(SCOPE_ORDER.size());
        for (String[] mapDiff : SCOPE_ORDER) {
            labels.add(mapLabel(mapDiff[0]) + MAP_DIFF_SEP + diffLabel(mapDiff[1]));
        }
        return labels;
    }

    /**
     * The difficulty tiers a map actually has (internal keys:
     * normal/hard/rip), derived from {@link #SCOPE_ORDER}.
     *
     * <p>Alien Arcadium has only Normal and must not be drawn 0 for
     * Hard/RIP; the "Total" tier is not here - the UI adds it separately.
     * Same source as {@link #SCOPE_ORDER}, so the two cannot disagree on
     * "which map has which tiers".
     *
     * @param mapInternalKey a key from {@link #MAP_INTERNAL_KEYS}
     * @return difficulty keys ordered normal/hard/rip; an empty list for unknown maps
     */
    public static List<String> mapDiffKeys(String mapInternalKey) {
        List<String> out = new ArrayList<>(3);
        for (String[] mapDiff : SCOPE_ORDER) {
            if (mapDiff[0].equals(mapInternalKey)) {
                out.add(mapDiff[1]);
            }
        }
        return out;
    }

    /**
     * Parses Hypixel's player object into {@link ZombiesStats} display-state
     * data; a missing stats/Arcade node returns a shell with only the
     * overview, never an error.
     *
     * @param player the non-{@code null} Hypixel player object
     * @param uuidNoHyphen the hyphen-less UUID used by the request, written into the result as is
     * @return a fresh stats object the UI may keep editing; never {@code null}
     * @throws NullPointerException when {@code player} is {@code null}
     */
    public static ZombiesStats parse(JsonObject player, String uuidNoHyphen) {
        ZombiesStats s = new ZombiesStats();
        s.uuid = uuidNoHyphen;
        s.displayName = firstString(player);
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

        // Two-layer intermediate maps: map key -> stat key -> MapStat, merging the
        // normal/hard/RIP tiers into one row (MapStat.values columns by difficulty),
        // flushed only after everything is read
        Map<String, Map<String, ZombiesStats.MapStat>> mapStatAcc = new LinkedHashMap<>();
        // Overall stats staged as {statKey, Row}, sorted by OVERALL_ORDER at the end
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
                    String scope = fastestScope(mFast.group(2), mFast.group(3));
                    if (scope != null) {
                        s.fastestTimes.computeIfAbsent(rounds, k -> new LinkedHashMap<>())
                                .put(scope, p.getAsLong());
                    }
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
            if (!mStat.matches()) {
                continue;
            }
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
        }

        // Overall stats sorted by priority - orderOf returns the max int for unknown items,
        // which naturally sink to the end
        overallAcc.sort(Comparator.comparingInt(a -> orderOf((String) a[0])));
        for (Object[] a : overallAcc) {
            s.overall.add((ZombiesStats.Row) a[1]);
        }

        // Per-map stats output in fixed MAP_ORDER, each map internally ordered by STAT_ORDER -
        // stable order keeps the UI from jittering between parses and lets it draw fixed rows
        for (String mapKey : MAP_ORDER) {
            Map<String, ZombiesStats.MapStat> diffRows = mapStatAcc.get(mapKey);
            if (diffRows == null) {
                continue;
            }
            s.perMap.put(mapLabel(mapKey), orderStats(diffRows));
        }

        // Each stat item's difficulty values reordered by DIFF_ORDER - "normal hard rip total",
        // no longer following Arcade's key enumeration order
        // (which yields a random tier order; the same data could read differently twice)
        for (List<ZombiesStats.MapStat> stats : s.perMap.values()) {
            for (ZombiesStats.MapStat ms : stats) {
                Map<String, Long> ordered = orderDiffs(ms.values);
                ms.values.clear();
                ms.values.putAll(ordered);
            }
        }

        // Enemy kills sorted by count descending - re-poured into a LinkedHashMap to keep the
        // order, so the UI can iterate directly
        List<Map.Entry<String, Long>> kills = new ArrayList<>(s.enemyKills.entrySet());
        kills.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        s.enemyKills.clear();
        for (Map.Entry<String, Long> en : kills) {
            s.enemyKills.put(en.getKey(), en.getValue());
        }

        // Fastest-round records reordered by SCOPE_ORDER: the table's ten items in the required
        // order, out-of-table items kept at the end
        // (out-of-table can only be a new Hypixel tier; better one extra row than a silently
        // dropped record)
        for (Map.Entry<Integer, Map<String, Long>> e : s.fastestTimes.entrySet()) {
            e.setValue(orderScopes(e.getValue()));
        }

        return s;
    }

    /**
     * Within one stat item, the four difficulty tiers' display order -
     * <b>normal, hard, RIP, total</b>.
     *
     * <p>A different matter from {@code SCOPE_ORDER} (the ten-item order of
     * fastest-round records): that table orders "which maps/tiers to show",
     * this one orders "which tier comes first within one item". The UI draws
     * by {@code MapStat.values}' iteration order, so the order must be fixed
     * here - Arcade's key enumeration order is unreliable, and without
     * ordering the same data could read differently twice.
     *
     * <p><b>"Total" goes last, the same position as the cumulative data
     * rows' "Total"</b>: that value is the sum of the other three tiers, the
     * row's total, read as "three tiers first, then the total"; putting it
     * first would show two "Totals" back to back in the UI, one word in two
     * positions.
     */
    private static final List<String> DIFF_ORDER =
            List.of("normal", "hard", "rip", "total");

    /**
     * Reorders one stat item's difficulty values by {@link #DIFF_ORDER};
     * out-of-table keys keep their original order appended after.
     *
     * <p>Keys are <b>translations</b> (the parser stores diffLabel's result as
     * the key in values from the start), so comparisons must also go through
     * diffLabel - internal keys cannot be compared directly.
     */
    private static Map<String, Long> orderDiffs(Map<String, Long> values) {
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        for (String key : DIFF_ORDER) {
            String label = diffLabel(key);
            Long v = values.get(label);
            if (v != null) {
                sorted.put(label, v);
            }
        }
        for (Map.Entry<String, Long> e : values.entrySet()) {
            if (!sorted.containsKey(e.getKey())) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        return sorted;
    }

    /**
     * Reorders one map's stat list by {@link #STAT_ORDER}; out-of-table items
     * keep their original order appended after.
     *
     * <p>The intermediate map is keyed by the <b>internal stat key</b> (the
     * translation swap has not happened yet), so internal keys compare
     * directly here; the output {@link ZombiesStats.MapStat} still carries the
     * translated label fixed at parse time.
     */
    private static List<ZombiesStats.MapStat> orderStats(Map<String, ZombiesStats.MapStat> diffRows) {
        List<ZombiesStats.MapStat> sorted = new ArrayList<>();
        for (String stat : STAT_ORDER) {
            ZombiesStats.MapStat ms = diffRows.get(stat);
            if (ms != null) {
                sorted.add(ms);
            }
        }
        for (Map.Entry<String, ZombiesStats.MapStat> e : diffRows.entrySet()) {
            if (!STAT_ORDER.contains(e.getKey())) {
                sorted.add(e.getValue());
            }
        }
        return sorted;
    }

    /**
     * The per-map stat items' display order (translations), i.e.
     * {@link #STAT_ORDER}'s translated list.
     *
     * <p>For the free query's "missing items still get a row": to list every
     * map's stat items at a fixed row count, the UI must first know which
     * items and in what order; <b>this table exists only in the parser</b>,
     * and the UI takes the translations back and looks them up in
     * {@code perMap} one by one rather than copying the internal-key table.
     *
     * @return translated labels in {@link #STAT_ORDER} order; read-only for callers
     */
    public static List<String> mapStatLabels() {
        List<String> labels = new ArrayList<>(STAT_ORDER.size());
        for (String stat : STAT_ORDER) {
            labels.add(statLabel(stat));
        }
        return labels;
    }

    /**
     * Reorders one round's scope table by {@link #SCOPE_ORDER}: table order
     * first, out-of-table entries appended in original order.
     *
     * <p>The {@code global} tier stopped being produced back in
     * {@link #fastestScope} (see its comment), so no filtering is needed here;
     * one genuinely read from an old cache would land in the "out-of-table"
     * group without displacing the ten-item order.
     */
    private static Map<String, Long> orderScopes(Map<String, Long> scopes) {
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        for (String[] mapDiff : SCOPE_ORDER) {
            String label = mapLabel(mapDiff[0]) + MAP_DIFF_SEP + diffLabel(mapDiff[1]);
            Long v = scopes.get(label);
            if (v != null) {
                sorted.put(label, v);
            }
        }
        for (Map.Entry<String, Long> e : scopes.entrySet()) {
            if (!sorted.containsKey(e.getKey())) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        return sorted;
    }

    /**
     * Display-name lookup: displayname first, playername second, an empty
     * string when both are missing.
     *
     * <p>No "candidate key array" parameter: there is one call site and the
     * keys are always these two - a array parameter would just make "which
     * keys were queried" something to trace through arguments.
     *
     * @param o Hypixel's player object
     * @return the player's display name; empty when neither key is a string
     */
    private static String firstString(JsonObject o) {
        for (String k : new String[]{"displayname", "playername"}) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                return e.getAsString();
            }
        }
        return "";
    }

    /**
     * Reads a numeric field as long - missing/mistyped fields fall back to 0
     * so parsing never breaks on one bad field.
     * Numbers are carried as long uniformly: the overview's login/logout
     * timestamps are epoch ms (magnitude 10^12), beyond int; the network-level
     * formula multiplies experience by 2 first, needing long headroom too.
     */
    private static long getLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsLong();
        }
        return 0L;
    }

    /** Network level conversion (Hypixel's formula):
     *  {@code level = floor(sqrt(2*exp + 30625)/50 - 2.5)}. */
    private static int networkLevel(long exp) {
        if (exp <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.sqrt(2 * exp + 30625) / 50 - 2.5);
    }

    /** Internal stat key -> {@code nomorezombies.query.stat.*} translation key;
     *  unregistered keys fall back to prettified display. */
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

    /** Map internal key -> nomorezombies.query.map.* translation key; unregistered keys fall back to prettified display. */
    private static String mapLabel(String map) {
        return switch (map) {
            case "alienarcadium" -> trans("nomorezombies.query.map.alienarcadium");
            case "deadend" -> trans("nomorezombies.query.map.deadend");
            case "prison" -> trans("nomorezombies.query.map.prison");
            case "badblood" -> trans("nomorezombies.query.map.badblood");
            default -> pretty(map);
        };
    }

    /** Difficulty key -> nomorezombies.query.diff.* translation key; empty means Total, RIP goes through translation (Chinese "安息"). */
    private static String diffLabel(String diff) {
        if (diff == null || diff.isEmpty()) {
            return trans("nomorezombies.query.diff.total");
        }
        return switch (diff) {
            case "normal" -> trans("nomorezombies.query.diff.normal");
            case "hard" -> trans("nomorezombies.query.diff.hard");
            case "rip" -> trans("nomorezombies.query.diff.rip");
            default -> diff;
        };
    }

    /**
     * The fastest-round scope label: {@code map-difficulty} (e.g.
     * "Dead End-RIP").
     *
     * <p><b>The separator is a hyphen, not a space</b>: English map names
     * carry spaces ("Dead End"), and space separation would blur the
     * "map name" / "difficulty" boundary, reading more like one item. The UI
     * splits at the last hyphen; see {@code QueryDataTree.splitScope}.
     *
     * <p><b>The {@code global} tier is no longer produced</b>: it is a
     * cross-map total, not "which map at which difficulty", and the panel's
     * time stats drop it anyway. Not producing it is cleaner than "produce
     * then filter" - one less place where a forgotten filter means one extra
     * row.
     */
    private static String fastestScope(String map, String diff) {
        if (map == null) {
            return null;
        }
        return mapLabel(map) + MAP_DIFF_SEP + diffLabel(diff);
    }

    /** Internal enemy key -> display name - strip the trailing {@code _zombie} first, then prettify
     *  (enemy kill labels have no translation key). */
    private static String enemyLabel(String internal) {
        String s = internal;
        if (s.endsWith("_zombie")) {
            s = s.substring(0, s.length() - "_zombie".length());
        }
        return pretty(s);
    }

    /** Underscores to spaces, first letters capitalized - the fallback display for English labels without a translation key. */
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

    /** The index of a stat key in OVERALL_ORDER; unregistered keys return the max int and naturally sink in sorting. */
    private static int orderOf(String statKey) {
        int idx = OVERALL_ORDER.indexOf(statKey);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** Thousands-separator formatting (%,d) - every numeric value in the overview uses this display. */
    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    /**
     * Formats seconds into the UI's time: under an hour {@code m:ss}, from
     * one hour up {@code h:mm:ss}; negative values are not clamped to zero
     * and follow Java's integer division/remainder rules.
     *
     * @param seconds seconds
     * @return the formatted time string
     */
    public static String formatTime(long seconds) {
        if (seconds >= 3600) {
            return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}