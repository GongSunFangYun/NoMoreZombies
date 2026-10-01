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
 * Request layer for player data lookup—speaks only to Mojang and Hypixel
 * over HTTP: Mojang for name→UUID/skin, Hypixel for UUID→player Zombies
 * stats. Every method blocks; callers hand them to a background thread.
 *
 * <p>One shared {@link HttpClient} singleton; connect and request timeouts
 * are both 5 seconds—fail fast rather than hang, since one in-game query
 * waits on up to four players and 5 seconds is the experience ceiling, not
 * a throughput tuning. The JDK client buffers each response body whole; no
 * byte cap is set here. Errors never throw—they map to {@link ApiResult}
 * for the UI to translate (keys under {@code nomorezombies.query.*}). Name
 * resolution and skin caching belong to the caller
 * (QueryDataManager/AvatarUtils); this layer holds no cache.
 */
public final class HypixelApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "NoMoreZombies/1.0";

    /** Process-wide HttpClient singleton—5-second connect timeout, and
     *  every request reuses it. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private HypixelApiClient() {
    }

    /**
     * Resolves a player name to a UUID (no hyphens, lowercase) through the
     * Mojang API. Missing name, rate limit, non-200, and network or JSON
     * error all return null, letting the caller decide the message. Blocks
     * the calling thread. The POST body is a single-element JSON array;
     * {@code name} is spliced into the JSON without escaping, and only the
     * first player in the response array is used.
     *
     * @param name player name; the caller must guarantee no JSON escaping is
     *   needed
     * @return a hyphen-less UUID, or {@code null} on failure
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
     * Fetches one player's full data and parses it into Zombies stats. The
     * UUID goes in the {@code uuid} query, the API key in the {@code API-Key}
     * header. Blocks the calling thread until a response or the 5-second
     * timeout. 403 maps to an invalid key, 429 to rate limiting, any other
     * non-200 to an HTTP error; {@code success=false}, a missing
     * {@code player}, and a missing {@code Arcade} node each map to a
     * {@code nomorezombies.query.*} key. I/O, parse, and interrupt all map
     * to a network error; on interrupt the thread's interrupt flag is
     * restored.
     *
     * @param uuidNoHyphen hyphen-less UUID; not URL-encoded, so the caller
     *   must pass a valid value
     * @param apiKey Hypixel API key, written to the {@code API-Key} header
     *   as-is
     * @return a success or error result, never {@code null}
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
     * Fetches the skin URL from the session server—the base64 {@code textures}
     * property has to be decoded first, then its inner JSON parsed. A
     * non-200, missing {@code properties}, or no {@code SKIN} texture all
     * return null.
     *
     * @param uuidHyphen hyphenated UUID
     * @return the skin PNG's https URL; null on failure
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