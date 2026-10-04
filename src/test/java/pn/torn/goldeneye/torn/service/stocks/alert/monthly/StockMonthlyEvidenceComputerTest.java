package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 月度证据月均计算领域测试 - 保护P0-2修复: 完整自然月均价必须按全部可用15分钟bar的
 * lastPrice算术平均计算,而非日末价降采样;空/非正价格不得参与;月变化恰为0不计负月。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@DisplayName("月度证据月均计算测试")
class StockMonthlyEvidenceComputerTest {

    /**
     * 证据窗口起点(含一月与二月两个完整自然月)。
     */
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    /**
     * 证据窗口终点(三月1日零点,保证一月/二月完整覆盖)。
     */
    private static final LocalDateTime END = LocalDateTime.of(2026, 3, 1, 0, 0);

    @Test
    @DisplayName("月均_日内多价且日末价偏离日内均值_使用全部15m bar而非日末价")
    void monthMean_usesAll15mBarsNotDailyClose() {
        // 一月: 每日08:00=90、12:00=100,日末价100,日内均值95
        // 二月: 每日08:00=96、12:00=96,日末价96,日内均值96
        // 全bar月均变化 = 96/95-1 > 0;若按日末价则 = 96/100-1 < 0
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.addAll(buildDayBars(YearMonth.of(2026, 1), 90, 100));
        bars.addAll(buildDayBars(YearMonth.of(2026, 2), 96, 96));

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, END, bars);

