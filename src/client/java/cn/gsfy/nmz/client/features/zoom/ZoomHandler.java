package cn.gsfy.nmz.client.features.zoom;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * Smooth zoom (a simplified Zoomify)—zoom is just FOV division, injected by
 * {@code ZoomMixin} into {@code GameRenderer.getFov}; the world and the
 * held item zoom together, the crosshair does not move.
 *
 * <p>The state machine is ported from Zoomify's
 * {@code TransitionInterpolator}: t∈[0,1] is the zoom progress, advanced
 * each tick by "1 / animation duration" as a linear prev/current; the
 * render frame first interpolates t, then applies the current direction's
 * easing curve, and finally computes the divisor:
 * {@code divisor = 1 / lerp(eased(t), 1, 1/zoom)}.
 * On a mid-animation direction reversal, "new curve ∘ old curve⁻¹" is used
 * to reproject prev/current into the new curve's linear space
 * ({@code inverse()} composed with {@code apply()}), so the render
 * interpolation does not jump; zoom-out automatically uses the selected
 * method's opposite curve
 * ({@code GlobalConfig.ZoomEasing#opposite()}, e.g. ease-out selected →
 * zoom-in ease-out / zoom-out ease-in).
 *
 * <p>Keys are read straight from GLFW physical key state
 * ({@link KeybindMulti#isKeyDown(int)}, independent of modifiers, equivalent
 * to vanilla {@code KeyBinding#isPressed()})—holding Shift/Ctrl/WASD or any
 * combo does not disturb the zoom: HOLD reads the level → hold to zoom in,
 * release to restore; TOGGLE reads a self-maintained edge (keyDownPrev) →
 * press to zoom in, press again to restore.
 * Malilib's isKeybindHeld/isPressed state machine is not used: its default
 * KeybindSettings.DEFAULT has allowExtraKeys=false and would misjudge a
 * combo as not pressed (the root cause of sneak with Shift+C not working).
 *
 * <p>Gate: master switch on and in a Zombies game (a static predicate, for
 * mixins to query).
 */
public final class ZoomHandler {

    /** The global singleton: mixins reference it directly; no separate get() channel. */
    public static final ZoomHandler INSTANCE = new ZoomHandler();

    /** The per-tick advance step's base (20 TPS ≈ 0.05s/frame, matching
     *  Zoomify's lastFrameDuration=0.05). */
    private static final double TICK_SECONDS = 0.05;

    /** Wheel hot-adjust zoom range (matches the config's initialZoom) and
     *  per-notch step (±1x/notch). */
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 10.0;
    private static final double SCROLL_ZOOM_STEP = 1.0;
    /**
     * Wheel hot-adjust smoothing factor (ported from Zoomify's
     * SmoothInterpolator): Zoomify's original formula normalizes by
     * lastFrameDuration, with the advance = diff × smoothness / 0.05 × tickDelta;
     * this port advances at a fixed 20Hz and multiplies by smoothness each
     * tick without frame-length normalization.
     * Default 0.37 → about 0.2s to converge most of the way; smaller values
     * feel "stickier" (more inertia), larger values arrive faster.
     * Exponential decay steps big when the distance is big and small when it
     * is small—a more natural feel than a fixed ratio.
     */
    private static final double SCROLL_SMOOTHNESS = 0.37;

    private final IKeybind keybind;

    // Key state
    private boolean zooming;
    private boolean keyDownPrev;

    // Wheel hot-adjust: session-level temporary zoom (0 = no override, use
    // config; >0 = wheel's temporary value, not written to config).
    // The session's full retraction clears it → next activation starts from
    // the initial zoom again (Zoomify-style "release restores the original").
    private double zoomOverride;
    /** The smoothed current zoom: each tick decays exponentially toward the
     *  target (effectiveZoom). During wheel hot-adjust the target changes
     *  discretely by ±1 per notch; applying the divisor directly would jump
     *  (a stutter feel); smoothing makes the divisor transition
     *  continuously, so wheel zoom feels like dragging a slider. */
    private double currentZoom = 1.0;
    /** The previous tick's currentZoom, used for the render frame's sub-tick
     *  interpolation (same as the animation's prev/current, removing the
     *  20Hz stair-step feel). */
    private double prevCurrentZoom = 1.0;

    // Animation state (ported from Zoomify's TransitionInterpolator)
    private double prev;
    private double current;
    private double prevTarget;
    private boolean justSwappedTransition;
    private GlobalConfig.ZoomEasing activeTransition;
    private GlobalConfig.ZoomEasing inactiveTransition;

    private ZoomHandler() {
        this.keybind = GlobalConfig.Zoom.ZOOM_KEY.getKeybind();
        // Safety fallback for the first render frame possibly arriving before
        // the first tick (the tick re-parses by config every frame)
        this.activeTransition = GlobalConfig.ZoomEasing.EASE_OUT_EXP;
        this.inactiveTransition = GlobalConfig.ZoomEasing.EASE_IN_EXP;
    }

    /** Called once by NoMoreZombiesClient at client init to register this
     *  singleton's tick callback; entering/leaving the world does not
     *  re-register. */
    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** Whether currently active: master switch on and in a Zombies game. */
    public static boolean isActive() {
        return GlobalConfig.QoL.ZOOM_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }

    /** The current effective zoom: the wheel hot-adjust temporary value wins
     *  (clamped), otherwise the config's initial zoom (the wheel does not
     *  write config). */
    private double effectiveZoom() {
        if (this.zoomOverride > 0.0) {
            return MathHelper.clamp(this.zoomOverride, MIN_ZOOM, MAX_ZOOM);
        }
        return MathHelper.clamp(GlobalConfig.Zoom.INITIAL_ZOOM.getDoubleValue(), MIN_ZOOM, MAX_ZOOM);
    }

    /**
     * ZoomMixin reads the FOV divisor every render frame; this only
     * interpolates tick state, it does not advance the animation.
     *
     * @param partialTicks this render frame's interpolation between the
     *   previous and current client ticks
     * @return the FOV divisor; values above 1 appear as zoomed in
     */
    public float getZoomDivisor(float partialTicks) {
        // Sub-tick interpolation: the animation channel and the wheel channel
        // (prevCurrentZoom/currentZoom) are lerped, removing the 20Hz tick
        // rate's stair-step feel—matching Zoomify's dual-channel sub-tick
        // interpolation
        double zoom = Math.max(MathHelper.lerp(partialTicks, this.prevCurrentZoom, this.currentZoom), 1.0);
        // Interpolate the linear t first, then apply the current direction's
        // easing curve, then take the reciprocal (interpolate before computing
        // the divisor, avoiding non-linear acceleration)
        double t = MathHelper.clamp(MathHelper.lerp(partialTicks, this.prev, this.current), 0.0, 1.0);
        double tEased = this.activeTransition.apply(t);
        double mult = MathHelper.lerp(tEased, 1.0, 1.0 / zoom);
        return (float) (1.0 / mult);
    }

    /**
     * The mouse-wheel mixin calls this during zoom: each notch adjusts the
     * zoom temporarily by {@value #SCROLL_ZOOM_STEP}, discarded at session
     * end, never written to config; once the wheel is taken over, the
     * vanilla hotbar switch must be skipped.
     *
     * @param vertical wheel vertical notches; sign decides zoom in or out
     * @return {@code true} when this wheel event was taken over;
     *   {@code false} when not active, not zooming, or a zero delta
     */
    public boolean onMouseScroll(double vertical) {
        if (!isActive() || !this.zooming) {
            return false;
        }
        if (vertical == 0.0) {
            return false;
        }
        double base = effectiveZoom();
        this.zoomOverride = MathHelper.clamp(base + vertical * SCROLL_ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
        return true;
    }

    /** The current tick's zoom divisor (for sensitivity conversion, no
     *  render interpolation). partialTicks=1.0 → t takes current directly
     *  (the tick-level instantaneous value). */
    public float getCurrentZoomDivisor() {
        return getZoomDivisor(1.0f);
    }

    /** Per-tick poll: parse config, advance the key state machine and
     *  animation, smooth the wheel zoom, and clean up the session. */
    private void onClientTick(MinecraftClient mc) {
        boolean active = isActive();
        if (!active) {
            // Force idle outside activation: leaving / switch off leaves no
            // residue; the next activation starts from 0 with no jump
            this.zooming = false;
            this.keyDownPrev = false;
            this.prev = 0.0;
            this.current = 0.0;
            this.prevTarget = 0.0;
            this.justSwappedTransition = false;
            this.zoomOverride = 0.0;
            this.currentZoom = 1.0;
            this.prevCurrentZoom = 1.0;
        }
        if (active) {
            GlobalConfig.ZoomKeyBehaviour kb = GlobalConfig.Zoom.KEY_BEHAVIOUR.getOptionListValue()
                    instanceof GlobalConfig.ZoomKeyBehaviour k ? k : GlobalConfig.ZoomKeyBehaviour.HOLD;
            GlobalConfig.ZoomEasing ease = GlobalConfig.Zoom.EASING.getOptionListValue()
                    instanceof GlobalConfig.ZoomEasing e ? e : GlobalConfig.ZoomEasing.EASE_OUT_EXP;
            float timeIn = MathHelper.clamp((float) GlobalConfig.Zoom.ZOOM_IN_TIME.getDoubleValue(), 0.1f, 5.0f);
            float timeOut = MathHelper.clamp((float) GlobalConfig.Zoom.ZOOM_OUT_TIME.getDoubleValue(), 0.1f, 5.0f);

            if (mc.currentScreen != null) {
                // A screen open (inventory / config) force-exits zoom; sync the
                // key state to prevent a false TOGGLE edge on closing
                this.zooming = false;
                this.keyDownPrev = isZoomKeyDown();
            } else {
                boolean down = isZoomKeyDown();
                if (kb == GlobalConfig.ZoomKeyBehaviour.HOLD) {
                    // Read physical key state directly (same as
                    // KeyMapping.isDown): combos do not disturb the zoom check
                    this.zooming = down;
                } else if (down && !this.keyDownPrev) {
                    // TOGGLE: self-maintained edge detection (keyDownPrev
                    // holds the previous tick's key state)
                    this.zooming = !this.zooming;
                }
                this.keyDownPrev = down;
            }

            tickInterpolation(this.zooming ? 1.0 : 0.0, ease, timeIn, timeOut);

            // Wheel hot-adjust smoothing (ported from Zoomify's
            // SmoothInterpolator): the original normalizes by frame length,
            // this port is fixed 20Hz and multiplies by smoothness each tick
            // (the step grows and shrinks with distance).
            // prevCurrentZoom is captured before advancing, for the render
            // frame's sub-tick interpolation
            this.prevCurrentZoom = this.currentZoom;
            if (this.zoomOverride > 0.0) {
                double diff = effectiveZoom() - this.currentZoom;
                this.currentZoom += diff * SCROLL_SMOOTHNESS;
            } else {
                this.currentZoom = effectiveZoom();
            }

            // The session fully retracted (not zooming and the animation at 0)
            // → clear the hot-adjust and reset the zoom; the next session
            // starts from the initial value.
            // Do not clear "the moment the key is released": the zoom-out
            // animation must shrink by the current hot-adjust zoom; clearing
            // early would jump the zoom
            if (!this.zooming && this.current == 0.0) {
                this.zoomOverride = 0.0;
                this.currentZoom = 1.0;
                this.prevCurrentZoom = 1.0;
            }
        }
    }

    /** Whether the zoom key is held: iterates every bound key; one not pressed
     *  means not held (combos do not disturb the check). */
    private boolean isZoomKeyDown() {
        List<Integer> keys = this.keybind.getKeys();
        if (keys.isEmpty()) {
            return false;
        }
        for (int keyCode : keys) {
            if (!KeybindMulti.isKeyDown(keyCode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advances one frame of animation (ported from Zoomify's
     * TransitionInterpolator). First captures prev=current, then picks the
     * active curve by direction (zoom-in = the selected curve, zoom-out = its
     * opposite); on a direction reversal where the curve has an inverse,
     * current is reprojected through inverse(apply()) before the linear
     * advance, and the captured prev is reprojected too—the render
     * interpolation does not jump at the direction switch's instant.
     */
    private void tickInterpolation(double target, GlobalConfig.ZoomEasing ease,
                                   float timeIn, float timeOut) {
        GlobalConfig.ZoomEasing transitionOut = ease.opposite();

        this.prev = this.current;
        double currentMod = this.current;

        if (target > this.current) {
            this.activeTransition = ease;
            this.inactiveTransition = transitionOut;
            if (this.prevTarget < target && this.activeTransition.hasInverse()) {
                this.justSwappedTransition = true;
                currentMod = this.activeTransition.inverse(this.inactiveTransition.apply(currentMod));
            }
        } else if (target < this.current) {
            this.activeTransition = transitionOut;
            this.inactiveTransition = ease;
            if (this.prevTarget > target && this.activeTransition.hasInverse()) {
                this.justSwappedTransition = true;
                currentMod = this.activeTransition.inverse(this.inactiveTransition.apply(currentMod));
            }
        }
        this.prevTarget = target;

        if (this.activeTransition == GlobalConfig.ZoomEasing.INSTANT) {
            // Instant jump: prev syncs to current, the render lerp has no intermediate frame
            this.current = target;
            this.prev = target;
            return;
        }

        if (target > currentMod) {
            this.current = Math.min(currentMod + TICK_SECONDS / timeIn, target);
        } else if (target < currentMod) {
            this.current = Math.max(currentMod - TICK_SECONDS / timeOut, target);
        } else {
            this.current = target;
        }

        if (this.justSwappedTransition) {
            this.justSwappedTransition = false;
            this.prev = this.activeTransition.inverse(this.inactiveTransition.apply(this.prev));
        }
    }
}