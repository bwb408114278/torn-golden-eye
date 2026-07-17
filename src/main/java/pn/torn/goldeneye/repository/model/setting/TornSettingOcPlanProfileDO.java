package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * OC规划档案配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_oc_plan_profile")
public class TornSettingOcPlanProfileDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * OC名称。
     */
    private String ocName;
    /**
     * OC等级。
     */
    private Integer rank;
    /**
     * 刷新池类型。
     */
    private String spawnPool;
    /**
     * 规划状态。
     */
    private String planStatus;
    /**
     * 自动规划使用的最低收益门槛。
     */
    private Long rewardFloor;
    /**
     * 收益评价所需的最小有效样本数。
     */
    private Integer minSampleSize;
}
