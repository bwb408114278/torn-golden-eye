package pn.torn.goldeneye.torn.service.stocks.alert.alpha.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * α策略组合定义。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaPortfolioDefinition {
    /**
     * VIP α策略组合编码。
     */
    public static final String PORTFOLIO_CODE = "VIP_ALPHA";
    /**
     * α策略组合槽位编号。
     */
    public static final int SLOT_NO = 1;
    /**
     * α策略组合逻辑资金。
     */
    public static final BigDecimal LOGICAL_CAPITAL = new BigDecimal("10000000000");
}
