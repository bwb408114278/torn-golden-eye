package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * 按OC名称与等级聚合的实测产出统计。仅服务于推荐度量层的实测来源判定，不是实体表DO。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Data
public class OcIncomeYieldStatsDO {
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC等级
     */
    private Integer rank;
    /**
     * 历史实例数（按oc_id去重，成功与失败均计入）
     */
    private Integer attemptCount;
    /**
     * 成功实例数（按oc_id去重）
     */
    private Integer successCount;
    /**
     * 全部实例的净收益合计：成功实例的总奖励减去全部实例的道具成本
     */
    private Long netRewardSum;
}
