package cn.gsfy.nmz.client.feature.esp;

import cn.gsfy.nmz.client.config.GlobalConfig;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.shared.EntityEsp;
import cn.gsfy.nmz.client.shared.EspRenderLayer;
import cn.gsfy.nmz.client.shared.EspTargets;
import cn.gsfy.nmz.client.shared.Powerup;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

/**
 * 箱体 ESP——在世界渲染的 AFTER_ENTITIES 阶段，把目标实体的包围盒画成线框。
 * 怪物一律红；玩家分三态——战斗中绿、倒地身体黄（靠随机名实体坐标关联，把贴地小盒
 * 放大成玩家标准盒 0.6×1.8×0.6 再锚定底边）、死亡/退出不画；已刷出的强化道具盔甲架白。
 * 三者各自独立开关（zombieEsp / teammateEsp / powerupEsp），互不牵连。
 *
 * <p>渲染机制（{@link GlobalConfig.EspRenderMode}）控制图层组合：
 * 常规 = 仅正常深度层（{@link EspRenderLayer#LINES}，LEQUAL 深度测试，不透明）——
 * 墙前可见、墙后自然被遮挡；
 * 穿墙 = 正常深度层 + 穿墙层（{@link EspRenderLayer#LINES_THROUGH_WALLS}，
 * alpha = {@value #XRAY_ALPHA}）双层叠加——墙前实心、墙后半透明穿透。
 * 顶点坐标必须是相对摄像机的偏移量（用世界包围盒坐标减相机位置得到，不产生新 Box）。
 *
 * <p>性能上不每帧扫全图：目标列表由 {@link EspTargets} 每 {@code EspTargets.REFRESH_TICKS}
 * 个 client tick 重扫一次，并和血条共享这份缓存；绘制时再做存活过滤 + 目标复检 + 视锥剔除，
 * 屏幕外的框干脆不画。
 */
public class EspRenderer {

    /** 穿墙层的 alpha：半透明，与不透明的正常层在视觉上区分 */
    private static final float XRAY_ALPHA = 0.3f;

    /** 怪物线框颜色（红）：敌对单位统一用红。 */
    private static final float[] ENEMY_COLOR = {1.0f, 0.0f, 0.0f};
    /** 玩家线框颜色（战斗中绿）：存活作战中的队友用绿。 */
    private static final float[] PLAYER_COLOR = {0.0f, 1.0f, 0.0f};
    /** 倒地队友线框颜色（黄）：倒地身体用黄，和战斗中的绿区分开。 */
    private static final float[] DOWNED_COLOR = {1.0f, 1.0f, 0.0f};
    /** 道具线框颜色（白）：道具盔甲架用白。 */
    private static final float[] POWERUP_COLOR = {1.0f, 1.0f, 1.0f};

