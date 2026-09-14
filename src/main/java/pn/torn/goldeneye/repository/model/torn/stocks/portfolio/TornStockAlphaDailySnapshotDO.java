package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * α策略日线收盘、排名与来源快照。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_alpha_daily_snapshot", autoResultMap = true)
public class TornStockAlphaDailySnapshotDO extends BaseDO {
    /**
     * 主键。
     */
    private Long id;
    /**
     * 股票ID。
     */
    private Integer stocksId;
    /**
     * 业务日期。
     */
    private LocalDate businessDate;
    /**
     * 收盘价。
     */
    private BigDecimal closePrice;
    /**
     * 来源bar主键。
     */
    private Long sourceBarId;
    /**
     * 来源bar开始时间。
     */
    private LocalDateTime sourceBarStartTime;
    /**
     * 股票池版本。
     */
    private String stockUniverseVersion;
    /**
     * α规则版本。
     */
    private String alphaRuleVersion;
    /**
     * 20日收益。
     */
    private BigDecimal r20;
    /**
     * 1日收益。
     */
    private BigDecimal r1;
    /**
     * 20日原始排名。
     */
    private BigDecimal r20Rank;
    /**
     * 1日原始排名。
     */
    private BigDecimal r1Rank;
    /**
     * 20日归一化排名。
     */
    private BigDecimal r20Normalized;
    /**
     * 1日归一化排名。
     */
    private BigDecimal r1Normalized;
    /**
     * α综合评分。
     */
    private BigDecimal alphaScore;
    /**
     * 最终名次。
     */
    private Integer rankPosition;
    /**
     * 是否为共同有效数据。
     */
    private Boolean commonValid;
}
