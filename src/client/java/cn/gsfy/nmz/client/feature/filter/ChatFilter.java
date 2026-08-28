package cn.gsfy.nmz.client.feature.filter;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import cn.gsfy.nmz.client.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 聊天消息过滤——把不想看的聊天行在进 HUD 之前掐掉。
 * {@link cn.gsfy.nmz.mixin.client.ChatHudMixin} 在 ChatHud.addMessage 的 HEAD 调进来，
 * 命中就取消渲染（消息彻底不显示）。HEAD 注入点照常执行，所以即便消息被过滤，
 * 队伍统计/道具检测仍能收到它——过滤只遮玩家眼睛，不挡数据解析。
 *
 * <p>各过滤项是独立开关（默认全关）。GOLD/WINDOW/HIT_TARGET/LUCKY_CHEST/OPEN_AREA 的匹配器
 * 刻意绕开 mod 解析依赖的消息（道具激活/击倒/救治/击杀），免得误伤数据源。
 * PLAYER_CONNECTION（默认关）是例外，会连带隐藏「离开游戏」行；一旦开启，
 * 队伍统计的死亡/退出判定就改由计分板金币缺失兜底。
 */
public final class ChatFilter {

    /** 窗口修理状态提示（中英）：修理中/已停止/完全修好等。 */
    private static final Pattern WINDOW = Pattern.compile(
            "正在修理窗户|已停止修理|停止修理|完全修好|修好.*窗户"
                    + "|repairing windows|stopped repairing|fully repaired|can't repair windows|cannot repair windows",
            Pattern.CASE_INSENSITIVE);

    /** 击中目标提示（中英）："击中了目标 / hit the target"。 */
    private static final Pattern HIT_TARGET = Pattern.compile(
            "击中了目标|擊中目標|hit the target", Pattern.CASE_INSENSITIVE);

    /** 幸运箱提示（中英）："幸运箱 / lucky chest"。 */
    private static final Pattern LUCKY_CHEST = Pattern.compile(
            "幸运箱|幸運箱|lucky chest", Pattern.CASE_INSENSITIVE);

    /** 开启区域提示（中英）："开启了 / opened the"。 */
    private static final Pattern OPEN_AREA = Pattern.compile(
            "开启了|打开了|開啟了|打開了|opened the|opened up", Pattern.CASE_INSENSITIVE);

    /** 玩家进出提示（默认关）：会连带隐藏「离开游戏」行，队伍统计只能靠计分板金币缺失兜底。 */
    private static final Pattern PLAYER_CONNECTION = Pattern.compile(
            "加入了游戏|加入遊戲|joined the game|has joined|left the game"
                    + "|离开了游戏|退出了游戏|重新加入|重新進入|rejoined",
            Pattern.CASE_INSENSITIVE);

    /** 是否有任一过滤项启用：全关时走快速放行通道，不做逐条正则匹配。 */
    private static boolean anyEnabled() {
        return GlobalConfig.Hide.HIDE_GOLD.getBooleanValue()
                || GlobalConfig.Hide.HIDE_WINDOW.getBooleanValue()
                || GlobalConfig.Hide.HIDE_HIT_TARGET.getBooleanValue()
                || GlobalConfig.Hide.HIDE_LUCKY_CHEST.getBooleanValue()
                || GlobalConfig.Hide.HIDE_OPEN_AREA.getBooleanValue()
                || GlobalConfig.Hide.HIDE_PLAYER_CONNECTION.getBooleanValue();
    }

    /** 是否隐藏这条消息（raw 已是去格式化的纯文本）。策略是「默认放行，有事拦截」：无过滤项启用就直接放行。 */
    public static boolean shouldHide(String raw) {
        // 快速通道：全部过滤项未启用 → 直接放行（不逐条判断）
        if (!anyEnabled()) {
            return false;
        }
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String m = StringUtils.trim(raw);
        if (m.isEmpty()) {
            return false;
        }
        // 保险闸：道具激活消息永远不过滤——怕将来某条匹配器误伤道具检测
        if (LanguageUtils.isActivatedMessage(m)) {
            return false;
        }
        if (!PlayerUtils.isInZombies()) {
            return false;
        }
        if (GlobalConfig.Hide.HIDE_GOLD.getBooleanValue() && LanguageUtils.GOLD_MESSAGE_PATTERN.matcher(m).find())
            return true;
        if (GlobalConfig.Hide.HIDE_WINDOW.getBooleanValue() && WINDOW.matcher(m).find())
            return true;
        if (GlobalConfig.Hide.HIDE_HIT_TARGET.getBooleanValue() && HIT_TARGET.matcher(m).find())
            return true;
        if (GlobalConfig.Hide.HIDE_LUCKY_CHEST.getBooleanValue() && LUCKY_CHEST.matcher(m).find())
            return true;
        if (GlobalConfig.Hide.HIDE_OPEN_AREA.getBooleanValue() && OPEN_AREA.matcher(m).find())
            return true;
        if (GlobalConfig.Hide.HIDE_PLAYER_CONNECTION.getBooleanValue() && PLAYER_CONNECTION.matcher(m).find())
            return true;
        return false;
    }

    private ChatFilter() {
    }
}
