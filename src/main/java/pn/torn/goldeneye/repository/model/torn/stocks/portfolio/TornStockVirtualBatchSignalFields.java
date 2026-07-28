package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票虚拟批次信号字段转换对象。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@Data
public class TornStockVirtualBatchSignalFields {
    /** 信号参考价 */
    private BigDecimal signalReferencePrice;
    /** 信号时间 */
    private LocalDateTime signalTime;
    /** 风格契合度 */
    private String stylePrior;
    /** 成熟度 */
    private String styleMaturity;
    /** 风险等级 */
    private String riskLevel;
    /** 风格生效月份 */
    private LocalDate styleEffectiveMonth;
    /** 买入规则版本 */
    private String buyRuleVersion;
}
