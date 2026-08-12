package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票虚拟批次成交入场字段转换对象。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@Data
public class TornStockVirtualBatchEntryFields {
    /**
     * 入场参考价
     */
    private BigDecimal entryReferencePrice;
    /**
     * 入场时间
     */
    private LocalDateTime entryTime;
    /**
     * 成交股数
     */
    private Long quantity;
    /**
     * 投入金额
     */
    private BigDecimal investedCash;
    /**
     * 剩余金额
     */
    private BigDecimal remainingCash;
}
