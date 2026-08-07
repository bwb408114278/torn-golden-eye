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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 区间下沿买入策略与严格反弹确认买入策略的边界测试，覆盖技术方案16.3中这两个策略的
 * 全部阈值边界、风格适配校验与质量分公式验证。两策略逻辑独立但均属于买入策略，
 * 合并于本测试类中分别以Nested内部类组织。
 *
 * @author Bai
 * @version 1.2.14
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
        void matches_allConditionsMet_returnsTrue() {
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
        void matches_width30Exactly8pct_returnsTrue() {
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
        void matches_width30SlightlyExceeds8pct_returnsFalse() {
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
        void matches_position30Exactly10pct_returnsTrue() {
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
        void matches_position30SlightlyExceeds10pct_returnsFalse() {
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
        void matches_return6hExactlyZero_returnsTrue() {
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
        void matches_return6hGreaterThanZero_returnsFalse() {
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
        void matches_trendProtectionNotMet_returnsFalse() {
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
        @DisplayName("绝对趋势守卫_return7d恰为-2%_通过")
        void guard_return7dEqualToFloor_passes() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    new BigDecimal("-0.02"));
            assertNull(strategy.absoluteTrendGuardFailureReason(context), "等于-2%应通过");
            assertTrue(strategy.matches(context), "结构条件与MA门禁应命中");
        }

        @Test
        @DisplayName("绝对趋势守卫_return7d略低于-2%_以ABSOLUTE_TREND_GUARD_FAILED拒绝")
        void guard_return7dBelowFloor_failed() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    new BigDecimal("-0.0201"));
            assertEquals(RangeLowerBuyStrategy.ABSOLUTE_TREND_GUARD_FAILED,
                    strategy.absoluteTrendGuardFailureReason(context), "略低于-2%应拒绝");
        }

        @Test
        @DisplayName("绝对趋势守卫_return7d略高于-2%_通过")
        void guard_return7dAboveFloor_passes() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    new BigDecimal("-0.01"));
            assertNull(strategy.absoluteTrendGuardFailureReason(context), "高于-2%应通过");
        }

        @Test
        @DisplayName("绝对趋势守卫_return7d缺失_数据不足DATA_INSUFFICIENT")
        void guard_return7dMissing_dataInsufficient() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    null);
            assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT,
                    strategy.absoluteTrendGuardFailureReason(context),
                    "return7d缺失必须记录为数据不足而非阈值失败");
        }

        @Test
        @DisplayName("绝对趋势守卫_MA7缺失_数据不足DATA_INSUFFICIENT且不伪装为普通不命中")
        void guard_ma7Missing_dataInsufficient() {
            BuyContext context = buildRangeContextWithMa7(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    null, new BigDecimal("1000"),
                    new BigDecimal("-0.01"));
            assertTrue(strategy.matches(context), "MA7缺失时结构条件满足应命中,由守卫判数据不足");
            assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT,
                    strategy.absoluteTrendGuardFailureReason(context),
                    "MA7缺失必须记录为数据不足");
        }

        @Test
        @DisplayName("绝对趋势守卫_MA30缺失_数据不足DATA_INSUFFICIENT且不伪装为普通不命中")
        void guard_ma30Missing_dataInsufficient() {
            BuyContext context = buildRangeContextWithMa7(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), null,
                    new BigDecimal("-0.01"));
            assertTrue(strategy.matches(context), "MA30缺失时结构条件满足应命中,由守卫判数据不足");
            assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT,
                    strategy.absoluteTrendGuardFailureReason(context),
                    "MA30缺失必须记录为数据不足");
        }

        @Test
        @DisplayName("绝对趋势守卫_MA7/MA30均缺失_数据不足DATA_INSUFFICIENT")
        void guard_maBothMissing_dataInsufficient() {
            BuyContext context = buildRangeContextWithMa7(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    null, null,
                    new BigDecimal("-0.01"));
            assertTrue(strategy.matches(context), "MA均缺失时结构条件满足应命中,由守卫判数据不足");
            assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT,
                    strategy.absoluteTrendGuardFailureReason(context),
                    "MA均缺失必须记录为数据不足");
        }

        @Test
        @DisplayName("matches_return7d低于-2%但结构条件满足_仍命中(守卫独立于matches)")
        void matches_return7dBelowFloorStructuralPasses_returnsTrue() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    new BigDecimal("-0.03"));
            assertTrue(strategy.matches(context), "matches不包含return7d守卫,结构条件满足即命中");
            assertEquals(RangeLowerBuyStrategy.ABSOLUTE_TREND_GUARD_FAILED,
                    strategy.absoluteTrendGuardFailureReason(context), "守卫应在资格层独立拒绝");
        }

        @Test
        @DisplayName("matches_MA门禁失败_return7d满足也返回false")
        void matches_maGateFails_returnsFalse() {
            // ma7d/ma30d - 1 = 978/1000 - 1 = -0.022 < -0.02,即使return7d满足也不命中
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("978"), new BigDecimal("1000"),
                    new BigDecimal("0"));
            assertFalse(strategy.matches(context), "MA门禁失败不应命中");
            assertNull(strategy.absoluteTrendGuardFailureReason(context), "return7d满足时守卫本身通过");
        }

        @Test
        @DisplayName("绝对趋势守卫_其他策略默认不设守卫_返回null")
        void guard_otherStrategies_defaultNull() {
            BuyContext context = buildRangeContextWithReturn7d(
                    StockStrategyFitEnum.RANGING,
                    new BigDecimal("0.04"),
                    new BigDecimal("0.05"),
                    new BigDecimal("-1.0"),
                    new BigDecimal("-0.01"),
                    new BigDecimal("998"), new BigDecimal("1000"),
                    new BigDecimal("-0.03"));
            assertNull(new DeepMeanReversionBuyStrategy().absoluteTrendGuardFailureReason(context),
                    "DEEP未设置RANGE守卫,不受该守卫影响");
            assertNull(new StrictReboundConfirmBuyStrategy().absoluteTrendGuardFailureReason(context),
                    "REBOUND未设置RANGE守卫,不受该守卫影响");
        }

        @Test
        @DisplayName("matches_STEADY风格_返回false")
        void matches_steadyStyle_returnsFalse() {
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
        void calculateQualityScore_correctResult() {
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
        void getStrategyType_returnsRangeLowerBuyStrategyCode() {
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
        void matches_allConditionsMet_returnsTrue() {
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
        void matches_lowDistanceExactlyThreshold_returnsTrue() {
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
        void matches_lowDistanceSlightlyExceedsThreshold_returnsFalse() {
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
        void matches_return1dExactlyZero_returnsFalse() {
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
        void matches_zscore1dExactly0p8_returnsTrue() {
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
        void matches_zscore1dSlightlyBelow0p8_returnsFalse() {
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
        void matches_refPriceExceedsMa30dTimes1p002_returnsFalse() {
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
        void matches_steadyStyle_returnsFalse() {
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
        void calculateQualityScore_correctResult() {
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
        void getStrategyType_returnsStrictReboundConfirmStrategyCode() {
            assertEquals(StockBuyStrategyEnum.STRICT_REBOUND_CONFIRM_BUY, strategy.getStrategyType());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建区间下沿策略测试上下文，仅设置该策略需要的字段。
     *
     * @param style      策略适配风格
     * @param width30d   30日通道宽度
     * @param position30 当前仓位位置
     * @param zscore1d   近1日Z-score
     * @param return6h   近6小时收益率
     * @param ma7d       近7日移动均价
     * @param ma30d      近30日移动均价
     * @return 买入评估上下文
     */
    private static BuyContext buildRangeContext(StockStrategyFitEnum style,
                                                BigDecimal width30d,
                                                BigDecimal position30,
                                                BigDecimal zscore1d,
                                                BigDecimal return6h,
                                                BigDecimal ma7d,
                                                BigDecimal ma30d) {
        return buildRangeContextWithReturn7d(style, width30d, position30, zscore1d, return6h,
                ma7d, ma30d, BigDecimal.ZERO);
    }

    /**
     * 构建区间下沿策略测试上下文(可指定return7d)。
     *
     * @param style      策略适配风格
     * @param width30d   30日通道宽度
     * @param position30 当前仓位位置
     * @param zscore1d   近1日Z-score
     * @param return6h   近6小时收益率
     * @param ma7d       近7日移动均价
     * @param ma30d      近30日移动均价
     * @param return7d   近7日收益率
     * @return 买入评估上下文
     */
    private static BuyContext buildRangeContextWithReturn7d(StockStrategyFitEnum style,
                                                            BigDecimal width30d,
                                                            BigDecimal position30,
                                                            BigDecimal zscore1d,
                                                            BigDecimal return6h,
                                                            BigDecimal ma7d,
                                                            BigDecimal ma30d,
                                                            BigDecimal return7d) {
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
                return7d,
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
     * 构建区间下沿策略测试上下文(可指定MA7/MA30是否缺失)。
     *
     * @param style      策略适配风格
     * @param width30d   30日通道宽度
     * @param position30 当前仓位位置
     * @param zscore1d   近1日Z-score
     * @param return6h   近6小时收益率
     * @param ma7d       近7日移动均价(可为null)
     * @param ma30d      近30日移动均价(可为null)
     * @param return7d   近7日收益率
     * @return 买入评估上下文
     */
    private static BuyContext buildRangeContextWithMa7(StockStrategyFitEnum style,
                                                       BigDecimal width30d,
                                                       BigDecimal position30,
                                                       BigDecimal zscore1d,
                                                       BigDecimal return6h,
                                                       BigDecimal ma7d,
                                                       BigDecimal ma30d,
                                                       BigDecimal return7d) {
        return buildRangeContextWithReturn7d(style, width30d, position30, zscore1d, return6h,
                ma7d, ma30d, return7d);
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
