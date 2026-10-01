package cn.gsfy.nmz.client.features.freecam;

import cn.gsfy.nmz.client.config.GlobalConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.stat.StatHandler;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Free-camera stand-in entity. Extends {@link ClientPlayerEntity} to inherit
 * the full player field and behavior set, but <b>never joins the world's
 * entity list</b> (vanilla never calls its {@code tick()}), so
 * {@link FreeCameraHandler} owns its lifecycle and movement by hand.
 * Position and orientation are fully independent of the real player: on
 * enable, it copies the player's position and rotation as its spawn, and
 * from then on the camera flies freely with WASD and the mouse while the
 * player body stays frozen in place. On disable, only the
 * {@code mc.cameraEntity} reference is restored—player data was never
 * touched, so nothing lingers.
 *
 * <p>Runtime state lives entirely in static fields, not config entries. The
 * camera is treated as a spectator ({@link #isSpectator()}→true), so fog
 * rendering and the like follow spectator identity. Every move manually
 * maintains the {@code prev* / lastRender*} interpolation fields—the camera
 * never enters the world, so those fields would otherwise be dead data, and
 * a moving camera without manual updates would stutter or tear.
 */
public final class CameraEntity extends ClientPlayerEntity {

    /** The current stand-in camera (null = not enabled). Its lifecycle is
     *  owned solely by setCameraState / removeCamera. */
    @Nullable private static CameraEntity camera;
    /** The camera entity before entry (usually the local player). Restored
     *  by reference on disable. */
    @Nullable private static Entity originalCameraEntity;
    /** Chunk culling state before entry. Restored on disable, so a player
     *  who had it on manually doesn't lose it for good. */
    private static boolean cullChunksOriginal;
    /** Camera sprint state. Only scales the forward component
     *  (tweakeroo-style ×3); persists across ticks with no movement input. */
    private static boolean sprinting;
    /** Whether the pre-entry camera entity was the player itself.
     *  Needed to re-fetch {@code mc.player} when recovering from
     *  death/respawn during freecam. */
    private static boolean originalCameraWasPlayer;

    /** Base camera flight speed (blocks/tick at SPEED=1.0): copied from
     *  tweakeroo's getMoveSpeed()=0.07*10. */
    private static final double BASE_SPEED = 0.7;
    /** Ramp step per tick toward the input direction, from tweakeroo's
     *  rampAmount=0.15. */
    private static final double RAMP_AMOUNT = 0.15;
    /** Per-tick velocity decay with no input, from tweakeroo's
     *  decelerationFactor=0.4. */
    private static final double DECELERATION = 0.4;
    /** Speed divisor for diagonal movement (from tweakeroo 1.2), suppressing
     *  diagonal acceleration. */
    private static final double DIAGONAL_FACTOR = 1.2;

    /** Camera's current ramped velocity (x=forward, y=vertical, z=strafe,
     *  range [-1,1]), carried across ticks for smooth accel and decel. */
    private static Vec3d cameraMotion = Vec3d.ZERO;

    private CameraEntity(MinecraftClient mc, ClientWorld world, ClientPlayNetworkHandler netHandler,
                         StatHandler stats, ClientRecipeBook recipeBook) {
        super(mc, world, netHandler, stats, recipeBook, false, false);
    }

    /** Treat the stand-in as a spectator: fog rendering, entity rendering,
     *  and the rest follow spectator identity. */
    @Override
    public boolean isSpectator() {
        return true;
    }

    /** Whether the pre-entry camera entity was the player itself. Used by
     *  {@code isCamera()} for its deception check. */
    public static boolean originalCameraWasPlayer() {
        return originalCameraWasPlayer;
    }

    /**
     * Creates the stand-in when FreeCameraHandler flips from disabled to
     * enabled, then takes over rendering, the mouse, and the crosshair
     * source. Turns off chunk culling and hides the first-person hand (a
     * second safeguard alongside the {@code renderHand} mixin).
     *
     * @param mc the current client; no-op when the world or local player is
     *   not yet available
     */
    public static void setCameraState(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (mc.world == null || player == null) {
            return;
        }
        camera = createCameraEntity(mc, player);
        originalCameraEntity = mc.getCameraEntity();
        originalCameraWasPlayer = originalCameraEntity == mc.player;
        cullChunksOriginal = mc.chunkCullingEnabled;

        mc.setCameraEntity(camera);
        mc.chunkCullingEnabled = false;                 // Chunk culling off: see farther.
        mc.gameRenderer.setRenderHand(false);           // Hide the first-person hand (second safeguard).
    }

    /**
     * Restores the original camera, chunk culling, and inputs when
     * FreeCameraHandler disables freecam or the client disconnects, then
     * clears session state.
     *
     * @param mc the current client; still attempts to restore residual
     *   input even when the camera is already gone
     */
    public static void removeCamera(MinecraftClient mc) {
        if (camera != null) {
            // Death/respawn during freecam swaps the player instance, so
            // re-fetch mc.player on restore.
            mc.setCameraEntity(originalCameraWasPlayer ? mc.player : originalCameraEntity);
            mc.chunkCullingEnabled = cullChunksOriginal;
            mc.gameRenderer.setRenderHand(true);
        }
        FreeCameraHandler.restoreRealInput(mc);   // Fallback: clear residual frozen input, effective at once.
        camera = null;
        originalCameraEntity = null;
        sprinting = false;
        cameraMotion = Vec3d.ZERO;
    }

    /**
     * Drives camera movement each tick (only called by the handler when
     * "allow player movement" is off). Follows tweakeroo: read six keys →
     * ramp accel/decel (zero first on direction reversal) → convert to
     * world velocity by yaw → noClip move.
     *
     * <p>Note the strafe keys: A (left) = +1, D (right) = -1, opposite of
     * vanilla. This is tweakeroo's coordinate convention, and copying it
     * as-is prevents left/right flipping on turns.
     */
    public static void movementTick() {
        CameraEntity cam = camera;
        if (cam == null) {
            return;
        }
        GameOptions options = MinecraftClient.getInstance().options;
        cam.updateInterpolation();

        if (options.sprintKey.isPressed()) {
            sprinting = true;
        } else if (!options.forwardKey.isPressed() && !options.backKey.isPressed()) {
            sprinting = false;
        }

        int forward = (options.forwardKey.isPressed() ? 1 : 0) - (options.backKey.isPressed() ? 1 : 0);
        int vertical = (options.jumpKey.isPressed() ? 1 : 0) - (options.sneakKey.isPressed() ? 1 : 0);
        int strafe = (options.leftKey.isPressed() ? 1 : 0) - (options.rightKey.isPressed() ? 1 : 0);

        cameraMotion = calculateMotionWithDeceleration(cameraMotion, forward, vertical, strafe);
        Vec3d velocity = calculateVelocity(cam);

        cam.setVelocity(velocity);
        cam.move(MovementType.SELF, velocity);   // noClip: move straight through blocks.
    }

    /**
     * Computes this tick's displacement velocity from the current
     * orientation and key state.
     *
     * <p>Follows tweakeroo's convention: yaw's sin/cos split out forward
     * and lateral components, sprinting scales only the <b>forward</b>
     * component ×3, and diagonals get no extra acceleration.
     *
     * @param cam the stand-in camera (its orientation this frame)
     * @return this tick's displacement vector
     */
    private static Vec3d calculateVelocity(CameraEntity cam) {
        float yaw = cam.getYaw();
        double xFactor = Math.sin(Math.toRadians(yaw));
        double zFactor = Math.cos(Math.toRadians(yaw));
        double speed = BASE_SPEED * GlobalConfig.FreeCam.SPEED.getDoubleValue();
        // Sprint scales the forward component only (tweakeroo-style ×3).
        double forwardMotion = sprinting ? cameraMotion.x * 3.0 : cameraMotion.x;
        return new Vec3d(
                (cameraMotion.z * zFactor - forwardMotion * xFactor) * speed,
                cameraMotion.y * speed,
                (forwardMotion * zFactor + cameraMotion.z * xFactor) * speed);
    }

    /**
     * Routes mouse deltas forwarded by FreeCameraLookMixin to the stand-in
     * entity, reusing the player entity's built-in 0.15 sensitivity.
     *
     * @param yawChange horizontal rotation delta
     * @param pitchChange vertical rotation delta
     */
    public static void rotateCamera(float yawChange, float pitchChange) {
        if (camera != null) {
            camera.changeLookDirection(yawChange, pitchChange);
        }
    }

    // ---------------- Internal: create / interpolate / move ----------------

    /** Creates the stand-in camera and spawns it at the player's position
     *  and rotation (without entering the world). noClip is pre-set for
     *  through-wall flight.
     *
     *  @param mc the current client, only for world and resource manager
     *  @param player the local player the caller already null-checked—not
     *    re-fetched here as {@code mc.player}
     */
    private static CameraEntity createCameraEntity(MinecraftClient mc, ClientPlayerEntity player) {
        CameraEntity cam = new CameraEntity(mc, mc.world, player.networkHandler,
                player.getStatHandler(), player.getRecipeBook());
        cam.noClip = true;   // Through-wall flight.

        float yaw = player.getYaw();
        float pitch = player.getPitch();
        cam.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), yaw, pitch);
        cam.setRotation(yaw, pitch);
        cam.updateInterpolation();

        return cam;
    }

    /** Manually maintains the render interpolation fields: the camera never
     *  enters the world, vanilla never calls its tick, and {@code prev*} /
     *  {@code lastRender*} would be dead data—without manual updates a
     *  moving camera would stutter or tear. */
    private void updateInterpolation() {
        this.lastRenderX = this.getX();
        this.lastRenderY = this.getY();
        this.lastRenderZ = this.getZ();
        this.prevX = this.getX();
        this.prevY = this.getY();
        this.prevZ = this.getZ();
        this.prevYaw = this.getYaw();
        this.prevPitch = this.getPitch();
    }

    /**
     * Ramped motion (copied from tweakeroo's
     * {@code calculatePlayerMotionWithDeceleration}): each axis ramps
     * independently, diagonals divided by 1.2 to suppress diagonal
     * acceleration.
     */
    private static Vec3d calculateMotionWithDeceleration(Vec3d lastMotion, int forward, int vertical, int strafe) {
        double diagonal = (forward != 0 && strafe != 0) ? DIAGONAL_FACTOR : 1.0;
        return new Vec3d(
                getRampedMotion(lastMotion.x, forward) / diagonal,
                getRampedMotion(lastMotion.y, vertical) / diagonal,
                getRampedMotion(lastMotion.z, strafe) / diagonal);
    }

    /**
     * One-axis ramp (copied from tweakeroo's {@code getRampedMotion}): with
     * input, approach by RAMP_AMOUNT (zero first on direction reversal to
     * prevent drag); without input, decay by DECELERATION. Result clamped
     * to [-1,1].
     */
    private static double getRampedMotion(double current, int input) {
        if (input != 0) {
            double ramp = RAMP_AMOUNT;
            if (input < 0) {
                ramp *= -1.0;
            }
            if ((input < 0) != (current < 0.0)) {
                current = 0.0;
            }
            current = MathHelper.clamp(current + ramp, -1.0, 1.0);
        } else {
            current *= DECELERATION;
        }
        return current;
    }
}