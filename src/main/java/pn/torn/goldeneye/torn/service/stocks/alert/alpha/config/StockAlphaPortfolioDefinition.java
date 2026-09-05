package pn.torn.goldeneye.torn.service.stocks.alert.alpha.config;

import java.math.BigDecimal;

/**
 * α策略组合定义。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
public final class StockAlphaPortfolioDefinition {
    public static final String PORTFOLIO_CODE = "VIP_ALPHA";
    public static final int SLOT_NO = 1;
    public static final BigDecimal LOGICAL_CAPITAL = new BigDecimal("10000000000");

    private StockAlphaPortfolioDefinition() {
    }
}
