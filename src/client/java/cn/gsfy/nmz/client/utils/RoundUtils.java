package cn.gsfy.nmz.client.utils;

import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.DifficultyId;
import cn.gsfy.nmz.client.data.model.MapId;

/**
 * Round predicate helpers—"is this round a boss round" has exactly one
 * source across the codebase.
 *
 * <p>Four consumers share it (the overview HUD's boss row, the RKPM
 * broadcast skip, boss-round field power-up recycling, the ESP boss
 * detection's round axis): the data lives in boss_rounds.json organized by
 * {@code map->difficulty->rounds}, with the difficulty from
 * {@link DifficultyUtils#getDifficulty()} (whose only source is the
 * scoreboard's Difficulty row). When the difficulty is unidentified (or the
 * map has no entry for that tier) it falls back to the normal tier—all four
 * maps use normal as the baseline, and AA has only the normal tier
 * anyway.
 */
public final class RoundUtils {

    /** Whether this round is a boss round; an unidentified map is treated as
     *  non-boss. */
    public static boolean isBossRound(MapId map, int round) {
        return isBossRound(map, round, DifficultyUtils.getDifficulty());
    }

    /**
     * Decides a boss round by a caller-supplied difficulty—one batch of
     * checks shares one tier, avoiding per-column re-reads. A {@code null} /
     * {@link MapId#NULL} map or a non-positive round returns false; a
     * {@code null} / {@link DifficultyId#NULL} difficulty or a missing tier
     * for the map falls back to the normal tier.
     *
     * @param map map id; may be {@code null}
     * @param round the round number starting at 1
     * @param difficulty difficulty id; may be {@code null}
     * @return true when the data table lists the round as a boss round
     */
    public static boolean isBossRound(MapId map, int round, DifficultyId difficulty) {
        if (map == null || map == MapId.NULL || round <= 0) {
            return false;
        }
        for (int r : DataManager.get().getBossRounds(map, difficulty)) {
            if (r == round) {
                return true;
            }
        }
        return false;
    }

    private RoundUtils() {
    }
}