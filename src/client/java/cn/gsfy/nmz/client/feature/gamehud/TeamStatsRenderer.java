package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.util.AvatarManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * 队伍统计 HUD（可移动/缩放）——把队友的战况拍成一张表钉在屏幕上：
 *     血量 状态   击杀 倒地 死亡 金钱
 * 玩家 20   战斗中 10   0    0    1200
 * 头像、名字、六列数值一一对齐，最多显示 4 名玩家。
 *
 * <p>数据从 {@link TeamStats} 读，这里只负责排版；头像由 {@link AvatarManager} 画，
 * 名字列右侧才是数值区。血量按阈值变色（高血绿 / 低血黄 / 濒死红），
 * 重进恢复的数据直接显示，不设占位态。
 */
public class TeamStatsRenderer extends TotalHUDRenderer {

    private static final int PAD = 8;
    /** 名字列左侧的头像区宽度：8px 头像 + 2px 间距，头像与名字之间留一条缝。 */
    private static final int AVATAR_W = 8;
    private static final int NAME_OFFSET = AVATAR_W + 2;

    /** 表头文案走 lang 键，随客户端语言中英切换。 */
    private String[] headers() {
        return new String[]{
                Text.translatable("nomorezombies.teamstats.header.hp").getString(),
                Text.translatable("nomorezombies.teamstats.header.status").getString(),
                Text.translatable("nomorezombies.teamstats.header.kills").getString(),
                Text.translatable("nomorezombies.teamstats.header.downs").getString(),
                Text.translatable("nomorezombies.teamstats.header.deaths").getString(),
                Text.translatable("nomorezombies.teamstats.header.gold").getString()
        };
    }

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || !GlobalConfig.Hud.VISIBLE_TEAM_STATS.getBooleanValue()) {
            return;
        }
        Map<String, TeamStats.PlayerStats> players = TeamStats.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int x = (int) (GlobalConfig.getXTeamStats(screenWidth) * screenWidth);
        int y = (int) (GlobalConfig.getYTeamStats(screenHeight) * screenHeight);
        float scale = (float) GlobalConfig.Hud.SCALE_TEAM_STATS.getDoubleValue();
        drawScaled(context, x, y, scale, () -> drawTable(context, x, y, players));
    }

    private void drawTable(DrawContext context, int x, int y, Map<String, TeamStats.PlayerStats> players) {
        String[] headers = headers();
        int nameCol = 0;
        for (String name : players.keySet()) {
            nameCol = Math.max(nameCol, textRenderer.getWidth(name));
        }

        int displayCount = Math.min(players.size(), 4);
        String[][] valueRows = new String[displayCount][headers.length];
        int[][] valueColors = new int[displayCount][headers.length];
        String[] playerNames = new String[displayCount];
        int[] colWidth = new int[headers.length];
        int row = 0;
        for (Map.Entry<String, TeamStats.PlayerStats> e : players.entrySet()) {
            if (row >= 4) {
                break;
            }
            playerNames[row] = e.getKey();
            TeamStats.PlayerStats st = e.getValue();
            // 重进恢复的数据直接亮出来——缓存只是兜底，稍后会被计分板/实体快照的权威值覆盖，所以不设占位态
            valueRows[row][0] = st.health < 0 ? "?" : String.valueOf(st.health);
            valueColors[row][0] = healthColor(st.health);
            valueRows[row][1] = statusLabel(st.status);
            valueColors[row][1] = statusColor(st.status);
            valueRows[row][2] = String.valueOf(st.kills);
            valueColors[row][2] = 0xFFFFFF;
            valueRows[row][3] = String.valueOf(st.downed);
            valueColors[row][3] = 0xFFFFFF;
            valueRows[row][4] = String.valueOf(st.deaths);
            valueColors[row][4] = 0xFFFFFF;
            valueRows[row][5] = String.valueOf(st.gold);
            valueColors[row][5] = 0xFFFFFF;
            for (int i = 0; i < headers.length; i++) {
                colWidth[i] = Math.max(colWidth[i], textRenderer.getWidth(headers[i]));
                colWidth[i] = Math.max(colWidth[i], textRenderer.getWidth(valueRows[row][i]));
            }
            row++;
        }

        // 名字列往右让出头像区：表头与数值列整体右移 NAME_OFFSET，和名字对齐，头像不被压住
        int nameX = x + NAME_OFFSET;
        int[] colX = new int[headers.length];
        int cx = nameX + nameCol + PAD;
        for (int i = 0; i < headers.length; i++) {
            colX[i] = cx;
            cx += colWidth[i] + PAD;
        }

        for (int i = 0; i < headers.length; i++) {
            context.drawTextWithShadow(textRenderer, headers[i], colX[i], y, 0xFFFF55);
        }
        y += textRenderer.fontHeight + 2;

        int fh = textRenderer.fontHeight;
        for (int r = 0; r < playerNames.length; r++) {
            // 头像取正面脸裁剪，垂直居中于本行；名字让出 NAME_OFFSET 与头像错开
            AvatarManager.drawHead(context, playerNames[r], null, x, y + (fh - AVATAR_W) / 2);
            context.drawTextWithShadow(textRenderer, playerNames[r], nameX, y, 0xFFFFFF);
            for (int i = 0; i < headers.length; i++) {
                context.drawTextWithShadow(textRenderer, valueRows[r][i], colX[i], y, valueColors[r][i]);
            }
            y += fh + 1;
        }
    }

    private String statusLabel(TeamStats.Status status) {
        return switch (status) {
            case IN_COMBAT -> Text.translatable("nomorezombies.teamstats.status.combat").getString();
            case DOWNED -> Text.translatable("nomorezombies.teamstats.status.downed").getString();
            case DEAD -> Text.translatable("nomorezombies.teamstats.status.dead").getString();
            case LEFT -> Text.translatable("nomorezombies.teamstats.status.left").getString();
        };
    }

    private int statusColor(TeamStats.Status status) {
        switch (status) {
            case IN_COMBAT: return 0x55FF55;
            case DOWNED: return 0xFFFF55;
            case DEAD: return 0xFF5555;
            case LEFT: return 0xAA0000;
        }
        return 0x55FF55;
    }

    private int healthColor(int health) {
        if (health < 0) {
            return 0xFFFFFF;
        }
        if (health > 10) return 0x55FF55;
        if (health > 5) return 0xFFFF55;
        return 0xFF5555;
    }
}
