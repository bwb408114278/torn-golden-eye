package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置OC流程图边表
 *
 * <p>存放判定节点向右的PASS/FAIL去向；目标已在生成期折叠到下一个判定或结局节点，
 * 因此运行时不需要处理叙事节点。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_oc_flow_edge", autoResultMap = true)
public class TornSettingOcFlowEdgeDO extends BaseDO {
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
     * 起点节点编码（判定节点）
     */
    private String nodeCode;
    /**
     * 分支：PASS / FAIL
     */
    private String branch;
    /**
     * 去向节点编码（判定或结局）
     */
    private String targetNodeCode;
}
