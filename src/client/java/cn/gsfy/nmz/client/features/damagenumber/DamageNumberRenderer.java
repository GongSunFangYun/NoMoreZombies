package cn.gsfy.nmz.client.features.damagenumber;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * World-space rendering of damage/heal numbers—draws the numbers
 * {@link DamageNumberTracker} collects above the entity.
 *
 * <p>Same recipe as
 * {@link cn.gsfy.nmz.client.features.healthbar.HealthBarRenderer}: translate
 * into camera space, {@code multiply(camera.getRotation())} to face the
 * text toward the camera, then {@code scale(s,-s,s)} to flip Y back up—so
 * both face and read the same way in the world. Scale differs slightly:
 * 0.035 for numbers, 0.025 for the health bar; numbers need to stand out.
 *
 * <p>Render mode follows {@link GlobalConfig.QoL#DAMAGE_NUMBER_RENDER_MODE}:
 * the regular tier does depth testing only, so walls occlude naturally; the
 * through-walls tier uses {@code SEE_THROUGH} but still obeys the overall
 * through-wall render distance, falling back to the regular tier beyond it—
 * same distance contract as ESP and the health bar.
 *
 * <p>Unrelated to {@code NO_PARTICLES}: a number isn't a vanilla particle
 * and never enters the {@code ParticleManager}, so "remove all particles"
 * doesn't touch it. The two are separate concerns.
 */
public final class DamageNumberRenderer {

    /** Text scale: level with the health bar's 0.025, a touch larger so
     *  numbers stand out. */
    private static final float TEXT_SCALE = 0.035f;

    /** Text offset up its own space: floats the number above the entity's
     *  eye height rather than sitting on the eye. */
    private static final float TEXT_Y = 4.0f;

    /** Full-bright light {@code pack(15,15)}: readable even in the dark. */
    private static final int FULLBRIGHT = 0xF000F0;

    /** Text background. 0 means no backdrop; for a nameplate-style
     *  translucent backdrop, change to 0x40000000. */
    private static final int BACKGROUND = 0x00000000;

    /**
     * Registers the {@code AFTER_ENTITIES} render callback. Call once from
     * the client entrypoint.
     *
     * <p>Same render stage as the health bar: entities are already drawn,
     * so numbers sit above every entity.
     */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(DamageNumberRenderer::render);
    }

    /**
     * The {@code AFTER_ENTITIES} callback: locates each number by its
     * within-frame interpolation and draws it.
     *
     * <p>A failed gate returns immediately, without even fetching the
     * list. Per-frame exceptions are isolated by Fabric's callback chain;
     * they are not swallowed here—same stance as the health bar.
     *
     * @param context the current world render context
     */
    private static void render(WorldRenderContext context) {
        if (!GlobalConfig.QoL.DAMAGE_NUMBER_ENABLED.getBooleanValue() || !PlayerUtils.isInZombies()) {
            return;
        }

        List<DamageNumberParticle> particles = DamageNumberTracker.particles();
        if (particles.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null || client.world == null) {
            return;
        }

        VertexConsumerProvider vcp = context.consumers();
        if (vcp == null) {
            return;
        }
        // The matrix stack is nullable (WorldRenderContext declares it
        // @Nullable). A number must hang off the current frame's camera
        // matrix; missing it means skipping the frame rather than drawing
        // in the wrong place with an identity matrix.
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        // Overall through-wall render distance: numbers beyond it fall back
        // to depth testing. (Squared distance, to avoid a sqrt.)
        double maxDistSq = GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE.getDoubleValue();
        maxDistSq *= maxDistSq;
        boolean throughWalls = GlobalConfig.QoL.DAMAGE_NUMBER_RENDER_MODE.getOptionListValue()
                == GlobalConfig.EspRenderMode.THROUGH_WALLS;

        float tickDelta = context.tickCounter().getTickDelta(true);
        TextRenderer tr = client.textRenderer;

        for (DamageNumberParticle particle : particles) {
            float px = particle.lerpX(tickDelta);
            float py = particle.lerpY(tickDelta);
            float pz = particle.lerpZ(tickDelta);

            Text text = Text.literal(particle.text());

            matrices.push();
            matrices.translate(px - cam.x, py - cam.y, pz - cam.z);
            matrices.multiply(context.camera().getRotation());
            matrices.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

            float x = -tr.getWidth(text) / 2.0f;
            // Through-walls tier and within the overall distance = SEE_THROUGH
            // (always visible); anything else = NORMAL (depth-tested, hidden
            // behind walls).
            TextRenderer.TextLayerType layerType = throughWalls
                    && particleDistanceSq(px, py, pz, cam) <= maxDistSq
                    ? TextRenderer.TextLayerType.SEE_THROUGH
                    : TextRenderer.TextLayerType.NORMAL;

            tr.draw(text, x, TEXT_Y, particle.colour(), true,
                    matrices.peek().getPositionMatrix(), vcp,
                    layerType, BACKGROUND, FULLBRIGHT);
            matrices.pop();
        }
    }

    /**
     * Squared distance from a number to the camera, computed straight from
     * coordinates rather than via {@code Entity.squaredDistanceTo}—the
     * number is detached from its entity from spawn on, so there is no
     * entity to ask.
     *
     * @param x interpolated X of the number
     * @param y interpolated Y of the number
     * @param z interpolated Z of the number
     * @param cam camera position
     * @return squared distance
     */
    private static double particleDistanceSq(float x, float y, float z, Vec3d cam) {
        double dx = x - cam.x;
        double dy = y - cam.y;
        double dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private DamageNumberRenderer() {
    }
}