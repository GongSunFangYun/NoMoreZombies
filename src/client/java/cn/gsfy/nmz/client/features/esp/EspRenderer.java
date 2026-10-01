package cn.gsfy.nmz.client.features.esp;

import cn.gsfy.nmz.client.config.GlobalConfig;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.shared.esp.BossDetector;
import cn.gsfy.nmz.client.shared.esp.EntityEsp;
import cn.gsfy.nmz.client.shared.esp.EspRenderLayer;
import cn.gsfy.nmz.client.shared.esp.EspTargets;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Box ESP - during world rendering's AFTER_ENTITIES stage, draws target
 * entities' bounding boxes as wireframes. Mobs green, bosses orange
 * ({@link BossDetector#isBoss}: Giant / Old One / mobs carrying a native
 * bar nametag); players in three states - in combat red, downed body yellow
 * (associated via the random-name entity's coordinates, enlarging the small
 * ground-hugging box into the standard player box 0.6x1.8x0.6 anchored to its
 * bottom), dead/left not drawn; spawned power-up armor stands white. The
 * three families have independent switches (zombieEsp/teammateEsp/
 * powerupEsp), none entangled with another.
 *
 * <p>The render mode ({@code GlobalConfig.EspRenderMode}) controls layer
 * combination: NORMAL draws only the LEQUAL normal-depth layer
 * ({@link EspRenderLayer#LINES} and its thicker {@code LINES_THICK}, both
 * opaque), so it is visible in front of walls and naturally occluded behind
 * them; THROUGH_WALLS draws the LEQUAL normal layer plus the ALWAYS
 * through-wall layer ({@link EspRenderLayer#LINES_THROUGH_WALLS} and its
 * thicker tier, alpha = {@value #XRAY_ALPHA}) - with both depths stacked, the
 * wall-front part stays solid and the behind-wall part shows translucent.
 * Vertex coordinates must be camera-relative offsets (world box coordinates
 * minus the camera position; no new Box is allocated).
 *
 * <p>Performance: no full-world scan per frame - the target list is rescanned
 * by {@link EspTargets} every {@code EspTargets.REFRESH_TICKS} client ticks
 * and shared with the health bar; drawing then does liveness filtering +
 * target re-verification + frustum culling, and off-screen boxes simply do
 * not draw.
 *
 * <p><b>The layer-acquisition contract (never fall back to grabbing four
 * {@code getBuffer}s in a row)</b>: all four layers are
 * {@code DrawMode.LINES} (vertices unshared), and
 * {@link VertexConsumerProvider.Immediate} keeps only one
 * {@code currentLayer} at a time for such layers - calling {@code getBuffer}
 * for a second layer internally first calls {@code drawCurrentLayer()}, which
 * ends the previous layer's {@code BufferBuilder} in place
 * ({@code endNullable()}, {@code building} -> false) yet leaves it in the
 * {@code pending} table; writing vertices to that builder afterwards throws
 * {@code IllegalStateException: Not building!} immediately - the very first
 * frame ESP frames a target would crash.
 * So this class works in two phases: the three target families first
 * <b>collect</b> their boxes for the frame ({@link PendingBox}, with
 * camera-space geometry and colors); after all three families run, the matrix
 * stack top is read once, then layers are <b>committed one by one</b> - each
 * layer closes the loop "get buffer -> write all this layer's edges ->
 * {@code immediate.draw(layer)} returns it", never holding two layers'
 * buffers at once; a layer with no vertices this frame does not even call
 * {@code getBuffer}.
 * When {@code context.consumers()} is not an {@code Immediate}, the whole
 * frame does not draw.
 */
public class EspRenderer {

    /** The through-wall layer's alpha: translucent, visually distinct from the opaque normal layer
     *  (0.3 turned mushy once lines thickened; raised to 0.5 for legibility). */
    private static final float XRAY_ALPHA = 0.5f;

    /** Mob wireframe color (green): hostile units uniformly green. */
    private static final float[] ENEMY_COLOR = {0.0f, 1.0f, 0.0f};
    /** Boss wireframe color (orange): Giant / Old One / native-bar nametag mobs at a glance. */
    private static final float[] BOSS_COLOR = {1.0f, 0.5f, 0.0f};
    /** Player wireframe color (in combat, red): living fighting teammates in red. */
    private static final float[] PLAYER_COLOR = {1.0f, 0.0f, 0.0f};
    /** Downed teammate wireframe color (yellow): downed bodies in yellow, apart from combat red. */
    private static final float[] DOWNED_COLOR = {1.0f, 1.0f, 0.0f};
    /** Power-up wireframe color (white): power-up armor stands in white. */
    private static final float[] POWERUP_COLOR = {1.0f, 1.0f, 1.0f};

    /** This frame's pending normal-depth boxes: written in one go at commit, then {@code draw}ed back. */
    private static final List<PendingBox> SOLID = new ArrayList<>();
    /** This frame's pending through-wall boxes: collected separately from {@link #SOLID}; each layer still closes its own loop. */
    private static final List<PendingBox> XRAY = new ArrayList<>();

    /**
     * One pending box's camera-space bounds (camera offset already
     * subtracted) and color.
     *
     * <p>Geometry is converted to camera-relative floats at collection time;
     * the commit stage needs no camera coordinates. Line width and alpha are
     * decided by the commit stage per layer, not here.
     */
    private record PendingBox(float minX, float minY, float minZ,
                              float maxX, float maxY, float maxZ,
                              float[] color) {
    }

    /** Registers the AFTER_ENTITIES callback: after entity rendering, EspRenderer::render draws the wireframes. */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(EspRenderer::render);
    }

    /** The AFTER_ENTITIES callback entry: after entity rendering, draws wireframes for the corresponding targets per the three switches; all off or outside a game returns directly.
     *
     * <p>This method does exactly three things: gate, collect, commit. First
     * the three families' boxes go into {@link #SOLID} / {@link #XRAY}, then
     * per layer "get buffer -> write -> return".
     */
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
        // The layer-acquisition contract only holds for Immediate (only it has draw(layer) to
        // return buffers); other implementations do not draw at all,
        // so the "write vertices straight into an unknown implementation" path never reappears
        if (!(vcp instanceof VertexConsumerProvider.Immediate immediate)) {
            return;
        }
        // The matrix stack is also a nullable accessor (WorldRenderContext declares @Nullable):
        // ESP wireframes must hang on the current frame's camera matrix; without it the whole
        // frame does not draw - no identity matrix substitute
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        Frustum frustum = context.frustum();

        // Render mode: normal = depth layer only (invisible behind walls); through-wall = depth
        // layer + through-wall layer (visible behind walls).
        // The SOLID list is always collected; the XRAY list only when that family is in
        // through-wall mode
        boolean teammateDrawXray = shouldDrawXray(GlobalConfig.QoL.TEAMMATE_ESP_RENDER_MODE);
        boolean zombieDrawXray = shouldDrawXray(GlobalConfig.QoL.ZOMBIE_ESP_RENDER_MODE);
        boolean powerupDrawXray = shouldDrawXray(GlobalConfig.QoL.POWERUP_ESP_RENDER_MODE);
        // Global through-wall render distance: entities beyond it draw no through-wall layer
        // (squared distance, no square root)
        double maxDistSq = GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE.getDoubleValue();
        maxDistSq *= maxDistSq;

        // Phase one: collect. The three families only filter and collect; no buffer is touched
        SOLID.clear();
        XRAY.clear();
        if (teammateEspOn) {
            renderTeammateEsp(world, frustum, cam, maxDistSq, teammateDrawXray);
        }
        if (zombieEspOn) {
            renderZombieEsp(world, frustum, cam, maxDistSq, zombieDrawXray);
        }
        if (powerupEspOn) {
            renderPowerupEsp(frustum, cam, maxDistSq, powerupDrawXray);
        }

        // Phase two: commit layer by layer. The matrix stack top is read once; each layer must
        // draw(layer)-return immediately after writing, before the next layer's getBuffer -
        // never hold two layers' buffers at once
        MatrixStack.Entry entry = matrices.peek();
        flushLayer(immediate, EspRenderLayer.LINES, SOLID, false, 1.0f, entry);
        flushLayer(immediate, EspRenderLayer.LINES_THICK, SOLID, true, 1.0f, entry);
        flushLayer(immediate, EspRenderLayer.LINES_THROUGH_WALLS, XRAY, false, XRAY_ALPHA, entry);
        flushLayer(immediate, EspRenderLayer.LINES_THROUGH_WALLS_THICK, XRAY, true, XRAY_ALPHA, entry);
        SOLID.clear();
        XRAY.clear();
    }

    /** Whether the render mode draws the through-wall layer: only THROUGH_WALLS does. */
    private static boolean shouldDrawXray(ConfigOptionList config) {
        return config.getOptionListValue() == GlobalConfig.EspRenderMode.THROUGH_WALLS;
    }

    /** Teammate ESP: collects wireframes for roster players in combat (red) / downed bodies (yellow).
     *
     * @param world the current world, for the shared scan
     * @param frustum the frustum, nullable (no culling when null)
     * @param cam the camera position
     * @param maxDistSq the squared global through-wall render distance
     * @param drawXray whether this family collects the through-wall layer
     */
    private static void renderTeammateEsp(World world, Frustum frustum, Vec3d cam,
                                          double maxDistSq, boolean drawXray) {
        // Shared scan with the health bar, rescanned every {@link EspTargets#REFRESH_TICKS}
        // ticks, idempotent within a frame
        EspTargets.ensureScanned(world, cam);
        List<Entity> targets = EspTargets.getTargets();

        if (targets.isEmpty()) {
            return;
        }

        for (Entity entity : targets) {
            // Players only; liveness filter first: entities killed or left during the scan gap
            // stop drawing
            if (entity.isRemoved() || !(entity instanceof PlayerEntity p)) {
                continue;
            }
            // Target re-verification: player state may change during the scan gap
            // (combat -> downed/dead/left); the frame must follow the live state
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }

            Box box = entity.getBoundingBox(); // returns the entity field, no new Box
            // Color by state: combat red / downed-body yellow (isTarget re-verified; only these
            // two occur)
            float[] color = PLAYER_COLOR;
            if (p.getGameProfile() != null && TeamStats.getDownedBodyOwner(p.getId()) != null) {
                color = DOWNED_COLOR;
                // A downed body is actually a SLEEPING ground-hugging tiny box (0.2 cubed,
                // nearly invisible), so enlarge to the standard player box
                // 0.6x1.8x0.6 anchored to the body's bottom edge (on the ground), keeping it
                // visible even through walls. Only downed bodies get this special case
                double cx = (box.minX + box.maxX) / 2.0;
                double cz = (box.minZ + box.maxZ) / 2.0;
                box = new Box(cx - 0.3, box.minY, cz - 0.3, cx + 0.3, box.minY + 1.8, cz + 0.3);
            }
            // Frustum culling: off-screen boxes are invisible anyway; skip outright
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }

            // Layer 1: normal depth test, opaque - the wall-front part visible, behind-wall
            // naturally occluded (normal mode draws only this layer)
            SOLID.add(pending(box, cam.x, cam.y, cam.z, color));
            // Layer 2: depth test off, translucent - the behind-wall part also visible (through-
            // wall mode only, within the global through-wall distance)
            if (drawXray && entity.squaredDistanceTo(cam.x, cam.y, cam.z) <= maxDistSq) {
                XRAY.add(pending(box, cam.x, cam.y, cam.z, color));
            }
        }
    }

    /** Zombie ESP: collects green/orange (boss) wireframes for zombies/wolves/blazes and other hostiles.
     *
     * @param world the current world, for the shared scan
     * @param frustum the frustum, nullable (no culling when null)
     * @param cam the camera position
     * @param maxDistSq the squared global through-wall render distance
     * @param drawXray whether this family collects the through-wall layer
     */
    private static void renderZombieEsp(World world, Frustum frustum, Vec3d cam,
                                        double maxDistSq, boolean drawXray) {
        // Shared scan with the health bar, rescanned every {@link EspTargets#REFRESH_TICKS}
        // ticks, idempotent within a frame
        EspTargets.ensureScanned(world, cam);
        List<Entity> targets = EspTargets.getTargets();

        if (targets.isEmpty()) {
            return;
        }

        for (Entity entity : targets) {
            // Hostiles only (Monster/wolves, no players), plus liveness filter: mobs killed
            // during the scan gap stop drawing
            if (entity.isRemoved() || entity instanceof PlayerEntity) {
                continue;
            }
            // Target re-verification: isTarget has already re-checked hostile entities in the
            // Zombies context
            if (!EntityEsp.isTarget(entity)) {
                continue;
            }

            Box box = entity.getBoundingBox(); // returns the entity field, no new Box
            // Boss coloring: only while the "boss ESP special mark" switch is on, Giant/Old
            // One/native-bar mobs use orange and other hostiles green; switch off, bosses and
            // ordinary mobs are both green (config-granularity refinement)
            float[] color = (BossDetector.isBoss(entity)
                    && GlobalConfig.QoL.BOSS_ESP_MARK.getBooleanValue()) ? BOSS_COLOR : ENEMY_COLOR;
            // Frustum culling: off-screen boxes are invisible anyway; skip outright
            if (isCulled(frustum, box)) {
                continue;
            }

            // Layer 1: normal depth test, opaque - the wall-front part visible, behind-wall
            // naturally occluded (normal mode draws only this layer)
            SOLID.add(pending(box, cam.x, cam.y, cam.z, color));
            // Layer 2: depth test off, translucent - the behind-wall part also visible (through-
            // wall mode only, within the global through-wall distance)
            collectXray(entity, cam, maxDistSq, drawXray, box, color);
        }
    }

    /** Power-up ESP: collects white wireframes for spawned, un-picked power-ups (armor stands).
     *  Data comes directly from {@link PowerupParser#powerups} maintained by power-up detection; no extra full-world scan.
     *
     * @param frustum the frustum, nullable (no culling when null)
     * @param cam the camera position
     * @param maxDistSq the squared global through-wall render distance
     * @param drawXray whether this family collects the through-wall layer
     */
    private static void renderPowerupEsp(Frustum frustum, Vec3d cam,
                                         double maxDistSq, boolean drawXray) {
        for (Map.Entry<ArmorStandEntity, PowerupParser> e : PowerupParser.powerups.entrySet()) {
            ArmorStandEntity stand = e.getKey();
            // Liveness filter: stands picked up or expired during the scan gap stop drawing
            if (stand.isRemoved()) {
                continue;
            }
            Box box = stand.getBoundingBox();
            // A small display armor stand is a ground-hugging tiny box, nearly invisible:
            // enlarge to a minimum 0.6x1.0x0.6 anchored to the bottom edge
            double cx = (box.minX + box.maxX) / 2.0;
            double cz = (box.minZ + box.maxZ) / 2.0;
            double h = Math.max(box.maxY - box.minY, 1.0);
            box = new Box(cx - 0.3, box.minY, cz - 0.3, cx + 0.3, box.minY + h, cz + 0.3);
            // Frustum culling: off-screen boxes are invisible anyway; skip outright
            if (isCulled(frustum, box)) {
                continue;
            }

            // Layer 1: normal depth test, opaque - the wall-front part visible, behind-wall
            // naturally occluded (normal mode draws only this layer)
            SOLID.add(pending(box, cam.x, cam.y, cam.z, POWERUP_COLOR));
            // Layer 2: depth test off, translucent - the behind-wall part also visible (through-
            // wall mode only, within the global through-wall distance)
            collectXray(stand, cam, maxDistSq, drawXray, box, POWERUP_COLOR);
        }
    }

    /** The single culling decision point: no frustum (null) always passes. */
    private static boolean isCulled(Frustum frustum, Box box) {
        return frustum != null && !frustum.isVisible(box);
    }

    /** Collects this family's frames into the through-wall layer under "through-wall mode + within the global distance"; both conditions required. */
    private static void collectXray(Entity entity, Vec3d cam, double maxDistSq, boolean drawXray,
                                    Box box, float[] color) {
        if (drawXray && entity.squaredDistanceTo(cam.x, cam.y, cam.z) <= maxDistSq) {
            XRAY.add(pending(box, cam.x, cam.y, cam.z, color));
        }
    }

    /**
     * Converts a world-coordinate bounding box into a camera-space pending
     * record.
     *
     * <p>Only six coordinate subtractions from world box to camera space; no
     * new {@link Box} is allocated.
     *
     * @param box the world-coordinate bounding box
     * @param camX camera position x
     * @param camY camera position y
     * @param camZ camera position z
     * @param color the wireframe color (r, g, b)
     * @return the camera-space pending record
     */
    private static PendingBox pending(Box box, double camX, double camY, double camZ, float[] color) {
        return new PendingBox(
                (float) (box.minX - camX), (float) (box.minY - camY), (float) (box.minZ - camZ),
                (float) (box.maxX - camX), (float) (box.maxY - camY), (float) (box.maxZ - camZ),
                color);
    }

    /**
     * Commits one layer: get buffer -> write all this layer's edges ->
     * draw(layer) returns it.
     *
     * <p>Where the layer-acquisition contract lands. When this layer has no
     * vertices this frame, {@code getBuffer} is not called at all, so no empty
     * buffer lingers in {@code pending}; after writing, the buffer must be
     * returned immediately, or the next layer's getBuffer ends this layer's
     * builder in place (the origin of {@code Not building!}).
     *
     * @param immediate the world render's immediate vertex provider
     * @param layer this layer's render layer
     * @param boxes the boxes to draw on this layer (camera space)
     * @param horizontal true = the horizontal edges of top/bottom faces (thick), false = vertical edges (thin)
     * @param alpha the overall alpha, 1.0f opaque
     * @param entry the matrix stack top
     */
    private static void flushLayer(VertexConsumerProvider.Immediate immediate, RenderLayer layer,
                                   List<PendingBox> boxes, boolean horizontal,
                                   float alpha, MatrixStack.Entry entry) {
        if (boxes.isEmpty()) {
            return;
        }
        VertexConsumer buffer = immediate.getBuffer(layer);
        for (PendingBox box : boxes) {
            float r = box.color()[0], g = box.color()[1], b = box.color()[2];
            float minX = box.minX(), minY = box.minY(), minZ = box.minZ();
            float maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
            if (horizontal) {
                // Bottom face's 4 edges: the rectangle closes back to its start
                flushQuad(buffer, entry, r, g, b, alpha,
                        minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
                // Top face's 4 edges
                flushQuad(buffer, entry, r, g, b, alpha,
                        minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
            } else {
                // 4 vertical edges: one per vertical face, unclosed
                line(buffer, entry, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
                line(buffer, entry, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
                line(buffer, entry, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
                line(buffer, entry, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
            }
        }
        immediate.draw(layer);
    }

    /**
     * Batch commit for one face (four edges): the four corners joined into a
     * closed ring in order.
     *
     * <p>The corners come as {@code (x0,z0) -> (x1,z0) -> (x1,z1) -> (x0,z1)},
     * the 4th edge automatically returning to the start - bottom and top
     * faces share this one pattern, so the "rectangle closes" rule is not
     * repeated four times at each call site.
     *
     * @param buffer the target buffer
     * @param entry the matrix stack top
     * @param y the face's height (y constant, shared by the four corners)
     */
    private static void flushQuad(VertexConsumer buffer, MatrixStack.Entry entry,
                                  float r, float g, float b, float alpha,
                                  float x0, float y, float z0, float x1, float y1, float z1,
                                  float x2, float y2, float z2, float x3, float y3, float z3) {
        line(buffer, entry, x0, y, z0, x1, y1, z1, r, g, b, alpha);
        line(buffer, entry, x1, y1, z1, x2, y2, z2, r, g, b, alpha);
        line(buffer, entry, x2, y2, z2, x3, y3, z3, r, g, b, alpha);
        line(buffer, entry, x3, y3, z3, x0, y, z0, r, g, b, alpha);
    }

    /**
     * Draws one line segment.
     *
     * <p>Field-tested trap: the line vertex shader uses the <b>Normal
     * attribute as the segment direction</b> to compute the perpendicular
     * width expansion ({@code linePosEnd = Position + Normal}), so normal
     * must carry the line's true direction (x2-x1, y2-y1, z2-z1) - otherwise
     * the expansion runs parallel to the segment and horizontal edges
     * collapse to near-zero area, nearly invisible.
     *
     * @param buffer the target buffer
     * @param entry the matrix stack top
     * @param x1 start x
     * @param y1 start y
     * @param z1 start z
     * @param x2 end x
     * @param y2 end y
     * @param z2 end z
     * @param r red component
     * @param g green component
     * @param b blue component
     * @param a alpha
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
