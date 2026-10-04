package pn.torn.goldeneye.torn.service.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockTradeActionEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockTradeAdvice;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider.ProvidedTradeFeature;
import pn.torn.goldeneye.torn.service.user.StockMonthlyStyleResolver.ResolvedMonthlyStyle;
import pn.torn.goldeneye.torn.service.user.StockTradeStrategyService.StockTradeAnalysis;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 私聊Stock分析策略服务单元测试(1.8.0: 15m特征+月度风格消费口径)。
 * <p>
 * 覆盖设计§3.6纯逻辑矩阵: 评分null安全(窗口指标为null不命中分支、不抛NPE)、
 * SWING_LOW_BUY买入门槛叠加(window/maturity/risk各+10)、月度强制HOLD场景
 * (无风格/特征陈旧/M0)与沿用留痕文案。评分阈值数值维持不变。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("私聊Stock分析策略服务测试")
class StockTradeStrategyServiceTest {

    private static final int STOCKS_ID = 1;
    private static final LocalDateTime ANALYSIS_TIME = LocalDateTime.of(2026, 10, 4, 10, 0);

    @Mock
    private Stock15mTradeFeatureProvider featureProvider;
    @Mock
    private StockMonthlyStyleResolver monthlyStyleResolver;

    @InjectMocks
    private StockTradeStrategyService service;

    @Test
    @DisplayName("门槛矩阵_评分58在STEADY(50)下BUY,任一+10惩罚均翻转为HOLD")
    void analyze_thresholdMatrix_singlePenaltyFlipsBuyToHold() {
        // 评分58: 距30日低点0.4%(+30) + z30=-1.6(+20) + z7=-1.0(+8);无罚分
        ProvidedTradeFeature feature = readyFeature(score58Point());
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(feature));

