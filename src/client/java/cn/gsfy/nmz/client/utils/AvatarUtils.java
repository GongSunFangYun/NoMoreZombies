package cn.gsfy.nmz.client.utils;

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
 * Player skin avatar (front-face crop) manager—the "name → drawable face"
 * pipeline packed into one static utility; HUDs and Screens just call it,
 * networking and threading stay inside.
 *
 * <p>Given a name it walks: tab list first for the UUID (in-round players
 * skip one Mojang request), falling back to a background Mojang resolve that
 * is cached; then the session server for the skin URL, a background thread
 * downloads the PNG, crops the head front (8,8,8,8), nearest-neighbor scales
 * 4x to 32x32, and finally {@code registerTexture} runs back on the render
 * thread and the cache is written. Any step not ready returns the Steve
 * placeholder (drawing its head area). Network and cropping all run on the
 * single daemon thread {@code NoMoreZombies-Avatar}, so {@code drawHead} is
 * safe to call directly from HUDs and Screens.
 */
public final class AvatarUtils {

    private static final String STEVE_PATH = "textures/entity/steve.png";
    /** Steve placeholder: fallback face, taken from the 8x8 head front at
     * (8,8) of the full skin. */
    private static final Identifier STEVE = Identifier.ofVanilla(STEVE_PATH);
    /** Default avatar (fallback after the network pipeline fails for good):
     * a 32x32 PNG at assets/nomorezombies/textures/avatar/default_avatar.png. */
    private static final Identifier DEFAULT_AVATAR = Identifier.of(NoMoreZombies.MOD_ID, "textures/avatar/default_avatar.png");
    /** Avatar texture side length: 32 px after 4x nearest-neighbor scaling of
     * the 8x8 head + hat layer. */
    private static final int AVATAR_TEX_SIZE = 32;

    /** Avatar texture cache: name (lowercase) → registered texture; a hit
     * draws directly, no re-download. */
    private static final Map<String, Identifier> HEAD_CACHE = new ConcurrentHashMap<>();
    /** Name → UUID cache: stores Mojang resolve results so the same player
     * does not hit Mojang repeatedly (rate-limit protection). */
    private static final Map<String, UuidEntry> UUID_CACHE = new ConcurrentHashMap<>();
    /** Names currently in flight: deduplicates same-name requests, avoiding
     * concurrent duplicate Mojang calls / skin downloads. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** Failure cooldown: name (lowercase) → retry-allowed timestamp - a
     * failed name must not retry instantly or every frame fires a request. */
    private static final Map<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    /** Failure retry counter: name (lowercase) → failures so far; the cap
     * reached means a permanent fallback to the default avatar. */
    private static final Map<String, Integer> RETRY_COUNT = new ConcurrentHashMap<>();

    /** Name → UUID cache TTL (5 minutes): re-resolve after expiry,
     * balancing freshness against rate limits. */
    private static final long UUID_TTL_MS = 5 * 60 * 1000L;
    /** Retry cooldown after failure (ms): 5 s between attempts - hammering
     * after a failure only eats rate limits. */
    private static final long RETRY_DELAY_MS = 5 * 1000L;
    /** Retry cap: 3 consecutive failures mean a permanent default-avatar
     * fallback, no more requests. */
    private static final int MAX_RETRIES = 3;

    /** Dedicated single-thread pool for the avatar pipeline: downloads and
     * cropping queue here, never racing the render thread. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NoMoreZombies-Avatar");
        t.setDaemon(true);
        return t;
    });

    /** HTTP client for skin downloads: one shared 5 s connect timeout so a
     * bad URL cannot hang forever. */
    private static final HttpClient SKIN_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private AvatarUtils() {
    }

    /**
     * Fetches a player's avatar texture: called before a HUD/Screen draws
     * the face. The UUID comes from the tab list first (in-round players
     * skip one Mojang request), falling back to a background Mojang resolve;
     * while neither is ready the Steve placeholder is returned so the
     * picture never goes blank.
     *
     * <p>The method does not wait for the network: on a cache miss it only
     * queues the work to the single-thread daemon executor and returns the
     * placeholder; the real texture registration switches back to the client
     * thread later. In-flight requests for the same lowercase name merge.
     *
     * @param name player name; {@code null} or empty returns Steve directly
     * @param uuid known UUID (may be {@code null}, in which case tab list,
     *  cache and Mojang are consulted in that order)
     * @return cached avatar, the default avatar after permanent failure, or
     *  Steve while still loading; never {@code null}
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
        // Inside the cooldown return Steve directly: a failed name must not
        // retry every frame or it floods Mojang/the skin server
        long now = System.currentTimeMillis();
        Long failUntil = FAILED_UNTIL.get(key);
        if (failUntil != null) {
            if (failUntil == Long.MAX_VALUE) {
                return DEFAULT_AVATAR; // Retries exhausted: permanent fallback to the default avatar.
            }
            if (failUntil > now) {
                return STEVE;
            }
            FAILED_UNTIL.remove(key); // Cooldown expired: clear the failure mark, requests allowed again.
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

    /** Draws the player avatar at 8x8 (before the name column): forwards to
     * the sized variant, Steve while not ready. */
    public static void drawHead(DrawContext context, String name, UUID uuid, int x, int y) {
        drawHead(context, name, uuid, x, y, 8);
    }

