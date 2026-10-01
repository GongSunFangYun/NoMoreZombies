package cn.gsfy.nmz.client.data.model;

/**
 * One powerup pattern, carried over from NEZ's PowerUpPatternData in the
 * shape this mod needs, so powerup detection can project later rounds.
 *
 * <p>{@code rounds} lists explicit rounds. Once those run out, {@code digits}
 * project the ones digit of later rounds: with {@code rounds {2,5,8,12,16}}
 * and {@code digits {1,6}}, the next rounds are 21/26/31/36…. {@code digits}
 * may be empty, meaning no projection.
 */
public class PowerupPattern {

    private final int[] rounds;
    private final int[] digits;

    /** Both arrays are held by reference, not copied. Only the parser and
     *  hand-written constants call this, and neither mutates them afterwards. */
    public PowerupPattern(int[] rounds, int[] digits) {
        this.rounds = rounds;
        this.digits = digits;
    }

    /** Explicit rounds. Returns the internal array, not a copy. */
    public int[] getRounds() {
        return rounds;
    }

    /** Projection digits. Returns the internal array, not a copy; its length
     *  may be 0. */
    public int[] getDigits() {
        return digits;
    }

    /**
     * Whether the given round matches this pattern. Explicit rounds are
     * matched item by item; past the largest of them, {@code round % 10}
     * is matched against {@code digits}. The arrays need no sorting, empty
     * {@code rounds} falls through to {@code digits}, and negative rounds
     * are not rejected. The semantics match {@code PowerupDetect.nextRound}.
     *
     * @param round round to test
     * @return true on an explicit-round hit or an extrapolated-digit hit
     */
    public boolean matchesRound(int round) {
        int max = Integer.MIN_VALUE;
        for (int r : rounds) {
            if (r == round) {
                return true;
            }
            if (r > max) {
                max = r;
            }
        }
        if (round < max || digits.length == 0) {
            return false;
        }
        int digit = round % 10;
        for (int d : digits) {
            if (d == digit) {
                return true;
            }
        }
        return false;
    }
}