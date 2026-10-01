package cn.gsfy.nmz.client.config.hud;

import net.minecraft.client.gui.DrawContext;

/**
 * One HUD preview: measured width and height plus the action that draws it
 * at a given position
 *
 * <p>This is the single exchange object between "editor box size" and
 * in-game anchor resolution ({@code anchorPixels}). Width and height are
 * measured by the <b>renderer itself</b> ({@code hudWidth}/{@code hudHeight},
 * or the preview-measuring {@code previewWidth}/{@code previewHeight});
 * the editor only reads it, draws it, and derives the hitbox from it--no
 * third place may compute the size again. See {@code README} section 3.10,
 * "box = hitbox = content rectangle"
 *
 * <p>{@link #width}/{@link #height} are in <b>virtual screen pixels</b>
 * (scale not yet applied, canvas ratio not yet applied); the canvas and
 * in-game rendering each multiply this same size by their own factor.
 * Rounding conventions are in the {@link HUDEditor} class comment
 */
public final class HudPreview {

    /** Preview width in virtual screen pixels, including shadow spill; the scoreboard has no shadow and includes none */
    public final int width;
    /** Preview height in virtual screen pixels, including shadow spill; the scoreboard has no shadow and includes none */
    public final int height;
    /** Draw action: paints the preview at (x, y) */
    public final Render render;

    /**
     * Constructs one preview
     *
     * @param width preview width (virtual screen pixels)
     * @param height preview height (virtual screen pixels)
     * @param render draw action, coordinates in virtual screen pixels
     */
    public HudPreview(int width, int height, Render render) {
        this.width = width;
        this.height = height;
        this.render = render;
    }

    /**
     * Preview draw callback: paints the preview at the given (x, y)
     *
     * <p>The coordinate convention is "content starts at (x, y)": the
     * preview's top-left corner is exactly (x, y)--HUDEditor's "anchor =
     * draw origin" placement argument rests on this
     */
    public interface Render {
        /**
         * Paints the preview
         *
         * @param ctx draw context
         * @param x top-left X (virtual screen pixels)
         * @param y top-left Y (virtual screen pixels)
         */
        void render(DrawContext ctx, int x, int y);
    }
}
