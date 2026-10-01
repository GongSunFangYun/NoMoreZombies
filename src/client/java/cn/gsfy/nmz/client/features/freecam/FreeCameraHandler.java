package cn.gsfy.nmz.client.features.freecam;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;

/**
 * Free-camera handler—mirrors {@code ZoomHandler}'s per-tick three-stage
 * enable/disable polling (there is no value-change callback to copy), and
 * works only inside a Zombies game with the config on.
 *
 * <p>Lifecycle: {@code wantActive} is computed from "config + in-game
 * gate". Enabling creates the stand-in camera and switches rendering and
 * the crosshair to it; disabling restores the original camera entity.
 * Whether the player is frozen and whether the camera flies are two
 * orthogonal sub-options:
 * <ul>
 *  <li>{@code playerMovement} off (default): the player is fully frozen and
 *  the camera flies freely with WASD and the mouse;</li>
 *  <li>{@code playerMovement} on: the player moves normally and the camera
 *  stays put as a stationary viewpoint;</li>
 *  <li>{@code playerInputs} off (default): attack/break/use/swing are all
 *  blocked while the camera is active, preventing blind misclicks.</li>
 * </ul>
 */
public final class FreeCameraHandler {

    /** Global singleton. Handler-side methods read its applied state; there
     *  is no separate get() channel. */
    public static final FreeCameraHandler INSTANCE = new FreeCameraHandler();

    /** Whether freecam is currently active: the camera entity is created and
     *  owns rendering. */
    private boolean applied;

    /** Frozen input. In 1.21.4 {@code Input} is a concrete class whose
     *  constructor already sets {@code playerInput=PlayerInput.DEFAULT} (all
     *  false), every boolean false and every float 0—so one
     *  {@code new Input()} is exactly the perfect empty input, and the
     *  player's tick reads it without moving a muscle. */
    private static final Input FROZEN_INPUT = new Input();

    /** The frozen input instance. FreeCameraPlayerMixin swaps it into
     *  {@code this.input} during the player tick. */
    public static Input getFrozenInput() {
        return FROZEN_INPUT;
    }

    /** The player's real input instance (captured before freezing;
     *  KeyboardInput is stateless and re-reads options each tick, so reusing
     *  it across player instances is safe). If the mixin's symmetric
     *  restore (frozen on tick HEAD, real on RETURN) is ever broken by an
     *  exception or interrupt, a frozen instance lingers—this field
     *  guarantees the real input is always recoverable, not polluted by a
     *  leftover. */
    private static Input playerRealInput;

    /**
     * FreeCameraPlayerMixin saves the real input before the player tick.
     * The frozen instance must not be written back here, or a later
     * restore would always get an empty input.
     *
     * @param current the input instance the player currently holds
     */
    public static void captureRealInput(Input current) {
        if (current != FROZEN_INPUT) {
            playerRealInput = current;
        }
    }

    /** The player's real input (for restoring from a freeze; captured before
     *  the first freeze, normally non-null). */
    public static Input getRealInput() {
        return playerRealInput != null ? playerRealInput : FROZEN_INPUT;
    }

    /**
     * Cleans up a stray frozen input at the end of each tick or on disable,
     * in case an exception or interrupt left the player permanently unable
     * to move.
     *
     * @param mc the current client
     */
    public static void restoreRealInput(MinecraftClient mc) {
        if (mc.player != null && mc.player.input == FROZEN_INPUT && playerRealInput != null) {
            mc.player.input = playerRealInput;
        }
    }

    private FreeCameraHandler() {
    }

    /** Hooks the per-tick enable/disable poll (freecam has no value-change
     *  callback, so config and in-game gate are re-checked every frame). */
    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** Whether freecam is currently active (queried by mixins and the HUD). */
    public static boolean isActive() {
        return INSTANCE.applied;
    }

    /** Whether the player should be frozen (mouse turning routed to the
     *  camera) = active and "allow player movement" off. */
    public static boolean shouldPreventMovement() {
        return isActive() && !GlobalConfig.FreeCam.PLAYER_MOVEMENT.getBooleanValue();
    }

    /** Whether player interaction should be blocked = active and "allow
     *  player inputs" off. */
    public static boolean shouldPreventInputs() {
        return isActive() && !GlobalConfig.FreeCam.PLAYER_INPUTS.getBooleanValue();
    }

    /**
     * Whether, with freecam active, the target entity is the local player
     * itself. In normal play the crosshair can never point at yourself (the
     * ray starts at the eyes and goes forward; hitting your own hitbox is
     * geometrically impossible), but under freecam the camera and body are
     * separate, so the crosshair can point at your own frozen body—and then
     * the attack/interact target equals the local player, the client sends
     * an interaction packet aimed at itself, and the server hard-rejects it
     * (Hypixel kicks with "Cannot interact with self!", a suspected
     * anti-cheat misjudgment with ban risk).
     *
     * <p>Unconditionally blocked here (independent of "allow player
     * inputs"—blocked even with it on): target is self → cut at the source,
     * the packet never goes out.
     */
    public static boolean isSelfTarget(Entity target) {
        if (!isActive() || target == null) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && target == mc.player;
    }

    /**
     * Routes mouse deltas the local player received to the stand-in
     * camera, via FreeCameraLookMixin.
     *
     * @param yawChange horizontal rotation delta
     * @param pitchChange vertical rotation delta
     */
    public static void rotateCamera(float yawChange, float pitchChange) {
        CameraEntity.rotateCamera(yawChange, pitchChange);
    }

    /** Force-disable (a disconnect/leave safety net hooked on DISCONNECT):
     *  restore the camera immediately so nothing lingers. */
    public static void forceDisable() {
        if (INSTANCE.applied) {
            CameraEntity.removeCamera(MinecraftClient.getInstance());
            INSTANCE.applied = false;
        }
    }

    /** Per-tick enable/disable poll (the wantActive/applied three-stage
     *  pattern), also driving camera flight and cleaning up residual
     *  input. */
    private void onClientTick(MinecraftClient mc) {
        boolean wantActive = GlobalConfig.QoL.FREE_CAMERA_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies()
                && mc.world != null && mc.player != null;

        if (wantActive && !this.applied) {
            CameraEntity.setCameraState(mc);
            this.applied = true;
        } else if (!wantActive && this.applied) {
            CameraEntity.removeCamera(mc);
            this.applied = false;
        }

        // The camera flies only when "allow player movement" is off (player
        // frozen); on, it's a stationary viewpoint.
        if (this.applied && !GlobalConfig.FreeCam.PLAYER_MOVEMENT.getBooleanValue()) {
            CameraEntity.movementTick();
        }

        // Fallback: a frozen input should exist only while freecam is
        // active. If the symmetric restore is interrupted, a frozen instance
        // lingers, and WASD/sneak/jump stop working after freecam is off
        // (standing at attention). Clean up unconditionally each tick to
        // self-heal.
        if (!this.applied) {
            restoreRealInput(mc);
        }
    }
}