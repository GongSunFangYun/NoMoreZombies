package cn.gsfy.nmz.client.config.hud;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.HoveredTooltipPositioner;
import net.minecraft.client.gui.tooltip.TooltipBackgroundRenderer;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import cn.gsfy.nmz.client.config.GlobalConfig;

/**
 * HUD editor (extends Screen) - a three-zone workbench: the top bar holds only
 * the title plus the info/warning icon pinned to the top-right corner; the left
 * column is the <b>library</b>; the right side is the <b>preview canvas</b>,
 * laid out at screen ratio and horizontally centered inside the right zone;
 * directly below the canvas sits the <b>two-row action area</b> (row 0 holds
 * the HUD widgets and the scale slider, the last row holds reset/save/cancel).
 * The class only knows how to drag, scale, toggle visibility and save; it knows
 * no concrete HUD: everything comes from {@link RegisterHUD} (descriptions in
 * {@link HudEntry}, samples in {@link HudSampleData}). Whether an element on
 * the canvas is actually pulling its weight is exposed to the sample factories
 * via {@link #workspaceActive(String)}, so the scoreboard sample redraws the
 * moment the player places, removes, enables or disables it. A HUD with its own
 * toggle hangs it into the action area via {@link HudEntry#extraConfigWidgets}
 * without touching this class's layout code. See {@link #layout()} for layout
 * and geometry, {@link #actionRow(int)} for row anchors.
 *
 * <p>Editing only touches the workspace snapshot; only "Save" persists:
 * position, scale, in-game visibility and whether the element sits on the
 * canvas all go into the five fields of {@link HudElement} first. Esc and
 * "Cancel" warn once when changes are detected (see {@link #requestClose()});
 * a second press discards and exits.
 *
 * <p><b>Coordinate semantics = anchor ratios</b> ({@code 0 leading edge /
 * 0.5 center / 1 trailing edge}, see {@code TotalHUDRenderer#anchorPixels}):
 * {@code workX}/{@code workY} store the exact number from the config, with
 * <b>no screen conversion</b> - the editor maps the travel {@code [minBox,
 * maxBox]} onto the canvas, the game maps
 * {@code [reserve, screenWidth - contentWidth - reserve]} onto the screen, and
 * both ends compute live from the <b>current frame's content size</b>. The
 * same number therefore lands at the same relative spot in both places, and
 * keeps that spot across devices.
 *
 * <p>The canvas is just a proportional view of it: the {@link #toCanvasX}/
 * {@link #toVirtualX} inverse pair plus the "ratio and virtual pixel" pair
 * (see {@link #pxFrom}/{@link #ratioFrom}); each renderer's {@code hudWidth}/
 * {@code hudHeight} remains the only size source - the canvas view changed no
 * size semantics, only the display magnification.
 * Three boundaries: "on the canvas" and "drawn in game" are two states each
 * persisted on its own (combination table in {@link HudElement});
 * the canvas is a static backdrop that provides only the screen scale
 * (see {@link #BG_TEXTURE});
 * removal happens only by dragging back into the library with the element
 * itself crossing the boundary by 30% (see {@link #draggingArmedForRemoval});
 * below 30% it lands at the leading edge
 * (see {@link #snapBackToLeadingEdge}).
 *
 * <p><b>The dragged position is a free pixel value</b>
 * ({@link #draggingPx}/{@link #draggingPy}): anchor ratios live in
 * {@code 0~1}, so solving them can only land inside the canvas travel and
 * cannot express "element slides out of the canvas into the library following
 * the pointer". Dragging therefore writes the free value only; release
 * collapses it back into a ratio.
 *
 * <p><b>The dragged element follows the pointer, but all four edges have
 * collision bounds</b>: the horizontal floor is whichever is farther left of
 * the library's left edge and the reach the arm point needs (narrow elements
 * sit flush against the library's left edge; only wide ones yield more to
 * reach the drop point), the ceiling is that HUD's own in-game reserve;
 * <b>vertically it picks one of two travels by whether the element's left edge
 * is in the library column or on the canvas</b>: on the canvas it uses the
 * canvas travel (the canvas bottom edge is a wall at any x), in the library
 * column it uses the library travel (can drop to the library's bottom) - the
 * switch line is the fixed canvas left edge and does not move with element
 * width, so nothing pokes out of the bottom-right short side nor jerks at the
 * switch; the library top keeps the same 1px frame slot as the canvas, so
 * dragging across the top never steps.
 * It is drawn by {@link #renderDraggedElement(DrawContext)} above the chrome
 * layer, so dragging left is never first covered by the library panel and
 * popping up only at the arm point.
 */
public class HUDEditor extends Screen implements HudEntry.PreviewContext {

    /**
     * True while the editor is open - in-game HUD renderers skip their actual
     * drawing based on it, otherwise the live HUD would double with the
     * editor's preview and look like a ghost image.
     */
    public static boolean IS_OPEN = false;

    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 2.0;

    // ----Three-zone geometry (logical pixels)----

    /**
     * Top bar height: the title, plus the info/warning icon pinned to the
     * <b>top-right</b> corner.
     *
     * <p>Only those two live in the top bar.
     * Reset/save/cancel are <b>session-level</b> actions (they decide whether
     * this edit is kept or dropped), unrelated to "which HUD am I placing";
     * on 854×480 the three buttons plus the icon side by side eat half the
     * screen width and leave the title nowhere to stand, so they live in the
     * action area below the canvas.
     * The selected element's X/Y readout is likewise not in the top bar: the
     * yellow frame on the canvas already answers "which one is selected",
     * and X/Y only helps mid-drag, yet it would share one horizontal band with
     * title and icon (squeezed under the icon it would have to skip whole
     * stretches - flickering) - the action area therefore went from three rows
     * to two, and that row went back to the canvas.
     *
     * <p><b>Squeezed to 22 by geometry</b>:
     * the top bar height comes straight out of the canvas's <b>vertical</b>
     * budget, and after proportional scaling "can it touch both side edges"
     * depends on having enough vertical budget -
     * when {@code scaleByH >= scaleByW},
     * canvasW equals {@code rightW - FRAME_W} exactly, margin is 0, i.e. both
     * edges flush.
     * That criterion expands to
     * {@code screenW/screenH <= (libraryW + 1) / (topBarH + actionH + 1)};
     * with the default narrowest {@link #LIB_MIN_W} (140) library and the
     * {@link #ACTION_H} (56) action area, the right side is {@code 141/79≈1.78},
     * so 16:9 and squarer windows are always flush on both sides, and only
     * from 21:9 does height get a say (widening the library raises this cap,
     * putting more windows back into the flush case).
     * The geometry regression pins these 22px with a table of common
     * resolutions:
     * 22px is the tallest top bar that keeps
     * 854×480/958×567/1280×720/1600×900/1920×1080/2560×1440 - these common
     * logical resolutions -
     * <b>flush on both sides across the library's whole width range</b>;
     * from 23 they keep a 1px gap.
     * It is also near the minimum where the icon
     * (2×{@value #INFO_ICON_HALF}+1px border = 17px)
     * and the title text both fit -
     * any shorter means shrinking the icon, and the icon is the whole UI's
     * only help entry; not worth sacrificing for a few pixels.
     */
    private static final int TOP_BAR_H = 22;
    /** Usable library width range (changed by dragging the right edge, written
     *  back to {@code hudLibraryWidth}); the ceiling is further capped at 1/4
     *  of screen width (see {@link #layout()}) - the library is supporting
     *  cast and must not eat half the screen on wide windows. */
    private static final int LIB_MIN_W = 140;
    private static final int LIB_MAX_W = 260;
    /** Library list row height (vanilla list look). No group headers - just one column. */
    private static final int LIB_ITEM_H = 18;
    /** Library list inner padding. */
    private static final int LIB_PAD = 4;

    /**
     * Preferred action-area button width; row height and the width floor sit
     * alongside as constants (width is re-derived on narrow screens,
     * see {@link #layoutWidgets()}).
     *
     * <p><b>All five buttons share one width; the slider matches a single
     * button</b>:
     * row 0's "Show"/"Place" pair and row 1's "Reset"/"Save"/"Cancel" use the
     * same thirds formula (content box split in three, two {@link #BTN_GAP}
     * gaps each), and the scale slider takes the third slot -
     * both rows align at their left and right edges, three equal columns, the
     * whole action area a tidy 3×2 grid.
     */
    private static final int BTN_W = 89;
    private static final int BTN_GAP = 6;
    /** Button width floor (px) - any narrower clips text ("Show: On" and "Remove from canvas" must fit). */
    private static final int MIN_BTN_W = 59;
    /** Action-area row height: one widget per row, vertically centered within it (see {@link #actionRow(int)}). */
    private static final int ROW_H = 24;

    /**
     * Action-area row count: one row of HUD widgets, one row of session
     * buttons (a HUD's own extra config rows are extra).
     *
     * <p>The name and the Position X/Y readout take no row: the yellow frame
     * on the canvas identifies the selection, the readout only helps
     * mid-drag; that row ({@link #ROW_H}px) plus the top bar band were given
     * back to the player.
     */
    private static final int ACTION_ROWS = 2;
    /** Action-area padding (top and bottom; left/right only for the content box, see {@link #layout()}). */
    private static final int ACTION_PAD = 4;
    /**
     * Total action-area height - <b>the canvas deducts it before computing
     * its scale</b> (see {@link #layout()}).
     *
     * <p>This reservation is the pivot of the whole layout: the action area
     * lives below the canvas, in space the canvas would otherwise fill.
     * Without deducting first, on width-limited windows the canvas runs to
     * the screen's bottom edge and the action area has to overlap it, also
     * blocking placement.
     * Deducted, the action area always fits at any resolution, and the canvas
     * is just a bit shorter.
     *
     * <p>It is the <b>baseline</b> of layout()'s "deduct, then scale" step;
     * the actual reserved height is computed live as
     * {@code actionH = ROW_H * (ACTION_ROWS + extraRows) + ACTION_PAD * 2}
     * (taller when a HUD brings extra config rows), so this constant equals
     * actionH only when the registry has no extra rows.
     */
    private static final int ACTION_H = ACTION_ROWS * ROW_H + ACTION_PAD * 2;
    /**
     * Uniform widget height in the action area (vanilla button/slider size);
     * the vertical-centering gap within a row is {@code ROW_H - WIDGET_H}.
     */
    private static final int WIDGET_H = 20;
    /** Top-right info icon: half-size and right margin (px). */
    private static final int INFO_ICON_HALF = 8;
    private static final int INFO_ICON_MARGIN = 8;

    /** Grab tolerance (px) for dragging the library's right edge to resize it. */
    private static final int SPLIT_GRAB = 3;

    /**
     * Minimum pointer travel (px) from "press in the library" to "counts as a
     * drag".
     *
     * <p>Without this gate, clicking an <b>already placed</b> HUD in the list
     * would instantly drag it under the cursor (the element teleports to the
     * pointer's center): the player clicks to select and the layout changes.
     * With the threshold, a click only selects; real movement moves.
     */
    private static final double DRAG_THRESHOLD = 4.0;

    /**
     * Arm point for "drag back into the library to remove": the fraction by
     * which <b>the element itself</b> crosses the library/canvas boundary -
     * {@code 0.3} means 30% of the element is over the line, i.e. visibly
     * pressed into the library.
     *
     * <p>The fraction multiplies the <b>element width</b>
     * ({@code bw * REMOVE_ARM_ELEMENT_FRACTION}); the boundary itself is a
     * fixed line ({@code libX + libW}, the library's right edge; when the
     * canvas sits flush top-right it coincides with the canvas's left edge).
     * Multiplying the <b>library width</b> instead would mean "element past
     * 30% of the library width" - pushed deep into the library before it
     * counts, much deeper than the gesture implies.
     *
     * <p>The criterion measures the element, not the pointer, because the
     * element really does follow the pointer: during a drag its position
     * lives in {@code draggingPx} (virtual pixels), never in the {@code 0~1}
     * {@code workX} ratio, so moving the pointer left slides the whole
     * element out of the canvas and under the translucent library.
     *
     * <p>0.3 rather than "the moment it enters the library": a drag hugging
     * the left edge only brings the element's left edge near the canvas
     * travel's left end, where it barely crosses the boundary, so edge-hugging
     * itself never deletes; when it truly arms, 30% of the element already
     * sits under the library and the red-frame warning means something.
     */
    private static final double REMOVE_ARM_ELEMENT_FRACTION = 0.3;

    // ----Frame and hit testing (the §3.10 invariants)----

    /**
     * Frame line width (px) - the frame is drawn as a ring <b>outside</b> the
     * element bounds, its inner edge exactly on the bounds.
     *
     * <p>Outside, because the outermost ring of an element's content
     * rectangle is often text shadow ({@code drawTextWithShadow} draws
     * {@link TotalHUDRenderer#TEXT_SHADOW}px right and below the glyphs, and
     * that overhang is already counted into the sample's width/height).
     * Drawn inside, the frame would inevitably cover that 1px of solid
     * pixels - it would look like the frame cuts into the text.
     * Drawn outside, <b>frame inner edge = hitbox = element content
     * rectangle</b>, the frame only adds 1px outward and never covers content.
     *
     * <p>It is a <b>screen pixel</b>: content shrinks with the canvas, but
     * this hint ring stays a hairline - UI hints should not thicken or thin
     * with the content scale; at 0.7x a shrinking 1px frame could no longer
     * be clicked.
     */
    private static final int FRAME_W = 1;

    /**
     * Hit-test slack (px) - exists purely to make clicking easier: precisely
     * clicking a 1px line with a mouse is hard, so the click/drag area
     * extends this far outward from the visible frame.
     *
     * <p>It is <b>never drawn</b> and does not represent element size: the
     * frame sits on the bounds and positions use the bounds, so what you see
     * always matches the real size; only "how much you can grab" is one ring
     * wider.
     */
    private static final int HIT_SLACK = 1;

    /**
     * Frame colors for the HUD previews on the canvas - geometry untouched,
     * only the color changes with state
     * (see {@link #frameColorFor(HudElement)}).
     *
     * <p>Presence is shown by a frame ring, not a translucent fill: a fill
     * tints the text it covers and is blunter where it shows, while a frame
     * measures the bounds exactly. The scoreboard brings its own dark
     * backdrop; this ring is its "edge", and other HUDs get the same edge so
     * their relationships stay legible while arranging.
     *
     * <p>Four states: unselected gray-white, selected yellow, <b>disabled
     * red</b>, and drag "about to be removed" red.
     * Disabled red and remove red are <b>deliberately the same color</b> -
     * both say "this HUD will show nothing in game" (the first already in
     * effect, the second on release); the unselected shade is fainter so the
     * selected one stands out among the others on the canvas.
     */
    private static final int FRAME_COLOR_IDLE = 0x66FFFFFF;
    /** Selected: yellow frame (differs from the default gray-white in color only, geometry identical). */
    private static final int FRAME_COLOR_SELECTED = 0xCCFFFF55;
    /**
     * Disabled ({@code workVisible=false}): red - the element still draws on
     * the canvas as usual, it is just <b>not drawn in game</b>,
     * so the canvas must show at a glance "this one is currently off",
     * otherwise the player thinks the HUD they just configured is gone.
     */
    private static final int FRAME_COLOR_HIDDEN = 0xCCFF5555;
    /** Disabled and <b>unselected</b>: same hue one shade fainter, so the selection stays visible in a screen of disabled elements. */
    private static final int FRAME_COLOR_HIDDEN_IDLE = 0x99FF5555;
    /**
     * Frame color while dragging once the "remove from canvas" condition is
     * met: red - releasing now takes the element off the canvas; the color is
     * the only warning in this state (the library also draws a red border and
     * pops a hint line,
     * see {@link #renderLibrary(DrawContext, TextRenderer, int, int)}).
     */
    private static final int FRAME_COLOR_REMOVE = 0xCCFF5555;

    /**
     * Black layer (25% opacity) laid under each HUD sample on the canvas.
     *
     * <p>One purpose: make "HUD content" and "not-a-HUD canvas backdrop"
     * distinguishable at a glance.
     * The canvas is a fairly bright static image; a white-text HUD on it has
     * a mushy boundary. With a light black layer, the block instantly reads
     * as "a component".
     * Like the yellow/red frame it is a <b>pure editor visual layer</b>: not
     * persisted, affects no in-game pixels.
     *
     * <p>HUDs with their own backdrop (see {@link HudEntry#ownBackground},
     * currently only the scoreboard) skip it:
     * they already draw a background, and another layer would just gray
     * theirs out.
     */
    private static final int HUD_BACKDROP = 0x40000000;

    /**
     * Veil color (one full-screen layer under the backdrop image, dimming the
     * game picture one step so text stays readable).
     *
     * <p><b>This is the mod's only "screen veil" color</b>: the player data
     * query screen ({@code QueryDataScreen}) reads it too, and both veils
     * must match - two independent literals quietly drift apart after some
     * one-sided retint, and "these two screens don't look like the same mod"
     * is a bug nobody thinks to report.
     */
    public static final int VEIL_COLOR = 0x55000000;

    /** Tooltip line pitch (px) - vanilla {@code OrderedTextTooltipComponent}'s single-line height is exactly 10. */
    private static final int TOOLTIP_LINE_H = 10;
    /**
     * Height (px) left between the text lines and the HUD sample in a
     * tooltip - a full line.
     *
     * <p>The blank line must stay: with the sample hugging the last text
     * line, the two read as a sentence with an illustration - the player
     * reads the line first and takes it as the sample's caption. With a blank
     * line each part stands alone and "what does this HUD look like" is
     * legible on its own - which is the entire reason this tooltip exists.
     */
    private static final int TOOLTIP_PREVIEW_GAP = TOOLTIP_LINE_H;
    /** Size cap (px) for the sample in the library hover preview: scale down proportionally if over, draw 1:1 if not. */
    private static final int PREVIEW_MAX_W = 220;
    private static final int PREVIEW_MAX_H = 120;

    /**
     * Reserve area base color (amber, 20% opacity) - draws the edge a HUD
     * declared as off-limits for itself.
     *
     * <p><b>Why "declared means drawn"</b>: a reserve is a wall - a HUD with
     * {@code reserve} really cannot reach that edge on the canvas (in game
     * it is the same; both sides use the same {@code anchorPixels}). The
     * canvas is a static backdrop image and the wall carries no sign; undrawn,
     * the editor just looks broken.
     * So once a declared reserve is in play, it gets drawn.
     *
     * <p>Currently <b>only the scoreboard's {@code reserve(1, 0)} is
     * non-zero</b> (that one pixel makes it coincide pixel-for-pixel with the
     * vanilla right boundary {@code W-1} when flush right). The hotbar
     * occupies only the <b>bottom-center</b> of the screen, while a reserve
     * bars an entire edge - a HUD avoiding the hotbar could not even reach
     * the four corners if it declared a reserve;
     * whether it overlaps the hotbar is left to the player's eyes.
     */
    private static final int RESERVE_BAND = 0x33FFAA00;
    /** Reserve area inner-edge line (one step more solid than the base, marking exactly where the wall is). */
    private static final int RESERVE_LINE = 0x66FFAA00;
    /**
     * Orange of the tooltip's "map-restricted" note (Alien Arcadium only).
     *
     * <p>The same orange the reserve band uses: the note and the band are both
     * "something in this editor is holding you back", so they read as one
     * family. Opaque - it is text, not a tint over the world.
     */
    private static final int FONT_ORANGE = 0xFFFFAA00;

    /**
     * Base color of the UI "chrome" (top bar / library / right-side plate) -
     * both go through these constants so a dozen scattered literals cannot be
     * missed at the next palette change.
     *
     * <p><b>Deliberately translucent</b>: the game picture runs underneath
     * (the game does not pause while the editor is open); fully opaque would
     * smear the whole screen. Translucent keeps the world behind visible yet
     * dark enough for readable text.
     * Vanilla widgets (buttons/slider) are drawn by the framework and stay
     * opaque - they are click targets, their edges must show.
     *
     * <p>The action area below the canvas <b>is not one of these</b>: it has
     * no panel of its own, so no color for it here.
     */
    private static final int CHROME_BG = 0xC0101014;
    /** Separator line (top bar's bottom edge): a bit more solid than the base, translucent but clearly visible. */
    private static final int CHROME_LINE = 0xCC303038;
    /** 1px border color for library / top bar / canvas (unused by the action area, which has no panel). */
    private static final int CHROME_BORDER = 0xCC303038;

    /**
     * The <b>static backdrop image</b> shown in the editor's canvas area:
     * {@code assets/nomorezombies/textures/background/hud_editor_background.png}
     *
     * <p>The canvas takes a static image rather than the current game frame:
     * live sampling means either blitting the main framebuffer every frame
     * (GPU-state dependent) or reading back periodically (a CPU copy), and
     * both only have a picture in-world (the main menu shows nothing);
     * a static image has none of these costs and previews fine in the main
     * menu, at the price of <b>no longer arranging against a real scene</b> -
     * the canvas provides only the screen scale.
     */
    private static final Identifier BG_TEXTURE =
            Identifier.of(NoMoreZombies.MOD_ID, "textures/background/hud_editor_background.png");

    /**
     * The backdrop image's <b>true</b> pixel size (1920×1111, measured from
     * the PNG header).
     *
     * <p>{@code drawTexture} needs it <b>twice</b>:
     * once as the sampling region (take the whole source), once as the
     * texture size (the divisor that normalizes {@code u/v} into 0~1).
     * Skip either group, or swap one for the canvas size, and the sampling
     * window collapses to "a small patch at the source's top-left" -
     * the screen shows an enlarged corner instead of the whole image.
     * See {@link #drawCanvasBackground(DrawContext)}.
     */
    private static final int BG_TEX_W = 1920;
    private static final int BG_TEX_H = 1111;

    /**
     * Tint for the backdrop image (ARGB) - alpha 235/255≈0.92, so the image
     * does not sit solidly on the veil: a bit of the veil below shows
     * through, and canvas and surrounding chrome read as one screen rather
     * than a pasted-on picture.
     *
     * <p>Passed via {@code drawTexture}'s color argument (vertex color) and
     * not {@code RenderSystem.setShaderColor}:
     * vertex color applies to this one draw and resets naturally with the
     * batch, leaving no global tint for later buttons;
     * stacking both would dim the image twice.
     */
    private static final int CANVAS_BG_TINT = 0xEBFFFFFF;

