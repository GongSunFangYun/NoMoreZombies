package cn.gsfy.nmz.client.utils;

import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.shared.game.ScoreboardManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language/map utilities - both the bilingual (Chinese/English) text matching
 * line and the map detection line live here; callers pick what they need for
 * scoreboard titles, chat messages and map identification.
 *
 * <p>All text checks run on the plain text after {@link StringUtils#trim}
 * (color codes and emoji already stripped); Chinese / English / traditional
 * variants are each spelled out, independent of the player's client language.
 * Map detection trusts the map name written on the scoreboard first
 * (delivered by the server, authoritative), then falls back to the block
 * feature at (0,72,12) - a misjudgment here would drag the whole
 * HUD/command/wave tables off course.
 *
 * <p>Map cache lifecycle: a successful detection is cached until the round
 * ends; the invalidation points map one-to-one to the map-ownership reset
 * points (disconnect / new round / game end / leaving, see
 * {@link #invalidateMapCache()}, which also invalidates the difficulty
 * cache). An unrecognized result returns {@link MapId#NULL} and is
 * <b>not cached</b>; the next call retries. The cached value is consumed at
 * high frequency by the scoreboard poll (every 5 ticks), so the block
 * fallback null-checks world/coordinates - no false report when far from the
 * map or the chunk is not loaded.
 */
public final class LanguageUtils {

    // ----Bilingual (Chinese/English) text matching----

    /** Whether the sidebar title belongs to Zombies (Chinese/English, case
     * insensitive): hits on ZOMBIES/僵尸末日/殭屍末日 */
    public static boolean isZombiesTitle(String title) {
        String t = StringUtils.trim(title);
        String upper = t.toUpperCase();
        return upper.contains("ZOMBIES") || t.contains("僵尸末日") || t.contains("殭屍末日");
    }

    /** Whether the line is the label part of "Zombies Left:N" (Chinese/English,
     * prefix match; the number after the colon is irrelevant).
     * Called for every line of the scoreboard scan each 5 client ticks
     * (250 ms) - high-frequency, so it only decides and never logs */
    public static boolean isZombiesLeft(String line) {
        String s = StringUtils.trim(line);
        if (s.contains(":")) {
            s = s.split(":")[0];
        } else if (s.contains("：")) {
            s = s.split("：")[0];
        }
        s = StringUtils.trim(s);
        return s.startsWith("Zombies Left") || s.startsWith("剩余僵尸") || s.startsWith("剩下殭屍數")
                || s.startsWith("剩餘殭屍") || s.startsWith("Zombies Left:");
    }

    /** Whether this is a round-start title (Chinese/English): e.g. "Round 5"/
     * "Round5"/"第1回合"; wave timing uses it to start the table */
    public static boolean isRoundTitle(String title) {
        String t = StringUtils.trim(title);
        return t.startsWith("Round") || t.contains("回合");
    }

    /** Whether this is a game-end title (You Win / Game Over and Chinese
     * variants); round timing settles here */
    public static boolean isGameEnd(String title) {
        String t = StringUtils.trim(title);
        return t.contains("You Win") || t.contains("You win") || t.contains("Game Over")
                || t.contains("你赢") || t.contains("游戏结束") || t.contains("遊戲結束");
    }

    /** Timestamp (MM:SS) inside end-of-round summary titles: accepts a Chinese
     * or English colon and spaces; time first, keywords second */
    private static final java.util.regex.Pattern SUMMARY_TIME =
            java.util.regex.Pattern.compile("\\d+\\s*[:：]\\s*\\d{2}");

    /**
     * Whether this is an end-of-round summary title (Chinese/English, with
     * traditional variants): carries a Zombies prefix, a timestamp and a
     * round number at once, e.g. "僵尸末日-12:34(第15回合)"/
     * "ZOMBIES-12:34(Round 15)". Ordinary round titles (第N回合 / Round N)
     * lack the Zombies prefix and the time, so they can never be caught by
     * mistake.
     */
    public static boolean isGameEndSummary(String title) {
        String t = StringUtils.trim(title);
        if (t.isEmpty()) {
            return false;
        }
        if (!SUMMARY_TIME.matcher(t).find()) {
            return false;
        }
        String upper = t.toUpperCase();
        return (t.contains("僵尸末日") || t.contains("殭屍末日") || upper.contains("ZOMBIES"))
                && (t.contains("回合") || upper.contains("ROUND"));
    }

    /** Whether this is a win title (Chinese/English): splits game end into
     * "team wiped" vs "run cleared" */
    public static boolean isWinTitle(String title) {
        String t = StringUtils.trim(title);
        String lower = t.toLowerCase();
        return lower.contains("you win") || lower.contains("victory")
                || t.contains("你赢") || t.contains("胜利") || t.contains("勝利");
    }

    /** Pulls the round number from a round title: returns 0 for a non-round
     * title, callers treat 0 as "not a round title" */
    public static int getRoundNumber(String title) {
        String t = StringUtils.trim(title);
        if (t.startsWith("Round") || t.contains("回合")) {
            return StringUtils.getNumberInString(t);
        }
        return 0;
    }

    // ----Powerup/hint text matching (Chinese/English)----

    /** Whether this is a powerup-activated message (Chinese/English, case
     * insensitive): the powerup engine confirms activation with it on chat */
    public static boolean isActivatedMessage(String message) {
        String m = StringUtils.trim(message);
        return m.toLowerCase().contains("enabled") || m.contains("activated") || m.contains("已激活")
                || m.contains("已啟動") || m.contains("启用") || m.contains("啟用");
    }

    /** Powerup duration text: English "for 30s/30seconds" or Chinese "30秒";
     * group 1 takes the English number, group 2 the Chinese one */
    private static final Pattern POWERUP_DURATION_PATTERN =
            Pattern.compile("for (\\d+)\\s*(?:s|seconds)|(\\d+)\\s*秒", Pattern.CASE_INSENSITIVE);

    /** Extracts the duration in seconds from a powerup activation message
     * ("for 30s"/"30秒"): 0 when nothing matches - instant powerups like
     * Max Ammo use 0 to mean no duration */
    public static int extractPowerupDuration(String message) {
        String m = StringUtils.trim(message);
        Matcher matcher = POWERUP_DURATION_PATTERN.matcher(m);
        if (matcher.find()) {
            String en = matcher.group(1);
            String zh = matcher.group(2);
            if (en != null) {
                return Integer.parseInt(en);
            }
            if (zh != null) {
                return Integer.parseInt(zh);
            }
        }
        return 0;
    }

    /** Gold-gain messages (broad): used by ChatFilter to hide - kill gold and
     * arcade coins both count; a hit hides the spam */
    public static final java.util.regex.Pattern GOLD_MESSAGE_PATTERN = java.util.regex.Pattern.compile(
            "\\+\\s*\\d+\\s*(金钱|金币|金幣|金錢|Gold|coin|硬币|硬幣)"
                    + "|(你获得了|You received|You got|You earned)\\s*\\d+\\s*(金钱|金币|金幣|金錢|Gold|coin|硬币|硬幣)");

    /** Whether this is a revive message for a rescued player
     * (Chinese/English): revive sound and revive announcements identify on it */
    public static boolean isReviveMessage(String message) {
        String m = StringUtils.trim(message);
        String lower = m.toLowerCase();
        // Exact matches only: any looser and casual chat containing
        // "复活/revive" counts as a revive - casual chat like "秦始皇会复活"
        // has already caused a false positive here; a missed revive beats a
        // wrong one
        return m.contains("救援了") || m.contains("救起了") || m.contains("复活了") || m.contains("復活了")
                || lower.contains("revived") || lower.contains("was revived");
    }

    // ----Powerup type names (Chinese/English, uppercase forms included)----

    /**
     * Whether the powerup name is Insta Kill - exact matches only across
     * Chinese/English/all-caps; input is not trimmed and case is not ignored,
     * and {@code null} throws {@link NullPointerException}.
     */
    public static boolean isInstaKill(String name) {
        return name.equals("INSTA KILL") || name.equals("Insta Kill") || name.equals("秒杀") || name.equals("一擊必殺")
                || name.equals("瞬间击杀") || name.equals("瞬間擊殺");
    }

    /** Whether the powerup name is Max Ammo: all Chinese/English variants
     * count */
    public static boolean isMaxAmmo(String name) {
        return name.equals("MAX AMMO") || name.equals("Max Ammo") || name.equals("满弹药") || name.equals("滿彈藥")
                || name.equals("弹药满载") || name.equals("彈藥滿載");
    }

    /** Whether the powerup name is Shopping Spree: all Chinese/English
     * variants count */
    public static boolean isShoppingSpree(String name) {
        // Actual Hypixel naming: Chinese "购物狂潮" (activation message
        // "启用了N秒的购物狂潮"), the English armor stand says "SHOP SPREE"
        // while the full name is "Shopping Spree" - both forms must be accepted
        return name.equals("SHOPPING SPREE") || name.equals("Shopping Spree")
                || name.equals("SHOP SPREE") || name.equals("Shop Spree")
                || name.equals("购物狂潮") || name.equals("購物狂潮");
    }

    /** Whether the powerup name is Carpenter: both the Chinese and English
     * names count */
    public static boolean isCarpenter(String name) {
        return name.equals("CARPENTER") || name.equals("Carpenter") || name.equals("木匠");
    }

    /** Whether the powerup name is Bonus Gold: both the Chinese and English
     * names count */
    public static boolean isBonusGold(String name) {
        return name.equals("BONUS GOLD") || name.equals("Bonus Gold") || name.equals("额外金币") || name.equals("額外金幣");
    }

    /** Whether the powerup name is Double Gold: Chinese variants like
     * "双倍金币"/"双倍金钱" all count */
    public static boolean isDoubleGold(String name) {
        return name.equals("DOUBLE GOLD") || name.equals("Double Gold") || name.equals("双倍金币") || name.equals("雙倍金幣")
                || name.equals("双倍金钱") || name.equals("雙倍金錢");
    }

    // ----Lucky chest reward names (Chinese/English) -> canonical keys----

    /**
     * Bilingual map of lucky chest reward names: {@code lowercase English
     * name} / {@code Chinese name} -> canonical key.
     *
     * <p><b>Must be maintained explicitly - no regex or character
     * heuristics.</b> The Chinese and English reward names share no reliable
     * form (Zombie Zapper vs "电击枪" have nothing in common), so any
     * inference rule would only mis-map silently.
     *
     * <p>The canonical keys feed the {@code RollStats.Item} enum (which also
     * holds the icon and the language key); the same string is shared in
     * three places, so a table change updates all three together.
     *
     * <p>Traditional-Chinese variants are <b>mechanical conversions</b>, not
     * yet verified in game: a wrong guess can only ever miss (canonical keys
     * are distinct, so there is no path where a traditional name maps to the
     * wrong item); fix after confirming on a live server.
     */
    private static final java.util.Map<String, String> ROLL_ITEMS = new java.util.LinkedHashMap<>();

    static {
        // Canonical keys correspond one-to-one with RollStats.Item.key (a
        // wrong key gets a WARN logged and the entry dropped by RollStats)
        putRollItem("zombie_zapper", "Zombie Zapper", "电击枪", "電擊槍");
        putRollItem("gold_digger", "Gold Digger", "黄金矿工", "黃金礦工");
        putRollItem("rainbow_rifle", "Rainbow Rifle", "彩虹步枪", "彩虹步槍");
        putRollItem("double_barrel", "Double Barrel Shotgun", "双管霰弹枪", "雙管霰彈槍");
        putRollItem("elder_gun", "Elder Gun", "接骨木枪", "接骨木槍");
        putRollItem("zombie_soaker", "Zombie Soaker", "水枪", "水槍");
        putRollItem("blow_dart", "Blow Dart", "吹箭筒");
        putRollItem("flamethrower", "Flamethrower", "火焰发射器", "火焰發射器");
        putRollItem("the_puncher", "The Puncher", "击退斧", "擊退斧");
        putRollItem("lightning_rod_skill", "Lightning Rod Skill", "电击棒技能", "電擊棒技能");
        putRollItem("heal_skill", "Heal Skill", "恢复技能", "恢復技能");
    }

    /**
     * Registers one reward key with its per-language spellings; English names
     * are stored lowercased, Chinese names as-is (Chinese is unaffected by
     * {@code toLowerCase}), so one query hits both spellings.
     *
     * @param key canonical key
     * @param english English reward name(s)
     * @param names Chinese reward name(s)
     */
    private static void putRollItem(String key, String english, String... names) {
        ROLL_ITEMS.put(english.toLowerCase(java.util.Locale.ROOT), key);
        for (String name : names) {
            ROLL_ITEMS.put(name, key);
        }
    }

    /**
     * Maps a lucky chest reward name to its canonical key - parsing matches
     * the Chinese/English originals, regardless of client language.
     *
     * @param itemName reward name captured from chat (may be {@code null})
     * @return canonical key; {@code null} when not in the map - the caller
     *  decides what to do
     */
    public static String rollItemKey(String itemName) {
        String name = StringUtils.trim(itemName);
        if (name.isEmpty()) {
            return null;
        }
        String key = ROLL_ITEMS.get(name);
        return key != null ? key : ROLL_ITEMS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    // ----Map detection (scoreboard map name + blocks)----

    /** Map detection cache: the last judged map, returned directly while
     * valid - no repeated sampling */
    private static MapId cachedMap = MapId.NULL;
    /** Cache validity: set true on a successful in-map detection, invalidated
     * on disconnect / new round */
    private static boolean cacheValid = false;
    // ----Scoreboard map-name detection (delivered by the server, authoritative)----

    /**
     * Detection order of the map name table - pairs one-to-one by index with
     * {@link #SCOREBOARD_MAP_NAMES}.
     *
     * <p>The order only matters when one line contains two map names (the
     * four maps' names are not substrings of each other, so in practice no
     * conflict); the real display order lives in
     * {@code ZombiesStatsParser.MAP_ORDER}. Same order is kept here so
     * readers do not assume two independent orderings.
     */
    private static final MapId[] SCOREBOARD_MAP_ORDER = {
            MapId.DEAD_END, MapId.BAD_BLOOD, MapId.ALIEN_ARCADIUM, MapId.PRISON
    };
    /** Map name table indexed in step with {@link #SCOREBOARD_MAP_ORDER}:
     * each row is one map's Chinese/English name variants */
    private static final String[][] SCOREBOARD_MAP_NAMES = {
            {"Dead End", "穷途末路", "窮途末路"},
            {"Bad Blood", "坏血之宫", "壞血之宮"},
            {"Alien Arcadium", "外星游乐园", "外星遊樂園"},
            {"Prison", "监狱", "監獄"}
    };

    /** Scans sidebar title + content rows for a map name: content rows first,
     * title last; a known name returns its map */
    private static MapId detectMapByScoreboard() {
        ScoreboardManager sb = ScoreboardManager.get();
        if (sb == null) {
            return MapId.NULL;
        }
        for (int row = 1; row <= sb.getSize(); row++) {
            MapId m = matchMapName(sb.getContent(row));
            if (m != MapId.NULL) {
                return m;
            }
        }
        return matchMapName(sb.getTitle());
    }

    /** Single-line match: walks the name table, any variant hit returns its
     * map; none hit returns NULL */
    private static MapId matchMapName(String line) {
        if (line == null || line.isEmpty()) {
            return MapId.NULL;
        }
        for (int i = 0; i < SCOREBOARD_MAP_NAMES.length; i++) {
            for (String name : SCOREBOARD_MAP_NAMES[i]) {
                if (line.contains(name)) {
                    return SCOREBOARD_MAP_ORDER[i];
                }
            }
        }
        return MapId.NULL;
    }

    /** Detects the current map: scoreboard name first (authoritative), then
     * the (0,72,12) block feature as fallback; the result is cached and
     * invalidated on disconnect/new round. HUD, wave tables, powerup patterns
     * and so on pick their data set by map. Block branches check carpet
     * before wool: the carpet tag is a subset of the wool tag, so the other
     * order would swallow AA's carpet into DEAD_END */
    public static MapId getMap() {
        if (cacheValid) {
            return cachedMap;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return MapId.NULL;
        }

        // 1) Scoreboard map-name detection: when the sidebar names the map
        //    outright, take it (server-delivered, authoritative) and skip the
        //    block check's ambiguity - otherwise AA's blocks get misread as
        //    DE and the HUD/command/wave tables/powerup patterns all pick the
        //    wrong set
        MapId sbMap = detectMapByScoreboard();
        if (sbMap != MapId.NULL) {
            cachedMap = sbMap;
            cacheValid = true;
            return cachedMap;
        }

        // 2) Block detection: fallback when the scoreboard names no map.
        // With chunk 0,0 unloaded getBlockState returns air, which proves
        // nothing - unloaded or air both return NULL without caching and
        // retry once the chunk is ready; feature identification runs only on
        // non-air
        if (!client.world.isChunkLoaded(0, 0)) {
            return MapId.NULL;
        }
        BlockState state = client.world.getBlockState(new BlockPos(0, 72, 12));
        Block block = state.getBlock();
        if (state.isAir()) {
            return MapId.NULL;
        }
        // Non-air: a definite block feature (carpet/stone bricks/wool/
        // terracotta) - identify and cache on the spot
        MapId map;
        if (state.isIn(BlockTags.WOOL_CARPETS)) {
            map = MapId.ALIEN_ARCADIUM;
        } else if (block == Blocks.STONE_BRICKS) {
            map = MapId.BAD_BLOOD;
        } else if (state.isIn(BlockTags.WOOL)) {
            map = MapId.DEAD_END;
        } else if (state.isIn(BlockTags.TERRACOTTA)) {
            map = MapId.PRISON;
        } else {
            map = MapId.NULL;
        }
        cachedMap = map;
        cacheValid = true;
        return map;
    }

    /** Invalidates the map cache: called on disconnect/new round so the next
     * getMap() detects from scratch. The difficulty cache shares the map's
     * source and lifecycle, so it is invalidated together
     * ({@link DifficultyUtils#invalidateCache()}) - callers need not
     * invalidate each one, and missing one would carry the last round's
     * difficulty into the new one */
    public static void invalidateMapCache() {
        cachedMap = MapId.NULL;
        cacheValid = false;
        DifficultyUtils.invalidateCache();
    }

    private LanguageUtils() {
    }
}
