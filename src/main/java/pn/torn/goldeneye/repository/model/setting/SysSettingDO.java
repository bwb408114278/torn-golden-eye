package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 系统设置表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "sys_setting", autoResultMap = true)
@NoArgsConstructor
public class SysSettingDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 设置Key
     */
    private String settingKey;
    /**
     * 设置值
     */
    private String settingValue;

    public SysSettingDO(String settingKey, String settingValue) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
    }
}