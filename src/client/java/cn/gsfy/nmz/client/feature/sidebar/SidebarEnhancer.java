package cn.gsfy.nmz.client.feature.sidebar;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.util.PlayerUtils;
import cn.gsfy.nmz.client.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 侧边栏行文本增强——把原生计分板里已被专属 HUD 覆盖的行收拾干净：
 * - 移除玩家行（队伍统计 HUD 已经显示了）
 * - 原生时间行按「队伍统计 HUD / 计时 HUD」二元对立，剥离重复的那一段
 * 波次/下一波改由 ShowSpawnTime 移植的波次 HUD 承担，不再往侧边栏里塞。
 */
public final class SidebarEnhancer {

    /** 原生游戏时间行（"Time: M:SS Kills: N" / "时间：M:SS Kills: N"，Kills 段恒为英文）。
     * 时间值兼容全/半角冒号（M:SS 或 H:MM:SS），免得误伤 "Time: 1200" 这种玩家行。 */
    private static final Pattern TIME_LINE = Pattern.compile("^(Time|时间|時間)\\s*[:：]\\s*\\d+[:：]\\d{2}");

    /** 时间行里的 Kills 段（"Kills: N"，大小写不敏感、数字允许千分位；去色码版本，供回退匹配）。 */
    private static final Pattern KILLS_LINE = Pattern.compile("(?i)Kills\\s*[:：]\\s*[\\d,]+");

    /** Kills 段起点（原生文本，单词里可能夹着色码，如 "§fKill§j§fs:"）。 */
    private static final Pattern KILLS_START = Pattern.compile("(?i)Kill(?:§[0-9a-zA-Z])*s\\s*[:：]");

    /** Kills 段整体（原生文本，保留色码）。 */
    private static final Pattern KILLS_LINE_RAW = Pattern.compile(
            "(?i)(?:§[0-9a-zA-Z])*Kill(?:§[0-9a-zA-Z])*s\\s*[:：]\\s*(?:§[0-9a-zA-Z])*[\\d,]+");

    /** InGameHudMixin 的 @ModifyArg 调进来，返回增强后的行文本（目前是空操作，占住入口）。 */
    public static Text enhanceLine(Text line) {
        return line;
    }

    /** 判断一行是不是原生游戏时间行：计时 HUD 开启时按二元对立逻辑隐藏它。 */
    public static boolean isTimeRow(String display) {
        String t = StringUtils.trim(display);
        return !t.isEmpty() && TIME_LINE.matcher(t).find();
    }

    /**
     * 原生时间行按「队伍统计 HUD / 计时 HUD」二元对立状态机决定这行怎么显示
     * （实现套路：先把原行移除，再按原格式覆写插回）：
     * <pre>
     *   队伍统计HUD   计时HUD     时间行显示
     *   关            关          保留原生整行「时间：M:SS Kills: N」
     *   关            开          「Kills: X」          （时长由计时 HUD 承担）
     *   开            关          「时间：M:SS」        （击杀由队伍统计 HUD 承担）
     *   开            开          整行移除               （两段均由对应 HUD 承担）
     * </pre>
     *
     * @return {@code null} = 整行移除；{@code ""} = 不覆写（原样保留原行）；其他 = 用该文本覆写该行
     */
    public static String timeRowOverride(String raw, boolean teamStatsOn, boolean gameTimeOn) {
        if (!isTimeRow(raw)) {
            return "";
        }
        if (teamStatsOn && gameTimeOn) {
            return null;
        }
        if (teamStatsOn) {
            return timeOnly(raw);
        }
        if (gameTimeOn) {
            return killsOnly(raw);
        }
        return "";
    }

    /** 时间行只留时间段（藏掉 Kills 段，色码保留）；非时间行原样返回。 */
    public static String timeOnly(String raw) {
        if (!isTimeRow(raw)) {
            return raw;
        }
        Matcher m = KILLS_START.matcher(raw);
        if (m.find()) {
            return trimCodes(raw.substring(0, m.start()));
        }
        // 回退：在剥离色码的文本上截断
        String stripped = StringUtils.trim(raw);
        int end = stripped.toLowerCase(Locale.ROOT).indexOf("kills");
        return end >= 0 ? stripped.substring(0, end).trim() : stripped;
    }

    /** 时间行只留 Kills 段（藏掉时间段，色码保留）；非时间行原样返回。 */
    public static String killsOnly(String raw) {
        if (!isTimeRow(raw)) {
            return raw;
        }
        Matcher m = KILLS_LINE_RAW.matcher(raw);
        if (m.find()) {
            return trimCodes(m.group());
        }
        // 回退：在剥离色码的文本上匹配
        Matcher m2 = KILLS_LINE.matcher(StringUtils.trim(raw));
        return m2.find() ? m2.group() : StringUtils.trim(raw);
    }

    /** 去掉行尾空白和悬空色码（行内颜色保留）。 */
    private static String trimCodes(String s) {
        return s.replaceAll("(?:§[0-9a-zA-Z]|\\s)+$", "");
    }

