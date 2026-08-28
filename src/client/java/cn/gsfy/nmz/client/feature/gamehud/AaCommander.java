package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.AaRoundDetail;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.DelayedTaskScheduler;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外星游乐园自动指挥（AA auto commander）——替你把 AA 局的回合情报盯牢：
 * 该轮刷不刷巨人 / 长者、危险到几级、站哪个点位，一眼扫 HUD 就有答案。
 * HUD 仅在 AA 局内渲染，门控是三层叠加：实用 HUD 总开关 + 该 HUD 开关 + 地图识别为 AA。
 *
 * <p>聊天输出与 HUD 并列第二件事：AA 局每回合开始，按全局配置页的输出方式与信息模板自动播报
 * 指挥建议，模板里 {round}/{point}/{boss}/{difficulty} 四个变量按回合数据替换，
 * 模板为空或含未知变量时回退默认模板、不报错；由 QoL.AA_COMMAND_ENABLED 门控。
 * 回合数据读 assets/nomorezombies/data/aa_round_details.json，DataManager 热重载，
 * 改数据表不用重启客户端。
 */
public class AaCommander extends TotalHUDRenderer {

    /** 合法模板变量白名单；模板为空或含未知 {@code {xxx}} 一律判非法并回退默认模板（不报错）。 */
    private static final Set<String> KNOWN_VARS = Set.of("round", "point", "boss", "difficulty");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** 回合危险等级在 HUD 上的配色：绿 / 深绿 / 黄 / 红 / 紫（下标 = dangerLevel - 1）。 */
    private static final int[] DIFFICULTY_COLORS = {0x55FF55, 0x00AA00, 0xFFFF55, 0xFF5555, 0xAA55FF};
    /** 回合危险等级罗马数字表（下标 = dangerLevel - 1），HUD 与聊天共用同一份。 */
    private static final String[] DIFFICULTY_ROMAN = {"I", "II", "III", "IV", "V"};
    /** 回合危险等级在聊天里的配色（与 DIFFICULTY_COLORS 逐位对应：绿/深绿/黄/红/紫）。 */
    private static final Formatting[] DIFFICULTY_FORMATTING = {
            Formatting.GREEN, Formatting.DARK_GREEN, Formatting.YELLOW, Formatting.RED, Formatting.LIGHT_PURPLE
    };
    private static final int LABEL_COLOR = 0xFFAA00;
    private static final int VALUE_COLOR = 0xFFFFFF;
    /** 推荐点位 "#N" 标记的颜色：蓝色（§9），让点位编号在名单里一眼可辨。 */
    private static final int POINT_COLOR = 0x5555FF;
    private static final int YES_COLOR = 0xFF5555;
    private static final int NO_COLOR = 0x55FF55;
    private static final int UNKNOWN_COLOR = 0xAAAAAA;

    // ==================== HUD ====================

