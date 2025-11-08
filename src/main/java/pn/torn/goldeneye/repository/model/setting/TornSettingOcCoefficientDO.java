package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;

/**
 * OC系数配置表
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_oc_coefficient", autoResultMap = true)
public class TornSettingOcCoefficientDO extends BaseDO {
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
     * 成功率下限（包含）
     */
    private Integer passRateMin;
    /**
     * 成功率上限（包含）
     */
    private Integer passRateMax;
    /**
     * 工时系数
     */
    private BigDecimal coefficient;
}