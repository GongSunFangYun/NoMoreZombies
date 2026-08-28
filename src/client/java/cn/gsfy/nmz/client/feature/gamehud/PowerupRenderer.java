package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.Powerup;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 道具 HUD（对应源 PowerupRenderer）。
 * 每种道具固定一行，行不跳动。状态机优先级从高到低渲染：
 *   已生效（真实时长带紧迫度变色倒计时 / 瞬时道具白字确认）> 已拾取（不渲染，状态只前进）
 *   > 本回合（已掉落·X.X秒 / 未掉落）> 已刷出（00:XX 消失倒计时，非预测类型）。
 *
 * <p>为什么这样排：已生效是玩家最关心的当前值，必须压在最上面；已拾取那轮只前进不回溯，
 * 错过了就不再补位，避免行数来回跳。道具名与状态文案全走 lang 键，随客户端语言中英切换，
 * 硬编码中文会把英文环境也钉死成中文。
 */
public class PowerupRenderer extends TotalHUDRenderer {

    /** 渲染顺序（固定，行不跳动）：道具永远按这个次序从上到下排，不看拾取先后。 */
    private static final List<Powerup.PowerupType> ORDER = List.of(
            Powerup.PowerupType.INSTA_KILL,
            Powerup.PowerupType.MAX_AMMO,
            Powerup.PowerupType.SHOPPING_SPREE,
            Powerup.PowerupType.DOUBLE_GOLD,
            Powerup.PowerupType.CARPENTER,
            Powerup.PowerupType.BONUS_GOLD);

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || !GlobalConfig.Hud.VISIBLE_POWERUP.getBooleanValue()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float absoluteX = (float) GlobalConfig.getXPowerup(screenWidth) * screenWidth;
        float absoluteY = (float) GlobalConfig.getYPowerup(screenHeight) * screenHeight;
        float scale = (float) GlobalConfig.Hud.SCALE_POWERUP.getDoubleValue();