        assertEquals(0.0, metrics.negativeMonthRatio(), "全bar月均应为正变化,负月占比0");
        assertEquals(0, metrics.negativeMonthStreak(), "全bar月均不应存在负月连续");
        assertEquals(2, metrics.completeMonthCount(), "窗口内应有1月与2月两个完整自然月");
    }

    @Test
    @DisplayName("月均_非正价格不参与月均_排除后月变化为负")
    void monthMean_nonPositivePriceExcluded() {
        // 一月: 每日08:00=90、11:00=0(非正,排除)、12:00=100,有效日内均值95;若计入0则均值63.33
        // 二月: 每日08:00=94、12:00=94,均值94
        // 排除非正价: 94/95-1 < 0 -> 负月;若错误计入0: 94/63.33-1 > 0 -> 非负月
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        YearMonth january = YearMonth.of(2026, 1);
        for (int d = 1; d <= january.lengthOfMonth(); d++) {
            LocalDate day = january.atDay(d);
            bars.add(buildBar(day.atTime(8, 0), 90));
            bars.add(buildBar(day.atTime(11, 0), 0));
            bars.add(buildBar(day.atTime(12, 0), 100));
        }
        bars.addAll(buildDayBars(YearMonth.of(2026, 2), 94, 94));

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, END, bars);

        assertEquals(1.0, metrics.negativeMonthRatio(), "排除非正价后二月较一月应为负月");
        assertEquals(1, metrics.negativeMonthStreak(), "末尾应存在1个连续负月");
    }

    @Test
    @DisplayName("月均_只有一个完整自然月_无月变化时占比为空连续为0")
    void monthMean_singleCompleteMonth_ratioNull() {
        LocalDateTime end = LocalDateTime.of(2026, 2, 1, 0, 0);
        List<TornStockMarketBar15mDO> bars = buildDayBars(YearMonth.of(2026, 1), 100, 100);

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, end, bars);

        assertNull(metrics.negativeMonthRatio(), "仅一个完整月不应产生月变化占比");
        assertEquals(0, metrics.negativeMonthStreak(), "仅一个完整月连续负月为0");
        assertEquals(1, metrics.completeMonthCount(), "窗口内应只有1个完整自然月");
    }

    @Test
    @DisplayName("月均_月内无可用价格_该月被跳过不参与相邻变化")
    void monthMean_monthWithoutPrices_skipped() {
        // 二月bar全部为空价格,该月无均值,不得生成占位月变化
        List<TornStockMarketBar15mDO> bars = new ArrayList<>(
                buildDayBars(YearMonth.of(2026, 1), 100, 100));
        YearMonth february = YearMonth.of(2026, 2);
        for (int d = 1; d <= february.lengthOfMonth(); d++) {
            TornStockMarketBar15mDO bar = buildBar(february.atDay(d).atTime(8, 0), 100);
            bar.setLastPrice(null);
            bars.add(bar);
        }

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, END, bars);

        assertNull(metrics.negativeMonthRatio(), "无有效价格的月份不得生成月变化");
        assertEquals(0, metrics.negativeMonthStreak(), "跳过无价格月份后连续负月为0");
    }

    @Test
    @DisplayName("月均_月变化恰为0_不计负月且中断连续")
    void monthMean_changeExactlyZero_notNegative() {
        // 一月与二月均价均为95,月变化=0
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.addAll(buildDayBars(YearMonth.of(2026, 1), 95, 95));
        bars.addAll(buildDayBars(YearMonth.of(2026, 2), 95, 95));

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, END, bars);

        assertEquals(0.0, metrics.negativeMonthRatio(), "月变化恰为0不计负月");
        assertEquals(0, metrics.negativeMonthStreak(), "月变化恰为0应中断连续负月");
    }

    @Test
    @DisplayName("月均_连续三个月负变化_末尾连续为3")
    void monthMean_threeNegativeMonths_streak3() {
        // 一月到四月均价依次100/99/98/97,三次月变化均为负
        LocalDateTime end = LocalDateTime.of(2026, 5, 1, 0, 0);
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.addAll(buildDayBars(YearMonth.of(2026, 1), 100, 100));
        bars.addAll(buildDayBars(YearMonth.of(2026, 2), 99, 99));
        bars.addAll(buildDayBars(YearMonth.of(2026, 3), 98, 98));
        bars.addAll(buildDayBars(YearMonth.of(2026, 4), 97, 97));

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(START, end, bars);

        assertEquals(1.0, metrics.negativeMonthRatio(), "三次月变化全为负");
        assertEquals(3, metrics.negativeMonthStreak(), "末尾连续负月应为3");
        assertEquals(4, metrics.completeMonthCount(), "窗口内应有4个完整自然月");
    }

    @Test
    @DisplayName("豁免_已备案窗口内450分钟间隔被豁免_adjusted口径完整")
    void waivedOutageWindow_450MinGap_excludedAndComplete() {
        // 证据区间[02-06 00:00, 02-16 00:00)完整包含备案窗口[02-14 08:00, 15:15):
        // 除窗口29个桶外每15分钟一个bar,唯一raw大间隔为02-14 07:45→15:15(450分钟),
        // 豁免后adjusted间隔=450-435=15;expectedBucketCount=961(含终点桶)、excludedBucketCount=29
        // → adjustedExpected=932,可用bar=931 → adjusted覆盖率931/932≈0.998927(raw 931/961≈0.968783)
        LocalDateTime evidenceStart = LocalDateTime.of(2026, 2, 6, 0, 0);
        LocalDateTime evidenceEnd = LocalDateTime.of(2026, 2, 16, 0, 0);
        LocalDateTime windowStart = LocalDateTime.of(2026, 2, 14, 8, 0);
        LocalDateTime windowEnd = LocalDateTime.of(2026, 2, 14, 15, 15);
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        for (LocalDateTime t = evidenceStart; t.isBefore(evidenceEnd); t = t.plusMinutes(15)) {
            if (!t.isBefore(windowStart) && t.isBefore(windowEnd)) {
                continue;
            }
            bars.add(buildBar(t, 100));
        }

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStart, evidenceEnd, bars);

        assertTrue(metrics.complete(), "备案窗口豁免后完整性必须通过");
        assertNull(metrics.incompleteReason(), "完整时无 incompleteReason");
        assertEquals(450L, metrics.rawMaxMissingBucketGap(), "原始最大间隔仍为450分钟(审计留痕)");
        assertEquals(15L, metrics.maxMissingBucketGap(), "豁免后adjusted最大间隔应为450-435=15分钟");
        assertEquals(931.0 / 932.0, metrics.usableBarCoverage(), 1e-9,
                "adjusted覆盖率=931个可用bar/932个调整后期望桶");
        assertEquals(931.0 / 961.0, metrics.rawUsableBarCoverage(), 1e-9,
                "raw覆盖率=931个可用bar/961个原始期望桶");
        assertEquals(435L, metrics.excludedMinutes(), "与备案窗口重叠435分钟");
        assertEquals(29L, metrics.excludedBucketCount(), "完整落入证据区间的豁免桶数为29");
        assertEquals(List.of(StockMonthlyOutageWaiver.EXCLUSION_ID), metrics.appliedExclusionIds(),
                "必须留痕实际豁免窗口ID");
    }

    @Test
    @DisplayName("豁免_未备案的135分钟间隔(窗口外)仍不完整且raw与adjusted一致")
    void unwaivedGap135OutsideWindow_stillIncomplete() {
        // 2026-03-05的135分钟真实间隔不跨任何已备案窗口: fail-closed,完整性必须仍不通过
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.add(buildBar(LocalDateTime.of(2026, 3, 5, 7, 45), 100));
        bars.add(buildBar(LocalDateTime.of(2026, 3, 5, 10, 0), 100));
        bars.add(buildBar(LocalDateTime.of(2026, 3, 5, 10, 15), 100));
        bars.add(buildBar(LocalDateTime.of(2026, 3, 5, 11, 0), 100));
        LocalDateTime evidenceStart = LocalDateTime.of(2026, 3, 5, 7, 45);
        LocalDateTime evidenceEnd = LocalDateTime.of(2026, 3, 5, 11, 0);

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStart, evidenceEnd, bars);

        assertFalse(metrics.complete(), "未备案的135分钟gap必须保持不完整");
        assertEquals("MONTHLY_EVIDENCE_INCOMPLETE", metrics.incompleteReason());
        assertEquals(135L, metrics.rawMaxMissingBucketGap(), "原始最大间隔135分钟");
        assertEquals(metrics.rawMaxMissingBucketGap(), metrics.maxMissingBucketGap(),
                "未备案间隔raw与adjusted完全一致");
        assertEquals(metrics.rawUsableBarCoverage(), metrics.usableBarCoverage(),
                "未备案间隔raw与adjusted覆盖率完全一致");
        assertEquals(0L, metrics.excludedBucketCount(), "豁免桶数为0");
        assertEquals(0L, metrics.excludedMinutes(), "豁免分钟数为0");
        assertTrue(metrics.appliedExclusionIds().isEmpty(), "不得记录任何豁免窗口ID");
    }

    @Test
    @DisplayName("豁免_证据区间不含备案窗口时raw与adjusted完全相等")
    void waivedOutageWindow_evidenceOutsideWindow_rawEqualsAdjusted() {
        LocalDateTime evidenceStart = LocalDateTime.of(2026, 5, 1, 0, 0);
        LocalDateTime evidenceEnd = LocalDateTime.of(2026, 5, 1, 4, 0);
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.add(buildBar(LocalDateTime.of(2026, 5, 1, 0, 0), 100));
        bars.add(buildBar(LocalDateTime.of(2026, 5, 1, 0, 15), 100));
        bars.add(buildBar(LocalDateTime.of(2026, 5, 1, 3, 45), 100));

        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStart, evidenceEnd, bars);

        assertEquals(metrics.rawUsableBarCoverage(), metrics.usableBarCoverage(),
                "证据区间不含窗口时覆盖率两口径相等");
        assertEquals(metrics.rawMaxMissingBucketGap(), metrics.maxMissingBucketGap(),
                "证据区间不含窗口时间隔两口径相等");
        assertEquals(0L, metrics.excludedBucketCount(), "豁免桶数为0");
        assertEquals(0L, metrics.excludedMinutes(), "豁免分钟数为0");
        assertTrue(metrics.appliedExclusionIds().isEmpty(), "不得记录任何豁免窗口ID");
    }

    /**
     * 为指定自然月每个自然日生成两根固定价格bar(日末价取secondPrice,日内均值取两价平均)。
     *
     * @param month       自然月
     * @param firstPrice  日内第一根bar价格
     * @param secondPrice 日内第二根bar价格(日末价)
     * @return 当月bar列表
     */
    private static List<TornStockMarketBar15mDO> buildDayBars(YearMonth month, double firstPrice,
                                                              double secondPrice) {
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        for (LocalDate day : iterableDays(month)) {
            bars.add(buildBar(day.atTime(8, 0), firstPrice));
            bars.add(buildBar(day.atTime(12, 0), secondPrice));
        }
        return bars;
    }

    /**
     * 将自然月展开为自然日迭代集合。
     *
     * @param month 自然月
     * @return 当月全部自然日
     */
    private static List<LocalDate> iterableDays(YearMonth month) {
        List<LocalDate> days = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            days.add(month.atDay(d));
        }
        return days;
    }

    /**
     * 构建单个可用bar。
     *
     * @param barStartTime bar开始时间
     * @param price        lastPrice
     * @return bar
     */
    private static TornStockMarketBar15mDO buildBar(LocalDateTime barStartTime, double price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1001);
        bar.setBarStartTime(barStartTime);
        bar.setBarEndTime(barStartTime.plusMinutes(15));
        bar.setLastSampleTime(barStartTime.plusMinutes(14));
        bar.setSampleCount(12);
        bar.setLastPrice(BigDecimal.valueOf(price));
        bar.setUsable(true);
        return bar;
    }
}
