package pn.torn.goldeneye.torn.service.stocks.alert.observation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拒绝观察理论路径计算器测试，覆盖紧邻入场、偏离、观察窗口边界与理论退出生命周期。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.29
 */
@DisplayName("拒绝观察理论路径计算器测试")
class StockRejectedObservationCalculatorTest {

    private static final LocalDateTime SIGNAL_TIME = LocalDateTime.of(2026, 7, 1, 10, 0);

    @Test
    @DisplayName("紧邻下一连续桶可用_按14个自然日计算纯价格路径")
    void calculate_adjacentEntryAndObservedBars_returnsPricePath() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.10"), true);
        TornStockMarketBar15mDO up = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("110.00"), true);
        TornStockMarketBar15mDO down = bar(SIGNAL_TIME.plusDays(2), new BigDecimal("95.00"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, up, down));

        assertEquals(0, result.laterMfe().compareTo(new BigDecimal("0.098901098901098901")));
        assertEquals(0, result.laterMae().compareTo(new BigDecimal("-0.050949050949050949")));
        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertTrue(result.observationDataIncomplete());
        assertEquals(SIGNAL_TIME.plusMinutes(15).plusDays(14), result.resolvedAt());
    }

    @Test
    @DisplayName("理论入场向上偏离超过百分之零点一五_立即结束且不等待更晚桶")
    void calculate_entryPriceDeviation_returnsNoTheoreticalEntry() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.16"), true);
        TornStockMarketBar15mDO later = bar(SIGNAL_TIME.plusMinutes(30), new BigDecimal("100.01"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, later));

        assertEquals(StockRejectedObservationCalculator.NO_THEORETICAL_ENTRY, result.resultCode());
        assertNull(result.laterMfe());
        assertNull(result.laterMae());
        assertEquals(batch.getEntryStaleAt(), result.resolvedAt());
        assertFalse(result.observationDataIncomplete());
    }

    @Test
    @DisplayName("观察窗口没有可用价格_结束为数据不足且收益保持空值")
    void calculate_noObservedBar_returnsInsufficientData() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.10"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry));

        assertEquals(StockRejectedObservationCalculator.OBSERVATION_DATA_INSUFFICIENT, result.resultCode());
        assertNull(result.laterMfe());
        assertNull(result.laterMae());
        assertEquals(SIGNAL_TIME.plusMinutes(15).plusDays(14), result.resolvedAt());
        assertTrue(result.observationDataIncomplete());
    }

    @Test
    @DisplayName("观察窗口存在部分可用行情_保留路径并标记数据不完整")
    void calculate_partialObservedBars_marksDataIncomplete() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.10"), true);
        TornStockMarketBar15mDO observed = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("101.00"), true);
        TornStockMarketBar15mDO unusable = bar(SIGNAL_TIME.plusDays(2), new BigDecimal("102.00"), false);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, observed, unusable));

        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertTrue(result.observationDataIncomplete());
        assertNotNull(result.laterMfe());
        assertNotNull(result.laterMae());
    }

    @Test
    @DisplayName("观察窗口连续可用_结果完成且不标记数据缺口")
    void calculate_completeObservedBars_returnsCompleteResult() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.10"), true);
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        bars.add(entry);
        LocalDateTime cursor = entry.getBarEndTime();
        LocalDateTime deadline = entry.getBarStartTime().plusDays(14);
        while (!cursor.isAfter(deadline)) {
            bars.add(bar(cursor, new BigDecimal("100.20"), true));
            cursor = cursor.plusMinutes(15);
        }

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, bars);

        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertFalse(result.observationDataIncomplete());
    }

    @Test
    @DisplayName("理论生命周期_第1天命中目标第2天成交_提前resolved且退出生命周期完整")
    void calculate_theoreticalTargetExit_day2Fill_earlyResolved() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("DEEP_REVERSION");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO signalBar = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("101.00"), true);
        TornStockMarketBar15mDO exitBar = bar(SIGNAL_TIME.plusDays(1).plusMinutes(15), new BigDecimal("100.90"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, signalBar, exitBar));

        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertEquals(SIGNAL_TIME.plusMinutes(15), result.theoreticalEntryTime());
        assertEquals(0, result.theoreticalEntryPrice().compareTo(new BigDecimal("100.00")));
        assertEquals(signalBar.getBarStartTime(), result.theoreticalExitSignalTime());
        assertEquals(exitBar.getBarStartTime(), result.theoreticalExitTime());
        assertEquals(0, result.theoreticalExitPrice().compareTo(new BigDecimal("100.90")));
        assertEquals("CLOSED_TARGET", result.theoreticalCloseType());
        assertEquals(result.theoreticalExitTime(), result.resolvedAt(), "提前退出resolvedAt=理论退出时间");
        assertNotNull(result.theoreticalNetReturn());
    }

    @Test
    @DisplayName("理论生命周期_命中硬风险_关闭类型CLOSED_RISK")
    void calculate_theoreticalRiskExit_closeRisk() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("DEEP_REVERSION");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO signalBar = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("98.30"), true);
        TornStockMarketBar15mDO exitBar = bar(SIGNAL_TIME.plusDays(1).plusMinutes(15), new BigDecimal("98.40"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, signalBar, exitBar));

        assertEquals("CLOSED_RISK", result.theoreticalCloseType());
        assertNotNull(result.theoreticalNetReturn());
    }

    @Test
    @DisplayName("理论生命周期_RANGE特征完整且position30达标_命中区间恢复")
    void calculate_theoreticalRangeExit_featuresComplete_closeRange() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("RANGE_LOWER_BUY");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO signalBar = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("100.50"), true);
        TornStockMarketBar15mDO exitBar = bar(SIGNAL_TIME.plusDays(1).plusMinutes(15), new BigDecimal("100.60"), true);
        List<TornStockStrategyFeature15mDO> features = List.of(
                feature(signalBar.getBarStartTime(), new BigDecimal("100.50"), new BigDecimal("95.00"),
                        new BigDecimal("105.00"), new BigDecimal("0.65")));

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, signalBar, exitBar), features);

        assertEquals("CLOSED_RANGE", result.theoreticalCloseType());
    }

    @Test
    @DisplayName("理论生命周期_RANGE特征缺失_不伪造区间恢复退出")
    void calculate_theoreticalRangeExit_featuresMissing_notFake() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("RANGE_LOWER_BUY");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO signalBar = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("100.50"), true);
        TornStockMarketBar15mDO exitBar = bar(SIGNAL_TIME.plusDays(1).plusMinutes(15), new BigDecimal("100.60"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, signalBar, exitBar), List.of());

        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertNull(result.theoreticalExitTime(), "特征缺失不得伪造区间恢复退出");
        assertNull(result.theoreticalCloseType());
    }

    @Test
    @DisplayName("理论生命周期_退出信号后紧邻bar缺失_不跨缺口成交")
    void calculate_exitSignalNextBarMissing_noGapFill() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("DEEP_REVERSION");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO signalBar = bar(SIGNAL_TIME.plusDays(1), new BigDecimal("101.00"), true);
        TornStockMarketBar15mDO gapExit = bar(SIGNAL_TIME.plusDays(1).plusMinutes(30), new BigDecimal("101.10"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, signalBar, gapExit));

        assertNull(result.theoreticalExitTime(), "紧邻下一bar缺失不得跨缺口成交");
        assertEquals(SIGNAL_TIME.plusMinutes(15).plusDays(14), result.resolvedAt());
    }

    @Test
    @DisplayName("理论生命周期_14天无提前退出_用截止前最后可用bar记录期末净收益")
    void calculate_noEarlyExit_usesFinalBarForNetReturn() {
        TornStockSignalEventDO event = event(new BigDecimal("100.00"));
        TornStockVirtualBatchDO batch = batch();
        batch.setPrimaryStrategy("DEEP_REVERSION");
        TornStockMarketBar15mDO entry = bar(SIGNAL_TIME.plusMinutes(15), new BigDecimal("100.00"), true);
        TornStockMarketBar15mDO finalBar = bar(SIGNAL_TIME.plusDays(13), new BigDecimal("103.00"), true);

        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, List.of(entry, finalBar));

        assertEquals("OBSERVATION_COMPLETED", result.resultCode());
        assertEquals(SIGNAL_TIME.plusMinutes(15).plusDays(14), result.resolvedAt());
        assertNull(result.theoreticalExitTime());
        assertNotNull(result.theoreticalNetReturn(), "14天无退出应记录期末理论净收益");
        assertEquals(0, result.theoreticalNetReturn().compareTo(new BigDecimal("0.02897")));
    }

    private TornStockSignalEventDO event(BigDecimal price) {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setSignalReferencePrice(price);
        event.setRoundTime(SIGNAL_TIME);
        return event;
    }

    private TornStockVirtualBatchDO batch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setExpectedEntryBarTime(SIGNAL_TIME.plusMinutes(15));
        batch.setEntryStaleAt(SIGNAL_TIME.plusMinutes(35));
        return batch;
    }

    private TornStockMarketBar15mDO bar(LocalDateTime start, BigDecimal price, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setBarStartTime(start);
        bar.setBarEndTime(start.plusMinutes(15));
        bar.setLastPrice(price);
        bar.setUsable(usable);
        bar.setSampleCount(15);
        bar.setLastSampleTime(start.plusMinutes(14));
        bar.setTailGapSeconds(60);
        return bar;
    }

    private TornStockStrategyFeature15mDO feature(LocalDateTime barStartTime, BigDecimal referencePrice,
                                                  BigDecimal low30d, BigDecimal high30d, BigDecimal position30) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setBarStartTime(barStartTime);
        feature.setReferencePrice(referencePrice);
        feature.setLow30d(low30d);
        feature.setHigh30d(high30d);
        feature.setPosition30(position30);
        return feature;
    }
}
