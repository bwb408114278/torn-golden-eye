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
    /**
     * ID
     */
    private Long id;
    /**
     * OC链编码。
     */
    private String chainCode;
    /**
     * 前置OC名称。
     */
    private String parentOcName;
    /**
     * 前置OC等级。
     */
    private Integer parentRank;
    /**
     * 后继OC名称。
     */
    private String childOcName;
    /**
     * 后继OC等级。
     */
    private Integer childRank;
    /**
     * 链关系顺序号。
     */
    private Integer sequenceNo;
    /**
     * 是否启用该链关系。
     */
    private Boolean enabled;
}
