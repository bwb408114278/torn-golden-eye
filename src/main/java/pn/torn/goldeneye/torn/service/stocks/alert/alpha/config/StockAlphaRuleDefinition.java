package pn.torn.goldeneye.torn.service.stocks.alert.alpha.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * α策略固定规则定义。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
public final class StockAlphaRuleDefinition {
    public static final String RULE_VERSION = "ALPHA_0.04_V1";
    public static final String STOCK_UNIVERSE_VERSION = "STOCKS_35_V1";
    public static final int MEMBER_COUNT = 35;
    public static final int WARMUP_COMMON_DAYS = 60;
    public static final int DECISION_INTERVAL_DAYS = 5;
    public static final int HYSTERESIS_TOP = 3;
    public static final BigDecimal R20_WEIGHT = new BigDecimal("0.96");
    public static final BigDecimal R1_WEIGHT = new BigDecimal("0.04");
    public static final int CALC_SCALE = 18;

    private StockAlphaRuleDefinition() {
    }

    /**
     * 返回固定升序股票成员。
     *
     * @return 35支股票ID
     */
    public static List<Integer> stockUniverse() {
        return java.util.stream.IntStream.rangeClosed(1, MEMBER_COUNT).boxed().toList();
    }
}
