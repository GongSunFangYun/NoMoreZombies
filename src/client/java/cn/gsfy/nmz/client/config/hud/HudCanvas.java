package cn.gsfy.nmz.client.config.hud;

import cn.gsfy.nmz.NoMoreZombies;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import cn.gsfy.nmz.client.config.GlobalConfig;

/**
 * The HUD editor's central <b>canvas</b>: shrinks the current game picture
 * (world + vanilla HUD) into a rectangle so the player arranges HUDs
 * against the real scene instead of guessing over a black or solid backdrop
 *
 * <p><b>Current role</b>: the editor canvas currently paints a static
 * backdrop image ({@code hud_editor_background.png}), and this class's full
 * sourcing implementation is kept intact--swap the editor's backdrop
 * drawing back to {@code HudCanvas.render} and it reconnects.
 * {@link #release()} is still called when the editor closes (see
 * {@code HUDEditor.closeEditor})
 *
 * <p><b>Why the captured picture is correct</b>: in {@code GameRenderer.render},
 * the screen's {@code renderWithTooltip} happens <b>after</b> world and
 * vanilla HUD have been drawn into the main framebuffer
 * ({@code drawContext.draw()} then {@code RenderSystem.clear(256)} then the
 * screen pass), and the start of each frame clears once more--so reading
 * the main framebuffer in this method always yields the finished current
 * frame, and what the editor itself drew last frame never bleeds in.
 * For the same reason this method must be called at the <b>very start</b>
 * of the editor's {@code render()}, before the editor paints anything
 *
 * <p><b>Four sourcing modes</b> ({@code GlobalConfig.Hud.HUD_CANVAS_MODE},
 * switchable in the editor):
 * <ul>
 *  <li>{@link HudCanvasMode#LIVE_GPU} (default)--blit the main framebuffer
 *  <b>into an owned framebuffer</b> (rescaling along the way), then draw
 *  that one's color attachment as a GUI texture: GPU sampling, zero CPU
 *  readback, <b>a fresh image every frame</b>; this is the streaming
 *  default. The blit step cannot be skipped--during screen rendering the
 *  main framebuffer is the current draw target, and sampling its own color
 *  attachment is the GL "render target also used as texture" feedback
 *  loop, undefined by the spec. Because the GUI orthographic projection
 *  points y down while GL texture v points up, drawing applies a y-flip
 *  matrix to set the picture upright</li>
 *  <li>{@link HudCanvasMode#LIVE_SNAPSHOT}--one synchronous readback via
 *  {@link ScreenshotRecorder#takeScreenshot(Framebuffer)}, downsampled and
 *  uploaded as a dynamic texture. One {@code glReadPixels} per sampling
 *  cycle, much pricier than the GPU path, but entirely independent of GL
 *  texture coordinate conventions ({@code takeScreenshot} already
 *  {@code mirrorVertically()}s internally); the fallback if the GPU path
 *  ever misrenders. Sampling frequency is governed by
 *  {@code GlobalConfig.Hud.HUD_CANVAS_FPS}</li>
 *  <li>{@link HudCanvasMode#FROZEN}--capture once when the editor opens,
 *  then freeze; cheapest, fits cases that only care about placement, not
 *  whether the backdrop moves</li>
 *  <li>{@link HudCanvasMode#OFF}--no picture at all, just a dark backdrop
 *  (placeholder)</li>
 * </ul>
 *
 * <p><b>Holds no editor state</b>: this class owns only "texture +
 * sampling throttle"; position and size all come from the caller
 */
public final class HudCanvas {

    /** Texture slot for the main framebuffer's color attachment (always reflects the current frame, never needs refreshing) */
    private static final Identifier LIVE_TEXTURE_ID = Identifier.of(NoMoreZombies.MOD_ID, "hud_canvas_live");
    /** Texture slot for the downsampled screenshot dynamic texture */
    private static final Identifier SNAPSHOT_TEXTURE_ID = Identifier.of(NoMoreZombies.MOD_ID, "hud_canvas_snapshot");

    /**
     * Max sampled width in screenshot mode (physical pixels): readback cost
     * and texture memory both cap here--the canvas is only a preview and
     * does not need native resolution
     */
    private static final int MAX_SNAPSHOT_WIDTH = 640;

    /** GL constants--avoids pulling in LWJGL's GL11 for a few enums (the only ones this class uses) */
    private static final int GL_FRAMEBUFFER = 36160;
    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_COLOR_BUFFER_BIT = 16384;
    private static final int GL_LINEAR = 9729;

