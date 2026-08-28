package cn.gsfy.nmz.client.data.model;

/**
 * 一条道具规律——NEZ PowerUpPatternData 迁移到本 mod 的形态，供道具检测推演后续轮次。
 *
 * <p>{@code rounds} 是显式轮次（升序）；显式表用尽后，用 {@code digits} 推演后续轮次的个位数字：
 * 如 rounds {2,5,8,12,16} + digits {1,6} → 之后 21/26/31/36…。digits 可为空（表示不推演）。
 */
public class PowerupPattern {

    private final int[] rounds;
    private final int[] digits;

    public PowerupPattern(int[] rounds, int[] digits) {
        this.rounds = rounds;
        this.digits = digits;
    }

    public int[] getRounds() {
        return rounds;
    }

    public int[] getDigits() {
        return digits;
    }
}
