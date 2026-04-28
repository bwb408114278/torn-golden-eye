package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置帮派OC岗位表
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.27
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_faction_oc_slot", autoResultMap = true)
public class TornSettingFactionOcSlotDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
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
     * 岗位编码
     */
    private String slotCode;
    /**
     * 成功率要求
     */
    private Integer passRate;
}