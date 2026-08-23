package pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 深度均值回归买入策略边界测试，覆盖技术方案16.3中该策略的全部阈值边界、
 * NARROW风格Z值0.6折修正、风格适配校验与质量分公式验证。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@DisplayName("深度均值回归买入策略测试")
class DeepMeanReversionBuyStrategyTest {

    private DeepMeanReversionBuyStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DeepMeanReversionBuyStrategy();
    }

    // ==================== matches 边界测试 ====================

    @Test
    @DisplayName("matches_所有条件满足_返回true")
    void matches_allConditionsMet_returnsTrue() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-2.5"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertTrue(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_距低点恰好0.3%_返回true")
    void matches_lowDistanceExactlyThreshold_returnsTrue() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.003"),
                new BigDecimal("-2.5"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertTrue(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_距低点略超0.3%_返回false")
    void matches_lowDistanceSlightlyExceedsThreshold_returnsFalse() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.0031"),
                new BigDecimal("-2.5"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertFalse(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_effectiveZ1恰好负二_返回true")
    void matches_effectiveZ1ExactlyMinus2_returnsTrue() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-2.0"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertTrue(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_effectiveZ1略高于负二_返回false")
    void matches_effectiveZ1SlightlyAboveMinus2_returnsFalse() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-1.99"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertFalse(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_return7d恰好负1%_返回true")
    void matches_return7dExactlyMinus1pct_returnsTrue() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-2.5"),
                new BigDecimal("-0.01"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertTrue(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_return7d低于负1%_返回false")
    void matches_return7dBelowMinus1pct_returnsFalse() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-2.5"),
                new BigDecimal("-0.011"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertFalse(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_NARROW风格Z值打0.6折后不满足_返回false")
    void matches_narrowZ1DiscountNotMet_returnsFalse() {
        // rawZ1 = -3.0, effectiveZ1 = -3.0 × 0.6 = -1.8 > -2.0
        BuyContext context = buildContext(
                StockStrategyFitEnum.NARROW,
                new BigDecimal("0.002"),
                new BigDecimal("-3.0"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertFalse(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_NARROW风格Z值打0.6折后满足_返回true")
    void matches_narrowZ1DiscountMet_returnsTrue() {
        // rawZ1 = -4.0, effectiveZ1 = -4.0 × 0.6 = -2.4 <= -2.0
        BuyContext context = buildContext(
                StockStrategyFitEnum.NARROW,
                new BigDecimal("0.002"),
                new BigDecimal("-4.0"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertTrue(strategy.matches(context));
    }

    @Test
    @DisplayName("matches_STRONG风格_返回false")
    void matches_strongStyle_returnsFalse() {
        BuyContext context = buildContext(
                StockStrategyFitEnum.STRONG,
                new BigDecimal("0.002"),
                new BigDecimal("-2.5"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        assertFalse(strategy.matches(context));
    }

    // ==================== 质量分测试 ====================

    @Test
    @DisplayName("calculateQualityScore_正确计算")
    void calculateQualityScore_correctResult() {
        // effectiveZ1 = -2.5, pctAbove30dLow = 0.002
        // score = 100 + max(0, 2.5) × 10 + max(0, 0.003 - 0.002) × 1000
        //       = 100 + 25 + 1 = 126
        BuyContext context = buildContext(
                StockStrategyFitEnum.RANGING,
                new BigDecimal("0.002"),
                new BigDecimal("-2.5"),
                new BigDecimal("0"),
                new BigDecimal("998"), new BigDecimal("1000"));
        BigDecimal score = strategy.calculateQualityScore(context);
        assertEquals(0, new BigDecimal("126").compareTo(score));
    }

    // ==================== isApplicableStyle 测试 ====================

    @Test
    @DisplayName("isApplicableStyle_NARROW_RANGING_STEADY_返回true")
    void isApplicableStyle_narrowRangingSteady_returnsTrue() {
        assertTrue(strategy.isApplicableStyle(StockStrategyFitEnum.NARROW));
        assertTrue(strategy.isApplicableStyle(StockStrategyFitEnum.RANGING));
        assertTrue(strategy.isApplicableStyle(StockStrategyFitEnum.STEADY));
    }

    @Test
    @DisplayName("isApplicableStyle_WEAK_DECLINER_STRONG_返回false")
    void isApplicableStyle_weakDeclinerStrong_returnsFalse() {
        assertFalse(strategy.isApplicableStyle(StockStrategyFitEnum.WEAK));
        assertFalse(strategy.isApplicableStyle(StockStrategyFitEnum.DECLINER));
        assertFalse(strategy.isApplicableStyle(StockStrategyFitEnum.STRONG));
    }

    @Test
    @DisplayName("getStrategyType_返回深度均值回归策略编码")
    void getStrategyType_returnsDeepMeanReversionStrategyCode() {
        assertEquals(StockBuyStrategyEnum.DEEP_MEAN_REVERSION_BUY, strategy.getStrategyType());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建买入评估上下文，仅设置深度均值回归策略需要的字段，其余使用安全默认值。
     *
     * @param style          策略适配风格
     * @param pctAbove30dLow 距30日低点涨幅
     * @param zscore1d       近1日Z-score
     * @param return7d       近7日收益率
     * @param ma7d           近7日移动均价
     * @param ma30d          近30日移动均价
     * @return 买入评估上下文
     */
    private static BuyContext buildContext(StockStrategyFitEnum style,
                                           BigDecimal pctAbove30dLow,
                                           BigDecimal zscore1d,
                                           BigDecimal return7d,
                                           BigDecimal ma7d,
                                           BigDecimal ma30d) {
        return new BuyContext(
                1001,
                "TEST",
                new BigDecimal("500"),
                new BigDecimal("500"),
                ma7d,
                ma30d,
                zscore1d,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                return7d,
                BigDecimal.ZERO,
                new BigDecimal("490"),
                new BigDecimal("510"),
                new BigDecimal("0.04"),
                new BigDecimal("0.05"),
                pctAbove30dLow,
                new BigDecimal("0.95"),
                Boolean.TRUE,
                style,
                StockMaturityEnum.M3_SEASONED,
                StockRiskLevelEnum.NONE
        );
    }
}
