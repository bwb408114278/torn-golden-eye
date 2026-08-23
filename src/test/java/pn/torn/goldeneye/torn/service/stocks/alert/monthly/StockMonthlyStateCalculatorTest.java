package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 月度状态纯计算器单元测试 - 覆盖成熟度边界、六类分类首次命中、证据完整性fail-closed、
 * 风险投票与迟滞、NARROW↔RANGING迟滞及系统确认语义。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@DisplayName("月度状态纯计算器测试")
class StockMonthlyStateCalculatorTest {

    private final StockMonthlyStateCalculator calculator = new StockMonthlyStateCalculator();

    private static final int STOCKS_ID = 1001;
    private static final String SHORTNAME = "TEST";
    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    // ==================== 成熟度 ====================

    @Test
    @DisplayName("成熟度_ 不足60天M0")
    void maturity_below60Days_m0() {
        LocalDateTime end = LocalDateTime.of(2026, 3, 1, 10, 0);
        LocalDateTime start = end.minusDays(59);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockMaturityEnum.M0_UNMATURE, draft.maturity());
    }

    @Test
    @DisplayName("成熟度_ 60天M1边界")
    void maturity_60Days_m1() {
        LocalDateTime end = LocalDateTime.of(2026, 3, 1, 10, 0);
        LocalDateTime start = end.minusDays(60);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockMaturityEnum.M1_EARLY, draft.maturity());
    }

    @Test
    @DisplayName("成熟度_ 120天M2边界")
    void maturity_120Days_m2() {
        LocalDateTime end = LocalDateTime.of(2026, 5, 1, 10, 0);
        LocalDateTime start = end.minusDays(120);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockMaturityEnum.M2_PROVISIONAL, draft.maturity());
    }

    @Test
    @DisplayName("成熟度_ 240天M3边界")
    void maturity_240Days_m3() {
        LocalDateTime end = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime start = end.minusDays(240);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockMaturityEnum.M3_SEASONED, draft.maturity());
    }

    @Test
    @DisplayName("成熟度_ 365天M4边界")
    void maturity_365Days_m4() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockMaturityEnum.M4_MATURE, draft.maturity());
    }

    // ==================== 证据完整性 fail-closed ====================

    @Test
    @DisplayName("完整性_ 无可用bar_证据不完整DRAFT且风格风险为空")
    void completeness_noBars_incomplete() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, List.of(), null);
        assertFalse(draft.complete(), "无bar应判定证据不完整");
        assertEquals(StockMonthlyStateCalculator.REASON_MONTHLY_EVIDENCE_INCOMPLETE,
                draft.incompleteReason());
        assertNull(draft.strategyFitPrior(), "证据不完整不得默认STEADY");
        assertNull(draft.riskLevel(), "证据不完整不得默认NONE");
        assertFalse(draft.confirmable(), "证据不完整不得自动确认");
    }

    @Test
    @DisplayName("完整性_ 日收盘不足10天_证据不完整")
    void completeness_dailyCloseUnder10_incomplete() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(5);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.01);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertFalse(draft.complete(), "日收盘不足10天应判定证据不完整");
        assertNull(draft.strategyFitPrior(), "证据不完整不得默认STEADY");
    }

    // ==================== 六类分类 ====================

    @Test
    @DisplayName("分类_ 平稳上行趋势_STRONG首次命中")
    void classify_strongTrend_strong() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 2.0);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertTrue(draft.complete(), "365天连续上涨证据应完整");
        assertEquals(StockStrategyFitEnum.STRONG, draft.rawPersonality(),
                "全窗口翻倍上涨应判定STRONG");
        assertEquals(StockStrategyFitEnum.STRONG, draft.suggestedPersonality(),
                "无历史时建议风格等于原始风格");
        assertEquals(StockStrategyFitEnum.STRONG, draft.strategyFitPrior(),
                "无人工覆盖时最终风格等于建议风格");
        assertTrue(draft.confirmable(), "完整且无迟滞依赖时应可自动确认");
    }

    @Test
    @DisplayName("分类_ 持续下行_DECLINER首次命中")
    void classify_declinerTrend_decliner() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 2.0, 1.0);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockStrategyFitEnum.DECLINER, draft.rawPersonality(),
                "全窗口腰斩下行应判定DECLINER");
    }

    @Test
    @DisplayName("分类_ 窄幅震荡_NARROW")
    void classify_narrowBand_narrow() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.02);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockStrategyFitEnum.NARROW, draft.rawPersonality(),
                "2%窄幅且近零趋势应判定NARROW");
    }

    @Test
    @DisplayName("分类_ 中等区间震荡_RANGING")
    void classify_rangingBand_ranging() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        // 中幅震荡: 高低价差约7%,趋势接近零
        List<TornStockMarketBar15mDO> bars = buildSawtoothBars(start, end, 1.0, 0.965, 1.035);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockStrategyFitEnum.RANGING, draft.rawPersonality(),
                "约7%中幅震荡应判定RANGING");
    }

    // ==================== 风险与迟滞 ====================

    @Test
    @DisplayName("风险_ 下行趋势高风险HIGH立即生效")
    void risk_declinerTrend_high() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 2.0, 1.0);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertEquals(StockRiskLevelEnum.HIGH, draft.rawRiskLevel());
        assertEquals(StockRiskLevelEnum.HIGH, draft.riskLevel(), "HIGH应立即生效");
    }

    @Test
    @DisplayName("迟滞_ NARROW到RANGING_上一月raw非RANGING_保留NARROW")
    void hysteresis_narrowToRanging_prevRawNotRanging_keepNarrow() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        // 约5%带宽: RANGING(>4.5%)且不触发显著越界(<=5.5%)
        List<TornStockMarketBar15mDO> bars = buildSawtoothBars(start, end, 1.0, 0.975, 1.025);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.NONE,
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.NONE);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertEquals(StockStrategyFitEnum.RANGING, draft.rawPersonality(),
                "当月原始分类应为RANGING");
        assertEquals(StockStrategyFitEnum.NARROW, draft.suggestedPersonality(),
                "普通越界且上一月raw非RANGING时应保留NARROW");
        assertEquals("NARROW_RANGING_HOLD", draft.hysteresisReason());
    }

    @Test
    @DisplayName("迟滞_ NARROW到RANGING_上一月raw=RANGING_切换RANGING")
    void hysteresis_narrowToRanging_prevRawRanging_switch() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        // 约5%带宽: RANGING(>4.5%)且不触发显著越界(<=5.5%)
        List<TornStockMarketBar15mDO> bars = buildSawtoothBars(start, end, 1.0, 0.975, 1.025);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.NONE,
                StockStrategyFitEnum.RANGING, StockRiskLevelEnum.NONE);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertEquals(StockStrategyFitEnum.RANGING, draft.suggestedPersonality(),
                "连续两月raw均为RANGING时应切换RANGING");
        assertEquals("NARROW_RANGING_TWO_MONTH", draft.hysteresisReason());
    }

    @Test
    @DisplayName("迟滞_ 需要两月判断但历史缺raw字段_fail-closed不可确认")
    void hysteresis_previousRawMissing_notConfirmable() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        // 约5%带宽: RANGING(>4.5%)且不触发显著越界(<=5.5%)
        List<TornStockMarketBar15mDO> bars = buildSawtoothBars(start, end, 1.0, 0.975, 1.025);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.NONE,
                null, null);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertFalse(draft.confirmable(), "历史缺raw字段不得自动确认");
        assertEquals(StockMonthlyStateCalculator.REASON_PREVIOUS_RAW_MISSING,
                draft.hysteresisReason());
    }

    @Test
    @DisplayName("迟滞_ 上一月DECLINER当月STRONG_恢复需两月_保持DECLINER")
    void hysteresis_declinerToStrong_recoveryTwoMonth_keepPrevious() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 2.0);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.DECLINER, StockRiskLevelEnum.HIGH,
                StockStrategyFitEnum.WEAK, StockRiskLevelEnum.HIGH);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertEquals(StockStrategyFitEnum.STRONG, draft.rawPersonality());
        assertEquals(StockStrategyFitEnum.DECLINER, draft.suggestedPersonality(),
                "从DECLINER恢复STRONG需连续两月,当月不切换");
    }

    @Test
    @DisplayName("风险_ 上一月HIGH当月raw NONE_上一月raw非NONE_保持HIGH")
    void risk_previousHighCurrentNone_prevRawNotNone_keepHigh() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.02);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.HIGH,
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.MEDIUM);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertEquals(StockRiskLevelEnum.HIGH, draft.riskLevel(),
                "上一月HIGH且上一月raw非NONE时应保持HIGH");
    }

    @Test
    @DisplayName("风险_ 上一月HIGH当月raw NONE_上一月raw=NONE_解除为NONE")
    void risk_previousHighCurrentNone_prevRawNone_clearToNone() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 1.02);
        StockMonthlyPrevious previous = new StockMonthlyPrevious(
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.HIGH,
                StockStrategyFitEnum.NARROW, StockRiskLevelEnum.NONE);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, previous);
        assertEquals(StockRiskLevelEnum.NONE, draft.riskLevel(),
                "上一月raw NONE且当月raw NONE时应解除风险");
    }

    // ==================== 快照 ====================

    @Test
    @DisplayName("快照_ 完整草稿包含raw值、投票明细与迟滞原因")
    void snapshot_completeDraft_containsRawAndVotes() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime start = end.minusDays(365);
        List<TornStockMarketBar15mDO> bars = buildBars(start, end, 1.0, 2.0);
        StockMonthlyStateDraft draft = calculator.calculate(
                STOCKS_ID, SHORTNAME, MONTH, start, end, bars, null);
        assertNotNull(draft.metricSnapshot());
        assertTrue(draft.metricSnapshot().contains("rawPersonality"));
        assertTrue(draft.metricSnapshot().contains("rawRiskLevel"));
        assertTrue(draft.metricSnapshot().contains("highVotes"));
        assertTrue(draft.metricSnapshot().contains("mediumVotes"));
        assertTrue(draft.metricSnapshot().contains("hysteresisReason"));
        assertTrue(draft.metricSnapshot().contains("usableBarCoverage"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成连续15分钟可用bar: 从start到end(含)每15分钟一个bar,价格按日序号线性过渡。
     *
     * @param start      起点
     * @param end        终点
     * @param firstPrice 首日价格
     * @param lastPrice  末日价格
     * @return 连续bar列表(按时间升序)
     */
    private List<TornStockMarketBar15mDO> buildBars(LocalDateTime start, LocalDateTime end,
                                                    double firstPrice, double lastPrice) {
        long expectedBuckets = java.time.Duration.between(start, end).toMinutes() / 15 + 1;
        if (expectedBuckets <= 0) {
            return List.of();
        }
        long totalDays = Math.max(1, java.time.Duration.between(start, end).toDays());
        List<TornStockMarketBar15mDO> bars = new ArrayList<>((int) expectedBuckets);
        for (long i = 0; i < expectedBuckets; i++) {
            double dayRatio = (double) (i / 96) / totalDays;
            double price = firstPrice + (lastPrice - firstPrice) * dayRatio;
            bars.add(buildBar(start.plusMinutes(15 * i), price));
        }
        return bars;
    }

    /**
     * 生成连续15分钟锯齿bar: 每6天为一周期,前3天高价、后3天低价,价格在base上下震荡。
     *
     * @param start     起点
     * @param end       终点
     * @param base      基准价
     * @param lowRatio  低价比例(相对base)
     * @param highRatio 高价比例(相对base)
     * @return 连续bar列表(按时间升序)
     */
    private List<TornStockMarketBar15mDO> buildSawtoothBars(LocalDateTime start, LocalDateTime end,
                                                            double base, double lowRatio, double highRatio) {
        long expectedBuckets = java.time.Duration.between(start, end).toMinutes() / 15 + 1;
        if (expectedBuckets <= 0) {
            return List.of();
        }
        List<TornStockMarketBar15mDO> bars = new ArrayList<>((int) expectedBuckets);
        for (long i = 0; i < expectedBuckets; i++) {
            long dayIndex = i / 96;
            boolean high = dayIndex % 6 < 3;
            double price = base * (high ? highRatio : lowRatio);
            bars.add(buildBar(start.plusMinutes(15 * i), price));
        }
        return bars;
    }

    /**
     * 构建单个可用bar。
     *
     * @param barStartTime bar开始时间
     * @param price        lastPrice
     * @return 可用bar
     */
    private TornStockMarketBar15mDO buildBar(LocalDateTime barStartTime, double price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(STOCKS_ID);
        bar.setBarStartTime(barStartTime);
        bar.setBarEndTime(barStartTime.plusMinutes(15));
        bar.setLastSampleTime(barStartTime.plusMinutes(14));
        bar.setSampleCount(12);
        bar.setLastPrice(BigDecimal.valueOf(price));
        bar.setUsable(true);
        return bar;
    }
}
