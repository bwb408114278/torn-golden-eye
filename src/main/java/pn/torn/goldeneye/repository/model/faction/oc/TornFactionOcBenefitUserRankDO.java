package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * OC用户收益榜查询结果
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.10
 */
@Data
public class TornFactionOcBenefitUserRankDO {
    /**
     * 收益
     */
    private Long benefit;
    /**
     * 同期标识
     */
    private String cohort;
    /**
     * 总排名
     */
    private Long overallRank;
    /**
     * 帮派内排名
     */
    private Long factionRank;
    /**
     * 同期排名
     */
    private Long cohortRank;
    /**
     * 上一名用户ID
     */
    private Long prevUserId;
    /**
     * 上一名收益
     */
    private Long prevBenefit;
    /**
     * 同期总人数
     */
    private Long cohortUsers;

    /**
     * 是否有上一名
     */
    public boolean hasPrev() {
        return prevUserId != null;
    }

    /**
     * 与上一名的差距
     */
    public Long gapWithPrev() {
        return hasPrev() ? prevBenefit - benefit : null;
    }
}