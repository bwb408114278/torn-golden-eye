package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Torn股票市场响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.24
 */
@Data
public class TornStocksMarketVO {
    /**
     * 当前价格
     */
    private BigDecimal price;
    /**
     * 市值
     */
    private long cap;
    /**
     * 总股数
     */
    private long shares;
    /**
     * 投资人数
     */
    private int investors;
}