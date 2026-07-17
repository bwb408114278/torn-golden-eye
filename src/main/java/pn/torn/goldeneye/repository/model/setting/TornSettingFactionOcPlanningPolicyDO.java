package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派OC新队规划策略配置。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_faction_oc_planning_policy")
public class TornSettingFactionOcPlanningPolicyDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID。
     */
    private Long factionId;
    /**
     * 岗位候选人评价模式。
     */
    private String evaluationMode;
    /**
     * 普通队规划需要保留的成员比例（百分比）。
     */
    private Integer normalPoolReservePercent;
    /**
     * 保守模式使用的安全刷新容量比例。
     */
    private Integer conservativeCapacityPercent;
    /**
     * 均衡模式使用的安全刷新容量比例。
     */
    private Integer balancedCapacityPercent;
    /**
     * 收益模式使用的安全刷新容量比例。
     */
    private Integer profitCapacityPercent;
}
