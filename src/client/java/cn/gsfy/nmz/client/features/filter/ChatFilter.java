package cn.gsfy.nmz.client.features.filter;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.StringUtils;

import java.util.regex.Pattern;

/**
 * Chat message filtering—cuts unwanted chat lines before they reach the
 * HUD. {@link cn.gsfy.nmz.mixin.client.chat.ChatHudMixin} calls in at the
 * HEAD of {@code ChatHud.addMessage}; a hit cancels rendering, so the
 * message never shows at all.
 *
 * <p>Filtered messages still reach mod parsing: the real guarantee is the
 * priority-500 ordering in ChatHudMixin—a hidden message still goes through
 * {@code ClientReceiveMessageEvents.GAME} for mod parsing first, and is
 * cancelled from rendering afterwards. Filtering covers only the player's
 * eyes; it never blocks team stats or powerup detection.
 *
 * <p>Each filter is its own switch (all off by default). The matchers for
 * GOLD/WINDOW/HIT_TARGET/LUCKY_CHEST/OPEN_AREA deliberately steer clear of
 * parse input, so they cannot damage a data source; powerup-activation
 * messages that parsing depends on have {@code isActivatedMessage} as a
 * safety catch (see {@link #shouldHide(String)}), so none get filtered.
 * PLAYER_CONNECTION (off by default) is the exception—it hides "left the
 * game" lines too; once enabled, the team stats' death/leave detection
 * falls back to the scoreboard's missing-coin check.
 */
public final class ChatFilter {

    /** Window repair status (Chinese and English): repairing, stopped,
     *  fully repaired, and so on. */
    private static final Pattern WINDOW = Pattern.compile(
            "正在修理窗户|已停止修理|停止修理|完全修好|修好.*窗户"
                    + "|repairing windows|stopped repairing|fully repaired|can't repair windows|cannot repair windows",
            Pattern.CASE_INSENSITIVE);

    /** Hit-target message (Chinese and English): "击中了目标 / hit the target". */
    private static final Pattern HIT_TARGET = Pattern.compile(
            "击中了目标|擊中目標|hit the target", Pattern.CASE_INSENSITIVE);

    /** Lucky-chest message (Chinese and English): "幸运箱 / lucky chest". */
    private static final Pattern LUCKY_CHEST = Pattern.compile(
            "幸运箱|幸運箱|lucky chest", Pattern.CASE_INSENSITIVE);

    /** Area-opened message (Chinese and English): "开启了 / opened the". */
    private static final Pattern OPEN_AREA = Pattern.compile(
            "开启了|打开了|開啟了|打開了|opened the|opened up", Pattern.CASE_INSENSITIVE);

    /**
     * Player join/leave (off by default). Hides "left the game" lines too;
     * team stats then have to fall back to the scoreboard's missing-coin
     * check.
     */
    private static final Pattern PLAYER_CONNECTION = Pattern.compile(
            "加入了游戏|加入遊戲|joined the game|has joined|left the game"
                    + "|离开了游戏|退出了游戏|重新加入|重新進入|rejoined",
            Pattern.CASE_INSENSITIVE);

    /** Whether any filter is on. All off sends {@link #shouldHide} down
     *  the fast path. */
    private static boolean anyEnabled() {
        return GlobalConfig.Hide.HIDE_GOLD.getBooleanValue()
                || GlobalConfig.Hide.HIDE_WINDOW.getBooleanValue()
                || GlobalConfig.Hide.HIDE_HIT_TARGET.getBooleanValue()
                || GlobalConfig.Hide.HIDE_LUCKY_CHEST.getBooleanValue()
                || GlobalConfig.Hide.HIDE_OPEN_AREA.getBooleanValue()
                || GlobalConfig.Hide.HIDE_PLAYER_CONNECTION.getBooleanValue();
    }

    /**
     * ChatHudMixin asks whether to hide a message before it enters the HUD.
     * Passes by default, and never intercepts powerup-activation messages.
     *
     * @param raw de-formatted message text; {@code null} or empty passes
     *   straight through
     * @return {@code true} when a hit on an enabled filter and in a Zombies
     *   game
     */
    public static boolean shouldHide(String raw) {
        // Fast path: nothing enabled, pass through without checking each one.
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
        // Safety catch: powerup-activation messages are never filtered—a
        // future matcher must not be able to damage powerup detection.
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
        // Last check returns directly—the five above already return on hit,
        // so an extra if here would only add a line.
        return GlobalConfig.Hide.HIDE_PLAYER_CONNECTION.getBooleanValue()
                && PLAYER_CONNECTION.matcher(m).find();
    }

    private ChatFilter() {
    }
}