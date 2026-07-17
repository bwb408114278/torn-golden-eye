package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派OC规划范围配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_faction_oc_plan")
public class TornSettingFactionOcPlanDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID。
     */
    private Long factionId;
    /**
     * OC名称。
     */
    private String ocName;
    /**
     * OC等级。
     */
    private Integer rank;
    /**
     * 是否允许该帮派自动规划此OC。
     */
    private Boolean enabled;
}
