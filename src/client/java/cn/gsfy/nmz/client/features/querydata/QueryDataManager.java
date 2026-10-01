package cn.gsfy.nmz.client.features.querydata;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.HypixelApiClient;
import cn.gsfy.nmz.client.data.model.ApiResult;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The cache / concurrency / lifecycle manager for player data queries -
 * funnels every query request through one place.
 *
 * <p>In-game query: at game start, requests up to four teammates concurrently
 * and caches them (skipped when no key is configured); a game end / leaving
 * Zombies / disconnect clears everything at once via {@link #clearCache()}.
 * Free query: input a player name to resolve through Mojang (with cache), or
 * input a UUID to normalize directly, with the callback run on the render
 * thread. A tick listener is also self-registered: when the cache is
 * non-empty and not in Zombies (empty world / scoreboard title mismatch),
 * the cache is cleared automatically, covering the "left the mode" and
 * disconnect cases.
 *
 * <p>Threading: a fixed four-thread daemon pool
 * ({@code NoMoreZombies-Query}) runs the blocking network requests; the
 * cache uses {@link ConcurrentHashMap}, the UI reads it every frame without
 * bouncing back to the main thread. A generation counter
 * ({@link #generation}) increments on {@link #clearCache()}; late results
 * whose generation does not match are dropped - otherwise an already-cleared
 * cache would be written back by stale data.
 */
public final class QueryDataManager {

    /** Hyphen-less UUID test: 32 hex digits, case-insensitive. */
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    /** Hyphenated UUID test: the standard 8-4-4-4-12 shape. */
    private static final Pattern UUID_PATTERN_HYPHEN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Global singleton - registered in init(), fetched by get(). */
    private static QueryDataManager instance;

    /**
     * The query thread pool: four fixed daemon threads; all blocking network
     * requests go here.
     *
     * <p>Four matches the in-game limit of at most four players
     * ({@link TeamStats#MAX_PLAYERS}) - one thread per player is exactly
     * enough to query them all in parallel.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "NoMoreZombies-Query");
        t.setDaemon(true);
        return t;
    });

    /** In-game cache: name (lower case) -> parsed Zombies data - keys are always lower case, lookups case-insensitive. */
    private final Map<String, ZombiesStats> cache = new ConcurrentHashMap<>();
    /** In-game request failures: name (lower case) -> error result, so the UI can show a failure state. */
    private final Map<String, ApiResult> errors = new ConcurrentHashMap<>();
    /**
     * Names (lower case) currently in flight - prevents the same name being
     * requested twice under concurrency. A separate set rather than reusing
     * cache.containsKey: success, error and in-flight states are kept
     * distinct, so "is the cache populated" cannot be mistaken for the
     * request's state.
     */
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    /** Name (lower case) -> hyphen-less UUID: the Mojang resolution result is cached, avoiding repeat requests and rate limits. */
    private final Map<String, String> nameToUuid = new ConcurrentHashMap<>();

    /**
     * Cache generation: clearCache increments it once; late results whose
     * generation does not match are dropped.
     *
     * <p>{@link AtomicLong} rather than {@code volatile long}:
     * {@code generation++} is a read-modify-write, and two threads calling
     * clearCache at once would lose one increment, letting a late result
     * between the two clears match the new generation and write back into
     * the cleared cache. The request runs on a four-thread pool, so the
     * increment must be atomic.
     */
    private final AtomicLong generation = new AtomicLong();

    /** Returns the global singleton; null before {@link #init()}, so callers must null-check. */
    public static QueryDataManager get() {
        return instance;
    }

    /** Initialization: registers the singleton and hooks the client tick listener - the tick also self-cleans stale in-game caches. */
    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /**
     * Translation keys whose text identifies the client language: the parser bakes <b>translated</b>
     * labels into {@code ZombiesStats} (map / stat / difficulty names), and the UI looks data up by
     * the current translations - so cached data is only readable in the language it was parsed in.
     */
    private static final String[] LANG_PROBE_KEYS = {
            "nomorezombies.query.map.deadend",
            "nomorezombies.query.diff.normal",
            "nomorezombies.query.stat.wins"};
    /** Translation fingerprint the cache was parsed under; null until the first tick sees loaded translations. */
    private String langSig;
    /** Ticks left before refetching after a language change (-1 = nothing pending) - waits for the resource reload to settle. */
    private int refetchDelay = -1;

    /** The current translation fingerprint; null while translations are not loaded (a key comes back as itself). */
    private static String currentLangSig() {
        StringBuilder sb = new StringBuilder();
        for (String key : LANG_PROBE_KEYS) {
            String text = Text.translatable(key).getString();
            if (key.equals(text)) {
                return null;
            }
            sb.append(text).append('|');
        }
        return sb.toString();
    }

    /**
     * A language switch (resource reload) makes every cached {@code ZombiesStats} unreadable: its labels
     * are in the old language, the lookups use the new one, and every cumulative / per-map value then
     * misses and draws as "-". So on a change the cache is dropped and, once the reload has settled,
     * the in-game players are fetched again (parsed in the new language).
     */
    private void checkLanguage() {
        String sig = currentLangSig();
        if (sig == null) {
            return; // translations are reloading - decide once they are back
        }
        if (langSig == null) {
            langSig = sig;
            return;
        }
        if (!sig.equals(langSig)) {
            langSig = sig;
            clearCache();
            refetchDelay = 20;
            return;
        }
        if (refetchDelay > 0 && --refetchDelay == 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null && PlayerUtils.isInZombies()) {
                fetchInGamePlayers();
            }
        }
    }

    /** Self-check once per tick: when no longer in Zombies (empty world / non-Zombies mode), destroy the in-game cache. */
    private void tick() {
        checkLanguage();
        if (cache.isEmpty() && errors.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        // Empty world / no longer in Zombies (lobby, other minigames) -> the cache is stale
        // data, cleared immediately
        if (mc.world == null || !PlayerUtils.isInZombies()) {
            clearCache();
        }
    }

    // ----In-game query----

    /** Whether an API key is configured - decides whether in-game auto queries and the free-query button light up. */
    public boolean hasApiKey() {
        return !GlobalConfig.getApiKeyPlain().isEmpty();
    }

    /**
     * A new game starts: fires concurrent requests for the real players in
     * the roster (skipped when the key is empty). Names already cached or in
     * flight are skipped, guaranteeing one request per player per game.
     */
    public void onGameStart(List<String> names) {
        if (!hasApiKey()) {
            return;
        }
        long gen = generation.get();
        String apiKey = GlobalConfig.getApiKeyPlain();
        for (String name : names) {
            if (!TeamStats.isValidPlayerName(name)) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (cache.containsKey(key)) {
                continue;
            }
            if (!loading.add(key)) {
                continue;
            }
            executor.execute(() -> fetchAndCache(gen, key, name, apiKey));
        }
    }

    /**
     * Concurrently requests up to four real players in the current game -
     * GameEventBus calls this 3 seconds (60 ticks) after each round via
     * {@code runTaskLater(60)}.
     */
    public void fetchInGamePlayers() {
        onGameStart(currentInGameNames());
    }

    /** The current game's player names: the team stats roster first, the local player as a fallback, at most four. */
    public List<String> currentInGameNames() {
        List<String> names = new ArrayList<>(TeamStats.getPlayers().keySet());
        // Local player as fallback - the team stats roster may not have scanned self early
        // in the game
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            String self = mc.player.getGameProfile().getName();
            if (self != null && !self.isEmpty() && !names.contains(self)) {
                names.add(self);
            }
        }
        if (names.size() > TeamStats.MAX_PLAYERS) {
            names = new ArrayList<>(names.subList(0, TeamStats.MAX_PLAYERS));
        }
        return names;
    }

    /** The request body run on a background thread: resolve UUID (with cache) -> fetch data -> decide by generation whether to cache. */
    private void fetchAndCache(long gen, String key, String name, String apiKey) {
        try {
            String uuid = nameToUuid.get(key);
            if (uuid == null) {
                uuid = HypixelApiClient.resolveUuid(name);
                if (uuid != null) {
                    nameToUuid.put(key, uuid);
                }
            }
            if (uuid == null) {
                if (gen == generation.get()) {
                    errors.put(key, ApiResult.error("nomorezombies.query.status.notfound"));
                }
                return;
            }
            ApiResult result = HypixelApiClient.fetchPlayer(uuid, apiKey);
            if (gen != generation.get()) {
                return; // Generation mismatch = the cache was cleared, the result is late; drop it
            }
            if (result.ok()) {
                cache.put(key, result.stats());
                errors.remove(key);
            } else {
                errors.put(key, result);
            }
        } finally {
            loading.remove(key);
        }
    }

    /** Destroys the in-game cache (game end / leaving Zombies / disconnect) - the generation increments so late requests' results are void. */
    public void clearCache() {
        generation.incrementAndGet();
        cache.clear();
        errors.clear();
        loading.clear();
    }

    // ----Free query----

    /**
     * The free query (callback on the render thread): input a player name or
     * UUID; no key / empty input returns an error without making a request.
     *
     * @param callback result callback, run on the render thread
     */
    public void queryFree(String input, Consumer<ApiResult> callback) {
        if (!hasApiKey()) {
            deliver(ApiResult.error("nomorezombies.query.warning.nokey"), callback);
            return;
        }
        if (input == null || input.trim().isEmpty()) {
            deliver(ApiResult.error("nomorezombies.query.status.nodata"), callback);
            return;
        }
        String normalized = normalizeInput(input);
        String apiKey = GlobalConfig.getApiKeyPlain();
        executor.execute(() -> {
            ApiResult result;
            if (isUuidInput(input)) {
                // UUID input: normalize then fetch directly, no resolution step
                result = HypixelApiClient.fetchPlayer(normalized, apiKey);
            } else {
                // Player-name input: resolve through Mojang (with cache) first, or report notfound
                String uuid = resolveUuidCached(normalized);
                result = (uuid != null)
                        ? HypixelApiClient.fetchPlayer(uuid, apiKey)
                        : ApiResult.error("nomorezombies.query.status.notfound");
            }
            // The free query is independent of the in-game cache, so the callback is always
            // delivered - one miss would leave the UI stuck loading forever
            deliver(result, callback);
        });
    }

    /** Resolves a name to a UUID (with cache, avoiding Mojang rate limits) - the free query goes through here. */
    private String resolveUuidCached(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String uuid = nameToUuid.get(key);
        if (uuid == null) {
            uuid = HypixelApiClient.resolveUuid(name);
            if (uuid != null) {
                nameToUuid.put(key, uuid);
            }
        }
        return uuid;
    }

    /** Bounces the result back to the render thread: MinecraftClient.execute guarantees the callback runs on the main thread. */
    private static void deliver(ApiResult result, Consumer<ApiResult> callback) {
        MinecraftClient.getInstance().execute(() -> callback.accept(result));
    }

    // ----UI access----

    /** A player's cached data; the name is lower-cased then looked up, null on a miss. */
    public ZombiesStats getCached(String name) {
        return name == null ? null : cache.get(name.toLowerCase(Locale.ROOT));
    }

    /** A player's request error; null when it never failed. */
    public ApiResult getError(String name) {
        return name == null ? null : errors.get(name.toLowerCase(Locale.ROOT));
    }

    /** Whether a player is still being requested (the UI shows a loading state from this). */
    public boolean isLoading(String name) {
        return name != null && loading.contains(name.toLowerCase(Locale.ROOT));
    }

    // ----Input normalization----

    /** Whether the input is a UUID: 32 hex digits, or the 8-4-4-4-12 hyphenated form. */
    public static boolean isUuidInput(String input) {
        if (input == null) {
            return false;
        }
        String t = input.trim();
        return UUID_PATTERN.matcher(t).matches() || UUID_PATTERN_HYPHEN.matcher(t).matches();
    }

    /** Input normalization: a UUID drops hyphens and lower-cases (matching Hypixel's side); a player name is returned as is. */
    private static String normalizeInput(String input) {
        String t = input.trim();
        if (UUID_PATTERN.matcher(t).matches()) {
            return t.toLowerCase(Locale.ROOT);
        }
        if (UUID_PATTERN_HYPHEN.matcher(t).matches()) {
            return t.replace("-", "").toLowerCase(Locale.ROOT);
        }
        return t;
    }
}