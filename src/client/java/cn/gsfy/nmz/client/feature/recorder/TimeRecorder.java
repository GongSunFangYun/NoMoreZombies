package cn.gsfy.nmz.client.feature.recorder;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.GlobalConfig.RecordTiming;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

/**
 * 回合用时记录（对应源 TimeRecorder）。
 *
 * <p>每个新回合开始时，把上一回合的「耗时 + 击杀 + 回合分均击杀（RKPM）」算成一句话
 * 发到聊天框（可点击复制）。耗时优先用权威差分——计分板服务器总时长在「回合开始」与
 * 「回合结束」两端的差值，免疫本地标题接收抖动；未同步到权威值（首回合 / 刚重进）时
 * 回退本地回合计时器 gameTick。击杀读队伍统计/计分板（重进不归零）——中途退出重进也不出错。
 */
public class TimeRecorder {

    /** 回合开始标题一出现就调用——此刻 gameTick 还没清零，currentRound 还停在上一轮。 */
    public static void recordGameTime() {
        // 录制总开关：关掉就不播报回合统计（播报频率在全局配置页统一选，所有地图共用一档）
        if (!GlobalConfig.QoL.RECORD_ENABLED.getBooleanValue()) {
            return;
        }
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            return;
        }
        boolean isAA = map == MapId.ALIEN_ARCADIUM;
        RecordTiming timing = (RecordTiming) GlobalConfig.Record.ROUNDS_RECORD.getOptionListValue();
        int currentRound = CheckSpawnTimes.get().getCurrentRound();
        if (currentRound <= 0) {
            return;
        }
        int increment = timing == RecordTiming.QUINTUPLE ? 5 : (timing == RecordTiming.TENFOLD ? 10 : 0);
        if (increment != 0 && currentRound % increment != 1) {
            return;
        }
        int[] skipRound = isAA ? new int[]{0, 10, 21, 105} : new int[]{0, 10, 20, 30, 40};
        if (contains(skipRound, currentRound)) {
            return;
        }
        try {
            // 回合持续时间优先取权威差分：计分板服务器总时长在「本回合开始」与「当前」两端之差
            // （GameTickHandler.getRoundElapsedFromTotal），免疫本地标题接收抖动；未就绪
            // （首回合还没同步 / 重进后还没到下个回合标题）回退到本地回合计时器 gameTick。
            long authoritativeMs = GameTickHandler.get().getRoundElapsedFromTotal();
            int durationSeconds = (int) (authoritativeMs >= 0
                    ? authoritativeMs / 1000 : GameTickHandler.get().getGameTick() / 1000);
            String cleanTime = formatDuration(durationSeconds);

            // 本回合击杀 = 收到本回合结束信号时的总击杀 − 收到上回合结束信号时的总击杀。
            // 总击杀读队伍统计/计分板的服务器权威值（重进不归零），才不会被中途退出重进坑成 0。
            int currentKills = TeamStats.getLocalKills();
            if (currentKills < 0) {
                currentKills = 0;
            }
            // 跨局检测：命中就作废击杀基线（-1），否则新局第一回合会拿上一局累计击杀当基线，
            // 把「本回合击杀」算成 0——两条判据：① 回合号严格回落（新游戏从低回合重来）；
            // ② 当前累计击杀 < 基线（局内击杀只增不减，变小 = 已跨局 / 数据被重置）。
            // 用「严格大于」而非「>=」：重连同一局时回合号可能暂未刷新（还等于上次记录的回合号），
            // 此时击杀若继续增长应保留基线做差分，不能误判成新游戏。
            if (lastRecordedRound > currentRound || currentKills < lastRoundKills) {
                lastRoundKills = -1;
            }
            lastRecordedRound = currentRound;

            int roundKills;
            if (lastRoundKills >= 0) {
                roundKills = Math.max(0, currentKills - lastRoundKills);
            } else {
                roundKills = currentKills;
            }
            lastRoundKills = currentKills;

            // RKPM（回合分均击杀）= 本回合净击杀 × 60 / 本回合持续时间（秒，优先权威差分、回退 gameTick）
            double rkpm = durationSeconds > 0 ? (double) roundKills * 60.0 / durationSeconds : 0.0;
            String rkpmStr = String.format("%.2f", rkpm);

            // 模板占位符 zh/en 一致：%1$d=击杀、%2$s=用时、%3$d=回合、%4$s=RKPM。
            // 传参顺序必须钉死 (roundKills, cleanTime, currentRound, rkpmStr)——我们踩过把前两个
            // 传反的坑：占位符对错位，HUD 就显示「在 25(击杀) 里击杀了 00:50(用时)」。
            String summary = Text.translatable("nomorezombies.record.summary",
                    roundKills, cleanTime, currentRound, rkpmStr).getString();

            Text crossBar = Text.literal("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
                    .formatted(net.minecraft.util.Formatting.GREEN, net.minecraft.util.Formatting.BOLD);
            Text summaryText = Text.literal(summary).formatted(net.minecraft.util.Formatting.YELLOW);
            Text copy = Text.translatable("nomorezombies.record.copy").formatted(net.minecraft.util.Formatting.GREEN);

            summaryText = summaryText.copy().styled(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, summary))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, copy)));

            PlayerUtils.sendMessage(crossBar);
            PlayerUtils.sendMessage(summaryText);
            PlayerUtils.sendMessage(crossBar);
        } catch (Exception e) {
            PlayerUtils.sendMessage(Text.translatable("nomorezombies.msg.recordFailed")
                    .formatted(net.minecraft.util.Formatting.RED));
        }
    }

    /** 上回合结束信号时的累计击杀基线（队伍统计/计分板权威值，重进不归零）。
     * -1 = 无基线（新游戏/中途加入的第一回合），此时本回合击杀直接用完整累计击杀。 */
    private static int lastRoundKills = -1;
    /** 最近一次记录的回合号：回合号回落 = 新游戏/跨局，击杀基线作废，防第一回合用上一局基线算出 0。 */
    private static int lastRecordedRound = 0;

    private static boolean contains(int[] array, int value) {
        for (int i : array) {
            if (i == value) {
                return true;
            }
        }
        return false;
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "00:00";
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private TimeRecorder() {
    }
}
