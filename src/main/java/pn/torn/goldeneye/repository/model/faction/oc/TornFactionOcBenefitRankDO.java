package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * OC收益榜查询结果
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.09.10
 */
@Data
public class TornFactionOcBenefitRankDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 总数
     */
    private Long benefit;
    /**
     * 道具成本
     */
    private Long itemCost;
}