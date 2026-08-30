package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * OC用户收益榜查询结果
 *
 * @author Bai
 * @version 1.5.2
 * @since 2025.09.10
 */
@Data
public class TornFactionOcBenefitUserRankDO {
    /**
     * 收益
     */
    private Long benefit;
    /**
     * 物品成本
     */
    private Long itemCost;
    /**
     * 收益归属帮派ID，即该月收益记录自身的帮派
     */
    private Long factionId;
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
}