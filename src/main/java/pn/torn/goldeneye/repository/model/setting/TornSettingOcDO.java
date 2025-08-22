package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置OC表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_oc", autoResultMap = true)
public class TornSettingOcDO extends BaseDO {
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
}