package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.hud.HUDEditor;
import cn.gsfy.nmz.client.features.rolls.RollStats;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Roll-stats HUD (movable / zoomable)—a three-row header plus one row per
 * item, pinning "what I rolled out of the lucky chest this game" as a table
 * on screen.
 *
 * <p>Structure: three header rows (total rolls / a summary subtitle /
 * item·count·roll), then one data row per item: "icon + item name + count +
 * the roll indices it came on". Names go through
 * {@link RollStats.Item#displayName()}, following the client language; the
 * icon is a stand-in vanilla item (a {@code minecraft:*} item)—the mod
 * cannot see the real weapon model, and the mapping is in
 * {@link #itemIcon(RollStats.Item)}.
 *
 * <p>Data is read from {@link RollStats}; this class only lays out.
 * <b>An empty count still renders</b>: the header and "total rolls 0" are
 * drawn, the data rows are just empty. This HUD's three gates (master
 * switch / placed / this element's visibility) all start true, so it is on
 * screen by default; before the first chest is opened it should read "no
 * rolls yet this game," not disappear entirely.
 *
 * <p>Row order is the enum's catalog order, not the count order, so rows
 * don't jump up and down with each roll.
 */
public class RollStatsRenderer extends TotalHUDRenderer {

    /** Row height (px): item icons are 12px while text rows are 9px; use
     *  the icon height for row spacing uniformly. */
    private static final int ROW_H = 12;
    /** Item icon edge (px): shrunk level with the text row; the native 16px
     *  would make data rows a head taller than the header. */
    private static final int ICON_SIZE = 12;
    /** Gap between icon and item name (px). */
    private static final int ICON_GAP = 3;
    /** Column gap (px): only between columns, never after the last one
     *  (same accounting as the team stats). */
    private static final int PAD = 8;
    /** Header row count. */
    private static final int HEADER_ROWS = 3;

    /** Colors: gold labels / white values / yellow header (same source as the team stats header). */
    private static final int COLOR_LABEL = 0xFFAA00;
    private static final int COLOR_VALUE = 0xFFFFFF;
    private static final int COLOR_HEADER = 0xFFFF55;
    private static final int COLOR_TEXT = 0xFFFFFF;

    /** Icon stand-in cache: ItemStack depends on the bound item component, so
     *  creating them statically during client entrypoint init steps on that.
     *  Built on first use. */
    private static final ItemStack[] ICONS = new ItemStack[RollStats.Item.values().length];

    // ----Fixed sample data: the sole source for the editor and default
    // position resolution; never reads in-game live state.----

    /** The sample rows' items and counts (the roll-index string is assembled by {@link #rollsText}). */
    private static final RollStats.Item[] SAMPLE_ITEMS = {
            RollStats.Item.ZOMBIE_ZAPPER, RollStats.Item.GOLD_DIGGER
    };
    private static final int[] SAMPLE_QTY = {2, 1};
    private static final int[][] SAMPLE_ROLLS = {{1, 3}, {2}};

    /**
     * One row in the table.
     *
     * @param item the item
     * @param qty rolls of it this game
     * @param rollsText the roll indices it came on (e.g. {@code 1,3}: the
     *   1st and 3rd rolls this game)
     */
    public record Row(RollStats.Item item, int qty, String rollsText) {
    }

    /**
     * One frame's full layout: the three header rows' wording, the data
     * rows, and each column's width.
     *
     * <p>Extracted so that <b>measuring and drawing share one copy</b>:
     * anchor positioning needs "how much this frame actually occupies"
     * while drawing needs the same column widths; computing them twice
     * inevitably diverges by 1px or a whole column on edge cases like
     * "two or three digit counts" or "long item names" (the same reason the
     * team stats has a TableLayout).
     *
     * @param totalLabel the total-count row's label
     * @param totalValue the total-count row's value
     * @param title the subtitle
     * @param headers the three header texts (item / qty / rolls)
     * @param rows the data rows
     * @param nameCol the item-name column's pixel width (header vs data take the larger)
     * @param qtyCol the qty column's pixel width
     * @param rollsCol the roll-index column's pixel width
     */
    public record Layout(String totalLabel, String totalValue, String title, String[] headers,
                         List<Row> rows, int nameCol, int qtyCol, int rollsCol) {
    }

    /**
     * Measures a layout from a data row set—width, height, and drawing all
     * read its return value, so the three no longer compute separately.
     *
     * @param tr the text renderer
     * @param total total roll count (shown on the first row)
     * @param rows data rows
     * @return the layout
     */
    public static Layout buildLayout(TextRenderer tr, int total, List<Row> rows) {
        String[] headers = {
                Text.translatable("nomorezombies.rollstats.header.item").getString(),
                Text.translatable("nomorezombies.rollstats.header.qty").getString(),
                Text.translatable("nomorezombies.rollstats.header.rounds").getString()
        };
        int nameCol = tr.getWidth(headers[0]);
        int qtyCol = tr.getWidth(headers[1]);
        int rollsCol = tr.getWidth(headers[2]);
        for (Row row : rows) {
            nameCol = Math.max(nameCol, tr.getWidth(row.item().displayName()));
            qtyCol = Math.max(qtyCol, tr.getWidth(String.valueOf(row.qty())));
            rollsCol = Math.max(rollsCol, tr.getWidth(row.rollsText()));
        }
        return new Layout(
                Text.translatable("nomorezombies.rollstats.total").getString(),
                String.valueOf(total),
                Text.translatable("nomorezombies.rollstats.title").getString(),
                headers, rows, nameCol, qtyCol, rollsCol);
    }

    /** The fixed sample's layout: two data rows (Zapper x2 / Gold Digger x1, total 3); content and spec match the sample exactly. */
    public static Layout sampleLayout(TextRenderer tr) {
        List<Row> rows = new ArrayList<>(SAMPLE_ITEMS.length);
        int total = 0;
        for (int i = 0; i < SAMPLE_ITEMS.length; i++) {
            rows.add(new Row(SAMPLE_ITEMS[i], SAMPLE_QTY[i], rollsText(SAMPLE_ROLLS[i])));
            total += SAMPLE_QTY[i];
        }
        return buildLayout(tr, total, rows);
    }

    /** This game's live data rows (enum order, only entries with count > 0). */
    public static List<Row> liveRows() {
        List<Row> rows = new ArrayList<>();
        for (RollStats.Entry entry : RollStats.entries()) {
            rows.add(new Row(entry.item(), entry.count(), rollsText(entry.rolls())));
        }
        return rows;
    }

    /**
     * The roll-index string: ascending, deduplicated indices, comma-joined
     * (e.g. {@code 1,3} = the 1st and 3rd rolls this game).
     *
     * <p>A repeated item lists every index, not compressed—this column
     * answers "on which rolls did I get it", and compressing to {@code 1-3}
     * would include rolls where it was not actually rolled.
     *
     * @param rolls ascending, deduplicated roll indices
     * @return the index string; empty for an empty list
     */
    public static String rollsText(List<Integer> rolls) {
        if (rolls == null || rolls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int roll : rolls) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(roll);
        }
        return sb.toString();
    }

    /** Array version: the fixed sample writes literals directly, saving one boxing pass. */
    private static String rollsText(int[] rolls) {
        List<Integer> boxed = new ArrayList<>(rolls.length);
        for (int roll : rolls) {
            boxed.add(roll);
        }
        return rollsText(boxed);
    }

    /** The item-name column's offset from the component's left edge (px): makes room for the icon. */
    private static int nameOffset() {
        return ICON_SIZE + ICON_GAP;
    }

    /** The layout's pixel width (first row / subtitle / table, whichever is widest, shadow overhang included). */
    public static int hudWidth(TextRenderer tr, Layout layout) {
        int totalRow = tr.getWidth(layout.totalLabel()) + tr.getWidth(" ") + tr.getWidth(layout.totalValue());
        int table = nameOffset() + layout.nameCol() + PAD + layout.qtyCol() + PAD + layout.rollsCol();
        return Math.max(Math.max(totalRow, tr.getWidth(layout.title())), table) + TEXT_SHADOW;
    }

    /**
     * Component height (three header rows + content rows, shadow overhang
     * included).
     *
     * <p>{@code tr} pairs with
     * {@link #hudWidth(TextRenderer, Layout)}: width is measured by the font
     * while height is linear in {@code fontHeight} alone, so this method
     * doesn't use it—the matching signature is so the two call sites (anchor
     * resolution and editor) don't have to remember two parameter sets.
     *
     * @param tr the text renderer (unused here)
     * @param layout this frame's layout
     * @return component pixel height
     */
    public static int hudHeight(TextRenderer tr, Layout layout) {
        return (HEADER_ROWS + layout.rows().size()) * ROW_H + TEXT_SHADOW;
    }

    /** The editor sample width: the same accounting as {@link #sampleLayout} (default position resolution goes through this too). */
    public static int previewWidth(TextRenderer tr) {
        return hudWidth(tr, sampleLayout(tr));
    }

    /** The editor sample height: same as {@link #previewWidth}. */
    public static int previewHeight(TextRenderer tr) {
        return hudHeight(tr, sampleLayout(tr));
    }

    /**
     * Draws the whole table: three header rows (total rolls / subtitle /
     * item·count·roll) plus the data rows.
     *
     * <p>The anchor is the draw origin—{@link #drawScaled} scales around the
     * anchor, and the sample factory's convention is "draw from the given
     * coordinates"; taking the same point for both is what makes the content
     * land exactly inside the frame.
     *
     * @param context draw context
     * @param tr the text renderer
     * @param x component left edge
     * @param y component top edge
     * @param layout this frame's layout
     */
    public static void draw(DrawContext context, TextRenderer tr, int x, int y, Layout layout) {
        int textPad = (ROW_H - tr.fontHeight) / 2;

        // Row 0: total rolls—gold label + white value
        context.drawTextWithShadow(tr, layout.totalLabel(), x, y + textPad, COLOR_LABEL);
        context.drawTextWithShadow(tr, layout.totalValue(),
                x + tr.getWidth(layout.totalLabel()) + tr.getWidth(" "), y + textPad, COLOR_VALUE);

        // Row 1: subtitle
        context.drawTextWithShadow(tr, layout.title(), x, y + ROW_H + textPad, COLOR_LABEL);

        // Row 2: the three column headers; the item header is drawn at the
        // component's left edge, covering the icon column and the name column.
        int headerY = y + ROW_H * 2 + textPad;
        int nameX = x + nameOffset();
        int qtyX = nameX + layout.nameCol() + PAD;
        int rollsX = qtyX + layout.qtyCol() + PAD;
        context.drawTextWithShadow(tr, layout.headers()[0], x, headerY, COLOR_HEADER);
        context.drawTextWithShadow(tr, layout.headers()[1], qtyX, headerY, COLOR_HEADER);
        context.drawTextWithShadow(tr, layout.headers()[2], rollsX, headerY, COLOR_HEADER);

        // Row 3 onward: one "icon + item name + count + roll indices" per row.
        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            int rowY = y + ROW_H * (HEADER_ROWS + i);
            drawIcon(context, icon(row.item()), x, rowY);
            context.drawTextWithShadow(tr, row.item().displayName(), nameX, rowY + textPad, COLOR_TEXT);
            context.drawTextWithShadow(tr, String.valueOf(row.qty()), qtyX, rowY + textPad, COLOR_TEXT);
            context.drawTextWithShadow(tr, row.rollsText(), rollsX, rowY + textPad, COLOR_TEXT);
        }
    }

    /**
     * Draws one {@value #ICON_SIZE}px item icon.
     *
     * <p>{@link DrawContext#drawItem} always draws at 16px and carries its
     * own {@code translate(x+8,y+8);scale(16,-16,16)}; it takes the
     * <b>same</b> matrix stack on the DrawContext, so sandwiching our own
     * scale on the same anchor before it pushes makes the 16px image land at
     * {@value #ICON_SIZE}px with its top-left exactly at (ix,iy).
     *
     * <p>Use {@code drawItemWithoutEntity}: it does not consume the entity
     * holding-state model, so the preview draws even at the main menu (no
     * player / no world).
     *
     * @param context draw context
     * @param stack the item to draw
     * @param ix icon left edge
     * @param iy icon top edge
     */
    private static void drawIcon(DrawContext context, ItemStack stack, int ix, int iy) {
        float k = ICON_SIZE / 16.0f;
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        float cx = ix + ICON_SIZE / 2.0f;
        float cy = iy + ICON_SIZE / 2.0f;
        matrices.translate(cx, cy, 0.0f);
        matrices.scale(k, k, 1.0f);
        matrices.translate(-cx, -cy, 0.0f);
        // The 16px origin pulls in by (ICON_SIZE-16)/2; after scaling, its
        // center lands on the icon's center.
        context.drawItemWithoutEntity(stack, ix + (ICON_SIZE - 16) / 2, iy + (ICON_SIZE - 16) / 2);
        matrices.pop();
    }

    /** Icon lazy-load wrapper (see {@link #ICONS}). */
    private static ItemStack icon(RollStats.Item item) {
        int index = item.ordinal();
        ItemStack cached = ICONS[index];
        if (cached == null) {
            cached = new ItemStack(itemIcon(item));
            ICONS[index] = cached;
        }
        return cached;
    }

    /**
     * Item name → icon stand-in (a vanilla item).
     *
     * <p>The mod cannot see the real weapon model, so this table is pure
     * convention: one visually matching vanilla item per weapon; the two
     * skills use {@code blaze_rod} (lightning rod) and {@code golden_apple}
     * (heal). It must line up entry by entry with {@link RollStats.Item}
     * and the bilingual mapping table—one missing entry or one swapped item
     * and that row's icon goes silently wrong.
     *
     * @param item the item
     * @return the item's icon
     */
    static Item itemIcon(RollStats.Item item) {
        return switch (item) {
            case ZOMBIE_ZAPPER -> Items.DIAMOND_PICKAXE;
            case GOLD_DIGGER -> Items.GOLDEN_PICKAXE;
            case RAINBOW_RIFLE -> Items.GOLDEN_SHOVEL;
            case DOUBLE_BARREL -> Items.FLINT_AND_STEEL;
            case ELDER_GUN -> Items.SHEARS;
            case ZOMBIE_SOAKER -> Items.DIAMOND_HOE;
            case BLOW_DART -> Items.IRON_SHOVEL;
            case FLAMETHROWER -> Items.GOLDEN_HOE;
            case THE_PUNCHER -> Items.DIAMOND_AXE;
            case LIGHTNING_ROD_SKILL -> Items.BLAZE_ROD;
            case HEAL_SKILL -> Items.GOLDEN_APPLE;
        };
    }

    /**
     * Draws the whole block: the empty state also draws (header + "total
     * rolls 0", data rows empty). Gate =
     * {@link GlobalConfig.Hud#rollStatsOn()} (master switch + placed + this
     * element's visibility).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.rollStatsOn()) {
            return;
        }
        List<Row> rows = liveRows();
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_ROLL_STATS.getDoubleValue();
        // Content size measured from this frame's row list: as the roll-index
        // string grows or items accumulate, right-hug never gets pushed off
        // the screen.
        Layout layout = buildLayout(textRenderer, RollStats.totalRolls(), rows);
        int hudW = visibleSize(hudWidth(textRenderer, layout), scale);
        int hudH = visibleSize(hudHeight(textRenderer, layout), scale);
        int absoluteX = anchorPixels(GlobalConfig.getXRollStats(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYRollStats(), screenHeight, hudH, 0);

        drawScaled(context, absoluteX, absoluteY, scale,
                () -> draw(context, textRenderer, absoluteX, absoluteY, layout));
    }
}