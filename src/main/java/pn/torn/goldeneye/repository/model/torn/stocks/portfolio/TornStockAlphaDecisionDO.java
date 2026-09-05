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
    private Long id;
    private LocalDate decisionBusinessDate;
    private Integer commonDayIndex;
    private Integer phase;
    private String decisionType;
    private Long currentBatchId;
    private Integer selectedStocksId;
    private String sourceSnapshotDigest;
    private LocalDateTime executionBarStartTime;
    private String executionStatus;
    private String failureReason;
    private Long rebalanceBatchId;
}
