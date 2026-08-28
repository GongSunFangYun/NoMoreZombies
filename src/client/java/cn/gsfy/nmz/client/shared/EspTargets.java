package cn.gsfy.nmz.client.shared;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * ESP 与怪物血条共享的目标实体扫描与缓存——两个渲染器复用同一个目标名单。
 *
 * <p>每 {@value #REFRESH_TICKS} 个 client tick 重扫一次（避免每帧遍历全图实体），
 * 世界切换时立即重扫。两个渲染器各自在渲染时调用 {@link #ensureScanned(World, Vec3d)}，
 * {@code scanned} 标志保证同一刷新窗口内只扫一次（帧内幂等）。
 */
public final class EspTargets {

    /** 搜索范围（方块数）：以相机为中心向四周各延伸这么多格。 */
    public static final int SEARCH_RADIUS = 128;

    /** 目标列表刷新间隔（client tick）——参照 ScoreboardManager 的每 N tick 轮询模式，避免每帧重扫 */
    public static final int REFRESH_TICKS = 10;

    private static int scanTicks;
    private static World scanWorld;
    /** 当前刷新窗口是否已扫描（保证两个渲染器同帧只扫一次） */
    private static boolean scanned;
    private static List<Entity> targets = List.of();

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (++scanTicks >= REFRESH_TICKS) {
                scanTicks = 0;
                scanned = false; // 打开下一个刷新窗口
            }
        });
    }

    /**
     * 确保目标列表已扫描：世界变化时强制立即重扫，同帧第二次调用直接复用缓存。
     * 两个渲染器都要调用，代价只是几次比较。
     */
    public static void ensureScanned(World world, Vec3d cam) {
        if (world == null) {
            return;
        }
        if (scanWorld != world) {
            scanWorld = world;
            scanned = false; // 世界切换：立即重扫，防止画上一个世界的残留实体
        }
        if (!scanned) {
            targets = scanTargets(world, cam);
            scanned = true;
        }
    }

    public static List<Entity> getTargets() {
        return targets;
    }

    private static List<Entity> scanTargets(World world, Vec3d cam) {
        Box searchBox = new Box(
                cam.x - SEARCH_RADIUS, cam.y - SEARCH_RADIUS, cam.z - SEARCH_RADIUS,
                cam.x + SEARCH_RADIUS, cam.y + SEARCH_RADIUS, cam.z + SEARCH_RADIUS
        );
        return world.getEntitiesByType(
                TypeFilter.instanceOf(Entity.class),
                searchBox,
                e -> e != MinecraftClient.getInstance().player && EntityEsp.isTarget(e)
        );
    }

    private EspTargets() {
    }
}