    private final Screen parent;
    /** Work set: one item per registry HUD, order = registration order (also the library's list order). */
    private final List<HudElement> elements;

    private HudElement selected;
    /** Element currently being dragged on the canvas. */
    private HudElement dragging;

    /**
     * Free pixel position (top-left) of the dragged element in <b>virtual
     * screen</b> space.
     *
     * <p>Anchor ratios live in {@code 0~1}, and {@link #pxFrom} always
     * resolves them inside the canvas travel, so "element slides out of the
     * canvas into the library following the pointer" cannot be expressed as a
     * ratio. The position during a drag is therefore kept separately as
     * pixels; on release {@link #clampWorkIntoCanvas(HudElement)} or
     * {@link #snapBackToLeadingEdge(HudElement, double)} collapses it into a
     * ratio and places it.
     *
     * <p>Recomputed every frame by
     * {@link #mouseDragged(double, double, int, double, double)};
     * {@link #mouseClicked(double, double, int)} fills it once from the
     * ratio-solved element box before entering drag state, so the grab offset
     * never picks up the previous frame's free position.
     */
    private double draggingPx;
    /** Vertical component of the free position; same source as {@link #draggingPx}, except vertically the element never leaves the canvas travel. */
    private double draggingPy;

    /**
     * Virtual screen coordinates (top-left) of the element box at press time.
     *
     * <p>Serves one purpose: deciding at release whether the drag moved
     * anything - a pure click-select (press and lift; {@code mouseDragged}
     * never fires) must not count as a geometry edit, otherwise clicking a
     * HUD once and saving replaces the config value the player never dragged
     * with the workspace's resolved position.
     */
    private double dragStartPx;
    private double dragStartPy;

    /** Element being dragged out of the library (release on the canvas places it). */
    private HudElement draggingFromLibrary;
    /**
     * Element pressed in the library whose travel has not passed
     * {@link #DRAG_THRESHOLD} yet - past the threshold it upgrades to
     * {@link #draggingFromLibrary}. This middle state keeps "click a list
     * item" equal to select only.
     */
    private HudElement pendingLibraryDragElement;
    /** Origin (screen pixels) of the press above, used to measure the travel. */
    private double pendingLibraryDragOriginX;
    private double pendingLibraryDragOriginY;
    /** True while dragging the library's right edge to resize it. */
    private boolean draggingSplitter;

    /** While dragging on the canvas: pointer offset from the element's top-left (<b>virtual screen</b> coords). */
    private double dragOffsetX;
    private double dragOffsetY;

    /** Pointer position (screen pixels) while dragging out of the library, for drawing the drag ghost box. */
    private double ghostMouseX;
    private double ghostMouseY;

    private boolean workInitialized;
    /** <b>Workspace</b> library width (written back only on save - like the coordinates, effective on save only). */
    private int workLibWidth;

    /** Capacity of the baseline arrays - the registry currently has 11 items, headroom left for future HUDs. */
    private static final int MAX_ELEMENTS = 32;

    /**
     * Whether the backdrop image is <b>missing</b> from resources - probed
     * once in {@link #init()}, then only this boolean is read per frame.
     *
     * <p>Once, not per frame: {@code ResourceManager.getResource} walks
     * resource packs one by one; doing that 60 times a second is pure waste,
     * and whether the image exists is fixed at jar packaging time - it cannot
     * change within an editing session.
     * Checking "is the object from the texture manager the missing texture"
     * does not work either: {@code TextureManager.getTexture} returns the
     * same registered object for the same id every time (only on a miss does
     * it create and register a new {@code ResourceTexture}), so object
     * identity tells nothing;
     * and on a missing resource it swaps only the <b>content</b> - the
     * load-time {@code FileNotFoundException} is caught into
     * {@code TextureContents.createMissing()},
     * the object itself remains a plain {@code ResourceTexture}
     * with no public marker to query.
     */
    private boolean canvasBackgroundMissing;

    private final ButtonWidget saveButton;
    private final ButtonWidget cancelButton;
    private final ButtonWidget resetButton;
    private final ButtonWidget visibleToggleButton;
    /**
     * Second button of action row 0: "Remove from canvas" when on the
     * canvas, "Place" when not - its label follows the selected element's
     * placed state (the only writer is {@link #refreshPlaceToggleLabel()}).
     */
    private final ButtonWidget placeToggleButton;
    private ScaleSliderWidget scaleSlider;

    /** Info icon center, computed in render and reused for drawing and hover testing. */
    private int iconCenterX;
    private int iconCenterY;
    /** Whether the pointer hovers over the info icon this frame. */
    private boolean iconHovered;

    /**
     * "Already warned once about unsaved changes" latch - see
     * {@link #requestClose()}.
     *
     * <p>Consumed on two paths only (Esc/cancel; a second press really
     * exits), reset in two places: {@link #save()} and
     * {@link #resetToSaved()}.
     * It is not a "dirty" cache - dirtiness is computed live every frame
     * ({@link #hasUnsavedEdits()}); this only records "the player has already
     * been asked once".
     *
     * <p><b>The orange warning icon reads only this</b> (not
     * {@link #hasUnsavedEdits()}): rearranging is normal operation, and an
     * orange light burning through every drag amounts to a "did you err?"
     * prompt attached to each movement;
     * the warning belongs to the moment the player <b>actually tries to
     * leave</b> - that is the live "pressing now loses things" reminder.
     */
    private boolean pendingDiscard;

    /**
     * Whether this opening has run entry initialization - separates a
     * <b>first open</b> from a <b>re-init after a window resize</b>.
     *
     * <p>Vanilla {@code Screen.resize} goes through
     * {@code refreshWidgetPositions() -> clearAndInit() -> init()}, i.e.
     * <b>every size change calls our {@code init()} again</b>.
     * If {@code init()} unconditionally reset the workspace to uninitialized
     * ({@link #workInitialized} = false), {@link #ensureWorkInit()} would
     * snapshot from the config again - wiping what the player just arranged
     * on the canvas.
     * So resetting the workspace, clearing the selection and clearing scroll
     * happen only on the <b>first</b> entry into {@code init()}; afterwards
     * (resolution/GUI scale changes) only geometry is recomputed and widgets
     * re-laid.
     */
    private boolean entered;

    /**
     * Coordinate baseline at entry (virtual screen ratios) - used by
     * {@link #hasUnsavedEdits()} to decide "was it moved".
     *
     * <p>The baseline records "where it sat on entry", rather than comparing
     * the workspace value against the config literal: the config stores an
     * anchor travel ratio, {@code resolveWorkX} clamps it into 0~1
     * (out-of-range values pulled into the range, negatives to 0.0 at the
     * leading edge), so a hand-edited out-of-range value makes the workspace
     * value differ from the literal and would be judged "edited" right after
     * opening.
     */
    private final double[] baseX = new double[MAX_ELEMENTS];
    /** Y baseline at entry, same accounting as {@link #baseX}. */
    private final double[] baseY = new double[MAX_ELEMENTS];
    /** Scale baseline at entry. */
    private final double[] baseScale = new double[MAX_ELEMENTS];
    /**
     * Baseline of "on the canvas or not" at entry.
     *
     * <p>It decides <b>whether position counts</b>: an off-canvas HUD's
     * coordinates are neither shown nor rendered.
     * After dragging a HUD onto the canvas and back, the workspace
     * coordinates left behind differ from the baseline,
     * but the HUD is still not on the canvas - the config's effective state
     * is unchanged and must not count as "edited".
     * So position participates only when the element is on the canvas
     * <b>now AND was at entry</b>;
     * the placed flip itself is covered by the {@code workPlaced} comparison
     * and is not skipped by this rule.
     */
    private final boolean[] basePlaced = new boolean[MAX_ELEMENTS];
    /** Library width baseline at entry (written back unconditionally on save, so it counts as a change too). */
    private double baseLibWidth;

    // ----Geometry recomputed every frame----

    private int libX;
    private int libY;
    private int libW;
    private int libH;
    /** Library list scroll offset (content pixels). */
    private int libScroll;
    /** Canvas rectangle (screen pixels) and the virtual-screen-to-screen scale. */
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;
    private float canvasScale = 1.0f;
    /**
     * Rows in the action area taken by a HUD's own extra config - computed
     * in {@link #layout()} from the <b>registry maximum</b>;
     * {@link #layoutWidgets()} reads it directly to lay out the session
     * button row.
     *
     * <p>One field instead of two independent computations: computing twice
     * risks "one row reserved, buttons laid out past the reservation" -
     * buttons poking outside the action area.
     */
    private int actionExtraRows;

    /**
     * Action-area rectangle (screen pixels) - the strip directly <b>below</b>
     * the canvas holding every widget: the two HUD buttons
     * and the scale slider, plus reset/save/cancel.
     * It is <b>not on the canvas</b> (the canvas deducts it before scaling,
     * see {@link #layout()}), so widgets covering the canvas cannot happen;
     * the content box shares the canvas's left edge and width.
     */
    private int actionX;
    private int actionY;
    private int actionW;
    private int actionH;


