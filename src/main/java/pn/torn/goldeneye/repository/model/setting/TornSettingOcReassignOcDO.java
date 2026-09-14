package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * 帮派大锅饭OC范围行配置。
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_oc_reassign_oc")
public class TornSettingOcReassignOcDO extends BaseDO {
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
     * OC级别，冗余自目录便于审计，与链表口径一致。
     */
    private Integer rank;
    /**
     * 生效时间；为null表示历史所有月份均属大锅饭（原有名单语义）。
     */
    private LocalDateTime effectiveFrom;
    /**
     * 是否启用该范围行。
     */
    private Boolean enabled;
}
