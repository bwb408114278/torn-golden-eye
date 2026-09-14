package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派级大锅饭开关与收益模式配置。
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_oc_reassign_faction")
public class TornSettingOcReassignFactionDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID，同帮派至多一条有效记录。
     */
    private Long factionId;
    /**
     * 收益模式：COEFFICIENT（系数）/ EQUAL（平分）。
     */
    private String incomeMode;
    /**
     * 是否启用大锅饭。
     */
    private Boolean enabled;
}
