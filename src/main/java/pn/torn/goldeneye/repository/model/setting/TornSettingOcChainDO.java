package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * OC链关系配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_setting_oc_chain")
public class TornSettingOcChainDO extends BaseDO {
    private String chainCode;
    private String parentOcName;
    private Integer parentRank;
    private String childOcName;
    private Integer childRank;
    private Integer sequenceNo;
    private Boolean enabled;
}
