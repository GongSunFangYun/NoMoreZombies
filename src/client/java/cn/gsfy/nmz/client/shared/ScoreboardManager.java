package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.client.util.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 计分板轮询器——每 5 个客户端 tick 读一次侧边栏，缓存标题与逐行内容。
 *
 * <p>始终存原始数据，不做 Zombies 标题过滤：原始内容便于调试（F3 对比），
 * 也方便各功能自行按需解析（回合号 / 总时长 / 队伍统计），过滤交给消费方更稳。
 */
public class ScoreboardManager {

    private static ScoreboardManager instance;
    private String title = "";
    private final List<String> content = new ArrayList<>();
    private int tick;

    public static ScoreboardManager get() {
        return instance;
    }

    /** 初始化单例并注册每 5 tick 的侧边栏轮询（仅游戏内、非单机时）。 */
    public void init() {
        instance = this;
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world == null || client.isInSingleplayer() || client.player == null) {
                return;
            }
            if (++tick % 5 == 0) {
                updateScoreboardContent();
            }
        });
    }

    /** 读一次侧边栏：缓存标题 + 过滤以 # 开头的计分板行，逆序保存逐行内容。 */
    public void updateScoreboardContent() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) {
            return;
        }
        String newTitle = StringUtils.trim(objective.getDisplayName().getString());

        List<String> newLines = new ArrayList<>();
        Collection<ScoreboardEntry> entries = scoreboard.getScoreboardEntries(objective);
        List<ScoreboardEntry> filtered = new ArrayList<>();
        for (ScoreboardEntry entry : entries) {
            if (entry.name() != null && !entry.name().getString().startsWith("#")) {
                filtered.add(entry);
            }
        }
        Collections.reverse(filtered);
        for (ScoreboardEntry entry : filtered) {
            Team team = scoreboard.getScoreHolderTeam(entry.name().getString());
            String prefix = team != null ? team.getPrefix().getString() : "";
            String suffix = team != null ? team.getSuffix().getString() : "";
            newLines.add(StringUtils.trim(prefix + entry.name().getString() + suffix));
        }
        this.title = newTitle;
        this.content.clear();
        this.content.addAll(newLines);
    }

    /** 清空标题与内容缓存（游戏结束 / 断线时调用，下一局重新轮询）。 */
    public void clear() {
        this.title = "";
        this.content.clear();
    }

    public String getTitle() {
        return title;
    }

    /** 取第 row 行（从 1 开始）。 */
    public String getContent(int row) {
        if (row < 1 || row > content.size()) {
            return "";
        }
        return content.get(row - 1);
    }

    public int getSize() {
        return content.size();
    }
}
