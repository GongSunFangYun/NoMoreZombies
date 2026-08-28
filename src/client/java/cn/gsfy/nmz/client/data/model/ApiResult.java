package cn.gsfy.nmz.client.data.model;

/**
 * Hypixel/Mojang 请求结果——ok 携带解析好的 {@link ZombiesStats}，error 携带可翻译的失败原因。
 *
 * <p>{@code errorKey} 是翻译 key（{@code nomorezombies.query.*}，界面里直接 translate）；
 * {@code arg} 是可选格式化参数（HTTP 状态码 / cause 原文），没有则为 null。
 */
public record ApiResult(boolean ok, String errorKey, String arg, ZombiesStats stats) {

    public static ApiResult ok(ZombiesStats stats) {
        return new ApiResult(true, null, null, stats);
    }

    public static ApiResult error(String errorKey) {
        return new ApiResult(false, errorKey, null, null);
    }

    public static ApiResult error(String errorKey, String arg) {
        return new ApiResult(false, errorKey, arg, null);
    }
}
