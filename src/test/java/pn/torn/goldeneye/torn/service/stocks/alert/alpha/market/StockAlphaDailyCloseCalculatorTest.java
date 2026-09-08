package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * α策略日线收盘计算器测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@DisplayName("α策略日线收盘计算器测试")
@ExtendWith(MockitoExtension.class)
class StockAlphaDailyCloseCalculatorTest {
    /**
     * 测试使用的结束日期。
     */
    private static final LocalDate END_DATE = LocalDate.of(2026, 9, 5);

    @Mock
    private TornStockMarketBar15mDAO barDao;
    @Mock
    private TornStockAlphaDailySnapshotDAO snapshotDao;

    @Test
    @DisplayName("收盘计算_取自然日最后一根可用正价bar")
    void selectsLastUsablePositiveBarOnBusinessDate() {
        LocalDate date = LocalDate.of(2026, 9, 5);
        TornStockMarketBar15mDO first = bar(date.atTime(23, 45), "100", true);
        TornStockMarketBar15mDO last = bar(date.atTime(23, 55), "101", true);
        TornStockMarketBar15mDO unusable = bar(date.atTime(23, 59), "102", false);
        assertEquals(new BigDecimal("101"), StockAlphaDailyCloseCalculator.calculate(date, List.of(first, last, unusable)).closePrice());
    }

    @Test
    @DisplayName("收盘计算_日期不匹配或价格非正时返回null")
    void missingDateOrInvalidPriceReturnsNull() {
        LocalDate date = LocalDate.of(2026, 9, 5);
        assertNull(StockAlphaDailyCloseCalculator.calculate(date, List.of(bar(date.atStartOfDay(), "0", true))));
    }

    @Test
    @DisplayName("日线读取_中间日期缺失时返回空且不读取bar")
    void loadDailyCloses_returnsEmptyAndReadsNoBarWhenMiddleDateMissing() {
        List<TornStockAlphaDailySnapshotDO> stored = new ArrayList<>();
        stored.addAll(completeDay(END_DATE.minusDays(2)));
        stored.addAll(completeDay(END_DATE));
        when(snapshotDao.selectByDateRange(eq(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION),
                eq(StockAlphaRuleDefinition.RULE_VERSION), any(), eq(END_DATE))).thenReturn(stored);

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);

