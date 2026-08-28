package cn.gsfy.nmz.client.data.model;

import java.util.List;

/**
 * AA（外星游乐园）单回合指挥详情——对应 aa_round_details.json 里的一条。
 *
 * <p>供「外星游乐园自动指挥」HUD 与聊天输出用：推荐站位、巨人 / 上古者是否出没、
 * 危险等级都由 AaCommander 读它来组织每回合的提示。
 */
public class AaRoundDetail {

    private final int round;
    /** 推荐站位点（解析时已滤掉空串，按 #1/#2/#3 顺序展示）。 */
    private final List<String> recommendedSpots;
    private final boolean hasGiant;
    private final boolean hasOldOne;
    /** 回合危险等级（1~5，对应绿 / 黄 / 橙 / 红 / 紫五档颜色）。 */
    private final int dangerLevel;

    public AaRoundDetail(int round, List<String> recommendedSpots, boolean hasGiant, boolean hasOldOne, int dangerLevel) {
        this.round = round;
        this.recommendedSpots = recommendedSpots;
        this.hasGiant = hasGiant;
        this.hasOldOne = hasOldOne;
        this.dangerLevel = dangerLevel;
    }

    public int getRound() {
        return round;
    }

    public List<String> getRecommendedSpots() {
        return recommendedSpots;
    }

    public boolean hasGiant() {
        return hasGiant;
    }

    public boolean hasOldOne() {
        return hasOldOne;
    }

    public int getDangerLevel() {
        return dangerLevel;
    }
}
