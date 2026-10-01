package cn.gsfy.nmz.client.shared.esp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * The target entity scan and cache shared by ESP and mob health bars—the two
 * renderers reuse one target list.
 *
 * <p>Rescanned once every {@value #REFRESH_TICKS} client ticks (avoiding a
 * full-world entity iteration every frame); a world switch rescans
 * immediately.
 *
 * <p>The scan is split in two: the tick callback only opens the window (sets
 * {@code scanned} back to false), while the actual scan is lazily deferred to
 * render time—the renderer's call into
 * {@link #ensureScanned(World, Vec3d)} happens at an unpredictable moment,
 * so the first call after the window opens does the scan, using the current
 * frame's camera position. The {@code scanned} flag guarantees one scan per
 * refresh window (idempotent within a frame).
 */
public final class EspTargets {

    /** Search radius (blocks, {@value}): the camera-centered cube extends this many blocks along each axis. */
    public static final int SEARCH_RADIUS = 128;

    /** Target list refresh interval (client ticks): following ScoreboardManager's every-N-ticks
     *  polling pattern, avoiding a rescan per frame. */
    public static final int REFRESH_TICKS = 10;

    private static int scanTicks;
    private static World scanWorld;
    /** Whether the current refresh window was already scanned (guaranteeing the two renderers scan once per frame). */
    private static boolean scanned;
    private static List<Entity> targets = List.of();

    /** Builds the singleton and hooks the per-tick counter: once
     *  {@link #REFRESH_TICKS} accumulate, the next refresh window opens;
     *  the rescan is handed to {@link #ensureScanned(World, Vec3d)}. */
    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (++scanTicks >= REFRESH_TICKS) {
                scanTicks = 0;
                scanned = false; // Open the next refresh window.
            }
        });
    }

    /**
     * Called by both renderers before drawing: a world change rescans
     * immediately, later calls within the same refresh window reuse the
     * cache.
     *
     * @param world the current client world; {@code null} keeps the old
     *   cache and returns
     * @param cam the current camera position, used as the center of the
     *   {@link #SEARCH_RADIUS} search box
     */
    public static void ensureScanned(World world, Vec3d cam) {
        if (world == null) {
            return;
        }
        if (scanWorld != world) {
            scanWorld = world;
            scanned = false; // World switch: rescan immediately, so last world's entities are not drawn.
        }
        if (!scanned) {
            targets = scanTargets(world, cam);
            scanned = true;
        }
    }

    /** The cached target list: {@code List.of()} before the first scan.
     *  A stable reference between scans, replaced wholesale on each scan.
     *  Renderers read it per frame; it refreshes with the scan every
     *  {@value #REFRESH_TICKS} ticks. */
    public static List<Entity> getTargets() {
        return targets;
    }

    /** One full scan: inside the camera-centered cube extended
     *  {@link #SEARCH_RADIUS} along each axis, each entity goes through
     *  {@link EntityEsp#isTarget(Entity)}; self is excluded. */
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