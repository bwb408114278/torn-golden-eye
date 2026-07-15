package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * OC新队规划档案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_oc_plan_profile")
public class TornSettingOcPlanProfileDO extends BaseDO {
    private String ocName;
    private Integer rank;
    private String spawnPool;
    private String planStatus;
    private Long rewardFloor;
    private Integer minSampleSize;
}
