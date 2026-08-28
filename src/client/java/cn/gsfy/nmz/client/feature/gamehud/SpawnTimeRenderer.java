package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import net.minecraft.client.gui.DrawContext;

/**
 * 波次时间 HUD（对应源 SpawnTimeRenderer）。
 * 把当前回合每波的预计时间逐行列出，箭头「➤」标在下一波的位置上，
 * 波次行按 {@link CheckSpawnTimes#getColor} 上色区分临近程度。
 *
 * <p>渲染只读核心逻辑 {@link CheckSpawnTimes} 的结算结果，自己不推算；
 * 门控由外部 shouldRender 总开关 + 实用 HUD 总开关 + 本 HUD 开关三层叠加。
 * 位置 / 缩放走 HUD 编辑器（Hud.X/Y/SCALE_SPAWN_TIME）。
 */
public class SpawnTimeRenderer extends TotalHUDRenderer {

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        if (!shouldRender
                || !GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || !GlobalConfig.Hud.VISIBLE_SPAWN_TIME.getBooleanValue()) {
            return;
        }
        CheckSpawnTimes spawnTimes = CheckSpawnTimes.get();
        if (spawnTimes == null) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float absoluteX = (float) GlobalConfig.getXSpawnTime(screenWidth) * screenWidth;
        float absoluteY = (float) GlobalConfig.getYSpawnTime(screenHeight) * screenHeight;
        float scale = (float) GlobalConfig.Hud.SCALE_SPAWN_TIME.getDoubleValue();

        drawScaled(context, (int) absoluteX, (int) absoluteY, scale, () -> {
            spawnTimes.getCurrentWave();
            int waveAmount = spawnTimes.getRoundTimes().length;
            if (spawnTimes.getCurrentRound() != 0 && waveAmount == 0) {
                return;
            }
            String arrow = "➤ ";
            int widthW = textRenderer.getWidth(arrow);
            if (waveAmount != 0) {
                context.drawTextWithShadow(textRenderer, arrow,
                        (int) absoluteX,
                        (int) (absoluteY + textRenderer.fontHeight * (spawnTimes.getNextWave() - 1)),
                        0xCC00CC);
            }
            for (int i = 0; i < waveAmount; i++) {
                int wave = i + 1;
                String line = "W" + wave + " " + getTime(spawnTimes.getWaveTime(wave));
                context.drawTextWithShadow(textRenderer, line,
                        (int) (absoluteX + widthW),
                        (int) (absoluteY + textRenderer.fontHeight * (wave - 1)),
                        spawnTimes.getColor(wave));
            }
        });
    }

    /** 秒数 → "MM:SS"（不足两位补零）；time≤0 按 00:00 处理。 */
    private String getTime(int time) {
        if (time <= 0) {
            return "00:00";
        }
        int seconds = time % 60;
        int minutes = time / 60;
        String strSeconds = seconds < 10 ? "0" + seconds : String.valueOf(seconds);
        String strMinutes = minutes < 10 ? "0" + minutes : String.valueOf(minutes);
        return strMinutes + ":" + strSeconds;
    }
}
