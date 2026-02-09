package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票变动
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.03
 */
@Data
public class StocksChangeDO {
    private Integer stocksId;
    private String stocksName;
    private String stocksShortname;

    // 最新数据
    private BigDecimal currentPrice;
    private Long currentTotalShares;
    private Long currentMarketCap;
    private LocalDateTime currentRegDateTime;

    // 上一次数据
    private BigDecimal previousPrice;
    private Long previousTotalShares;
    private Long previousMarketCap;
    private LocalDateTime previousRegDateTime;

    /**
     * 股数变化
     */
    private Long sharesDelta;
    /**
     * 净买入/卖出市值
     */
    private Long netTradeValue;
    private boolean isBuy;

    public void calculateNetTrade() {
        this.sharesDelta = currentTotalShares - previousTotalShares;
        // 净交易价值 = 股数变化 × 当前价格
        this.netTradeValue = currentPrice.multiply(new BigDecimal(sharesDelta)).longValue();
        this.isBuy = sharesDelta > 0;
    }
}