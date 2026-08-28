package cn.gsfy.nmz.client.data;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.data.model.ApiResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 玩家数据查询的请求层——对外只认 Mojang 与 Hypixel 两家 HTTP API：
 * Mojang 负责「玩家名 → UUID / 皮肤」，Hypixel 负责「UUID → 玩家 Zombies 数据」。
 *
 * <p>共用单例 {@link HttpClient}，连接 / 请求均 5 秒超时，宁快断不干等；全部是阻塞方法，
 * 由调用方丢到后台线程。错误不抛异常，统一映射成 {@link ApiResult} 让界面直接 translate
 * （翻译 key 见 {@code nomorezombies.query.*}）。
 */
public final class HypixelApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "NoMoreZombies/1.0";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private HypixelApiClient() {
    }

    /**
     * 通过 Mojang API 把玩家名解析成 UUID（无连字符、小写）。
     * 名字不存在 / 触发限流 / 响应非 200 / 网络异常时一律返回 null，让调用方自行提示。
     * 调的是 {@code POST /profiles/minecraft}，只取返回数组里的首个玩家。
     *
     * @param name 玩家名
     * @return 无连字符 UUID；解析失败返回 null
     */
    public static String resolveUuid(String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/profiles/minecraft"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString("[\"" + name + "\"]"))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
            if (arr.isEmpty()) {
                return null;
            }
            return arr.get(0).getAsJsonObject().get("id").getAsString();
        } catch (Exception e) {
            NoMoreZombies.LOGGER.warn("Mojang UUID resolve failed for '{}'", name);
            return null;
        }
    }

    /**
     * 拉取某玩家的完整数据并解析成 Zombies 统计。
     * HTTP 状态码、Hypixel 的 {@code success=false}、缺 player / Arcade 节点，
     * 都被翻译成对应的 {@link ApiResult} 错误（见 {@code nomorezombies.query.*}）。
     *
     * @param uuidNoHyphen 无连字符 UUID
     * @param apiKey       Hypixel API Key
     */
    public static ApiResult fetchPlayer(String uuidNoHyphen, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hypixel.net/v2/player?uuid=" + uuidNoHyphen))
                    .header("API-Key", apiKey)
                    .header("User-Agent", USER_AGENT)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 403) {
                return ApiResult.error("nomorezombies.query.error.key.invalid");
            }
            if (code == 429) {
                return ApiResult.error("nomorezombies.query.error.rate.limited");
            }
            if (code != 200) {
                return ApiResult.error("nomorezombies.query.error.http", String.valueOf(code));
            }

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.has("success") && !root.get("success").getAsBoolean()) {
                String cause = root.has("cause") && !root.get("cause").isJsonNull()
                        ? root.get("cause").getAsString() : "unknown";
                return ApiResult.error("nomorezombies.query.error.cause", cause);
            }
            if (!root.has("player") || root.get("player").isJsonNull()) {
                return ApiResult.error("nomorezombies.query.status.notfound");
            }

            JsonObject player = root.getAsJsonObject("player");
            if (!player.has("stats") || player.get("stats").isJsonNull()
                    || !player.getAsJsonObject("stats").has("Arcade")) {
                return ApiResult.error("nomorezombies.query.status.nozombies");
            }

            return ApiResult.ok(ZombiesStatsParser.parse(player, uuidNoHyphen));
        } catch (IOException e) {
            return ApiResult.error("nomorezombies.query.error.network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResult.error("nomorezombies.query.error.network");
        } catch (Exception e) {
            NoMoreZombies.LOGGER.warn("Hypixel fetch failed for {}", uuidNoHyphen, e);
            return ApiResult.error("nomorezombies.query.error.network");
        }
    }

    /**
     * 从会话服务器取皮肤 URL——base64 的 textures 属性要先解出来、再翻一层 JSON 才能拿到。
     * 状态码非 200 / 缺 properties / 没有 SKIN 纹理都返回 null。
     *
     * @param uuidHyphen 带连字符 UUID
     * @return 皮肤 PNG 的 https URL；失败返回 null
     */
    public static String fetchSkinUrl(String uuidHyphen) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuidHyphen))
                    .header("User-Agent", USER_AGENT)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray props = root.getAsJsonArray("properties");
            if (props == null) {
                return null;
            }
            for (JsonElement e : props) {
                JsonObject o = e.getAsJsonObject();
                if (!"textures".equals(o.get("name").getAsString())) {
                    continue;
                }
                String b64 = o.get("value").getAsString();
                String json = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonObject tex = JsonParser.parseString(json).getAsJsonObject();
                JsonElement skinEl = tex.getAsJsonObject("textures").get("SKIN");
                if (skinEl != null && skinEl.getAsJsonObject().has("url")) {
                    return skinEl.getAsJsonObject().get("url").getAsString();
                }
            }
        } catch (Exception e) {
            NoMoreZombies.LOGGER.warn("Skin profile fetch failed for {}", uuidHyphen);
        }
        return null;
    }
}
