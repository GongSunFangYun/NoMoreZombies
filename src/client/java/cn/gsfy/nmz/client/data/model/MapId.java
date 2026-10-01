package cn.gsfy.nmz.client.data.model;

/**
 * Zombies map id. Each enum constant's {@code jsonKey} matches one key
 * under {@code maps} in the data JSON.
 *
 * <p>The enum stores only the JSON key, so hot-reloadable data never gets
 * pinned down in code.
 */
public enum MapId {
    NULL(""),
    ALIEN_ARCADIUM("alien_arcadium"),
    DEAD_END("dead_end"),
    BAD_BLOOD("bad_blood"),
    PRISON("prison");

    private final String jsonKey;

    MapId(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** Reverse-lookup by a data JSON {@code maps} key; an unrecognized key
     *  returns {@link #NULL}, treated as "no map". */
    public static MapId fromJsonKey(String key) {
        for (MapId id : values()) {
            if (id.jsonKey.equals(key)) {
                return id;
            }
        }
        return NULL;
    }
}