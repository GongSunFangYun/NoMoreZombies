package cn.gsfy.nmz.client.features.powerups;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Power-up round prediction (the original PowerupPredict, NEZ-ified). Called
 * at round start; outputs a chat prediction.
 *
 * <p>The prediction does not run its own algorithm - it asks
 * {@link PowerupDetect#nextRound} directly: types with a committed pattern
 * (commit-once) get their next spawn round by "explicit rounds + ones-digit
 * extrapolation," still projecting into late rounds after the explicit table
 * is exhausted.
 *
 * <p>Only types with a committed pattern enter the output, one sentence per
 * type, joined with "and/."; all wording goes through lang keys, following
 * the client language automatically (same accounting as TimeRecorder).
 *
 * <p>The schedule fires in GameEventBus.handlePowerupsOnRound: 40 ticks (2
 * seconds) after a round starts, and only when {@code POWERUP_PREDICT} is on -
 * the delay gives the pattern commitment and this round's prediction entries
 * time to land, or the broadcast would be empty words.
 */
public class PowerupPredict {

    /**
     * Translation keys for the three predictable power-ups; order is pinned
     * to TYPES (Insta Kill/Max Ammo/Shopping Spree).
     */
    private static final String[] NAME_KEYS = {
            "nomorezombies.powerup.instaKill",
            "nomorezombies.powerup.maxAmmo",
            "nomorezombies.powerup.shoppingSpree"
    };
    private static final Formatting[] COLORS = {Formatting.RED, Formatting.BLUE, Formatting.DARK_PURPLE};
    private static final PowerupParser.PowerupType[] TYPES = {
            PowerupParser.PowerupType.INSTA_KILL,
            PowerupParser.PowerupType.MAX_AMMO,
            PowerupParser.PowerupType.SHOPPING_SPREE
    };

    /**
     * Builds the whole power-up round chat prediction at round start: only
     * types with a committed pattern broadcast, one sentence each; a type
     * spawning this round also reports the next one; no hits means silence.
     */
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
        for (PowerupParser.PowerupType t : TYPES) {
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
            PowerupParser.PowerupType type = TYPES[i];
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
                // Spawns this round: "Insta Kill this round (next round)" - the next one is
                // reported along the way, saving a separate query
                notice = Text.translatable("nomorezombies.msg.powerup.now")
                        .formatted(Formatting.GREEN, Formatting.BOLD);
                int further = detect.nextRound(type, round + 1);
                if (further > 0) {
                    notice = Text.empty().append(notice)
                            .append(Text.translatable("nomorezombies.msg.powerup.further", further)
                                    .formatted(Formatting.GRAY));
                }
            } else {
                // Spawns in a later round: "Insta Kill will spawn on round 7" - a heads-up for
                // pacing
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