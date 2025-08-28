package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置帮派表
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_faction", autoResultMap = true)
public class TornSettingFactionDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派名称
     */
    private String factionName;
    /**
     * 帮派简称
     */
    private Integer factionShortName;
}