    private static final LiveSource LIVE = new LiveSource();
    private static final SnapshotSource SNAPSHOT = new SnapshotSource();

    /** Last sampling time (wall-clock ms)--only used to throttle screenshot mode */
    private static long lastCaptureMs;

    /** Whether frozen mode still needs a fresh capture (set on init and in {@link #release()}, cleared after one capture) */
    private static boolean dirty = true;

    private HudCanvas() {
    }

    /**
     * Canvas size to sampling size (physical pixels): scaled up by the GUI
     * scale factor, width capped at {@value #MAX_SNAPSHOT_WIDTH}, height
     * following at the original aspect ratio
     *
     * <p>Both the GPU and the screenshot path need this step; extracting it
     * keeps "cap width, scale height" to one source of truth--when each
     * path wrote its own copy, any change to the factor on one side would
     * blur the preview in exactly one mode
     *
     * @param mc current client
     * @param w canvas logical width
     * @param h canvas logical height
     * @return {target width, target height}, both in physical pixels
     */
    private static int[] targetSize(MinecraftClient mc, int w, int h) {
        double guiScale = Math.max(1.0, mc.getWindow().getScaleFactor());
        int wantW = Math.clamp((int) Math.round(w * guiScale), 1, MAX_SNAPSHOT_WIDTH);
        int wantH = Math.max(1, (int) Math.round((double) wantW * h / Math.max(1, w)));
        return new int[]{wantW, wantH};
    }

    /**
     * Draws the current game picture into the canvas rectangle--
     * <b>must be called at the very start of the editor's {@code render()}</b>
     *
     * @param ctx draw context
     * @param x canvas top-left X (screen pixels)
     * @param y canvas top-left Y (screen pixels)
     * @param w canvas width (screen pixels)
     * @param h canvas height (screen pixels)
     */
    public static void render(DrawContext ctx, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        HudCanvasMode mode = currentMode();

        if (mode == HudCanvasMode.OFF) {
            drawPlaceholder(ctx, x, y, w, h);
            return;
        }

        if (mode == HudCanvasMode.LIVE_GPU) {
            if (LIVE.ensure(mc, w, h)) {
                LIVE.draw(ctx, x, y, w, h);
            } else {
                drawPlaceholder(ctx, x, y, w, h);
            }
            return;
        }

        // The snapshot and frozen paths share one dynamic texture; only the
        // re-capture timing differs
        if (!SNAPSHOT.ensure(mc, w, h)) {
            drawPlaceholder(ctx, x, y, w, h);
            return;
        }
        if (shouldCapture(mode)) {
            SNAPSHOT.capture(mc);
        }
        SNAPSHOT.draw(ctx, x, y, w, h);
    }

    /** Current sourcing mode (the config read lives here so callers need not care about MaLiLib's option type) */
    public static HudCanvasMode currentMode() {
        Object value = GlobalConfig.Hud.HUD_CANVAS_MODE.getOptionListValue();
        return value instanceof HudCanvasMode m ? m : HudCanvasMode.LIVE_GPU;
    }

    /**
     * Call when the editor closes: hands the dynamic textures back to the
     * texture manager
     *
     * <p>{@link LiveSource}'s GL id belongs to the main framebuffer, and
     * releasing that would be a <b>disaster</b> (the picture goes black),
     * so that texture shell overrides {@code close()}/{@code clearGlId()}
     * into no-ops and unregistering both here is safe
     */
    public static void release() {
        LIVE.release();
        SNAPSHOT.release();
        lastCaptureMs = 0L;
        dirty = true;
    }

    /** Sampling throttle: only screenshot and frozen modes need it; returns whether this call should re-capture */
    private static boolean shouldCapture(HudCanvasMode mode) {
        long now = System.currentTimeMillis();
        if (mode == HudCanvasMode.FROZEN) {
            if (!dirty) {
                return false;
            }
            dirty = false;
            lastCaptureMs = now;
            return true;
        }
        int fps = (int) Math.round(GlobalConfig.Hud.HUD_CANVAS_FPS.getDoubleValue());
        if (fps <= 0) {
            // fps=0: equivalent to frozen--capture one frame per dirty flag, the power-sipping mode
            if (!dirty) {
                return false;
            }
            dirty = false;
            lastCaptureMs = now;
            return true;
        }
        long interval = 1000L / fps;
        if (now - lastCaptureMs < interval) {
            return false;
        }
        lastCaptureMs = now;
        return true;
    }

