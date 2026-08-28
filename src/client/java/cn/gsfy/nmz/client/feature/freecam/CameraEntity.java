package cn.gsfy.nmz.client.feature.freecam;

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
 * 自由视角的替身相机实体——继承 {@link ClientPlayerEntity} 白嫖全套玩家字段与行为，
 * 但**从不加入世界实体列表**（vanilla 永不调它的 {@code tick()}），生命周期与移动
 * 完全由 {@link FreeCameraHandler} 手动接管。位置/朝向与真玩家彻底独立：启用时复制
 * 玩家坐标朝向出生，此后相机随 WASD/鼠标自由飞，玩家本体冻结在原地；停用时只恢复
 * {@code mc.cameraEntity} 引用——玩家数据从没被动过，零残留。
 *
 * <p>运行时状态全放静态字段，不占配置项。相机按旁观者对待（{@link #isSpectator()} → true），
 * 雾渲染这类按旁观者身份走。每次移动手动维护 {@code prev* / lastRender*} 插值字段——
 * 相机不入世界，这些字段本是死数据，不手动更新的话相机动起来会抖动/断裂。
 */
public final class CameraEntity extends ClientPlayerEntity {

    @Nullable private static CameraEntity camera;
    @Nullable private static Entity originalCameraEntity;
    private static boolean cullChunksOriginal;
    private static boolean sprinting;
    private static boolean originalCameraWasPlayer;

    /** 相机飞行速度基数（方块/tick @ SPEED=1.0）：照抄 tweakeroo getMoveSpeed() = 0.07*10。 */
    private static final double BASE_SPEED = 0.7;
    /** 斜坡加速步长：每 tick 向输入方向逼近的量，照 tweakeroo rampAmount=0.15。 */
    private static final double RAMP_AMOUNT = 0.15;
    /** 无输入时每 tick 的速度衰减比例：照 tweakeroo decelerationFactor=0.4。 */
    private static final double DECELERATION = 0.4;
    /** 斜向移动的速度除数（照 tweakeroo 1.2）：用来抑制对角加速。 */
    private static final double DIAGONAL_FACTOR = 1.2;

    /** 相机当前斜坡速度（x=前向输入、y=垂直输入、z=横移输入，取值 [-1,1]），
     * 跨 tick 保留，才能做出平滑加减速。 */
    private static Vec3d cameraMotion = Vec3d.ZERO;

    private CameraEntity(MinecraftClient mc, ClientWorld world, ClientPlayNetworkHandler netHandler,
                         StatHandler stats, ClientRecipeBook recipeBook) {
        super(mc, world, netHandler, stats, recipeBook, false, false);
    }

    /** 替身相机按旁观者对待：雾渲染、实体渲染等都拿旁观者身份处理。 */
    @Override
    public boolean isSpectator() {
        return true;
    }

    /** 切入前相机实体是不是玩家本人：给 {@code isCamera()} 做欺骗判断用。 */
    public static boolean originalCameraWasPlayer() {
        return originalCameraWasPlayer;
    }

    /** 相机当前是否已创建：创建了才算自由视角生效。 */
    public static boolean isCameraActive() {
        return camera != null;
    }

    /** 启用自由视角：创建替身相机，并把渲染/鼠标/准星来源切到它身上。 */
    public static void setCameraState(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        camera = createCameraEntity(mc);
        originalCameraEntity = mc.getCameraEntity();
        originalCameraWasPlayer = originalCameraEntity == mc.player;
        cullChunksOriginal = mc.chunkCullingEnabled;

        mc.setCameraEntity(camera);
        mc.chunkCullingEnabled = false;                 // 关闭区块裁剪，看得更远
        mc.gameRenderer.setRenderHand(false);           // 隐藏第一人称手（renderHand mixin 之外再兜一层保险）
    }

    /** 停用自由视角：恢复原相机实体与区块剔除开关。玩家数据从未被动过，不需要别的恢复动作。 */
    public static void removeCamera(MinecraftClient mc) {
        if (camera != null) {
            // 自由视角期间死亡/重生，玩家实例会被替换，所以恢复时重新取 mc.player
            mc.setCameraEntity(originalCameraWasPlayer ? mc.player : originalCameraEntity);
            mc.chunkCullingEnabled = cullChunksOriginal;
            mc.gameRenderer.setRenderHand(true);
        }
        FreeCameraHandler.restoreRealInput(mc);   // 兜底：还原可能残留的冻结玩家输入（立即生效）
        camera = null;
        originalCameraEntity = null;
        sprinting = false;
        cameraMotion = Vec3d.ZERO;
    }

    /** 每 tick 驱动相机移动（只有「允许玩家移动」关闭时 handler 才调）。照 tweakeroo 走：
     * 读六个键 → 斜坡加减速（方向反转先清零）→ 按 yaw 换算世界速度 → noClip 移动。
     * 注意横移键位：A(左)=+1、D(右)=-1，跟 vanilla 相反——这是 tweakeroo 的坐标系约定，
     * 照抄才不会转向时左右颠倒（此前按 vanilla 键位实现，结果 A/D 反了）。 */
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

        float yaw = cam.getYaw();
        double xFactor = Math.sin(Math.toRadians(yaw));
        double zFactor = Math.cos(Math.toRadians(yaw));
        double speed = BASE_SPEED * GlobalConfig.FreeCam.SPEED.getDoubleValue();
        // 冲刺只放大前向分量（tweakeroo 同款 ×3）
        double forwardMotion = sprinting ? cameraMotion.x * 3.0 : cameraMotion.x;
        Vec3d velocity = new Vec3d(
                (cameraMotion.z * zFactor - forwardMotion * xFactor) * speed,
                cameraMotion.y * speed,
                (forwardMotion * zFactor + cameraMotion.z * xFactor) * speed);

        cam.setVelocity(velocity);
        cam.move(MovementType.SELF, velocity);   // noClip 直接穿方块移动
    }

    /** 鼠标转向转发（FreeCameraLookMixin 调进来）：增量交给相机实体（内建 0.15 灵敏度，与玩家一致）。
     * 相机自己的 {@code changeLookDirection} 同样会命中 LookMixin，不过 {@code this != mc.player} 直接放行。 */
    public static void rotateCamera(float yawChange, float pitchChange) {
        if (camera != null) {
            camera.changeLookDirection(yawChange, pitchChange);
        }
    }

    // ---------------- 内部：创建 / 插值 / 运动 ----------------

    private static CameraEntity createCameraEntity(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        CameraEntity cam = new CameraEntity(mc, mc.world, player.networkHandler,
                player.getStatHandler(), player.getRecipeBook());
        cam.noClip = true;   // 穿墙飞行

        float yaw = player.getYaw();
        float pitch = player.getPitch();
        cam.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), yaw, pitch);
        cam.setRotation(yaw, pitch);
        cam.updateInterpolation();

        return cam;
    }

    /** 手动维护渲染插值字段：相机不入世界、vanilla 不调它的 tick，prev* / lastRender* 全是死数据。 */
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

    /** 斜坡运动（照 tweakeroo calculatePlayerMotionWithDeceleration）：每轴独立斜坡，斜向 ÷1.2 抑制对角加速。 */
    private static Vec3d calculateMotionWithDeceleration(Vec3d lastMotion, int forward, int vertical, int strafe) {
        double diagonal = (forward != 0 && strafe != 0) ? DIAGONAL_FACTOR : 1.0;
        return new Vec3d(
                getRampedMotion(lastMotion.x, forward) / diagonal,
                getRampedMotion(lastMotion.y, vertical) / diagonal,
                getRampedMotion(lastMotion.z, strafe) / diagonal);
    }

    /** 单轴斜坡（照 tweakeroo getRampedMotion）：有输入按 RAMP_AMOUNT 逼近（方向反转先清零防拖滞），
     * 无输入按 DECELERATION 衰减，结果钳在 [-1, 1]。 */
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
