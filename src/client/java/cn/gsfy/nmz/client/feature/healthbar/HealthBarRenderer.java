package cn.gsfy.nmz.client.feature.healthbar;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.EntityEsp;
import cn.gsfy.nmz.client.shared.EspTargets;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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
 * 怪物血条——在怪物（非玩家）的 nametag 位置渲染世界空间文本血条
 * {@code [###############] 20/20HP}（满格用 {@code #}、已失血量用 {@code -}）。
 * 渲染机制（{@link GlobalConfig.QoL#HEALTH_BAR_RENDER_MODE}）控制图层类型：
 * 常规 = {@link TextRenderer.TextLayerType#NORMAL}（深度测试，墙后不可见）；
 * 穿墙 = {@link TextRenderer.TextLayerType#SEE_THROUGH}（始终可见）。
 *
 * <p>渲染配方跟 vanilla {@code EntityRenderer.renderLabelIfPresent} 保持一致：
 * translate 到头顶 → {@code multiply(camera.getRotation())}（billboard 面向相机）→
 * {@code scale(0.025, -0.025, 0.025)} → {@code textRenderer.draw(...)}。
 *
 * <p>性能：目标列表复用 {@link EspTargets} 的共享扫描缓存（每 10 tick 一次、和 ESP 共用一份）；
 * 逐实体做存活过滤 + 目标复检 + 视锥剔除；不设距离上限。
 */
public final class HealthBarRenderer {

    /** 血条格数：满格 {@code #} 与失血 {@code -} 加起来的总长度。 */
    private static final int BAR_LENGTH = 15;

    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_YELLOW = 0xFFFF55;
    private static final int COLOR_RED = 0xFF5555;

    /** 全亮光照 LightmapTextureManager.pack(15, 15)：保证暗处也读得清。 */
    private static final int FULLBRIGHT = 0xF000F0;
    /** 文本背景（0 = 无底框；想要 nametag 式半透明底可改 0x40000000） */
    private static final int BACKGROUND = 0x00000000;
    /** 额外的头顶抬升量：怪物自带自定义名与血条重叠时调到 ~0.35F。 */
    private static final float Y_OFFSET_EXTRA = 0.0F;

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(HealthBarRenderer::render);
    }

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

        // 复用共享扫描缓存：和 ESP 共用同一次扫描，每 REFRESH_TICKS tick 重扫，帧内幂等
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
        // 总穿墙渲染距离：超出该距离的实体血条退回深度测试（平方距离，免开方）
        double maxDistSq = GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE.getDoubleValue();
        maxDistSq *= maxDistSq;
        float tickDelta = context.tickCounter().getTickDelta(true);
        TextRenderer tr = client.textRenderer;
        MatrixStack matrices = context.matrixStack();
        String suffix = Text.translatable("nomorezombies.healthbar.hpSuffix").getString();

        for (Entity entity : targets) {
            // 存活过滤：扫描间隙被击杀/退出的实体不再画
            if (entity.isRemoved()) {
                continue;
            }
            // 只给怪物画：LivingEntity 才有血量可读，玩家除外
            if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity) {
                continue;
            }
            // 目标复检（isInZombies 带 200ms 缓存，很便宜）
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }
            // 视锥剔除：向上扩 1 格，免得怪贴近屏幕下缘时血条被误剔
            if (context.frustum() != null
                    && !context.frustum().isVisible(entity.getBoundingBox().expand(0, 1, 0))) {
                continue;
            }

            float health = living.getHealth();
            float max = living.getMaxHealth();
            if (health <= 0 || max <= 0) {
                continue;
            }

            // 平滑插值到实体现时渲染位置，血条才跟得住身体
            double ex = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
            double ez = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());

            Text text = buildText((int) health, (int) max, health / max, suffix);

            matrices.push();
            matrices.translate(ex - cam.x, ey - cam.y, ez - cam.z);
            matrices.translate(0.0, entity.getHeight() + 0.5 + Y_OFFSET_EXTRA, 0.0);
            // billboard：抵消世界→相机旋转，让文本始终面向相机（multiply 只读这个四元数）
            matrices.multiply(context.camera().getRotation());
            matrices.scale(0.025F, -0.025F, 0.025F);

            float x = -tr.getWidth(text) / 2.0F;
            // 渲染机制 + 距离：穿墙模式且实体在总穿墙渲染距离内 = SEE_THROUGH（始终可见）；
            // 常规模式或超距 = NORMAL（深度测试，墙后不可见）
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
     * 构建血条文本：{@code [###########----] 12/20HP}，满格 {@code #}、失血 {@code -}，
     * 条身按血量比例着色（绿/黄/红），数字保持白色。
     */
    private static Text buildText(int health, int max, float ratio, String suffix) {
        ratio = Math.max(0.0F, Math.min(1.0F, ratio));
        int filled = Math.round(ratio * BAR_LENGTH);
        int color = ratio >= 0.66F ? COLOR_GREEN : (ratio >= 0.33F ? COLOR_YELLOW : COLOR_RED);
        String bar = "[" + "#".repeat(filled) + "-".repeat(BAR_LENGTH - filled) + "] ";
        return Text.literal(bar).styled(s -> s.withColor(color))
                .append(Text.literal(health + "/" + max + suffix).styled(s -> s.withColor(COLOR_WHITE)));
    }

    private HealthBarRenderer() {
    }
}
