package cn.gsfy.nmz.client.data.model;

/**
 * Result of a Hypixel/Mojang request. {@code ok} carries the parsed
 * {@link ZombiesStats}; {@code error} carries a translatable failure reason.
 *
 * <p>{@code errorKey} is a translation key ({@code nomorezombies.query.*},
 * translated directly in the UI). {@code arg} is an optional format argument
 * (an HTTP status code, or the raw cause text); it is null when there is none.
 */
public record ApiResult(boolean ok, String errorKey, String arg, ZombiesStats stats) {

    /**
     * Success result: carries the stats object as-is. Both {@code errorKey}
     * and {@code arg} are {@code null}.
     *
     * @param stats stats object; may be {@code null}
     */
    public static ApiResult ok(ZombiesStats stats) {
        return new ApiResult(true, null, null, stats);
    }

    /** Failure result: {@code errorKey} is a translatable
     *  {@code nomorezombies.query.*} key, with no format argument. */
    public static ApiResult error(String errorKey) {
        return new ApiResult(false, errorKey, null, null);
    }

    /** Failure result: {@code errorKey} plus one format argument
     *  (a status code, or the raw cause text), shown as
     *  {@code translate(key, arg)}. */
    public static ApiResult error(String errorKey, String arg) {
        return new ApiResult(false, errorKey, arg, null);
    }
}