    /**
     * Draws a dark backdrop plus one line of text when no picture is
     * available, so the middle never sits empty and "unavailable" can't be
     * mistaken for "broken".
     * The hint line uses translation key
     * {@code nomorezombies.hudeditor.canvas.unavailable}, shared with
     * {@code HUDEditor.drawCanvasMissing}
     */
    private static void drawPlaceholder(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0xFF101014);
        ctx.drawBorder(x, y, w, h, 0xFF3A3A44);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) {
            return;
        }
        String hint = Text.translatable("nomorezombies.hudeditor.canvas.unavailable").getString();
        ctx.drawTextWithShadow(mc.textRenderer, hint,
                x + (w - mc.textRenderer.getWidth(hint)) / 2, y + h / 2 - mc.textRenderer.fontHeight / 2, 0xFF808088);
    }

    // ----Source one: copy the main framebuffer into an owned framebuffer, then draw it as a GUI texture----

    /**
     * Shell around the live-picture texture--only borrows another
     * framebuffer's GL id, <b>does not own</b> it:
     * {@link AbstractTexture#close()} and {@link AbstractTexture#clearGlId()}
     * are overridden into no-ops so the texture manager's unregister / close
     * cannot delete the borrowed texture (deleting it blacks out the whole
     * picture)
     */
    private static final class FramebufferTexture extends AbstractTexture {

        /** Points this shell at the given GL texture id (the id changes when the framebuffer is rebuilt; re-point then) */
        void bindTo(int glId) {
            this.glId = glId;
        }

        /** No-op: this GL id is not owned by this object, releasing it would destroy the picture */
        @Override
        public void clearGlId() {
            // No-op--reason in the method comment above
        }

        /** No-op: same as {@link #clearGlId()} */
        @Override
        public void close() {
            // No-op--same as clearGlId()
        }
    }

    /**
     * GPU direct sampling: blit the main framebuffer <b>into an owned
     * framebuffer</b> (rescaling along the way), then draw that texture
     *
     * <p><b>Why not sample the main framebuffer's color attachment
     * directly</b>: during screen rendering the main framebuffer is the
     * current draw target, and sampling its own color attachment is the GL
     * "render target also used as texture" feedback loop, undefined by the
     * spec--best case occasional glitching, worst case a fully black block.
     * The extra blit sidesteps it: the owned framebuffer is only ever a
     * sampling source, never a draw target, so the result is deterministic
     *
     * <p>The blit runs on the GPU and rescales along the way (one
     * full-screen copy down to a few hundred pixels of texture), so it can
     * happen <b>every frame</b>--that is the "streaming" implementation;
     * {@code hudCanvasFps} only governs the snapshot and frozen paths
     */
    private static final class LiveSource {

        private final FramebufferTexture texture = new FramebufferTexture();
        private boolean registered;
        private int boundGlId = -1;
        /** Owned copy framebuffer: created by this class, released by this class */
        private Framebuffer copy;
        private int copyW;
        private int copyH;
        private int texW;
        private int texH;

        /** Ensures the copy framebuffer and texture are ready (sized to the canvas on demand) and completes this frame's blit; returns whether drawing can proceed */
        boolean ensure(MinecraftClient mc, int w, int h) {
            if (mc == null) {
                return false;
            }
            Framebuffer src = mc.getFramebuffer();
            if (src == null || src.fbo <= 0 || src.textureWidth <= 0 || src.textureHeight <= 0) {
                return false;
            }
            int[] size = targetSize(mc, w, h);
            int wantW = size[0];
            int wantH = size[1];

            if (copy == null || wantW != copyW || wantH != copyH) {
                if (copy != null) {
                    copy.delete();
                    copy = null;
                }
                copy = new SimpleFramebuffer(wantW, wantH, false);
                copyW = wantW;
                copyH = wantH;
                texW = wantW;
                texH = wantH;
            }

            // GPU-side copy: main picture into the owned framebuffer (GL_LINEAR
            // downsamples along the way). The blit changes the read/draw
            // framebuffer bindings, so the main framebuffer must be bound back
            // afterwards--screen rendering assumes it is the current draw
            // target, and leaving it swapped would paint the rest of this
            // frame's GUI into our copy
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, src.fbo);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copy.fbo);
            GlStateManager._glBlitFrameBuffer(0, 0, src.textureWidth, src.textureHeight,
                    0, 0, copyW, copyH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
            // Bind the main framebuffer back with GL_FRAMEBUFFER (sets read and
            // draw at once): binding draw only would leave the read binding
            // pointing at the copy, and later code relying on it would read
            // the wrong source
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, src.fbo);

            if (!registered) {
                mc.getTextureManager().registerTexture(LIVE_TEXTURE_ID, texture);
                registered = true;
            }
            if (boundGlId != copy.getColorAttachment()) {
                texture.bindTo(copy.getColorAttachment());
                boundGlId = copy.getColorAttachment();
            }
            return true;
        }

        /**
         * Draws into the canvas rectangle: the whole texture scaled to w x h
         *
         * <p>A y-flip is required: the GUI's orthographic projection points
         * y down while GL texture v points up, so drawing directly would be
         * upside down. The trick is to first move the origin to the
         * rectangle's <b>bottom</b> edge, then scale y negatively--in local
         * coordinates y=0 is then the screen's bottom edge, and v=0 is
         * exactly the image's bottom edge; the two line up and the picture
         * is upright
         */
        void draw(DrawContext ctx, int x, int y, int w, int h) {
            MatrixStack matrices = ctx.getMatrices();
            matrices.push();
            matrices.translate(x, y + h, 0);
            matrices.scale(1.0f, -1.0f, 1.0f);
            ctx.drawTexture(RenderLayer::getGuiTextured, LIVE_TEXTURE_ID,
                    0, 0, 0.0f, 0.0f, w, h, texW, texH, texW, texH);
            matrices.pop();
        }

        /** Unregisters the texture slot and releases the owned framebuffer (the GL id itself is ours and must be deleted explicitly) */
        void release() {
            if (registered) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(LIVE_TEXTURE_ID);
                registered = false;
                boundGlId = -1;
            }
            if (copy != null) {
                copy.delete();
                copy = null;
                copyW = 0;
                copyH = 0;
                texW = 0;
                texH = 0;
            }
        }
    }

    // ----Source two: screenshot + downsample + dynamic texture (fallback path)----

    /** Screenshot downsample: one synchronous readback per cycle, expensive but stable, and the flip problem is solved by vanilla {@code takeScreenshot} */
    private static final class SnapshotSource {

        private NativeImageBackedTexture texture;
        private int texW;
        private int texH;

        /** Ensures the dynamic texture exists and matches the canvas size; returns whether drawing can proceed */
        boolean ensure(MinecraftClient mc, int w, int h) {
            if (mc == null || mc.getFramebuffer() == null) {
                return false;
            }
            // The GUI scale factor is a double (Window.getScaleFactor()): canvas
            // logical pixels times it are physical pixels, and building the
            // texture at physical resolution keeps it sharp when scaled for
            // display
            int[] size = targetSize(mc, w, h);
            int targetW = size[0];
            int targetH = size[1];
            if (texture != null && targetW == texW && targetH == texH) {
                return true;
            }
            if (texture != null) {
                mc.getTextureManager().destroyTexture(SNAPSHOT_TEXTURE_ID);
                texture.close();
                texture = null;
            }
            texW = targetW;
            texH = targetH;
            texture = new NativeImageBackedTexture(new NativeImage(texW, texH, false));
            mc.getTextureManager().registerTexture(SNAPSHOT_TEXTURE_ID, texture);
            return true;
        }

        /** Reads back the current frame, downsamples into the dynamic texture and uploads */
        void capture(MinecraftClient mc) {
            if (mc == null || texture == null) {
                return;
            }
            Framebuffer fb = mc.getFramebuffer();
            NativeImage target = texture.getImage();
            if (fb == null || target == null) {
                return;
            }
            NativeImage full = ScreenshotRecorder.takeScreenshot(fb);
            // NativeImage is AutoCloseable: the read-back full image must be
            // closed after use, otherwise every frame leaks one block of
            // native memory; try-with-resources closes it on the normal path
            // and on both early-return branches
            try (full) {
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), target);
                texture.upload();
            } catch (Exception e) {
                NoMoreZombies.LOGGER.warn("[HUD画布] 截图降采样失败，本帧保持上一张", e);
            }
        }

        /** Draws into the canvas rectangle--{@code takeScreenshot} already mirrored vertically, no flip needed here */
        void draw(DrawContext ctx, int x, int y, int w, int h) {
            ctx.drawTexture(RenderLayer::getGuiTextured, SNAPSHOT_TEXTURE_ID,
                    x, y, 0.0f, 0.0f, w, h, texW, texH, texW, texH);
        }

        /** Unregisters the texture slot and hands the dynamic texture's memory back */
        void release() {
            if (texture != null) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(SNAPSHOT_TEXTURE_ID);
                texture.close();
                texture = null;
                texW = 0;
                texH = 0;
            }
        }
    }
}
