package cn.gsfy.nmz.client.features.querydata;

import cn.gsfy.nmz.client.config.hud.HUDEditor;
import cn.gsfy.nmz.client.data.model.ApiResult;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import cn.gsfy.nmz.client.utils.AvatarUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The player-data query screen (a native Screen, does not pause the game) -
 * spreads the data pulled from the Hypixel API into a scrollable
 * <b>overview panel</b>.
 *
 * <p>The top switches between "free query" (type a player name/UUID) and
 * "in-game query" (cache-based, click the list to switch); the in-game query
 * button is clickable only during a game
 * ({@link PlayerUtils#isInZombies()} true). Without an API key, the query
 * button and in-game auto requests are all disabled, a yellow warning shows
 * at the top first, and it disappears once a key is configured.
 *
 * <p>The panel content is the column of lines {@link QueryDataOverview}
 * produces (account overview + eight four-map cumulative items + fastest
 * clear + best map), <b>with no collapsible structure</b>; the free-query
 * side instead uses {@link QueryDataTree}'s collapsible tree.
 * This class handles layout only: {@link DrawContext#enableScissor} clipping
 * (note it takes absolute coordinates) plus {@code scrollOffset} translation
 * plus hover highlight. The background matches the HUD editor: only a
 * translucent veil ({@link HUDEditor#VEIL_COLOR} - one constant shared by
 * both); there is no separate ✕ button, Esc returns (vanilla Screen key
 * handling).
 *
 * <p><b>Coordinates are declared in exactly one place</b>: each of the
 * panel's four edges comes from one method
 * ({@link #inGamePanelX()} / {@link #inGamePanelY()} / {@link #freePanelY()}
 * / {@link #panelRightEdge()}), and the list and panel share
 * {@link #LIST_Y} - rendering and hit-testing each keeping their own copy of
 * a coordinate is how they drift apart on the first edit.
 *
 * <p>Coordinate system: the mode buttons are pinned at {@link #MODE_BTN_Y};
 * the list/panel origin ({@link #LIST_Y}) derives from it; the free mode's
 * input field and result panel follow the key state. Anchors:
 * <pre>
 *  Y=8   title centered
 *  Y=32  mode buttons (Free Query | In-Game) - fixed, never drifts with hasKey
 *  Y=56  in-game player list / detail panel origin (MODE_BTN_Y + 20 + 4)
 *  Y=58  free mode input field (hasKey) / warning banner origin (no key)
 *  panelY (free) = inputField.getY() + inputField.getHeight() + 8  dynamic follow
 *  panelY (in-game) = LIST_Y (aligned with the list origin)
 * </pre>
 */
public class QueryDataScreen extends Screen {

    /**
     * Fixed Y of the mode buttons - both pinned at 32, never drifting with
     * hasKey, so the other mode is always one click away.
     */
    private static final int MODE_BTN_Y = 32;
    /**
     * Origin Y of the in-game player list / detail panel - the toolbar is
     * gone, so the list follows the mode buttons directly
     * ({@link #MODE_BTN_Y} + button height 20 + gap 4) instead of a floating
     * magic number.
     */
    private static final int LIST_Y = MODE_BTN_Y + 20 + 4;

    /** In-game list entry height (px). */
    private static final int ENTRY_H = 20;
    /** In-game list left edge (px). */
    private static final int LIST_X = 10;
    /** Margin (px) between panels and the screen's right/bottom edges. */
    private static final int PANEL_MARGIN = 10;

    /** Inset (px) of a list entry's backdrop relative to the entry box: a little off each side, so the backdrop does not touch the panel edge. */
    private static final int ENTRY_BG_INSET = 2;
    /**
     * Top edge and height (px, relative to the entry origin ey) of a list
     * entry's backdrop - sized to the actual ink of "avatar / two text
     * lines": the avatar occupies {@code ey+5..ey+13}, the two text lines sit
     * at {@code ey+4} and {@code ey+12} (line height 9), shadows reach
     * {@code ey+21}. The backdrop is therefore {@code ey+3 .. ey+21},
     * exactly wrapping avatar and both lines;
     * <b>do not go back to deriving it from {@link #ENTRY_H}</b> - that clips
     * the second line at the bottom.
     */
    private static final int ENTRY_BG_TOP = 3;
    private static final int ENTRY_BG_H = 18;

    private final Screen parent;

    private boolean freeMode = true;
    private int selectedIndex;
    private int scrollOffset;

    // Free-query state: whether a request is in flight, where the last result landed
    private boolean freeLoading;
    private ApiResult freeResult;

    // Mouse position - refreshed every render frame; the detail panel uses it to find the hovered row
    private int hoverMouseX;
    private int hoverMouseY;

    // Widgets: built in init(), positioned in layout()
    private TextFieldWidget inputField;
    private ButtonWidget queryButton;
    private ButtonWidget modeFreeButton;
    private ButtonWidget modeInGameButton;

    /**
     * The free query's tree expansion state: the input field's name (lower
     * case) -> the set of expanded node paths.
     *
     * <p>Pure UI state, not written into {@link QueryDataManager} (no
     * lifecycle tie to the network cache); it dies with the Screen instance
     * on close. Stored by <b>name</b> rather than an index, so querying the
     * same player again continues from last time; the in-game query has no
     * tree and never touches it.
     */
    private final Map<String, Set<String>> expandedNodes = new HashMap<>();
    /** The free query's tree structure cache: one stats object builds the tree once; only a new name/result rebuilds it. */
    private ZombiesStats treeSource;
    private List<QueryDataTree.Node> treeRoots = List.of();

    /** Constructor: remembers the return screen (switched back on close); widgets and state are built in {@link #init()}. */
    public QueryDataScreen(Screen parent) {
        super(Text.translatable("nomorezombies.query.title"));
        this.parent = parent;
    }

    // ──Lifecycle────────────────────────────────────────────────────────────

    /** Builds every widget: mode toggles, the free input field; coordinates are fixed to prevent drift
     *  (no ✕ button - Esc-to-close is the vanilla Screen convention; no need to draw another). */
    @Override
    protected void init() {
        this.clearChildren();
        this.selectedIndex = 0;
        this.scrollOffset = 0;

        // Mode buttons pinned at Y=32, excluded from all later coordinate drift
        this.modeFreeButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.mode.free"), b -> switchMode(true))
                .dimensions(10, MODE_BTN_Y, 90, 20).build();
        this.modeInGameButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.mode.ingame"), b -> switchMode(false))
                .dimensions(104, MODE_BTN_Y, 90, 20).build();

        // Free mode: input field + query button - Y is not fixed; layout() positions it by hasKey
        this.inputField = new TextFieldWidget(this.textRenderer, 10, 58, 200, 20,
                Text.translatable("nomorezombies.query.input.placeholder"));
        this.inputField.setMaxLength(40);
        this.inputField.setPlaceholder(Text.translatable("nomorezombies.query.input.placeholder"));
        this.queryButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.button.query"), b -> doFreeQuery())
                .dimensions(214, 58, 60, 20).build();

        // No "switch teammate" toolbar: the player clicks the list themselves -
        // not worth a row of buttons, and not worth an auto-carousel

        this.addDrawableChild(modeFreeButton);
        this.addDrawableChild(modeInGameButton);
        this.addDrawableChild(inputField);
        this.addDrawableChild(queryButton);

        layout();
    }

    /** Closes the screen: switches back to the return screen when there is one, otherwise the framework default (this screen does not pause the game). */
    @Override
    public void close() {
        if (parent != null) {
            // Use the singleton instead of this.client: the latter is injected by the framework
            // at setScreen time and static analysis sees a nullable field, while the singleton is
            // always non-null
            MinecraftClient.getInstance().setScreen(parent);
        } else {
            super.close();
        }
    }

    /** The query screen does not pause the game: zombies keep coming while the query page is open in-game. */
    @Override
    public boolean shouldPause() {
        return false;
    }

    // ──Mode switch / layout─────────────────────────────────────────────────

    /** Switches free/in-game mode: clears selection and scroll, lets {@code layout()} re-place widgets. */
    private void switchMode(boolean free) {
        this.freeMode = free;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        layout();
    }

    /**
     * Positions widgets by mode, key state and whether a game is running
     * (called from init and mode switches).
     *
     * <p>Coordinate duties are separated: the mode buttons have a pinned Y
     * ({@link #MODE_BTN_Y}) and never drift; the free mode's input field Y
     * follows hasKey (58 with a key, 100 without), and the result panel
     * dynamically follows the input field's bottom - that gives the warning
     * banner a place to stand without covering the input field. The in-game
     * button is clickable only when {@link PlayerUtils#isInZombies()} is
     * true, so a non-Zombies screen cannot produce an empty list.
     */
    private void layout() {
        QueryDataManager manager = QueryDataManager.get();
        boolean hasKey = manager.hasApiKey();
        boolean inZombies = PlayerUtils.isInZombies();
        boolean inGame = !freeMode;

        // The current mode's button grays out (gray = selected); the other side stays lit and clickable
        this.modeFreeButton.active = inGame;
        // In-game query button: disabled outside a Zombies game - clicking it has nothing to list
        this.modeInGameButton.active = freeMode && inZombies;

        // Free-mode widgets: all hidden in in-game mode; only the list and detail remain
        this.inputField.visible = !inGame;
        this.queryButton.visible = !inGame;
        this.queryButton.active = !inGame && hasKey;

        // Free mode: the input field's Y follows hasKey - without a key the warning banner
        // takes about 42px, so everything shifts down to make room
        if (!inGame) {
            int inputY = hasKey ? 58 : 100;
            this.inputField.setY(inputY);
            this.queryButton.setY(inputY);
            this.inputField.setX(10);
            this.queryButton.setX(this.inputField.getX() + this.inputField.getWidth() + 4);
        }
    }

    // ──Rendering────────────────────────────────────────────────────────────

    /** Only a translucent veil: the game picture is not interrupted, and the fight behind stays visible.
     *  The veil shade shares {@link HUDEditor#VEIL_COLOR} with the HUD editor - one tier across both screens,
     *  no second literal. */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, HUDEditor.VEIL_COLOR);
    }

    /** Per-frame main drawing: title + no-key warning -> dispatch to free/in-game rendering -> hand widgets to the framework last. */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // Record the mouse position first so panels can compute the hovered row - every frame
        this.hoverMouseX = mouseX;
        this.hoverMouseY = mouseY;

        // Title centered at the top
        String title = this.title.getString();
        context.drawTextWithShadow(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, 8, 0xFFFFFF);

        if (!QueryDataManager.get().hasApiKey()) {
            drawNoKeyWarning(context);
        }

        if (freeMode) {
            renderFreeMode(context);
        } else {
            renderInGameMode(context);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /** The yellow warning shown without a key - says where to apply and where to paste it,
     *  otherwise the player just sees a screen that can never query. */
    private void drawNoKeyWarning(DrawContext context) {
        String[] lines = {
                Text.translatable("nomorezombies.query.warning.nokey.line1").getString(),
                Text.translatable("nomorezombies.query.warning.nokey.line2").getString(),
                Text.translatable("nomorezombies.query.warning.nokey.line3").getString()
        };
        int y = 58;
        // Line height is fontHeight+2 rather than a hard-coded 10px - a font-size change would
        // make a fixed value overlap the text
        int lineH = this.textRenderer.fontHeight + 2;
        for (String line : lines) {
            context.drawTextWithShadow(this.textRenderer, line, 10, y, 0xFFFF55);
            y += lineH;
        }
    }

    // ──Free query───────────────────────────────────────────────────────────

    /** Fires a free query: empty input errors immediately, an in-flight request is ignored, and the async result resets scrolling. */
    private void doFreeQuery() {
        if (this.freeLoading) {
            return;
        }
        String input = this.inputField.getText();
        if (input == null || input.trim().isEmpty()) {
            this.freeResult = ApiResult.error("nomorezombies.query.status.nodata");
            return;
        }
        this.freeLoading = true;
        this.freeResult = null;
        QueryDataManager.get().queryFree(input, result -> {
            this.freeLoading = false;
            this.freeResult = result;
            this.scrollOffset = 0;
        });
    }

    /** Free-mode rendering: loading / idle / error / result - each state draws its own. */
    private void renderFreeMode(DrawContext context) {
        // panelY dynamically follows the input field's bottom rather than a fixed value -
        // the input field moves with hasKey, and a fixed value would misalign
        int panelX = freePanelX();
        int panelY = freePanelY();
        int panelRight = panelRightEdge();

        if (freeLoading) {
            drawStatus(context, panelX, panelY, tr("nomorezombies.query.status.loading"), 0xFFFF55);
            return;
        }
        if (freeResult == null) {
            drawStatus(context, panelX, panelY, tr("nomorezombies.query.status.idle"), 0xAAAAAA);
            return;
        }
        if (!freeResult.ok()) {
            drawError(context, panelX, panelY, panelRight - panelX, freeResult.errorKey(), freeResult.arg());
            return;
        }
        // The free query uses the collapsible tree: parents render only their title ("Combined stats"...),
        // the player expands the detail themselves
        drawTreePanel(context, freeResult.stats(), panelX, panelY, panelRight);
    }

    /** Free-mode panel left edge (px) - each of the panel's four edges has exactly one source. */
    private int freePanelX() {
        return PANEL_MARGIN;
    }

    /** Free-mode panel origin Y - dynamically follows the input field's bottom. */
    private int freePanelY() {
        return this.inputField.getY() + this.inputField.getHeight() + 8;
    }

    // ──In-game query────────────────────────────────────────────────────────

    /** In-game rendering: player list on the left + detail panel on the right, each list row embedding a request-status brief. */
    private void renderInGameMode(DrawContext context) {
        QueryDataManager manager = QueryDataManager.get();
        List<String> names = manager.currentInGameNames();
        boolean hasKey = manager.hasApiKey();

        // Player list on the left: width adapts to the names, horizontal space yields to the
        // detail panel on the right.
        // The list origin is pinned at LIST_Y, its own coordinate separate from the toolbar
        int listW = inGameListWidth();

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int ey = LIST_Y + i * ENTRY_H;
            if (i == selectedIndex) {
                // The backdrop follows the actual ink of "avatar / two text lines", not ENTRY_H -
                // that is the only way to wrap both lines: no gap at the top, no clipped glyphs at
                // the bottom
                int bgTop = ey + ENTRY_BG_TOP;
                context.fill(LIST_X + ENTRY_BG_INSET, bgTop,
                        LIST_X + listW - ENTRY_BG_INSET, bgTop + ENTRY_BG_H, 0x40FFFFFF);
            }
            AvatarUtils.drawHead(context, name, null, LIST_X + 3, ey + 5);
            context.drawTextWithShadow(this.textRenderer, name, LIST_X + 16, ey + 4, 0xFFFFFF);
            // Second small line: loading / error / network level - each player's request state at a glance
            String brief = briefFor(manager, name, hasKey);
            context.drawTextWithShadow(this.textRenderer, brief, LIST_X + 16, ey + 12, 0x888888);
        }
        if (names.isEmpty()) {
            drawStatus(context, LIST_X, LIST_Y, tr("nomorezombies.query.status.noplayer"), 0xAAAAAA);
            return;
        }

        // Detail panel on the right: clip + scroll; the body is drawn in drawOverviewPanel
        if (selectedIndex >= names.size()) {
            selectedIndex = names.size() - 1;
        }
        int panelX = inGamePanelX();
        int panelY = inGamePanelY();
        String selected = names.get(selectedIndex);
        ZombiesStats stats = manager.getCached(selected);
        if (stats != null) {
            drawOverviewPanel(context, stats, panelX, panelY, panelRightEdge());
        } else if (manager.isLoading(selected)) {
            drawStatus(context, panelX, panelY, tr("nomorezombies.query.status.loading"), 0xFFFF55);
        } else {
            ApiResult err = manager.getError(selected);
            if (err != null) {
                drawError(context, panelX, panelY, panelRightEdge() - panelX, err.errorKey(), err.arg());
                drawStatus(context, panelX, panelY + this.textRenderer.fontHeight + 4,
                        tr("nomorezombies.query.hint.retry"), 0xFFFF55);
            } else {
                drawStatus(context, panelX, panelY,
                        hasKey ? tr("nomorezombies.query.status.nodata") : tr("nomorezombies.query.warning.nokey"),
                        0xAAAAAA);
            }
        }
    }

    /** In-game detail panel left edge (px) - the list width adapts. */
    private int inGamePanelX() {
        return LIST_X + inGameListWidth() + 10;
    }

    /** In-game detail panel origin Y - the same constant as the list origin, no second number. */
    private int inGamePanelY() {
        return LIST_Y;
    }

    /** Panel right edge (px) - shared by both modes. */
    private int panelRightEdge() {
        return this.width - PANEL_MARGIN;
    }

    /** A list entry's second small line - one phrase picked by "no key -> loading -> failed -> network level -> waiting". */
    private String briefFor(QueryDataManager manager, String name, boolean hasKey) {
        if (!hasKey) {
            return tr("nomorezombies.query.hint.nokey.short");
        }
        if (manager.isLoading(name)) {
            return tr("nomorezombies.query.status.loading");
        }
        if (manager.getError(name) != null) {
            return tr("nomorezombies.query.status.failed");
        }
        ZombiesStats stats = manager.getCached(name);
        if (stats != null) {
            return "Lv." + stats.networkLevel;
        }
        return tr("nomorezombies.query.status.waiting");
    }

    // ──Overview panel (shared by the free query result / the in-game selected player)──

    /**
     * Draws the overview panel (the scrollable stats area).
     *
     * <p>Clipping goes through {@link DrawContext#enableScissor}: it takes
     * the two corners' <strong>absolute coordinates</strong>
     * {@code (x1, y1, x2, y2)}, not {@code (x, y, width, height)}.
     */
    private void drawOverviewPanel(DrawContext context, ZombiesStats s, int panelX, int panelY, int panelRight) {
        int panelBottom = this.height - PANEL_MARGIN;
        int avail = panelRight - panelX;
        int fh = this.textRenderer.fontHeight;

        // Fold into drawable rows first and compute the content height exactly - only then is
        // the scroll range right - and clip-draw after that
        List<QueryDataOverview.DrawRow> rows =
                QueryDataOverview.wrap(this.textRenderer, QueryDataOverview.lines(s), avail);
        int contentH = 0;
        for (QueryDataOverview.DrawRow r : rows) {
            contentH += r.gap(fh);
        }
        int maxScroll = Math.max(0, contentH - (panelBottom - panelY));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        // enableScissor takes absolute coordinates (panelX, panelY, panelRight, panelBottom);
        // passing width/height shifts the whole clip region
        context.enableScissor(panelX, panelY, panelRight, panelBottom);
        int y = panelY - scrollOffset;
        for (QueryDataOverview.DrawRow r : rows) {
            int rowH = r.gap(fh);
            // Hover highlight: the highlight height covers only the text itself (fh), not the
            // line pitch - otherwise the band's bottom edge bleeds into the next row.
            // The hit zone still uses the full rowH: the gap between rows also triggers the
            // current row, which makes it easier to click
            if (hoverMouseX >= panelX && hoverMouseX < panelRight
                    && hoverMouseY >= y && hoverMouseY < y + rowH) {
                context.fill(panelX, y, panelRight, y + fh, 0x28FFFFFF);
            }
            int textX = r.indent() ? panelX + QueryDataOverview.INDENT : panelX;
            if (r.right() == null) {
                context.drawTextWithShadow(this.textRenderer, r.left(), textX, y, r.color());
            } else {
                context.drawTextWithShadow(this.textRenderer, r.left(), textX, y, r.color());
                drawRight(context, r.right(), r.rightSpans(), panelRight, y);
            }
            y += rowH;
        }
        context.disableScissor();
    }

    /**
     * Draws a row's right value: right-aligned, colored segment by segment.
     *
     * <p>With {@code spans} null the whole value is white; otherwise each
     * segment is drawn left to right from the right-aligned origin - a
     * segment's start is "the total width of the segments before it", no
     * re-layout.
     */
    private void drawRight(DrawContext context, String right, List<QueryDataOverview.Span> spans,
                           int panelRight, int y) {
        int vw = this.textRenderer.getWidth(right);
        int x = panelRight - vw;
        if (spans == null) {
            context.drawTextWithShadow(this.textRenderer, right, x, y, QueryDataOverview.COLOR_VALUE);
            return;
        }
        drawSpans(context, spans, x, y);
    }

    /** In-game list width: adapts to the longest name (clamped to 130-210px), yielding horizontal space to the detail panel. */
    private int inGameListWidth() {
        int maxName = 0;
        for (String n : QueryDataManager.get().currentInGameNames()) {
            maxName = Math.max(maxName, this.textRenderer.getWidth(n));
        }
        return Math.clamp(16 + maxName + 10, 130, 210);
    }

    // ──The free query's tree panel──────────────────────────────────────────

    /**
     * Draws the free query's collapsible tree panel (same clip + scroll
     * mechanics as the overview panel).
     *
     * <p>Mechanics identical to the overview panel: clipping takes absolute
     * coordinates, row height derives from fontHeight, and panelY dynamically
     * follows the input field.
     */
    private void drawTreePanel(DrawContext context, ZombiesStats s, int panelX, int panelY, int panelRight) {
        int panelBottom = this.height - PANEL_MARGIN;
        int fh = this.textRenderer.fontHeight;

        List<QueryDataTree.Row> rows = treeRows(s, panelRight - panelX);
        int contentH = 0;
        for (QueryDataTree.Row r : rows) {
            contentH += treeRowGap(r, fh);
        }
        int maxScroll = Math.max(0, contentH - (panelBottom - panelY));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        // Summary-layout column widths measured over all rows, so every cumulative row shares
        // one column grid
        int[] summaryW = QueryDataTree.summaryColumnWidths(rows, this.textRenderer);
        // Tier columns of the map detail (Normal / Hard / RIP / Total), measured over the whole tree
        // so they stay put when maps are expanded or collapsed
        QueryDataTree.TierLayout tier = QueryDataTree.tierLayout(this.textRenderer, treeRoots);

        context.enableScissor(panelX, panelY, panelRight, panelBottom);
        int y = panelY - scrollOffset;
        for (QueryDataTree.Row r : rows) {
            int rowH = treeRowGap(r, fh);
            // Hover highlight: a merged big row lights up as one block (both lines together) -
            // "selected reads as one big row too"
            if (isHoverable(r)
                    && hoverMouseX >= panelX && hoverMouseX < panelRight
                    && hoverMouseY >= y && hoverMouseY < y + rowH) {
                context.fill(panelX, y, panelRight, y + (r.isGrid() ? rowH : fh), 0x28FFFFFF);
            }
            drawTreeLines(context, r, panelX, y, rowH);
            drawTreeRow(context, r, panelX, panelRight, y, summaryW, tier);
            y += rowH;
        }
        context.disableScissor();
    }

    /**
     * A row's height (px): <b>the two lines of a merged big row together take
     * exactly one computed block height</b>, with no extra gap inside - that
     * is why it reads as one block, not three small rows.
     */
    private static int treeRowGap(QueryDataTree.Row r, int fh) {
        return r.lines * fh + (r.spacious ? 5 : 2);
    }

    /** Rows excluded from hover highlight: pure section titles (unclickable, no value, no cells). */
    private static boolean isHoverable(QueryDataTree.Row r) {
        return !(r.node.path() == null && r.right == null && !r.isGrid() && r.spacious);
    }

    // ──Tree lines: self-drawn continuous segments (no box-drawing characters)──

    /**
     * Draws a row's left tree lines - <b>all filled rects, no box-drawing
     * characters</b>.
     *
     * <p>Character-drawn branches are born with gaps: the {@code │} glyph
     * does not fill its cell, leaving seams between the glyphs of adjacent
     * rows, which reads as broken dashed lines (2026-09 feedback). Filling
     * rects by geometry here makes adjacent rows butt end to end, and the
     * whole tree is one continuous line.
     *
     * <p>Three pieces:
     * <ul>
     *  <li><b>Through-line</b>: columns set to 1 in
     *  {@link QueryDataTree.Row#branchMask}, drawn for the full row height -
     *  that ancestor still has later siblings, the line must not break;</li>
     *  <li><b>Own column's vertical stubs</b>: {@link QueryDataTree.Row#topStub} /
     *  {@code bottomStub} each draw half the row, together forming the
     *  segment when "there is a sibling above/below";</li>
     *  <li><b>Horizontal twig</b>: a short right turn from this column's
     *  vertical line at the row's vertical center, meeting the row name.</li>
     * </ul>
     *
     * @param rowH the row's total height from {@link #treeRowGap} - lines are drawn by row height,
     *             not glyph height, so the two lines inside a merged big row also carry the line
     */
    private void drawTreeLines(DrawContext context, QueryDataTree.Row r, int panelX, int y, int rowH) {
        if (r.depth < 1) {
            return;
        }
        int half = rowH / 2;
        // Through-lines: ancestor columns, full row height
        for (int i = 0; i < r.depth - 1; i++) {
            if ((r.branchMask & (1 << i)) == 0) {
                continue;
            }
            vline(context, panelX + QueryDataTree.TREE_W + i * QueryDataTree.INDENT_STEP, y, rowH);
        }
        // Own column: upper/lower halves each drawn once, together continuous (the half without
        // a sibling stays empty)
        int colX = panelX + QueryDataTree.TREE_W + (r.depth - 1) * QueryDataTree.INDENT_STEP;
        if (r.topStub) {
            vline(context, colX, y, half);
        }
        if (r.bottomStub) {
            vline(context, colX, y + half, rowH - half);
        }
        // Horizontal twig: from this column's vertical line rightward to the row name, at the
        // row's vertical center
        context.fill(colX, y + half - 1, colX + QueryDataTree.STUB_W, y + half + 1,
                QueryDataTree.COLOR_TREE);
    }

    /** One vertical segment (width {@link QueryDataTree#TREE_W}, height {@code h}). */
    private static void vline(DrawContext context, int x, int y, int h) {
        context.fill(x, y, x + QueryDataTree.TREE_W, y + h, QueryDataTree.COLOR_TREE);
    }

    /**
     * Draws one merged big row (or an ordinary text row).
     *
     * <p>Merged big row: the row name sits left (vertically centered between
     * the two lines), and the data body starts at
     * {@link QueryDataTree.Row#valueOffset}, <b>hugging the name column</b>
     * rather than the panel's right edge - right-edge alignment would leave a
     * large blank between the name and the data.
     * The start is computed by the flatten stage from the widest grid row
     * name; all "cumulative data" rows share the same number, so the boundary
     * is one straight vertical line.
     *
     * <p>Other rows (map detail / time stats / enemy kills / account
     * overview): the name follows the indent, the value is right-aligned,
     * and colored per segment when it carries spans.
     */
    private void drawTreeRow(DrawContext context, QueryDataTree.Row r, int panelX,
                             int panelRight, int y, int[] summaryW, QueryDataTree.TierLayout tier) {
        // The name origin shares geometry with the tree lines: QueryDataTree.leadX
        int x = panelX + QueryDataTree.leadX(r.depth);

        if (r.isGrid()) {
            int valueX = panelX + r.valueOffset;
            // With two lines, the name centers vertically between them, aligned with the data
            // block on the right
            int leadY = y + (r.lines - 1) * this.textRenderer.fontHeight / 2;
            // The name is left-aligned (width passed as 0): name lengths vary by language (all
            // four characters in Chinese, but Total Rounds / Total Knockdowns differ in width),
            // and centering would look ragged in English
            drawLead(context, r, x, 0, leadY);
            drawCells(context, r, valueX, panelRight, y, summaryW);
            return;
        }
        drawLead(context, r, x, 0, y);
        if (r.right != null) {
            if (r.rightSpans != null && tier != null
                    && drawTierCells(context, r.rightSpans, tier, panelX, panelRight, y)) {
                return;
            }
            drawRight(context, r.right, r.rightSpans, panelRight, y);
        }
    }

    /**
     * Draws a map detail's tiers (Normal / Hard / RIP / Total) as aligned columns: each tier has its own
     * column, left-aligned, with widths shared by every map-detail row ({@link QueryDataTree#tierLayout}),
     * so all rows of all maps line up. A map lacking a tier (Alien Arcadium has no Hard / RIP) leaves that
     * column blank, which keeps Total in the same column everywhere. The block hugs the panel's right edge.
     *
     * <p>When the panel is too narrow, the gaps shrink to {@link QueryDataTree#SUMMARY_MIN_GAP}; if even
     * that does not fit between the row names and the right edge, nothing is drawn here and the caller
     * falls back to the old single right-aligned line.
     *
     * @return {@code true} when the row was drawn
     */
    private boolean drawTierCells(DrawContext context, List<QueryDataOverview.Span> spans,
                                  QueryDataTree.TierLayout tier, int panelX, int panelRight, int y) {
        List<List<QueryDataOverview.Span>> cells = QueryDataTree.tierCells(spans);
        if (cells.isEmpty()) {
            return false;
        }
        int[] colW = tier.colW();
        int sumW = 0;
        for (int w : colW) {
            sumW += w;
        }
        int gaps = colW.length - 1;
        int gap = QueryDataTree.CELL_GAP;
        int avail = panelRight - (panelX + tier.minStart());
        if (sumW + gaps * gap > avail) {
            gap = gaps == 0 ? 0 : (avail - sumW) / gaps;
            if (gap < QueryDataTree.SUMMARY_MIN_GAP) {
                return false;
            }
        }
        int[] colX = new int[colW.length];
        int cx = panelRight - (sumW + gaps * gap);
        for (int c = 0; c < colW.length; c++) {
            colX[c] = cx;
            cx += colW[c] + gap;
        }
        int[] slots = QueryDataTree.tierSlots(cells);
        int last = cells.size() - 1;
        if (QueryDataTree.isCompactTierRow(cells, slots)) {
            // A map with fewer tiers (Alien Arcadium: Normal + Total only): Total keeps its column so
            // it still lines up with the other maps, and the remaining tiers pack up against it
            // instead of leaving a blank gap in the middle. Each packed tier is a left-aligned column
            // of the widest compact-row width, so those cells line up too.
            int nextX = colX[slots[last]];
            drawSpans(context, cells.get(last), nextX, y);
            for (int i = last - 1; i >= 0; i--) {
                nextX -= gap + tier.packW()[i];
                drawSpans(context, cells.get(i), nextX, y);
            }
            return true;
        }
        for (int i = 0; i < cells.size(); i++) {
            drawSpans(context, cells.get(i), colX[slots[i]], y);
        }
        return true;
    }

    /**
     * Draws a row name.
     *
     * <p>{@code width <= 0} draws straight from {@code x} (left-aligned;
     * ordinary rows like the map detail and the "cumulative data" merged big
     * rows both take this path - the latter's names differ in width across
     * languages and cannot be centered); {@code width > 0} would
     * <b>horizontally center</b> within {@code [x, x + width)} - currently no
     * caller uses it.
     *
     * <p>When the title carries difficulty text (the time stats'
     * "map-difficulty") it still takes the difficulty color: the prefix and
     * the difficulty segment each get their own offset, reading as one line
     * together.
     */
    private void drawLead(DrawContext context, QueryDataTree.Row r, int x, int width, int y) {
        boolean split = r.leadDiffText != null && !r.leadDiffText.isEmpty() && r.lead.endsWith(r.leadDiffText);
        String prefix = split ? r.lead.substring(0, r.lead.length() - r.leadDiffText.length()) : r.lead;
        String tail = split ? r.leadDiffText : "";
        int leadW = this.textRenderer.getWidth(prefix)
                + (tail.isEmpty() ? 0 : this.textRenderer.getWidth(tail));
        int cx = width > 0 ? x + Math.max(0, (width - leadW) / 2) : x;
        if (!prefix.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, prefix, cx, y, r.color);
            cx += this.textRenderer.getWidth(prefix);
        }
        if (!tail.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, tail, cx, y,
                    QueryDataOverview.difficultyColor(r.leadDiffKey));
        }
    }

    /**
     * Draws the data body's cells: <b>column-aligned, spreadsheet style</b>.
     *
     * <p>The whole block hugs the panel's right edge ({@code valueX} is only
     * the left bound, keeping the block off the row name); inside the block
     * each column stays left-aligned.
     *
     * <p>Cell i sits at row {@code i / cols}, column {@code i % cols}; the
     * column width is the max width of that column across rows
     * ({@link QueryDataTree#gridColumnWidths}), so with 5 cells laid out 3+2,
     * 监狱风云 lands directly under 穷途末路 and 综合 under 坏血之宫 - matching
     * the requirement's D/E/F columns. Each cell is left-aligned within its
     * column.
     *
     * <p>Wrapping is decided by {@link QueryDataTree#gridLineCount} at the
     * flatten stage; drawing only reads {@link QueryDataTree.Row#lines}, so
     * "said it fits but it overflows" cannot happen.
     */
    private void drawCells(DrawContext context, QueryDataTree.Row r, int valueX, int panelRight, int y,
                           int[] summaryW) {
        if (r.cells.isEmpty() || panelRight <= valueX) {
            return;
        }
        if (QueryDataTree.isSummaryLayout(r.cells, r.lines)) {
            drawSummaryCells(context, r, valueX, panelRight, y, summaryW);
            return;
        }
        int[] colW = QueryDataTree.gridColumnWidths(r.cells, this.textRenderer, r.lines);
        int cols = colW.length;
        int[] colX = new int[cols];
        // Block hugs the panel's right edge: block width = sum of column widths + gaps;
        // clamped back to valueX so a very narrow panel does not push it onto the name
        int blockW = (cols - 1) * QueryDataTree.CELL_GAP;
        for (int w : colW) {
            blockW += w;
        }
        int cx = Math.max(valueX, panelRight - blockW);
        for (int c = 0; c < cols; c++) {
            colX[c] = cx;
            cx += colW[c] + QueryDataTree.CELL_GAP;
        }
        for (int i = 0; i < r.cells.size(); i++) {
            drawSpans(context, r.cells.get(i), colX[i % cols],
                    y + (i / cols) * this.textRenderer.fontHeight);
        }
    }

    /**
     * Draws the cumulative-data layout: <b>the four maps as a 2x2 block, Total
     * merged on its right and centered</b>.
     *
     * <pre>
     *  Dead End: XX     Bad Blood: XX
     *                                      Total: XX
     *  Alien Arcadium: XX   Prison: XX
     * </pre>
     *
     * <p>Geometry: three columns (map col 0, map col 1, Total col) whose widths
     * come from {@link QueryDataTree#summaryColumnWidths} - shared by all rows,
     * so the block is one fixed rectangle and every row lines up. The block
     * hugs the panel's right edge and never starts left of {@code valueX}. The
     * font and the two-line height are unchanged. When the panel is too narrow
     * for the normal gaps, the gaps shrink (down to
     * {@link QueryDataTree#SUMMARY_MIN_GAP}) before anything is pushed out of
     * the data area. Total is left-aligned in its own column like the map columns,
     * and vertically centered across both lines.
     */
    private void drawSummaryCells(DrawContext context, QueryDataTree.Row r, int valueX, int panelRight,
                                  int y, int[] colW) {
        int fh = this.textRenderer.fontHeight;
        int sumW = 0;
        for (int w : colW) {
            sumW += w;
        }
        int gaps = QueryDataTree.SUMMARY_COLS - 1;
        int gap = QueryDataTree.CELL_GAP;
        int avail = panelRight - valueX;
        if (sumW + gaps * gap > avail) {
            // Too narrow: tighten the gaps first, never below the minimum
            gap = Math.max(QueryDataTree.SUMMARY_MIN_GAP, (avail - sumW) / gaps);
        }
        int blockW = sumW + gaps * gap;
        int cx = Math.max(valueX, panelRight - blockW);
        int[] colX = new int[QueryDataTree.SUMMARY_COLS];
        for (int c = 0; c < colX.length; c++) {
            colX[c] = cx;
            cx += colW[c] + gap;
        }
        int maps = r.cells.size() - 1;
        for (int i = 0; i < maps; i++) {
            drawSpans(context, r.cells.get(i), colX[i % 2], y + (i / 2) * fh);
        }
        // Total: left-aligned in its column (same as the map columns), vertically centered
        // across the row's two lines
        List<QueryDataOverview.Span> total = r.cells.get(maps);
        int ty = y + (r.lines - 1) * fh / 2;
        drawSpans(context, total, colX[2], ty);
    }

    /** Draws one colored-segment text run from the given left edge (difficulty segments take their tier color). */
    private void drawSpans(DrawContext context, List<QueryDataOverview.Span> spans, int x, int y) {
        int cx = x;
        for (QueryDataOverview.Span sp : spans) {
            if (sp.text().isEmpty()) {
                continue;
            }
            int color = sp.difficulty() == null
                    ? sp.color()
                    : QueryDataOverview.difficultyColor(sp.difficulty());
            context.drawTextWithShadow(this.textRenderer, sp.text(), cx, y, color);
            cx += this.textRenderer.getWidth(sp.text());
        }
    }

    /**
     * The rows to draw this frame - both tree building and flattening happen
     * only here.
     *
     * <p>The same stats builds the tree once ({@link #treeSource}); the
     * expansion state is taken by the input field's name, and collapsing just
     * swaps a {@code Set} - no tree rebuild, no re-request.
     */
    private List<QueryDataTree.Row> treeRows(ZombiesStats s, int avail) {
        if (s != treeSource) {
            treeSource = s;
            treeRoots = QueryDataTree.build(s);
        }
        return QueryDataTree.flatten(this.textRenderer, treeRoots, expandedFor(freeTreeKey()), avail);
    }

    /** The free query's expansion-state key: the input field's name (lower case). */
    private String freeTreeKey() {
        String t = this.inputField == null ? "" : this.inputField.getText();
        return t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
    }

    /** Fetches the expansion set for the key; a first-seen key gets an empty set (all collapsed). */
    private Set<String> expandedFor(String key) {
        return expandedNodes.computeIfAbsent(key, k -> new HashSet<>());
    }

    /** Toggles a node's expansion state (applied on click, effective at the next flatten). */
    private void toggleNode(QueryDataTree.Node node, String key) {
        Set<String> open = expandedFor(key);
        if (!open.remove(node.path())) {
            open.add(node.path());
        }
    }

    /**
     * Click hit test for the tree panel: the whole row is the hot zone
     * (including the whitespace the indent creates).
     * Row order/height/scroll offset come from exactly the same source as
     * {@link #drawTreePanel}.
     *
     * @return {@code true} when a node was hit and toggled
     */
    private boolean treeClicked(double mouseX, double mouseY, ZombiesStats s,
                                int panelX, int panelY, int panelRight) {
        int panelBottom = this.height - PANEL_MARGIN;
        if (mouseX < panelX || mouseX >= panelRight || mouseY < panelY || mouseY >= panelBottom) {
            return false;
        }
        int fh = this.textRenderer.fontHeight;
        List<QueryDataTree.Row> rows = treeRows(s, panelRight - panelX);
        int y = panelY - scrollOffset;
        for (QueryDataTree.Row r : rows) {
            int rowH = treeRowGap(r, fh);
            if (mouseY >= y && mouseY < y + rowH) {
                if (!r.node.collapsible()) {
                    return false; // clicking a leaf row does nothing
                }
                toggleNode(r.node, freeTreeKey());
                return true;
            }
            y += rowH;
        }
        return false;
    }

    // ──Status / error / helpers─────────────────────────────────────────────

    /** Draws one status text line (loading / no data etc.), with shadow. */
    private void drawStatus(DrawContext context, int x, int y, String text, int color) {
        context.drawTextWithShadow(this.textRenderer, text, x, y, color);
    }

    /** Draws the error line ({@code nomorezombies.query.*} key translation + optional arg);
     *  over-wide messages wrap automatically so long errors are not truncated. */
    private void drawError(DrawContext context, int x, int y, int maxWidth, String key, String arg) {
        String msg = (arg != null) ? tr(key, arg) : tr(key);
        if (this.textRenderer.getWidth(msg) <= maxWidth) {
            context.drawTextWithShadow(this.textRenderer, msg, x, y, 0xFF5555);
            return;
        }
        int yy = y;
        for (String line : QueryDataOverview.wrapText(this.textRenderer, msg, maxWidth)) {
            context.drawTextWithShadow(this.textRenderer, line, x, yy, 0xFF5555);
            yy += this.textRenderer.fontHeight + 2;
        }
    }

    /** Resolves a translation key's text (the nomorezombies.query.* namespace). */
    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    /** Resolves a translation with arguments (HTTP status code / cause placeholder fills). */
    private static String tr(String key, Object... args) {
        return Text.translatable(key, args).getString();
    }

    // ──Mouse / keyboard interaction─────────────────────────────────────────

    /** Mouse press: widgets first; in free mode, clicking a tree container row collapses/expands it;
     *  in in-game mode, clicking the left player list selects that player, and clicking a failed entry retries. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (freeMode) {
            // Free query: only tree container rows are clickable; nothing else responds
            ApiResult r = this.freeResult;
            if (r == null || !r.ok() || r.stats() == null) {
                return false;
            }
            return treeClicked(mouseX, mouseY, r.stats(), freePanelX(), freePanelY(), panelRightEdge());
        }
        // listY must share the same source as renderInGameMode - the LIST_Y constant;
        // each side writing its own number is how they misalign on the first edit
        QueryDataManager manager = QueryDataManager.get();
        if (manager == null) {
            return false;
        }
        List<String> names = manager.currentInGameNames();
        int listW = inGameListWidth();
        if (mouseX >= LIST_X && mouseX < LIST_X + listW) {
            int idx = (int) ((mouseY - LIST_Y) / ENTRY_H);
            if (idx >= 0 && idx < names.size()) {
                this.selectedIndex = idx;
                this.scrollOffset = 0;
                String name = names.get(idx);
                if (manager.getError(name) != null) {
                    // Clicking a failed entry = retry: onGameStart re-fires the request,
                    // and since the error is not cached, it will not be skipped
                    manager.onGameStart(List.of(name));
                }
                return true;
            }
        }
        return false;
    }

    /** Wheel paging: scroll offset decreases up / increases down (10px per step), clamped at 0 -
     *  it never overshoots nor scrolls past the panel. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        this.scrollOffset -= (int) (verticalAmount * 10);
        if (this.scrollOffset < 0) {
            this.scrollOffset = 0;
        }
        return true;
    }

    /** Key press: widgets first; in free mode, Enter (numpad Enter included) fires the query directly. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ENTER
                || keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_KP_ENTER) {
            if (freeMode) {
                doFreeQuery();
                return true;
            }
        }
        return false;
    }
}