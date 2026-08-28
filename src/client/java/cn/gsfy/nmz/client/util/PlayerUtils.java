package cn.gsfy.nmz.client.util;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.ScoreboardManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 玩家相关工具——局内状态判定、聊天消息发送、音效播放收在一个静态类里，
 * 各 feature 按需取用，不重复写「拿客户端实例」的样板代码。
 *
 * <p>局内判定带 200ms 缓存 + 连续 3 次确认的防抖：回合切换瞬间的侧边栏抖动
 * 不会误判离场；聊天发送按输出方式（本地 / 队伍 / 公聊）路由，队伍公聊去前缀。
 */
public final class PlayerUtils {

    /** 上次局内判定的时间戳：跟 200ms 节流配合，判定不会每帧都重扫计分板。 */
    private static long lastZombiesCheck;
    /** 节流后的缓存结果：判定窗口内直接返回它，避免每帧重扫计分板。 */
    private static boolean cachedInZombies;
    /** 连续「不在 Zombies」的判定次数：攒满 {@link #NOT_IN_ZOMBIES_CONFIRM} 次才翻转为 false。 */
    private static int notInZombiesStreak;
    /** 200ms×3 ≈ 600ms：短暂非局内（回合切换瞬间侧边栏缺「剩余僵尸」行）不视为离场。 */
    private static final int NOT_IN_ZOMBIES_CONFIRM = 3;

    /**
     * 判断是否在 Zombies 局内：标题命中 + 至少一行为 Zombies Left，带 200ms 缓存节流。
     * 进局即时识别；离场要连续 3 次非局内才翻转——防回合切换 / 侧边栏瞬时缺行
     * 误判离场，把整套状态机和挂起任务全复位。
     */
    public static boolean isInZombies() {
        long now = System.currentTimeMillis();
        if (now - lastZombiesCheck > 200) {
            boolean in = computeInZombies();
            if (in) {
                cachedInZombies = true;
                notInZombiesStreak = 0;
            } else if (++notInZombiesStreak >= NOT_IN_ZOMBIES_CONFIRM) {
                // 连续攒满非局内才翻转：回合切换瞬间侧边栏会缺行，误判离场会把
                // 状态机和挂起任务全复位，宁慢勿错。
                cachedInZombies = false;
            }
            lastZombiesCheck = now;
        }
        return cachedInZombies;
    }

    /** 实时算一遍是否局内（不读缓存）：标题是 Zombies 且侧边栏能找到 Zombies Left 行。 */
    private static boolean computeInZombies() {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return false;
        }
        if (!LanguageUtils.isZombiesTitle(sm.getTitle())) {
            return false;
        }
        for (int i = 1; i <= sm.getSize(); i++) {
            if (LanguageUtils.isZombiesLeft(sm.getContent(i))) {
                return true;
            }
        }
        return false;
    }

    /** 判断是否在 Zombies 大厅：只有标题命中、没有剩余僵尸行，区分「局内」与「等人」两种态。 */
    public static boolean isInZombiesTitle() {
        ScoreboardManager sm = ScoreboardManager.get();
        return sm != null && LanguageUtils.isZombiesTitle(sm.getTitle());
    }

    /** 往本地聊天 HUD 追加一条消息：只显示不发送，通报 / 提示类输出都用它。 */
    public static void sendMessage(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(text);
        }
    }

    /** mod 前缀（[NoMoreZombies] ）：只在本地显示时保留，发队伍 / 公聊要剥掉。 */
    private static final String MOD_PREFIX = "[" + NoMoreZombies.MOD_NAME + "] ";

    /** 剥掉 mod 前缀（以 [NoMoreZombies] 开头才剥）：队伍 / 公聊发给别人看，不污染聊天。 */
    private static String stripModPrefix(String s) {
        if (s != null && s.startsWith(MOD_PREFIX)) {
            return s.substring(MOD_PREFIX.length());
        }
        return s;
    }

    /**
     * 按输出方式路由消息：SELF 走本地聊天，PARTY / CHAT 走服务端命令（/pc、/ac）。
     * 走服务端时先去色再去 mod 前缀——前缀只在本地 SELF 显示，发出去会污染别人聊天。
     */
    public static void sendMessage(Text text, GlobalConfig.AlertOutput output) {
        if (text == null || output == null) {
            return;
        }
        switch (output) {
            case SELF -> sendMessage(text);
            case PARTY -> sendCommandToServer("pc " + stripModPrefix(StringUtils.trim(text.getString())));
            case CHAT -> sendCommandToServer("ac " + stripModPrefix(StringUtils.trim(text.getString())));
        }
    }

    /** 向服务器发一条命令：不带前导斜杠，sendChatCommand 会按 /命令 处理签名，队伍 / 公聊发送都借它。 */
    public static void sendCommandToServer(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    /**
     * 在玩家位置播一个音效（现代实现，取代旧版伪造 S29 数据包的做法）：
     * 提示音 / 整秒滴答这类反馈都走它，免手工发包。
     *
     * @param soundId 音效的资源位置字符串
     * @param pitch   播放音高
     */
    public static void playSound(String soundId, float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        SoundEvent soundEvent = SoundEvent.of(Identifier.of(soundId));
        client.getSoundManager().play(PositionedSoundInstance.master(soundEvent, pitch));
    }
}
