package cn.gsfy.nmz.client.data.model;

/**
 * Zombies 地图标识——每个枚举常量的 jsonKey 与数据 JSON 里的 maps 键一一对应。
 *
 * <p>回合数上限不在这存（没有消费者）：波次表能走到第几回合全由行长决定，
 * 缺行靠 DataManager 降级说明里的守卫静默跳过。
 */
public enum MapId {
    NULL(""),
    ALIEN_ARCADIUM("alien_arcadium"),
    DEAD_END("dead_end"),
    BAD_BLOOD("bad_blood"),
    THE_LAB("the_lab"),
    PRISON("prison");

    private final String jsonKey;

    MapId(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** 由数据 JSON 的 maps 键反查枚举；不认识就返回 {@link #NULL} 当「无地图」处理。 */
    public static MapId fromJsonKey(String key) {
        for (MapId id : values()) {
            if (id.jsonKey.equals(key)) {
                return id;
            }
        }
        return NULL;
    }
}