    /**
     * Builds the editor - records the return screen and wraps every registry
     * HUD into a workspace element.
     *
     * <p>The class knows no concrete HUD: names, accessors, default
     * positions and samples all come from {@link RegisterHUD}.
     *
     * @param parent screen returned to after save, cancel or close
     */
    public HUDEditor(Screen parent) {
        super(Text.translatable("nomorezombies.hudeditor.title"));
        this.parent = parent;

        RegisterHUD.registerBuiltins();
        List<HudElement> work = new ArrayList<>();
        for (HudEntry entry : RegisterHUD.entries()) {
            work.add(new HudElement(entry));
        }
        this.elements = work;

        this.saveButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.save"), b -> save())
                .dimensions(0, 0, 60, 20).build();
        this.cancelButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.cancel"), b -> cancel())
                .dimensions(0, 0, 60, 20).build();
        this.resetButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.reset"), b -> resetToSaved())
                .dimensions(0, 0, 60, 20).build();
        // The buttons refresh their own labels: both actions change the button's
        // own text (Show: On<->Off / Remove<->Place), so the action entry
        // points include the refresh instead of relying on another
        // layoutWidgets() pass
        this.visibleToggleButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.visible.on"),
                        b -> toggleVisible())
                .dimensions(0, 0, BTN_W, 20).build();
        this.placeToggleButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.remove"),
                        b -> togglePlaced())
                .dimensions(0, 0, BTN_W, 20).build();
    }

    /**
     * Whether a HUD is actually pulling its weight on the <b>editing
     * canvas</b> - the {@link HudEntry.PreviewContext} implementation,
     * letting the scoreboard sample decide whether to absorb the sidebar's
     * player/time rows.
     * Both the canvas path and the hover tooltip path call this one
     * implementation: both see the same shape.
     *
     * <p><b>Criterion: on the canvas AND enabled</b>, both required:
     * <ul>
     *  <li><b>On the canvas</b> ({@code workPlaced}):
     * a HUD removed from the canvas does not exist in the canvas picture at
     * all, so its partner should compose "without it". Reading visibility
     * only, ignoring placement,
     * would leave the scoreboard compositing "with it" after team stats is
     * dragged back to the library - never updating</li>
     *  <li><b>Enabled</b> ({@code workVisible}):
     * an element wearing the red frame (not drawn in game) carries no rows
     * on the canvas; counting it as "present" would make the composite
     * contradict its own red frame</li>
     * </ul>
     *
     * <p><b>Returns false early once a drag reaches the "30% into the
     * library" point</b>: releasing there takes it off the canvas, so the
     * composite steps ahead one beat -
     * the same moment, the same meaning as the frame turning red and the
     * library's red border ("what release will do, visible now").
     * Moving the pointer back inside retracts it immediately; the boundary
     * behavior matches the red frame exactly.
     *
     * <p>Computed live on every call, never cached: samples are
     * {@code create()}d fresh each frame,
     * so a criterion change recomputes the row set next frame,
     * and the scoreboard's frame grows/shrinks with it (frame and content
     * always share one source).
     *
     * @param id the target HUD's {@link HudEntry#id}
     * @return whether the HUD is currently on the canvas and enabled; false
     *         for an unregistered id
     */
    @Override
    public boolean workspaceActive(String id) {
        HudElement element = elementById(id);
        if (element == null || !element.workPlaced || !element.workVisible) {
            return false;
        }
        return !(element == dragging && draggingArmedForRemoval);
    }

    /** Workspace element by id; {@code null} if absent (keeps a modified registry from crashing). */
    private HudElement elementById(String id) {
        for (HudElement e : this.elements) {
            if (e.entry.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    // ----Three-zone geometry: all computed from the current screen size, so
    // resolution or GUI scale changes never shift positions----

    /**
     * Computes the canvas and action-area geometry - render calls it once per
     * frame, and init/resize/layoutWidgets also call it,
     * so mouse events and widget placement always see correct geometry first.
     *
     * <pre>
     *  ┌──────────────────────────────────────────────────────────┐  Top bar: title · ℹ
     *  ├────────────┬─────────────────────────────────────────────┤
     *  │            │ ┌─────────────────────────────────────────┐ │  Canvas centered
     *  │  Library   │ │  Preview canvas (proportional, static   │ │
     *  │ ● Wave     │ │  backdrop image)                        │ │
     *  │ ○ Items    │ └─────────────────────────────────────────┘ │
     *  │ ...        │ [Show: On][Remove]   [---- Scale: 1.00 ---] │  Row 0 HUD widgets
     *  │            │ [Reset][Save][Cancel]                       │  Last row session buttons
     *  │            │ Horizontal margin beside the canvas (chrome │
     *  │            │ backing color)                              │
     *  └────────────┴─────────────────────────────────────────────┘
     * </pre>
     *
     * <p><b>Layout</b>: the library is one full column on the left;
     * <b>the preview canvas takes the whole right zone minus the action
     * area</b> -
     * top edge on the top bar's bottom edge, horizontally centered inside the
     * right zone, with {@link #FRAME_W}
     * px reserved on its right and bottom for its own gray border
     * (see {@link #drawCanvasFrame(DrawContext)}), proportional, never
     * enlarged; <b>directly below the canvas is the action area</b>
     * (see {@link #actionRow(int)}): one row of HUD widgets, one row of
     * reset/save/cancel,
     * both rows sharing the same content box. <b>The content box is sized
     * from the right zone width and does not shrink with the canvas</b> - the
     * centered canvas floats horizontally,
     * and buttons drifting with it would have to be re-found at every window
     * size.
     *
     * <p>The canvas aspect ratio always equals the screen's, while the right
     * zone's does not, so after taking the smaller of the two axes the other
     * axis is left with a stretch of space:
     * the canvas is therefore horizontally centered in the right zone,
     * the margin split between the two sides (see the two chrome strips laid
     * by {@link #drawCanvasPlate(DrawContext)}),
     * both edges flush is impossible, and even splitting is the choice here.
     *
     * <p><b>The action area height is deducted from the canvas first</b>
     * ({@link #ACTION_H}) - the single pivot of the whole layout.
     * The reverse order (size the canvas from the whole right zone, then
     * press the action area onto it) is the rejected variant:
     * on width-limited windows the horizontal axis rules, the canvas runs to
     * the screen's bottom edge, and the action area can only float mid-canvas.
     *
     * <p><b>Canvas size does not jitter with the selection</b>:
     * the action area reserves height for the <b>registry's maximum extra
     * config rows</b>
     * (not the current selection's), so selecting any HUD never makes the
     * canvas jump.
     * All eleven items currently have 0 rows, numerically equal to
     * {@link #ACTION_H}.
     *
     * <p><b>Width decides the scale</b> (for any window less extreme than
     * 45:9): the canvas aspect always equals the screen's,
     * width fills the right zone,
     * and the vertical leftover goes to the whitespace above the action area.
     *
     * <p>The content area is "top bar bottom edge to screen bottom": the
     * usage instructions live in the top bar icon's tooltip
     * (see {@link #renderIconTooltip(DrawContext, TextRenderer, int, int, boolean, boolean)}), there is no footer strip at the
     * screen's bottom,
     * and library and canvas fill the whole stretch.
     */
    private void layout() {
        ensureWorkInit();

        int contentY = TOP_BAR_H;
        int contentH = Math.max(40, this.height - TOP_BAR_H);

        // ----Library (left column, full content height; width clamped by clampLibWidth())----
        // Three caps on width: the dragged value, the constant cap, and "1/4 of screen width"
        // The library is supporting cast; on wide screens it must not eat half the
        // screen - however wide it is dragged, it stops at 1/4
        libX = 0;
        libY = contentY;
        libW = clampLibWidth(workLibWidth);
        libH = contentH;

        // ----Action area height: reserved for the registry's max extra config rows----
        // "Maximum", not "current selection's": the latter would make the canvas bob up
        // and down with the selection -
        // clicking the list shifts the canvas and every HUD in it together - the last
        // thing arranging needs
        // All eleven items currently have 0 rows, so this only goes non-zero when a
        // future HUD brings its own toggles
        int extraRows = 0;
        for (HudElement e : elements) {
            extraRows = Math.max(extraRows, extraConfigRowCount(e));
        }
        actionExtraRows = extraRows;
        actionH = ROW_H * (ACTION_ROWS + extraRows) + ACTION_PAD * 2;

        // ----Preview canvas (horizontally centered; one slot each on the sides
        // and bottom reserved for the canvas's own gray border)----
        // The right slot must stay: of the canvas's four border lines, the top and
        // bottom ones already have footing outside the canvas
        // (top = the top bar separator row, bottom = the action area's top edge); the
        // left borrows the library's right-edge column
        // when width-limited, or lands on the plate left of the canvas when
        // height-limited - only the right, hugging the window edge, has no neighbor
        // to borrow. Without the slot the gray border can only squeeze into the
        // canvas's own last column, and any HUD dragged fully right would cover it -
        // the right line would be the only border an element can cover (see
        // drawCanvasFrame)
        int rightX = libW;
        int rightW = Math.max(1, this.width - libW);
        float scaleByW = (float) Math.max(1, rightW - FRAME_W) / Math.max(1, this.width);
        // The vertical budget gives up two things: the canvas's own bottom border and
        // the height of the action area below it
        // This is "deduct the action area first, then scale the canvas" - reversed,
        // width-limited windows run the canvas to the bottom edge
        float scaleByH = (float) Math.max(1, contentH - FRAME_W - actionH) / Math.max(1, this.height);
        // Never enlarge: the canvas is a preview, not a magnifier - proportional,
        // taking the smaller of the two axes; aspect ratio always equals the screen's
        // Which axis rules: width first (flush on both sides); only when fitting by
        // width pushes the canvas under the top bar
        // does it fall back to fitting by height. Criterion: screenW/screenH >
        // (libraryW+1)/(topBarH+actionH+1):
        // with the narrowest library of 140, the right side is 141/79≈1.78 - only from
        // 21:9 does height get a say
        canvasScale = Math.min(1.0f, Math.max(0.1f, Math.min(scaleByW, scaleByH)));
        canvasW = Math.max(1, Math.round(this.width * canvasScale));
        canvasH = Math.max(1, Math.round(this.height * canvasScale));
        // Horizontal centering: margin split between the two sides (the Math.max(rightX,
        // ...) is only a guard against the canvas crossing onto the library)
        // With 0 margin the formula degenerates to "flush on both sides" - and the
        // larger the vertical budget (shorter top bar, thinner action area)
        // the more window ratios hit it: when scaleByH >= scaleByW, canvasW = rightW -
        // FRAME_W
        // Proportional scaling with different budgets on the two axes can never be flush
        // on both sides; the margin must land on one side or both;
        // even splitting is the choice here: an equal strip of backing color on each
        // side reads as symmetric
        // The price: the canvas's left edge no longer equals the library's right edge
        // The margin uses integer division (both /2 operands are int), not float:
        // canvasX must be an integer -
        // it is the rectangle origin; half a pixel would put the outer frame on a half
        // pixel and every "flush" assertion fails
        // Integer division of a non-negative margin is floor division, error always 0
        // or 1px, within the assertions' "margin difference <= 1px"
        canvasX = Math.max(rightX, rightX + (rightW - FRAME_W - canvasW) / 2);
        canvasY = contentY;

        // ----Action area (directly below the canvas)----
        // Top edge = one slot below the canvas's bottom edge, that slot being the
        // canvas's own bottom border
        // (see drawCanvasFrame) - so the action area covers neither the border nor the
        // canvas;
        // not even a seam between the two. The content box is sized from the right zone
        // width, not the canvas:
        // the centered canvas drifts horizontally with the window ratio, and buttons
        // drifting with it would need re-finding at every window size
        // Centered on the right zone, the button group depends only on the library
        // width - fully decoupled from "where the canvas sits"
        int areaY = canvasY + canvasH + FRAME_W;
        int areaH = Math.max(actionH, this.height - areaY);
        // Leftover height is centered inside the action area (not parked against the
        // canvas's bottom edge): the whitespace below the canvas varies with the win-
        // dow ratio; centered, the widgets sit symmetrically between canvas and screen
        // bottom - reading as "a region below the canvas" rather than "a row glued to
        // it" - which also separates it visually from the canvas
        actionW = Math.max(1, rightW - ACTION_PAD * 2);
        actionX = Math.max(rightX, rightX + (rightW - actionW) / 2);
        actionY = Math.max(0, Math.min(areaY + (areaH - actionH) / 2, this.height - actionH));
    }

    /**
     * Top edge of action-area row {@code index} (screen pixels) - this
     * class's only row anchor.
     *
     * <p>Widgets inside the row do not take this y: with widget height
     * {@link #WIDGET_H} and row height {@link #ROW_H},
     * each adds {@code (ROW_H - WIDGET_H) / 2} to center vertically
     * (see {@link #layoutWidgets()})
     * Row meaning: 0 = HUD widgets, 1+ = a HUD's extra config rows, last =
     * session buttons
     * (the coordinate readout row is gone, see {@link #ACTION_ROWS}.)
     *
     * @param index row number (0-based)
     * @return the row's top y (screen pixels)
     */
    private int actionRow(int index) {
        return actionY + ACTION_PAD + index * ROW_H;
    }

    /** Offset for vertically centering a widget in its row (widget shorter than the row; half the difference sits above). */
    private static int rowWidgetOffset() {
        return (ROW_H - WIDGET_H) / 2;
    }

    /** How many extra config rows the selected element needs (always 0; see {@link HudEntry#extraConfigWidgets}). */
    private static int extraConfigRowCount(HudElement e) {
        return e.entry.extraConfigRowCount();
    }

    /**
     * Horizontal mapping from virtual screen coordinates to screen
     * coordinates.
     *
     * @param vx virtual screen X
     * @return screen X
     */
    private int toCanvasX(double vx) {
        return (int) Math.round(canvasX + vx * canvasScale);
    }

    /**
     * Vertical mapping from virtual screen coordinates to screen coordinates.
     *
     * @param vy virtual screen Y
     * @return screen Y
     */
    private int toCanvasY(double vy) {
        return (int) Math.round(canvasY + vy * canvasScale);
    }

    /**
     * Screen coordinates (mouse) to virtual screen X: drag hit tests and
     * clamping run in virtual screen space, the same coordinates as in game.
     * The divisor floors at {@code Math.max(0.0001, canvasScale)} - never a
     * division by zero, however small the scale.
     */
    private double toVirtualX(double mouseX) {
        return (mouseX - canvasX) / Math.max(0.0001, canvasScale);
    }

    /** Screen coordinates to virtual screen Y. Same divisor floor as {@link #toVirtualX(double)} against division by zero. */
    private double toVirtualY(double mouseY) {
        return (mouseY - canvasY) / Math.max(0.0001, canvasScale);
    }

    /** On first open, snapshot each HUD's live config into the workspace; editing afterwards touches only the snapshot, never the config. */
    private void ensureWorkInit() {
        if (workInitialized) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) return;
        snapshotFromConfig();
        workLibWidth = clampLibWidth(GlobalConfig.Hud.HUD_LIBRARY_WIDTH.getDoubleValue());
        baseLibWidth = workLibWidth;
        workInitialized = true;
    }

    /**
     * Snapshots the live config wholesale into the workspace -
     * <b>opening the editor and "Reset" share this code</b>.
     *
     * <p>Sharing it makes "Reset" always mean "back to what the editor showed
     * at the moment it opened",
     * rather than a separately written "back to default edge-flush positions"
     * algorithm; once the two are written separately,
     * "reset looks different from a fresh open" appears - a divergence only
     * discovered in play.
     *
     * <p>The five snapshot sources map one-to-one onto {@link HudElement}'s
     * five workspace fields:
     * coordinates (the config stores anchor travel ratios,
     * clamped into 0~1 by {@link #resolveWorkX(HudElement)}),
     * scale, in-game visibility,
     * and whether the element sits on the canvas. {@code geometryModified} is
     * cleared too - a freshly snapshotted workspace matches the config
     * verbatim, with nothing to write back.
     *
     * <p><b>The library width is not included</b>: it is a UI preference
     * (written back unconditionally on save),
     * and rolling it back would yank the splitter being dragged right back -
     * not what the player asks of "Reset".
     *
     * <p><b>It also records the entry baseline</b>
     * ({@link #baseX}/{@link #baseY}/{@link #baseScale}/
     * {@link #baseLibWidth}): the unsaved check wants "what changed since
     * entry",
     * not "does it equal the config literal".
     * The config's anchor ratio passes through {@code resolveWorkX}'s clamp
     * before entering the workspace,
     * so a hand-edited out-of-range value naturally differs - a literal
     * comparison would falsely report "edited".
     * The baseline is taken right after the snapshot, so it matches the
     * workspace's initial values verbatim.
     */
    private void snapshotFromConfig() {
        for (HudElement e : elements) {
            e.workX = resolveWorkX(e);
            e.workY = resolveWorkY(e);
            e.workScale = e.entry.getScale.getAsDouble();
            e.workVisible = e.entry.getVisible.getAsBoolean();
            // "On the canvas" has its own config; read it directly - never infer it
            // from visibility (the two are independent states). By default only the
            // loot stats HUD is placed; the other ten are ○, and the canvas holds only it
            e.workPlaced = e.entry.getPlaced.getAsBoolean();
            e.geometryModified = false;
        }
        recordBaseline();
    }

    /**
     * Records the workspace state as the unsaved-check baseline - called from
     * {@link #snapshotFromConfig()} (open
     * /reset)
     * and from {@link #save() } (after persisting) - two call sites.
     *
     * <p>Extracted because both share the same semantics, differing only in
     * trigger: right after the snapshot has written the workspace,
     * or right after save has written the workspace into the config,
     * "workspace == saved state" holds in both moments, and the baseline
     * should land there.
     */
    private void recordBaseline() {
        int i = 0;
        for (HudElement e : elements) {
            // Items past capacity get no baseline (hasUnsavedEdits() degrades to
            // comparing only visibility/placement for them)
            if (i < MAX_ELEMENTS) {
                baseX[i] = e.workX;
                baseY[i] = e.workY;
                baseScale[i] = e.workScale;
                basePlaced[i] = e.workPlaced;
            }
            i++;
        }
        baseLibWidth = workLibWidth;
    }

    /**
     * Whether this element's position <b>should</b> join the "was it edited"
     * comparison.
     *
     * <p>Only when it is on the canvas <b>now AND was at entry</b>:
     * an off-canvas HUD's
     * coordinates are neither displayed nor rendered - after the player
     * "dragged it onto the canvas and back", the leftover coordinates differ
     * from the baseline,
     * but the config's effective state (it is not on the canvas) never
     * changed.
     * The placed flip itself is handled by the bitwise {@code workPlaced}
     * comparison, so skipping position here never misses "really added or
     * removed a HUD".
     *
     * @param i index into the baseline arrays
     * @param e workspace element
     * @return whether position participates in the comparison
     */
    private boolean posCounts(int i, HudElement e) {
        return i < MAX_ELEMENTS && e.workPlaced && basePlaced[i];
    }

    /**
     * Whether the position lands on <b>the same pixel</b> as the entry
     * baseline - the shared judgment of {@link #hasUnsavedEdits()} and
     * {@link #save()}.
     *
     * <p><b>Compare by pixel, not by ratio</b>:
     * the editor places everything in integer pixels ({@code virtualBox}
     * computes
     * {@code (int)(workX * width)}),
     * while the drag path writes back the double {@code targetPx/width}.
     * So "drag to B and back to A" lands on a ratio
     * <b>only a fraction of a pixel off the baseline</b>; a bitwise
     * comparison always fails,
     * and the mechanism misreports "edited". Compared on the same pixel
     * accounting, such net-zero gestures dissolve naturally;
     * a real move is at least one pixel and cannot be absorbed.
     *
     * @param i index into the baseline arrays
     * @param e workspace element
     * @return {@code true} when both land on the same pixel
     */
    private boolean samePosAsBaseline(int i, HudElement e) {
        if (i >= MAX_ELEMENTS) {
            return false;
        }
        return virtualPx(e.workX, e, false) == virtualPx(baseX[i], e, false)
                && virtualPx(e.workY, e, true) == virtualPx(baseY[i], e, true);
    }

    /**
     * Anchor ratio to virtual screen pixels (resolved against the current
     * canvas travel), for the pixel-based "did the position change" check.
     *
     * <p>The editor places in integer pixels, while the drag path writes back
     * the {@code ratioFrom(px)} double,
     * so "drag to B and back to A" lands on a ratio <b>only a fraction of a
     * pixel off the baseline</b>; a bitwise comparison always fails. Compared
     * on the same pixel accounting, such net-zero gestures dissolve
     * naturally.
     *
     * @param ratio anchor ratio
     * @param e workspace element
     * @param vertical true = Y axis, false = X axis
     * @return the pixel the ratio resolves to under the current canvas travel
     */
    private int virtualPx(double ratio, HudElement e, boolean vertical) {
        HudPreview p = e.entry.preview.create(this);
        float s = (float) e.workScale;
        if (vertical) {
            int bh = TotalHUDRenderer.visibleSize(p.height, s);
            return (int) Math.round(pxFrom(ratio, minBoxY(e.entry), maxBoxY(bh, e.entry)));
        }
        int bw = TotalHUDRenderer.visibleSize(p.width, s);
        return (int) Math.round(pxFrom(ratio, minBoxX(e.entry), maxBoxX(bw, e.entry)));
    }
    /**
     * Whether the scale matches the entry baseline <b>at readout
     * precision</b> (two decimals,
     * the digit shown as "Scale: 1.00" in the UI).
     *
     * <p><b>Compare at readout precision</b>: the slider is continuous;
     * dragging it out and back to the original spot
     * yields a double differing from the baseline below the third decimal -
     * the player sees the same "1.00", yet the config would record a
     * different number.
     * Readout precision aligns exactly with "changes the player can see".
     *
     * <p>The precision still catches real operations: the slider is only
     * {@code BTN_W} wide, one pixel is worth about 0.017 of scale,
     * and the 0.01 granularity is finer - any scale change a player can
     * deliberately make far exceeds this threshold.
     *
     * @param i index into the baseline arrays
     * @param e workspace element
     * @return {@code true} when the readouts match
     */
    private boolean sameScaleAsBaseline(int i, HudElement e) {
        return i < MAX_ELEMENTS && Math.round(e.workScale * 100.0) == Math.round(baseScale[i] * 100.0);
    }

    /**
     * Workspace X accounting: the number in the config <b>is</b> the anchor
     * ratio, with no screen conversion.
     *
     * <p>Negative values mean nothing here: factory values are already one
     * of three steps (0/0.5/1),
     * {@code 0.5} is dead center - no extra sentinel for "never dragged" is
     * needed.
     *
     * <p>The range is still clamped once: a hand-edited config file can
     * contain out-of-range values,
     * and the anchor ratio's legal domain is {@code 0~1}.
     *
     * @param e workspace element
     * @return anchor ratio (0~1)
     */
    private double resolveWorkX(HudElement e) {
        return TotalHUDRenderer.clampAnchorRatio(e.entry.getX.getAsDouble());
    }

    /**
     * Workspace Y accounting: fully symmetric with
     * {@link #resolveWorkX(HudElement)}.
     *
     * @param e workspace element
     * @return anchor ratio (0~1)
     */
    private double resolveWorkY(HudElement e) {
        return TotalHUDRenderer.clampAnchorRatio(e.entry.getY.getAsDouble());
    }

    /**
     * The library width's single clamping point - both the drag path and
     * config reads pass through it,
     * so the two paths cannot clamp differently and disagree.
     *
     * <p>Two ceilings: the constant cap {@code LIB_MAX_W}, and <b>1/4 of
     * screen width</b> (the library is supporting cast,
     * it must not eat half the screen on wide windows); the latter is raised
     * to the floor {@code LIB_MIN_W},
     * so narrow screens never clamp below the floor.
     *
     * @param rawWidth target width (px)
     * @return clamped width (px)
     */
    private int clampLibWidth(double rawWidth) {
        int quarterScreen = Math.max(LIB_MIN_W, this.width / 4);
        int ceiling = Math.min(LIB_MAX_W, quarterScreen);
        return (int) Math.round(Math.clamp(rawWidth, LIB_MIN_W, ceiling));
    }

    // ----Element rectangles: one in virtual space, one in screen space (paired rounding)----

    /**
     * Anchor ratio to virtual screen pixels (the canvas's own travel).
     *
     * <p>The <b>same formula</b> as the in-game
     * {@code TotalHUDRenderer#anchorPixels};
     * only the travel's ends are swapped for the canvas's
     * {@link #minBoxX(HudEntry)}/{@link #maxBoxX(int, HudEntry)}
     * (the canvas also reserves a slot for the outward frame and stacks the
     * HUD's own in-game reserve - see their own docs):
     * {@code x = min + ratio * (max - min)}.
     *
     * <p>So {@code ratio=0/0.5/1} is "canvas left edge/center/right edge" in
     * the editor and
     * "screen left edge/center/right edge" in game -
     * the same number lands at the same relative spot in both, independent of
     * resolution and canvas scale.
     *
     * @param ratio anchor ratio (clamped into 0~1)
     * @param min travel start (virtual screen pixels)
     * @param max travel end (virtual screen pixels)
     * @return virtual screen pixel coordinate
     */
    private static double pxFrom(double ratio, double min, double max) {
        double r = TotalHUDRenderer.clampAnchorRatio(ratio);
        return min + r * Math.max(0.0, max - min);
    }

    /**
     * Virtual screen pixels to anchor ratio - the inverse of {@link #pxFrom}.
     *
     * <p>With a zero-length travel (element larger than the canvas, both ends
     * collapsed together) it returns 0:
     * the element is already pressed against the canvas boundary, and either
     * step stores the same thing.
     *
     * @param px virtual screen pixel coordinate
     * @param min travel start
     * @param max travel end
     * @return anchor ratio (0~1)
     */
    private static double ratioFrom(double px, double min, double max) {
        double span = max - min;
        if (span <= 0.0) {
            return TotalHUDRenderer.ANCHOR_LEADING;
        }
        return TotalHUDRenderer.clampAnchorRatio((px - min) / span);
    }

    /**
     * The element's rectangle {@code [x, y, w, h]} in <b>virtual screen</b>
     * space.
     *
     * <p>Position is solved by {@link #pxFrom} from the workspace anchor
     * ratios; size stays the
     * single {@link HudPreview} copy (times the workspace scale).
     * The old {@code clampX} fallback is gone: under the anchor accounting
     * the {@code 0~1} ends
     * land inside the travel by construction, out-of-range is structurally
     * impossible.
     *
     * <p><b>The dragged one takes the free position</b>
     * ({@code draggingPx/draggingPy}):
     * {@code workX} always lives in {@code 0~1} and resolves inside the
     * canvas travel, unable to express
     * the "element slides out of the canvas into the library" middle state.
     * The criterion is {@code e == dragging}, not "is there a free position",
     * because the free position is valid only mid-drag;
     * after release it must immediately return to the ratio as the single
     * source of truth.
     *
     * <p><b>Size goes through {@link TotalHUDRenderer#visibleSize}</b>
     * (ceiling), not rounding:
     * content is drawn on a float matrix; rounding would make the box one
     * pixel smaller than the ink whenever the fraction is under 0.5,
     * and the frame would cut off the flush-bottom/flush-right pixel - the
     * origin of the "1px missing at the bottom". Both sides must share this
     * accounting,
     * and the in-game anchor travel reads it too.
     */
    private int[] virtualBox(HudElement e, HudPreview p, float s) {
        int bw = TotalHUDRenderer.visibleSize(p.width, s);
        int bh = TotalHUDRenderer.visibleSize(p.height, s);
        if (e == dragging) {
            return new int[]{(int) Math.round(draggingPx), (int) Math.round(draggingPy), bw, bh};
        }
        int x = (int) Math.round(pxFrom(e.workX, minBoxX(e.entry), maxBoxX(bw, e.entry)));
        int y = (int) Math.round(pxFrom(e.workY, minBoxY(e.entry), maxBoxY(bh, e.entry)));
        return new int[]{x, y, bw, bh};
    }

    /**
     * Virtual rectangle to <b>screen pixel</b> rectangle {@code [x, y, w, h]}.
     *
     * <p>The mapping itself is one formula:
     * {@code screenPx = round(canvasX + virtualPx * canvasScale)}
     * but mapping a rectangle must <b>map both ends and subtract</b> (paired
     * rounding), never compute the width first and map that -
     * a precomputed width drifts 1px at non-integer scales, the canvas-side
     * twin of "flush in the editor, a gap in game".
     *
     * <p><b>The far end really is "map the far end too and subtract"</b>; do
     * not change it to "near end + some size":
     * the flush-edge case ({@code toCanvasX(vx + bw)} landing on the canvas's
     * last column, the gray border right beside it)
     * relies on {@link #maxBoxX(int, HudEntry)}'s {@code +0.5} working with
     * this mapping;
     * "near end + rounded size" is off by a slot for more than half of the
     * combinations at non-integer canvas scales (measured grid),
     * and flush edges are directly player-visible. Whether the box covers the
     * ink is the size end's job:
     * element size goes through {@link TotalHUDRenderer#visibleSize} (ceiling,
     * never smaller than the ink).
     *
     * <p>Frame and hit testing follow a different accounting:
     * {@link #FRAME_W}/{@link #HIT_SLACK} measure <b>screen
     * </b> pixels,
     * they are UI hints (a hairline, a click margin) and do not scale with
     * {@code canvasScale};
     * the "outside the bounds" semantics holds unchanged, i.e. "frame inner
     * edge = hitbox = content rectangle".
     * The clamp bounds are screen-pixel quantities too: divide by
     * {@code canvasScale} back into virtual coordinates before comparing
     * (see {@link #minBoxX(HudEntry)}/{@link #maxBoxX(int, HudEntry)}) - using screen pixels as virtual ones
     * shrinks on a sub-1x canvas, and after rounding reserves nothing.
     */
    private int[] screenBox(int[] v) {
        int x0 = toCanvasX(v[0]);
        int y0 = toCanvasY(v[1]);
        int x1 = toCanvasX(v[0] + v[2]);
        int y1 = toCanvasY(v[1] + v[3]);
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** The element's on-screen rectangle (within the canvas) - hit tests, drawing and frames all use it. */
    private int[] elementScreenBox(HudElement e) {
        HudPreview p = e.entry.preview.create(this);
        return screenBox(virtualBox(e, p, (float) e.workScale));
    }

    /**
     * Leftmost pixel X available to the element (virtual screen coordinates);
     * converts the <b>screen-space</b> constraint "the outward frame must
     * stay inside the canvas" back into virtual coordinates.
     *
     * <p>{@link #FRAME_W} measures <b>screen</b> pixels (a hairline that does
     * not scale with the canvas,
     * see the class comment's two accountings),
     * so dividing by {@code canvasScale} gives the distance in virtual screen
     * space. Used directly as a virtual pixel:
     * on a 0.77x canvas that is only 0.77 screen pixels, which rounds to
     * nothing reserved,
     * and any flush-left element presses onto the canvas's outermost column.
     *
     * <p><b>The HUD's own in-game reserve is stacked on top</b>
     * ({@link HudEntry#reserveX}):
     * the in-game travel is {@code [reserve, screenWidth - contentWidth - reserve]}, and the editor's travel must
     * stack the same reserve, otherwise any HUD declaring one lands a whole
     * stretch away from its in-game spot -
     * exactly where "canvas position does not match the real position" came
     * from.
     *
     * <p><b>The two amounts have different units and different landing
     * rules</b>: {@link #FRAME_W} is a <b>screen (canvas) pixel</b>,
     * so it goes inside the division back to virtual coordinates;
     * while {@link HudEntry#reserveX} is itself declared as <b>virtual screen
     * pixels</b>
     * (see {@code HudEntry.Builder#reserve}'s docs),
     * and therefore sits <b>outside</b> the division - folding it in would be
     * off by
     * {@code reserve * (1 - 1/canvasScale)} pixels.
     *
     * <p>The left end and {@link #maxBoxX(int, HudEntry)} are a symmetric
     * pair: flush left the frame touches the canvas's first column,
     * flush right its last column.
     *
     * @param entry HUD being positioned (reads its {@link HudEntry#reserveX})
     * @return the left-end floor in virtual screen coordinates
     */
    private double minBoxX(HudEntry entry) {
        return FRAME_W / Math.max(0.0001, canvasScale) + entry.reserveX;
    }

    /**
     * Rightmost pixel X available to the element (virtual screen coordinates);
     * symmetric with {@link #minBoxX(HudEntry)}:
     * the element including its outward frame exactly reaches
     * the canvas's last column, right against the canvas's own gray border
     * (see {@link #drawCanvasFrame(DrawContext)}).
     *
     * <p><b>No half-slot compensation</b>: what is stored <b>is the anchor
     * ratio</b>, retrieved through {@link #pxFrom} then
     * {@code Math.round}, and on release
     * {@link TotalHUDRenderer#snapAnchorRatio(double, double)}
     * has already collapsed "dragged to the end" into an exact {@code 1.0} -
     * {@code pxFrom(1.0)} resolves to
     * {@code maxBoxX} itself; there is no second truncation to compensate.
     * Any positive correction instead pushes the element and its outer ring
     * half a slot past the edge, rounding onto the border row/column:
     * "the component pressed against it looks stacked onto the boundary".
     *
     * <p>The bottom end ({@link #maxBoxY(int, HudEntry)}) and the two library
     * edges follow the same logic;
     * while the {@link #minBoxX(HudEntry)}/{@link #minBoxY(HudEntry)}
     * floor is already "the frame slot" and needs no compensation.
     *
     * <p>Compensation cannot be added back: any positive value recreates
     * "pressed onto the boundary".
     *
     * <p><b>What is subtracted is exactly the content size passed in</b> (it
     * has already been through
     * {@link TotalHUDRenderer#visibleSize}, never smaller than the ink): both
     * ends must use the same number,
     * otherwise "flush right" is off by that sub-slot remainder.
     *
     * <p><b>The ceiling likewise subtracts the in-game reserve</b>
     * ({@link HudEntry#reserveX}) and subtracts it <b>outside the
     * division</b>: the reserve is a virtual screen pixel amount that shrinks
     * the whole travel rather than just moving the start,
     * so both ends must take part - subtracting only the floor would degrade
     * "reserve" into a translation.
     *
     * @param bw element content width (virtual screen pixels)
     * @param entry HUD being positioned (reads its {@link HudEntry#reserveX})
     * @return the right-end ceiling in virtual screen coordinates; collapses
     *         to {@link #minBoxX(HudEntry)} when the element is wider than
     *         the canvas
     */
    private double maxBoxX(int bw, HudEntry entry) {
        double limit = (canvasW - FRAME_W) / Math.max(0.0001, canvasScale);
        return Math.max(minBoxX(entry), limit - bw - entry.reserveX);
    }
    /** Vertical top end (virtual screen coordinates): the top likewise reserves the outward-frame slot plus the in-game reserve; see {@link #minBoxX(HudEntry)}. */
    private double minBoxY(HudEntry entry) {
        return FRAME_W / Math.max(0.0001, canvasScale) + entry.reserveY;
    }

    /**
     * Bottommost end available to the element (virtual screen coordinates) -
     * the vertical twin of {@link #maxBoxX(int, HudEntry)},
     * likewise <b>without that {@code +0.5}</b>: the row beyond the canvas's
     * bottom edge is the gray border,
     * and the element including its outward frame must land <b>inside</b> the
     * canvas to avoid covering it; see {@link #maxBoxX(int, HudEntry)}.
     *
     * <p>A HUD with {@code reserveY} relies on this amount to pull "flush
     * bottom" inside that edge,
     * matching what the in-game {@code anchorPixels} resolves. The only
     * declared vertical reserve is currently 0,
     * so this branch is kept for future HUDs - it is still where the
     * "editor = in-game" contract lands.
     *
     * @param bh element content height (virtual screen pixels)
     * @param entry HUD being positioned (reads its {@link HudEntry#reserveY})
     * @return the bottom-end ceiling in virtual screen coordinates; collapses
     *         to {@link #minBoxY(HudEntry)} when the element is taller than
     *         the canvas
     */
    private double maxBoxY(int bh, HudEntry entry) {
        double limit = (canvasH - FRAME_W) / Math.max(0.0001, canvasScale);
        return Math.max(minBoxY(entry), limit - bh - entry.reserveY);
    }

    /** Clamps the drag target into [min,max]; when the ceiling is below the floor it collapses to the floor, producing neither negatives nor jitter. */
    private static double clampRange(double value, double min, double max) {
        // Raise the ceiling to at least the floor first, then hand to Math.clamp: a
        // degenerate travel (hi < min) therefore always yields min
        return Math.clamp(value, min, Math.max(min, max));
    }

    // ----Drawing----

    /**
     * Per-frame main draw: veil, right-side plate, canvas backdrop image, HUD
     * samples, library,
     * top bar (with the selection readout)
     * then tooltips.
     *
     * <p><b>Draw order is z-order</b>: later draws cover earlier ones. The
     * library and top bar both come after the samples,
     * so the parts of the canvas they overlap cannot be covered back by
     * samples; the action area's buttons and slider come later still
     * (the framework draws them in {@code super.render}), so they always sit
     * on top.
     * The action area itself is <b>outside</b> the canvas (the canvas deducts
     * it before scaling),
     * so it actually covers no sample -
     * the ordering is kept for the library and top bar.
     */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        layout();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) {
            return;
        }

        // (1) Full-screen veil: dims the game picture so the backdrop image and widget
        // readouts have contrast
        context.fill(0, 0, this.width, this.height, VEIL_COLOR);

        // (2) Right-side plate: fills the ring outside the canvas with the chrome base
        // color; the canvas sits on top
        drawCanvasPlate(context);

        // (3) Canvas area: static backdrop image (whole image stretched over the
        // canvas rectangle)
        drawCanvasBackground(context);

        // (3b) Reserve bands: draws "the edge the current HUD declared off-limits" onto
        // the canvas (under the samples)
        // It explains "why it cannot be dragged to the very bottom" - the canvas is a
        // static image, there is no hotbar underneath
        drawReserveBands(context);

        // (4) HUD samples on the canvas: read workspace state, never touch the live
        // config; what you see is the trial placement
        renderElements(context);

        // (5) Chrome layer: library -> top bar (title + icon; order is z-order, both
        // must sit above the samples)
        renderLibrary(context, tr, mouseX, mouseY);
        renderTopBar(context, tr, mouseX, mouseY);

        // (5b) The one in hand: above the library/top bar, so it can be watched
        // entering the library all the way
        renderDraggedElement(context);

        // The "Show" button's label is not refreshed here: it follows the selection, and
        // any change runs
        // layoutWidgets(), which already writes it - putting it on the per-frame path
        // would just allocate a new Text object every frame
        super.render(context, mouseX, mouseY, delta);

        // (6) Tooltip layer drawn last
        boolean hudMasterOn = GlobalConfig.QoL.HUD_MASTER.getBooleanValue();
        if (iconHovered) {
            // The tooltip's "unsaved" line reads pendingDiscard too: same state as the
            // icon turning orange,
            // appearing at the same moment (otherwise the tooltip could claim unsaved
            // changes while the icon stays blue)
            renderIconTooltip(context, tr, mouseX, mouseY, hudMasterOn, pendingDiscard);
        }
        // While dragging, report only the name (pinned bottom-right of the cursor), not
        // state - what the player wants to know is "what is this"
        // Position via DragNamePositioner, see its docs (state is shown by the canvas's
        // red/yellow frames)
        if (draggingFromLibrary != null) {
            renderDragGhost(context);
            renderDragNameTooltip(context, tr, draggingFromLibrary, mouseX, mouseY);
        } else if (dragging != null) {
            renderDragNameTooltip(context, tr, dragging, mouseX, mouseY);
        } else {
            // Only when the pointer rests do we answer "what is this": a library row gets
            // the "name + state +
            // what this HUD looks like" hover preview, a canvas element gets "name + state"
            HudElement libHovered = libraryItemAt(mouseX, mouseY);
            if (libHovered != null) {
                renderLibraryTooltip(context, tr, mouseX, mouseY, libHovered);
            } else {
                HudElement hovered = elementAt(mouseX, mouseY);
                if (hovered != null) {
                    renderHoverTooltip(context, tr, mouseX, mouseY, hovered);
                }
            }
        }
    }

    /**
     * Name tooltip during a drag: one line, pinned to the cursor's
     * <b>bottom right</b> (flips automatically at screen edges, see
     * {@link DragNamePositioner})
     *
     * <p>Dragging reports the name where hovering reports nothing: a
     * translucent drop box is already in hand,
     * "which slot will this land in" is visible -
     * what is not visible is "which one did I grab" - especially when the
     * library's
     * eleven items look alike. A fixed position (bottom right) rather than
     * flipping around the cursor
     * keeps it from covering the drop box itself.
     *
     * @param context draw context
     * @param tr text renderer
     * @param e the element being dragged
     * @param mouseX cursor X (the tooltip pins to its bottom right)
     * @param mouseY cursor Y
     */
    private void renderDragNameTooltip(DrawContext context, TextRenderer tr, HudElement e,
                                       int mouseX, int mouseY) {
        // After a library drag, ghostMouseX/Y still hold the last drag's values; the
        // position always uses the current frame's cursor
        List<Text> lines = List.of(Text.literal(e.entry.name()));
        renderVanillaTooltip(context, tr, lines, null, mouseX, mouseY,
                DragNamePositioner.INSTANCE, false);
    }

    /**
     * Right-side plate: fills the whole right zone with the same translucent
     * chrome base as the top bar and library.
     *
     * <p>The whole right zone is plated: after proportional scaling the
     * canvas always leaves one axis with leftover (see {@link #layout()}'s
     * docs),
     * and an unfilled gap would be a hole leaking the game picture - the
     * canvas would look like it floats in midair.
     * With the plate, the leftover becomes "a base holding the canvas", the
     * canvas reads as a panel set into the UI,
     * and this layer shares its color fully with the top bar and library
     * (both {@link #CHROME_BG}),
     * so the surrounding borders join up naturally.
     *
     * <p><b>The entire right zone</b> is filled, not just strips above and
     * below:
     * when the canvas fits by height (ultra-wide windows) the sides also show
     * leftover;
     * filling once avoids writing four cases and leaves no seam unmissed.
     * The canvas draws over this layer afterwards,
     * so this layer shows only outside the canvas.
     */
    private void drawCanvasPlate(DrawContext context) {
        context.fill(libW, TOP_BAR_H, this.width, this.height, CHROME_BG);
    }

    /**
     * The gray border ring outside the canvas rectangle - 1px on each side,
     * drawn <b>outside</b> the canvas,
     * so it consumes none of the canvas's pixels.
     *
     * <p><b>Each side has its own footing</b>: the left lands on the library's
     * right-edge column when width-limited,
     * or on the plate left of the canvas when height-limited; the top lands
     * on the top bar separator row (same color
     * {@link #CHROME_BORDER} as the left, reading as one continuous line); the
     * bottom lands on the plate row,
     * and the right relies on {@link #layout()} <b>reserving the slot</b>
     * when computing the scale
     * ({@code scaleByW} uses {@code rightW-FRAME_W}) - the right hugs the
     * window edge,
     * with no neighbor to borrow.
     *
     * <p>The right slot must be reserved, otherwise the whole line either
     * falls off screen,
     * or squeezes into the canvas's own last column:
     * the outer rectangle {@code (canvasX-1, canvasY-1, canvasW+2, canvasH+2)} puts its right edge at
     * {@code canvasX + canvasW}, and when the canvas is flush right that is
     * {@code width - FRAME_W} -
     * the last column ({@code width-1}) belongs to the border; without the
     * slot the line retreats to {@code width},
     * the whole line lands off screen, and the right side loses its border.
     * Even clamped back on screen, it can only squeeze into the canvas's own
     * last column, and any HUD dragged fully right
     * can cover it - with the slot now reserved,
     * the element's placeable range ({@link #maxBoxX(int, HudEntry)}) keeps
     * clear of it,
     * and no border ever interacts with elements.
     *
     * <p>The clamps here are a <b>fallback</b> (keep the border on screen when
     * an extremely narrow window squeezes the canvas against the screen edge),
     * not the mechanism for the matter above.
     */
    private void drawCanvasFrame(DrawContext context) {
        int x0 = Math.max(0, canvasX - 1);
        int y0 = Math.max(0, canvasY - 1);
        int x1 = Math.min(this.width, canvasX + canvasW + 1);
        int y1 = Math.min(this.height, canvasY + canvasH + 1);
        context.drawBorder(x0, y0, x1 - x0, y1 - y0, CHROME_BORDER);
    }

    /**
     * The canvas area's static backdrop: the whole {@link #BG_TEXTURE}
     * stretched over the canvas rectangle, then the border ring on top.
     *
     * <p><b>Must use the 12-argument overload, and the region must be the
     * whole image
     * </b>{@code DrawContext.drawTexture}
     * has two overload families;
     * the 10-argument one implicitly sets the sampling region to the draw
     * size ({@code regionWidth = width}),
     * so {@code u} only walks from 0 to {@code canvasW/1920} - of the
     * 1920-wide image only about the top-left third gets sampled,
     * then stretched over the canvas, which looks like "the backdrop zoomed
     * until only a patch shows". Only the 12-argument family lets the caller
     * give
     * region and texture size separately:
     * <ul>
     *  <li>{@code width/height} = the size drawn on screen (the canvas
     *  rectangle);</li>
     *  <li>{@code regionWidth/regionHeight} = how much of the texture to
     *  sample
     *  (the whole image means {@link #BG_TEX_W}×{@link #BG_TEX_H});</li>
     *  <li>{@code textureWidth/textureHeight} = the texture's true pixel
     *  size,
     *  the divisor that turns {@code u/v} into 0~1</li>
     * </ul>
     * In other words, "which part of the source to take" and "how large to
     * draw it" are two different things,
     * and the 10-argument family welds them into one.
     * {@code HudCanvas.LiveSource.draw} in this project
     * draws the same way (region and texture size equal).
     *
     * <p>When the image is missing (not packaged into the jar / name typo),
     * it falls back to a solid fill plus one hint line -
     * otherwise the player sees a purple-black checkerboard, knowing neither
     * what it is nor how to fix it.
     * Detection: {@link #canvasBackgroundMissing}
     */
    private void drawCanvasBackground(DrawContext context) {
        if (canvasBackgroundMissing) {
            drawCanvasMissing(context);
            return;
        }
        context.drawTexture(RenderLayer::getGuiTextured, BG_TEXTURE,
                canvasX, canvasY, 0.0f, 0.0f, canvasW, canvasH,
                BG_TEX_W, BG_TEX_H,     // sampling region = whole image (must not follow the canvas size)
                BG_TEX_W, BG_TEX_H,     // texture's true pixel size (the u/v divisor)
                CANVAS_BG_TINT);
        drawCanvasFrame(context);
    }

    /**
     * Draws the edge the current HUD declared off-limits as an amber band -
     * the visible explanation for "cannot be dragged to the bottom".
     *
     * <p><b>Why it must exist</b>: a HUD with {@code reserve} really cannot
     * reach that edge on the canvas
     * (see {@link #minBoxX(HudEntry)}/{@link #maxBoxY(int, HudEntry)}), and in
     * game it is exactly the same
     * (same {@code TotalHUDRenderer.anchorPixels}, pinned pixel-for-pixel by
     * the 4f-6 test).
     * But the canvas paints a static backdrop image, and the wall <b>carries
     * no sign of "no entry here"</b>,
     * undrawn it looks like "the editor is broken". The band draws the wall,
     * turning it into an explanation.
     *
     * <p>Currently only the scoreboard's {@code reserve(1, 0)} reaches here,
     * and it is only 1 pixel wide, nearly invisible -
     * the path's value is that "whoever declares a reserve in the future gets
     * visibility automatically", with no need to remember to add this later.
     *
     * <p><b>Only the currently relevant element's reserve is drawn</b> (the
     * selection, or the dragged one),
     * not all of them: each item's reserve can differ, and drawing all eleven
     * would smear the canvas into a wash,
     * losing "this band is its". With nothing selected the canvas stays
     * clean.
     *
     * <p>The band's thickness is {@code reserve * canvasScale} - the reserve
     * is a virtual pixel amount and the canvas its proportional view,
     * so the band exactly overlaps the stretch the element cannot actually
     * reach.
     *
     * <p><b>No text on the band</b>: reserves are usually only a few pixels
     * tall, and an 8px text line would squeeze into a slit; the band itself is
     * the whole explanation ("this edge is off-limits"), and it is drawn only
     * for the element being dragged or selected.
     *
     * @param context draw context
     */
    private void drawReserveBands(DrawContext context) {
        HudElement e = dragging != null ? dragging : selected;
        if (e == null || !(e.workPlaced || e == dragging)) {
            return;
        }
        // Vertical: the anchorPixels formula is symmetric - the same reserve constant
        // trims the same distance at both ends,
        // so both ends must be drawn - drawing only one would keep the other wall
        // hidden
        int thicknessY = Math.round(e.entry.reserveY * canvasScale);
        if (thicknessY > 0) {
            int top = canvasY + canvasH - thicknessY;
            context.fill(canvasX, top, canvasX + canvasW, canvasY + canvasH, RESERVE_BAND);
            context.fill(canvasX, top, canvasX + canvasW, top + 1, RESERVE_LINE);
            int bottom = canvasY + thicknessY;
            context.fill(canvasX, canvasY, canvasX + canvasW, bottom, RESERVE_BAND);
            context.fill(canvasX, bottom - 1, canvasX + canvasW, bottom, RESERVE_LINE);
        }
        // Horizontal: same formula as the vertical case
        int thicknessX = Math.round(e.entry.reserveX * canvasScale);
        if (thicknessX > 0) {
            int left = canvasX + canvasW - thicknessX;
            context.fill(left, canvasY, canvasX + canvasW, canvasY + canvasH, RESERVE_BAND);
            context.fill(left, canvasY, left + 1, canvasY + canvasH, RESERVE_LINE);
        }
    }

    /** Fallback when the backdrop image is unavailable: solid fill plus one centered hint line, instead of making players guess at a purple-black checkerboard. */
    private void drawCanvasMissing(DrawContext context) {
        context.fill(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, CHROME_BG);
        drawCanvasFrame(context);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) {
            return;
        }
        String hint = Text.translatable("nomorezombies.hudeditor.canvas.unavailable").getString();
        context.drawTextWithShadow(tr, hint,
                canvasX + (canvasW - tr.getWidth(hint)) / 2,
                canvasY + canvasH / 2 - tr.fontHeight / 2, 0xFF808088);
    }

    /**
     * The HUD samples on the canvas: boxes measured in virtual screen
     * coordinates, drawn in screen coordinates, scale = workspace scale
     * × canvas scale.
     *
     * <p><b>Only "removed from canvas" elements are skipped</b>
     * ({@code workPlaced == false}):
     * they are no longer on the canvas,
     * leaving only a ○ in the left list. And "disabled"
     * ({@code workVisible == false})
     * <b>still draws its content, with only the frame color changed</b>:
     * disabled decides only "drawn in game or not"; on the editor canvas it
     * must stay visible,
     * otherwise one click of "Show: Off" makes the element vanish into thin
     * air, impossible to find or click back.
     * Disabled or not is expressed by the red frame
     * ({@link #FRAME_COLOR_HIDDEN}).
     *
     * <p>Each sample also gets a {@link #HUD_BACKDROP} layer underneath
     * (25% black, except HUDs with their own backdrop):
     * the canvas is a fairly bright static image, white text has a mushy
     * boundary on it;
     * with a light black layer the block instantly reads as "a component".
     * Same nature as the frame - a pure editor visual layer, not persisted,
     * affects no in-game rendering.
     *
     * <p><b>anchor takes (sx, sy), not (0, 0)</b>:
     * {@link TotalHUDRenderer#drawScaled(DrawContext, int, int, float, Runnable)}'s
     * semantics are
     * "scale around the anchor" - expanded it is {@code p -> anchor + k*(p - anchor)},
     * the identity at {@code p = anchor}.
     * And the sample factory's {@code p.render.render(ctx, x, y)} convention
     * is "content drawn at (x, y)",
     * so passing the same {@code (sx, sy)} as both anchor and draw origin
     * puts the content's left edge exactly on
     * the {@link #screenBox(int[]) box}'s left edge,
     * and scaling changes only the content's own size, never its position -
     * the same pattern as in-game rendering (content drawn at absoluteX/Y,
     * then scaled around that point).
     * "translate(sx, sy) + scale(k) + render(ctx, 0, 0)" would be
     * mathematically equivalent too,
     * but <b>the lambda's coordinates must be changed to (0, 0) along with
     * it</b>;
     * changing only the matrix shoves the content off the canvas entirely.
     */
    private void renderElements(DrawContext context) {
        for (HudElement e : elements) {
            if (!e.workPlaced) {
                continue;
            }
            // The dragged one is not drawn in this layer: renderDraggedElement draws it
            // above the chrome layer.
            // The library is a 75% opaque panel drawn after the samples; from here, the
            // element would be covered the moment it enters the library,
            // and the player would see "only the mouse moving" until the library layer
            // adds the red frame at the arm point -
            // that "sudden appearance" is the reported "teleport"
            if (e == dragging) {
                continue;
            }
            drawElementBox(context, e, e.entry.preview.create(this), (float) e.workScale);
        }
    }

    /**
     * Draws one element's full block: 25% black base (skipped for HUDs with
     * their own backdrop) -> content -> frame.
     *
     * <p>Order is z-order: the base must be under the content, otherwise it
     * covers it; the frame goes last to sit on the outermost ring.
     * Extracted because the canvas layer and the drag overlay must render
     * identically -
     * two hand-copied versions guarantee the next palette or base rule change
     * touches only one of them.
     *
     * @param context draw context
     * @param e target element (decides frame tier and whether to add the base)
     * @param p this frame's sample for the element (size source)
     * @param s workspace scale
     */
    private void drawElementBox(DrawContext context, HudElement e, HudPreview p, float s) {
        int[] box = screenBox(virtualBox(e, p, s));
        int sx = box[0];
        int sy = box[1];
        int bw = box[2];
        int bh = box[3];
        if (!e.entry.ownBackground) {
            context.fill(sx, sy, sx + bw, sy + bh, HUD_BACKDROP);
        }
        TotalHUDRenderer.drawScaled(context, sx, sy, s * canvasScale,
                () -> p.render.render(context, sx, sy));
        drawFrame(context, sx, sy, bw, bh, frameColorFor(e));
    }

    /**
     * The dragged element - <b>drawn above the entire chrome layer</b>.
     *
     * <p>This layer is reserved for "the thing in hand": the element stays
     * above the library and top bar throughout,
     * so dragging left shows it sliding into the library with the pointer,
     * instead of vanishing under the library panel first and
     * popping out again at the arm point. The library's red frame and
     * "remove from canvas" hint are still drawn by
     * {@link #renderLibrary(DrawContext, TextRenderer, int, int)} on the
     * library layer
     * (that describes the "drop point", a different thing from the thing in
     * hand); the element's own red frame comes from here instead -
     * {@link #frameColorFor(HudElement)} already returns red when armed.
     */
    private void renderDraggedElement(DrawContext context) {
        if (dragging == null || !dragging.workPlaced) {
            return;
        }
        drawElementBox(context, dragging, dragging.entry.preview.create(this), (float) dragging.workScale);
    }

    /**
     * Which frame color an element uses this frame - all four states decided
     * in one place,
     * so rendering and other callers cannot develop two accountings.
     *
     * <p>Priority: drag "about to be removed" first (immediate feedback
     * outranks everything) ->
     * disabled (red) -> selected (yellow)
     * -> default (gray-white). The {@code workVisible} tested here means only
     * "drawn in game or not";
     * whether the element can reach this method at all
     * has already been filtered by the caller via {@code workPlaced}.
     *
     * @param e a workspace element on the canvas (necessarily placed)
     * @return the element's frame color as ARGB
     */
    private int frameColorFor(HudElement e) {
        if (e == dragging && draggingArmedForRemoval) {
            return FRAME_COLOR_REMOVE;
        }
        if (!e.workVisible) {
            return (e == selected) ? FRAME_COLOR_HIDDEN : FRAME_COLOR_HIDDEN_IDLE;
        }
        return (e == selected) ? FRAME_COLOR_SELECTED : FRAME_COLOR_IDLE;
    }

    /**
     * Draws a thin frame <b>outside</b> the bounds: {@link #FRAME_W}px per
     * side, inner edge exactly on
     * (x,y)-(x+bw,y+bh), i.e. the hitbox and element content rectangle
     * themselves.
     *
     * <p>Outward rather than inward, because the content's outermost ring is
     * often glyph shadow (already counted into the sample's size);
     * an inward frame would cover that 1px - it would look like the frame
     * cuts into the text. Outward, the frame occupies only the ring outside
     * the element,
     * covering no content.
     *
     * <p>Geometry changes only color with selection, never position or
     * thickness: a moving frame reads as the element itself jumping a pixel.
     */
    private static void drawFrame(DrawContext context, int x, int y, int bw, int bh, int color) {
        // Top/bottom/left/right edges: each spans the full run (corners included, so
        // adjacent sides leave no gaps)
        context.fill(x - FRAME_W, y - FRAME_W, x + bw + FRAME_W, y, color);                  // top
        context.fill(x - FRAME_W, y + bh, x + bw + FRAME_W, y + bh + FRAME_W, color);        // bottom
        context.fill(x - FRAME_W, y, x, y + bh, color);                                     // left
        context.fill(x + bw, y, x + bw + FRAME_W, y + bh, color);                            // right
    }

    /**
     * Top bar: <b>title + the top-right info icon</b> (buttons are drawn by
     * the framework; here only the base and text).
     *
     * <p>Reset/save/cancel live in the action area's last row below the
     * canvas - the three buttons are session-level actions,
     * unrelated to "which HUD am I placing",
     * and on narrow logical resolutions they eat most of the top bar (on
     * 854×480 four widgets side by side
     * take half the screen width, leaving the title nowhere to stand). The
     * selected element's X/Y readout is not here either:
     * the yellow frame on the canvas is the answer to "which one is
     * selected"; repeating X/Y only helps mid-drag,
     * yet it would fight the title and icon for one horizontal band (squeezed
     * under the icon it would skip whole stretches,
     * i.e. flicker). The top bar therefore keeps only
     * identity (title) and the single help entry (icon).
     */
    private void renderTopBar(DrawContext context, TextRenderer tr, int mouseX, int mouseY) {
        context.fill(0, 0, this.width, TOP_BAR_H, CHROME_BG);
        context.fill(0, TOP_BAR_H - 1, this.width, TOP_BAR_H, CHROME_LINE);
        context.drawTextWithShadow(tr, this.title, 8, (TOP_BAR_H - tr.fontHeight) / 2, 0xFFFFFF);

        boolean hudMasterOn = GlobalConfig.QoL.HUD_MASTER.getBooleanValue();
        // The icon is pinned top-right: it is the screen's only help entry, its position
        // must be constant -
        // with only the title left in the top bar, the icon is placed straight from the
        // margin, not derived from button widths
        iconCenterX = this.width - INFO_ICON_MARGIN - INFO_ICON_HALF;
        iconCenterY = TOP_BAR_H / 2;
        // The warning reads pendingDiscard only ("the player just tried to leave with
        // unsaved changes"), not "currently dirty"
        // An orange light during every drag amounts to asking "did you err?" each time;
        // the warning appears only when actually leaving
        renderInfoIcon(context, tr, mouseX, mouseY, hudMasterOn, pendingDiscard);
    }

    /**
     * Left library: one column, one row per item (status dot = placed/not),
     * wheel scrollable, overflow clipped.
     *
     * <p><b>No group headers</b>: most of the eleven items belong to
     * "information" anyway, four headers would cost 4×14px of height
     * for nearly zero information, and push the list into scrolling. The list
     * is therefore one column, order = registration order.
     *
     * <p>Drawn by hand rather than the vanilla {@code EntryListWidget}: drag
     * and drop requires "press in the list,
     * drag to the canvas, release inside the canvas" -
     * the whole path is owned by the editor,
     * while the vanilla list swallows {@code mouseClicked}/{@code mouseDragged},
     * and its selection semantics conflict with dragging. Colors follow the
     * vanilla list: dark base + border, hover highlight, scrollbar on the
     * right.
     *
     * <p>It doubles as the "remove from canvas" target zone: when a dragged
     * element enters this rectangle deep enough
     * (past the "30%" threshold), it draws a red border and pops a hint line
     * (see {@link #draggingArmedForRemoval}), because the library draws after
     * the elements,
     * the element sits under it -
     * that translucent panel becomes the "dragged back to the list" visual
     * feedback.
     * The hint hugs the <b>library's very bottom</b> (see the comment below).
     */
    private void renderLibrary(DrawContext context, TextRenderer tr, int mouseX, int mouseY) {
        context.fill(libX, libY, libX + libW, libY + libH, CHROME_BG);
        drawLibraryFrame(context);

        context.drawTextWithShadow(tr, Text.translatable("nomorezombies.hudeditor.library.title"),
                libX + LIB_PAD + 2, libY + LIB_PAD, 0xFFFF55);

        int listTop = libY + LIB_PAD + tr.fontHeight + 4;
        int listBottom = libY + libH - LIB_PAD;
        int listH = Math.max(1, listBottom - listTop);

        // Content height is computed and used right here: it feeds no drawing and is not
        // reused across methods,
        // so it is deliberately not a field (a fielded version would be one snapshot
        // read in several places - a stale value waiting to happen)
        int contentH = libraryContentHeight();
        libScroll = Math.clamp(libScroll, 0, Math.max(0, contentH - listH));

        context.enableScissor(libX + 1, listTop, libX + libW - 1, listBottom);
        int y = listTop - libScroll;
        for (HudElement e : elements) {
            boolean hovered = mouseX >= libX + 1 && mouseX <= libX + libW - 1 && mouseY >= y && mouseY < y + LIB_ITEM_H;
            boolean isSelected = (e == selected);
            if (hovered || isSelected) {
                context.fill(libX + 2, y, libX + libW - 2, y + LIB_ITEM_H, isSelected ? 0x40FFFF55 : 0x30FFFFFF);
            }
            String name = e.entry.name();
            // Name brightness follows "will it really draw" (on canvas AND enabled);
            // placement alone is fooled by "red-frame disabled". Map-restricted HUDs are
            // not grayed: all eleven rows should read as "usable
            // components", and "it only works on one map" is a hover-time question - that
            // explanation lives in
            // the hover tooltip; graying would just make the player think it is broken
            // (before entering AA it is supposed to be this bright)
            int nameColor = (e.workPlaced && e.workVisible) ? 0xFFFFFFFF : 0xFFB0B0B0;
            context.drawTextWithShadow(tr, name, libX + LIB_PAD + 10, y + (LIB_ITEM_H - tr.fontHeight) / 2, nameColor);
            // Status dot: ● on the canvas (green) / ○ not (gray) - it means only "placed
            // on the canvas or not";
            // the "placed but disabled" tier is shown by the red frame on the canvas
            // (another color tier in the list would only get harder to read)
            String dot = e.workPlaced ? "●" : "○";
            int dotColor = e.workPlaced ? 0xFF55FF55 : 0xFF606060;
            context.drawTextWithShadow(tr, dot, libX + LIB_PAD, y + (LIB_ITEM_H - tr.fontHeight) / 2, dotColor);
            y += LIB_ITEM_H;
        }
        context.disableScissor();

        // Scrollbar: only when content overflows, positioned by scroll ratio
        if (contentH > listH) {
            // contentH is always > listH inside the if above, so that Math.max(1, ...) is
            // redundant
            int barH = Math.max(12, listH * listH / contentH);
            int barY = listTop + (listH - barH) * libScroll / (contentH - listH);
            context.fill(libX + libW - 4, barY, libX + libW - 2, barY + barH, 0x80FFFFFF);
        }

        // Dragging has pushed the element into the library: red border around the whole
        // library + a red-backed hint at its bottom saying "release removes it"
        // The element itself is drawn by the renderDraggedElement layer (red frame
        // included); this only describes the drop point
        if (draggingArmedForRemoval) {
            context.drawBorder(libX, libY, libW, libH, FRAME_COLOR_REMOVE);
            String hint = Text.translatable("nomorezombies.hudeditor.remove").getString();
            // Hugging the library's bottom inner edge (right over the bottom border): it
            // says "drop the thing in hand here",
            // best placed on the edge nearest the pointer - anchored under the title it
            // would read as "the library's subtitle"
            // No height is reserved for it: reserving would shift the whole list
            // mid-drag, row positions hopping with the pointer
            int hintY = libY + libH - 2 - tr.fontHeight;
            // A dark red strip under it: the line sits over list rows, and without a
            // backing it blurs into the entry text
            context.fill(libX + 1, hintY - 1, libX + libW - 1, hintY + tr.fontHeight + 1, 0xCC2A0A0A);
            context.drawTextWithShadow(tr, hint,
                    libX + (libW - tr.getWidth(hint)) / 2, hintY, FRAME_COLOR_REMOVE);
        }
    }

    /**
     * The library's outer frame - <b>only the left, right and bottom sides;
     * the top edge is deliberately skipped</b>.
     *
     * <p>The top line is saved: that row is already taken by the top bar
     * separator
     * (the full-width line at {@code TOP_BAR_H - 1} in
     * {@link #renderTopBar(DrawContext, TextRenderer, int, int)}
     * ) and the canvas's top border ({@code canvasY-1} in
     * {@link #drawCanvasFrame(DrawContext)}),
     * all three in the same color
     * {@link CHROME_BORDER}. If the library drew all four sides via
     * {@code drawBorder},
     * it would stack another line on the {@code TOP_BAR_H}
     * row - the only 2px-thick line in the UI, right at the library/top bar
     * seam,
     * visibly inconsistent with everything else at a glance. The top edge
     * <b>shares</b> the top bar's row:
     * the seam of the three chrome blocks is one line, 1px everywhere.
     *
     * <p>The other three sides each have their own footing, none redundant:
     * the right column {@code libW-1} doubles as the canvas's left border
     * column when width-limited
     * (canvas flush top-right and filling the right zone gives
     * {@code canvasX = libW}),
     * the bottom lands on the last on-screen row,
     * the left at {@code x=0}. When height-limited the canvas cannot shift
     * left that far,
     * its left border lands on the plate left of it,
     * and this column is just the library's own right edge.
     *
     * <p>The red "removal armed" ring does not go through here - it is a
     * few-frame momentary warning,
     * all four sides lit is what reads; it stays with the {@code drawBorder}
     * in {@link #renderLibrary(DrawContext, TextRenderer, int, int)}
     */
    private void drawLibraryFrame(DrawContext context) {
        context.fill(libX, libY, libX + 1, libY + libH, CHROME_BORDER);
        context.fill(libX + libW - 1, libY, libX + libW, libY + libH, CHROME_BORDER);
        context.fill(libX, libY + libH - 1, libX + libW, libY + libH, CHROME_BORDER);
    }

    /** Library content total height (one row per item), for scrolling and clamping. */
    private int libraryContentHeight() {
        return elements.size() * LIB_ITEM_H;
    }

    /**
     * Draws the top-right info/warning icon - a square base with one border
     * ring and one centered glyph, three states:
     * <ul>
     *  <li><b>Unsaved warning</b> (orange base + {@code ⚠}) - highest
     *  priority;
     * driven by {@link #pendingDiscard},
     *  i.e. <b>lit only in the instant the player just tried to leave with
     *  changes</b> (
     * see {@link #requestClose()}),
     *  not "lit as long as anything is changed";</li>
     *  <li>HUD master switch off (orange base + {@code ⚠}) - second;</li>
     *  <li>otherwise (blue base + {@code ℹ})</li>
     * </ul>
     * The latter two reuse the same orange/blue palette, no new constants:
     * orange already means "something needs your attention" in this screen.
     *
     * @param warn whether the "unsaved exit" warning state is active (callers
     *             pass {@link #pendingDiscard})
     */
    private void renderInfoIcon(DrawContext context, TextRenderer tr, int mouseX, int mouseY,
                                boolean hudMasterOn, boolean warn) {
        int half = INFO_ICON_HALF;
        int x0 = iconCenterX - half;
        int y0 = iconCenterY - half;
        int x1 = iconCenterX + half;
        int y1 = iconCenterY + half;

        iconHovered = mouseX >= x0 && mouseX <= x1 && mouseY >= y0 && mouseY <= y1;

        // Blue = all normal; orange = something to say (unsaved warning outranks master
        // switch off)
        boolean orange = warn || !hudMasterOn;
        int bgAlpha = iconHovered ? 0xCC : 0x88;
        int bgColor = !orange
                ? (bgAlpha << 24 | 0x1A3A6A)
                : (bgAlpha << 24 | 0x5A2A00);
        int borderColor = !orange ? 0xFF4A9EFF : 0xFFFFAA00;

        context.fill(x0, y0, x1, y1, borderColor);
        context.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bgColor);

        String icon = orange ? "⚠" : "ℹ";
        int iconColor = orange ? 0xFFFFDD44 : 0xFFCCE5FF;
        context.drawTextWithShadow(tr, icon, iconCenterX - tr.getWidth(icon) / 2, iconCenterY - tr.fontHeight / 2, iconColor);
    }

    /**
     * Multi-line help drawn while hovering the info/warning icon - <b>the
     * only home for all usage instructions</b>
     * (there is no footer strip at the screen's bottom, see
     * {@link #layout()}); with the HUD master switch off, a warning heads the
     * first line.
     *
     * <p>The "select a HUD to configure" line for the unselected state
     * ({@code hudeditor.action.none}) lives here too:
     * the action area reserves no row for it (see {@link #ACTION_ROWS}),
     * so this hover tooltip becomes its only home -
     * "what should I do first" is exactly when the player comes asking the ℹ.
     *
     * <p><b>Two paragraphs, each its own block</b>: first "how to start"
     * (unsaved warning + master switch warning
     * + unselected hint +
     * the three basics: drag/scale/reset), then a blank line,
     * then "what else is possible" (drag out of the list, resize via the
     * right edge,
     * right-click, drag back to remove...).
     * As one long run, the vanilla tooltip would stretch to half the screen
     * width on the longest line (it sizes by the longest line;
     * the positioner only moves the box back on screen, it cannot cap the
     * width itself);
     * with the blank line each line is short, and "basics"
     * vs "advanced" reads cleanly. The blank line is {@link Text#empty()} -
     * in a tooltip it is exactly one empty row.
     *
     * <p><b>The two warning lines must come first</b>: they say "what Esc
     * will do right now",
     * and {@code warnFirst} yellows only the <b>first</b> line - so the
     * unsaved warning precedes the master switch warning.
     *
     * @param warn whether the "unsaved exit" warning state is active (callers
     *             pass {@link #pendingDiscard})
     */
    private void renderIconTooltip(DrawContext context, TextRenderer tr, int mouseX, int mouseY,
                                   boolean hudMasterOn, boolean warn) {
        List<Text> lines = new ArrayList<>();
        // Unsaved warning first: of the two, it is the only one where "Esc right now
        // would actually be intercepted"
        // (warnFirst yellows only the first line, so the order must be unsaved ->
        // master)
        if (warn) {
            lines.add(Text.translatable("nomorezombies.hudeditor.warn.unsaved"));
        }
        if (!hudMasterOn) {
            lines.add(Text.translatable("nomorezombies.hudeditor.warn.master"));
        }
        // First block: how to start + the three basics (one line each, no long sentences
        // stretching across half the screen)
        lines.add(Text.translatable("nomorezombies.hudeditor.action.none"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.drag"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.scale"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.reset"));
        // Blank line: above is "how to use it", below "what else is possible"; the two
        // blocks each stand alone
        lines.add(Text.empty());
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.drop"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.extra.remove"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.extra.click"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.extra.library"));
        renderVanillaTooltip(context, tr, lines, null, mouseX, mouseY,
                HoveredTooltipPositioner.INSTANCE, warn || !hudMasterOn);
    }

    /**
     * Name tooltip following the mouse while hovering a canvas element - the
     * name line only.
     *
     * <p>State is not reported here: being able to hover it proves it is
     * already on the canvas,
     * and "drawn in game or not" is said directly by the <b>red frame</b> on
     * the canvas
     * (see {@link #frameColorFor(HudElement)}); another line would be a
     * repeat.
     */
    private void renderHoverTooltip(DrawContext context, TextRenderer tr, int mouseX, int mouseY, HudElement e) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(e.entry.name()));
        renderVanillaTooltip(context, tr, lines, null, mouseX, mouseY, HoveredTooltipPositioner.INSTANCE, false);
    }

    /**
     * Tooltip while hovering a library row: name + (when map-restricted) one
     * explanation line +
     * <b>what this HUD looks like</b>.
     *
     * <p>The preview is the whole reason this row exists: the list's eleven
     * rows carry only names,
     * and from "Wave Timer"
     * "Global Overview" a player (especially on a fresh install) cannot
     * picture the shapes - the only alternative is dragging each onto the
     * canvas to see, then discovering it is not the wanted one, and removing
     * it again.
     * Seeing it right in the list is the entire point of this hover box.
     *
     * <p><b>No "on canvas / in game" status lines anymore</b>:
     * both are narration of things already visible - "on the canvas or not"
     * is said by the row's leading ●/○, "drawn in game or not" by the canvas's
     * red frame;
     * writing them into the tooltip just turns one line into three,
     * pushing down the sample the player actually came to see. The full blank
     * line between text and sample is kept
     * (see {@link #renderVanillaTooltip(DrawContext, TextRenderer, List, HudPreview, int, int, TooltipPositioner, boolean)})
     *
     * <p><b>The map restriction line is the only second line kept</b>:
     * it is the only explanation of "why this HUD does not appear elsewhere",
     * and only asked while hovering. The criterion is
     * {@link HudEntry#isMapRestricted()} rather than
     * {@code isAvailable(getMap())} - the latter returns true while the player
     * stands inside AA,
     * making the explanation vanish at the moment it is needed most.
     */
    private void renderLibraryTooltip(DrawContext context, TextRenderer tr, int mouseX, int mouseY, HudElement e) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(e.entry.name()));
        if (e.entry.isMapRestricted()) {
            lines.add(Text.translatable("nomorezombies.hudeditor.preview.restricted.aa")
                    .withColor(FONT_ORANGE));
        }
        // The tooltip's sample is the same shape as the canvas's: both use this editor
        // as the
        // PreviewContext (it provides workspaceActive); there is no "full version for
        // the tooltip" branch anymore
        HudPreview preview = e.entry.preview.create(this);
        renderVanillaTooltip(context, tr, lines, preview, mouseX, mouseY,
                HoveredTooltipPositioner.INSTANCE, false);
    }

    /**
     * Draws a <b>vanilla-looking</b> tooltip: the vanilla nine-patch backdrop
     * + the same line pitch +
     * the same default positioner.
     *
     * <p><b>Why assembled by hand instead of using
     * {@code DrawContext.drawTooltip}</b>:
     * the vanilla public overload family takes text only,
     * the overload taking {@code List<TooltipComponent>} is private,
     * and {@code TooltipComponent.of(TooltipData)} accepts only Bundles/Profiles
     * internal data:
     * <b>an image cannot be pushed into the vanilla tooltip pipeline</b>.
     * Fortunately {@link TooltipBackgroundRenderer#render(DrawContext,int,int,int,
     * int,int,Identifier)}
     * and {@link HoveredTooltipPositioner#INSTANCE}
     * are both public, so this replicates the same layout by hand: width from
     * the widest line,
     * row height 10px per vanilla {@code OrderedTextTooltipComponent}, 2px
     * extra after the first line,
     * then the vanilla positioner decides the spot. The look therefore
     * matches any vanilla screen,
     * the only difference being that a HUD sample can hang at the end.
     *
     * <p>This method is <b>the editor's only tooltip outlet</b>: the info
     * icon's help,
     * the library preview, canvas element state,
     * the drag name - all four go through it. Keeping the styling in one
     * place is the only way nothing gets forgotten at the next change.
     *
     * @param context draw context
     * @param tr text renderer
     * @param lines text lines (at least one; an empty list with no sample
     *              returns immediately)
     * @param preview the sample to append at the end ({@code null} =
     *                text-only tooltip)
     * @param mouseX cursor X (input to the vanilla positioner, not the final
     *               spot)
     * @param mouseY cursor Y
     * @param positioner hover uses vanilla {@link HoveredTooltipPositioner},
     *  dragging uses the bottom-right one
     * @param warnFirst whether the first line is yellow (the master-switch
     *                  warning)
     */
    private void renderVanillaTooltip(DrawContext context, TextRenderer tr, List<Text> lines,
                                      HudPreview preview, int mouseX, int mouseY,
                                      TooltipPositioner positioner, boolean warnFirst) {
        if (lines.isEmpty() && preview == null) {
            return;
        }
        // Layout follows vanilla: single line is 10-2=8, each line 10px, 2px extra
        // after the first line
        int rows = lines.size() + (preview == null ? 0 : 1);
        int width = 0;
        int height = (rows == 1) ? -2 : 0;
        for (Text line : lines) {
            width = Math.max(width, tr.getWidth(line));
            height += TOOLTIP_LINE_H;
        }

        // Sample scaled proportionally into the box: 1:1 preferred (that is its true
        // on-screen size), shrinking only past the caps.
        // A full blank line to the text (TOOLTIP_PREVIEW_GAP): hugging the text reads
        // like "an illustration of the line
        // above"; with the gap each part stands alone, and the player can see what this
        // HUD actually looks like
        float previewScale = 1.0f;
        // The two sizes are meaningless when preview == null, and the two reads below
        // sit under the same guard;
        // the 0 initial values just keep the "no sample drawn" path reading a
        // deterministic pair
        int previewW = 0;
        int previewH = 0;
        if (preview != null) {
            previewScale = Math.min(1.0f, Math.min(
                    (float) PREVIEW_MAX_W / Math.max(1, preview.width),
                    (float) PREVIEW_MAX_H / Math.max(1, preview.height)));
            previewW = Math.max(1, Math.round(preview.width * previewScale));
            previewH = Math.max(1, Math.round(preview.height * previewScale));
            width = Math.max(width, previewW);
            // The blank line counts only when there is real text: a sample-only tooltip
            // gains no phantom whitespace
            height += (lines.isEmpty() ? 0 : TOOLTIP_PREVIEW_GAP) + previewH;
        }
        if (height < 1) {
            height = TOOLTIP_LINE_H;
        }

        Vector2ic pos = positioner.getPosition(this.width, this.height, mouseX, mouseY, width, height);
        int tx = pos.x();
        int ty = pos.y();

        // Vanilla backdrop + border (it carries 12px of outward padding, so the row math
        // above adds no inner padding of its own)
        TooltipBackgroundRenderer.render(context, tx, ty, width, height, 400, null);

        // Vanilla tooltips draw at Z+400: HUD samples write depth in this layer, without
        // the lift the depth test would hide them
        context.getMatrices().push();
        context.getMatrices().translate(0.0f, 0.0f, 400.0f);
        int cursorY = ty;
        for (int i = 0; i < lines.size(); i++) {
            context.drawTextWithShadow(tr, lines.get(i).getString(), tx, cursorY,
                    (warnFirst && i == 0) ? 0xFFFF55 : 0xFFFFFF);
            cursorY += TOOLTIP_LINE_H + (i == 0 ? 2 : 0);
        }
        if (preview != null) {
            int px = tx + (width - previewW) / 2;
            // Same accounting as the height budget above: one blank line, then the sample
            // (see TOOLTIP_PREVIEW_GAP)
            int py = cursorY + (lines.isEmpty() ? 0 : TOOLTIP_PREVIEW_GAP);
            if (previewScale < 1.0f) {
                // The scaled sample draws on its own top-left anchor, matching the canvas's
                // anchor convention
                TotalHUDRenderer.drawScaled(context, px, py, previewScale,
                        () -> preview.render.render(context, px, py));
            } else {
                preview.render.render(context, px, py);
            }
        }
        context.getMatrices().pop();
    }

    /**
     * Drop-target ghost while dragging out of the library - <b>it draws this
     * HUD's real content</b>, not an empty box.
     *
     * <p>The ghost box shows real content: the library's eleven rows are
     * names only,
     * and names do not match shapes ("Global Overview", "AA Commander"
     * cannot be pictured from the name alone). An empty box only tells the
     * player "it will occupy this much space", while what the player is
     * really deciding is
     * "does this block look good at that position" - undecidable without
     * seeing the content.
     * So the same painting as on the canvas:
     * 25% black base (skipped for HUDs with their own backdrop) -> sample
     * content -> the frame, sized to the element's real screen box
     * ({@code workScale × canvasScale}), so "how big it will be in there" is
     * visible right now.
     *
     * <p>The frame keeps its two tiers, meaning consistent with the canvas:
     * <b>placeable</b> (inside the canvas <b>or</b> over the action area
     * below it,
     * see {@link #canPlaceAt(double, double)}) yellow, otherwise gray-white.
     * The name goes to the tooltip pinned bottom-right of the cursor
     * (see {@link #renderDragNameTooltip(DrawContext,TextRenderer,HudElement,int,
     * int)}),
     * no second copy here - hence no {@code TextRenderer} parameter:
     * drawing content needs no font, and taking one would be an unread
     * parameter.
     *
     * @param context draw context
     */
    private void renderDragGhost(DrawContext context) {
        int[] box = elementScreenBox(draggingFromLibrary);
        int w = box[2];
        int h = box[3];
        int x = (int) ghostMouseX - w / 2;
        int y = (int) ghostMouseY - h / 2;
        boolean placeable = canPlaceAt(ghostMouseX, ghostMouseY);

        float drawScale = (float) draggingFromLibrary.workScale * canvasScale;
        HudPreview p = draggingFromLibrary.entry.preview.create(this);
        if (!draggingFromLibrary.entry.ownBackground) {
            context.fill(x, y, x + w, y + h, HUD_BACKDROP);
        }
        TotalHUDRenderer.drawScaled(context, x, y, drawScale,
                () -> p.render.render(context, x, y));
        drawFrame(context, x, y, w, h, placeable ? FRAME_COLOR_SELECTED : FRAME_COLOR_IDLE);
    }

    // ----Widget placement----

    /**
     * Lays out the widgets on the action area's two rows (plus a HUD's own
     * extra rows).
     *
     * <p><b>Row 0 (HUD widgets)</b>: the two buttons "Show:
     * On/Off" and "Remove from canvas/Place" + the scale slider,
     * <b>three equal columns</b> (content box split in thirds). Both buttons
     * and the slider <b>always exist</b>; without a selection they only get
     * {@code active=false} (the framework draws the disabled state gray) -
     * left out of the child list, the player could not read "a button belongs
     * here", and the slider would look intermittent.
     * HUDs have no "default position" concept,
     * placing always lands at the canvas center (see
     * {@link #placeAtCenter(HudElement)}),
     * so there is no "default position" button.
     *
     * <p><b>Last row (session buttons)</b>: reset/save/cancel, using the
     * <b>same thirds formula</b> as row 0,
     * so both rows align at their edges, six equal columns - the whole action
     * area a tidy 3×2 grid.
     * They are session-level actions, unrelated to "which HUD am I placing",
     * hence the bottom row, farthest from the canvas.
     *
     * <p>All widths derive from {@code actionW} (nothing hard-coded): a
     * narrow action area narrows buttons and slider together,
     * <b>always equally wide and aligned</b>,
     * never poking outside the action area - once a button's frame and its
     * text compute from different origins,
     * a narrow resolution shows "text floating outside the button".
     *
     * <p>{@link #layout()} runs first: this method reads
     * {@code actionX/actionY/actionW},
     * and it can be invoked before {@code layout()} would otherwise run (for
     * example on selection changes);
     * without the geometry pass it would use last frame's stale values.
     */
    private void layoutWidgets() {
        layout();
        this.clearChildren();

        // Row 0: two HUD buttons + the scale slider, one content-box column each (same
        // formula as the last row's session buttons)
        // This line and sessionW below are the same thirds formula + [MIN,MAX] clamping,
        // the Math.max/Math.min spelling is kept, not swapped for the equivalent
        // Math.clamp
        int hudBtnW = Math.max(MIN_BTN_W, Math.min(BTN_W, (actionW - BTN_GAP * 2) / 3));
        int row0Y = actionRow(0) + rowWidgetOffset();
        visibleToggleButton.setWidth(hudBtnW);
        visibleToggleButton.setPosition(actionX, row0Y);
        placeToggleButton.setWidth(hudBtnW);
        placeToggleButton.setPosition(actionX + hudBtnW + BTN_GAP, row0Y);
        // Both buttons' labels and enabled states refresh together (the single writer,
        // see refreshActionLabels)
        refreshActionLabels();
        this.addDrawableChild(visibleToggleButton);
        this.addDrawableChild(placeToggleButton);

        // The slider matches a single button's width (the literal reading of "in sync
        // with the buttons"): three equal columns, right edge on the content box's right
        // edge
        // sliderW must stay bit-identical to hudBtnW; do not inline it away
        int sliderX = actionX + hudBtnW * 2 + BTN_GAP * 2;
        int sliderW = hudBtnW;
        // The slider field is reused (vanilla SliderWidget carries hover/drag state that
        // a rebuild would drop),
        // only position and width change when geometry changes
        if (scaleSlider == null) {
            scaleSlider = new ScaleSliderWidget(sliderX, row0Y, sliderW, WIDGET_H, this::onSliderChanged);
        } else {
            scaleSlider.setPosition(sliderX, row0Y);
            scaleSlider.setWidth(sliderW);
        }
        // Always attached: with no selection it is just a gray bar (active=false), so
        // the scale feature never looks intermittent
        // It shares row 0 and the same width tier as the two buttons; attaching or not
        // changes no row's position,
        // so selecting/deselecting never makes the UI jump
        this.addDrawableChild(scaleSlider);
        if (selected != null) {
            scaleSlider.active = true;
            scaleSlider.setScaleValue(selected.workScale);
        } else {
            scaleSlider.active = false;
            // Reset the readout to the default scale (1.00) when nothing is selected,
            // so no stale value from the previous HUD lingers
            scaleSlider.setScaleValue(1.0);
        }

        // The HUD's own extra config (currently unused; see HudEntry.extraConfigWidgets)
        // -
        // stacked between the HUD widget row and the session button row, full content
        // box width
        int extraY = actionRow(1);
        if (selected != null) {
            for (ClickableWidget widget : selected.entry.extraWidgets()) {
                widget.setPosition(actionX, extraY + rowWidgetOffset());
                widget.setWidth(actionW);
                widget.active = true;
                this.addDrawableChild(widget);
                extraY += ROW_H;
            }
        }

        // Last row: reset/save/cancel, the group left-aligned to the content box's left
        // edge (same columns as the two buttons above)
        // This row's formula and sessionW / sessionX below are the same thirds formula
        // and clamping,
        // the Math.max/Math.min spelling is kept
        int sessionW = Math.max(MIN_BTN_W, Math.min(BTN_W, (actionW - BTN_GAP * 2) / 3));
        int sessionTotal = sessionW * 3 + BTN_GAP * 2;
        // On extremely narrow screens (320×180-class logical sizes, with the library at
        // its floor) the three buttons' floor widths together
        // exceed the content box, so the left-aligned group sticks out a few pixels
        // Keep the screen boundary then: the content box is only a "follows the canvas"
        // convention; poking off screen is far worse
        int sessionX = actionX;
        sessionX = Math.max(0, Math.min(sessionX, this.width - sessionTotal));
        int sessionY = actionRow(ACTION_ROWS - 1 + extraActionRows()) + rowWidgetOffset();
        resetButton.setWidth(sessionW);
        resetButton.setPosition(sessionX, sessionY);
        saveButton.setWidth(sessionW);
        saveButton.setPosition(sessionX + sessionW + BTN_GAP, sessionY);
        cancelButton.setWidth(sessionW);
        cancelButton.setPosition(sessionX + (sessionW + BTN_GAP) * 2, sessionY);
        this.addDrawableChild(resetButton);
        this.addDrawableChild(saveButton);
        this.addDrawableChild(cancelButton);
    }

    /**
     * Rows in the action area taken by "a HUD's own extra config" - the
     * session button row must yield below them
     * (see {@link #layoutWidgets()})
     *
     * <p>Same "registry maximum" accounting as the {@code actionH} budget in
     * {@link #layout()}:
     * computed independently, the two would produce "one row reserved, but
     * buttons laid out past the reservation".
     * {@link #layout()} computes the value each frame into
     * {@link #actionExtraRows},
     * this method only reads it out.
     *
     * @return rows in the action area taken by extra config
     */
    private int extraActionRows() {
        return actionExtraRows;
    }

    // ----Input----

    /**
     * Mouse press: widgets first; then the library (select / start dragging
     * out / right-click place or remove);
     * then the library's right edge (resize); finally canvas elements (select
     * / start dragging / right-click toggle).
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Library right edge: grab -> resize
        if (Math.abs(mouseX - (libX + libW)) <= SPLIT_GRAB && mouseY >= libY && mouseY <= libY + libH) {
            draggingSplitter = true;
            return true;
        }

        HudElement libHit = libraryItemAt(mouseX, mouseY);
        if (libHit != null) {
            if (button == 0) {
                selected = libHit;
                // A click only selects: record the press point and element; only a
                // mouseDragged past the threshold promotes it to a drag
                // Entering drag directly would teleport the element to the pointer center
                // on a mere select-click, changing the layout
                // Double-clicking a list item does not place: placement always lands at the
                // canvas center, and one accidental trigger moves the element away - not
                // worth it
                pendingLibraryDragElement = libHit;
                pendingLibraryDragOriginX = mouseX;
                pendingLibraryDragOriginY = mouseY;
                layoutWidgets();
                return true;
            }
            if (button == 1) {
                // List right-click = place/remove: it sits right next to the ●/○ status
                // dot, and changes "on the canvas or not"
                // (enable/disable belongs to canvas elements: toggling it on an off-canvas
                // element shows no visible change)
                selected = libHit;
                togglePlaced();
                layoutWidgets();
                return true;
            }
        }

        HudElement canvasHit = overChrome(mouseX, mouseY) ? null : elementAt(mouseX, mouseY);
        if (canvasHit != null && insideCanvas(mouseX, mouseY)) {
            boolean wasSelected = (selected == canvasHit);
            selected = canvasHit;
            draggingArmedForRemoval = false;
            if (button == 0) {
                // Solve the element box from ratios first: dragging is not set yet at this
                // point, so the box comes from the
                // workX/workY single source of truth and never reads the previous frame's
                // leftover free position
                int[] v = virtualBox(canvasHit, canvasHit.entry.preview.create(this), (float) canvasHit.workScale);
                dragOffsetX = toVirtualX(mouseX) - v[0];
                dragOffsetY = toVirtualY(mouseY) - v[1];
                // The free position starts at this box: the grab offset then matches the
                // visible box exactly, and the element does not move on press
                draggingPx = v[0];
                draggingPy = v[1];
                dragStartPx = v[0];
                dragStartPy = v[1];
                dragging = canvasHit;
            } else if (button == 1) {
                toggleVisible();
            }
            if (!wasSelected) layoutWidgets();
            return true;
        }

        // Click on empty space: deselect (the two action-area buttons turn disabled, the
        // slider grays out but stays in place)
        if (selected != null) {
            selected = null;
            layoutWidgets();
        }
        dragging = null;
        draggingFromLibrary = null;
        pendingLibraryDragElement = null;
        return false;
    }

    /**
     * Dragging: out of the library updates the ghost box; on-canvas dragging
     * updates the element's free position and tests for removal.
     *
     * <p>Mouse coordinates must first be inverted back to virtual screen via
     * {@link #toVirtualX(double)}/{@link #toVirtualY(double)};
     * the free position and the release collapse both run in that space - so
     * the ratio stored in the workspace
     * is the same number the in-game rendering uses, canvas scale takes no
     * part in storage, and resolution/GUI scale changes never shift
     * positions.
     *
     * <p><b>The drag region is "library column ∪ canvas", two side-by-side
     * rectangles with collision bounds on all four edges</b>:
     * the horizontal floor is whichever is farther left of the "library left
     * edge" and the "reach the arm point needs"
     * (narrow elements sit flush against the library's left edge, never
     * trapped beyond it; only elements too wide to reach the drop point yield
     * more);
     * the ceiling splits in two by "touching the canvas or not" - above the
     * canvas's bottom edge the canvas travel's right bound applies (it can
     * cross that edge),
     * below it the bound collapses to the library's right edge (to the right
     * of that stretch there is only plate - a wall). The test uses the
     * previous frame's free position,
     * the reason is in that comment in the method body.
     * Vertically it picks one of two by "does the element <b>whole</b> fit
     * into the library column" - fitting gives the library travel
     * (can drop to the library's bottom), otherwise the canvas travel (so the
     * canvas's bottom edge is a wall at any x);
     * the library top also keeps the same 1px frame slot as the canvas, so
     * the same element tops out at the same height on both sides,
     * and dragging across the top never steps.
     * <b>The loosened bounds last only during the drag</b>: the landing point
     * is a 0~1 anchor ratio, and the collapse path is untouched.
     *
     * <p><b>Horizontal 1:1 tracking and "not trapped" are the same floor</b>:
     * the floor is the "just enough" minimum above - narrow elements hug the
     * library's left edge (not trapped, not clamped to the library's right
     * side),
     * only wide ones yield more to reach the drop point.
     *
     * <p>The element's position goes into the {@link #draggingPx}/
     * {@link #draggingPy} free position,
     * <b>never</b> into {@link HudElement#workX}/{@link HudElement#workY}:
     * those two fields are {@code 0~1} anchor ratios that {@link #pxFrom}
     * always resolves inside the canvas travel,
     * unable to express "the element is already out of the canvas". On
     * release {@link #clampWorkIntoCanvas(HudElement)} or
     * {@link #snapBackToLeadingEdge(HudElement, double)} collapses it into a
     * ratio and places it.
     *
     * <p>The free position itself <b>does not set</b> {@code geometryModified}:
     * mid-drag the landing spot is still undecided,
     * the release step decides (remove = placed flip, under 30% = leading
     * edge, see
     * {@link #mouseReleased(double, double, int)})
     *
     * <p>The arm criterion {@link #draggingArmedForRemoval} measures whether
     * <b>the element itself</b> is 30% past the library's right edge - the
     * same accounting as the element following the pointer.
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingSplitter) {
            workLibWidth = clampLibWidth(mouseX);
            layout();
            layoutWidgets();
            return true;
        }
        // After a library press, wait for the travel to pass the threshold: below it this
        // is still a "click" and moves nothing
        if (pendingLibraryDragElement != null) {
            double dist = Math.hypot(mouseX - pendingLibraryDragOriginX, mouseY - pendingLibraryDragOriginY);
            if (dist >= DRAG_THRESHOLD) {
                draggingFromLibrary = pendingLibraryDragElement;
                pendingLibraryDragElement = null;
                ghostMouseX = mouseX;
                ghostMouseY = mouseY;
            }
            return true;
        }
        if (draggingFromLibrary != null) {
            ghostMouseX = mouseX;
            ghostMouseY = mouseY;
            return true;
        }
        if (dragging != null) {
            HudPreview p = dragging.entry.preview.create(this);
            float s = (float) dragging.workScale;
            int bw = TotalHUDRenderer.visibleSize(p.width, s);
            int bh = TotalHUDRenderer.visibleSize(p.height, s);

            // --Horizontal: stop flush with the library's left edge; only elements "too wide
            // to reach the drop point" yield more--
            // The floor takes the one that is <b>just enough</b>:
            //   - the library's left edge toVirtualX(libX) - with the element's left edge
            //     level with the library's left edge (the screen's left edge),
            //     the boundary at which the element could slide wholly off screen sits
            //     exactly at the canvas area's left exit, so a wall must stand there
            //     looking out from the canvas's left side;
            //   - but the arm point (left edge + bw*0.3 past the library's right edge)
            //     must stay reachable, so the floor must also
            //     yield "the arm point's worth": library right edge - bw*0.3
            // Whichever is farther left wins. For a narrow element the "arm point's worth"
            // already sits right of the library's left edge, so the floor is the library's
            // left edge
            // (flush, not trapped); only elements wide enough that 0.3bw > library width
            // yield more -
            // that is the only position from which it reaches the drop point, and its
            // right edge still shows on screen
            double libLeftPx = toVirtualX(libX);
            double libRightPx = toVirtualX(libX + libW);
            double armingPx = libRightPx - bw * REMOVE_ARM_ELEMENT_FRACTION;
            double minPx = Math.min(libLeftPx, armingPx);
            double desiredPx = toVirtualX(mouseX) - dragOffsetX;
            double desiredPy = toVirtualY(mouseY) - dragOffsetY;

            // --Right bound splits in two by "touching the canvas or not": the upper stretch
            // can be crossed, below the bottom edge is a wall--
            // The library's right edge (x = libX + libW) neighbors the canvas only on its
            // upper stretch - the shared height of the two blocks
            // is the canvas's own stretch (libY = canvasY, above the canvas's bottom
            // edge); below the canvas's bottom edge that stretch
            // has nothing to its right, only plate, so there the edge <b>is just a
            // wall</b>: the element's right edge may not cross it.
            //
            // The test takes the <b>previous frame's</b> free position, not this frame's
            // desired position: while the element is still being dragged down inside the
            // canvas,
            // "this frame's desired position" already reaches below the canvas's bottom
            // edge; collapsing the right bound on that basis would yank the whole element
            // into the library
            // (a sideways jump of its own), when the right thing is to let the canvas's
            // bottom edge stop it. With the previous frame, only
            // an element that "has already dropped into the library column" meets this
            // wall, and as it drops its right edge is already inside the column -
            // so neither direction sees a jump
            double libBottomPx = toVirtualY(libY + libH);
            double canvasBottomPx = toVirtualY(canvasY + canvasH);
            boolean belowCanvas = draggingPy + bh > canvasBottomPx;
            // The library right-edge stretch also keeps the same 1px frame slot as the
            // canvas (FRAME_W/canvasScale):
            // without it, an element stopping against the edge paints its outer ring on
            // the column <b>outside</b> the library's right edge -
            // reading as "the component pokes 1px past here".
            // Same deal as the minBoxX amount: the frame is a screen-pixel quantity, so
            // divide by the scale back into virtual coordinates
            double libBoundPx = libRightPx - FRAME_W / Math.max(0.0001, canvasScale) - bw;
            // An element that cannot fit the library column (wider than the library) has
            // no business below the canvas's bottom edge either: that tier does not apply,
            // it keeps the canvas travel (its right edge governed by maxBoxX, its bottom
            // by the canvas wall)
            boolean inLibrarySlot = belowCanvas && libBoundPx >= minPx;
            double maxPx = inLibrarySlot ? libBoundPx : maxBoxX(bw, dragging.entry);
            double targetPx = clampRange(desiredPx, minPx, maxPx);

            // --Vertical: only an element wholly inside the library column earns the
            // library's taller travel--
            // Library and canvas are <b>side by side</b> (library left, canvas right), and
            // the library is taller than the canvas:
            //   - wholly inside the column (x + bw <= library right edge) -> <b>library
            //     travel</b>: can drop all the way to the library's bottom;
            //   - the rest (element reaching into the canvas, or wide enough to cross the
            //     library's right edge) -> <b>canvas travel</b>:
            //     top/bottom/right are all the canvas's own walls, <b>so the canvas's
            //     bottom edge is a wall at any x</b>
            //
            // The test is "fits", not "left edge past some line": judging by the left edge
            // alone, a wide element
            // with its left edge probing into the column but most of its body over the
            // canvas would still earn the library's full height,
            // and could poke out of that "nobody's land" below the canvas's bottom edge
            // and right of the library's right edge -
            // exactly the field-reported "element can escape through the region where
            // library and canvas do not join on the right".
            // It pairs with the right bound above: below the bottom edge only the library
            // column is allowed, and in the column it may only descend to the library's
            // bottom
            boolean inLibraryColumn = targetPx + bw <= libRightPx;
            double minPy = inLibraryColumn
                    ? toVirtualY(libY) + FRAME_W / Math.max(0.0001, canvasScale)
                    : minBoxY(dragging.entry);
            double maxPy = inLibraryColumn
                    ? libBottomPx - FRAME_W / Math.max(0.0001, canvasScale) - bh
                    : maxBoxY(bh, dragging.entry);
            double targetPy = clampRange(desiredPy, minPy, maxPy);

            draggingPx = targetPx;
            draggingPy = targetPy;
            // Arming looks only at how far the element itself is past the library's right
            // edge: a separate concern from the vertical switch (the former decides "will
            // release remove it",
            // the latter decides "where can it land this frame"), computed once here, both
            // consumers share the boolean
            draggingArmedForRemoval = elementArmedForRemoval(draggingPx, bw);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    /**
     * Whether the element's own left edge has reached into the library
     * rectangle (screen coordinates).
     *
     * <p>Horizontal only: the element's vertical travel is clamped inside the
     * canvas travel, and the canvas sits within the library's vertical band
     * ({@code libY = canvasY = TOP_BAR_H}, the canvas height also minus the
     * action area), so the element is always within the library's vertical
     * range - a vertical test would be a constant true.
     *
     * @param freeX virtual screen x of the element's left edge
     * @return {@code true} when the element horizontally intersects the
     *         library rectangle
     */
    private boolean elementReachesLibrary(double freeX) {
        return elementLeftScreenX(freeX) < libX + libW;
    }

    /**
     * The element's left edge folded onto the screen's x - the same formula
     * as {@link #toCanvasX(double)}.
     *
     * @param freeX virtual screen x of the element's left edge
     * @return screen pixel x
     */
    private int elementLeftScreenX(double freeX) {
        return (int) Math.round(canvasX + freeX * canvasScale);
    }

    /**
     * Whether the element's own arm point across "the library/canvas
     * boundary" has crossed it.
     *
     * <p>{@code freeX + bw * fraction} is "the point on the element that
     * crosses the boundary", folded to the screen by the same formula as
     * {@link #toCanvasX(double)}, then compared against the fixed boundary
     * {@code libX + libW} -
     * both sides must share units (screen pixels) for the comparison to
     * hold. The fraction multiplies the <b>element width</b>, so
     * {@code 0.3} means "30% of the element itself is inside the library",
     * not "the element was pushed deep into the library".
     *
     * <p><b>No pointer in the criterion</b>: the element tracks the pointer
     * 1:1, so "how far the element has passed" already contains the player's
     * full intent; stacking a "pointer must be inside the library rectangle"
     * condition on top
     * would produce the feel of "the element is already 30% past but cannot
     * be removed, just because the pointer rests on the top bar/action area
     * row".
     *
     * @param freeX virtual screen x of the element's left edge
     * @param bw the element's current content width (scale applied)
     * @return whether the removal condition is met
     */
    private boolean elementArmedForRemoval(double freeX, int bw) {
        double armScreenX = canvasX + (freeX + bw * REMOVE_ARM_ELEMENT_FRACTION) * canvasScale;
        return armScreenX <= libX + libW;
    }

    /**
     * Mouse release: the drop point decides "place" or "cancel" for library
     * drags;
     * a canvas element is removed only when <b>itself</b> crosses the
     * boundary by 30%,
     * while one inside the library but under 30% snaps back to the leading
     * edge (x at the leading edge, y kept where dragged).
     *
     * <p>Both criteria (remove and "dragged into the library") measure only
     * <b>the element</b>, see
     * {@link #elementArmedForRemoval(double, int)} and
     * {@link #elementReachesLibrary(double)} - measuring the pointer would
     * leave "the element is already 30% past,
     * just because the pointer rests on the top bar/action area row"
     * unrecoverable.
     *
     * <p><b>Inside the library but under 30%</b> is the third outcome: the
     * player's gesture already clearly said "I want to move it left", so x
     * snaps to the leading edge and y <b>keeps the dragged spot</b> -
     * the position is the player's own choice; snapping back to the original
     * spot would void the whole drag.
     *
     * <p>The drop criterion for library drags is {@link #canPlaceAt(double,
     * double)}:
     * inside the canvas <b>or</b> over the action area directly below it, see
     * its docs - the action area hugs the canvas's bottom edge,
     * and treating "the pointer slid through that strip" as a cancel-place
     * would invent a trap where a near miss falls right back into the
     * library.
     *
     * <p>Pressed but not dragged ({@code pendingLibraryDragElement} still
     * set) does nothing - that was a plain click-select.
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        pendingLibraryDragElement = null;
        if (draggingSplitter) {
            draggingSplitter = false;
            return true;
        }
        if (draggingFromLibrary != null) {
            HudElement e = draggingFromLibrary;
            draggingFromLibrary = null;
            if (canPlaceAt(mouseX, mouseY)) {
                placeAt(e, mouseX, mouseY);
            }
            return true;
        }
        if (dragging != null) {
            HudElement e = dragging;
            boolean remove = draggingArmedForRemoval;
            // "Dragged into the library" also looks only at the element: a pointer
            // criterion would drop "the element is already pressed over the library,
            // just because the pointer rests on the top bar/action area row"
            // into the third branch (plain collapse);
            // sharing the arm criterion's source is what avoids two accountings
            boolean returnedToLibrary = elementReachesLibrary(draggingPx);
            // The free position must be read out before use: the collapse path reads it
            // (virtualBox matches on e == dragging),
            // so dragging must stay set until placement has finished
            double freeY = draggingPy;
            // Press-and-lift (plain click-select) never ran mouseDragged, so the free
            // position still sits at its start -
            // that is not a geometry edit: the coordinates keep the config value, and
            // geometryModified stays unset
            boolean moved = draggingPx != dragStartPx || draggingPy != dragStartPy;
            if (remove) {
                // Remove = truly leaving the canvas (the list turns ○); coordinates still
                // collapse first, so re-placing starts near the leading edge
                clampWorkIntoCanvas(e);
            } else if (returnedToLibrary) {
                // Dragged into the library but under 30%: x snaps to the leading edge, y
                // lands where dragged
                snapBackToLeadingEdge(e, freeY);
            } else if (moved) {
                // Not into the library but genuinely moved: collapse back into the canvas
                // travel (net-zero gestures are absorbed by pixel in hasUnsavedEdits)
                clampWorkIntoCanvas(e);
            }
            if (moved) {
                // The geometry-edit evidence is set only at this moment: mid-drag the
                // position lives only in the free position,
                // the landing is decided by one of the three branches above, and only
                // after that has "the player truly moved this HUD"
                e.geometryModified = true;
            }
            dragging = null;
            draggingArmedForRemoval = false;
            if (remove) {
                setWorkPlaced(e, false);
            }
        }        dragOffsetX = 0;
        dragOffsetY = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Collapses the workspace coordinates into the canvas travel - the
     * mandatory step on drag release.
     *
     * <p>During a drag the element's position lives in the free position
     * ({@code draggingPx/draggingPy}),
     * on release it must return to the single source of truth,
     * {@link HudElement#workX}/{@link HudElement#workY}:
     * the {@code 0~1} anchor ratio can structurally never leave the screen,
     * but the free position can land outside the canvas, and without the
     * collapse "place back on the canvas" would show the element in the
     * fringe beyond the canvas edge.
     *
     * <p>The collapse goes through {@link #ratioFrom}: pixels to ratio,
     * landing naturally in {@code 0~1}
     * (0 when the travel is zero);
     * {@link TotalHUDRenderer#snapAnchorRatio(double, double)} then applies
     * the three-step snap - "dragged near an edge" automatically equals
     * "flush", no extra button needed.
     *
     * <p>The snap band is declared in <b>virtual pixels</b>, so its own
     * travel ({@code max - min}) is passed along:
     * the same pixel tolerance is a narrow band on a long travel and a wide
     * one on a short travel. Hard-coding it as a ratio constant would grow
     * the band linearly with the travel - on wide
     * screens HUDs would get grabbed before even nearing the bottom edge,
     * reading as "stuck/springing back".
     *
     * @param e the workspace element just released
     */
    private void clampWorkIntoCanvas(HudElement e) {
        HudPreview p = e.entry.preview.create(this);
        float s = (float) e.workScale;
        int bw = TotalHUDRenderer.visibleSize(p.width, s);
        int bh = TotalHUDRenderer.visibleSize(p.height, s);
        int[] v = virtualBox(e, p, s);
        // Both bounds fetched once: ratioFrom and the snap band must share one travel
        // pair; computing twice invites rounding drift
        double minX = minBoxX(e.entry);
        double maxX = maxBoxX(bw, e.entry);
        double minY = minBoxY(e.entry);
        double maxY = maxBoxY(bh, e.entry);
        e.workX = TotalHUDRenderer.snapAnchorRatio(ratioFrom(v[0], minX, maxX), maxX - minX);
        e.workY = TotalHUDRenderer.snapAnchorRatio(ratioFrom(v[1], minY, maxY), maxY - minY);
    }

    /**
     * Landing for "dragged into the library but under the 30% threshold" on
     * release: x at the leading edge, y where dragged.
     *
     * <p>This is the half-way outcome of the "drag back to the library" path.
     * The gesture already clearly said "I want to move it left",
     * so x is pinned straight to {@link TotalHUDRenderer#ANCHOR_LEADING}
     * ({@code 0 = leading edge}),
     * no pixel solving and folding back - under the anchor accounting "flush
     * left" is one screen-independent number;
     * y still collapses from the free position with the three-step snap,
     * landing wherever band the player dragged it into.
     *
     * <p>x <b>cannot</b> go through {@link #clampWorkIntoCanvas(HudElement)}:
     * the free position is outside the canvas's left edge at this moment, and
     * the collapse would only press it back into the left-edge slot, off from
     * "flush left" by the {@link #FRAME_W}/canvas-scale sliver,
     * and the harder the drag the bigger the gap.
     *
     * <p>Sets {@code geometryModified}: x is the landing this drag explicitly
     * chose,
     * it deserves persisting - unlike the pure state flip in
     * {@link #setWorkPlaced(HudElement, boolean)}.
     *
     * @param e the workspace element just released
     * @param freeY the free position's y at release (virtual screen pixels)
     */
    private void snapBackToLeadingEdge(HudElement e, double freeY) {
        HudPreview p = e.entry.preview.create(this);
        float s = (float) e.workScale;
        int bh = TotalHUDRenderer.visibleSize(p.height, s);
        e.workX = TotalHUDRenderer.ANCHOR_LEADING;
        double minY = minBoxY(e.entry);
        double maxY = maxBoxY(bh, e.entry);
        e.workY = TotalHUDRenderer.snapAnchorRatio(ratioFrom(freeY, minY, maxY), maxY - minY);
        e.geometryModified = true;
    }

    /**
     * Whether this drag has pushed the element back into the library - its own
     * body has crossed the library's right edge (the same x as the canvas's
     * left edge) by the {@link #REMOVE_ARM_ELEMENT_FRACTION} fraction, so
     * releasing now removes it.
     * Recomputed every frame by
     * {@link #mouseDragged(double, double, int, double, double)};
     * {@link #mouseReleased(double, double, int)} decides on it.
     *
     * <p>The middle tier exists for the player: an element is clamped to the
     * bounds all around the canvas, and if release simply made it vanish the
     * player would see "it was still pressed against the canvas edge - where
     * did it go". Removal therefore has a visible precondition - the element
     * really was dragged toward the library (30% of itself inside) - and this
     * state gets its own warning (red frame plus the library's red border,
     * see {@link #renderElements(DrawContext)}/
     * {@link #renderLibrary(DrawContext, TextRenderer, int, int)}).
     *
     * <p><b>The criterion measures the element, not the pointer</b>: the
     * element tracks the pointer 1:1, so "how far the element has passed"
     * already carries the player's full intent; stacking a "pointer must be
     * inside the library rectangle" condition would leave "the element is 30%
     * past, only because the pointer rests on the top bar/action area row"
     * unrecoverable. No vertical test either - the element's vertical travel
     * is clamped inside the canvas travel, and the canvas sits entirely
     * within the library's vertical band, so the element is always inside
     * that range and the condition would be constant true.
     *
     * <p>The threshold is "30%", not "wholly out": wholly out requires the
     * element's right edge to pass the canvas's left edge, but the element is
     * clamped inside the canvas - a HUD wider than the library (scaled up, or
     * the library dragged narrower) can never be pushed that far, and removal
     * would be a dead end for it. 30% is always reachable, and while armed
     * 70% of the element still shows on the canvas, so the player can see
     * which HUD they are about to remove.
     */
    private boolean draggingArmedForRemoval;

    /** Mouse wheel scroll over the library list. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        layout();
        if (mouseX >= libX && mouseX <= libX + libW && mouseY >= libY && mouseY <= libY + libH) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            int listH = Math.max(1, libH - LIB_PAD * 2 - tr.fontHeight - 4);
            int max = Math.max(0, libraryContentHeight() - listH);
            libScroll = (int) Math.clamp(libScroll - verticalAmount * LIB_ITEM_H * 2, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /**
     * Do not pause the game: arranging HUDs happens while the game keeps
     * running (the canvas is a static image; the live picture behind it is
     * still visible), so the world need not stop for the editor.
     */
    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * Empty - the editor skips the vanilla background handling and draws its
     * own veil.
     *
     * <p>Neither of the vanilla {@code renderBackground} steps works here: it
     * first calls {@code applyBlur()}, and blurring reads the main framebuffer
     * and writes the result back to the current draw target - a needless
     * full-screen round trip in the GUI layer; the full-screen darkening
     * texture it draws afterwards would cover the canvas - {@code Screen.render}
     * draws "background first, widgets after", while the canvas is drawn
     * before {@code super.render} is called.
     * The veil is therefore drawn by {@link #render(DrawContext, int, int, float)}
     * itself as a full-screen {@link #VEIL_COLOR} layer: the editor decides
     * its extent and darkness, it comes <b>before</b> the backdrop image, and
     * its only job is to underlay the canvas and give the text contrast.
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Empty: the veil and the canvas backdrop are both drawn in render(),
        // see the method comment
    }

    // ----Hit testing and state operations----

    /** Whether the mouse is inside the canvas rectangle. */
    private boolean insideCanvas(double mouseX, double mouseY) {
        return mouseX >= canvasX && mouseX <= canvasX + canvasW
                && mouseY >= canvasY && mouseY <= canvasY + canvasH;
    }

    /**
     * Whether the mouse is inside the action-area rectangle (the strip
     * directly below the canvas).
     *
     * <p>Two uses: blocking click-through (gaps around the widgets must not
     * hit canvas elements), and giving an element dragged out of the library
     * a drop point where release still counts as placement -
     * see {@link #canPlaceAt(double, double)}.
     */
    private boolean overAction(double mouseX, double mouseY) {
        return inRect(mouseX, mouseY, actionX, actionY, actionW, actionH);
    }

    /**
     * Whether releasing a library drag at (mouseX, mouseY) counts as placing.
     *
     * <p><b>Inside the canvas, or on the action area below it</b>. The latter
     * is deliberate slack: the action area hugs the canvas's bottom edge, and
     * while dragging an element to the canvas's bottom edge the pointer
     * easily slips that one row into the action area; if release at that
     * moment sent the element back to the library, it would read as "I put it
     * right at the canvas edge and it bounced back" - while the actual landing
     * would have been clamped back onto the canvas's bottom edge, exactly
     * where the element belongs. This never happens while the widgets float
     * on the canvas (everything is inside the canvas anyway), but once they
     * moved below it, this rule has to exist - otherwise a "so close yet
     * bounced back" trap appears out of nowhere.
     *
     * <p>The top bar and the library do <b>not</b> count: they are UI zones,
     * and dropping an element there means "I don't want to place it".
     *
     * @param mouseX cursor x (screen pixels)
     * @param mouseY cursor y (screen pixels)
     * @return whether release should place
     */
    private boolean canPlaceAt(double mouseX, double mouseY) {
        return insideCanvas(mouseX, mouseY) || overAction(mouseX, mouseY);
    }

    /**
     * Whether the mouse is on a "chrome" block - the top bar, the library, or
     * the action area below the canvas; true inside any of the three
     * rectangles.
     *
     * <p>Blocks <b>click-through</b>: the action area holds buttons and a
     * slider, and the gaps around them have the right-zone backdrop and the
     * canvas boundary behind them. An element's hitbox by definition never
     * leaves the canvas, so it should not be clickable there; this layer
     * exists for elements <b>pressed near the canvas's bottom edge</b> - the
     * pointer lands in the action area yet clicks an element on the canvas's
     * bottom few pixels, reading as "selected it through the buttons".
     * The same applies to parts covered by the library/top bar. On any chrome
     * block: never select an element, never start a drag.
     *
     * <p>The action area has <b>no panel</b> (it is just where widgets sit,
     * not a panel), so this test uses the action-area rectangle itself: it
     * exactly wraps those widget rows. An empty full-size panel would eat a
     * large strip of the canvas's draggable area - "the canvas is huge but I
     * can't click it" comes from exactly that.
     *
     * <p>In {@link #mouseClicked(double, double, int)} the widgets themselves
     * are consumed first by {@code super.mouseClicked}; this test only covers
     * the gaps "the widgets miss but chrome covers" - the two are
     * complementary, and dropping either leaks clicks.
     */
    private boolean overChrome(double mouseX, double mouseY) {
        return inRect(mouseX, mouseY, 0, 0, this.width, TOP_BAR_H)
                || inRect(mouseX, mouseY, libX, libY, libW, libH)
                || overAction(mouseX, mouseY);
    }

    /** Whether a point falls inside a rectangle (top-left corner plus size). */
    private static boolean inRect(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    /**
     * Hit test for elements on the canvas: the screen rectangle expanded by
     * {@link #HIT_SLACK}; iterating in reverse lets later-drawn elements win.
     *
     * <p><b>Only elements removed from the canvas have no hitbox</b>
     * ({@code workPlaced == false}): they really are not on the canvas
     * anymore. A "disabled" element still has one - it is still drawn on the
     * canvas (content plus red outline, see
     * {@link #renderElements(DrawContext)}), and drawn-but-unclickable would
     * be a decoration you can see but not touch: no dragging it back into
     * place, no selecting it to press "Show".
     */
    private HudElement elementAt(double mouseX, double mouseY) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            if (!e.workPlaced) {
                continue;
            }
            int[] box = elementScreenBox(e);
            if (mouseX >= box[0] - HIT_SLACK && mouseX <= box[0] + box[2] + HIT_SLACK
                    && mouseY >= box[1] - HIT_SLACK && mouseY <= box[1] + box[3] + HIT_SLACK) {
                return e;
            }
        }
        return null;
    }

    /** Hit test for the library list, row by row with the scroll offset. No group headers and a constant row height - pure arithmetic. */
    private HudElement libraryItemAt(double mouseX, double mouseY) {
        if (mouseX < libX || mouseX > libX + libW) {
            return null;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int listTop = libY + LIB_PAD + tr.fontHeight + 4;
        int listBottom = libY + libH - LIB_PAD;
        if (mouseY < listTop || mouseY > listBottom) {
            return null;
        }
        int y = listTop - libScroll;
        for (HudElement e : elements) {
            if (mouseY >= y && mouseY < y + LIB_ITEM_H) {
                return e;
            }
            y += LIB_ITEM_H;
        }
        return null;
    }

    /**
     * Places the element at the mouse position (centered), puts it on the
     * canvas and marks the geometry edited - the path taken when
     * <b>dragging from the library onto the canvas</b>.
     *
     * <p>The drag path must respect the drop point (wherever the player
     * dragged it is where it lands), so it does not use
     * {@link #placeAtCenter(HudElement)}; only placements with no drop point
     * available (list right-click, button) fall back to the canvas center.
     */
    private void placeAt(HudElement e, double mouseX, double mouseY) {
        HudPreview p = e.entry.preview.create(this);
        float s = (float) e.workScale;
        int bw = TotalHUDRenderer.visibleSize(p.width, s);
        int bh = TotalHUDRenderer.visibleSize(p.height, s);

        double minX = minBoxX(e.entry);
        double maxX = maxBoxX(bw, e.entry);
        double minY = minBoxY(e.entry);
        double maxY = maxBoxY(bh, e.entry);
        double targetPx = clampRange(toVirtualX(mouseX) - bw / 2.0, minX, maxX);
        double targetPy = clampRange(toVirtualY(mouseY) - bh / 2.0, minY, maxY);

        // Snap once after converting the drop point back to an anchor ratio:
        // dragging from the library onto a canvas edge should equal "flush"
        // The travel is the same pair of values as above (see the pixel-band
        // note in clampWorkIntoCanvas)
        e.workX = TotalHUDRenderer.snapAnchorRatio(ratioFrom(targetPx, minX, maxX), maxX - minX);
        e.workY = TotalHUDRenderer.snapAnchorRatio(ratioFrom(targetPy, minY, maxY), maxY - minY);
        e.geometryModified = true;
        setWorkPlaced(e, true);
    }

    /**
     * Places the element at the <b>canvas center</b> and puts it on the
     * canvas - the only landing "Place" uses (both the list right-click and
     * the action-area button go through it).
     *
     * <p><b>The center is the only landing</b>: factory coordinates (which
     * edge to hug, how much margin) were tuned for one screen and may look
     * off at another resolution, while "dead center" is a starting point that
     * holds at every resolution. Under the anchor accounting the center is
     * simply {@code 0.5} - no pixel solving and folding back; "centered" is a
     * screen-independent number.
     *
     * <p>It <b>does</b> set {@code geometryModified}: this is a landing the
     * player explicitly chose, "I want it here" deserves persisting (unlike
     * the pure state flip in {@link #setWorkPlaced}).
     */
    private void placeAtCenter(HudElement e) {
        e.workX = TotalHUDRenderer.ANCHOR_CENTER;
        e.workY = TotalHUDRenderer.ANCHOR_CENTER;
        e.geometryModified = true;
        setWorkPlaced(e, true);
    }

    /**
     * The second button: remove from the canvas if placed, otherwise place at
     * the canvas center (the label is written by
     * {@link #refreshPlaceToggleLabel()}).
     */
    private void togglePlaced() {
        if (selected == null) return;
        if (selected.workPlaced) {
            setWorkPlaced(selected, false);
        } else {
            placeAtCenter(selected);
        }
    }

    /** Show toggle: flips "drawn in game", affects only the preview and the red frame, never "on the canvas". */
    private void toggleVisible() {
        if (selected == null) return;
        setWorkVisible(selected, !selected.workVisible);
    }

    /**
     * The <b>single entry point</b> for changing "on the canvas" -
     * <b>touches placement only, never visibility, never geometry</b>.
     *
     * <p>The two states are independent (each persisted as its own
     * {@code PLACED_*}/{@code VISIBLE_*} key), so "remove from canvas" does
     * not drag "show" along: flipping both fields would turn "remove from
     * canvas" into "remove + disable", and the player would have to switch
     * the HUD back on by hand.
     *
     * <p><b>Does not set {@code geometryModified}</b>: placement is a state
     * switch, not a geometry edit. Setting it here would mean one placement
     * flip plus Save writes the workspace's clamped coordinates back into the
     * config - even if the player never dragged anything. Whether geometry is
     * persisted is decided only by the four real geometry paths: canvas drag /
     * {@code placeAt} / {@code placeAtCenter} / {@code onSliderChanged}.
     *
     * @param e workspace element
     * @param placed target placement state
     */
    private void setWorkPlaced(HudElement e, boolean placed) {
        e.workPlaced = placed;
        refreshActionLabels();
    }

    /**
     * The <b>single entry point</b> for changing workspace visibility - one
     * place to change, one place to refresh the button label.
     *
     * <p>There are only two change sites, both funneled through
     * {@link #toggleVisible()}: the action area's "Show" button and a canvas
     * right-click on the element. The "Show: On/Off" label is written once in
     * {@link #layoutWidgets()}; bypass this entry point and the label sticks
     * at its old value - the button says "Show: On" while the canvas already
     * shows it off. Funneling into one method means no change site can forget
     * the refresh.
     *
     * <p><b>Does not set {@code geometryModified}</b>: same reason as
     * {@link #setWorkPlaced} - visibility is a state switch, unrelated to
     * geometry.
     *
     * @param e workspace element
     * @param visible target visibility
     */
    private void setWorkVisible(HudElement e, boolean visible) {
        e.workVisible = visible;
        refreshActionLabels();
    }

    /**
     * Refreshes the labels and enabled states of row 0's two buttons ("Show"
     * and "Remove from canvas"/"Place") - the only writer in this class,
     * see {@link #refreshVisibleToggleLabel()}/
     * {@link #refreshPlaceToggleLabel()}
     * (both buttons sit in {@link #actionRow(int)} row 0, see
     * {@link #layoutWidgets()}).
     *
     * <p>The two buttons are a pair but talk about two independent things:
     * the first is "drawn in game", the second "on the canvas". "Not on the
     * canvas" does not gray out the first - that HUD may well still be on in
     * game, the player merely does not want it occupying the editing canvas,
     * and "Show: On" is then <b>true</b>
     * (see the combination table in {@link HudElement}).
     */
    private void refreshActionLabels() {
        boolean hasSelection = (selected != null);
        visibleToggleButton.active = hasSelection;
        placeToggleButton.active = hasSelection;
        refreshVisibleToggleLabel();
        refreshPlaceToggleLabel();
    }

    /**
     * Refreshes the "Show" button's label - looks only at {@code workVisible},
     * never at "on the canvas".
     *
     * <p>The two states are independent, and this button asks purely about
     * in-game life or death; entangling it with placement (labeling it "on
     * the canvas and enabled") would display the legitimate state "removed
     * from the canvas but still on in game" as "Show: Off".
     * Called only when the state really changed (funneled through
     * {@link #setWorkPlaced(HudElement, boolean)}/
     * {@link #setWorkVisible(HudElement, boolean)} and
     * {@link #layoutWidgets()}), never on a per-frame path - refreshing every
     * frame would allocate a new {@code Text} object for nothing.
     */
    private void refreshVisibleToggleLabel() {
        boolean on = (selected != null) && selected.workVisible;
        visibleToggleButton.setMessage(Text.translatable(on
                ? "nomorezombies.hudeditor.visible.on" : "nomorezombies.hudeditor.visible.off"));
    }

    /** Refreshes the second button's label: on the canvas -> "Remove from canvas", off -> "Place back". */
    private void refreshPlaceToggleLabel() {
        boolean placed = (selected != null) && selected.workPlaced;
        placeToggleButton.setMessage(Text.translatable(placed
                ? "nomorezombies.hudeditor.remove" : "nomorezombies.hudeditor.place"));
    }

    /**
     * "Reset" = <b>rollback</b>: return the whole workspace to the state at
     * the last save (or at this editor open).
     *
     * <p>All five items roll back, not just coordinates: position, scale,
     * in-game visibility and "on the canvas" together. That makes it
     * exactly "discard everything I changed since I opened the editor", and
     * it is also the only way back from "Show: Off" (that toggle lives only
     * in the action area, and once an element is removed from the canvas it
     * is nowhere to be seen).
     *
     * <p>It rolls back to <b>the last saved state</b> - there is no "factory
     * default edge-hugging position" concept (see
     * {@link #placeAtCenter(HudElement)}). Implementing "reset" as "push each
     * element back to its default edge position" would be neither what a
     * player means by the word nor preserve the position they already
     * arranged - it would swap it for a different set of coordinates.
     *
     * <p>No "has edits" graying: with no edits, pressing it is a no-op with
     * no side effects; maintaining a derived "is dirty" flag for that much
     * benefit is not worth it.
     *
     * <p>Must finish with {@link #layoutWidgets()}: the slider re-reads the
     * selected element's scale (it keeps its own internal value), and both
     * buttons' labels and enabled states need refreshing - touching only the
     * workspace fields would leave last second's readout on screen.
     */
    private void resetToSaved() {
        ensureWorkInit();
        snapshotFromConfig();
        // snapshotFromConfig re-records the baseline, so workspace == baseline
        // and "unsaved" disappears
        // (otherwise Esc after the rollback would be intercepted once more, even
        // though that exit would lose nothing)
        pendingDiscard = false;
        layoutWidgets();
    }

    /**
     * Slider drag callback: writes the workspace scale and marks the geometry
     * edited; persisted only on save.
     */
    private void onSliderChanged(double newScale) {
        if (selected == null) return;
        selected.workScale = newScale;
        selected.geometryModified = true;
    }

    // ----Lifecycle----

    /**
     * Save: writes the workspace state back into the config (coordinates and
     * scale only when <b>geometry</b> was edited; visibility and placement
     * always), then persists and exits the editor.
     *
     * <p><b>The two states are written independently, never conjoined</b>:
     * {@code VISIBLE_*} and {@code PLACED_*} are two separate matters (see
     * the combination table in {@link HudElement}); both keys are written,
     * neither derived from the other.
     *
     * <p><b>Coordinates honor only {@code geometryModified}</b> - the key
     * step once "visibility/placement flips" and "geometry edits" were
     * separated: the latter is the evidence the player truly moved position
     * or scale. Without this gate, one "Show" press plus Save would write
     * the workspace's clamped coordinates back into the config - even if the
     * player never dragged it.
     */
    private void save() {
        for (HudElement e : elements) {
            e.entry.setVisible.accept(e.workVisible);
            e.entry.setPlaced.accept(e.workPlaced);
            if (e.geometryModified) {
                e.entry.setX.accept(e.workX);
                e.entry.setY.accept(e.workY);
                e.entry.setScale.accept(e.workScale);
            }
        }
        GlobalConfig.Hud.HUD_LIBRARY_WIDTH.setDoubleValue(workLibWidth);
        GlobalConfig.saveToFile();
        // After saving the workspace matches the config, so the dirty flag
        // expires - the next exit must not report "unsaved"
        // The baseline must move forward too: otherwise it still "differs from
        // the baseline" after saving, and exit keeps warning
        recordBaseline();
        pendingDiscard = false;
        closeEditor();
    }

    /**
     * Whether the workspace differs from "the last save/open" - computed
     * live, item by item against the <b>entry baseline</b>.
     *
     * <p><b>The question is "what was touched", not "does it equal the
     * config's literal values"</b>: the config stores coordinates as anchor
     * ratios, which pass through {@link #resolveWorkX(HudElement)} clamping
     * into {@code 0~1} on their way into the workspace - an out-of-range
     * hand-edited value makes the two naturally differ, and comparing
     * literally would false-positive "unsaved". The baseline is recorded
     * right after the snapshot in {@link #snapshotFromConfig()}, so "nothing
     * touched" is exactly "every item equals the baseline".
     *
     * <p>Covers the four kinds of edits a player can make (which is every
     * edit this screen allows):
     * <ul>
     *  <li><b>Moving</b> a HUD -> position differs from the baseline
     *  (compared in <b>pixels</b>, see
     *  {@link #samePosAsBaseline(int, HudElement)});</li>
     *  <li><b>Removing/adding</b> a HUD ("on the canvas") ->
     *  {@code workPlaced} differs from the baseline;</li>
     *  <li><b>Resizing</b> -> scale differs from the baseline (compared at
     *  <b>readout precision</b>, see
     *  {@link #sameScaleAsBaseline(int, HudElement)});</li>
     *  <li>plus "Show: On/Off" and the library width (both written back
     *  unconditionally on save; missing them would silently drop changes)</li>
     * </ul>
     *
     * <p><b>Three kinds of "net-zero gestures" do not count as edits</b>:
     * dragging to B and back to A, scaling up and back down, and dragging
     * from the list onto the canvas and back into the list. The first two
     * dissolve naturally under the pixel/readout-precision comparisons, the
     * third under bit-equal {@code workPlaced}. "The player made a net-zero
     * gesture" and "the player changed it and changed it back" have the same
     * outcome - the config is unchanged in the end, so no warning.
     *
     * <p>Why not reuse {@code HudElement.geometryModified}: that field means
     * "should Save write coordinates/scale", it does <b>not</b> cover the
     * library width, and it carries no "relative to entry state" meaning -
     * moreover it answers "was it touched", not "does it now differ"; after
     * a net-zero gesture it is still true. This pre-exit gate asks "is what
     * is on screen still what I saw when I came in", and comparing item by
     * item against the baseline is the most direct answer.
     *
     * @return {@code true} when unsaved edits exist
     */
    private boolean hasUnsavedEdits() {
        if (workLibWidth != baseLibWidth) {
            return true;
        }
        int i = 0;
        for (HudElement e : elements) {
            // Position and scale participate only when "on the canvas now and on
            // the canvas at entry" -
            // coordinates left behind by drag-up-then-back are not displayed and
            // the config's effective state did not change (see posCounts)
            if (posCounts(i, e)
                    && (!samePosAsBaseline(i, e) || !sameScaleAsBaseline(i, e))) {
                return true;
            }
            // Visibility and placement bypass clamping: the workspace value is the
            // config value, compare directly (also covers entries beyond the array)
            if (e.workVisible != e.entry.getVisible.getAsBoolean()
                    || e.workPlaced != e.entry.getPlaced.getAsBoolean()) {
                return true;
            }
            i++;
        }
        return false;
    }

    /**
     * Requests closing the editor - the <b>anti-foot-gun gate for unsaved
     * edits</b>: first press warns only, second press really exits.
     *
     * <p>Esc, the top-right ✕ and the "Cancel" button all funnel here. With
     * unsaved edits, the first call does <b>nothing</b> except raise
     * {@link #pendingDiscard}: the top-right icon turns into an orange
     * warning whose tooltip's first line says "press once more to discard".
     * Triggering the same close action again really goes through
     * {@link #closeEditor()} - and <b>writes no config</b>; the unsaved
     * edits are simply discarded.
     *
     * <p>Once raised it need not be cleared before exiting - the player can
     * keep editing and the gate stays on the next Esc (that is exactly the
     * "I have already warned" semantics). The real reset points are three:
     * {@link #save()} (saved, nothing to lose),
     * {@link #resetToSaved()} (workspace back to the config, dirty state
     * gone), and the next editor open's {@link #init()}.
     * <b>The orange warning icon follows only this flag</b>: tweaking the
     * layout is normal operation, and lighting the lamp on every tweak would
     * mean every drag pops "did you make a mistake"; the warning is a useful
     * cue only at the moment the player <b>really tries to leave</b>.
     *
     * <p>Why the gate must exist: every edit on this screen writes only the
     * workspace snapshot - <b>only "Save" persists</b> - and Esc is the most
     * common way to close a screen; one mis-press would silently throw away
     * ten-plus minutes of arranging.
     *
     * @return whether the editor really closed
     */
    private boolean requestClose() {
        if (hasUnsavedEdits() && !pendingDiscard) {
            pendingDiscard = true;
            return false;
        }
        pendingDiscard = false;
        closeEditor();
        return true;
    }

    /** Cancel: exit discarding unsaved edits - but through the anti-foot-gun gate first (see {@link #requestClose()}). */
    private void cancel() {
        requestClose();
    }

    /**
     * Closes the editor: resets {@link #IS_OPEN}, releases canvas resources,
     * then returns to the parent screen.
     *
     * <p>On the static-backdrop path {@link HudCanvas#release()} is a no-op
     * (the canvas was never used), but it only unregisters the texture slot
     * and its own framebuffer, with no side effects - kept so that "if
     * HudCanvas ever gets wired back in" the release is not forgotten.
     */
    private void closeEditor() {
        IS_OPEN = false;
        // If this same instance is setScreen'd again (vanilla takes
        // refreshWidgetPositions, not init), a leftover entered == true would skip
        // the workspace reset - clear it here so "reopen" equals a fresh open
        entered = false;
        // release() only unregisters the texture slot and its own framebuffer; a
        // no-op here with no side effects.
        // Kept so that "if HudCanvas ever gets wired back in" the release is not
        // forgotten
        HudCanvas.release();
        // With no return screen, setScreen(null) is vanilla's "close this screen";
        // no extra branch needed
        MinecraftClient.getInstance().setScreen(parent);
    }

    /**
     * Closes the editor: both Esc and the top-right ✕ come here - through the
     * anti-foot-gun gate first (see {@link #requestClose()}); with unsaved
     * edits the first press only lights the warning and does not close.
     */
    @Override
    public void close() {
        requestClose();
    }

    /**
     * Enters the editor: raises {@link #IS_OPEN} (each HUD uses it to stop
     * drawing in place), resets the workspace, lays out widgets.
     *
     * <p>The canvas is a <b>static backdrop image</b> with nothing to take a
     * live source from - the {@link HudCanvas}/{@link HudCanvasMode}/
     * {@code hudCanvasMode} live-source code and config key are <b>kept as
     * is</b>: they can be wired back in anytime (swap the draw call in
     * {@link #drawCanvasBackground(DrawContext)} back to
     * {@code HudCanvas.render(context, canvasX, canvasY, canvasW, canvasH)}),
     * whereas deleting them would mean deleting the config key too.
     */
    @Override
    protected void init() {
        IS_OPEN = true;
        this.clearChildren();
        // Probe once whether the backdrop image exists (asking the resource
        // manager every frame is too expensive, and it is fixed at packaging) -
        // if missing, draw the fallback backdrop; don't wait for vanilla's
        // purple-black checker to hit the screen and leave the player guessing
        canvasBackgroundMissing = MinecraftClient.getInstance().getResourceManager()
                .getResource(BG_TEXTURE).isEmpty();
        // ----Only on the first pass of "this open"----
        // Vanilla Screen.resize calls init() again on every size change
        // (refreshWidgetPositions -> clearAndInit -> init), so the "reset the
        // workspace" steps below must never rerun unconditionally: otherwise one
        // window resize throws the layout the player just arranged back to the
        // config values
        if (!entered) {
            entered = true;
            workInitialized = false;
            selected = null;
            dragging = null;
            draggingFromLibrary = null;
            pendingLibraryDragElement = null;
            draggingSplitter = false;
            // Reset the mid-drag "about to remove" flag: closing the editor at the red
            // frame moment last time leaves it true,
            // so a fresh open with an element just grabbed and not yet moved would
            // already show the library with its red border
            draggingArmedForRemoval = false;
            // Same for the free position: nothing is being dragged in a fresh open,
            // and a stale coordinate only turns "if some path ever reads it" into an
            // invisible misplacement
            draggingPx = 0;
            draggingPy = 0;
            scaleSlider = null;
            libScroll = 0;
            // A new open must not inherit the previous "already warned" state
            pendingDiscard = false;
        }
        layout();
        layoutWidgets();
    }

    /** Resolution/GUI-scale change: recompute geometry and re-lay widgets (the scale factor follows; relative positions stay). */
    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        layout();
        layoutWidgets();
    }
    // ----Native scale slider: extends SliderWidget directly, message is "Scale: 1.00"----

    /**
     * The scale slider (a native SliderWidget) - the message itself is
     * "Scale: 1.00"; maps the 0.5~2.0 factor onto the slider's 0~1 range and
     * callbacks on drag/release.
     */
    private static final class ScaleSliderWidget extends SliderWidget {

        private final Consumer<Double> onChange;

        ScaleSliderWidget(int x, int y, int width, int height, Consumer<Double> onChange) {
            super(x, y, width, height, Text.empty(), 0.5);
            this.onChange = onChange;
            updateMessage();
        }

        void setScaleValue(double scale) {
            double t = (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
            this.value = Math.clamp(t, 0.0, 1.0);
            updateMessage();
        }

        double getScaleValue() {
            return MIN_SCALE + this.value * (MAX_SCALE - MIN_SCALE);
        }

        /** Vanilla slider flavor: refresh the message to "Scale: 1.00" on drag/construct. */
        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("nomorezombies.hudeditor.scale").copy()
                    .append(Text.literal(String.format("%.2f", getScaleValue()))));
        }

        /** Converts the slider value back to a scale factor for the callback on drag/release (same 0.5~2.0 range). */
        @Override
        protected void applyValue() {
            onChange.accept(getScaleValue());
        }
    }

    // ----Drag-time name tooltip positioner----

    /**
     * Pins the tooltip to the pointer's <b>bottom-right</b> (drag-specific,
     * see {@link #renderDragNameTooltip(DrawContext,TextRenderer,HudElement,
     * int,int)}).
     *
     * <p>A different positioner during drags, not vanilla's
     * {@link HoveredTooltipPositioner}: that one sits <b>top-right</b> of the
     * pointer, which during a drag is exactly where the drop indicator box
     * lives - they would cover each other. Bottom-right is nearest the
     * pointer and never blocks the box.
     * All four directions flip at the screen bounds (no room right -> go
     * left, no room below -> go up), so "bottom-right" near a screen edge
     * never ends up half off screen.
     */
    private enum DragNamePositioner implements TooltipPositioner {
        INSTANCE;

        @Override
        public Vector2ic getPosition(int screenWidth, int screenHeight, int x, int y, int width, int height) {
            int px = x + 12;
            if (px + width > screenWidth - 4) {
                px = Math.max(4, x - 12 - width);
            }
            int py = y + 12;
            if (py + height > screenHeight - 4) {
                py = Math.max(4, y - 12 - height);
            }
            return new Vector2i(px, py);
        }
    }

    // ----Workspace element: a HudEntry reference plus unsaved position/scale/visibility----

    /**
     * One HUD's instance in the workspace - holds only "what this editing
     * session changed", not the HUD itself.
     *
     * <p>The HUD's name, read/write accessors, default-position resolution
     * and sample all come from {@link #entry} (the registry's description);
     * this class adds only five <b>workspace state</b> fields:
     * {@code workX}/{@code workY}/{@code workScale}/
     * {@code workPlaced}/{@code workVisible}.
     * Dragging and the slider write only these fields (plus
     * {@code geometryModified}); only "Save" writes back into the config.
     * The layer in between lets the player try positions repeatedly and
     * cancel outright - one drag never pollutes the live config.
     *
     * <p><b>"On the canvas" and "drawn in game" are two fields</b>: two
     * separate matters, persisted separately
     * ({@code PLACED_*} vs {@code VISIBLE_*}) - "show" is the HUD's life or
     * death in game, "place" is whether it occupies my editing canvas. The
     * four combinations:
     * <ul>
     *  <li>placed + shown: arranged normally, drawn in game too (gray-white
     *  or yellow frame on the canvas);</li>
     *  <li>placed + disabled: "Show: Off" - <b>stays on the canvas, frame
     *  turns red</b>; the player is still arranging it, the game does not
     *  draw it;</li>
     *  <li>unplaced + disabled: "removed from canvas" - <b>taken off the
     *  canvas</b> (no frame at all, just an ○ in the list);</li>
     *  <li>unplaced + shown: <b>not drawn in game either</b>
     *  (see the predicate group at {@link GlobalConfig.Hud#spawnTimeOn()}:
     *  placement is a necessary condition).
     *  This cell is a "layout staging slot" - the config still records it as
     *  on, but it is not on the canvas, so "where in game" does not arise</li>
     * </ul>
     * For the <b>on-canvas composition</b> (see
     * {@link #workspaceActive(String)}) only "placed + shown" counts: in the
     * other three cells the HUD carries no duty on the canvas, and its
     * partner should not reserve room for it. If the two states were merged
     * into one field, "remove from canvas" would only repaint the frame red
     * (the element would appear to snap back, still disabled) and the player
     * would think removal failed - hence the split, each persisted on its
     * own.
     */
    private static final class HudElement {
        /** The registry's HUD description (identity, accessors, default position, sample). */
        final HudEntry entry;

        /** Workspace X anchor (virtual screen ratio) - written back to config only on save. */
        double workX;
        /** Workspace Y anchor (virtual screen ratio) - written back to config only on save. */
        double workY;
        /** Workspace scale (0.5~2.0) - written back to config only on save. */
        double workScale;
        /** Whether placed on the editor canvas (persisted as {@code PLACED_*}). */
        boolean workPlaced;
        /** Whether drawn in game (persisted as {@code VISIBLE_*}) - independent of {@link #workPlaced}. */
        boolean workVisible;
        /**
         * Whether this session edited <b>geometry</b> (position or scale);
         * if not, nothing is written back - so untouched coordinates are
         * never persisted as if they were an edit result.
         *
         * <p><b>Only geometry paths may set it</b>: canvas drag, drag out of
         * the library, {@code placeAtCenter} (the "Place" button / list
         * right-click), the scale slider.
         * {@code setWorkPlaced}/{@code setWorkVisible} are <b>state</b>
         * switches and touch no geometry - were they to set it, one "Show"
         * flip plus Save would write the workspace's clamped coordinates
         * back into the config, even if the player never dragged anything.
         */
        boolean geometryModified;

        HudElement(HudEntry entry) {
            this.entry = entry;
        }
    }
}