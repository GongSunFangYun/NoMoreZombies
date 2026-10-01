package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Locale;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Always-on time HUD—two rows, top-right by default: game duration
 * (accumulated across rounds) and round time (reset each round), gold label
 * + white time. Renders once inside a Zombies game, without waiting for the
 * round title; rejoining an in-progress game shows the same.
 * Position/scale go through the HUD editor (xGameTime/yGameTime/
 * scaleGameTime).
 *
 * <p>The two rows read from different sources: the game-duration row reads
 * the scoreboard's authoritative total (seconds precision, refreshed by the
 * 5-tick poll); the round-time row is pure local timing plus cache restore
 * (resumes from the breakpoint on rejoin).
 *
 * <p>Both rows <em>show seconds only</em>: the millisecond digit, whether
 * from local interpolation or the scoreboard, is inevitably jittered by
 * network latency (the local second boundary and the server's are out of
 * phase, and interpolation would periodically jump forward/back). Rather
 * than twitch, leave it out—the seconds contract is clean and the width is
 * constant (the integer digit flips once per second, no jitter).
 *
 * <p>At game end, gameOver clears the scoreboard, isInZombies stops being
 * true, so gameOver backstops it: the frozen final duration stays on screen
 * until the player leaves and it resets—clearing runs or wipes both need
 * this.
 */
public class TimeHudRenderer extends TotalHUDRenderer {

    /** The editor sample width: the wider of the two "label + H:MM:SS" rows
     *  + shadow overhang; the same accounting as
     *  {@code GlobalConfig.getXGameTime}'s default right-hug resolution
     *  (that also calls this function). */
    public static int hudWidth(net.minecraft.client.font.TextRenderer tr) {
        int game = tr.getWidth(Text.translatable("nomorezombies.timehud.game").getString())
                + tr.getWidth("00:12:34");
        int round = tr.getWidth(Text.translatable("nomorezombies.timehud.round").getString())
                + tr.getWidth("00:45:12");
        return Math.max(game, round) + TEXT_SHADOW;
    }

    /** The editor sample height: two text rows + the last row's shadow overhang. */
    public static int hudHeight(net.minecraft.client.font.TextRenderer tr) {
        return tr.fontHeight * 2 + TEXT_SHADOW;
    }

    /**
     * Draws two rows: game duration (accumulated across rounds) and this
     * round's time, gold label + white three-segment H:MM:SS; frozen at game
     * end. Gate = {@link GlobalConfig.Hud#gameTimeOn()} (master switch +
     * placed + this element's visibility).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        GameTickHandler g = GameTickHandler.get();
        // Renders once inside a Zombies game (isInZombies, including rejoining
        // an in-progress game); gameOver backstops it—at game end the
        // scoreboard is cleared, isInZombies stops being true, and the frozen
        // final duration must stay (clearing runs / wipes both need this),
        // hidden after leaving and gameOver resets.
        if (g == null || !(PlayerUtils.isInZombies() || g.isGameOver())
                || !GlobalConfig.Hud.gameTimeOn()) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_GAME_TIME.getDoubleValue();
        // Content width by the sample (format is always H:MM:SS, sample width);
        // height is two rows.
        int hudW = visibleSize(hudWidth(textRenderer), scale);
        int hudH = visibleSize(hudHeight(textRenderer), scale);
        int absoluteX = anchorPixels(GlobalConfig.getXGameTime(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYGameTime(), screenHeight, hudH, 0);

        drawScaled(context, absoluteX, absoluteY, scale, () -> {
            String gameLabel = Text.translatable("nomorezombies.timehud.game").getString();
            String roundLabel = Text.translatable("nomorezombies.timehud.round").getString();
            int lineHeight = textRenderer.fontHeight;

            int y = absoluteY;
            // Row 0: game duration—gold label 0xFFAA00, white value 0xFFFFFF,
            // reads the scoreboard's authoritative total.
            context.drawTextWithShadow(textRenderer, gameLabel, absoluteX, y, 0xFFAA00);
            context.drawTextWithShadow(textRenderer, formatClock(g.getTotalGameTick()),
                    absoluteX + textRenderer.getWidth(gameLabel), y, 0xFFFFFF);
            // Row 1: this round (seconds precision; game end—clear or wipe—
            // freezes at the current value).
            y += lineHeight;
            context.drawTextWithShadow(textRenderer, roundLabel, absoluteX, y, 0xFFAA00);
            context.drawTextWithShadow(textRenderer, formatClock(g.getGameTick()),
                    absoluteX + textRenderer.getWidth(roundLabel), y, 0xFFFFFF);
        });
    }

    /** ms → fixed clock format H:MM:SS (hour:min:sec, zero-padded, seconds
     *  precision); negative values clamped to 0.
     *  No millisecond digit: that one, whether from local interpolation or the
     *  scoreboard, is inevitably jittered by network latency; rather than
     *  twitch, leave it out. */
    static String formatClock(long ms) {
        long clamped = Math.max(0, ms);
        long hours = clamped / 3_600_000;
        int minutes = (int) (clamped / 60_000 % 60);
        int seconds = (int) (clamped / 1000 % 60);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }
}