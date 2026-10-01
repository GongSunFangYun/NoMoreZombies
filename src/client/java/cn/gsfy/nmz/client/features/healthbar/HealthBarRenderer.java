package cn.gsfy.nmz.client.features.healthbar;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.esp.BossDetector;
import cn.gsfy.nmz.client.shared.esp.EntityEsp;
import cn.gsfy.nmz.client.shared.esp.EspTargets;
import cn.gsfy.nmz.client.utils.JavaUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Mob health bars—world-space text bars drawn at a mob's (non-player)
 * nametag position, {@code [###############]20/20HP} (full cells are
 * {@code #}, lost health is {@code -}). The render mode
 * ({@code GlobalConfig.QoL.HEALTH_BAR_RENDER_MODE}) picks the layer type:
 * regular = {@link TextRenderer.TextLayerType#NORMAL} (depth-tested, hidden
 * behind walls); through-walls =
 * {@link TextRenderer.TextLayerType#SEE_THROUGH} (always visible).
 *
 * <p>The render recipe matches vanilla
 * {@code EntityRenderer.renderLabelIfPresent}: translate above the head →
 * {@code multiply(camera.getRotation())} (billboard toward the camera) →
 * {@code scale(0.025, -0.025, 0.025)} → {@code textRenderer.draw(...)}.
 *
 * <p>Performance: the target list reuses {@link EspTargets}'s shared scan
 * cache (rescanned every {@link EspTargets#REFRESH_TICKS} ticks, shared with
 * ESP); per entity it does a liveness filter, a target re-check and a
 * frustum cull; no distance cap.
 */
public final class HealthBarRenderer {

    /** Bar cells: total length of full {@code #} plus lost {@code -} cells. */
    private static final int BAR_LENGTH = 15;

    /** Decimal places kept on the health numbers (integers drop the point):
     *  same accounting as damage numbers, see {@link #buildText}. */
    private static final int DECIMALS = 1;

    private static final int COLOR_WHITE = 0xFFFFFF;// Number: always white.
    private static final int COLOR_GREEN = 0x55FF55;// Regular mob, high tier: green.
    private static final int COLOR_YELLOW = 0xFFFF55;// Regular mob, mid tier: yellow.
    private static final int COLOR_RED = 0xFF5555;// Regular mob, low tier: red.
    /** Boss's high-health color (bright purple): readable at the same luminance as orange/yellow/red. */
    private static final int COLOR_PURPLE = 0xAA55FF;
    /** Boss's second-highest color (orange): kept distinct from the regular mobs' green tier. */
    private static final int COLOR_ORANGE = 0xFFA500;

    /** Full-bright lightmap {@code pack(15,15)}: readable even in the dark. */
    private static final int FULLBRIGHT = 0xF000F0;
    /** Text background (0 = no backdrop; for a nametag-style translucent backdrop use 0x40000000). */
    private static final int BACKGROUND = 0x00000000;
    /** Extra head lift: nudge to ~0.35F when a mob's own custom name overlaps the bar. */
    private static final float Y_OFFSET_EXTRA = 0.0F;
    /** Boss mobs carry a native nametag bar, so this mod's bar is lifted another 0.35F to prevent overlap. */
    private static final float BOSS_Y_OFFSET_EXTRA = 0.35F;

    /** Hooks the world render callback: text bars are drawn over heads after
     *  entity rendering ({@code AFTER_ENTITIES}), split into regular /
     *  through-walls by {@code GlobalConfig.QoL.HEALTH_BAR_RENDER_MODE}. */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(HealthBarRenderer::render);
    }

    /** The {@code AFTER_ENTITIES} callback: draws over-head bars after entities
     *  render; a failed gate returns immediately. */
    private static void render(WorldRenderContext context) {
        if (!GlobalConfig.QoL.ENTITY_HEALTH_BAR.getBooleanValue() || !PlayerUtils.isInZombies()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.textRenderer == null) {
            return;
        }
        World world = context.world();
        if (world == null) {
            return;
        }

        // Reuse the shared scan cache: shares one scan with ESP, rescanned every
        // REFRESH_TICKS ticks, idempotent within a frame.
        EspTargets.ensureScanned(world, context.camera().getPos());
        List<Entity> targets = EspTargets.getTargets();
        if (targets.isEmpty()) {
            return;
        }

        VertexConsumerProvider vcp = context.consumers();
        if (vcp == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        // Overall through-wall render distance: an entity bar beyond it falls back
        // to depth testing (squared distance, no sqrt).
        double maxDistSq = GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE.getDoubleValue();
        maxDistSq *= maxDistSq;
        float tickDelta = context.tickCounter().getTickDelta(true);
        TextRenderer tr = client.textRenderer;
        // Both the matrix stack and the frustum are nullable (WorldRenderContext
        // declares them @Nullable): a missing matrix stack skips the whole frame;
        // a null frustum means "no culling," still draw.
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }
        Frustum frustum = context.frustum();
        String suffix = Text.translatable("nomorezombies.healthbar.hpSuffix").getString();

        for (Entity entity : targets) {
            // Liveness filter: entities killed / removed between scans are no longer drawn.
            if (entity.isRemoved()) {
                continue;
            }
            // Mobs only: LivingEntity is the only one with readable health, and players are excluded.
            if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity) {
                continue;
            }
            // Target re-check (isInZombies is cached at 200ms, very cheap).
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }
            // Boss determination is gated by the "special boss bar" switch: off
            // renders bosses like regular mobs (regular three tiers, no extra
            // lift). The decision goes through {@code BossDetector.isBoss},
            // whose internal throttled scan is also driven by this call.
            boolean boss = GlobalConfig.QoL.BOSS_HEALTH_BAR_MARK.getBooleanValue()
                    && BossDetector.isBoss(entity);
            // Frustum cull: expand up 1 block, so a mob hugging the bottom of the screen
            // does not get its bar mistakenly culled.
            if (frustum != null && !frustum.isVisible(entity.getBoundingBox().expand(0, 1, 0))) {
                continue;
            }

            float health = living.getHealth();
            float max = living.getMaxHealth();
            if (health <= 0 || max <= 0) {
                continue;
            }

            // Smooth interpolation to the entity's current render position: only
            // then does the bar follow the body.
            double ex = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
            double ez = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());

            Text text = buildText(health, max, health / max, suffix, boss);

            matrices.push();
            matrices.translate(ex - cam.x, ey - cam.y, ez - cam.z);
            // Boss mobs carry a native nametag bar; the whole bar is lifted another
            // 0.35F to prevent overlap.
            matrices.translate(0.0, entity.getHeight() + 0.5
                    + (boss ? BOSS_Y_OFFSET_EXTRA : Y_OFFSET_EXTRA), 0.0);
            // Billboard: cancels the world-to-camera rotation so text always faces
            // the camera (multiply only reads this quaternion).
            matrices.multiply(context.camera().getRotation());
            matrices.scale(0.025F, -0.025F, 0.025F);

            float x = -tr.getWidth(text) / 2.0F;
            // Render mode + distance: through-walls mode and the entity within the
            // overall through-wall render distance = SEE_THROUGH (always visible);
            // regular mode or beyond the distance = NORMAL (depth-tested, hidden
            // behind walls).
            TextRenderer.TextLayerType layerType =
                    GlobalConfig.QoL.HEALTH_BAR_RENDER_MODE.getOptionListValue()
                            == GlobalConfig.EspRenderMode.THROUGH_WALLS
                            && entity.squaredDistanceTo(cam) <= maxDistSq
                            ? TextRenderer.TextLayerType.SEE_THROUGH
                            : TextRenderer.TextLayerType.NORMAL;
            tr.draw(text, x, 0.0F, 0xFFFFFFFF, true,
                    matrices.peek().getPositionMatrix(), vcp,
                    layerType, BACKGROUND, FULLBRIGHT);
            matrices.pop();
        }
    }

    /**
     * Builds the bar text: {@code [###########----] 12/20HP}, full cells
     * {@code #}, lost health {@code -}, the bar body colored by health ratio,
     * numbers always white. The numbers are followed by {@code suffix}
     * (translation key {@code nomorezombies.healthbar.hpSuffix}). A boss uses
     * four tiers, purple/yellow/orange/red (0.75/0.5/0.25—spawn purple →
     * half-health yellow → low orange → dying red); a regular mob keeps three
     * tiers, green/yellow/red (0.66/0.33).
     *
     * <p>Health and max both keep {@value #DECIMALS} decimal places (integers
     * drop the point), sharing {@link JavaUtils#roundToDecimal} with damage
     * numbers: health is inherently fractional, and rounding would read a
     * 1.4-damage hit as "20/20" while 1.4 floats beside it—the two would not
     * agree.
     *
     * <p>When {@code maxHealth} is 0, show 0, not NaN—0/0 prints "NaN," and
     * such an entity should have no bar reading at all.
     *
     * @param health current health
     * @param maxHealth health cap
     * @param ratio health ratio, used only for the cell count and the color
     * @param suffix unit suffix after the numbers
     * @param boss whether to use the boss color tiers
     * @return the bar text
     */
    private static Text buildText(float health, float maxHealth, float ratio, String suffix, boolean boss) {
        ratio = Math.clamp(ratio, 0.0F, 1.0F);
        int filled = Math.round(ratio * BAR_LENGTH);
        int color = boss
                ? (ratio >= 0.75F ? COLOR_PURPLE
                : ratio >= 0.50F ? COLOR_YELLOW
                : ratio >= 0.25F ? COLOR_ORANGE : COLOR_RED)
                : (ratio >= 0.66F ? COLOR_GREEN : (ratio >= 0.33F ? COLOR_YELLOW : COLOR_RED));
        String bar = "[" + "#".repeat(filled) + "-".repeat(BAR_LENGTH - filled) + "] ";
        String numbers = maxHealth <= 0.0F
                ? "0/0"
                : JavaUtils.roundToDecimal(health, DECIMALS) + "/"
                + JavaUtils.roundToDecimal(maxHealth, DECIMALS);
        return Text.literal(bar).styled(s -> s.withColor(color))
                .append(Text.literal(numbers + suffix).styled(s -> s.withColor(COLOR_WHITE)));
    }

    private HealthBarRenderer() {
    }
}