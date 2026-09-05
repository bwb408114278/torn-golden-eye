package pn.torn.goldeneye.torn.service.stocks.alert.alpha.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * α策略固定规则定义。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaRuleDefinition {
    /**
     * α规则版本。
     */
    public static final String RULE_VERSION = "ALPHA_0.04_V1";
    /**
     * 股票池版本。
     */
    public static final String STOCK_UNIVERSE_VERSION = "STOCKS_35_V1";
    /**
     * 股票池成员数量。
     */
    public static final int MEMBER_COUNT = 35;
    /**
     * 预热所需共同有效日数量。
     */
    public static final int WARMUP_COMMON_DAYS = 60;
    /**
     * 决策间隔天数。
     */
    public static final int DECISION_INTERVAL_DAYS = 5;
    /**
     * 持仓保持的最高排名范围。
     */
    public static final int HYSTERESIS_TOP = 3;
    /**
     * 20日收益权重。
     */
    public static final BigDecimal R20_WEIGHT = new BigDecimal("0.96");
    /**
     * 1日收益权重。
     */
    public static final BigDecimal R1_WEIGHT = new BigDecimal("0.04");
    /**
     * 收益和排名计算精度。
     */
    public static final int CALC_SCALE = 18;


    /**
     * 返回固定升序股票成员。
     *
     * @return 35支股票ID
     */
    public static List<Integer> stockUniverse() {
        return java.util.stream.IntStream.rangeClosed(1, MEMBER_COUNT).boxed().toList();
    }
}
