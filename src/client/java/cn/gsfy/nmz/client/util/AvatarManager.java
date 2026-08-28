package cn.gsfy.nmz.client.util;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.data.HypixelApiClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 玩家皮肤头像（正面脸裁剪）管理器——把「名字 → 能直接画的脸」这条流水线
 * 收进一个静态工具类，HUD 与 Screen 只管调用，网络与线程交给它内部打理。
 *
 * <p>拿到名字后依次走：tab 列表优先取 UUID（局内玩家免一次 Mojang 请求）、
 * 兜底后台走 Mojang 解析并缓存；再到会话服务器取皮肤 URL，后台线程下载 PNG、
 * 裁剪头部正面 (8,8,8,8)、最近邻 4× 放大到 32×32，最后回渲染线程
 * {@code registerTexture} 并写入缓存。任一步未就绪就返回 Steve 占位
 * （绘制其头部区域）；网络与裁剪全在 {@code NoMoreZombies-Avatar} 单线程
 * 守护线程执行，{@link #drawHead} 被 HUD 与 Screen 直接调用也安全。
 */
public final class AvatarManager {

    private static final String STEVE_PATH = "textures/entity/steve.png";
    /** Steve 占位：兜底玩家长相，取自整张皮肤里 (8,8) 起的 8×8 头部正面。 */
    private static final Identifier STEVE = Identifier.ofVanilla(STEVE_PATH);
    /** 默认头像（网络流程彻底失败后的回退）：32×32 PNG，
     * 放 assets/nomorezombies/textures/avatar/default_avatar.png。 */
    private static final Identifier DEFAULT_AVATAR = Identifier.of(NoMoreZombies.MOD_ID, "textures/avatar/default_avatar.png");
    /** 头像纹理边长：8×8 头部 + 帽子层最近邻 4× 放大后的 32 像素。 */
    private static final int AVATAR_TEX_SIZE = 32;

    /** 头像纹理缓存：名字（小写）→ 已注册纹理，命中就直接画，不再重复下载。 */
    private static final Map<String, Identifier> HEAD_CACHE = new ConcurrentHashMap<>();
    /** 名字 → UUID 缓存：存 Mojang 解析结果，同一人不用反复打 Mojang（防限速）。 */
    private static final Map<String, UuidEntry> UUID_CACHE = new ConcurrentHashMap<>();
    /** 正在处理的玩家名集合：同名请求去重，避免并发重复打 Mojang / 下载皮肤。 */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** 请求失败冷却：名字（小写）→ 可重试时间戳。失败后不能立刻重试，免得每帧刷请求。 */
    private static final Map<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    /** 失败重试计数：名字（小写）→ 已失败次数，攒满上限就永久回退默认头像。 */
    private static final Map<String, Integer> RETRY_COUNT = new ConcurrentHashMap<>();

    /** 名字 → UUID 缓存有效期（5 分钟）：过期就重新解析，兼顾新鲜度与限速。 */
    private static final long UUID_TTL_MS = 5 * 60 * 1000L;
    /** 失败后重试冷却（毫秒）：每次重试间隔 5 秒，失败太急只会一直吃限速。 */
    private static final long RETRY_DELAY_MS = 5 * 1000L;
    /** 失败重试上限：连错 3 次就永久回退默认头像，不再反复请求。 */
    private static final int MAX_RETRIES = 3;

    /** 头像流水线专用单线程池：下载、裁剪全在这里排队，避免与渲染线程抢时间。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NoMoreZombies-Avatar");
        t.setDaemon(true);
        return t;
    });

    /** 皮肤下载用的 HTTP 客户端：统一设 5 秒连接超时，避免坏链接一直挂着。 */
    private static final HttpClient SKIN_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private AvatarManager() {
    }

    /**
     * 取某玩家的头像纹理：HUD / Screen 画脸前调用，名字优先从 tab 列表拿 UUID
     * （局内玩家免一次 Mojang 请求），兜底后台走 Mojang 解析，均未就绪先返回
     * Steve 占位，画面不留空。
     *
     * @param name 玩家名
     * @param uuid 已知 UUID（可为 null，null 时自动解析）
     */
    public static Identifier getHeadTexture(String name, UUID uuid) {
        if (name == null || name.isEmpty()) {
            return STEVE;
        }
        String key = name.toLowerCase(Locale.ROOT);

        Identifier cached = HEAD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        // 冷却期内直接给 Steve：失败后不能每帧重试，否则会刷爆 Mojang / 皮肤站
        long now = System.currentTimeMillis();
        Long failUntil = FAILED_UNTIL.get(key);
        if (failUntil != null) {
            if (failUntil == Long.MAX_VALUE) {
                return DEFAULT_AVATAR; // 重试耗尽：永久回退默认头像
            }
            if (failUntil > now) {
                return STEVE;
            }
            FAILED_UNTIL.remove(key); // 冷却到期：清掉失败标记，允许重新发起请求
        }
        if (IN_FLIGHT.contains(key)) {
            return STEVE;
        }

        UUID resolved = uuid;
        if (resolved == null) {
            resolved = tabListUuid(name);
        }
        if (resolved == null) {
            resolved = cachedUuid(key);
            if (resolved == null) {
                scheduleResolve(key, name);
                return STEVE;
            }
        }
        scheduleFetch(key, resolved);
        return STEVE;
    }

    /** 以 8×8 尺寸绘制玩家头像（名字列前）：内部转调指定尺寸版本，未就绪画 Steve。 */
    public static void drawHead(DrawContext context, String name, UUID uuid, int x, int y) {
        drawHead(context, name, uuid, x, y, 8);
    }

    /**
     * 以指定尺寸绘制玩家头像（正面脸裁剪）：先取纹理，再按来源分流画法，
     * 调用方只需给出坐标与边长。
     *
     * @param size 屏幕上渲染的边长（px），纹理内部已 4× 放大保证小尺寸下像素清晰
     */
    public static void drawHead(DrawContext context, String name, UUID uuid, int x, int y, int size) {
        Identifier id = getHeadTexture(name, uuid);
        if (id == STEVE) {
            // Steve 占位：从整张皮肤里抠出 (8,8) 起的 8×8 头部正面，再缩放到 size
            context.drawTexture(RenderLayer::getGuiTextured, STEVE, x, y, 8, 8, size, size, 8, 8, 64, 64);
        } else {
            // 已注册 / 默认头像：整个纹理就是 32×32 正面脸，整张当源区域绘制
            context.drawTexture(RenderLayer::getGuiTextured, id, x, y, 0, 0, size, size,
                    AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE);
        }
    }

    /** 以 8×8 尺寸绘制默认头像（default_avatar.png，本地资源，无网络请求）；
     * 供 HUD 编辑器示例等本地场景使用。 */
    public static void drawDefaultHead(DrawContext context, int x, int y) {
        drawDefaultHead(context, x, y, 8);
    }

    /** 以指定尺寸绘制默认头像：整张 32×32 本地纹理作源区域，本地场景无需等网络。 */
    public static void drawDefaultHead(DrawContext context, int x, int y, int size) {
        context.drawTexture(RenderLayer::getGuiTextured, DEFAULT_AVATAR, x, y, 0, 0, size, size,
                AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE);
    }

    // ---- 内部：解析 / 下载 / 裁剪 / 注册 ----

    /** 从客户端 tab 列表直接取 UUID：局内玩家名字对得上就能免一次 Mojang 请求。 */
    private static UUID tabListUuid(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return null;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
        return entry != null ? entry.getProfile().getId() : null;
    }

    /** 从 UUID 缓存取值：未过期才有效，过期直接移除并返回 null，逼下一次重新解析。 */
    private static UUID cachedUuid(String key) {
        UuidEntry e = UUID_CACHE.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.fetchedAt > UUID_TTL_MS) {
            UUID_CACHE.remove(key);
            return null;
        }
        return e.uuid;
    }

    /** 后台异步：Mojang 名→UUID，成功后接着取皮肤；同名重复请求由 IN_FLIGHT 挡掉。 */
    private static void scheduleResolve(String key, String name) {
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                String uuidNoHyphen = HypixelApiClient.resolveUuid(name);
                if (uuidNoHyphen == null) {
                    markFailed(key);
                    return;
                }
                UUID uuid = uuidFromNoHyphen(uuidNoHyphen);
                if (uuid == null) {
                    markFailed(key);
                    return;
                }
                UUID_CACHE.put(key, new UuidEntry(uuid, System.currentTimeMillis()));
                fetchSkin(key, uuid);
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /** 后台异步：会话服务器取皮肤 URL → 下载 → 裁剪 → 注册，由 EXECUTOR 单线程执行。 */
    private static void scheduleFetch(String key, UUID uuid) {
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                fetchSkin(key, uuid);
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /** 后台取皮肤并注册纹理：取 URL → 下载 PNG → 裁剪 → 渲染线程注册，
     * 任何一步失败都标记失败，交给 markFailed 决定重试还是放弃。 */
    private static void fetchSkin(String key, UUID uuid) {
        try {
            String skinUrl = HypixelApiClient.fetchSkinUrl(uuid.toString());
            if (skinUrl == null) {
                markFailed(key);
                return;
            }
            byte[] png = downloadPng(skinUrl);
            if (png == null) {
                markFailed(key);
                return;
            }
            NativeImageBackedTexture tex = cropHead(png);
            if (tex == null) {
                markFailed(key);
                return;
            }
            Identifier id = Identifier.of(NoMoreZombies.MOD_ID, "avatar/" + uuid);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                tex.close();
                markFailed(key);
                return;
            }
            // registerTexture 必须回渲染线程；先注册后写缓存——否则缓存里
            // 出现未注册纹理，画出来是紫黑块
            client.execute(() -> {
                client.getTextureManager().registerTexture(id, tex);
                HEAD_CACHE.put(key, id);
                FAILED_UNTIL.remove(key);
                RETRY_COUNT.remove(key);
            });
        } catch (Exception e) {
            NoMoreZombies.LOGGER.warn("Avatar fetch failed for {}", uuid);
            markFailed(key);
        }
    }

    /** 记一次失败：未达上限进短冷却等重试，达上限就永久回退默认头像，不再折腾。 */
    private static void markFailed(String key) {
        int count = RETRY_COUNT.merge(key, 1, Integer::sum);
        if (count < MAX_RETRIES) {
            FAILED_UNTIL.put(key, System.currentTimeMillis() + RETRY_DELAY_MS);
        } else {
            FAILED_UNTIL.put(key, Long.MAX_VALUE);
        }
    }

    /** 后台下载皮肤 PNG 字节：状态非 200 或空体都返回 null，交给上层标记失败。 */
    private static byte[] downloadPng(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = SKIN_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body().length == 0) {
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从皮肤 PNG 裁剪头部正面：8×8 脸 + 8×8 帽子层 alpha 混合，
     * 最近邻 4× 放大到 32×32（chat_heads 方式），尺寸不对直接放弃。 */
    private static NativeImageBackedTexture cropHead(byte[] png) {
        NativeImage img = null;
        try (InputStream in = new ByteArrayInputStream(png)) {
            img = NativeImage.read(in);
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                return null;
            }
            NativeImage head = new NativeImage(32, 32, true);
            for (int sx = 0; sx < 8; sx++) {
                for (int sy = 0; sy < 8; sy++) {
                    int face = img.getColorArgb(8 + sx, 8 + sy);      // 头部正面（皮肤左上角 8×8 的脸）
                    int hat = img.getColorArgb(40 + sx, 8 + sy);      // 帽子层（皮肤第二行左侧 8×8，叠在脸上）
                    int argb = blendArgb(face, hat);
                    for (int dx = 0; dx < 4; dx++) {
                        for (int dy = 0; dy < 4; dy++) {
                            head.setColorArgb(sx * 4 + dx, sy * 4 + dy, argb);
                        }
                    }
                }
            }
            return new NativeImageBackedTexture(head);
        } catch (IOException e) {
            return null;
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /** 帽子层按自身 alpha 叠到脸上（chat_heads 的混合式）：不透明处显帽子，透明处透出脸。 */
    private static int blendArgb(int face, int hat) {
        int hatA = (hat >> 24) & 0xFF;
        if (hatA == 0) {
            return face; // 帽子完全透明：直接拿脸，省一次混合
        }
        float a = hatA / 255f;
        float inv = 1f - a;
        int faceA = (face >> 24) & 0xFF;
        int faceR = (face >> 16) & 0xFF;
        int faceG = (face >> 8) & 0xFF;
        int faceB = face & 0xFF;
        int hatR = (hat >> 16) & 0xFF;
        int hatG = (hat >> 8) & 0xFF;
        int hatB = hat & 0xFF;
        int outA = clamp(Math.round(a * a * 255f + inv * faceA));
        int outR = clamp(Math.round(a * hatR + inv * faceR));
        int outG = clamp(Math.round(a * hatG + inv * faceG));
        int outB = clamp(Math.round(a * hatB + inv * faceB));
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    /** 把混合结果夹到 [0, 255]：浮点混合后不越界，颜色通道才不会溢出。 */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 无连字符 UUID（32 位十六进制）→ {@link UUID}：Mojang 返回的正是这种格式，非法输入返回 null。 */
    private static UUID uuidFromNoHyphen(String s) {
        if (s == null || s.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                    + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** UUID 缓存条目：UUID + 获取时间戳，时间戳供 TTL 判定过期用。 */
    private record UuidEntry(UUID uuid, long fetchedAt) {
    }
}
