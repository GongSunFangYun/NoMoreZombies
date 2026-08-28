package cn.gsfy.nmz.client.feature.playerquery;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.HypixelApiClient;
import cn.gsfy.nmz.client.data.model.ApiResult;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 玩家数据查询的缓存 / 并发 / 生命周期管理——把所有查询请求收口到一处。
 *
 * <p>局内查询：开局后对最多 4 名队友并发请求并缓存（Key 没配就跳过），
 * 本局结束 / 退出 Zombies / 断线时由 {@link #clearCache()} 一把清掉。
 * 自由查询：输入玩家名走 Mojang 解析（带缓存），输入 UUID 直接规范化，
 * 回调在渲染线程执行。另自注册 tick 监听：缓存非空又不在 Zombies
 * （世界为空 / 计分板标题不符）就自动清缓存，兜住「退出模式」与断线场景。
 *
 * <p>线程模型：{@code NoMoreZombies-Query} 固定 4 线程 daemon 跑阻塞网络
 * 请求；缓存用 {@link ConcurrentHashMap}，界面每帧直接读，不用绕回主线程。
 * 代际计数 {@link #generation} 在 {@link #clearCache()} 时自增，迟到的结果
 * 对不上新代际就直接丢弃——否则已清空的缓存会被过期数据写回去。
 */
public final class PlayerQueryManager {

    /** 无连字符 UUID 判据：32 位 hex，大小写不敏感。 */
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    /** 带连字符 UUID 判据：标准 8-4-4-4-12 形态。 */
    private static final Pattern UUID_PATTERN_HYPHEN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** 全局单例——init() 里登记，get() 取用。 */
    private static PlayerQueryManager instance;

    /** 查询线程池：固定 4 条 daemon 线程，阻塞网络请求都扔这里。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "NoMoreZombies-Query");
        t.setDaemon(true);
        return t;
    });

    /** 局内缓存：名字（小写）→ 已解析的 Zombies 数据——键一律小写，查询不区分大小写。 */
    private final Map<String, ZombiesStats> cache = new ConcurrentHashMap<>();
    /** 局内请求失败的记录：名字（小写）→ 错误结果，界面据此显示失败态。 */
    private final Map<String, ApiResult> errors = new ConcurrentHashMap<>();
    /** 正在请求中的名字（小写）——防并发下同一个名字重复发请求。 */
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    /** 名字（小写）→ 无连字符 UUID：Mojang 解析结果缓存下来，省得重复请求被限速。 */
    private final Map<String, String> nameToUuid = new ConcurrentHashMap<>();

    /** 缓存代际：clearCache 自增一次，迟到结果对不上当前代际就丢弃。 */
    private volatile long generation;

    /** 返回全局单例；{@link #init()} 之前是 null，调用方记得判空。 */
    public static PlayerQueryManager get() {
        return instance;
    }

    /** 初始化：登记单例、挂客户端 tick 监听——tick 里顺带自清理过期的局内缓存。 */
    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** 每 tick 自检一次：发现已离开 Zombies（世界为空 / 非 Zombies 模式）就销毁局内缓存。 */
    private void tick() {
        if (cache.isEmpty() && errors.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        // 世界为空 / 已不在 Zombies 模式（大厅、其他游戏）→ 缓存留着也是死数据，直接清
        if (mc.world == null || !PlayerUtils.isInZombies()) {
            clearCache();
        }
    }

    // ---- 局内查询 ----

    /** API Key 配没配——决定局内自动查询和自由查询按钮要不要亮。 */
    public boolean hasApiKey() {
        return !GlobalConfig.getApiKeyPlain().isEmpty();
    }

    /**
     * 新游戏开始：对名单里的真人玩家并发发起请求（Key 为空直接跳过）。
     * 已缓存 / 已在请求中的名字都跳过——保证每名玩家每局只请求一次。
     */
    public void onGameStart(List<String> names) {
        if (!hasApiKey()) {
            return;
        }
        long gen = generation;
        String apiKey = GlobalConfig.getApiKeyPlain();
        for (String name : names) {
            if (name == null || !TeamStats.isValidPlayerName(name)) {
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

    /** 把当前局最多 4 名真人玩家并发请求（GameEventBus 在开局后延迟调用）。 */
    public void fetchInGamePlayers() {
        onGameStart(currentInGameNames());
    }

    /** 当前局的玩家名列表：队伍统计名单为主、本地玩家兜底，最多 4 人。 */
    public List<String> currentInGameNames() {
        List<String> names = new ArrayList<>(TeamStats.getPlayers().keySet());
        // 本地玩家兜底补进名单——开局早期队伍统计可能还没扫到自己
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

    /** 后台线程执行的请求体：解析 UUID（带缓存）→ 拉数据 → 按代际决定是否落缓存。 */
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
                if (gen == generation) {
                    errors.put(key, ApiResult.error("nomorezombies.query.status.notfound"));
                }
                return;
            }
            ApiResult result = HypixelApiClient.fetchPlayer(uuid, apiKey);
            if (gen != generation) {
                return; // 代际对不上 = 缓存已被清空，结果迟到，直接丢弃别写回
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

    /** 销毁局内缓存（游戏结束 / 退出 Zombies / 断线）——代际自增，让在途请求的结果作废。 */
    public void clearCache() {
        generation++;
        cache.clear();
        errors.clear();
        loading.clear();
    }

    // ---- 自由查询 ----

    /**
     * 自由查询（回调在渲染线程）：输入玩家名或 UUID 都行，
     * 无 Key / 空输入直接回错误，不发起请求。
     *
     * @param callback 结果回调，在渲染线程执行
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
                // UUID 输入：规范化后直接查，不用再解析
                result = HypixelApiClient.fetchPlayer(normalized, apiKey);
            } else {
                // 玩家名输入：先走 Mojang 解析（带缓存），解析不到就报 notfound
                String uuid = resolveUuidCached(normalized);
                result = (uuid != null)
                        ? HypixelApiClient.fetchPlayer(uuid, apiKey)
                        : ApiResult.error("nomorezombies.query.status.notfound");
            }
            // 自由查询跟局内缓存互不相干，结果必须无条件回调——漏一次界面就永远卡「加载中」
            deliver(result, callback);
        });
    }

    /** 名字解析到 UUID（带缓存，防 Mojang 限速）——自由查询走这里。 */
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

    /** 把结果丢回渲染线程：MinecraftClient.execute 保证回调在主线程跑。 */
    private static void deliver(ApiResult result, Consumer<ApiResult> callback) {
        MinecraftClient.getInstance().execute(() -> callback.accept(result));
    }

    // ---- 界面访问 ----

    /** 某玩家的缓存数据；名字转小写再查，未命中返回 null。 */
    public ZombiesStats getCached(String name) {
        return name == null ? null : cache.get(name.toLowerCase(Locale.ROOT));
    }

    /** 某玩家的请求错误；没报过错返回 null。 */
    public ApiResult getError(String name) {
        return name == null ? null : errors.get(name.toLowerCase(Locale.ROOT));
    }

    /** 某玩家是否还在请求中（界面据此显示加载态）。 */
    public boolean isLoading(String name) {
        return name != null && loading.contains(name.toLowerCase(Locale.ROOT));
    }

    // ---- 输入规范化 ----

    /** 是不是 UUID 输入：32 位 hex，或 8-4-4-4-12 带连字符那种。 */
    public static boolean isUuidInput(String input) {
        if (input == null) {
            return false;
        }
        String t = input.trim();
        return UUID_PATTERN.matcher(t).matches() || UUID_PATTERN_HYPHEN.matcher(t).matches();
    }

    /** 输入规范化：UUID 去连字符转小写（跟 Hypixel 侧保持一致）；玩家名原样返回。 */
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