    /**
     * Draws the player avatar at a given size (front-face crop): fetches the
     * texture first, then branches the draw by source; callers only supply
     * coordinates and a side length.
     *
     * @param size rendered side length in px; the texture is pre-scaled 4x
     *  internally so small sizes stay pixel-crisp
     */
    public static void drawHead(DrawContext context, String name, UUID uuid, int x, int y, int size) {
        Identifier id = getHeadTexture(name, uuid);
        if (id == STEVE) {
            // Steve placeholder: crop the 8x8 head front at (8,8) from the
            // full skin, then scale to size
            context.drawTexture(RenderLayer::getGuiTextured, STEVE, x, y, 8, 8, size, size, 8, 8, 64, 64);
        } else {
            // Registered / default avatar: the whole texture is the 32x32
            // front face, drawn whole as the source region
            context.drawTexture(RenderLayer::getGuiTextured, id, x, y, 0, 0, size, size,
                    AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE);
        }
    }

    /** Draws the default avatar at the given size: the whole 32x32 local
     *  texture is the source region—a local scene that needs no network. */
    public static void drawDefaultHead(DrawContext context, int x, int y, int size) {
        context.drawTexture(RenderLayer::getGuiTextured, DEFAULT_AVATAR, x, y, 0, 0, size, size,
                AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE, AVATAR_TEX_SIZE);
    }

    // ----Internal: resolve / download / crop / register----

    /** Reads the UUID straight from the client tab list: an in-round player
     *  name match skips one Mojang request. */
    private static UUID tabListUuid(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return null;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
        return entry != null ? entry.getProfile().getId() : null;
    }

    /** Reads from the UUID cache: valid only before TTL expiry; expired
     *  entries are removed and return null, forcing a re-resolve next call. */
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

    /** Async background: Mojang name→UUID, then fetch the skin on success;
     *  duplicate same-name requests are blocked by IN_FLIGHT. */
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

    /** Async background: fetch the skin URL from the session server →
     *  download → crop → register, run on the single-thread EXECUTOR. */
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

    /** Fetches the skin and registers the texture in the background: fetch
     *  URL → download PNG → crop → register on the render thread; any step
     *  failing marks the failure and lets markFailed decide retry or give
     *  up. */
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
            // registerTexture must go back to the render thread; register
            // before writing the cache - otherwise an unregistered texture
            // sits in the cache and draws as a purple-black block
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

    /** Records one failure: below the cap, enter a short cooldown for retry;
     *  at the cap, permanently fall back to the default avatar and stop. */
    private static void markFailed(String key) {
        int count = RETRY_COUNT.merge(key, 1, Integer::sum);
        if (count < MAX_RETRIES) {
            FAILED_UNTIL.put(key, System.currentTimeMillis() + RETRY_DELAY_MS);
        } else {
            FAILED_UNTIL.put(key, Long.MAX_VALUE);
        }
    }

    /**
     * Downloads the skin PNG bytes in the background—the request has a 5 s
     * timeout; a non-200 status or an empty body returns {@code null}. The
     * JDK client reads the response whole into a byte[]; no byte cap is set
     * here, and only 64x64 images are accepted downstream.
     *
     * @param url the skin URL; an invalid URI returns a failure
     * @return the non-empty body, or {@code null} on failure
     */
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

    /** Crops the head front from the skin PNG: the face is 8x8 at (8,8), the
     *  hat layer 8x8 at (40,8); after alpha blending, nearest-neighbor 4x to
     *  32x32 (chat_heads style). An unexpected size gives up directly. */
    private static NativeImageBackedTexture cropHead(byte[] png) {
        // NativeImage is AutoCloseable: both resources go through
        // try-with-resources, so the early return on a size mismatch and the
        // normal return both close the source image without a finally block
        try (InputStream in = new ByteArrayInputStream(png);
             NativeImage img = NativeImage.read(in)) {
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                return null;
            }
            NativeImage head = new NativeImage(32, 32, true);
            for (int sx = 0; sx < 8; sx++) {
                for (int sy = 0; sy < 8; sy++) {
                    int face = img.getColorArgb(8 + sx, 8 + sy);      // Head front.
                    int hat = img.getColorArgb(40 + sx, 8 + sy);      // Hat layer.
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
        }
    }

    /** Blends the hat layer onto the face by its own alpha (chat_heads'
     *  formula): opaque pixels show the hat, transparent ones show the face. */
    private static int blendArgb(int face, int hat) {
        int hatA = (hat >> 24) & 0xFF;
        if (hatA == 0) {
            return face; // Hat fully transparent: use the face directly, saving a blend.
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

    /**
     * Clamps the blend result to [0,255]: after a floating-point blend, only
     * an in-range color channel cannot overflow.
     *
     * <p>The ternary is equivalent to {@code Math.min(255, Math.max(0, v))}.
     * This is the last step of per-channel blending, written as "negative →
     * 0, over → 255" for direct reading; a change to a math call is just
     * another spelling, so either form is fine.
     */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Hyphen-less UUID (32 hex digits) → {@link UUID}: Mojang returns
     *  exactly this format; invalid input returns null. */
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

    /** UUID cache entry: UUID + fetch timestamp; the timestamp drives TTL
     *  expiry. */
    private record UuidEntry(UUID uuid, long fetchedAt) {
    }
}