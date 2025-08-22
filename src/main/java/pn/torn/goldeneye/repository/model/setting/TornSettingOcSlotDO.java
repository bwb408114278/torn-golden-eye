package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置OC岗位表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_oc_slot", autoResultMap = true)
public class TornSettingOcSlotDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC级别
     */
    private Integer rank;
    /**
     * 岗位编码
     */
    private String slotCode;
    /**
     * 岗位短编码
     */
    private String slotShortCode;
    /**
     * 成功率
     */
    private Integer passRate;
}