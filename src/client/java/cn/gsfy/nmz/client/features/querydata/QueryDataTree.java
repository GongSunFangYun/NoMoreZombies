package cn.gsfy.nmz.client.features.querydata;

import cn.gsfy.nmz.client.data.ZombiesStatsParser;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tree model and the sole flatten entry point of the free-query detail panel
 *
 * <p>Only the free query uses this tree: type a name and open whatever you
 * want to see. The in-game query uses {@link QueryDataOverview} (one flat
 * overview) instead -- there the point is judging teammates within seconds,
 * a different task from browsing a stranger's profile, so each side keeps
 * its own implementation and neither touches the other.
 *
 * <p><b>Container rows carry a title only, never summary numbers.</b> A
 * total next to the title would be this mod's own judgment, not a fact
 * about the account; it would eat width and suggest nothing is visible
 * without expanding. Collapsed is just {@code [+] Overall Stats}; details
 * appear only when expanded.
 *
 * <p><b>Tree lines are geometry, not characters.</b> Box characters such as
 * {@code ├─}/{@code └─}/{@code │} leave gaps above and below their ink, so
 * joining them line by line produces broken dashes. The flatten result
 * carries only geometry -- which columns continue, whether this row needs
 * a top/bottom stub ({@link Row#branchMask}/{@link Row#topStub}/
 * {@link Row#bottomStub}) -- and {@code QueryDataScreen.drawTreeLines}
 * paints it as continuous 2px lines with color blocks.
 *
 * <p><b>A data row is one merged row.</b> A row has a name and a data body:
 * the name is centered in the left column, the body sits in the right area
 * and may span two text lines within the row (many cells), but externally
 * it is still <b>one</b> {@link Row} -- one row height, one hover/selection
 * plate, never split into small rows (one click hit, not "only one of
 * several lines is clickable").
 *
 * <p><b>Rendering and hit-testing must read the same flatten result.</b>
 * {@link #flatten} is the only source of "currently visible rows";
 * {@code QueryDataScreen}'s drawing and {@code mouseClicked} both call it
 * and neither may recompute -- two independently written coordinate lists
 * drift apart at the first change.
 */
public final class QueryDataTree {

    /** Collapsed marker (prepended to the title) */
    private static final String MARK_COLLAPSED = "[+] ";
    /** Expanded marker (prepended to the title) */
    private static final String MARK_EXPANDED = "[-] ";

    /**
     * Tree line width (px) -- thickness of the self-drawn color blocks
     *
     * <p>2px is visible without stealing attention: 1px turns faint under
     * GUI scaling, and anything thicker covers the text.
     */
    public static final int TREE_W = 2;
    /** Indent step per level (px): vertical-line spacing between adjacent levels */
    public static final int INDENT_STEP = 11;
    /** Horizontal stub length (px): the short segment from the vertical line to the name */
    public static final int STUB_W = 6;
    /** Minimum gap between two cells in the same line (px) */
    public static final int CELL_GAP = 10;

    /** Gap (px) between the name column and the data body in a merged row */
    public static final int LEAD_GAP = 12;

    /** Max cells on one grid line: at or below this the cells fit one line, above it two */
    private static final int GRID_ONE_LINE_MAX = 4;
    /**
     * Expand-state key of the "Totals" child under "Overall Stats" --
     * <b>a literal constant, language-independent</b>
     *
     * <p>Node paths use internal keys, not translations: a translated path
     * breaks when the client language changes and groups the player had
     * expanded would silently fold back (every path in the tree obeys this).
     */
    private static final String SUMS_PATH = "group2/sums";
    /** Prefix of map node paths, followed by the map's internal key (also language-independent) */
    private static final String MAP_PATH_PREFIX = "group2/map/";

    /**
     * Rounds <b>always shown</b> in the fastest-times section (Hypixel's
     * {@code fastest_time_<N>_...} has only these three tiers)
     *
     * <p>Shared by all maps, including Alien Arcadium (it also has only
     * 10/20/30, no record for 105). When a player has not reached a round
     * the data has no matching key and iterating the parsed result alone
     * would drop the whole group, so the rounds to render must be known
     * upfront. Out-of-table rounds (new Hypixel tiers) are merged in, never
     * dropped.
     */
    private static final List<Integer> FASTEST_ROUNDS = List.of(10, 20, 30);
    /** Round shown as "Final Round" in its title (affects the label only) */
    private static final int FINAL_ROUND = 30;

    /** Placeholder in fastest-time records for "no clear on this scope" */
    private static final String TIME_DASH = "--:--";

    /** Side-by-side test gap (px) for non-grid rows: title left + right-aligned value */
    private static final int GAP = 10;

    /** Title color of collapsible containers (gold) */
    public static final int COLOR_TITLE = 0xFFFF55;
    /** Gray of leaf row titles */
    public static final int COLOR_LEAF = 0xAAAAAA;
    /** White of values (wrapped continuation lines use it too) */
    public static final int COLOR_VALUE = 0xFFFFFF;
    /**
     * Tree line color -- one step darker than content so lines recede
     *
     * <p><b>Must carry alpha (0xFF......)</b>: tree lines go through
     * {@code DrawContext.fill}, which in 1.21.x reads the color as ARGB;
     * alpha=0 is fully transparent and the whole line disappears. Text APIs
     * add alpha automatically, so only color blocks are affected.
     */
    public static final int COLOR_TREE = 0xFF888888;

    private QueryDataTree() {
    }

    // ---- Layout: row name in the left column / data body on the right ----

    /**
     * Start X of a row name (px, from the panel's left edge) -- the
     * <b>single source</b> for both tree-line geometry and text origin
     *
     * <p>Top level (depth 0) has no tree lines; the name only clears
     * {@link #TREE_W}. Each level deeper adds one {@link #INDENT_STEP} plus
     * the stub {@link #STUB_W} where this column's vertical line turns
     * toward the name.
     */
    public static int leadX(int depth) {
        return TREE_W + (depth >= 1 ? (depth - 1) * INDENT_STEP + STUB_W : 0);
    }

    /**
     * Data-body start of merged rows (px, from the panel's left edge) --
     * <b>measured from the widest name in the totals grid</b>
     *
     * <p>Width accounts only for the totals grid rows' own names (all eight
     * are four CJK characters, i.e. equal width), so the body sits right
     * next to the name column with just a {@link #LEAD_GAP} of space.
     * Taking the frame's widest name as the column width would float short
     * names far from their data.
     *
     * <p>Computed once during flatten and written into each grid row's
     * {@link Row#valueOffset}; drawing only reads that number, so
     * hit-testing and drawing never measure independently.
     */
    private static int gridValueOffset(TextRenderer tr, List<Node> nodes, int depth) {
        int w = 0;
        for (Node n : nodes) {
            if (n.isGridNode()) {
                w = Math.max(w, leadX(depth) + tr.getWidth(n.title));
            }
            w = Math.max(w, gridValueOffset(tr, n.children, depth + 1) - LEAD_GAP);
        }
        return w == 0 ? 0 : w + LEAD_GAP;
    }

    /**
     * Column widths for spreadsheet-style grid alignment: column j = max
     * width of each row's cell j
     *
     * <p>Cell i lands in column {@code i % cols}, row {@code i / cols}: 5
     * cells on two lines gives {@code 3+2}, so the second line's two cells
     * sit exactly below the first line's first two, matching the requested
     * D/E/F columns (Prison under Dead End).
     *
     * @param lineCount {@link Row#lines}: 1 line means one column per cell;
     *                  2 lines takes the count from {@link #gridHeadCount}
     */
    public static int[] gridColumnWidths(List<List<QueryDataOverview.Span>> cells, TextRenderer tr, int lineCount) {
        int cols = lineCount >= 2 ? gridHeadCount(cells.size()) : cells.size();
        int[] w = new int[Math.max(1, cols)];
        for (int i = 0; i < cells.size(); i++) {
            int c = i % w.length;
            w[c] = Math.max(w[c], QueryDataOverview.spansWidth(tr, cells.get(i)));
        }
        return w;
    }

    /**
     * How many text lines a grid row takes in the panel: <b>one line when
     * there are at most 4 cells and they fit, otherwise two</b>
     *
     * <p>Both rules together are the complete story:
     * <ul>
     *  <li>"At most 4 cells = one line" is the main rule: Alien Arcadium
     *  has a single Normal tier (2 cells) and map details have four tiers
     *  (4 cells), which fit one line in normal windows and must not be
     *  force-split;</li>
     *  <li>"Two lines only when it does not fit" is the narrow-window
     *  fallback: when 4 cells truly do not fit, fall back to two lines
     *  (2+2) rather than wrapping or clipping, so values stay readable.</li>
     * </ul>
     *
     * @param cells      cells (each cell is several colored spans)
     * @param tr         font metrics
     * @param valueAvail width available to the data body (px) =
     *                   panel width - {@link Row#valueOffset}
     * @return 1 or 2
     */
    public static int gridLineCount(List<List<QueryDataOverview.Span>> cells, TextRenderer tr, int valueAvail) {
        if (cells.size() <= GRID_ONE_LINE_MAX && cellsWidth(cells, tr) <= valueAvail) {
            return 1;
        }
        return 2;
    }

    /**
     * Cells on the grid's first line -- <b>on wrap, the half rounded up</b>
     *
     * <p>5 cells gives 3+2, 4 gives 2+2, 3 gives 2+1: both lines end up
     * nearly full and symmetric at a glance. Drawing and flatten share this
     * number.
     */
    public static int gridHeadCount(int count) {
        return (count + 1) / 2;
    }

    // ---- Summary layout: four maps as a 2x2 block + Total merged on the right ----

    /** Columns of the summary layout: map column 0, map column 1, Total column. */
    public static final int SUMMARY_COLS = 3;
    /** Smallest gap (px) between summary columns when the panel is too narrow for {@link #CELL_GAP}. */
    public static final int SUMMARY_MIN_GAP = 4;

    /**
     * Whether a grid row uses the summary layout: two lines and exactly
     * "four maps + Total" cells (the cumulative-data rows).
     *
     * <p>Cells 0..3 are the maps in {@code QueryDataOverview.MAPS} order and
     * sit as a 2x2 block (Dead End / Bad Blood over Alien Arcadium / Prison);
     * the last cell is Total, drawn in its own column to the right of the
     * block and vertically centered across both lines. Any other grid shape
     * keeps the generic column layout.
     */
    public static boolean isSummaryLayout(List<List<QueryDataOverview.Span>> cells, int lineCount) {
        return lineCount >= 2 && cells.size() == QueryDataOverview.MAPS.size() + 1;
    }

    /**
     * Column widths of the summary layout, <b>shared by every summary row</b>.
     *
     * <p>Column 0 = widest of the left-hand map cells (Dead End / Alien
     * Arcadium), column 1 = widest of the right-hand ones (Bad Blood /
     * Prison), column 2 = widest Total. Measuring over all rows gives every
     * row the same rectangle, so the columns line up as one straight grid
     * and the block keeps one fixed size no matter which values it holds.
     */
    public static int[] summaryColumnWidths(List<Row> rows, TextRenderer tr) {
        int[] w = new int[SUMMARY_COLS];
        for (Row r : rows) {
            if (!r.isGrid() || !isSummaryLayout(r.cells, r.lines)) {
                continue;
            }
            int maps = r.cells.size() - 1;
            for (int i = 0; i < maps; i++) {
                w[i % 2] = Math.max(w[i % 2], QueryDataOverview.spansWidth(tr, r.cells.get(i)));
            }
            w[2] = Math.max(w[2], QueryDataOverview.spansWidth(tr, r.cells.get(maps)));
        }
        return w;
    }

    // ---- Tier layout: the map detail's Normal / Hard / RIP / Total as aligned columns ----

    /** Separator span between tiers in a map detail's right value (three spaces); also the split point for {@link #tierCells}. */
    static final String TIER_SEP = "   ";
    /** Fixed column order of the tiers; a tier always lands in its own column, whichever tiers the map has. */
    private static final List<String> TIER_ORDER = List.of(QueryDataOverview.DIFF_NORMAL,
            QueryDataOverview.DIFF_HARD, QueryDataOverview.DIFF_RIP, QueryDataOverview.DIFF_TOTAL);

    /** Number of fixed tier columns: Normal / Hard / RIP / Total. */
    public static final int TIER_COLUMNS = 4;

    /**
     * Column geometry shared by every map-detail row.
     *
     * @param colW     width (px) of each tier column
     * @param minStart leftmost x (px, from the panel's left edge) the block may start at: right of the
     *                 widest row name plus {@link #LEAD_GAP}
     * @param packW    for "compact" rows (fewer tiers than columns, Total last - Alien Arcadium): the width of
     *                 the cell at each position before Total, widest over all compact rows. These cells pack
     *                 against Total as <b>their own left-aligned columns</b> of this width
     */
    public record TierLayout(int[] colW, int minStart, int[] packW) {
    }

    /** Whether a row is compact: fewer tiers than columns and Total as the last cell. */
    public static boolean isCompactTierRow(List<List<QueryDataOverview.Span>> cells, int[] slots) {
        return !cells.isEmpty() && cells.size() < TIER_COLUMNS && slots[cells.size() - 1] == TIER_COLUMNS - 1;
    }

    /**
     * Splits a map detail's right-value spans into one cell per tier (cut at {@link #TIER_SEP} spans,
     * which {@code addDiffSpan} puts between tiers).
     */
    public static List<List<QueryDataOverview.Span>> tierCells(List<QueryDataOverview.Span> spans) {
        List<List<QueryDataOverview.Span>> cells = new ArrayList<>();
        List<QueryDataOverview.Span> cur = new ArrayList<>();
        for (QueryDataOverview.Span sp : spans) {
            if (sp.difficulty() == null && TIER_SEP.equals(sp.text())) {
                if (!cur.isEmpty()) {
                    cells.add(cur);
                    cur = new ArrayList<>();
                }
                continue;
            }
            cur.add(sp);
        }
        if (!cur.isEmpty()) {
            cells.add(cur);
        }
        return cells;
    }

    /**
     * Column index of each cell: Normal / Hard / RIP / Total take columns 0-3 by their tier key, so Total
     * lines up across maps even when a map (Alien Arcadium) lacks Hard / RIP. Cells with an unrecognized
     * tier (new Hypixel tiers) are appended after column 3 in order.
     */
    public static int[] tierSlots(List<List<QueryDataOverview.Span>> cells) {
        int[] slots = new int[cells.size()];
        int extra = TIER_ORDER.size();
        for (int i = 0; i < cells.size(); i++) {
            String key = cells.get(i).get(0).difficulty();
            int idx = key == null ? -1 : TIER_ORDER.indexOf(key);
            slots[i] = idx >= 0 ? idx : extra++;
        }
        return slots;
    }

    /**
     * Measures the tier columns over <b>every</b> map-detail row of the tree (collapsed ones included), so
     * the columns neither shift nor resize when the player expands or collapses a map.
     *
     * @return the shared layout; {@code null} when the tree has no such rows
     */
    public static TierLayout tierLayout(TextRenderer tr, List<Node> roots) {
        int[][] w = {new int[TIER_ORDER.size()]};   // boxed so collectTier can grow it for extra tiers
        int[] start = {0};
        boolean[] found = {false};
        int[] pack = new int[TIER_COLUMNS];
        collectTier(tr, roots, 0, w, start, found, pack);
        return found[0] ? new TierLayout(w[0], start[0] + LEAD_GAP, pack) : null;
    }

    private static void collectTier(TextRenderer tr, List<Node> nodes, int depth, int[][] wRef,
                                    int[] start, boolean[] found, int[] pack) {
        for (Node n : nodes) {
            if (n.rightSpans != null && n.cells.isEmpty()) {
                found[0] = true;
                start[0] = Math.max(start[0], leadX(depth) + tr.getWidth(n.title));
                List<List<QueryDataOverview.Span>> cells = tierCells(n.rightSpans);
                int[] slots = tierSlots(cells);
                if (isCompactTierRow(cells, slots)) {
                    for (int i = 0; i < cells.size() - 1; i++) {
                        pack[i] = Math.max(pack[i], QueryDataOverview.spansWidth(tr, cells.get(i)));
                    }
                }
                for (int i = 0; i < cells.size(); i++) {
                    if (slots[i] >= wRef[0].length) {
                        wRef[0] = java.util.Arrays.copyOf(wRef[0], slots[i] + 1);
                    }
                    wRef[0][slots[i]] = Math.max(wRef[0][slots[i]],
                            QueryDataOverview.spansWidth(tr, cells.get(i)));
                }
            }
            collectTier(tr, n.children, depth + 1, wRef, start, found, pack);
        }
    }

    /** Total width of a line of cells (px): sum of cell widths + gaps */
    public static int cellsWidth(List<List<QueryDataOverview.Span>> cells, TextRenderer tr) {
        int w = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w += CELL_GAP;
            }
            w += QueryDataOverview.spansWidth(tr, cells.get(i));
        }
        return w;
    }

    // ---- Node model ----

    /**
     * Tree node -- describes only title / right value / cells / children;
     * indentation and line heights are decided at flatten time
     *
     * <p>A non-null {@code path} means the node can be remembered in the
     * expand state; but only nodes that actually have children draw
     * {@code [+]/[-]} and respond to clicks ({@link #collapsible()}).
     *
     * <p><b>Difficulty names are colored wherever they appear</b>, in two
     * places:
     * <ul>
     *  <li>{@code titleDiffText} -- the difficulty part of the title
     *  (fastest-time rows read "map difficulty");</li>
     *  <li>{@code cells} -- difficulty names inside data cells (one cell
     *  each for Normal/Hard/RIP/Total).</li>
     * </ul>
     * In both cases only the name itself is tinted; numbers stay white.
     */
    public static final class Node {

        /** Expand-state key; {@code null} = pure leaf */
        private final String path;
        /** Node title (gold for containers, gray for leaves; the +/- marker counts in) */
        private final String title;
        /** Difficulty text at the end of the title (colored by tier when non-null) */
        private final String titleDiffText;
        /** Difficulty key matching {@link #titleDiffText} */
        private final String titleDiffKey;
        /** Right-aligned value (nullable); used by non-grid rows, empty on container rows */
        private final String right;
        /** Per-span coloring of the right value (non-null = drawn per span:
         *  difficulty names by tier color, numbers white) */
        private final List<QueryDataOverview.Span> rightSpans;
        /**
         * Cells of a grid row -- non-empty means "this is a merged row":
         * name on the left, cells laid out into 1-2 lines as needed
         *
         * <p>Mutually exclusive with {@code right}/{@code rightSpans}: a
         * grid row carries its own text and colors, needs no joined string,
         * and takes no part in the wrap-when-too-narrow decision.
         */
        private final List<List<QueryDataOverview.Span>> cells;
        /** Title color */
        private final int color;
        /** Row spacing uses title padding (containers and section titles); leaf rows use compact padding */
        private final boolean spacious;
        /** Children (may be empty; empty makes this node a plain leaf) */
        private final List<Node> children = new ArrayList<>();

        private Node(String path, String title, String titleDiffText, String titleDiffKey,
                     String right, List<QueryDataOverview.Span> rightSpans,
                     int color, boolean spacious) {
            this(path, title, titleDiffText, titleDiffKey, right, rightSpans, color, spacious, List.of());
        }

        private Node(String path, String title, String titleDiffText, String titleDiffKey,
                     String right, List<QueryDataOverview.Span> rightSpans,
                     int color, boolean spacious, List<List<QueryDataOverview.Span>> cells) {
            this.path = path;
            this.title = title;
            this.titleDiffText = titleDiffText;
            this.titleDiffKey = titleDiffKey;
            this.right = right;
            this.rightSpans = rightSpans;
            this.cells = cells;
            this.color = color;
            this.spacious = spacious;
        }

        /** Expand-state key; {@code null} = a pure leaf that is not clickable */
        public String path() {
            return path;
        }

        /** Only nodes with children are collapsible containers; only their rows carry the {@code [+]/[-]} marker and respond to clicks */
        public boolean collapsible() {
            return path != null && !children.isEmpty();
        }

        /** The grid row's cells; an empty list when this is not a grid row */
        public List<List<QueryDataOverview.Span>> cells() {
            return cells;
        }

        /** Whether this node is a "merged big row" (has cells, uses grid layout) */
        public boolean isGridNode() {
            return !cells.isEmpty();
        }
    }

    /** Section titles like the account overview: flat rows that draw no value */
    private static Node plain(String title, String right, int color, boolean spacious) {
        return new Node(null, title, null, null, right, null, color, spacious);
    }

    /** A non-expandable row: title + optional right-aligned value (enemy kills, map detail, etc.) */
    private static Node titled(String title, String right) {
        return new Node(null, title, null, null, right, null, COLOR_LEAF, false);
    }

    /** A non-expandable row whose right value colors per span (the map detail's "Total: 30   Normal: 30  ...") */
    private static Node leafSpans(String title, List<QueryDataOverview.Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (QueryDataOverview.Span sp : spans) {
            sb.append(sp.text());
        }
        return new Node(null, title, null, null, sb.toString(), List.copyOf(spans), COLOR_LEAF, false);
    }

    /** A collapsible container: title only, never summary numbers (see the class comment) */
    private static Node group(String path, String title) {
        return new Node(path, title, null, null, null, null, COLOR_TITLE, true);
    }

    /**
     * A merged big row's data row: the name on the left, the data body a list
     * of cells.
     *
     * <p><b>Only the eight "cumulative data" rows take this path</b> - they
     * have 5 cells (four maps + Total) with five-to-six digit numbers, the
     * only kind that can overflow the panel; the four map details have at
     * most four tiers with short numbers, which can never wrap, so a single
     * right-aligned text line suffices and the grid is unnecessary.
     *
     * <p>The cells are laid out into 1 or 2 lines at flatten time by
     * {@link #gridLineCount}, but this row is always <b>one</b> {@link Row}.
     */
    private static Node grid(String title, List<List<QueryDataOverview.Span>> cells) {
        return new Node(null, title, null, null, null, null, COLOR_LEAF, false, cells);
    }

    /**
     * A leaf data row whose title carries a difficulty text: the difficulty
     * text is colored alone, the rest gray (each time-stats row reads
     * "map difficulty", and the difficulty must take its tier color).
     */
    private static Node leafDiff(String title, String diffText, String diffKey, String right) {
        return new Node(null, title, diffText, diffKey, right, null, COLOR_LEAF, false);
    }

    // ──Structure: ZombiesStats -> tree──────────────────────────────────────

    /**
     * Builds one player's stats into the free query's tree: <b>the account
     * overview first (flat), then three collapsible groups</b>.
     *
     * <p>The account overview goes through
     * {@link QueryDataOverview#accountLines}, sharing a source with the
     * in-game overview - that part is inherently flat, not a fold item;
     * without it the free query would open on three bare
     * {@code [+] Overall Stats}-style rows, with not even the queried name
     * visible without scrolling up to the input field.
     *
     * <p>Data sources and semantics follow the parser layer exactly; this
     * method only rearranges the display hierarchy:
     * <ul>
     *  <li>{@code perMap} keeps its three layers as is: map -> stat item ->
     *  values; all four map nodes are built, each map lays out rows by
     *  {@code ZombiesStatsParser.mapStatLabels()} with missing tiers as 0;
     *  {@link QueryDataOverview#DASH} is drawn only when the player has no
     *  data at all across the four maps;</li>
     *  <li>{@code enemyKills} keeps the parser layer's kill-count descending
     *  order, no re-sorting; only kills above 0 are listed - with none, the
     *  group still appears with a single "-" row;</li>
     *  <li>{@code fastestTimes} lays out rounds by {@link #FASTEST_ROUNDS}
     *  (10/20/30), each round fixed at ten "map-difficulty" scopes, uncleared
     *  ones drawing {@code --:--}.</li>
     * </ul>
     *
     * <p>{@code overall} is not rendered: its per-item values are just the
     * perMap sums, and listing them again would duplicate the expanded layer;
     * the four-map totals instead come from the
     * {@code Overall Stats -> Cumulative data} layer, reading the same
     * {@code QueryDataOverview.sumGridRows} as the in-game overview.
     *
     * @param s parsed player stats; the caller guarantees non-{@code null}
     * @return top-level nodes in render order
     */
    public static List<Node> build(ZombiesStats s) {
        List<Node> roots = new ArrayList<>();

        // ──Account overview: flat on top, same wording as the in-game overview ──
        for (QueryDataOverview.Line line : QueryDataOverview.accountLines(s)) {
            boolean header = line.value() == null;
            roots.add(plain(line.label(), line.value(), header ? COLOR_TITLE : COLOR_LEAF, header));
        }

        // ──Overall stats (group -> cumulative data + maps -> stat items)──
        //   Three decisions are fixed here and nowhere else:
        //   - "Cumulative data" is the group's first child, its eight rows sharing a source
        //     with the in-game overview (QueryDataOverview.sumGridRows), so values/order/
        //     placeholders cannot disagree with the in-game panel;
        //   - all four map nodes are built (QueryDataOverview.MAPS), maps the player never
        //     played included - missing data must not make a whole block vanish;
        //   - each map's detail lays out rows by ZombiesStatsParser.mapStatLabels() (best round
        //     first) with missing tiers as 0; "-" is drawn only when the player has no data at
        //     all on the four maps, instead of dropping the row and shifting the rest up - fixed
        //     positions are how the player remembers which row is which ──
        Node overall = group("group2", trans("nomorezombies.query.section.overall"));

        Node sums = group(SUMS_PATH, trans("nomorezombies.query.section.sums"));
        for (QueryDataOverview.GridRow row : QueryDataOverview.sumGridRows(s)) {
            sums.children.add(grid(row.label(), row.cells()));
        }
        overall.children.add(sums);

        // Whether the player has "any data at all" on the four maps: yes -> missing items as 0;
        // none -> the whole row draws "-".
        // Decide once up front, not as you go (a map sitting before a map with data would
        // otherwise be misjudged as "-")
        boolean anyMapData = hasAnyMapData(s);
        for (int i = 0; i < QueryDataOverview.MAPS.size(); i++) {
            QueryDataOverview.MapRef map = QueryDataOverview.MAPS.get(i);
            String mapLabel = map.label();
            String mapKey = ZombiesStatsParser.MAP_INTERNAL_KEYS.get(i);
            List<ZombiesStats.MapStat> stats = s.perMap.get(mapLabel);
            Node mapNode = group(MAP_PATH_PREFIX + mapKey, mapLabel);
            for (String statLabel : ZombiesStatsParser.mapStatLabels()) {
                if (!anyMapData) {
                    // No data at all: the row still appears, with only a placeholder value
                    mapNode.children.add(titled(statLabel, QueryDataOverview.DASH));
                    continue;
                }
                ZombiesStats.MapStat ms = findStat(stats, statLabel);
                // ms == null (this map lacks the item) still emits the row, all tiers 0
                mapNode.children.add(leafSpans(statLabel, mapStatSpans(ms, mapKey)));
            }
            overall.children.add(mapNode);
        }
        roots.add(overall);

        // ──Time stats (group -> round -> scope): each scope is "map-difficulty",
        //   the whole title takes that difficulty's color (the time stays white), so diffText
        //   receives the whole string. Placed before enemy kills - order per the player's
        //   request: overall stats -> time stats -> enemy kills ──
        // Rounds and scopes are both fixed skeletons: an unrecorded scope draws --:--; whole
        // rows/groups never vanish.
        //   Rounds group by the rounds Hypixel actually records (10/20/30); parsing and lookup
        //   do not distinguish maps.
        //   Round 30 is called "Final Round" <b>in the title only</b> (see roundTitle): for Dead
        //   End / Bad Blood / Prison it is the clear time; Alien Arcadium's clear is round 105,
        //   so its 30 is strictly "reached round 30" - but the upstream provides only this tier,
        //   so the same display group is reused and the data is untouched
        {
            Node times = group("group4", trans("nomorezombies.query.section.fastest"));
            Set<Integer> rounds = new TreeSet<>(FASTEST_ROUNDS);
            rounds.addAll(s.fastestTimes.keySet());
            for (int roundNo : rounds) {
                times.children.add(timeRound("group4/" + roundNo, roundTitle(roundNo),
                        s.fastestTimes.get(roundNo)));
            }
            roots.add(times);
        }

        // ──Enemy kills (group -> enemy): last. Enemies have no fixed list;
        //   only kills above 0 are listed, un-killed mobs get no row. With none killed the
        //   group still appears, holding a single "-" row ──
        Node enemies = group("group3", trans("nomorezombies.query.section.enemies"));
        for (Map.Entry<String, Long> e : s.enemyKills.entrySet()) {
            if (e.getValue() > 0) {
                enemies.children.add(titled(e.getKey(), fmt(e.getValue())));
            }
        }
        if (enemies.children.isEmpty()) {
            enemies.children.add(titled(QueryDataOverview.DASH, null));
        }
        roots.add(enemies);

        return roots;
    }

    /**
     * One round group: the fixed ten "map-difficulty" scopes, unrecorded ones
     * drawing {@code --:--}.
     *
     * @param have scope label -> seconds; may be {@code null}. Records beyond the fixed ten
     *             (new Hypixel tiers) are appended - better one extra row than a dropped one
     */
    private static Node timeRound(String path, String title, Map<String, Long> have) {
        Node round = group(path, title);
        Set<String> shown = new HashSet<>();
        for (String label : ZombiesStatsParser.fastestScopeLabels()) {
            shown.add(label);
            Long secs = have == null ? null : have.get(label);
            round.children.add(leafDiff(label, label, splitScope(label).diffKey(),
                    secs == null ? TIME_DASH : ZombiesStatsParser.formatTime(secs)));
        }
        if (have != null) {
            for (Map.Entry<String, Long> extra : have.entrySet()) {
                if (shown.add(extra.getKey())) {
                    round.children.add(leafDiff(extra.getKey(), extra.getKey(),
                            splitScope(extra.getKey()).diffKey(),
                            ZombiesStatsParser.formatTime(extra.getValue())));
                }
            }
        }
        return round;
    }

    /**
     * Splits one map's stat item's right value into colored spans:
     * {@code Normal: 30} -> difficulty name (tier color) + {@code : 30}
     * (white), three spaces between tiers.
     *
     * <p><b>Tiers are a fixed skeleton, not "draw whatever exists"</b>: which
     * tiers a map has is decided by {@link ZombiesStatsParser#mapDiffKeys}
     * (Alien Arcadium has only Normal), plus "Total"; a tier without data
     * records <b>0</b>, and when {@code ms} is null entirely, every tier is 0.
     * Tiers beyond the fixed skeleton in the data (new Hypixel tiers) are
     * appended at the end, never dropped.
     *
     * <p>The four maps' details do not use grid layout: at most four fixed
     * tiers with short numbers - one right-aligned line is enough.
     *
     * @param ms     this map's data for the item; {@code null} when absent
     * @param mapKey the map's internal key, deciding which tiers exist
     */
    private static List<QueryDataOverview.Span> mapStatSpans(ZombiesStats.MapStat ms, String mapKey) {
        List<String> diffKeys = new ArrayList<>(ZombiesStatsParser.mapDiffKeys(mapKey));
        diffKeys.add(QueryDataOverview.DIFF_TOTAL);
        List<QueryDataOverview.Span> spans = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        for (String key : diffKeys) {
            String label = QueryDataOverview.diffLabel(key);
            shown.add(label);
            Long v = ms == null ? null : ms.values.get(label);
            addDiffSpan(spans, label, key, v == null ? 0L : v);
        }
        if (ms != null) {
            for (Map.Entry<String, Long> e : ms.values.entrySet()) {
                if (shown.add(e.getKey())) {
                    // Unrecognizable difficulty names (edited language table / new tier): the whole
                    // segment stays white, no color guessing
                    addDiffSpan(spans, e.getKey(), QueryDataOverview.diffKeyOf(e.getKey()), e.getValue());
                }
            }
        }
        return spans;
    }

    /** Appends one tier: three spaces first when content precedes; the difficulty name takes its tier-key color (white when the key is null), the value white. */
    private static void addDiffSpan(List<QueryDataOverview.Span> spans, String label, String key, long value) {
        if (!spans.isEmpty()) {
            spans.add(QueryDataOverview.Span.of(TIER_SEP, QueryDataOverview.COLOR_VALUE));
        }
        spans.add(key == null
                ? QueryDataOverview.Span.of(label, QueryDataOverview.COLOR_VALUE)
                : QueryDataOverview.Span.difficulty(label, key));
        spans.add(QueryDataOverview.Span.of(": " + fmt(value), QueryDataOverview.COLOR_VALUE));
    }

    /** Whether any stat item exists on any of the four maps - decides whether missing items draw 0 or "-". */
    private static boolean hasAnyMapData(ZombiesStats s) {
        for (List<ZombiesStats.MapStat> stats : s.perMap.values()) {
            if (!stats.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds one item in a map's stat list by translated name; {@code null}
     * when absent.
     *
     * <p>{@code MapStat.label} stores the translation of the parser layer's
     * {@code statLabel}, the same wording as
     * {@link ZombiesStatsParser#mapStatLabels()}, so direct comparison works -
     * when unrecognized (an edited language table) it is treated as "this map
     * lacks the item" and a placeholder is drawn rather than guessing.
     */
    private static ZombiesStats.MapStat findStat(List<ZombiesStats.MapStat> stats, String statLabel) {
        if (stats == null) {
            return null;
        }
        for (ZombiesStats.MapStat ms : stats) {
            if (ms.label.equals(statLabel)) {
                return ms;
            }
        }
        return null;
    }

    /**
     * Splits a "{@code map-difficulty}" scope label: the whole segment takes
     * the difficulty color, the time stays white - the title segment is
     * colored as a whole by {@code QueryDataScreen.drawTreeRow}.
     *
     * <p>Split at the <b>last hyphen</b>: the parser layer's separator is
     * {@code ZombiesStatsParser.MAP_DIFF_SEP}, and map names themselves
     * contain no hyphen (English names like {@code Dead End} carry spaces).
     *
     * <p>The difficulty comes from a <b>translation reverse lookup</b>, not a
     * guess: the split segment is compared against the four difficulty
     * translations, and the matching one's tier key is used; unrecognized
     * (edited language table / new Hypixel tier) treats the whole segment as
     * difficulty text with the color falling back to gray.
     */
    private static ScopeLabel splitScope(String scope) {
        int sep = scope.lastIndexOf(ZombiesStatsParser.MAP_DIFF_SEP);
        if (sep <= 0 || sep >= scope.length() - 1) {
            return new ScopeLabel(scope, "", null);
        }
        String map = scope.substring(0, sep);
        String diff = scope.substring(sep + ZombiesStatsParser.MAP_DIFF_SEP.length());
        for (String key : new String[]{QueryDataOverview.DIFF_NORMAL, QueryDataOverview.DIFF_HARD,
                QueryDataOverview.DIFF_RIP, QueryDataOverview.DIFF_TOTAL}) {
            if (diff.equals(QueryDataOverview.diffLabel(key))) {
                return new ScopeLabel(map, diff, key);
            }
        }
        return new ScopeLabel(map, diff, null);
    }

    /** The split scope label: map name + difficulty text + difficulty tier key ({@code null} when unrecognized). */
    private record ScopeLabel(String mapLabel, String difficulty, String diffKey) {
    }

    // ──Flatten: tree + expansion state -> the rows to draw this frame───────

    /**
     * A flattened <b>big row</b> - the name centered in the left column, the
     * data body in the right area.
     *
     * <p>One row may lay out as two text lines ({@link #lines} = 2, many
     * cells), but it is still <b>one</b> object: one row height, one
     * hover/selection plate, one click hit. All geometry lives at this level
     * too ({@link #depth}/{@link #branchMask}/{@link #topStub}/
     * {@link #bottomStub}), and the screen paints continuous tree lines from
     * it - character-drawn branches are born with seams; see the class
     * comment.
     */
    public static final class Row {

        /** Which node this row belongs to - both click hit-testing and expand toggling read it. */
        public final Node node;
        /** Collapse marker + row name. */
        public final String lead;
        /** The difficulty text inside the row name (colored by tier when non-null; nulled when wrapped). */
        public final String leadDiffText;
        /** The difficulty tier key matching {@link #leadDiffText}. */
        public final String leadDiffKey;
        /** Right-aligned value (may be {@code null}); used by non-grid rows. */
        public final String right;
        /** Per-span coloring of the right value (drawn per span when non-null). */
        public final List<QueryDataOverview.Span> rightSpans;
        /** The data body's cells; empty = this row is not a grid big row. */
        public final List<List<QueryDataOverview.Span>> cells;
        /** How many text lines this row lays out internally (always 1 for non-grid rows). */
        public final int lines;
        /** Grid row data-body start (px, from the panel's left edge); 0 for non-grid rows. */
        public final int valueOffset;
        /** Indent level: 0 = the account overview's flat rows, 1 = Overall Stats' children... (tree-line column = depth - 1). */
        public final int depth;
        /** Bitmask: bit i set = column i draws a vertical line through this row (that ancestor has later siblings). */
        public final int branchMask;
        /** This row draws the upper half of its own column's vertical line (a sibling exists above). */
        public final boolean topStub;
        /** This row draws the lower half of its own column's vertical line (a sibling exists below). */
        public final boolean bottomStub;
        /** Row spacing uses title padding (container rows). */
        public final boolean spacious;
        /** The color of {@link #lead}. */
        public final int color;

        private Row(Node node, String lead, String leadDiffText, String leadDiffKey,
                    String right, List<QueryDataOverview.Span> rightSpans,
                    List<List<QueryDataOverview.Span>> cells, int lines, int valueOffset,
                    int depth, int branchMask, boolean topStub, boolean bottomStub,
                    boolean spacious, int color) {
            this.node = node;
            this.lead = lead;
            this.leadDiffText = leadDiffText;
            this.leadDiffKey = leadDiffKey;
            this.right = right;
            this.rightSpans = rightSpans;
            this.cells = cells;
            this.lines = lines;
            this.valueOffset = valueOffset;
            this.depth = depth;
            this.branchMask = branchMask;
            this.topStub = topStub;
            this.bottomStub = bottomStub;
            this.spacious = spacious;
            this.color = color;
        }

        /** Whether this row is a "merged big row" (has cells). */
        public boolean isGrid() {
            return !cells.isEmpty();
        }

        /** Copies the row changing only the stub flags - the segments of a wrapped row share one node and geometry. */
        private Row withStubs(boolean top, boolean bottom) {
            return new Row(node, lead, leadDiffText, leadDiffKey, right, rightSpans, cells, lines, valueOffset,
                    depth, branchMask, top, bottom, spacious, color);
        }
    }

    /**
     * Flattens the node tree into the rows to draw this frame under the
     * current expansion state.
     *
     * <p><b>This is the only source of "currently visible rows".</b> The
     * children of collapsed nodes are never visited - so expanding/collapsing
     * just swaps the {@code expanded} set: no tree rebuild, no re-request.
     *
     * @param tr       for font metrics (measures width only, produces no coordinates)
     * @param roots    {@link #build}'s return value
     * @param expanded the player's currently expanded node paths; may be empty, never {@code null}
     * @param avail    the panel's available width (px): decides whether a row's cells lay out on one line or two
     * @return the big rows to draw, top to bottom
     */
    public static List<Row> flatten(TextRenderer tr, List<Node> roots, Set<String> expanded, int avail) {
        List<Row> out = new ArrayList<>();
        // The grid data-body start is computed once for the whole tree: all "cumulative data"
        // rows share one boundary
        int gridOff = gridValueOffset(tr, roots, 0);
        for (Node root : roots) {
            /* Top level (account overview and the three groups) has no columns: last is true,
               so it draws no lower stub and its children gain no extra through-line */
            appendNode(out, tr, root, expanded, avail, gridOff, 0, 0, true);
        }
        return out;
    }

    /**
     * Appends the rows for one node (and its subtree, when expanded).
     *
     * @param depth       nesting depth; top level is 0
     * @param branchMask  continuation mask of ancestor columns (bit i = column i draws a vertical line)
     * @param gridOff     grid data-body start (px, from the panel's left edge), see {@link #gridValueOffset}
     * @param last        whether this node is the last among its siblings (decides the lower stub)
     */
    private static void appendNode(List<Row> out, TextRenderer tr, Node node, Set<String> expanded,
                                   int avail, int gridOff, int depth, int branchMask, boolean last) {
        boolean open = node.collapsible() && expanded.contains(node.path);
        String lead = (node.collapsible() ? (open ? MARK_EXPANDED : MARK_COLLAPSED) : "") + node.title;

        // Upper stub: every row with a column (depth >= 1) draws one - the first child must
        // also connect from the parent's row, otherwise the vertical line between the parent
        // and its first child is broken; lower stub: only when later siblings follow
        boolean top = depth >= 1;
        boolean bottom = depth >= 1 && !last;

        if (node.isGridNode()) {
            // Merged big row: name + cells; the cells lay out on 1 or 2 lines by rule, but the
            // row is still one
            List<List<QueryDataOverview.Span>> cells = node.cells();
            out.add(new Row(node, lead, null, null, null, null, cells,
                    gridLineCount(cells, tr, avail - gridOff), gridOff,
                    depth, branchMask, top, bottom, node.spacious, node.color));
        } else {
            appendTextFragments(out, tr, node, lead, avail, depth, branchMask, top, bottom);
        }

        appendChildren(out, tr, node, expanded, avail, gridOff, depth, branchMask, last, open);
    }

    /**
     * Text fragments of a non-grid row: when one line fits, "name +
     * right-aligned value"; when it does not, the name takes its line(s) and
     * the value continues on its own lines.
     *
     * <p>Wrapped continuation lines no longer carry difficulty coloring: once
     * a segment is cut across lines, deriving colors from segment offsets only
     * drifts further off. The segments share one node and geometry
     * ({@code depth}/{@code branchMask}/{@code topStub}/
     * {@code bottomStub}), and the screen paints the ancestor columns'
     * vertical lines on every segment, so wrapping never breaks a line.
     */
    private static void appendTextFragments(List<Row> out, TextRenderer tr, Node node, String lead,
                                            int avail, int depth, int branchMask,
                                            boolean top, boolean bottom) {
        // Available width = panel width - this row's name origin: ordinary rows like the map
        // detail have no "name column", the whole width is theirs - they must not reuse the
        // narrower width reserved for grids,
        // or a fitting "best round + four tiers" would be split into white multi-line text,
        // losing both difficulty colors and indent
        int valueW = Math.max(1, avail - leadX(depth));
        int rightW = node.right == null ? 0 : GAP + tr.getWidth(node.right);
        List<Row> frags = new ArrayList<>();

        if (tr.getWidth(lead) + rightW <= valueW) {
            boolean hasDiff = node.titleDiffText != null && !node.titleDiffText.isEmpty()
                    && node.titleDiffKey != null;
            frags.add(new Row(node, lead,
                    hasDiff ? node.titleDiffText : null, hasDiff ? node.titleDiffKey : null,
                    node.right, node.rightSpans, List.of(), 1, 0,
                    depth, branchMask, false, false, node.spacious, node.color));
        } else {
            List<String> heads = tr.getWidth(lead) <= valueW
                    ? List.of(lead)
                    : QueryDataOverview.wrapText(tr, lead, valueW);
            for (int i = 0; i < heads.size(); i++) {
                frags.add(new Row(node, heads.get(i), null, null, null, null, List.of(), 1, 0,
                        depth, branchMask, false, false, node.spacious && i == 0, node.color));
            }
            if (node.right != null) {
                for (String line : QueryDataOverview.wrapText(tr, node.right, valueW)) {
                    frags.add(new Row(node, line, null, null, null, null, List.of(), 1, 0,
                            depth, branchMask, false, false, false, COLOR_VALUE));
                }
            }
        }

        // Segments share one ancestor line: only the first draws the upper stub and the last
        // the lower stub; middle segments draw the full row height
        for (int i = 0; i < frags.size(); i++) {
            out.add(frags.get(i).withStubs(i != 0 ? depth >= 1 : top,
                    i != frags.size() - 1 ? depth >= 1 : bottom));
        }
    }

    /**
     * Appends expanded children after this node (collapsed does nothing).
     *
     * <p>Its own method because both the "merged big row" and "text row"
     * paths go through it - collapsing/expanding and "how this row draws" are
     * two matters that must not be copied into two branches.
     *
     * @param gridOff    the grid data-body start, passed to children unchanged
     * @param depth      this node's depth; children's column number is {@code depth}
     * @param last       whether this node is the last - when not, children must continue
     *                   drawing the vertical line on <b>this node's column</b>
     */
    private static void appendChildren(List<Row> out, TextRenderer tr, Node node, Set<String> expanded,
                                       int avail, int gridOff, int depth, int branchMask, boolean last, boolean open) {
        if (!open) {
            return;
        }
        // This node's column number is depth-1 (top level has none); when it is not the last,
        // children draw one extra through-line
        int childMask = (last || depth < 1) ? branchMask : (branchMask | (1 << (depth - 1)));
        for (int i = 0; i < node.children.size(); i++) {
            appendNode(out, tr, node.children.get(i), expanded, avail, gridOff, depth + 1, childMask,
                    i == node.children.size() - 1);
        }
    }
    // ──Wording / formatting─────────────────────────────────────────────────

    /** Resolves a translation key's text (follows the client language). */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    /**
     * Round group titles: the {@link #FINAL_ROUND} group is "Final Round",
     * the rest "Round N".
     *
     * <p>Wording only: the node path stays {@code group4/<N>}, data is looked
     * up by round number, and no per-map branching exists.
     */
    private static String roundTitle(int roundNo) {
        return roundNo == FINAL_ROUND ? trans("nomorezombies.query.fastest.final") : transRound(roundNo);
    }

    /** The "Round N" group title - this class's only parameterized translation; the key is hard-coded here rather than adding a constant key parameter. */
    private static String transRound(Object round) {
        return Text.translatable("nomorezombies.query.fastest.round", round).getString();
    }

    /** Thousands-separator formatting (%,d). */
    private static String fmt(long n) {
        return String.format("%,d", n);
    }
}