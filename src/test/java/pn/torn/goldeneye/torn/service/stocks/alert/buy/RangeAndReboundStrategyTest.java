package pn.torn.goldeneye.torn.service.stocks.alert.buy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间下沿买入策略与严格反弹确认买入策略的边界测试，覆盖技术方案16.3中这两个策略的
 * 全部阈值边界、风格适配校验与质量分公式验证。两策略逻辑独立但均属于买入策略，
 * 合并于本测试类中分别以Nested内部类组织。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@DisplayName("区间下沿与严格反弹确认买入策略测试")
class RangeAndReboundStrategyTest {

    // ==================== 区间下沿买入策略测试 ====================

    @DisplayName("区间下沿买入策略测试")
    @Nested
    class RangeLowerBuyStrategyTest {

        private RangeLowerBuyStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new RangeLowerBuyStrategy();
        }

        @Test
        @DisplayName("matches_所有条件满足_返回true")
        void matches_所有条件满足_返回true() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_width30恰好8%_返回true")
        void matches_width30恰好8pct_返回true() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.08"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_width30略超8%_返回false")
        void matches_width30略超8pct_返回false() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.081"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_position30恰好10%_返回true")
        void matches_position30恰好10pct_返回true() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.10"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_position30略超10%_返回false")
        void matches_position30略超10pct_返回false() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.101"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_return6h恰好0_返回true")
        void matches_return6h恰好0_返回true() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("0"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_return6h大于0_返回false")
        void matches_return6h大于0_返回false() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("0.001"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_趋势保护不满足_返回false")
        void matches_趋势保护不满足_返回false() {
            // ma7d/ma30d - 1 = 978/1000 - 1 = -0.022 < -0.02
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("978"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_STEADY风格_返回false")
        void matches_STEADY风格_返回false() {
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.STEADY,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("calculateQualityScore_正确计算")
        void calculateQualityScore_正确计算() {
            // position30 = 0.05, zscore1d = -1.0 (NARROW不打折)
            // score = 80 + max(0, 0.10 - 0.05) × 100 + max(0, 1.0) × 5
            //       = 80 + 5 + 5 = 90
            BuyContext context = buildRangeContext(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"));
            BigDecimal score = strategy.calculateQualityScore(context);
            assertEquals(0, new BigDecimal("90").compareTo(score));
        }

        @Test
        @DisplayName("getStrategyType_返回区间下沿买入策略编码")
        void getStrategyType_返回区间下沿买入策略编码() {
            assertEquals(StockBuyStrategyEnum.RANGE_LOWER_BUY, strategy.getStrategyType());
        }
    }

    // ==================== 严格反弹确认买入策略测试 ====================

    @DisplayName("严格反弹确认买入策略测试")
    @Nested
    class StrictReboundConfirmBuyStrategyTest {

        private StrictReboundConfirmBuyStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new StrictReboundConfirmBuyStrategy();
        }

        @Test
        @DisplayName("matches_所有条件满足_返回true")
        void matches_所有条件满足_返回true() {
            // DECLINER, pctAbove30dLow=0.004, return1d=0.001, zscore1d=0.9, refPrice<=ma30d×1.002
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_距低点恰好0.5%_返回true")
        void matches_距低点恰好0p5_返回true() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.005"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_距低点略超0.5%_返回false")
        void matches_距低点略超0p5_返回false() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.0051"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_return1d恰好0_返回false")
        void matches_return1d恰好0_返回false() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_zscore1d恰好0.8_返回true")
        void matches_zscore1d恰好0p8_返回true() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.8"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertTrue(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_zscore1d略低于0.8_返回false")
        void matches_zscore1d略低于0p8_返回false() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.79"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_refPrice超过ma30d乘1.002_返回false")
        void matches_refPrice超过ma30d乘1p002_返回false() {
            // ma30d = 1000, 上限 = 1002, refPrice = 1002.01 超限
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1002.01"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("matches_STEADY风格_返回false")
        void matches_STEADY风格_返回false() {
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.STEADY,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            assertFalse(strategy.matches(context));
        }

        @Test
        @DisplayName("calculateQualityScore_正确计算")
        void calculateQualityScore_正确计算() {
            // zscore1d = 0.9, pctAbove30dLow = 0.004
            // score = 60 + 0.9 × 5 + max(0, 0.005 - 0.004) × 1000
            //       = 60 + 4.5 + 1 = 65.5
            BuyContext context = buildReboundContext(
                    StockStrategyFitEnum.DECLINER,
                    new BigDecimal("0.004"),
                    new BigDecimal("0.001"),
                    new BigDecimal("0.9"),
                    new BigDecimal("1000"), new BigDecimal("1000"));
            BigDecimal score = strategy.calculateQualityScore(context);
            assertEquals(0, new BigDecimal("65.5").compareTo(score));
        }

        @Test
        @DisplayName("getStrategyType_返回严格反弹确认策略编码")
        void getStrategyType_返回严格反弹确认策略编码() {
            assertEquals(StockBuyStrategyEnum.STRICT_REBOUND_CONFIRM_BUY, strategy.getStrategyType());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建区间下沿策略测试上下文，仅设置该策略需要的字段。
     *
     * @param style     策略适配风格
     * @param width30d  30日通道宽度
     * @param position30 当前仓位位置
     * @param zscore1d  近1日Z-score
     * @param return6h  近6小时收益率
     * @param ma7d      近7日移动均价
     * @param ma30d     近30日移动均价
     * @return 买入评估上下文
     */
    private static BuyContext buildRangeContext(StockStrategyFitEnum style,
                                                BigDecimal width30d,
                                                BigDecimal position30,
                                                BigDecimal zscore1d,
                                                BigDecimal return6h,
                                                BigDecimal ma7d,
                                                BigDecimal ma30d) {
        return new BuyContext(
                2002,
                "RANGE",
                new BigDecimal("500"),
                new BigDecimal("500"),
                ma7d,
                ma30d,
                zscore1d,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                return6h,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("490"),
                new BigDecimal("510"),
                width30d,
                position30,
                new BigDecimal("0.002"),
                new BigDecimal("0.95"),
                Boolean.TRUE,
                style,
                StockMaturityEnum.M3_SEASONED,
                StockRiskLevelEnum.NONE
        );
    }

    /**
     * 构建严格反弹确认策略测试上下文，仅设置该策略需要的字段。
     *
     * @param style          策略适配风格
     * @param pctAbove30dLow 距30日低点涨幅
     * @param return1d       近1日收益率
     * @param zscore1d       近1日Z-score
     * @param referencePrice 当前参考价格
     * @param ma30d          近30日移动均价
     * @return 买入评估上下文
     */
    private static BuyContext buildReboundContext(StockStrategyFitEnum style,
                                                  BigDecimal pctAbove30dLow,
                                                  BigDecimal return1d,
                                                  BigDecimal zscore1d,
                                                  BigDecimal referencePrice,
                                                  BigDecimal ma30d) {
        return new BuyContext(
                3003,
                "REBOUND",
                referencePrice,
                new BigDecimal("500"),
                new BigDecimal("500"),
                ma30d,
                zscore1d,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                return1d,
                BigDecimal.ZERO,
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
