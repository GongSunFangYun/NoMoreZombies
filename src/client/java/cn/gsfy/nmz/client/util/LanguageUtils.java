package cn.gsfy.nmz.client.util;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.shared.ScoreboardManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语言 / 地图相关工具——中英双语文案匹配 + 地图检测两条线都收在这，
 * 计分板标题、聊天消息、地图识别的调用方各取所需。
 *
 * <p>文案判定统一基于 {@link StringUtils#trim} 后的纯文本（已去颜色符号与 emoji），
 * 中 / 英 / 繁简各写各的变体，不依赖玩家客户端的语言设置；地图检测则先信计分板
 * 明确写出的地图名（服务器直接下发，权威），再拿 (0,72,12) 方块特征兜底，
 * 避免误判带偏整个 HUD / 指挥 / 波次表。
 */
public final class LanguageUtils {

    // ---- 中英文案匹配 ----

    /** 判断侧边栏标题属不属于 Zombies（中英，大小写不敏感）：含 ZOMBIES / 僵尸末日 / 殭屍末日即命中。 */
    public static boolean isZombiesTitle(String title) {
        String t = StringUtils.trim(title);
        String upper = t.toUpperCase();
        return upper.contains("ZOMBIES") || t.contains("僵尸末日") || t.contains("殭屍末日");
    }

    /** 判断该行是不是「Zombies Left: N」的标签部分（中英，前缀匹配，冒号后的数字不关心）。
     * 计分板每 200ms 逐行扫描都会调它，属高频判定——所以命中不打日志，
     * 局内 / 离场翻转日志集中在 {@code PlayerUtils.isInZombies()}，防刷屏。 */
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

    /** 判断是不是回合开始标题（中英）：如 "Round 5" / "第1回合" / "第 1 回合"，波次计时靠它起表。 */
    public static boolean isRoundTitle(String title) {
        String t = StringUtils.trim(title);
        return t.startsWith("Round") || t.contains("回合");
    }

    /** 判断是不是游戏结束标题（You Win / Game Over 及其中文变体），回合计时到此结算。 */
    public static boolean isGameEnd(String title) {
        String t = StringUtils.trim(title);
        return t.contains("You Win") || t.contains("You win") || t.contains("Game Over")
                || t.contains("你赢") || t.contains("游戏结束") || t.contains("遊戲結束");
    }

    /** 局末总结标题里的时间戳（MM:SS）：兼容中英冒号与中英空格，先认时间再认关键词。 */
    private static final java.util.regex.Pattern SUMMARY_TIME =
            java.util.regex.Pattern.compile("\\d+\\s*[:：]\\s*\\d{2}");

    /**
     * 判断是不是局末总结标题（仅简体中文 + 英文）：同时带僵尸前缀、时间戳与回合号，
     * 如「僵尸末日 - 12:34（第15回合）」/「ZOMBIES - 12:34 (Round 15)」——普通回合
     * 标题（第N回合 / Round N）没有僵尸前缀和时间，天然不会误伤。
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

    /** 判断是不是胜利标题（中英）：用于把游戏结束拆成「全队覆灭」还是「通关」两种结局。 */
    public static boolean isWinTitle(String title) {
        String t = StringUtils.trim(title);
        String lower = t.toLowerCase();
        return lower.contains("you win") || lower.contains("victory")
                || t.contains("你赢") || t.contains("胜利") || t.contains("勝利");
    }

    /** 从回合标题里抠回合号：标题不是回合格式就返回 0，调用方拿 0 当「非回合标题」处理。 */
    public static int getRoundNumber(String title) {
        String t = StringUtils.trim(title);
        if (t.startsWith("Round") || t.contains("回合")) {
            return StringUtils.getNumberInString(t);
        }
        return 0;
    }

    // ---- 道具/提示文案匹配（中英） ----

    /** 判断是不是道具已激活消息（中英，大小写不敏感）：道具检测引擎接到聊天后用它确认激活。 */
    public static boolean isActivatedMessage(String message) {
        String m = StringUtils.trim(message);
        return m.toLowerCase().contains("enabled") || m.contains("activated") || m.contains("已激活")
                || m.contains("已啟動") || m.contains("启用") || m.contains("啟用");
    }

    /** 道具时长文案匹配：英文 "for 30s / 30 seconds" 或中文 "30秒"；群 1 收英文数字、群 2 收中文数字。 */
    private static final Pattern POWERUP_DURATION_PATTERN =
            Pattern.compile("for (\\d+)\\s*(?:s|seconds)|(\\d+)\\s*秒", Pattern.CASE_INSENSITIVE);

    /** 从道具激活消息里提取持续秒数（"for 30s" / "30秒"）：匹配不到返回 0，
     * 瞬时道具（如 Max Ammo）正好用 0 表达无时长。 */
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

    /** 金币获得消息（广义）：ChatFilter 隐藏判定用，击杀金币 + 街机硬币都算，命中就把刷屏噪音藏掉。 */
    public static final java.util.regex.Pattern GOLD_MESSAGE_PATTERN = java.util.regex.Pattern.compile(
            "\\+\\s*\\d+\\s*(金钱|金币|金幣|金錢|Gold|coin|硬币|硬幣)"
                    + "|(你获得了|You received|You got|You earned)\\s*\\d+\\s*(金钱|金币|金幣|金錢|Gold|coin|硬币|硬幣)");

    /** 判断是不是玩家被救治的复活消息（中英）：复活提示音 / 复活播报靠它识别。 */
    public static boolean isReviveMessage(String message) {
        String m = StringUtils.trim(message);
        String lower = m.toLowerCase();
        // 这里只做精确匹配：宽松一点就会把闲聊里的「复活 / revive」也当复活消息——
        // 我们踩过闲聊「秦始皇会复活」被误判的坑，宁可漏判也别错报
        return m.contains("救援了") || m.contains("救起了") || m.contains("复活了") || m.contains("復活了")
                || lower.contains("revived") || lower.contains("was revived");
    }

    // ---- 道具类型名（中英，含大写形式） ----

    /** 判断道具名是不是 Insta Kill（秒杀）：中文 / 英文 / 全大写各变体都认。 */
    public static boolean isInstaKill(String name) {
        return name.equals("INSTA KILL") || name.equals("Insta Kill") || name.equals("秒杀") || name.equals("一擊必殺")
                || name.equals("瞬间击杀") || name.equals("瞬間擊殺");
    }

    /** 判断道具名是不是 Max Ammo（满弹药）：中英各变体都认。 */
    public static boolean isMaxAmmo(String name) {
        return name.equals("MAX AMMO") || name.equals("Max Ammo") || name.equals("满弹药") || name.equals("滿彈藥")
                || name.equals("弹药满载") || name.equals("彈藥滿載");
    }

    /** 判断道具名是不是 Shopping Spree（购物狂潮）：中英各变体都认。 */
    public static boolean isShoppingSpree(String name) {
        // Hypixel 实际名：中文「购物狂潮」（激活消息「启用了N秒的购物狂潮」），
        // 英文盔甲架写 "SHOP SPREE"、全称 "Shopping Spree"，两种都要收进来。
        return name.equals("SHOPPING SPREE") || name.equals("Shopping Spree")
                || name.equals("SHOP SPREE") || name.equals("Shop Spree")
                || name.equals("购物狂潮") || name.equals("購物狂潮");
    }

    /** 判断道具名是不是 Carpenter（木匠）：中英两种叫法都认。 */
    public static boolean isCarpenter(String name) {
        return name.equals("CARPENTER") || name.equals("Carpenter") || name.equals("木匠");
    }

    /** 判断道具名是不是 Bonus Gold（额外金币）：中英两种叫法都认。 */
    public static boolean isBonusGold(String name) {
        return name.equals("BONUS GOLD") || name.equals("Bonus Gold") || name.equals("额外金币") || name.equals("額外金幣");
    }

    /** 判断道具名是不是 Double Gold（双倍金币）：中文「双倍金币 / 双倍金钱」等变体都认。 */
    public static boolean isDoubleGold(String name) {
        return name.equals("DOUBLE GOLD") || name.equals("Double Gold") || name.equals("双倍金币") || name.equals("雙倍金幣")
                || name.equals("双倍金钱") || name.equals("雙倍金錢");
    }

    // ---- 地图检测（计分板地图名 + 方块） ----

    /** 地图解析结果缓存：上次判定的地图，缓存有效期内直接返回，不重复采样。 */
    private static MapId cachedMap = MapId.NULL;
    /** 缓存是否有效：进图判定成功后置真，断线 / 新局时失效。 */
    private static boolean cacheValid = false;
    /** 空气二次确认：首次读到空气的时间戳（-1 = 未在确认中），
     * 用来把 THE_LAB 和「区块数据还没就绪」分开。 */
    private static long airFirstSeenMs = -1L;
    /** 空气持续 1500ms 才认定 THE_LAB：给区块数据加载留足时间，防进图瞬间误判。 */
    private static final long AIR_CONFIRM_DELAY_MS = 1500L;
    /** 诊断日志去重：记上次采样的方块 id（null = 尚未采样），方块变了才打印，防刷屏。 */
    private static String lastSampledBlock = null;
    /** 地图解析完成监听（如 AA HUD 可见性同步）：解析出具体地图（缓存无效→有效）时逐个触发。 */
    private static final List<Runnable> mapResolveListeners = new ArrayList<>();

    /** 注册地图解析完成回调：AA HUD 可见性同步等要用。回调里别同步调 getMap()，会重入。 */
    public static void onMapResolved(Runnable listener) {
        mapResolveListeners.add(listener);
    }

    /** 触发地图解析完成回调：只有解析出具体地图才通知，MapId.NULL 不惊动监听者。 */
    private static void notifyMapResolved(MapId map) {
        if (map == MapId.NULL) {
            return;
        }
        for (Runnable r : mapResolveListeners) {
            try {
                r.run();
            } catch (Exception e) {
                NoMoreZombies.LOGGER.warn("[地图] 地图解析回调异常", e);
            }
        }
    }

    // ---- 计分板地图名次级检测（服务器直接下发，权威） ----
    // 检测顺序即优先级：先精确长名后短名——"Laboratory" 必须先于 "Lab"，
    // 否则子串匹配会把实验室误认成别的地图。

    /** 地图名表的检出顺序：与 {@link #SCOREBOARD_MAP_NAMES} 按序一一对应。 */
    private static final MapId[] SCOREBOARD_MAP_ORDER = {
            MapId.ALIEN_ARCADIUM, MapId.THE_LAB, MapId.DEAD_END, MapId.BAD_BLOOD, MapId.PRISON, MapId.THE_LAB
    };
    /** 与 {@link #SCOREBOARD_MAP_ORDER} 按序对应的地图名表：每行是一个地图的中英名变体。 */
    private static final String[][] SCOREBOARD_MAP_NAMES = {
            {"Alien Arcadium", "外星游乐园", "外星遊樂園"},
            {"Laboratory", "实验室", "實驗室"},
            {"Dead End", "穷途末路", "窮途末路"},
            {"Bad Blood", "坏血之宫", "壞血之宮"},
            {"Prison", "监狱", "監獄"},
            {"Lab"}
    };

    /** 扫描侧边栏标题 + 内容行找地图名：先逐行扫内容再扫标题，命中已知名就返回对应地图。 */
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

    /** 单行匹配：遍历地图名表，任一变体命中即返回对应地图；全不中就返回 NULL。 */
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

    /** 识别当前地图：先试计分板明确写出的地图名（权威），没有再读 (0,72,12) 方块特征兜底；
     * 结果缓存，断线 / 新局后失效重新检测。 */
    public static MapId getMap() {
        if (cacheValid) {
            return cachedMap;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return MapId.NULL;
        }

        // ① 方块采样（诊断）：chunk 加载状态 + 方块 id + tag 命中都打日志，供跟游戏实际行为对照。
        //    方块 id 变了才记一次，防刷屏；计分板识图成功也打印一次，方便核对 AA 方块误判根因。
        String blockName = null;
        if (client.world.isChunkLoaded(0, 0)) {
            BlockState state = client.world.getBlockState(new BlockPos(0, 72, 12));
            Block block = state.getBlock();
            blockName = Registries.BLOCK.getId(block).toString();
            if (!blockName.equals(lastSampledBlock)) {
                lastSampledBlock = blockName;
                NoMoreZombies.LOGGER.info("[地图] 采样 (0,72,12) 方块 {}｜air={}｜woolCarpets={}｜wool={}｜terracotta={}｜stoneBricks={}",
                        blockName, state.isAir(), state.isIn(BlockTags.WOOL_CARPETS),
                        state.isIn(BlockTags.WOOL), state.isIn(BlockTags.TERRACOTTA),
                        block == Blocks.STONE_BRICKS);
            }
        } else {
            if (!"__CHUNK_NOT_LOADED__".equals(lastSampledBlock)) {
                lastSampledBlock = "__CHUNK_NOT_LOADED__";
                NoMoreZombies.LOGGER.info("[地图] 区块 (0,0) 未加载，等待中");
            }
        }

        // ② 计分板地图名次级检测：侧边栏明确写出地图名时优先采用（服务器直接下发，权威），
        //    绕开方块判定的歧义——不然 AA 方块被误认成 DE，HUD / 指挥 / 波次 / 道具全走错表。
        MapId sbMap = detectMapByScoreboard();
        if (sbMap != MapId.NULL) {
            cachedMap = sbMap;
            cacheValid = true;
            airFirstSeenMs = -1L;
            NoMoreZombies.LOGGER.info("[地图] 计分板识别：{}（方块 {}{}）", sbMap,
                    blockName != null ? blockName : "未加载", "");
            notifyMapResolved(sbMap);
            return cachedMap;
        }

        // ③ 方块判定：计分板没给出地图名时的兜底。
        // 坑在这里：chunk 0,0 未加载时 getBlockState 返回空气，会被误判成 THE_LAB 并整局缓存。
        // 所以未加载只返回 NULL 不缓存；已加载但读到空气也要二次确认——首次进图区块数据可能
        // 还没就绪，空气 ≠ THE_LAB，要连续确认满 1500ms 才算数，免得一进图就错判整局报废。
        if (!client.world.isChunkLoaded(0, 0)) {
            airFirstSeenMs = -1L;
            return MapId.NULL;
        }
        BlockState state = client.world.getBlockState(new BlockPos(0, 72, 12));
        Block block = state.getBlock();
        if (state.isAir()) {
            long now = System.currentTimeMillis();
            if (airFirstSeenMs < 0) {
                airFirstSeenMs = now;
                return MapId.NULL;
            }
            if (now - airFirstSeenMs < AIR_CONFIRM_DELAY_MS) {
                return MapId.NULL;
            }
            // 空气撑满确认窗口还没变：认定为 THE_LAB，缓存并通知监听者
            airFirstSeenMs = -1L;
            cachedMap = MapId.THE_LAB;
            cacheValid = true;
            NoMoreZombies.LOGGER.info("[地图] 方块判定：空气持续确认 → {}（计分板无地图名）", MapId.THE_LAB);
            notifyMapResolved(cachedMap);
            return cachedMap;
        }
        // 非空气：方块特征明确（地毯 / 石砖 / 羊毛 / 陶瓦），当场识别并缓存
        airFirstSeenMs = -1L;
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
        NoMoreZombies.LOGGER.info("[地图] 方块判定：{}（计分板无地图名）", map);
        notifyMapResolved(map);
        return map;
    }

    /** 让地图缓存失效：断线 / 新局时调用，下次 getMap() 从头检测，方块采样也一并重置。 */
    public static void invalidateMapCache() {
        if (cacheValid || cachedMap != MapId.NULL || airFirstSeenMs >= 0) {
            NoMoreZombies.LOGGER.info("[地图] 缓存失效（旧值 {}）", cachedMap);
        }
        cachedMap = MapId.NULL;
        cacheValid = false;
        airFirstSeenMs = -1L;
        lastSampledBlock = null; // 新局重新采样：旧的诊断日志去重标记作废
    }

    private LanguageUtils() {
    }
}
