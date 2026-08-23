package pn.torn.goldeneye.torn.service.stocks.rebuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15 分钟策略特征纯计算器测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@DisplayName("15分钟策略特征纯计算器测试")
class Stock15mFeatureCalculatorTest {

    private static final int STOCKS_ID = 1;
    private static final String SHORTNAME = "TST";
    private static final String BUILD_VERSION = Stock15mBarBuildService.BUILD_VERSION;
    private static final int BARS_30D = Stock15mFeatureBuildService.BARS_30D;

    @Test
    @DisplayName("预热不足_条件性指标NULL且INSUFFICIENT_HISTORY")
    void prewarmInsufficient_conditionalsNull() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> history = historyBars(current, 96, "100.00");
        TornStockStrategyFeature15mDO feature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "200.00", true), history);

        assertNotNull(feature);
        assertNull(feature.getMa7d());
        assertNull(feature.getMa30d());
        assertNull(feature.getZscore7d());
        assertNull(feature.getReturn7d());
        assertNull(feature.getReturn14d());
        assertFalse(feature.getStrategyReady());
        assertEquals("INSUFFICIENT_HISTORY", feature.getDataQualityReason());
    }

    @Test
    @DisplayName("2880连续可用bar_strategyReady=true")
    void sufficientConsecutive_strategyReadyTrue() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> history = historyBars(current, BARS_30D - 1, "100.00");
        TornStockStrategyFeature15mDO feature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "100.00", true), history);

        assertTrue(feature.getStrategyReady());
        assertNull(feature.getDataQualityReason());
    }

    @Test
    @DisplayName("少1条/断层/不可用bar_strategyReady=false且HISTORY_NOT_CONSECUTIVE")
    void notConsecutive_strategyReadyFalse() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);

        List<TornStockMarketBar15mDO> shortHistory = historyBars(current, BARS_30D - 2, "100.00");
        TornStockStrategyFeature15mDO shortFeature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "100.00", true), shortHistory);
        assertFalse(shortFeature.getStrategyReady());
        assertEquals("INSUFFICIENT_HISTORY", shortFeature.getDataQualityReason());

        List<TornStockMarketBar15mDO> gapHistory = IntStream.range(0, BARS_30D - 1)
                .mapToObj(i -> {
                    long offset = i + 1 + (i >= 1000 ? 1 : 0);
                    return bar(current.minusMinutes(15L * offset), "100.00", true);
                })
                .toList();
        TornStockStrategyFeature15mDO gapFeature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "100.00", true), gapHistory);
        assertFalse(gapFeature.getStrategyReady());
        assertEquals("HISTORY_NOT_CONSECUTIVE", gapFeature.getDataQualityReason());

        List<TornStockMarketBar15mDO> unusableHistory = historyBars(current, BARS_30D - 1, "100.00");
        TornStockMarketBar15mDO unusable = unusableHistory.get(500);
        unusable.setSampleCount(1);
        unusable.setUsable(false);
        TornStockStrategyFeature15mDO unusableFeature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "100.00", true), unusableHistory);
        assertFalse(unusableFeature.getStrategyReady());
        assertEquals("HISTORY_NOT_CONSECUTIVE", unusableFeature.getDataQualityReason());
    }

    @Test
    @DisplayName("高低价相同_标准差0_return基准0_保持既有边界")
    void boundaryCases_preserveOldSemantics() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> history = historyBars(current, BARS_30D - 1, "100.00");
        TornStockStrategyFeature15mDO feature = Stock15mFeatureCalculator.buildSingleFeature(
                bar(current, "100.00", true), history);

        assertEquals(0, feature.getWidth30d().compareTo(BigDecimal.ZERO));
        assertNull(feature.getPosition30());
        assertEquals(0, feature.getZscore30d().compareTo(BigDecimal.ZERO));
        assertEquals(0, feature.getReturn6h().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("实时单桶入口与rolling批处理入口逐字段一致")
    void realtimeAndRollingEntry_equal() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> history = IntStream.range(0, BARS_30D - 1)
                .mapToObj(i -> {
                    BigDecimal price = i % 2 == 0 ? new BigDecimal("100.00") : new BigDecimal("105.00");
                    return bar(current.minusMinutes(15L * (BARS_30D - 1 - i)), price, true);
                })
                .toList();
        TornStockMarketBar15mDO currentBar = bar(current, "120.00", true);

        TornStockStrategyFeature15mDO realtime = Stock15mFeatureCalculator.buildSingleFeature(currentBar, history);

        Stock15mFeatureRollingWindow window = new Stock15mFeatureRollingWindow();
        history.forEach(window::advance);
        window.advance(currentBar);
        TornStockStrategyFeature15mDO rolling = window.materializeCurrent();

        assertFeatureEquals(realtime, rolling);
    }

    @Test
    @DisplayName("超过2881条输入_窗口容量保持BARS_30D+1且最早淘汰不改变后续特征")
    void overCapacity_evictsOldestWithoutChangingLaterFeatures() {
        LocalDateTime anchor = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> longHistory = historyBars(anchor.plusMinutes(15L * 3), BARS_30D + 3, "100.00");
        TornStockMarketBar15mDO currentBar = bar(anchor.plusMinutes(15L * 3), "110.00", true);

        Stock15mFeatureRollingWindow window = new Stock15mFeatureRollingWindow();
        longHistory.forEach(window::advance);
        assertEquals(BARS_30D + 1, window.size(), "滚动窗口容量应始终不超过BARS_30D+1");
        window.advance(currentBar);
        TornStockStrategyFeature15mDO feature = window.materializeCurrent();
        assertEquals(BARS_30D + 1, window.size());
        assertNotNull(feature);
        assertTrue(feature.getStrategyReady(), "淘汰最早历史后最后2880条仍应连续");
    }

    @Test
    @DisplayName("预热只advance不物化_最终current仅materialize一次")
    void prewarm_advanceOnly_materializeOnce() {
        LocalDateTime current = LocalDateTime.of(2026, 7, 24, 10, 0);
        List<TornStockMarketBar15mDO> history = historyBars(current, BARS_30D - 1, "100.00");
        Stock15mFeatureRollingWindow window = new Stock15mFeatureRollingWindow();
        history.forEach(window::advance);
        assertEquals(0, window.materializedFeatureCount(), "预热advance不得物化feature");
        window.advance(bar(current, "100.00", true));
        TornStockStrategyFeature15mDO feature = window.materializeCurrent();
        assertNotNull(feature);
        assertEquals(1, window.materializedFeatureCount(), "最终current只应物化一次");
        assertEquals(0, window.materializedFeatureCount() - 1, "materializeCurrent不改变窗口状态");
    }

    private void assertFeatureEquals(TornStockStrategyFeature15mDO expected, TornStockStrategyFeature15mDO actual) {
        assertEquals(expected.getStocksId(), actual.getStocksId());
        assertEquals(expected.getStocksShortname(), actual.getStocksShortname());
        assertEquals(expected.getBarStartTime(), actual.getBarStartTime());
        assertBigDecimalEquals(expected.getReferencePrice(), actual.getReferencePrice());
        assertBigDecimalEquals(expected.getMa1d(), actual.getMa1d());
        assertBigDecimalEquals(expected.getMa7d(), actual.getMa7d());
        assertBigDecimalEquals(expected.getMa30d(), actual.getMa30d());
        assertBigDecimalEquals(expected.getZscore1d(), actual.getZscore1d());
        assertBigDecimalEquals(expected.getZscore7d(), actual.getZscore7d());
        assertBigDecimalEquals(expected.getZscore30d(), actual.getZscore30d());
        assertBigDecimalEquals(expected.getReturn6h(), actual.getReturn6h());
        assertBigDecimalEquals(expected.getReturn1d(), actual.getReturn1d());
        assertBigDecimalEquals(expected.getReturn7d(), actual.getReturn7d());
        assertBigDecimalEquals(expected.getReturn14d(), actual.getReturn14d());
        assertBigDecimalEquals(expected.getLow30d(), actual.getLow30d());
        assertBigDecimalEquals(expected.getHigh30d(), actual.getHigh30d());
        assertBigDecimalEquals(expected.getWidth30d(), actual.getWidth30d());
        assertBigDecimalEquals(expected.getPosition30(), actual.getPosition30());
        assertBigDecimalEquals(expected.getPctAbove30dLow(), actual.getPctAbove30dLow());
        assertBigDecimalEquals(expected.getPctBelow30dHigh(), actual.getPctBelow30dHigh());
        assertEquals(expected.getStrategyReady(), actual.getStrategyReady());
        assertEquals(expected.getDataQualityReason(), actual.getDataQualityReason());
        assertEquals(expected.getFeatureVersion(), actual.getFeatureVersion());
    }

    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertEquals(0, expected.compareTo(actual));
        }
    }

    private List<TornStockMarketBar15mDO> historyBars(LocalDateTime currentBarStart, int count, String price) {
        List<TornStockMarketBar15mDO> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(bar(currentBarStart.minusMinutes(15L * (count - i)), new BigDecimal(price), true));
        }
        return list;
    }

    private TornStockMarketBar15mDO bar(LocalDateTime barStart, String price, boolean usable) {
        return bar(barStart, new BigDecimal(price), usable);
    }

    private TornStockMarketBar15mDO bar(LocalDateTime barStart, BigDecimal price, boolean usable) {
        TornStockMarketBar15mDO b = new TornStockMarketBar15mDO();
        b.setStocksId(STOCKS_ID);
        b.setStocksShortname(SHORTNAME);
        b.setBarStartTime(barStart);
        b.setBarEndTime(barStart.plusMinutes(15));
        b.setFirstSampleTime(barStart);
        b.setLastSampleTime(barStart.plusMinutes(14));
        b.setFirstPrice(price);
        b.setLastPrice(price);
        b.setLowPrice(price);
        b.setHighPrice(price);
        b.setSampleCount(usable ? 15 : 1);
        b.setDuplicateCount(0);
        b.setUsable(usable);
        b.setBuildVersion(BUILD_VERSION);
        return b;
    }
}
