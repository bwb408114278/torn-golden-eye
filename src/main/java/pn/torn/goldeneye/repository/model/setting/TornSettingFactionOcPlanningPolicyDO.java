package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派OC新队规划策略。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_faction_oc_planning_policy")
public class TornSettingFactionOcPlanningPolicyDO extends BaseDO {
    private Long factionId;
    private String evaluationMode;
    private Integer normalPoolReservePercent;
}
