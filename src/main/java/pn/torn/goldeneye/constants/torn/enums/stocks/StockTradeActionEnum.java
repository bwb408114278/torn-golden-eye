package pn.torn.goldeneye.constants.torn.enums.stocks;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 股票交易操作枚举
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
@Getter
@AllArgsConstructor
public enum StockTradeActionEnum {
    /**
     * 买入
     */
    BUY("买入"),
    /**
     * 卖出
     */
    SELL("卖出"),
    /**
     * 观望
     */
    HOLD("观望");

    private final String name;
}