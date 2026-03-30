package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 股票交易状态
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.16
 */
@Data
public class StocksTradeStatsDO {
    /**
     * 股票ID
     */
    private Integer stocksId;

    /**
     * 24小时买入值
     */
    private BigDecimal buyVolume24h;
    /**
     * 24小时卖出值
     */
    private BigDecimal sellVolume24h;
    /**
     * 24小时最低价
     */
    private BigDecimal minPrice24h;
    /**
     * 24小时最高价
     */
    private BigDecimal maxPrice24h;
    /**
     * 24小时平均价
     */
    private BigDecimal avgPrice24h;
    /**
     * 24小时买入均价
     */
    private BigDecimal avgBuyPrice24h;
    /**
     * 24小时卖出均价
     */
    private BigDecimal avgSellPrice24h;

    /**
     * 7天买入值
     */
    private BigDecimal buyVolume7d;
    /**
     * 7天卖出值
     */
    private BigDecimal sellVolume7d;
    /**
     * 7天最低价
     */
    private BigDecimal minPrice7d;
    /**
     * 7天最高价
     */
    private BigDecimal maxPrice7d;
    /**
     * 7天平均价
     */
    private BigDecimal avgPrice7d;
    /**
     * 7天买入均价
     */
    private BigDecimal avgBuyPrice7d;
    /**
     * 7天卖出均价
     */
    private BigDecimal avgSellPrice7d;
}