    /** 注册 AFTER_ENTITIES 渲染回调：实体渲染完后由 EspRenderer::render 画线框。 */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(EspRenderer::render);
    }

    /** 渲染入口：按三项开关分别画对应目标的框；全关或不在 Zombies 局内就直接返回，不做无用功。 */
    private static void render(WorldRenderContext context) {
        boolean teammateEspOn = GlobalConfig.QoL.TEAMMATE_ESP.getBooleanValue();
        boolean zombieEspOn = GlobalConfig.QoL.ZOMBIE_ESP.getBooleanValue();
        boolean powerupEspOn = GlobalConfig.QoL.POWERUP_ESP.getBooleanValue();
        if ((!teammateEspOn && !zombieEspOn && !powerupEspOn) || !PlayerUtils.isInZombies()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        World world = context.world();
        if (world == null) {
            return;
        }

        VertexConsumerProvider vcp = context.consumers();
        if (vcp == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        double px = cam.x, py = cam.y, pz = cam.z;
        Frustum frustum = context.frustum();

        // 横棱/竖棱分别取对应 buffer：横棱用粗线、竖棱用细线；正常深度与穿墙各取一套
        VertexConsumer solidThin  = vcp.getBuffer(EspRenderLayer.LINES);
        VertexConsumer solidThick = vcp.getBuffer(EspRenderLayer.LINES_THICK);
        VertexConsumer xrayThin   = vcp.getBuffer(EspRenderLayer.LINES_THROUGH_WALLS);
        VertexConsumer xrayThick  = vcp.getBuffer(EspRenderLayer.LINES_THROUGH_WALLS_THICK);

        // 渲染机制：常规 = 仅深度层（墙后不可见）；穿墙 = 深度层 + 穿墙层双层（墙后可见）。
        // solid 缓冲恒传；xray 缓冲仅在穿墙模式传（null = 常规模式下不画穿墙层）。
        boolean teammateDrawXray = shouldDrawXray(GlobalConfig.QoL.TEAMMATE_ESP_RENDER_MODE);
        boolean zombieDrawXray = shouldDrawXray(GlobalConfig.QoL.ZOMBIE_ESP_RENDER_MODE);
        boolean powerupDrawXray = shouldDrawXray(GlobalConfig.QoL.POWERUP_ESP_RENDER_MODE);
        // 总穿墙渲染距离：超出该距离的实体不画穿墙层（平方距离，免开方）
        double maxDistSq = GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE.getDoubleValue();
        maxDistSq *= maxDistSq;

        if (teammateEspOn) {
            renderTeammateEsp(context, world, frustum, px, py, pz, maxDistSq,
                    solidThin, solidThick,
                    teammateDrawXray ? xrayThin : null, teammateDrawXray ? xrayThick : null);
        }
        if (zombieEspOn) {
            renderZombieEsp(context, world, frustum, px, py, pz, maxDistSq,
                    solidThin, solidThick,
                    zombieDrawXray ? xrayThin : null, zombieDrawXray ? xrayThick : null);
        }
        if (powerupEspOn) {
            renderPowerupEsp(context, frustum, px, py, pz, maxDistSq,
                    solidThin, solidThick,
                    powerupDrawXray ? xrayThin : null, powerupDrawXray ? xrayThick : null);
        }
    }

    /** 渲染机制是否绘制穿墙层：THROUGH_WALLS 模式才画。 */
    private static boolean shouldDrawXray(ConfigOptionList config) {
        return config.getOptionListValue() == GlobalConfig.EspRenderMode.THROUGH_WALLS;
    }

    /** 队友 ESP：画名单内战斗中（绿）/ 倒地身体（黄）的玩家线框。 */
    private static void renderTeammateEsp(WorldRenderContext context, World world, Frustum frustum,
                                          double px, double py, double pz, double maxDistSq,
                                          VertexConsumer solidThin, VertexConsumer solidThick,
                                          VertexConsumer xrayThin, VertexConsumer xrayThick) {
        // 复用共享扫描缓存：和血条共用同一次扫描，每 REFRESH_TICKS tick 重扫一次，帧内幂等
        EspTargets.ensureScanned(world, context.camera().getPos());
        List<Entity> targets = EspTargets.getTargets();

        if (targets.isEmpty()) {
            return;
        }

        for (Entity entity : targets) {
            // 只框玩家，先做存活过滤：扫描间隙被击杀/退出的实体不再画
            if (entity.isRemoved() || !(entity instanceof PlayerEntity)) {
                continue;
            }
            // 目标复检：玩家状态可能在扫描间隙变化（战斗→倒地/死亡/退出），框要跟得上实时状态
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }

            Box box = entity.getBoundingBox(); // 返回实体字段，不产生新 Box
            // 颜色按状态走：战斗中绿 / 倒地身体黄（isTarget 已复检，只会是名单内战斗玩家 + 倒地身体）
            float[] color = PLAYER_COLOR;
            PlayerEntity p = (PlayerEntity) entity;
            if (p.getGameProfile() != null && TeamStats.getDownedBodyOwner(p.getId()) != null) {
                color = DOWNED_COLOR;
                // 倒地身体实际是 SLEEPING 贴地小盒（0.2³，几乎不可见），于是按玩家标准盒
                // 0.6×1.8×0.6 放大并锚定身体底边（贴地），保证穿墙也看得见。仅倒地身体特殊处理。
                double cx = (box.minX + box.maxX) / 2.0;
                double cz = (box.minZ + box.maxZ) / 2.0;
                box = new Box(cx - 0.3, box.minY, cz - 0.3, cx + 0.3, box.minY + 1.8, cz + 0.3);
            }
            // 视锥剔除：屏幕外的框本就不可见，直接跳过
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }

            // 层1：正常深度测试，不透明——墙前部分可见，墙后自然被遮挡（常规模式即只画这层）
            drawBox(solidThin, solidThick, context.matrixStack(), box, px, py, pz, color, 1.0f);
            // 层2：关掉深度测试，半透明——墙后部分也能看见（仅穿墙模式且距离在总穿墙渲染距离内）
            if (xrayThin != null && entity.squaredDistanceTo(px, py, pz) <= maxDistSq) {
                drawBox(xrayThin, xrayThick, context.matrixStack(), box, px, py, pz, color, XRAY_ALPHA);
            }
        }
    }

    /** 僵尸 ESP：画僵尸/狼/烈焰人等敌对生物的红色线框。 */
    private static void renderZombieEsp(WorldRenderContext context, World world, Frustum frustum,
                                        double px, double py, double pz, double maxDistSq,
                                        VertexConsumer solidThin, VertexConsumer solidThick,
                                        VertexConsumer xrayThin, VertexConsumer xrayThick) {
        // 复用共享扫描缓存：和血条共用同一次扫描，每 REFRESH_TICKS tick 重扫一次，帧内幂等
        EspTargets.ensureScanned(world, context.camera().getPos());
        List<Entity> targets = EspTargets.getTargets();

        if (targets.isEmpty()) {
            return;
        }

        for (Entity entity : targets) {
            // 只框敌对生物（Monster/狼，不含玩家），并做存活过滤：扫描间隙被击杀的不再画
            if (entity.isRemoved() || entity instanceof PlayerEntity) {
                continue;
            }
            // 目标复检：isTarget 已按 Zombies 环境复核过敌对实体
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }

            Box box = entity.getBoundingBox(); // 返回实体字段，不产生新 Box
            // 视锥剔除：屏幕外的框本就不可见，直接跳过
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }

            // 层1：正常深度测试，不透明——墙前部分可见，墙后自然被遮挡（常规模式即只画这层）
            drawBox(solidThin, solidThick, context.matrixStack(), box, px, py, pz, ENEMY_COLOR, 1.0f);
            // 层2：关掉深度测试，半透明——墙后部分也能看见（仅穿墙模式且距离在总穿墙渲染距离内）
            if (xrayThin != null && entity.squaredDistanceTo(px, py, pz) <= maxDistSq) {
                drawBox(xrayThin, xrayThick, context.matrixStack(), box, px, py, pz, ENEMY_COLOR, XRAY_ALPHA);
            }
        }
    }

    /** 道具 ESP：给已刷出且未被拾取的强化道具（盔甲架）画白色线框。
     * 数据直接来自道具检测维护的 {@link Powerup#powerups}，不额外全图扫描。 */
    private static void renderPowerupEsp(WorldRenderContext context, Frustum frustum,
                                         double px, double py, double pz, double maxDistSq,
                                         VertexConsumer solidThin, VertexConsumer solidThick,
                                         VertexConsumer xrayThin, VertexConsumer xrayThick) {
        for (Map.Entry<ArmorStandEntity, Powerup> e : Powerup.powerups.entrySet()) {
            ArmorStandEntity stand = e.getKey();
            // 存活过滤：扫描间隙被拾取/超时移除的盔甲架不再画
            if (stand.isRemoved()) {
                continue;
            }
            Box box = stand.getBoundingBox();
            // 小型 display 盔甲架是贴地小盒，几乎看不见：按最小 0.6×1.0×0.6 放大并锚定底边
            double cx = (box.minX + box.maxX) / 2.0;
            double cz = (box.minZ + box.maxZ) / 2.0;
            double h = Math.max(box.maxY - box.minY, 1.0);
            box = new Box(cx - 0.3, box.minY, cz - 0.3, cx + 0.3, box.minY + h, cz + 0.3);
            // 视锥剔除：屏幕外的框本就不可见，直接跳过
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }

            // 层1：正常深度测试，不透明——墙前部分可见，墙后自然被遮挡（常规模式即只画这层）
            drawBox(solidThin, solidThick, context.matrixStack(), box, px, py, pz, POWERUP_COLOR, 1.0f);
            // 层2：关掉深度测试，半透明——墙后部分也能看见（仅穿墙模式且距离在总穿墙渲染距离内）
            if (xrayThin != null && stand.squaredDistanceTo(px, py, pz) <= maxDistSq) {
                drawBox(xrayThin, xrayThick, context.matrixStack(), box, px, py, pz, POWERUP_COLOR, XRAY_ALPHA);
            }
        }
    }

    /**
     * 画包围盒的 12 条边：上下面的横棱用粗线（thickBuffer），竖棱用细线（thinBuffer）。
     *
     * @param thinBuffer  竖棱（细线）buffer
     * @param thickBuffer 横棱（粗线）buffer
     * @param matrices    当前渲染的矩阵栈
     * @param box         待绘制的世界坐标包围盒
     * @param camX        相机位置 X 分量
     * @param camY        相机位置 Y 分量
     * @param camZ        相机位置 Z 分量
     * @param color       线框颜色（r,g,b）
     * @param alpha       整体透明度，1.0f 为不透明
     * @apiNote 传入相机坐标正是为了把世界包围盒就地换算成相机空间坐标——内联减掉偏移，不产生新 Box。
     */
    private static void drawBox(VertexConsumer thinBuffer, VertexConsumer thickBuffer,
                                MatrixStack matrices, Box box,
                                double camX, double camY, double camZ,
                                float[] color, float alpha) {
        MatrixStack.Entry entry = matrices.peek();
        float r = color[0], g = color[1], b = color[2];
        float minX = (float) (box.minX - camX), minY = (float) (box.minY - camY), minZ = (float) (box.minZ - camZ);
        float maxX = (float) (box.maxX - camX), maxY = (float) (box.maxY - camY), maxZ = (float) (box.maxZ - camZ);

        // 底面 4 条边（横棱 → 粗线）
        line(thickBuffer, entry, minX, minY, minZ, maxX, minY, minZ, r, g, b, alpha);
        line(thickBuffer, entry, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, alpha);
        line(thickBuffer, entry, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        line(thickBuffer, entry, minX, minY, maxZ, minX, minY, minZ, r, g, b, alpha);

        // 顶面 4 条边（横棱 → 粗线）
        line(thickBuffer, entry, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        line(thickBuffer, entry, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
        line(thickBuffer, entry, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        line(thickBuffer, entry, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);

        // 竖直 4 条边（竖棱 → 细线）
        line(thinBuffer, entry, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
        line(thinBuffer, entry, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        line(thinBuffer, entry, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
        line(thinBuffer, entry, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
    }

    /**
     * 画一条线段。
     *
     * <p>踩坑记录：line 顶点着色器拿 <b>Normal 属性当线段方向</b>来算垂直扩展线宽
     * （{@code linePosEnd = Position + Normal}），所以 normal 必须传线的真实方向
     * (x2-x1, y2-y1, z2-z1)——否则扩展方向与线段平行，横棱会塌缩成近零面积、几乎看不见。
     */
    private static void line(VertexConsumer buffer, MatrixStack.Entry entry,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        int ir = (int) (r * 255);
        int ig = (int) (g * 255);
        int ib = (int) (b * 255);
        int ia = (int) (a * 255);

        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;

        buffer.vertex(entry, x1, y1, z1).color(ir, ig, ib, ia).normal(dx, dy, dz);
        buffer.vertex(entry, x2, y2, z2).color(ir, ig, ib, ia).normal(dx, dy, dz);
    }

    private EspRenderer() {
    }
}
