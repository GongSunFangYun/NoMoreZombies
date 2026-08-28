package cn.gsfy.nmz.client.feature.powerups;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.Powerup;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 道具轮次预测（对应源 PowerupPredict，NEZ 化改造）。在回合开始时调用，输出聊天预测。
 * 预测不走自己的算法，直接问 {@link PowerupDetect#nextRound} 引擎：已提交规律
 * （commit-once）的类型按「显式轮次 + 个位数字推演」给出下一次刷新回合，
 * 显式表用尽后仍能跨出去预测到后期。
 *
 * <p>只有已提交规律的类型才进输出，一个类型一句话，句间用「和 / 。」拼接；
 * 文案全走 lang 翻译键，随客户端语言自动中英切换（与 TimeRecorder 同一套口径）。
 */
public class PowerupPredict {

    /** 三种可预测道具的翻译键，顺序钉死与 TYPES 对齐（Insta Kill / Max Ammo / Shopping Spree）。 */
    private static final String[] NAME_KEYS = {
            "nomorezombies.powerup.instaKill",
            "nomorezombies.powerup.maxAmmo",
            "nomorezombies.powerup.shoppingSpree"
    };
    private static final Formatting[] COLORS = {Formatting.RED, Formatting.BLUE, Formatting.DARK_PURPLE};
    private static final Powerup.PowerupType[] TYPES = {
            Powerup.PowerupType.INSTA_KILL,
            Powerup.PowerupType.MAX_AMMO,
            Powerup.PowerupType.SHOPPING_SPREE
    };

    public static void detectNextPowerupRound() {
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round <= 0) {
            return;
        }
        PowerupDetect detect = PowerupDetect.get();
        if (detect == null) {
            return;
        }

        boolean anyCommitted = false;
        for (Powerup.PowerupType t : TYPES) {
            if (detect.hasCommitted(t)) {
                anyCommitted = true;
                break;
            }
        }
        if (!anyCommitted) {
            return;
        }

        List<Text> parts = new ArrayList<>();
        for (int i = 0; i < TYPES.length; i++) {
            Powerup.PowerupType type = TYPES[i];
            if (!detect.hasCommitted(type)) {
                continue;
            }
            int noticeRound = detect.nextRound(type, round);
            if (noticeRound < 0) {
                continue;
            }
            Text powerupText = Text.translatable(NAME_KEYS[i]).formatted(COLORS[i]);
            Text notice;
            if (noticeRound == round) {
                // 本回合就刷新：「瞬间击杀 本回合 (下一轮)」——顺带把下一轮也查出来一起报，省得连问
                notice = Text.translatable("nomorezombies.msg.powerup.now")
                        .formatted(Formatting.GREEN, Formatting.BOLD);
                int further = detect.nextRound(type, round + 1);
                if (further > 0) {
                    notice = Text.empty().append(notice)
                            .append(Text.translatable("nomorezombies.msg.powerup.further", further)
                                    .formatted(Formatting.GRAY));
                }
            } else {
                // 未来才刷新：「瞬间击杀 将在第 7 回合刷新」——提前打招呼，玩家好排节奏
                notice = Text.translatable("nomorezombies.msg.powerup.refresh", noticeRound)
                        .formatted(Formatting.AQUA);
            }
            parts.add(Text.empty().append(powerupText).append(" ").append(notice));
        }

        if (parts.isEmpty()) {
            return;
        }

        Text message = Text.literal("[NoMoreZombies] ").formatted(Formatting.GOLD);
        for (int i = 0; i < parts.size(); i++) {
            message = message.copy().append(parts.get(i));
            if (i != parts.size() - 1) {
                message = message.copy()
                        .append(Text.translatable("nomorezombies.msg.powerup.and").formatted(Formatting.WHITE));
            } else {
                message = message.copy()
                        .append(Text.translatable("nomorezombies.msg.powerup.dot").formatted(Formatting.WHITE));
            }
        }
        PlayerUtils.sendMessage(message,
                GlobalConfig.Powerups.ALERT_OUTPUT.getOptionListValue() instanceof GlobalConfig.AlertOutput o
                        ? o : GlobalConfig.AlertOutput.SELF);
    }

    private PowerupPredict() {
    }
}
