package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * α策略日线决策与phase消费记录。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_alpha_decision", autoResultMap = true)
public class TornStockAlphaDecisionDO extends BaseDO {
    /**
     * 主键。
     */
    private Long id;
    /**
     * 决策业务日期。
     */
    private LocalDate decisionBusinessDate;
    /**
     * 共同有效日序号。
     */
    private Integer commonDayIndex;
    /**
     * 消费阶段。
     */
    private Integer phase;
    /**
     * 决策类型。
     */
    private String decisionType;
    /**
     * 当前持仓批次ID。
     */
    private Long currentBatchId;
    /**
     * 目标股票ID。
     */
    private Integer selectedStocksId;
    /**
     * 来源快照摘要。
     */
    private String sourceSnapshotDigest;
    /**
     * 执行bar开始时间。
     */
    private LocalDateTime executionBarStartTime;
    /**
     * 执行状态。
     */
    private String executionStatus;
    /**
     * 失败原因。
     */
    private String failureReason;
    /**
     * 换仓批次ID。
     */
    private Long rebalanceBatchId;
}