        assertTrue(service.loadDailyCloses(END_DATE).isEmpty());
        verifyNoInteractions(barDao);
        verify(snapshotDao, never()).batchInsertIgnoreConflict(any());
    }

    @Test
    @DisplayName("日线读取_成员集合与固定股票池不一致时返回空")
    void loadDailyCloses_returnsEmptyWhenMemberSetDiffers() {
        List<TornStockAlphaDailySnapshotDO> stored = new ArrayList<>(completeDay(END_DATE));
        stored.removeLast();
        stored.add(snapshot(END_DATE, StockAlphaRuleDefinition.MEMBER_COUNT + 1));
        when(snapshotDao.selectByDateRange(eq(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION),
                eq(StockAlphaRuleDefinition.RULE_VERSION), any(), eq(END_DATE))).thenReturn(stored);

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);

        assertTrue(service.loadDailyCloses(END_DATE).isEmpty());
    }

    @Test
    @DisplayName("日线构建_窗口已完整时不扫描bar且不写入")
    void buildDailyCloses_skipsScanAndWriteWhenEveryDateComplete() {
        when(snapshotDao.selectByDateRange(eq(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION),
                eq(StockAlphaRuleDefinition.RULE_VERSION), any(), eq(END_DATE)))
                .thenReturn(completeWindow(END_DATE));

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);

        assertEquals(0, service.buildDailyCloses(END_DATE));
        verifyNoInteractions(barDao);
        verify(snapshotDao, never()).batchInsertIgnoreConflict(any());
    }

    @Test
    @DisplayName("日线构建_仅批量写入缺失且成员完整的日期")
    void buildDailyCloses_batchWritesOnlyMissingCompleteDate() {
        List<TornStockAlphaDailySnapshotDO> stored = completeWindow(END_DATE.minusDays(1));
        when(snapshotDao.selectByDateRange(eq(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION),
                eq(StockAlphaRuleDefinition.RULE_VERSION), any(), eq(END_DATE))).thenReturn(stored);
        when(barDao.selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()), any(), any(), any()))
                .thenReturn(completeDayBars(END_DATE));
        when(snapshotDao.batchInsertIgnoreConflict(any())).thenReturn(StockAlphaRuleDefinition.MEMBER_COUNT);

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);

        assertEquals(StockAlphaRuleDefinition.MEMBER_COUNT, service.buildDailyCloses(END_DATE));
        ArgumentCaptor<List<TornStockAlphaDailySnapshotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotDao).batchInsertIgnoreConflict(captor.capture());
        assertEquals(StockAlphaRuleDefinition.MEMBER_COUNT, captor.getValue().size());
        assertEquals(END_DATE, captor.getValue().getFirst().getBusinessDate());
    }

    @Test
    @DisplayName("日线构建_成员不完整日期不写入")
    void buildDailyCloses_dropsDateWithoutFullMemberSet() {
        List<TornStockAlphaDailySnapshotDO> stored = completeWindow(END_DATE.minusDays(1));
        when(snapshotDao.selectByDateRange(eq(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION),
                eq(StockAlphaRuleDefinition.RULE_VERSION), any(), eq(END_DATE))).thenReturn(stored);
        when(barDao.selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()), any(), any(), any()))
                .thenReturn(List.of(closeBar(END_DATE, 1)));

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);

        assertEquals(0, service.buildDailyCloses(END_DATE));
        verify(snapshotDao, never()).batchInsertIgnoreConflict(any());
    }

    @Test
    @DisplayName("日线读取_窗口完整时返回按日期索引的收盘结果")
    void loadDailyCloses_returnsIndexedResultsWhenEveryDateComplete() {
        List<TornStockAlphaDailySnapshotDO> stored = new ArrayList<>();
        for (LocalDate date = END_DATE.minusDays(80); !date.isAfter(END_DATE); date = date.plusDays(1)) {
            stored.addAll(completeDay(date));
        }
        when(snapshotDao.selectByDateRange(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, END_DATE.minusDays(80), END_DATE))
                .thenReturn(stored);

        StockAlphaDailyCloseService service =
                new StockAlphaDailyCloseService(barDao, snapshotDao);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loaded =
                service.loadDailyCloses(END_DATE);

        assertEquals(81, loaded.size());
        assertEquals(StockAlphaRuleDefinition.MEMBER_COUNT, loaded.get(END_DATE).size());
        assertEquals(new BigDecimal("12.34"), loaded.get(END_DATE).get(1).closePrice());
        verifyNoInteractions(barDao);
    }

    private TornStockMarketBar15mDO bar(LocalDateTime start, String price, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1);
        bar.setBarStartTime(start);
        bar.setBarEndTime(start.plusMinutes(15));
        bar.setLastSampleTime(start.plusMinutes(14));
        bar.setSampleCount(usable ? 15 : 1);
        bar.setUsable(usable);
        bar.setLastPrice(new BigDecimal(price));
        return bar;
    }

    /**
     * 构造指定自然日最后一根可用bar。
     *
     * @param date     自然日
     * @param stocksId 股票ID
     * @return 15分钟bar
     */
    private TornStockMarketBar15mDO closeBar(LocalDate date, int stocksId) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setId((long) stocksId);
        bar.setStocksId(stocksId);
        bar.setBarStartTime(date.atTime(23, 45));
        bar.setBarEndTime(date.atTime(23, 45).plusMinutes(15));
        bar.setLastSampleTime(date.atTime(23, 59));
        bar.setSampleCount(15);
        bar.setUsable(true);
        bar.setLastPrice(new BigDecimal("10.00"));
        return bar;
    }

    /**
     * 构造指定自然日全部35支成员的最后可用bar。
     *
     * @param date 自然日
     * @return 15分钟bar
     */
    private List<TornStockMarketBar15mDO> completeDayBars(LocalDate date) {
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        for (int stocksId = 1; stocksId <= StockAlphaRuleDefinition.MEMBER_COUNT; stocksId++) {
            bars.add(closeBar(date, stocksId));
        }
        return bars;
    }

    /**
     * 构造截至指定日期的完整窗口快照。
     *
     * @param endDate 窗口结束日期
     * @return 日线快照
     */
    private List<TornStockAlphaDailySnapshotDO> completeWindow(LocalDate endDate) {
        List<TornStockAlphaDailySnapshotDO> snapshots = new ArrayList<>();
        for (LocalDate date = endDate.minusDays(80); !date.isAfter(endDate); date = date.plusDays(1)) {
            snapshots.addAll(completeDay(date));
        }
        return snapshots;
    }

    /**
     * 构造指定自然日的完整35支成员快照。
     *
     * @param date 自然日
     * @return 日线快照
     */
    private List<TornStockAlphaDailySnapshotDO> completeDay(LocalDate date) {
        List<TornStockAlphaDailySnapshotDO> day = new ArrayList<>();
        for (int stocksId = 1; stocksId <= StockAlphaRuleDefinition.MEMBER_COUNT; stocksId++) {
            day.add(snapshot(date, stocksId));
        }
        return day;
    }

    /**
     * 构造指定自然日和股票的合法日线快照。
     *
     * @param date     自然日
     * @param stocksId 股票ID
     * @return 日线快照
     */
    private TornStockAlphaDailySnapshotDO snapshot(LocalDate date, int stocksId) {
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(stocksId);
        snapshot.setBusinessDate(date);
        snapshot.setClosePrice(new BigDecimal("12.34"));
        snapshot.setSourceBarId((long) stocksId);
        snapshot.setSourceBarStartTime(date.atTime(23, 45));
        snapshot.setStockUniverseVersion(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION);
        snapshot.setAlphaRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        snapshot.setCommonValid(true);
        return snapshot;
    }
}