        drawScaled(context, (int) absoluteX, (int) absoluteY, scale, () -> {
            int widthBasic = textRenderer.getWidth("-");
            // 名字列宽取全部道具类型翻译后的最大宽度——列宽稳定行不跳，且中文英文宽度不同，不能按字符数猜
            int widthNameCol = 0;
            for (Powerup.PowerupType t : ORDER) {
                widthNameCol = Math.max(widthNameCol, textRenderer.getWidth(powerupName(t)));
            }
            String roundLabel = Text.translatable("nomorezombies.powerup.hud.round").getString();
            long now = System.currentTimeMillis();
            Powerup.activePowerups.removeIf(a -> a.getExpireMs() <= now);
            int queue = 0;

            for (Powerup.PowerupType type : ORDER) {
                // ── 状态 1：已生效（最高优先，拾取后压过本回合的预测态） ──
                Powerup.ActivePowerup active = activeFor(type, now);
                if (active != null) {
                    drawName(context, absoluteX, absoluteY, widthBasic, type, queue, false);
                    if (active.isTimed()) {
                        drawActiveTimed(context, absoluteX, absoluteY, widthBasic, widthNameCol, active, now, queue);
                    } else {
                        String status = " - " + Text.translatable("nomorezombies.powerup.hud.activeInstant").getString();
                        context.drawTextWithShadow(textRenderer, status,
                                (int) (absoluteX + widthBasic + widthNameCol),
                                (int) (absoluteY + textRenderer.fontHeight * queue), 0xFFFFFF);
                    }
                    queue++;
                    continue;
                }
                // ── 状态 2：已拾取（生效已结束，状态机只前进、不回溯，这轮就空着不渲染） ──
                if (Powerup.pickedUpRound.contains(type)) {
                    continue;
                }
                // ── 状态 3：本回合预测（已掉落 / 未掉落二选一，预测引擎给的回合内情报） ──
                Powerup inc = incFor(type);
                if (inc != null) {
                    drawName(context, absoluteX, absoluteY, widthBasic, type, queue, false);
                    String status = inc.isDropped()
                            ? roundLabel + String.format(
                                    Text.translatable("nomorezombies.powerup.hud.dropAt").getString(),
                                    formatSeconds(inc.getDropGameTickMs()))
                            : roundLabel + Text.translatable("nomorezombies.powerup.hud.undropped").getString();
                    context.drawTextWithShadow(textRenderer, status,
                            (int) (absoluteX + widthBasic + widthNameCol),
                            (int) (absoluteY + textRenderer.fontHeight * queue), 0xFFFFFF);
                    queue++;
                    continue;
                }
                // ── 状态 4：已刷出（非预测类型在场存留，00:XX 消失倒计时） ──
                Powerup sitting = sittingFor(type);
                if (sitting != null) {
                    int offsetSeconds = sitting.getOffsetTime() / 20;
                    boolean flash = offsetSeconds <= 10 && offsetSeconds % 2 == 0;
                    drawName(context, absoluteX, absoluteY, widthBasic, type, queue, flash);
                    String status = " - 00:" + String.format("%02d", offsetSeconds);
                    context.drawTextWithShadow(textRenderer, status,
                            (int) (absoluteX + widthBasic + widthNameCol),
                            (int) (absoluteY + textRenderer.fontHeight * queue), 0x99CCFF);
                    queue++;
                }
            }
        });
    }

    /** 道具名行：名字按 type 上色，flash=true 时改白字闪烁，专门用来提示存留道具即将消失。 */
    private void drawName(DrawContext context, float absoluteX, float absoluteY, int widthBasic,
                          Powerup.PowerupType type, int queue, boolean flash) {
        String name = (flash ? "§f" : type.getColorCode()) + powerupName(type);
        context.drawTextWithShadow(textRenderer, name,
                (int) (absoluteX + widthBasic), (int) (absoluteY + textRenderer.fontHeight * queue), 0xFFFFFF);
    }

    /** 已生效（真实时长）行：白色「 - 已生效(」+ 紧迫度变色的秒数 + 白色「秒)」，秒数越少颜色越红。 */
    private void drawActiveTimed(DrawContext context, float absoluteX, float absoluteY,
                                 int widthBasic, int widthNameCol,
                                 Powerup.ActivePowerup active, long now, int queue) {
        String template = Text.translatable("nomorezombies.powerup.hud.active").getString();
        String withMarker = String.format(template, "%s"); // 如 "已生效(%s秒)"，%s 为秒数占位
        int marker = withMarker.indexOf("%s");
        String pre = marker >= 0 ? withMarker.substring(0, marker) : withMarker;
        String suf = marker >= 0 ? withMarker.substring(marker + 2) : "";
        long leftMs = active.getRemainingMs(now);
        String secs = formatSeconds(leftMs);
        int seconds = (int) (leftMs / 1000);
        int timeColor = seconds > 10 ? 0x55FF55 : (seconds > 3 ? 0xFFAA00 : 0xFF5555);

        int y = (int) (absoluteY + textRenderer.fontHeight * queue);
        int x = (int) (absoluteX + widthBasic + widthNameCol);
        String segPre = " - " + pre;
        context.drawTextWithShadow(textRenderer, segPre, x, y, 0xFFFFFF);
        x += textRenderer.getWidth(segPre);
        context.drawTextWithShadow(textRenderer, secs, x, y, timeColor);
        x += textRenderer.getWidth(secs);
        context.drawTextWithShadow(textRenderer, suf, x, y, 0xFFFFFF);
    }

    /** 该类型当前生效且未过期的条目；没有就返回 null——null 表示轮到下一状态。 */
    private static Powerup.ActivePowerup activeFor(Powerup.PowerupType type, long now) {
        for (Powerup.ActivePowerup a : new ArrayList<>(Powerup.activePowerups)) {
            if (a.getPowerupType() == type && a.getExpireMs() > now) {
                return a;
            }
        }
        return null;
    }

    /** 该类型本回合的预测条目；没有就 null——预测表里没有，说明这轮不是它的回合。 */
    private static Powerup incFor(Powerup.PowerupType type) {
        for (Powerup p : new ArrayList<>(Powerup.incPowerups)) {
            if (p.getPowerupType() == type) {
                return p;
            }
        }
        return null;
    }

    /** 场上存留盔甲架里该类型最接近消失的一条——给非预测类型当「在场倒计时」展示用。 */
    private static Powerup sittingFor(Powerup.PowerupType type) {
        Powerup best = null;
        for (Powerup p : new ArrayList<>(Powerup.powerups.values())) {
            if (p.getPowerupType() == type && (best == null || p.getOffsetTime() < best.getOffsetTime())) {
                best = p;
            }
        }
        return best;
    }

    /** 道具名走 lang 键（Powerup.keyFor），中英随客户端语言切换。 */
    private static String powerupName(Powerup.PowerupType type) {
        return Text.translatable(Powerup.keyFor(type)).getString();
    }

    /** 毫秒 → X.X 秒（1 位小数）；掉落时刻与生效剩余共用同一格式，HUD 两处读数口径一致。 */
    private static String formatSeconds(long ms) {
        int seconds = (int) (ms / 1000);
        int tenths = (int) ((ms % 1000) / 100);
        return seconds + "." + tenths;
    }
}