    /**
     * 从原生计分板过滤出要渲染的行（InGameHudMixin 的 @Redirect 和编辑器保存时的 DEBUG dump 共用，
     * 保证 dump 所见即渲染所得）：
     * - 队伍统计 HUD 开启 → 移除空行与玩家行
     * - 原生时间行（时间段+Kills 同行）按各 HUD 开关状态机剥离对应段（两个都开就整行移除）
     */
    public static Collection<ScoreboardEntry> filterSidebar(Scoreboard scoreboard, ScoreboardObjective objective,
                                                            Collection<ScoreboardEntry> entries) {
        boolean inZombies = PlayerUtils.isInZombies();
        boolean teamStatsOn = GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                && GlobalConfig.Hud.VISIBLE_TEAM_STATS.getBooleanValue();
        boolean gameTimeOn = GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                && GlobalConfig.Hud.VISIBLE_GAME_TIME.getBooleanValue();
        // 不在 Zombies，或两个 HUD 都关闭时：原样返回原生计分板
        if (!inZombies || (!teamStatsOn && !gameTimeOn)) {
            return entries;
        }
        Set<String> knownPlayers = knownPlayerNames();
        List<ScoreboardEntry> filtered = new ArrayList<>();
        for (ScoreboardEntry entry : entries) {
            Team team = scoreboard.getScoreHolderTeam(entry.name().getString());
            String prefix = team != null ? team.getPrefix().getString() : "";
            String suffix = team != null ? team.getSuffix().getString() : "";
            String raw = prefix + entry.name().getString() + suffix;
            String display = StringUtils.trim(raw);
            if (teamStatsOn) {
                // 空行（Hypixel 填充行）和玩家行都移除
                if (display.isEmpty()) {
                    continue;
                }
                if (isPlayerRow(display)) {
                    continue;
                }
                // 已知玩家名直接跳过（覆盖倒地/死亡/退出等任何状态）
                int colon = display.indexOf(':');
                int colonZh = display.indexOf('：');
                int idx = colon >= 0 ? colon : colonZh;
                if (idx > 0 && knownPlayers.contains(display.substring(0, idx).trim())) {
                    continue;
                }
            }
            if (isTimeRow(display)) {
                // 原生时间行（时间段 + Kills 段同行）：先移除该行，再按状态机覆写插入
                //（null=整行移除 / ""=不覆写保留原行 / 其他=覆写为对应段，保留原生色码）。
                String override = timeRowOverride(raw, teamStatsOn, gameTimeOn);
                if (override == null) {
                    continue; // 队伍 HUD 与计时 HUD 都开 → 两段均被覆盖，整行隐藏
                }
                if (!override.isEmpty()) {
                    filtered.add(replaceDisplay(entry, override)); // 覆写插入（保留 owner/分数/格式）
                    continue;
                }
            }
            filtered.add(entry);
        }
        return filtered;
    }

    /** 构造一个 value/格式相同、仅行文本换成 newDisplay 的新条目（时间行分段剥离用）。
     * 必须换成无 team 的 owner：Hypixel 把每行可见文本拆在 team 的 prefix/suffix 里，
     * 渲染时 InGameHud.method_55439 会用 team 把 name 包一遍（prefix+name+suffix）。若保留原 owner，
     * override 会被包回原生前后缀，「原生时间/击杀 + 覆写」就重复叠加了。
     * 换成不存在的 owner → getScoreHolderTeam 返回 null → decorateName 原样吐回 override。 */
    public static ScoreboardEntry replaceDisplay(ScoreboardEntry entry, String newDisplay) {
        return new ScoreboardEntry("nmz_replace_" + entry.owner(), entry.value(), Text.literal(newDisplay),
                entry.numberFormatOverride());
    }

    /** 侧边栏不再追加前缀文本，x 坐标补偿恒返 0（入口保留给 mixin 调）。 */
    public static int getAddedWidth(net.minecraft.client.font.TextRenderer tr) {
        return 0;
    }

    /** 判断一行是不是玩家行（用于从原生计分板移除）：
     * 1. 玩家名在已知世界玩家列表里；2. ASCII 用户名 + 数字/状态值（兜住已退出等）。 */
    public static boolean isPlayerRow(String display) {
        int colon = display.indexOf(':');
        int colonZh = display.indexOf('：');
        if (colon < 0 && colonZh < 0) {
            return false;
        }
        int idx = (colon >= 0 && (colonZh < 0 || colon < colonZh)) ? colon : colonZh;
        if (idx <= 0) {
            return false;
        }
        String namePart = display.substring(0, idx).trim();
        String valuePart = display.substring(idx + 1).trim();
        // 击杀行不算玩家行
        if (namePart.equals("Kills") || namePart.equals("Kill") || namePart.equals("击杀") || namePart.equals("杀敌")) {
            return false;
        }
        // 已知世界玩家 → 玩家行
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (PlayerEntity p : client.world.getPlayers()) {
                if (namePart.equals(p.getGameProfile().getName())) {
                    return true;
                }
            }
        }
        // 启发式：ASCII 用户名 + 数字/状态值
        if (!namePart.matches("[A-Za-z0-9_]{1,16}")) {
            return false;
        }
        if (valuePart.isEmpty()) {
            return false;
        }
        return isNumeric(valuePart) || isStatusWord(valuePart);
    }

    /** 已知玩家名集合（供过滤使用）：世界玩家 + 队伍统计名单。
     * 必须含已退出/已死亡的玩家——他们已无实体，光靠世界实体列表认不出其计分板行。 */
    public static Set<String> knownPlayerNames() {
        Set<String> names = new HashSet<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (PlayerEntity p : client.world.getPlayers()) {
                names.add(p.getGameProfile().getName());
            }
        }
        names.addAll(TeamStats.getPlayers().keySet());
        return names;
    }

    private static boolean isNumeric(String s) {
        for (char c : s.replace(",", "").toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStatusWord(String s) {
        return s.contains("退出") || s.contains("倒地") || s.contains("死亡")
                || s.toLowerCase().contains("left") || s.toLowerCase().contains("downed")
                || s.toLowerCase().contains("knocked") || s.toLowerCase().contains("dead")
                || s.toLowerCase().contains("died");
    }

    private SidebarEnhancer() {
    }
}