package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OC收益汇总表
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("torn_faction_oc_income_summary")
public class TornFactionOcIncomeSummaryDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 年月
     */
    private String yearMonth;
    /**
     * 总有效工时
     */
    private BigDecimal totalEffectiveHours;
    /**
     * 总道具成本
     */
    private Long totalItemCost;
    /**
     * 总奖励
     */
    private Long totalReward;
    /**
     * 净收益
     */
    private Long netReward;
    /**
     * 最终收益
     */
    private Long finalIncome;
    /**
     * 参与OC数量
     */
    private Integer ocCount;
    /**
     * 成功OC数量
     */
    private Integer successOcCount;
    /**
     * 是否结算
     */
    private Boolean isSettled;
    /**
     * 结算时间
     */
    private LocalDateTime settledTime;
}