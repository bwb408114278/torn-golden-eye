package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派OC新队规划范围。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_faction_oc_plan")
public class TornSettingFactionOcPlanDO extends BaseDO {
    private Long factionId;
    private String ocName;
    private Integer rank;
    private Boolean enabled;
}
