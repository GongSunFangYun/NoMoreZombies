package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for on-screen HUDs (waves, powerups, CPS, lightning rod
 * queue, etc.). Subclasses implement {@link #onRender(DrawContext)} to
 * draw; registration, gating and exception isolation all close here.
 *
 * <p>Renderers land in one of two channels chosen by {@link #renderAfterChat()},
 * both declared once into the vanilla HUD layer table via
 * {@link HudLayerRegistrationCallback}: the regular layer ({@code ALL}) is
 * appended at the <b>end</b> of the layer table with {@code addLayer} and
 * draws above every native HUD (suiting HUDs in the mid/upper screen); the
 * late layer ({@code LATE}) is inserted after the native chat layer with
 * {@code attachLayerAfter(IdentifiedLayer.CHAT, ...)} and draws above chat,
 * background included (suiting HUDs anchored over the chat area).
 * LightningRodQueue overrides {@link #renderAfterChat()} to return true.
 *
 * <p>Gating has two layers: the base class first asks the environment hook
 * {@link #shouldRenderHud()} (inside a Zombies game by default); the
 * subclass then combines {@link #shouldRender}, config switches or its own
 * scene limits inside {@link #onRender(DrawContext)}. The base class does
 * not check {@code shouldRender} itself, because not every HUD follows the
 * round-title show/hide signal. Both render entries catch all exceptions:
 * a failed frame skips only that HUD, never the others.
 * {@link MinecraftClient} and {@link TextRenderer} are re-fetched before
 * every draw pass - during init {@code textRenderer} is not ready yet, so
 * fetching at render time is the safe choice. Subclasses call
 * {@link #init()} after onInitializeClient to join a channel.
 *
 * <p><b>Both entries pass {@link #shouldRenderHud()} first.</b> This is the
 * only structural guarantee that no HUD can bypass the environment gate:
 * if a HUD survives an unintended or deliberate exit, either a subclass
 * built its own gate or the gate's source of truth (the sidebar cache) is
 * stale - there is no third path.
 */
public abstract class TotalHUDRenderer {

    /**
     * Ink overflow of text shadows (px). {@code drawTextWithShadow} paints
     * a 1px shadow to the right of and below each glyph, so the visible HUD
     * size is {@code getWidth} / {@code fontHeight} plus this amount.
     *
     * <p>Include it when measuring HUD width/height (editor collision box,
     * default edge anchors), or the box comes out 1px smaller than the
     * visible content: harmless at the top-left corner, but a right- or
     * bottom-anchored HUD shows a shadow strip outside the box - this is
     * usually why a HUD that sits flush in the editor looks off by a pixel
     * in game. HUDs drawn with {@code drawText} (no shadow, e.g. the
     * scoreboard) don't count it.
     */
    public static final int TEXT_SHADOW = 1;

    /**
     * Rounding rule for content size times scale - <b>round up only</b>,
     * so the collision box always covers the ink.
     *
     * <p>The actual content is drawn through a <b>floating-point</b> scale
     * matrix ({@link #drawScaled}); its real size is
     * {@code contentSize * scale}, which is not an integer, while the
     * collision box can only be whole pixels. Rounding to nearest makes the
     * box <b>smaller</b> than the ink for every scale whose fraction is
     * under 0.5: the shadow of the last content row lands outside the box
     * and the bottom/right cell is lost ("1px of collision range missing at
     * the bottom"; on a measured grid, roughly six in ten combos miss that
     * cell). With ceil, {@code box >= contentSize * scale} always holds and
     * the cell is never lost.
     *
     * <p>The cost: a bottom-anchored HUD can show a sub-pixel gap between
     * the content's bottom edge and the screen edge - that strip is the
     * last cell of the glyph shadow, invisible. A missing cell is visible.
     *
     * <p>The tiny subtraction kills float noise: combos that are exactly
     * integral (content 19, scale 1.0) must not get an extra cell, and
     * {@code float} to {@code double} carries ~1e-7 error. The epsilon is
     * far below 1 and far above that error.
     *
     * @param contentSize content size in virtual screen px, the one the
     *                    renderer measured
     * @param scale this HUD's scale factor (workspace scale x canvas ratio
     *              in the editor)
     * @return smallest integer not below {@code contentSize * scale}
     */
    public static int visibleSize(int contentSize, float scale) {
        return (int) Math.ceil(contentSize * (double) scale - 1.0e-6);
    }

    // ----anchor ratios (the single entry point for cross-resolution layout)----

    /**
     * <b>Anchor ratio semantics</b>: the configured X/Y is not "the
     * element's top-left corner as a fraction of the screen" but "how far
     * along the <b>available travel</b> the element sits" -
     * {@code 0.0} = at the leading edge (left/top), {@code 0.5} = centered,
     * {@code 1.0} = at the trailing edge (right/bottom).
     *
     * <p>Travel = {@code [pad, screenWidth - contentWidth - pad]}. Changing
     * device, GUI Scale or window size recomputes the travel while the
     * fraction stays put: a right-anchored HUD stays right-anchored at any
     * resolution, a centered one stays centered.
     *
     * <p>That is the core advantage over storing a top-left fraction, which
     * is not resolution-invariant when the content width is fixed
     * ({@code right edge = r*W + contentWidth}, with {@code contentWidth}
     * constant and {@code W} changing): a layout tuned on one screen
     * overflows on a smaller one and floats far from the edge on a bigger
     * one.
     *
     * <p>Another structural benefit: 0 and 1 land exactly on the pad by
     * construction, so no clamp-back fallback is needed - the position is
     * always on screen.
     *
     * <p><b>0.5 is not a snap point.</b> It is only the number "ratio equals
     * exactly half"; rendering is still {@code pad + 0.5 * travel}
     * (geometric centering). In the editor only the Place button writes it
     * directly; dragging never snaps to it (see
     * {@link #snapAnchorRatio(double, double)}).
     */
    public static final double ANCHOR_LEADING = 0.0;
    /** Centered anchor ({@code 0.5}) - <b>a definition of what the ratio
     *  equals</b>, not a snap slot: only the editor's Place (canvas center)
     *  writes it directly, dragging never snaps to it. */
    public static final double ANCHOR_CENTER = 0.5;
    /** Trailing-edge anchor ({@code 1.0}). */
    public static final double ANCHOR_TRAILING = 1.0;

    /**
     * The endpoint tolerance (virtual screen pixels) - <b>serves only
     * "edge-hugging down to that one pixel"</b>, not a snap band.
     *
     * <p>On release the ratio is back-computed from <b>rounded pixels</b>
     * ({@code HUDEditor.clampWorkIntoCanvas} goes through {@code virtualBox},
     * which is {@code (int) Math.round}), so
     * "dragged to the travel's endpoint" lands as {@code 1 - delta/travel}
     * ({@code |delta| <= 0.5}).
     * Only collapsing that residue into an exact {@code 0}/{@code 1} keeps
     * "right-hug" still hugging on other resolutions: a leftover
     * {@code 0.9993} ratio turns into a 1px gap once the travel becomes 1400
     * virtual pixels.
     *
     * <p><b>It is not a snap band</b>: the endpoint tolerance only recognizes
     * "landing inside the endpoint's one pixel" and returns everything else
     * unchanged - land wherever near the anchor.
     * With a snap band, the player could not land precisely anywhere in that
     * stretch; do not resurrect those constants under their old names.
     *
     * <p>The {@code 0.5} value follows directly from the {@code |delta| <= 0.5}
     * above; it is not a feel parameter: 1 whole canvas pixel from the
     * endpoint gives {@code r x travel = 1 / canvasScale in [1, 1.42] > 0.5},
     * which always escapes
     * ({@code canvasScale <= 1} is guaranteed by {@code HUDEditor.layout()}),
     * so "endpoint exactness" and "can land 1px from the edge" are two matters
     * under one ruler, not in conflict.
     */
    public static final double ANCHOR_END_TOLERANCE_PX = 0.5;

    /**
     * Anchor ratio -> screen-pixel left (or top) anchor.
     *
     * <p>One formula only: {@code pad + ratio x (screen - contentSize - 2 x pad)};
     * both ends are therefore exact, with no rounding compensation.
     *
     * <p><b>The content size is measured live by the caller</b> (each
     * renderer's {@code hudWidth}/{@code hudHeight} times this frame's
     * scale), reading neither the sample nor the last frame's cache - "I
     * measure the screen I am about to draw" is the shared premise of both
     * this layer's and the editor's "frame = content rectangle" contracts.
     *
     * @param ratio the anchor ratio (out-of-range values clamped; {@code NaN} treated as 0)
     * @param screen the screen's (virtual screen's) size on this axis
     * @param contentSize the element's content size on this axis (scale applied)
     * @param pad this axis's reserve (see {@code HudEntry#reserveX}/{@code #reserveY})
     * @return the element's top-left screen pixel coordinate
     */
    public static int anchorPixels(double ratio, int screen, int contentSize, int pad) {
        int travel = Math.max(0, screen - contentSize - pad * 2);
        return pad + (int) Math.round(clampAnchorRatio(ratio) * travel);
    }

    /**
     * Clamps any double into the anchor ratio's {@code 0~1} range.
     *
     * <p>An out-of-range config value (a hand-edited config file, or a
     * negative left over from old configs) is clamped to the nearest end -
     * no exception, no off-screen coordinates.
     *
     * @param ratio the raw ratio
     * @return the ratio within {@code 0~1}; {@code NaN} returns 0
     */
    public static double clampAnchorRatio(double ratio) {
        if (Double.isNaN(ratio)) {
            return ANCHOR_LEADING;
        }
        return Math.clamp(ratio, 0.0, 1.0);
    }

    /**
     * Collapses the ratio into "the ratio represented by the pixel it landed
     * in": <b>only the travel's first/last pixel</b> collapses into an exact
     * {@link #ANCHOR_LEADING}/{@link #ANCHOR_TRAILING}; the middle returns as
     * is.
     *
     * <p><b>No snap band here</b>: the collapse happens only when "it landed
     * inside the endpoint's one pixel", and only at the two ends - the reason
     * is in {@link #ANCHOR_END_TOLERANCE_PX}'s note: release back-computes
     * the ratio from rounded pixels, so the endpoint's half-pixel residue must
     * be collapsed, or "dragged all the way" would store a ratio off by a
     * fraction of a pixel, becoming 1px on another resolution.
     * A larger tolerance would swallow a whole stretch near the anchor and the
     * player could not land precisely.
     *
     * <p><b>Centering is no longer a tier</b>: {@code 0.5} gets no special
     * treatment - for exact centering use the editor's "Place" (canvas center,
     * writing {@code 0.5} directly, see {@code HUDEditor.placeAtCenter});
     * dragging stops wherever it stops.
     *
     * <p><b>The travel length must be passed by the caller</b>: the endpoint
     * tolerance is declared in virtual screen pixels (the same system as
     * {@code HudEntry#reserve}); in ratio space it is
     * {@code ANCHOR_END_TOLERANCE_PX / travel} - the same pixel tolerance is a
     * narrow mouth on a long travel and a wide one on a short travel. A
     * hard-coded ratio constant would grow the tolerance linearly with
     * travel: on big windows, a HUD nearing the edge would get grabbed and
     * stuck ("drag rubber-banding").
     *
     * @param ratio the raw ratio
     * @param span the axis's travel length (virtual screen pixels, i.e. {@code max - min});
     *             non-positive or {@code NaN} skips the endpoint collapse and returns as is
     * @return the collapsed ratio (middle values return unchanged)
     */
    public static double snapAnchorRatio(double ratio, double span) {
        double r = clampAnchorRatio(ratio);
        if (!(span > 0.0)) {
            // Degenerate travel (the element larger than the available space) or NaN: nothing to
            // collapse, return as is
            return r;
        }
        // Endpoints in the "pixel" sense: landing inside the first/last pixel (+- half pixel)
        // collapses to an exact 0/1
        if (r * span <= ANCHOR_END_TOLERANCE_PX) {
            return ANCHOR_LEADING;
        }
        if ((1.0 - r) * span <= ANCHOR_END_TOLERANCE_PX) {
            return ANCHOR_TRAILING;
        }
        return r;
    }

    /**
     * The regular layer - declared as one HUD layer by {@link #initLayers()}'s
     * {@code addLayer}, drawn at the very end of the vanilla layer table (above
     * all native HUDs); {@link #setShouldRender(boolean)} batch-writes only
     * this layer.
     */
    private static final List<TotalHUDRenderer> ALL = new ArrayList<>();
    /**
     * The late layer - needs to sit above chat (with its background),
     * declared by {@link #initLayers()}'s
     * {@code attachLayerAfter(IdentifiedLayer.CHAT, ...)} (e.g. the lightning
     * rod queue); not subject to the batch visibility write.
     */
    private static final List<TotalHUDRenderer> LATE = new ArrayList<>();

    /** The regular layer's id ({@code nomorezombies:hud}) - the one appended at the layer table's end. */
    private static final Identifier REGULAR_LAYER_ID = Identifier.of(NoMoreZombies.MOD_ID, "hud");
    /** The late layer's id ({@code nomorezombies:late_hud}) - the one inserted after vanilla's chat layer. */
    private static final Identifier LATE_LAYER_ID = Identifier.of(NoMoreZombies.MOD_ID, "late_hud");
    /** Whether the two layers are declared: every HUD's {@link #init()} reaches here; declaration must happen once. */
    private static boolean layersRegistered;

    /**
     * The global visibility switch - driven in batch from outside via
     * {@link #setShouldRender(boolean)} for regular-layer instances.
     * Subclasses may read it or add conditions; the base class's default gate
     * does not check this field - subclasses wanting that semantics must adopt
     * it explicitly in {@link #onRender(DrawContext)} or an overridden
     * {@link #shouldRenderHud()}.
     */
    public boolean shouldRender;
    /** The current frame's client instance; usable after {@link #init()}, refreshed every frame by the render entry, for subclass extension points. */
    protected MinecraftClient minecraft;
    /** The current frame's text renderer; guaranteed non-{@code null} before {@link #onRender(DrawContext)} runs, otherwise the frame is skipped. */
    protected TextRenderer textRenderer;

    /**
     * Declares the two HUD layers into the vanilla layer table - idempotent,
     * executed by {@link #init()} on first call.
     *
     * <p><b>Why it lives in the base class</b>: the two channels' layer ids
     * and placement belong to this layer alone; scattered into
     * {@code NoMoreZombiesClient}, adding a channel could forget the client
     * entry - a bug that only shows at runtime as "some HUD does not draw".
     * Kept here, adding a channel means editing one file.
     *
     * <p><b>Why "two layers" and not "one layer per HUD"</b>: a layer's
     * meaning is draw order, and the ten HUDs share one order (either all
     * above native HUDs or all above chat), while layer ids must be globally
     * unique ({@code LayeredDrawerWrapperImpl.validateUnique} throws on
     * duplicates) - one layer per HUD is both unnecessary and would bloat the
     * table to ten entries.
     *
     * <p><b>Why declare inside {@link HudLayerRegistrationCallback} instead of
     * calling directly</b>: during vanilla {@code InGameHud}'s layer table
     * construction, the callback's dispatch point comes after its own chain of
     * {@code addLayer}s (Fabric's {@code InGameHudMixin} dispatches at the
     * constructed {@code RETURN}), so what is seen here is the complete native
     * layer table - {@code attachLayerAfter(CHAT, ...)} can find CHAT.
     */
    private static void initLayers() {
        if (layersRegistered) {
            return;
        }
        layersRegistered = true;
        HudLayerRegistrationCallback.EVENT.register(drawer -> {
            // Late layer: inserted after vanilla's chat layer; it draws only after chat (with
            // background) finishes
            drawer.attachLayerAfter(IdentifiedLayer.CHAT,
                    IdentifiedLayer.of(LATE_LAYER_ID, TotalHUDRenderer::renderLateHud));
            // Regular layer: appended at the layer table's end, above all native HUDs
            drawer.addLayer(IdentifiedLayer.of(REGULAR_LAYER_ID,
                    TotalHUDRenderer::renderRegularHud));
        });
    }

    /**
     * Registers the renderer into the channel chosen by
     * {@link #renderAfterChat()} and prepares the client access fields.
     * Call once per instance during client initialization; repeat calls
     * register repeatedly. Subclasses overriding must call {@code super.init()},
     * or they neither enter a render channel nor get the base initialization.
     */
    public void init() {
        initLayers();
        minecraft = MinecraftClient.getInstance();
        shouldRender = false;
        // The layers are declared; this only decides which side the instance belongs to: both
        // sides' gating and exception handling funnel in one place each
        if (renderAfterChat()) {
            LATE.add(this);
        } else {
            ALL.add(this);
        }
    }

    /**
     * The regular layer's per-frame entry - iterates {@link #ALL},
     * refreshes the client fields, gates on environment, then calls the draw
     * extension point. An instance that throws only skips itself.
     *
     * <p>The per-instance gating is structurally identical to the late layer's
     * (see {@link #renderLateHud}): both funnel here, subclasses need no
     * try/catch of their own, and one frame's failure does not drag the other
     * HUDs down.
     *
     * @param context the current HUD draw context
     * @param tickCounter the current render tick counter (kept for the layer callback contract)
     */
    private static void renderRegularHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (TotalHUDRenderer renderer : ALL) {
            renderer.minecraft = mc;
            renderer.textRenderer = mc.textRenderer;
            if (renderer.textRenderer == null) {
                continue;
            }
            try {
                if (renderer.shouldRenderHud()) {
                    renderer.onRender(context);
                }
            } catch (Exception e) {
                // One frame's failure must not drag the other HUDs, but a bare console stack is
                // not enough either: go through the shared LOGGER so the log shows which HUD
                // failed
                NoMoreZombies.LOGGER.error("[HUD] {} 本帧渲染失败", renderer.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * The per-frame draw extension point - called on both the regular and
     * late layers after the environment gate passes and {@link #minecraft} /
     * {@link #textRenderer} are refreshed.
     * Implementations draw only their own content; exceptions are caught by
     * the base class and never block other HUDs.
     *
     * @param context the current HUD draw context
     */
    public abstract void onRender(DrawContext context);

    /**
     * The environment gate extension point - called each frame before
     * {@link #onRender(DrawContext)}; the default accepts only inside a
     * Zombies game. Subclasses may override to add map or other limits;
     * if they also adopt {@link #shouldRender} or config switches, they must
     * compose explicitly.
     *
     * @return whether this frame may call {@code onRender}
     */
    protected boolean shouldRenderHud() {
        return PlayerUtils.isInZombies();
    }

    /**
     * The layer-choice extension point - called once at {@link #init()};
     * returning {@code true} registers into the post-chat late layer,
     * otherwise the regular layer.
     * Changing the return value after registration does not migrate channels.
     *
     * @return whether {@link #renderLateHud(DrawContext, RenderTickCounter)} drives it after the chat background draws
     */
    protected boolean renderAfterChat() {
        return false;
    }

    /**
     * Batch-sets {@link #shouldRender} on regular-layer instances; the late
     * layer is not in {@link #ALL} and stays untouched. This method only
     * writes the flag and never triggers drawing directly; each subclass
     * still decides by its own gating contract whether to adopt it.
     *
     * @param flag the visibility flag to write into each regular-layer instance
     */
    public static void setShouldRender(boolean flag) {
        for (TotalHUDRenderer renderer : ALL) {
            renderer.shouldRender = flag;
        }
    }

    /**
     * The unified post-chat entry - refreshes the late instances' client
     * fields each frame, gates on environment, then calls the draw extension
     * point. An instance that throws only skips itself;
     * {@code tickCounter} matches the Fabric layer signature and takes no part
     * in computation currently.
     *
     * @param context the current HUD draw context
     * @param tickCounter the current render tick counter (kept for the layer callback contract)
     */
    public static void renderLateHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (TotalHUDRenderer renderer : LATE) {
            renderer.minecraft = mc;
            renderer.textRenderer = mc.textRenderer;
            if (renderer.textRenderer == null) {
                continue;
            }
            try {
                if (renderer.shouldRenderHud()) {
                    renderer.onRender(context);
                }
            } catch (Exception e) {
                NoMoreZombies.LOGGER.error("[HUD] {} 本帧渲染失败(晚渲染层)", renderer.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Draws with (x, y) as the anchor, scaled by scale, supporting the HUD
     * editor's move/zoom. {@code scale <= 0} is treated as 1 (original size);
     * when {@code |scale-1|} is tiny the matrix transform is skipped and it
     * draws directly, saving a pointless push. Scaling uses "translate, scale,
     * translate back to the anchor" - the anchor stays pixel-still across the
     * zoom.
     *
     * @param context the draw context
     * @param x the HUD's top-left anchor x
     * @param y the HUD's top-left anchor y
     * @param scale the scale factor ({@code <= 0} or ~1 treated as original size)
     * @param draw the actual drawing operation
     */
    public static void drawScaled(DrawContext context, int x, int y, float scale, Runnable draw) {
        if (scale <= 0) {
            scale = 1.0f;
        }
        if (Math.abs(scale - 1.0f) < 0.001f) {
            draw.run();
            return;
        }
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-x, -y, 0);
        draw.run();
        context.getMatrices().pop();
    }
}