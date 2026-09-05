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
    private Long id;
    private Integer stocksId;
    private LocalDate businessDate;
    private BigDecimal closePrice;
    private Long sourceBarId;
    private LocalDateTime sourceBarStartTime;
    private String stockUniverseVersion;
    private String alphaRuleVersion;
    private BigDecimal r20;
    private BigDecimal r1;
    private BigDecimal r20Rank;
    private BigDecimal r1Rank;
    private BigDecimal r20Normalized;
    private BigDecimal r1Normalized;
    private BigDecimal alphaScore;
    private Integer rankPosition;
    private Boolean commonValid;
}
