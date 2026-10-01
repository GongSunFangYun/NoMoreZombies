package cn.gsfy.nmz.client.data.model;

/**
 * Zombies map difficulty id. Each enum constant's {@code jsonKey} matches
 * one difficulty key under a map in {@code boss_rounds.json}.
 *
 * <p>{@link #NULL} is the "not identified" sentinel: the recognition layer
 * reports what it found and never substitutes a tier for an unidentified
 * result. The boss-round fallback—treat as normal when unidentified—lives
 * in the query layer; see {@code GameData#getBossRounds}.
 */
public enum DifficultyId {
    NULL(""),
    NORMAL("normal"),
    HARD("hard"),
    RIP("rip");

    private final String jsonKey;

    DifficultyId(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** Reverse-lookup by a JSON difficulty key; an unrecognized key returns
     *  {@link #NULL}, treated as "difficulty unknown". */
    public static DifficultyId fromJsonKey(String key) {
        for (DifficultyId id : values()) {
            if (id.jsonKey.equals(key)) {
                return id;
            }
        }
        return NULL;
    }
}