        // 正常(M3/NONE/就绪): 58>=50 → BUY
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M3_SEASONED, StockRiskLevelEnum.NONE)));
        assertEquals(StockTradeActionEnum.BUY, service.analyze(ANALYSIS_TIME, true)
                .advices().getFirst().action(), "无惩罚时58分应BUY");

        // M1_EARLY(+10) → 门槛60 → HOLD
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M1_EARLY, StockRiskLevelEnum.NONE)));
        assertFirstHolds("M1_EARLY+10应把58分压为HOLD");

        // HIGH(+10) → 门槛60 → HOLD
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M3_SEASONED, StockRiskLevelEnum.HIGH)));
        assertFirstHolds("HIGH+10应把58分压为HOLD");

        // 窗口未就绪(+10) → 门槛60 → HOLD
        when(featureProvider.loadLatestFeatures(any()))
                .thenReturn(List.of(new ProvidedTradeFeature(score58Point(), true, "INSUFFICIENT_HISTORY", false)));
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M3_SEASONED, StockRiskLevelEnum.NONE)));
        assertFirstHolds("窗口未就绪+10应把58分压为HOLD");
    }

    @Test
    @DisplayName("门槛矩阵_月度惩罚可叠加,评分65在M1+HIGH(70)下HOLD")
    void analyze_stackedMonthlyPenalties_holdAtScore65() {
        // 评分65: 距30日低点0.4%(+30) + z30=-1.6(+20) + z7=-1.6(+15)
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(readyFeature(score65Point())));
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M1_EARLY, StockRiskLevelEnum.HIGH)));

        StockTradeAdvice first = service.analyze(ANALYSIS_TIME, true).advices().getFirst();

        assertEquals(StockTradeActionEnum.HOLD, first.action(), "M1+HIGH叠加门槛70应把65分压为HOLD");
        String reasons = String.join("\n", first.reasons());
        assertTrue(reasons.contains("本月买入门槛"), "门槛加成必须合并为一行输出");
        assertTrue(reasons.contains("风险高 +10"), "HIGH必须在门槛合并行列出");
        assertTrue(reasons.contains("成熟度早期 +10"), "M1_EARLY必须在门槛合并行列出");
    }

    @Test
    @DisplayName("null安全_窗口指标全null且未就绪_不抛NPE且分支不命中")
    void analyze_nullWindowMetrics_noNpeAndBranchesNotHit() {
        StockStrategyFeaturePoint point = new StockStrategyFeaturePoint(
                STOCKS_ID, "TCS", ANALYSIS_TIME, new BigDecimal("100.00"),
                null, null, null, null, null, null,
                new BigDecimal("50"), null, null, null, null, null);
        when(featureProvider.loadLatestFeatures(any()))
                .thenReturn(List.of(new ProvidedTradeFeature(point, true, "INSUFFICIENT_HISTORY", false)));
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M4_MATURE, StockRiskLevelEnum.NONE)));

        StockTradeAdvice first = service.analyze(ANALYSIS_TIME, true).advices().getFirst();

        assertEquals(StockTradeActionEnum.HOLD, first.action(), "null指标不得命中任何买卖分支");
        assertNull(first.ma1d(), "null窗口指标必须原样透传null,不得填充0");
        assertTrue(String.join("\n", first.reasons()).contains("数据不足"),
                "窗口不足必须输出数据不足文案");
    }

    @Test
    @DisplayName("无可用月度风格_强制HOLD并产生顶部告警")
    void analyze_styleUnavailable_forcedHoldWithWarning() {
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(readyFeature(score65Point())));
        when(monthlyStyleResolver.resolveAll(any())).thenReturn(Map.of());

        StockTradeAnalysis analysis = service.analyze(ANALYSIS_TIME, true);

        assertEquals(StockTradeActionEnum.HOLD, analysis.advices().getFirst().action(), "无风格必须强制HOLD");
        assertTrue(String.join("\n", analysis.advices().getFirst().reasons()).contains("月度风格不可用"));
        assertFalse(analysis.warnings().isEmpty(), "无风格必须产生顶部告警文案");
    }

    @Test
    @DisplayName("特征陈旧_强制HOLD并标注原因")
    void analyze_staleFeature_forcedHoldWithReason() {
        when(featureProvider.loadLatestFeatures(any()))
                .thenReturn(List.of(new ProvidedTradeFeature(score65Point(), false, null, true)));
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M4_MATURE, StockRiskLevelEnum.NONE)));

        StockTradeAdvice first = service.analyze(ANALYSIS_TIME, true).advices().getFirst();

        assertEquals(StockTradeActionEnum.HOLD, first.action(), "特征陈旧必须强制HOLD");
        assertTrue(String.join("\n", first.reasons()).contains("特征陈旧"), "必须标注特征陈旧原因");
    }

    @Test
    @DisplayName("成熟度M0_全部信号强制HOLD(debug保留行)")
    void analyze_maturityUnmature_forcedHold() {
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(readyFeature(score65Point())));
        when(monthlyStyleResolver.resolveAll(any()))
                .thenReturn(Map.of(STOCKS_ID, style(StockPersonalityEnum.STEADY,
                        StockMaturityEnum.M0_UNMATURE, StockRiskLevelEnum.NONE)));

        StockTradeAdvice first = service.analyze(ANALYSIS_TIME, true).advices().getFirst();

        assertEquals(StockTradeActionEnum.HOLD, first.action(), "M0必须强制HOLD");
        assertTrue(String.join("\n", first.reasons()).contains("月度成熟度不足"), "必须标注成熟度不足原因");
    }

    @Test
    @DisplayName("沿用上月风格_留痕文案与月度摘要齐全")
    void analyze_carriedOverStyle_leaveTrailInReasons() {
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(readyFeature(score65Point())));
        ResolvedMonthlyStyle carried = new ResolvedMonthlyStyle(true, StockPersonalityEnum.RANGING,
                StockMaturityEnum.M3_SEASONED, StockRiskLevelEnum.MEDIUM,
                YearMonth.of(2026, 9), true, null);
        when(monthlyStyleResolver.resolveAll(any())).thenReturn(Map.of(STOCKS_ID, carried));

        StockTradeAdvice first = service.analyze(ANALYSIS_TIME, true).advices().getFirst();

        String reasons = String.join("\n", first.reasons());
        assertTrue(reasons.contains("使用 2026-09 风格（2026-10 未生成）"), "沿用必须留痕");
        assertTrue(reasons.contains("月度：风格=区间震荡"), "月度摘要必须包含中文风格");
        assertTrue(reasons.contains("风险=中等风险"), "月度摘要必须包含风险(仅展示)");
        assertFalse(reasons.contains("生效"), "月度摘要不得显示生效月份");
        assertFalse(reasons.chars().anyMatch(c -> Character.isLetter(c) && c < 128),
                "依据文案不得包含ASCII字母");
        assertFalse(reasons.contains("月度风险高"), "MEDIUM不得输出门槛加成文案");
    }

    @Test
    @DisplayName("非debug模式_过滤HOLD只保留动作建议")
    void analyze_nonDebug_filtersHoldAdvices() {
        when(featureProvider.loadLatestFeatures(any())).thenReturn(List.of(readyFeature(score65Point())));
        when(monthlyStyleResolver.resolveAll(any())).thenReturn(Map.of());

        StockTradeAnalysis analysis = service.analyze(ANALYSIS_TIME, false);

        assertTrue(analysis.advices().isEmpty(), "非debug下强制HOLD行应被过滤");
        assertFalse(analysis.warnings().isEmpty(), "告警在非debug下仍保留");
    }

    @Test
    @DisplayName("参考价展示_保留2位小数HALF_UP")
    void getBasePriceText_twoDecimalHalfUp() {
        StockTradeAdvice advice = new StockTradeAdvice(STOCKS_ID, "TCS", null, null, null,
                new BigDecimal("353.2"), null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, List.of());

        assertEquals("353.20", advice.getBasePriceText(), "参考价展示必须保留2位小数");
    }

    /**
     * 断言首个建议为HOLD(当前mock输入下)。
     *
     * @param message 失败信息
     */
    private void assertFirstHolds(String message) {
        assertEquals(StockTradeActionEnum.HOLD, service.analyze(ANALYSIS_TIME, true)
                .advices().getFirst().action(), message);
    }

    /**
     * 构建就绪特征(窗口指标由point携带)。
     *
     * @param point 特征点
     * @return 就绪特征
     */
    private static ProvidedTradeFeature readyFeature(StockStrategyFeaturePoint point) {
        return new ProvidedTradeFeature(point, false, null, false);
    }

    /**
     * 构建SWING_LOW_BUY评分58的特征点(30+20+8,无罚分,其余信号均低于阈值)。
     *
     * @return 特征点
     */
    private static StockStrategyFeaturePoint score58Point() {
        return new StockStrategyFeaturePoint(STOCKS_ID, "TCS", ANALYSIS_TIME,
                new BigDecimal("100.00"), bd("99.00"), bd("99.50"), bd("99.80"),
                null, bd("-1.00"), bd("-1.60"), new BigDecimal("50"),
                bd("0.010"), bd("0.000"), bd("0.000"), bd("0.004"), bd("-0.500"));
    }

    /**
     * 构建SWING_LOW_BUY评分65的特征点(30+20+15,无罚分)。
     *
     * @return 特征点
     */
    private static StockStrategyFeaturePoint score65Point() {
        return new StockStrategyFeaturePoint(STOCKS_ID, "TCS", ANALYSIS_TIME,
                new BigDecimal("100.00"), bd("99.00"), bd("99.50"), bd("99.80"),
                null, bd("-1.60"), bd("-1.60"), new BigDecimal("50"),
                bd("0.010"), bd("0.000"), bd("0.000"), bd("0.004"), bd("-0.500"));
    }

    /**
     * 构建可用月度风格。
     *
     * @param personality 评分档位
     * @param maturity    成熟度
     * @param risk        风险
     * @return 可用解析结果
     */
    private static ResolvedMonthlyStyle style(StockPersonalityEnum personality,
                                              StockMaturityEnum maturity,
                                              StockRiskLevelEnum risk) {
        return new ResolvedMonthlyStyle(true, personality, maturity, risk,
                YearMonth.of(2026, 10), false, null);
    }

    /**
     * 构建指定精度小数。
     *
     * @param value 文本值
     * @return BigDecimal
     */
    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
