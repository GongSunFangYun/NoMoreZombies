package cn.gsfy.nmz.client.features.recorder;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.GlobalConfig.RecordTiming;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.RoundUtils;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Round time recording (counterpart of the source TimeRecorder).
 *
 * <p>At each new round's start, the previous round's "duration + kills +
 * kills per minute (RKPM)" is turned into one line and sent to chat
 * (clickable to copy). The kill baseline advances every round (skipped rounds
 * included), and the broadcast's kills and duration strictly belong to the
 * same round. Duration prefers the total-time diff—the total time is
 * calibrated by the scoreboard's whole-second value and advanced by the local
 * wall clock between syncs, output at millisecond precision as MM:SS,mmm;
 * when not yet calibrated (first round / just rejoined) it falls back to the
 * local round timer gameTick. Kills read from the team stats / scoreboard
 * (not reset on rejoin)—exiting mid-game and rejoining does not corrupt the
 * count.
 */
public class TimeRecorder {

    /**
     * Called the moment a round-start title appears—at that point gameTick
     * has not reset and currentRound still holds the previous round.
     *
     * <p>Kill baseline and round number advance unconditionally at every
     * round boundary (skipped rounds included), so the broadcast's kills
     * strictly belong to "the round that just ended." Broadcast frequency /
     * skip decisions only decide whether a message is sent. Duration uses the
     * total-time snapshot diff (the scoreboard's whole-second value
     * calibrates; between syncs the local wall clock advances; GameEventBus
     * resets the base each round)—numerator and denominator always belong to
     * the same round, and RKPM cannot drift when a round is skipped.
     */
    public static void recordGameTime() {
        // Master switch for recording: off silences round stats (broadcast
        // frequency is chosen globally, one tier shared by all maps).
        if (!GlobalConfig.QoL.RECORD_ENABLED.getBooleanValue()) {
            return;
        }
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            return;
        }
        int currentRound = CheckSpawnTimes.get().getCurrentRound();
        if (currentRound <= 0) {
            return;
        }
        try {
            // Round duration prefers the total-time diff: the scoreboard's whole-second value
            // calibrates and the local wall clock advances between syncs
            // (GameTickHandler.getRoundElapsedFromTotal); only when not yet ready (first round
            // uncalibrated / rejoin before the next round title) does it fall back to the local
            // round timer gameTick. The advancing stage keeps millisecond precision; the
            // broadcast uses MM:SS,mmm and the RKPM denominator is not truncated to whole
            // seconds
            long authoritativeMs = GameTickHandler.get().getRoundElapsedFromTotal();
            long durationMs = authoritativeMs >= 0
                    ? authoritativeMs : GameTickHandler.get().getGameTick();

            // This round's kills = total kills when this round's end signal arrived - total
            // kills when the previous round's end signal arrived.
            // Total kills reads the team stats / scoreboard's server-authoritative value (not
            // reset on rejoin); otherwise exiting mid-game and rejoining would poison it to 0.
            int currentKills = TeamStats.getLocalKills();

            // Cross-game detection: a hit voids the kill baseline (-1), or the new game's first
            // round would take the previous game's cumulative kills as its baseline and compute
            // this round's kills as 0. Two conditions: (1) the round number strictly falls (a
            // new game restarts from low rounds); (2) the current cumulative kills are lower
            // than the baseline (in-game kills only increase; a drop means the game changed or
            // data was reset).
            // Use "strictly greater" rather than ">=": reconnecting to the same game may leave
            // the round number stale (still equal to the last recorded value), and if kills keep
            // growing the baseline should be kept for the diff, not misjudged as a new game.
            if (lastRecordedRound > currentRound || (currentKills >= 0 && currentKills < lastRoundKills)) {
                lastRoundKills = -1;
            }
            lastRecordedRound = currentRound;

            // Kills unavailable (local player has not entered the team stats table, e.g. the
            // scoreboard has not parsed after a rejoin): this round's data is incomplete, skip
            // the computation and the broadcast, and do not pollute the kill baseline (keep the
            // original baseline untouched).
            if (currentKills < 0) {
                return;
            }

            int roundKills;
            if (lastRoundKills >= 0) {
                roundKills = Math.max(0, currentKills - lastRoundKills);
            } else {
                roundKills = currentKills;
            }
            // The baseline advances every round (skipped rounds included), so the next round's
            // diff is a single-round quantity.
            lastRoundKills = currentKills;

            // Broadcast frequency and skip decisions only decide whether a message is sent;
            // they do not affect baseline advance. QUINTUPLE/TENFOLD hit every 5/10 rounds
            // starting at round 1, i.e. 1,6,11… / 1,11,21…, not multiples of 5/10.
            RecordTiming timing = (RecordTiming) GlobalConfig.Record.ROUNDS_RECORD.getOptionListValue();
            int increment = timing == RecordTiming.QUINTUPLE ? 5 : (timing == RecordTiming.TENFOLD ? 10 : 0);
            if (increment != 0 && currentRound % increment != 1) {
                return;
            }
            // The boss round reads the shared check (boss_rounds table + current difficulty,
            // same source as the overview HUD / powerup recycling).
            if (RoundUtils.isBossRound(map, currentRound)) {
                return;
            }

            String cleanTime = formatDuration(durationMs);
            // RKPM (kills per minute) = net kills * 60000 / round ms (prefers the total-time
            // diff, falls back to gameTick).
            double rkpm = durationMs > 0 ? (double) roundKills * 60000.0 / durationMs : 0.0;
            String rkpmStr = String.format(Locale.ROOT, "%.2f", rkpm);

            // Template placeholders are the same in zh/en: %1$d=kills, %2$s=duration,
            // %3$d=round, %4$s=RKPM.
            // The argument order is pinned (roundKills, cleanTime, currentRound, rkpmStr) - a
            // past bug swapped the first two: the placeholders landed on the wrong slots, and
            // the HUD read "killed 00:50 (duration) in 25 (kills)".
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

    /**
     * The cumulative-kills baseline when the previous round's end signal
     * arrived (team stats / scoreboard authoritative, not reset on rejoin).
     * -1 = no baseline (new game / the first round after joining mid-game),
     * in which case this round's kills use the full cumulative kills
     * directly.
     */
    private static int lastRoundKills = -1;
    /**
     * The last recorded round number: a round-number fall means a new game /
     * cross-game, so the kill baseline is voided, preventing the first round
     * from computing 0 off the previous game's baseline.
     */
    private static int lastRecordedRound = 0;

    /**
     * Milliseconds -> MM:SS,mmm (min:sec,millis); negative/0 returns
     * 00:00,000. A single round never reaches hours, so no hour segment.
     */
    private static String formatDuration(long ms) {
        long clamped = Math.max(0, ms);
        return String.format(Locale.ROOT, "%02d:%02d,%03d",
                clamped / 60_000 % 60, clamped / 1000 % 60, clamped % 1000);
    }

    private TimeRecorder() {
    }
}