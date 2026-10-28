package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn设置OC流程图节点表
 *
 * <p>存放OC每个判定节点的参与者与结局节点类型，是收益计算器的图结构来源。</p>
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_oc_flow_node", autoResultMap = true)
public class TornSettingOcFlowNodeDO extends BaseDO {
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
     * 节点编码
     */
    private String nodeCode;
    /**
     * 节点类型：CHECK / GOOD_END / BAD_END
     */
    private String nodeType;
    /**
     * 判定参与者岗位编码，逗号分隔；结局节点为空
     */
    private String participants;
    /**
     * 站点叙事文本，仅供维护对照
     */
    private String label;
    /**
     * 上游结构状态：COMPLETE / WIP
     */
    private String sourceStatus;
    /**
     * 与上游成功率计算器的最大偏差（概率，0~1）
     */
    private BigDecimal verifyMaxDelta;
    /**
     * 通过逐位校验的时间；为空表示近似可用、仍待人工复核
     */
    private LocalDateTime verifiedAt;
    /**
     * 好结局奖励（现金与道具折算）；坏结局为0，空表示未配置
     */
    private Long rewardValue;
}