    /** 门控：仅在 AA 局内渲染——地图未识别（区块未加载）时 getMap 不是 AA，自然被挡在门外。 */
    @Override
    protected boolean shouldRenderHud() {
        return PlayerUtils.isInZombies() && LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM;
    }

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        // AA 图内强制显示：「AA 模式自动启用」是硬性语义，总开关照听，该 HUD 自身的开关
        // 在 AA 图内被绕过。可见性同步由 GameEventBus 按地图驱动，但同步事件可能晚于
        // 首帧渲染才到，这里再兜一道底：同步前先按地图自己判一遍，窗口期不会闪没
        if (!GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || (!GlobalConfig.Hud.VISIBLE_AA_COMMAND.getBooleanValue()
                    && LanguageUtils.getMap() != MapId.ALIEN_ARCADIUM)) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round <= 0) {
            return;
        }
        AaRoundDetail detail = DataManager.get().getAaRoundDetail(round);

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int x = (int) (GlobalConfig.getXAaCommand(screenWidth) * screenWidth);
        int y = (int) (GlobalConfig.getYAaCommand(screenHeight) * screenHeight);
        float scale = (float) GlobalConfig.Hud.SCALE_AA_COMMAND.getDoubleValue();

        drawScaled(context, x, y, scale, () -> drawHud(context, x, y, round, detail));
    }

    private void drawHud(DrawContext context, int x, int y, int round, AaRoundDetail detail) {
        String yes = Text.translatable("nomorezombies.aacommand.yes").getString();
        String no = Text.translatable("nomorezombies.aacommand.no").getString();
        int fh = textRenderer.fontHeight;

        drawLine(context, x, y,
                Text.translatable("nomorezombies.aacommand.hud.round").getString(),
                "r" + round, VALUE_COLOR);
        y += fh;

        String giantVal = detail == null ? "?" : (detail.hasGiant() ? yes : no);
        int giantColor = detail == null ? UNKNOWN_COLOR : (detail.hasGiant() ? YES_COLOR : NO_COLOR);
        drawLine(context, x, y,
                Text.translatable("nomorezombies.aacommand.hud.giant").getString(), giantVal, giantColor);
        y += fh;

        String oldoneVal = detail == null ? "?" : (detail.hasOldOne() ? yes : no);
        int oldoneColor = detail == null ? UNKNOWN_COLOR : (detail.hasOldOne() ? YES_COLOR : NO_COLOR);
        drawLine(context, x, y,
                Text.translatable("nomorezombies.aacommand.hud.oldone").getString(), oldoneVal, oldoneColor);
        y += fh;

        drawLine(context, x, y,
                Text.translatable("nomorezombies.aacommand.hud.difficulty").getString(),
                detail == null ? "?" : difficultyRoman(detail.getDangerLevel()),
                detail == null ? UNKNOWN_COLOR : difficultyColor(detail.getDangerLevel()));
        y += fh;

        drawSpotsLine(context, x, y, detail);
    }

    private void drawLine(DrawContext context, int x, int y, String label, String value, int valueColor) {
        context.drawTextWithShadow(textRenderer, label, x, y, LABEL_COLOR);
        context.drawTextWithShadow(textRenderer, value, x + textRenderer.getWidth(label), y, valueColor);
    }

    /** 推荐点位行：label 后依次画蓝色 "#N" 标记 + 白色点位名；没有数据就画一个 "?" 占位。 */
    private void drawSpotsLine(DrawContext context, int x, int y, AaRoundDetail detail) {
        String label = Text.translatable("nomorezombies.aacommand.hud.spots").getString();
        context.drawTextWithShadow(textRenderer, label, x, y, LABEL_COLOR);
        int cx = x + textRenderer.getWidth(label);
        List<String[]> segments = buildPointSegments(detail);
        if (segments == null) {
            context.drawTextWithShadow(textRenderer, "?", cx, y, VALUE_COLOR);
            return;
        }
        for (int i = 0; i < segments.size(); i++) {
            String[] segment = segments.get(i);
            String marker = segment[0] + " ";
            context.drawTextWithShadow(textRenderer, marker, cx, y, POINT_COLOR);
            cx += textRenderer.getWidth(marker);
            context.drawTextWithShadow(textRenderer, segment[1], cx, y, VALUE_COLOR);
            cx += textRenderer.getWidth(segment[1]);
            if (i != segments.size() - 1) {
                context.drawTextWithShadow(textRenderer, " ", cx, y, VALUE_COLOR);
                cx += textRenderer.getWidth(" ");
            }
        }
    }

    // ==================== 聊天输出（自动指挥） ====================

    /** 回合开始钩子（GameEventBus 调用）：延迟 20 tick 再播报，留出 AA 地图识别时间——回合标题常先于地图解析到达。 */
    public static void onRoundStarted(int round) {
        if (round <= 0) {
            return;
        }
        DelayedTaskScheduler scheduler = DelayedTaskScheduler.get();
        if (scheduler == null) {
            return;
        }
        scheduler.runTaskLater(20, () -> fireIfDue(round));
    }

    private static void fireIfDue(int round) {
        fireIfDue(round, 0);
    }

    /**
     * 播报守卫——把「该不该说」拆成四道闸：总开关、回合号没被推进、人还在 Zombies、
     * 地图确是 AA，全过才播。地图未识别时不能直接放弃：回合标题常早于地图解析到达，
     * 于是延迟 20 tick 重试，最多 15 次（≈5s）仍不识别才放弃本次播报。
     *
     * <p>每道守卫的裁决都打调试日志，回放游戏流程时能逐条对上，方便排查「为什么不播」。
     */
    private static void fireIfDue(int round, int attempt) {
        if (!GlobalConfig.QoL.AA_COMMAND_ENABLED.getBooleanValue()) {
            NoMoreZombies.LOGGER.info("[AA指挥] 开关关闭，跳过回合 {} 播报", round);
            return;
        }
        // 20 tick 延迟窗口里又开了新回合，说明那轮播报已过时——跳过本次，防重复播报
        if (CheckSpawnTimes.get().getCurrentRound() != round) {
            NoMoreZombies.LOGGER.info("[AA指挥] 当前回合 {} 已推进，跳过回合 {} 的本次播报",
                    CheckSpawnTimes.get().getCurrentRound(), round);
            return;
        }
        if (!PlayerUtils.isInZombies()) {
            NoMoreZombies.LOGGER.info("[AA指挥] 已离开 Zombies，跳过回合 {} 播报", round);
            return;
        }
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            if (attempt < 15) {
                DelayedTaskScheduler.get().runTaskLater(20, () -> fireIfDue(round, attempt + 1));
            } else {
                NoMoreZombies.LOGGER.info("[AA指挥] 地图未识别（已重试 {} 次），放弃回合 {} 播报", attempt, round);
            }
            return;
        }
        if (map != MapId.ALIEN_ARCADIUM) {
            NoMoreZombies.LOGGER.info("[AA指挥] 地图 {} 非 AA，跳过回合 {} 播报", map, round);
            return;
        }
        Text message = buildMessageText(round);
        Text text = Text.literal("[NoMoreZombies] ").formatted(Formatting.GOLD)
                .copy()
                .append(message);
        PlayerUtils.sendMessage(text, getOutput());
        NoMoreZombies.LOGGER.info("[AA指挥] 回合 {} 播报已发送", round);
    }

    private static GlobalConfig.AlertOutput getOutput() {
        return GlobalConfig.AaCommand.OUTPUT.getOptionListValue() instanceof GlobalConfig.AlertOutput o
                ? o : GlobalConfig.AlertOutput.SELF;
    }

    /** 用模板拼出本回合指挥信息：模板非法时回退默认模板，无该回合数据时相关字段占位 "?"。 */
    public static Text buildMessageText(int round) {
        AaRoundDetail detail = DataManager.get().getAaRoundDetail(round);
        String template = resolveTemplate();
        MutableText result = Text.literal("");
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            result.append(Text.literal(template.substring(last, m.start())));
            switch (m.group(1)) {
                case "round" -> result.append(Text.literal(Integer.toString(round)).formatted(Formatting.YELLOW));
                case "point" -> result.append(buildPointsText(detail));
                case "boss" -> result.append(buildBossText(detail));
                case "difficulty" -> result.append(buildDifficultyText(detail));
                default -> result.append(Text.literal(m.group()));
            }
            last = m.end();
        }
        result.append(Text.literal(template.substring(last)));
        return result;
    }

    private static String resolveTemplate() {
        String template = GlobalConfig.AaCommand.TEMPLATE.getStringValue();
        // 模板为空或非法时回退到本地化默认模板——默认模板走 lang 键，中英随客户端语言自动切换
        return isValidTemplate(template) ? template
                : Text.translatable("nomorezombies.aacommand.defaultTemplate").getString();
    }

    private static boolean isValidTemplate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return false;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!KNOWN_VARS.contains(m.group(1))) {
                return false;
            }
        }
        return true;
    }

    /** 推荐点位切成片段：[{ "#N", 点位名 }, ...]，HUD 逐段上色用；无数据返回 null。 */
    private static List<String[]> buildPointSegments(AaRoundDetail detail) {
        if (detail == null) {
            return null;
        }
        List<String> spots = detail.getRecommendedSpots();
        if (spots.isEmpty()) {
            return null;
        }
        List<String[]> segments = new ArrayList<>(spots.size());
        for (int i = 0; i < spots.size(); i++) {
            segments.add(new String[]{"#" + (i + 1), spots.get(i)});
        }
        return segments;
    }

    /** 推荐点位（聊天版）："#N" 蓝色标记 + 白色点位名；无数据时返回 "?"。 */
    public static Text buildPointsText(AaRoundDetail detail) {
        List<String[]> segments = buildPointSegments(detail);
        if (segments == null) {
            return Text.literal("?");
        }
        MutableText result = Text.literal("");
        for (int i = 0; i < segments.size(); i++) {
            String[] segment = segments.get(i);
            result.append(Text.literal(segment[0]).formatted(Formatting.BLUE));
            result.append(Text.literal(" " + segment[1]).formatted(Formatting.WHITE));
            if (i != segments.size() - 1) {
                result.append(Text.literal(" "));
            }
        }
        return result;
    }

    /** 首领描述文案：巨人 / 老者 / 两者都有 / 没有——走 lang 键，中英随客户端语言。 */
    public static String buildBoss(AaRoundDetail detail) {
        if (detail == null) {
            return "?";
        }
        boolean giant = detail.hasGiant();
        boolean oldOne = detail.hasOldOne();
        if (giant && oldOne) {
            return Text.translatable("nomorezombies.aacommand.boss.both").getString();
        }
        if (giant) {
            return Text.translatable("nomorezombies.aacommand.boss.giant").getString();
        }
        if (oldOne) {
            return Text.translatable("nomorezombies.aacommand.boss.oldone").getString();
        }
        return Text.translatable("nomorezombies.aacommand.boss.none").getString();
    }

    /** 首领描述（聊天版）：有首领标红、无首领标绿，一眼看当前回合要不要防；无数据返回 "?"。 */
    public static Text buildBossText(AaRoundDetail detail) {
        if (detail == null) {
            return Text.literal("?");
        }
        boolean hasBoss = detail.hasGiant() || detail.hasOldOne();
        return Text.literal(buildBoss(detail)).formatted(hasBoss ? Formatting.RED : Formatting.GREEN);
    }

    /** 回合危险等级在 HUD 上的颜色（1~5 → 绿/深绿/黄/红/紫）；越界回退白色兜底。 */
    public static int difficultyColor(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_COLORS.length) {
            return VALUE_COLOR;
        }
        return DIFFICULTY_COLORS[dangerLevel - 1];
    }

    /** 回合危险等级罗马数字（1~5 → I/II/III/IV/V）；越界回退 "?" 占位。 */
    public static String difficultyRoman(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_ROMAN.length) {
            return "?";
        }
        return DIFFICULTY_ROMAN[dangerLevel - 1];
    }

    /** 回合危险等级在聊天里的颜色（与 HUD 的 difficultyColor 一一对应）；越界回退白色。 */
    public static Formatting difficultyFormatting(int dangerLevel) {
        if (dangerLevel < 1 || dangerLevel > DIFFICULTY_FORMATTING.length) {
            return Formatting.WHITE;
        }
        return DIFFICULTY_FORMATTING[dangerLevel - 1];
    }

    /** 难度（聊天版）：罗马数字按危险等级着色；无数据返回白色 "?" 占位。 */
    public static Text buildDifficultyText(AaRoundDetail detail) {
        if (detail == null) {
            return Text.literal("?").formatted(Formatting.WHITE);
        }
        return Text.literal(difficultyRoman(detail.getDangerLevel()))
                .formatted(difficultyFormatting(detail.getDangerLevel()));
    }
}
