package cn.gsfy.nmz.client.data.model;

import java.util.List;

/**
 * Command detail for one Alien Arcadium (AA) round—one entry in
 * {@code aa_round_details.json}.
 *
 * <p>{@code AAAutoCommand} reads this to build the per-round prompt for
 * the "AA auto-command" HUD and chat output: recommended positions,
 * whether a Giant or an Old One spawns, and the danger level.
 */
public class AaRoundDetail {

    private final int round;
    /** Recommended positions, held by reference. The caller owns the
     *  list, its content, and its order. */
    private final List<String> recommendedPoints;
    private final boolean hasGiant;
    private final boolean hasOldOne;
    /** Round danger level, 1–5. Each level maps to a color in
     *  {@code AAAutoCommand}: green, dark green, yellow, red, purple. */
    private final int dangerLevel;

    /**
     * @param round round number
     * @param recommendedPoints recommended positions
     * @param hasGiant whether a Giant spawns this round
     * @param hasOldOne whether an Old One spawns this round
     * @param dangerLevel danger level; the constructor does not range-check it
     */
    public AaRoundDetail(int round, List<String> recommendedPoints, boolean hasGiant, boolean hasOldOne, int dangerLevel) {
        this.round = round;
        this.recommendedPoints = recommendedPoints;
        this.hasGiant = hasGiant;
        this.hasOldOne = hasOldOne;
        this.dangerLevel = dangerLevel;
    }

    /** Round number. */
    public int getRound() {
        return round;
    }

    /** Recommended positions. Returns the internal list, not a copy.
     *  May be {@code null}, and its content is still mutable. */
    public List<String> getRecommendedPoints() {
        return recommendedPoints;
    }

    /** Whether a Giant spawns this round. */
    public boolean hasGiant() {
        return hasGiant;
    }

    /** Whether an Old One spawns this round. */
    public boolean hasOldOne() {
        return hasOldOne;
    }

    /** Danger level, 1–5. */
    public int getDangerLevel() {
        return dangerLevel;
    }
}