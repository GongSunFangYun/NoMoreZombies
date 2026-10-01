package cn.gsfy.nmz.client.features.querydata;

import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.ZombiesStatsParser;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The detail panel content for the in-game player query - <b>one flat
 * overview, no collapsible structure at all</b>.
 *
 * <p>The panel is a single top-down column of {@link Line}s (a label on
 * the left plus a value on the right, or a pure header line), folded by
 * {@link #wrap} into {@link DrawRow}s. The hierarchy ends there - in the
 * first seconds of a game what matters is "has this player won, how well
 * do they play", not a per-map per-difficulty breakdown.
 *
 * <p><b>Colors</b>: headers gold, labels gray, <b>all data white</b>; only
 * difficulty names get their own colors -
 * {@code Total (orange), Normal (green), Hard (red), RIP (dark red)}.
 * Lines that need segmented coloring (fastest clear / best map) are
 * described with {@link Span}s, drawn segment by segment by the screen.
 *
 * <p><b>Responsibility boundary</b>: this class produces only "which lines
 * to draw, what text each carries, what color" - no pixel coordinates, no
 * scrolling, no clipping; those live in {@code QueryDataScreen}.
 * It must therefore not reference {@code DrawContext} / {@code Screen} /
 * {@code MinecraftClient}, nor {@code QueryDataManager}: data may only be
 * read from the passed-in {@link ZombiesStats} plus {@link DataManager}'s
 * static tables (each map's clear round, used for gating).
 */
public final class QueryDataOverview {

    /** Timestamp format for the account overview (epoch ms -> local zone). */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Gap (px) between a row's label and its right-aligned value - also counted into the available width for the side-by-side test. */
    static final int GAP = 10;
    /**
     * Gap (px) between a cumulative row's label and its data block - tighter than {@link #GAP} so the label
     * and the block stay on one line in as narrow a panel as possible, before the stacked fallback is needed.
     */
    static final int GRID_LABEL_GAP = 6;

    /** Indent (px) of a wrapped continuation (the value) relative to the panel's left edge - keeps the hierarchy legible. */
    public static final int INDENT = 8;

    /** Gold for header/group-title lines. */
    public static final int COLOR_HEADER = 0xFFFF55;
    /** Gray for body-row labels. */
    public static final int COLOR_LABEL = 0xAAAAAA;
    /** White for right-aligned values (all data). */
    public static final int COLOR_VALUE = 0xFFFFFF;
    /**
     * The color of "Total" in grid rows (orange). The four map names now use
     * {@link #COLOR_MAP_NAME} so Total stands apart from them.
     *
     * <p>Same color as the {@link #DIFF_TOTAL} tier, and literally the same
     * value: the {@code total} branch of {@link #difficultyColor} returns it
     * - two separate {@code 0xFFA500} literals would sooner or later get one
     * changed and not the other.
     */
    public static final int COLOR_MAP = 0xFFA500;
    /**
     * The color of the four <b>map names</b> in grid rows: aqua, so they read apart from the orange
     * "Total" next to them while staying inside the game's own palette (yellow headers, gray labels,
     * white values, green / red tier names). Only the map names use it; "Total" keeps {@link #COLOR_MAP}.
     */
    public static final int COLOR_MAP_NAME = 0x55FFFF;

    /** Difficulty palette - Total (orange). */
    public static final String DIFF_TOTAL = "total";
    /** Difficulty palette - Normal (green). */
    public static final String DIFF_NORMAL = "normal";
    /** Difficulty palette - Hard (red). */
    public static final String DIFF_HARD = "hard";
    /** Difficulty palette - RIP (dark red). */
    public static final String DIFF_RIP = "rip";

    /**
     * The three selectable difficulties ("Total" excluded): the order is the
     * display order.
     *
     * <p><b>AA will only ever have the "Normal" tier</b> - Hypixel has no
     * hard/RIP keys for it, so "sum across difficulties" degrades naturally
     * to "just itself" for AA, with no special case needed: walk one table
     * and add however many tiers a map has - that is why this design needs
     * no exceptions. The same goes for the average-time divisor: the number
     * of tiers whose time was actually retrieved (1 for AA).
     */
    private static final List<String> DIFFS = List.of(DIFF_NORMAL, DIFF_HARD, DIFF_RIP);

    /**
     * The cumulative stats - <b>eight items, each the sum of one stat across
     * the four maps</b>.
     *
     * <p>After each item is its parser-layer "combined stats" key
     * ({@code nomorezombies.query.stat.*}): rounds survived / wins / zombie
     * kills / revives / downs / deaths / windows repaired / doors opened.
     * The panel's left labels use the {@code query.overview.total*} group
     * ("Total rounds survived"...), which need not match the key's own
     * Chinese name - a cumulative item says "these four maps added up",
     * while {@code stat.*} is the parser layer's per-map row name.
     *
     * <p><b>The order is the display order</b>: survived / wins / kills /
     * revives / downs / deaths / windows / doors - matching
     * {@code ZombiesStatsParser.STAT_ORDER} from its second item on (best
     * round lives only in the per-map detail, not in the cumulative items).
     * With both tables in the same order, the player reads the same sequence
     * in the free query's "cumulative data" as in the expanded per-map
     * detail.
     */
    private static final List<SumStat> SUM_STATS = List.of(
            new SumStat("nomorezombies.query.overview.totalRounds", "nomorezombies.query.stat.total_rounds_survived"),
            new SumStat("nomorezombies.query.overview.totalWins", "nomorezombies.query.stat.wins"),
            new SumStat("nomorezombies.query.overview.totalKills", "nomorezombies.query.stat.zombie_kills"),
            new SumStat("nomorezombies.query.overview.totalRevived", "nomorezombies.query.stat.players_revived"),
            new SumStat("nomorezombies.query.overview.totalKnockedDown", "nomorezombies.query.stat.times_knocked_down"),
            new SumStat("nomorezombies.query.overview.totalDeaths", "nomorezombies.query.stat.deaths"),
            new SumStat("nomorezombies.query.overview.totalWindows", "nomorezombies.query.stat.windows_repaired"),
            new SumStat("nomorezombies.query.overview.totalDoors", "nomorezombies.query.stat.doors_opened"));

    /** Gap between adjacent cells in one row (plain spaces); the free query's grid rows also use it within a column. */
    private static final String SUM_SEP = "   ";

    /**
     * The four maps: UI label key + the {@link MapId} used against the data
     * table (fetches the map's clear round, for gating).
     *
     * <p><b>The order is the display order</b>: Dead End / Bad Blood / Alien
     * Arcadium / Prison, matching {@code ZombiesStatsParser.MAP_ORDER} (the
     * regression script compares verbatim).
     *
     * <p>Package-visible: {@code QueryDataTree} must build <b>all four map
     * nodes</b> from this same table (maps the player never played
     * included), so the free query must not keep its own copy of the map
     * table.
     */
    static final List<MapRef> MAPS = List.of(
            new MapRef("nomorezombies.query.map.deadend", MapId.DEAD_END),
            new MapRef("nomorezombies.query.map.badblood", MapId.BAD_BLOOD),
            new MapRef("nomorezombies.query.map.alienarcadium", MapId.ALIEN_ARCADIUM),
            new MapRef("nomorezombies.query.map.prison", MapId.PRISON));

    // ----Best-map weights (wins : kills : speed)----
    /** Weight: most wins - the strongest signal that "he knows this map". */
    private static final double W_WINS = 3.0;
    /** Weight: most zombie kills - secondary evidence, reflecting time invested in the map. */
    private static final double W_KILLS = 2.0;
    /** Weight: shortest average clear time across difficulties - the weakest evidence, only breaks ties the first two cannot. */
    private static final double W_SPEED = 1.0;

    /** Placeholder drawn on the right when data is unavailable - <b>the only one in the codebase</b>; the tree panel and the per-map detail read it too. */
    static final String DASH = "-";

    private QueryDataOverview() {
    }

    // ----Line model----

    /**
     * One colored segment within a line.
     *
     * <p>A non-null {@code difficulty} marks the segment as a difficulty
     * name, colored by {@link #difficultyColor(String)}; otherwise
     * {@code color} applies.
     */
    public record Span(String text, int color, String difficulty) {

        /** Plain segment: fixed color. */
        static Span of(String text, int color) {
            return new Span(text, color, null);
        }

        /** Difficulty segment: color derived from the difficulty tier. */
        static Span difficulty(String text, String difficulty) {
            return new Span(text, difficultyColor(difficulty), difficulty);
        }
    }

    /**
     * A semantic line: {@code value == null} means a pure header line (one
     * gold line of text), otherwise "label left, value right-aligned".
     *
     * <p>When {@code spans} is non-null it drives per-segment coloring (the
     * fastest clear / best map lines need their difficulty names colored,
     * and the cumulative data "names" need orange); {@code value} still holds
     * the joined full text, used only for width computation and the wrap
     * test.
     */
    public record Line(String label, String value, int color, List<Span> spans, List<List<Span>> cells) {

        /** Line without grid cells (everything except the cumulative rows). */
        public Line(String label, String value, int color, List<Span> spans) {
            this(label, value, color, spans, null);
        }

        /** Plain line: one value, one color. */
        static Line of(String label, String value, int color) {
            return new Line(label, value, color, null);
        }

        /** Segmented line: the value is joined from several colored segments. */
        static Line ofSpans(String label, List<Span> spans) {
            StringBuilder sb = new StringBuilder();
            for (Span sp : spans) {
                sb.append(sp.text());
            }
            return new Line(label, sb.toString(), COLOR_VALUE, List.copyOf(spans));
        }
    }

    /**
     * A row as actually drawn: a non-null {@code right} puts "left label +
     * right-aligned value" on one line, null makes it a standalone text
     * line (headers / wrapped continuations). {@code indent} indents wrapped
     * continuations; {@code header} decides the line pitch.
     *
     * <p>When {@code rightSpans} is non-null and the row was not wrapped, the
     * right value is colored segment by segment.
     */
    public record DrawRow(String left, String right, int color, boolean indent, boolean header,
                          List<Span> rightSpans, List<List<Span>> cells) {

        /** Row without grid cells. */
        public DrawRow(String left, String right, int color, boolean indent, boolean header,
                       List<Span> rightSpans) {
            this(left, right, color, indent, header, rightSpans, null);
        }

        /** Height this row occupies: headers get more whitespace, body rows stay compact, grid rows are two lines tall. */
        public int gap(int fh) {
            if (cells != null) {
                return fh * 2 + 4;
            }
            return header ? fh + 5 : fh + 2;
        }

        /** Plain row (single color on the right). */
        static DrawRow of(String left, String right, int color, boolean indent, boolean header) {
            return new DrawRow(left, right, color, indent, header, null);
        }
    }

    /** A cumulative stat: UI label key + the parser layer's combined-stats key. */
    private record SumStat(String labelKey, String statKey) {
    }

    /**
     * One map: UI label key + the MapId used against the data table.
     *
     * <p>Package-visible (together with {@link #MAPS}): the free query builds
     * its map nodes from this same table.
     */
    record MapRef(String labelKey, MapId id) {

        /** The key the parser layer's perMap uses (= the {@code mapLabel} translation, i.e. the map name the UI shows). */
        String label() {
            return trans(labelKey);
        }
    }

    /** One "map + difficulty" cell that passed the gate. */
    private record Cell(MapRef map, String diff, String label, long wins, long kills, long seconds) {
    }

    // ----Building: ZombiesStats -> a column of lines----

    /**
     * Spreads one player's stats into every line of the overview panel: the
     * account overview, the eight "summed across four maps" cumulative
     * items, then "Fastest clear" and "Best map".
     *
     * <p><b>Every line always appears; an unavailable cell draws
     * {@link #DASH}</b> - lines never appear, disappear or shift with the
     * data: the placeholder answers "is this 0 or absent", and the player
     * remembers which line lives where.
     *
     * @param s parsed player stats; the caller guarantees non-{@code null}
     * @return top-down lines (not yet wrapped)
     */
    public static List<Line> lines(ZombiesStats s) {
        List<Line> out = new ArrayList<>(accountLines(s));
        out.addAll(sumLines(s));

        // ──Gating: keep only cells where "this map was cleared and this tier was won"──
        List<Cell> cells = gatedCells(s);

        // ──Fastest clear: the shortest time among gated cells──
        out.add(fastestClearLine(cells));

        // ──Best map: three weighted signals, drawing "map + difficulty" only──
        out.add(bestMapLine(cells));

        return out;
    }

    /**
     * The eight cumulative items' lines - <b>shared by the in-game overview
     * and the free query's "cumulative data" node</b>.
     *
     * <p>The right value is "one number per map + the four-map total":
     * {@code Dead End: 4,310   Bad Blood: 466   ...   Total: 8,088}.
     * A map missing the item records <b>0</b> in its cell and still joins
     * the sum; when none of the four maps has the item, <b>every cell of the
     * row draws {@link #DASH}</b> - this is where the requirement "show -
     * when there is no data at all, 0 for a single missing map" lands.
     *
     * @param s parsed player stats
     * @return eight lines in {@link #SUM_STATS} order
     */
    static List<Line> sumLines(ZombiesStats s) {
        List<GridRow> rows = sumGridRows(s);
        List<Line> out = new ArrayList<>(rows.size());
        for (GridRow row : rows) {
            out.add(new Line(row.label(), joinCells(row.cells()), COLOR_LABEL, flattenCells(row.cells()),
                    row.cells()));
        }
        return out;
    }

    /**
     * One grid row: a title plus several "cells"; each cell is itself several
     * colored segments.
     *
     * <p><b>Cells rather than one joined string</b> is a layout need of the
     * free query: that side spreads cells over two lines by column width,
     * and only knowing "which segments belong to one cell" allows computing
     * column widths and wrapping per cell.
     */
    public record GridRow(String label, List<List<Span>> cells) {
    }

    /**
     * The eight cumulative items in "cell" form - <b>read by the free
     * query's "cumulative data" node</b>.
     *
     * <p>Same values and same placeholder rules as {@link #sumLines}, just
     * not pre-joined into one string: the two panels therefore cannot drift
     * in values, order or placeholders.
     *
     * @param s parsed player stats
     * @return eight rows in {@link #SUM_STATS} order, five cells each (four maps + Total)
     */
    static List<GridRow> sumGridRows(ZombiesStats s) {
        List<GridRow> out = new ArrayList<>(SUM_STATS.size());
        for (SumStat stat : SUM_STATS) {
            out.add(new GridRow(trans(stat.labelKey()), sumCells(s, trans(stat.statKey()))));
        }
        return out;
    }

    /**
     * One cumulative item's five cells: one per map + one "Total".
     *
     * <p>Map names take {@link #COLOR_MAP_NAME} (aqua) and "Total" takes the
     * {@link #COLOR_MAP} orange, so Total stands apart from the maps;
     * values stay white - each cell is split into two segments rather than
     * joined into one string precisely so the name can be colored alone
     * while widths are still computed per cell.
     *
     * <p>When <b>none</b> of the four maps has the item, every cell's value
     * is {@link #DASH}; as soon as one map has it, the missing maps record
     * <b>0</b> and join the sum - the two kinds of "absent" must stay
     * separate, otherwise the row either draws as a string of 0s (you cannot
     * tell whether the item exists at all) or disappears (and the row order
     * breaks). So the four maps' values are fetched first to decide "is the
     * row empty", and only then are the cells built - building as you go
     * would misjudge an empty cell sitting before a map with data as "-".
     */
    static List<List<Span>> sumCells(ZombiesStats s, String statLabel) {
        Long[] perMap = new Long[MAPS.size()];
        long total = 0L;
        boolean any = false;
        for (int i = 0; i < MAPS.size(); i++) {
            perMap[i] = mapValue(s, MAPS.get(i).label(), statLabel);
            if (perMap[i] != null) {
                total += perMap[i];
                any = true;
            }
        }
        List<List<Span>> cells = new ArrayList<>(MAPS.size() + 1);
        for (int i = 0; i < MAPS.size(); i++) {
            cells.add(cell(MAPS.get(i).label(),
                    perMap[i] != null ? fmt(perMap[i]) : (any ? "0" : DASH), COLOR_MAP_NAME));
        }
        cells.add(cell(diffLabel(DIFF_TOTAL), any ? fmt(total) : DASH));
        return cells;
    }

    /** One cell: name (orange) + {@code ": "} + value (white), two segments so the name can be colored alone. */
    static List<Span> cell(String name, String value) {
        return cell(name, value, COLOR_MAP);
    }

    /** Same as {@link #cell(String, String)} with an explicit name color (the map names use {@link #COLOR_MAP_NAME}). */
    static List<Span> cell(String name, String value, int nameColor) {
        return List.of(Span.of(name, nameColor), Span.of(": " + value, COLOR_VALUE));
    }

    /** Joins cells into one string - used by the in-game panel and as the fallback when the grid cannot fit. */
    static String joinCells(List<List<Span>> cells) {
        StringBuilder sb = new StringBuilder();
        for (List<Span> cell : cells) {
            if (!sb.isEmpty()) {
                sb.append(SUM_SEP);
            }
            for (Span sp : cell) {
                sb.append(sp.text());
            }
        }
        return sb.toString();
    }

    /** Flattens cells into one colored-segment list (a gap segment between adjacent cells), for the per-segment drawing path. */
    static List<Span> flattenCells(List<List<Span>> cells) {
        List<Span> out = new ArrayList<>();
        for (List<Span> cell : cells) {
            if (!out.isEmpty()) {
                out.add(Span.of(SUM_SEP, COLOR_VALUE));
            }
            out.addAll(cell);
        }
        return out;
    }

    /** Total width (px) of a colored-segment list - the grid derives column widths from it. */
    public static int spansWidth(TextRenderer tr, List<Span> spans) {
        int w = 0;
        for (Span sp : spans) {
            w += tr.getWidth(sp.text());
        }
        return w;
    }

    /**
     * The account overview section: a section title + display name / level /
     * Karma / experience / three timestamps.
     *
     * <p><b>Both queries share this section</b>: the in-game overview puts it
     * on top, and the free query's tree uses it as top-level flat rows (see
     * {@code QueryDataTree.build}). It is therefore a single funnel nobody
     * may copy - one copy each means two half-maintained variants that will
     * drift.
     *
     * <p>Same placeholder rule as the stat rows: <b>every line appears</b>;
     * unavailable ones (no display name / Karma 0 / network exp 0 / no
     * timestamp) draw {@link #DASH} instead of omitting the whole line -
     * omission would make the account block's row order depend on the data.
     *
     * @param s parsed player stats
     * @return the section's lines (not yet wrapped)
     */
    static List<Line> accountLines(ZombiesStats s) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of(trans("nomorezombies.query.section.overview"), null, COLOR_HEADER));
        out.add(Line.of(trans("nomorezombies.query.overview.name"),
                s.displayName.isEmpty() ? DASH : s.displayName, COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.level"), "Lv." + s.networkLevel, COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.karma"),
                s.karma > 0 ? fmt(s.karma) : DASH, COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.exp"),
                s.networkExp > 0 ? fmt(s.networkExp) : DASH, COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.firstlogin"), fmtDate(s.firstLogin), COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.lastlogin"), fmtDate(s.lastLogin), COLOR_LABEL));
        out.add(Line.of(trans("nomorezombies.query.overview.lastlogout"), fmtDate(s.lastLogout), COLOR_LABEL));
        return out;
    }

    // ----Gating----

    /**
     * Whether a cell (map + difficulty) counts as "cleared"; both conditions
     * must hold:
     * <ol>
     *  <li><b>The map's best round equals the map's clear round</b> - 30 for
     *  DE/BB/Prison, 105 for AA
     *  (from {@link DataManager#get()}{@code .getMaxRound(MapId)}).
     *  Using "the map's own clear round" instead of a hard-coded 30: AA's cap
     *  is 105, and a hard-coded 30 would wipe out AA-only players entirely;</li>
     *  <li><b>The player won this map at this difficulty at least once</b> -
     *  the secondary check. Round count alone is not enough: "best round = 30"
     *  can also hold on an account that reached round 30 only to be wiped,
     *  while a win is the victory-banner-graded fact</li>
     * </ol>
     *
     * <p>Cells failing either are <b>skipped</b>: fastest clear and best map
     * both choose among gated cells only, so an account that "died at round
     * 30" is not miscounted.
     *
     * @return the gated cells; an empty list when there are none (the caller draws {@link #DASH})
     */
    static List<Cell> gatedCells(ZombiesStats s) {
        String winsLabel = trans("nomorezombies.query.stat.wins");
        String killsLabel = trans("nomorezombies.query.stat.zombie_kills");
        List<Cell> cells = new ArrayList<>();
        for (MapRef map : MAPS) {
            int clearRound = DataManager.get().getMaxRound(map.id());
            if (bestRound(s, map.label()) < clearRound) {
                continue;
            }
            Map<String, Long> scopes = s.fastestTimes.get(clearRound);
            for (String diff : DIFFS) {
                long wins = diffValue(s, map.label(), winsLabel, diff);
                if (wins < 1) {
                    continue;
                }
                String label = map.label() + ZombiesStatsParser.MAP_DIFF_SEP + diffLabel(diff);
                Long secs = scopes == null ? null : scopes.get(label);
                cells.add(new Cell(map, diff, label, wins,
                        diffValue(s, map.label(), killsLabel, diff),
                        secs == null ? Long.MAX_VALUE : secs));
            }
        }
        return cells;
    }

    /**
     * The map's best round: the maximum across all tiers of the
     * {@code best_round} item.
     *
     * <p>Hypixel provides both {@code best_round_zombies_<map>} and
     * {@code ..._<map>_<diff>}, collected into the same
     * {@code MapStat.values}; what is wanted here is "which round did he
     * reach on this map", so the maximum is taken regardless of tier.
     */
    static int bestRound(ZombiesStats s, String mapLabel) {
        List<ZombiesStats.MapStat> stats = s.perMap.get(mapLabel);
        if (stats == null) {
            return 0;
        }
        String bestLabel = trans("nomorezombies.query.stat.best_round");
        int best = 0;
        for (ZombiesStats.MapStat ms : stats) {
            if (!ms.label.equals(bestLabel)) {
                continue;
            }
            for (long v : ms.values.values()) {
                best = (int) Math.max(best, v);
            }
        }
        return best;
    }

    // ----Value access----

    /**
     * The four-map sum of one stat item; {@code null} when none of the four
     * maps has it.
     *
     * <p>The in-game overview goes through {@link #sumCells} fetching per
     * cell with placeholders, so this method currently has no runtime
     * caller; it is kept because the "summed across four maps" accounting
     * (difficulty tiers only, missing maps as 0, {@code null} only when all
     * are missing) remains defined here.
     */
    static Long crossMapSum(ZombiesStats s, String statLabel) {
        long sum = 0L;
        boolean any = false;
        for (MapRef map : MAPS) {
            Long v = mapValue(s, map.label(), statLabel);
            if (v != null) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : null;
    }

    /**
     * One stat item's value on one map.
     *
     * <p><b>Prefer the difficulty tiers only (normal/hard/RIP), excluding the
     * map's own "Total" tier</b> - Hypixel stores both
     * {@code _zombies_<map>} and {@code _zombies_<map>_normal}, and the
     * former <b>includes</b> the latter; adding both would double-count the
     * same games.
     * Some items provide only the "Total" tier (no difficulty tiers at all);
     * only then does it fall back to "Total" - better a slightly uneven
     * accounting than reading the whole item as 0.
     *
     * @return the map's value for the item; {@code null} when the map lacks it
     */
    static Long mapValue(ZombiesStats s, String mapLabel, String statLabel) {
        List<ZombiesStats.MapStat> stats = s.perMap.get(mapLabel);
        if (stats == null) {
            return null;
        }
        String totalLabel = diffLabel(DIFF_TOTAL);
        for (ZombiesStats.MapStat ms : stats) {
            if (!ms.label.equals(statLabel)) {
                continue;
            }
            long diffSum = 0L;
            long allSum = 0L;
            boolean anyDiff = false;
            for (Map.Entry<String, Long> e : ms.values.entrySet()) {
                allSum += e.getValue();
                if (!e.getKey().equals(totalLabel)) {
                    diffSum += e.getValue();
                    anyDiff = true;
                }
            }
            return anyDiff ? diffSum : allSum;
        }
        return null;
    }

    /** One map's value for an item at a specific difficulty tier; 0 when the tier is absent. */
    static long diffValue(ZombiesStats s, String mapLabel, String statLabel, String diff) {
        List<ZombiesStats.MapStat> stats = s.perMap.get(mapLabel);
        if (stats == null) {
            return 0L;
        }
        String want = diffLabel(diff);
        for (ZombiesStats.MapStat ms : stats) {
            if (!ms.label.equals(statLabel)) {
                continue;
            }
            Long v = ms.values.get(want);
            return v == null ? 0L : v;
        }
        return 0L;
    }

    // ----Fastest clear----

    /**
     * The "Fastest clear" line: the shortest time among gated cells, drawn as
     * "map + difficulty + time".
     *
     * <p>The difficulty name is colored alone; the time and map name stay
     * white. With no usable cell at all the whole line draws {@link #DASH}.
     */
    static Line fastestClearLine(List<Cell> cells) {
        String label = trans("nomorezombies.query.overview.fastestClear");
        Cell best = null;
        for (Cell c : cells) {
            if (c.seconds() == Long.MAX_VALUE) {
                continue;
            }
            if (best == null || c.seconds() < best.seconds()) {
                best = c;
            }
        }
        if (best == null) {
            return Line.of(label, DASH, COLOR_LABEL);
        }
        return Line.ofSpans(label, List.of(
                Span.of(best.map().label() + ZombiesStatsParser.MAP_DIFF_SEP, COLOR_VALUE),
                Span.difficulty(diffLabel(best.diff()), best.diff()),
                Span.of("  " + ZombiesStatsParser.formatTime(best.seconds()), COLOR_VALUE)));
    }

    // ----Best map----

    /**
     * The "Best map" line: the map chosen by the three weighted signals, then
     * the difficulty with the most wins on that map.
     *
     * <p>Draws only "{@code map-difficulty}" (same format as the time stats).
     * With no usable cell at all the whole line draws {@link #DASH}.
     */
    static Line bestMapLine(List<Cell> cells) {
        String label = trans("nomorezombies.query.overview.bestMap");
        BestMap best = bestMap(cells);
        if (best == null) {
            return Line.of(label, DASH, COLOR_LABEL);
        }
        return Line.ofSpans(label, List.of(
                Span.of(best.mapLabel() + (best.difficulty() == null ? "" : ZombiesStatsParser.MAP_DIFF_SEP), COLOR_VALUE),
                best.difficulty() == null
                        ? Span.of("", COLOR_VALUE)
                        : Span.difficulty(best.difficulty(), best.difficultyKey())));
    }

    /** The "Best map" result: map name + difficulty name + that difficulty's tier key. */
    record BestMap(String mapLabel, String difficulty, String difficultyKey) {
    }

    /**
     * Judges which map this player knows best - <b>chosen only among the
     * gated cells above</b>.
     *
     * <p>The three signals are weighted {@code wins : kills : speed = 3 : 2 : 1},
     * each normalized to {@code 0~1} before summing - the quantities differ by
     * orders of magnitude (tens of wins, tens of thousands of kills, over a
     * thousand seconds), and without normalization the time term alone would
     * own the result.
     *
     * <p>All three are used <b>after summing the map's difficulties</b>:
     * <ul>
     *  <li><b>Wins</b>: the map's wins across difficulties / the max across four maps</li>
     *  <li><b>Kills</b>: the map's kills across difficulties / the max across four maps
     *  (AA has only the Normal tier, so the sum is just itself - no special case; see {@link #DIFFS})</li>
     *  <li><b>Speed</b>: the <b>average</b> of the map's clear times across difficulties,
     *  <b>shorter scores higher</b> - mapped as
     *  {@code (maxAvg - thisAvg) / (maxAvg - minAvg)};
     *  when all four averages are equal (or only one map has a time) the term scores full</li>
     * </ul>
     *
     * <p><b>The average's divisor is "the number of difficulties whose time
     * was actually retrieved", not a hard-coded 3.</b> A player who cleared
     * one map at one difficulty, with no records on the other two tiers, has
     * divisor 1; a hard-coded 3 would crush his average to a third and hand
     * the speed term to other maps for free.
     * With divisor 0 (no time retrieved for the map) the term scores 0 and
     * stays out of the min/max range - see {@link #averageSeconds}.
     *
     * <p>On ties, <b>more wins wins</b> - it is the primary signal of the
     * three. Returns {@code null} when there are no cells at all (the caller
     * draws {@link #DASH}).
     */
    static BestMap bestMap(List<Cell> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        int n = MAPS.size();
        long[] wins = new long[n];
        long[] kills = new long[n];
        long[] timeSum = new long[n];
        int[] timeCount = new int[n];
        long[] avgSeconds = new long[n];
        int[] bestDiffWins = new int[n];
        String[] bestDiffKey = new String[n];
        boolean[] hasTime = new boolean[n];

        for (Cell c : cells) {
            int i = MAPS.indexOf(c.map());
            wins[i] += c.wins();
            kills[i] += c.kills();
            // Only cells that "got a time": a gated cell may have wins but no fastest
            // record, and counting it into the divisor would drag the average down to a
            // fake number
            if (c.seconds() != Long.MAX_VALUE) {
                timeSum[i] += c.seconds();
                timeCount[i]++;
            }
            if (c.wins() > bestDiffWins[i]) {
                bestDiffWins[i] = (int) c.wins();
                bestDiffKey[i] = c.diff();
            }
        }

        long maxWins = 0L;
        long maxKills = 0L;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            maxWins = Math.max(maxWins, wins[i]);
            maxKills = Math.max(maxKills, kills[i]);
            Long avg = averageSeconds(timeSum[i], timeCount[i]);
            if (avg != null) {
                avgSeconds[i] = avg;
                hasTime[i] = true;
                minTime = Math.min(minTime, avg);
                maxTime = Math.max(maxTime, avg);
            }
        }

        double bestScore = -1.0;
        int bestIdx = -1;
        for (int i = 0; i < n; i++) {
            double winScore = maxWins > 0 ? (double) wins[i] / maxWins : 0.0;
            double killScore = maxKills > 0 ? (double) kills[i] / maxKills : 0.0;
            double speedScore = 0.0;
            if (hasTime[i]) {
                speedScore = (maxTime > minTime)
                        ? (double) (maxTime - avgSeconds[i]) / (double) (maxTime - minTime)
                        : 1.0;
            }
            double score = (W_WINS * winScore + W_KILLS * killScore + W_SPEED * speedScore)
                    / (W_WINS + W_KILLS + W_SPEED);
            // On ties, more wins wins: the leader only changes on strictly greater, plus
            // one clause for "higher score but fewer wins"
            if (score > bestScore + 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9 && bestIdx >= 0 && wins[i] > wins[bestIdx])) {
                bestScore = score;
                bestIdx = i;
            }
        }
        if (bestIdx < 0 || wins[bestIdx] == 0) {
            return null;
        }
        MapRef map = MAPS.get(bestIdx);
        String diff = bestDiffKey[bestIdx];
        return new BestMap(map.label(), diff == null ? null : diffLabel(diff), diff);
    }

    /**
     * The map's average clear time: the divisor is <b>the number of
     * difficulties whose time was actually retrieved</b>.
     *
     * <p>Returns {@code null} with no difficulty records, and the caller
     * treats it as "no data for this term" - no division happens here, so
     * neither {@code 0/0} nor {@code n/0} can occur.
     *
     * @param sum   the sum of per-difficulty times (seconds)
     * @param count the number of difficulties whose time was retrieved; 0 means no usable time for the map
     */
    static Long averageSeconds(long sum, int count) {
        return count <= 0 ? null : sum / count;
    }

    // ----Difficulty palette / wording----

    /**
     * Difficulty tier key -> color: <b>Total orange / Normal green / Hard
     * red / RIP dark red</b>.
     *
     * <p>Decided by key, not display text: the Chinese "安息" and English
     * "RIP" are one key, and a text-based check would drop one branch on a
     * language switch.
     */
    public static int difficultyColor(String diffKey) {
        if (diffKey == null) {
            return COLOR_VALUE;
        }
        return switch (diffKey) {
            case DIFF_TOTAL -> COLOR_MAP;
            case DIFF_NORMAL -> 0x55FF55;
            case DIFF_HARD -> 0xFF5555;
            case DIFF_RIP -> 0xAA0000;
            default -> COLOR_VALUE;
        };
    }

    /** Difficulty tier key -> display text (via translation: 综合/普通/困难/安息 in Chinese, Total/Normal/Hard/RIP in English). */
    static String diffLabel(String diffKey) {
        if (diffKey == null) {
            return "";
        }
        return switch (diffKey) {
            case DIFF_TOTAL -> trans("nomorezombies.query.diff.total");
            case DIFF_NORMAL -> trans("nomorezombies.query.diff.normal");
            case DIFF_HARD -> trans("nomorezombies.query.diff.hard");
            case DIFF_RIP -> trans("nomorezombies.query.diff.rip");
            default -> diffKey;
        };
    }

    /**
     * Display text -> difficulty tier key; {@code null} when unrecognized.
     *
     * <p>For reverse-looking-up a difficulty name that is already a
     * translation (the parser layer joins the difficulty name straight into
     * the {@code MapStat.values} key), so coloring still decides by key and
     * never drops a branch on a language switch.
     */
    static String diffKeyOf(String label) {
        if (label == null || label.isEmpty()) {
            return null;
        }
        for (String key : new String[]{DIFF_TOTAL, DIFF_NORMAL, DIFF_HARD, DIFF_RIP}) {
            if (label.equals(diffLabel(key))) {
                return key;
            }
        }
        return null;
    }

    // ----Wrapping: semantic lines -> drawable text rows----

    /**
     * Folds semantic lines into drawable text rows: when one line fits, "left
     * label + right-aligned value" sit side by side; when it does not, the
     * label takes its own line and the value wraps at the available width,
     * indented as a whole - otherwise overly wide values (long player names,
     * the best map's map+difficulty) would be clipped.
     *
     * <p>Wrapped values are no longer colored per segment (all white): once a
     * long text is cut across lines, deriving colors from segment offsets
     * only drifts further off, and wrapping happens only on very narrow
     * panels anyway.
     *
     * <p>Cumulative rows (those carrying grid cells) become two-line grid rows when the panel is wide
     * enough for all of them; otherwise they wrap like any other long value.
     *
     * @param avail the panel's available width (px)
     */
    public static List<DrawRow> wrap(TextRenderer tr, List<Line> lines, int avail) {
        return wrap(tr, lines, avail, List.of());
    }

    /**
     * Same as {@link #wrap(TextRenderer, List, int)}, but the cumulative rows' layout is also measured
     * against {@code alsoMeasure} (other players' cumulative cells).
     *
     * <p>The in-game panel passes every listed player's cells here: the layout mode and the column widths
     * then depend on <b>the whole list</b> rather than on whichever player is selected, so every player
     * is laid out the same way and the columns do not jump when switching between them.
     *
     * <p>Cumulative rows have three modes, picked once for all of them: side by side (label left, 2x2 +
     * Total block right) when the widest label plus the block fit; stacked (label on its own line, the
     * same block below it) when only the block fits; otherwise the old wrapping.
     */
    public static List<DrawRow> wrap(TextRenderer tr, List<Line> lines, int avail,
                                     List<List<List<Span>>> alsoMeasure) {
        List<DrawRow> out = new ArrayList<>();

        List<List<List<Span>>> gridCells = new ArrayList<>(alsoMeasure);
        boolean anyGrid = false;
        int maxGridLabelW = 0;
        for (Line l : lines) {
            if (l.cells() != null && QueryDataTree.isSummaryLayout(l.cells(), 2)) {
                gridCells.add(l.cells());
                anyGrid = true;
                maxGridLabelW = Math.max(maxGridLabelW, tr.getWidth(l.label()));
            }
        }
        boolean side = false;
        boolean stacked = false;
        if (anyGrid) {
            int sumW = 0;
            for (int w : QueryDataTree.summaryColumnWidths(tr, gridCells)) {
                sumW += w;
            }
            int blockMin = sumW + (QueryDataTree.SUMMARY_COLS - 1) * QueryDataTree.SUMMARY_MIN_GAP;
            side = maxGridLabelW + GRID_LABEL_GAP + blockMin <= avail;
            stacked = !side && GRID_LABEL_GAP + blockMin <= avail;
        }

        for (Line l : lines) {
            if (l.value() == null) {
                out.add(DrawRow.of(l.label(), null, l.color(), false, true));
                continue;
            }
            if ((side || stacked) && l.cells() != null && QueryDataTree.isSummaryLayout(l.cells(), 2)) {
                if (side) {
                    out.add(new DrawRow(l.label(), null, l.color(), false, false, null, l.cells()));
                } else {
                    out.add(DrawRow.of(l.label(), null, l.color(), false, false));
                    out.add(new DrawRow(null, null, l.color(), false, false, null, l.cells()));
                }
                continue;
            }
            int labelW = tr.getWidth(l.label());
            int valueW = tr.getWidth(l.value());
            if (labelW + GAP + valueW <= avail) {
                out.add(new DrawRow(l.label(), l.value(), l.color(), false, false, l.spans()));
            } else {
                appendWrapped(tr, out, l.label(), l.color(), false, avail, false);
                appendWrapped(tr, out, l.value(), COLOR_VALUE, false, avail, true);
            }
        }
        return out;
    }

    /** The cumulative rows' cell lists of one player - what the in-game panel measures every listed player by. */
    static List<List<List<Span>>> sumCellLists(ZombiesStats s) {
        List<List<List<Span>>> out = new ArrayList<>(SUM_STATS.size());
        for (GridRow row : sumGridRows(s)) {
            out.add(row.cells());
        }
        return out;
    }

    /** Appends one text row; when it does not fit, hands off to {@link #wrapText}. */
    private static void appendWrapped(TextRenderer tr, List<DrawRow> out, String text, int color,
                                      boolean header, int avail, boolean indent) {
        if (tr.getWidth(text) <= avail) {
            out.add(DrawRow.of(text, null, color, indent, header));
            return;
        }
        for (String line : wrapText(tr, text, avail)) {
            out.add(DrawRow.of(line, null, color, indent, header));
        }
    }

    /**
     * Wraps text at the available width: prefers breaking at the last space
     * so Latin words are never split in half; with no space (Chinese and the
     * like) it breaks per character. Every line fits at least one character,
     * so it always terminates.
     *
     * @param maxWidth max width per line (px); non-positive returns one unchanged line
     */
    public static List<String> wrapText(TextRenderer tr, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add(text == null ? "" : text);
            return out;
        }
        if (maxWidth <= 0) {
            out.add(text);
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (!line.isEmpty() && tr.getWidth(line + ch) > maxWidth) {
                int sp = line.lastIndexOf(" ");
                if (sp > 0) {
                    out.add(line.substring(0, sp));
                    line = new StringBuilder(line.substring(sp + 1));
                    continue;
                }
                out.add(line.toString());
                line = new StringBuilder();
            }
            line.append(ch);
            i += Character.charCount(cp);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    // ----Wording / formatting----

    /** Resolves a translation key's text (follows the client language). */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    /** Thousands-separator formatting (%,d) - every numeric value in the overview uses this display. */
    public static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** epoch ms -> local-zone date string; invalid values (0/negative) draw {@link #DASH}, the same placeholder as every other missing item. */
    private static String fmtDate(long epochMs) {
        if (epochMs <= 0) {
            return DASH;
        }
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DATE_FMT);
    }
}