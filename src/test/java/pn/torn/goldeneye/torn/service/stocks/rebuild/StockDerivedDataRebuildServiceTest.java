package pn.torn.goldeneye.torn.service.stocks.rebuild;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 全范围派生数据重建服务单元测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("全范围派生数据重建服务测试")
class StockDerivedDataRebuildServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = START.plusMinutes(30);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 1, 0);

    @Mock
    private TornStocksDAO stocksDao;
    @Mock
    private TornStocksHistoryDAO stocksHistoryDao;
    @Mock
    private TornStockMarketBar15mDAO bar15mDao;
    @Mock
    private TornStockStrategyFeature15mDAO feature15mDao;
    @Mock
    private TornStockMarketRoundDAO roundDao;
    @Mock
    private StockMarketClock marketClock;
    @Mock
    private StockMonthlyStateRangeRebuildService monthlyStateRangeRebuildService;

    private StockDerivedDataRebuildService service;

    @BeforeEach
    void setUp() {
        service = new StockDerivedDataRebuildService(
                stocksDao, stocksHistoryDao, bar15mDao, feature15mDao, roundDao,
                new StockMarketRoundFactory(), marketClock, monthlyStateRangeRebuildService);
        lenient().when(marketClock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("成功路径_按分钟事实构建bar、标记REPAIRED_DATA_ONLY并调用月度范围重建")
    void rebuildRange_success_buildsBarAndMarksRound() {
        when(stocksDao.list()).thenReturn(List.of(stock()));
        when(stocksHistoryDao.selectHistoryPointsRange(START, END))
                .thenReturn(List.of(
                        point(START.plusMinutes(5)),
                        point(START.plusMinutes(6)),
                        point(START.plusMinutes(7)),
                        point(START.plusMinutes(8)),
                        point(START.plusMinutes(9)),
                        point(START.plusMinutes(10)),
                        point(START.plusMinutes(11)),
                        point(START.plusMinutes(12)),
                        point(START.plusMinutes(13)),
                        point(START.plusMinutes(14))));
        when(bar15mDao.selectByStockAndTimeRange(eq(1), any(), any(), any()))
                .thenReturn(List.of());
        when(roundDao.upsertRepairedDataOnlyRounds(any())).thenReturn(1);

        StockDerivedDataRebuildResult result = service.rebuildRange(START, END);

        assertTrue(result.isSuccess());
        assertEquals(1, result.processedBucketCount());
        assertEquals(1, result.barWriteCount());
        assertEquals(0, result.featureWriteCount());
        assertEquals(1, result.repairedDataOnlyRoundCount());
        verify(bar15mDao).upsertBars(any());
        verify(roundDao).upsertRepairedDataOnlyRounds(any());
        verify(roundDao, never()).updateById(any());
        verify(roundDao, never()).selectByRoundTime(any());
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
        verify(monthlyStateRangeRebuildService).rebuild(START, END);
    }

    @Test
    @DisplayName("三支股票_feature每支仅一次范围查询且预热/不可用不写、usable写feature")
    void rebuildRange_threeStocks_oneQueryPerStockAndWritesOnlyUsableTargets() {
        TornStocksDO stock1 = stock(1, "AAA");
        TornStocksDO stock2 = stock(2, "BBB");
        TornStocksDO stock3 = stock(3, "CCC");
        when(stocksDao.list()).thenReturn(List.of(stock1, stock2, stock3));
        when(stocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(List.of());

        LocalDateTime prewarm = START.minusMinutes(15);
        LocalDateTime unusableTarget = START.plusMinutes(15);
        when(bar15mDao.selectByStockAndTimeRange(eq(1), any(), any(), any()))
                .thenReturn(List.of(
                        bar(1, "AAA", prewarm, true),
                        bar(1, "AAA", START, true),
                        bar(1, "AAA", unusableTarget, false)));
        when(bar15mDao.selectByStockAndTimeRange(eq(2), any(), any(), any()))
                .thenReturn(List.of(bar(2, "BBB", START, true)));
        when(bar15mDao.selectByStockAndTimeRange(eq(3), any(), any(), any()))
                .thenReturn(List.of(bar(3, "CCC", START, true)));

        StockDerivedDataRebuildResult result = service.rebuildRange(START, END);

        assertTrue(result.isSuccess());
        assertEquals(3, result.featureWriteCount());
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bar15mDao, times(3)).selectByStockAndTimeRange(any(), any(), endCaptor.capture(), any());
        verify(bar15mDao, times(1)).selectByStockAndTimeRange(eq(1), any(), any(), any());
        verify(bar15mDao, times(1)).selectByStockAndTimeRange(eq(2), any(), any(), any());
        verify(bar15mDao, times(1)).selectByStockAndTimeRange(eq(3), any(), any(), any());
        endCaptor.getAllValues().forEach(end -> assertEquals(END, end, "feature范围必须传递原始endExclusive"));
        ArgumentCaptor<List<TornStockStrategyFeature15mDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(feature15mDao, times(3)).upsertFeatures(captor.capture());
        List<TornStockStrategyFeature15mDO> features = captor.getAllValues().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(3, features.size());
        assertTrue(features.stream().noneMatch(f -> f.getBarStartTime().equals(prewarm)),
                "预热bar不得写feature");
        assertTrue(features.stream().noneMatch(f -> f.getBarStartTime().equals(unusableTarget)),
                "不可用target不得写feature");
    }

    @Test
    @DisplayName("feature批量写入_500条触发第一批_余量第二批")
    void rebuildRange_featureBatch_500AndRemainder() {
        when(stocksDao.list()).thenReturn(List.of(stock()));
        when(stocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(List.of());

        LocalDateTime rangeStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime rangeEnd = rangeStart.plusMinutes(15L * 501);
        List<TornStockMarketBar15mDO> bars = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            bars.add(bar(1, "TST", rangeStart.plusMinutes(15L * i), true));
        }
        when(bar15mDao.selectByStockAndTimeRange(eq(1), any(), any(), any())).thenReturn(bars);

        StockDerivedDataRebuildResult result = service.rebuildRange(rangeStart, rangeEnd);

        assertTrue(result.isSuccess());
        assertEquals(501, result.featureWriteCount());
        ArgumentCaptor<List<TornStockStrategyFeature15mDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(feature15mDao, times(2)).upsertFeatures(captor.capture());
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(1, captor.getAllValues().get(1).size());
    }

    @Test
    @DisplayName("round批量标记_501桶只调用2次批量UPSERT且无逐桶SQL")
    void rebuildRange_roundBatch_501Buckets_usesTwoBatchUpserts() {
        when(stocksDao.list()).thenReturn(List.of(stock()));
        when(stocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(List.of());
        when(bar15mDao.selectByStockAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(roundDao.upsertRepairedDataOnlyRounds(any())).thenReturn(0);

        LocalDateTime rangeStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime rangeEnd = rangeStart.plusMinutes(15L * 501);
        // 不直接 mock 501 个桶集合；通过 selectHistoryPointsRange 返回 501 个不同分钟事实构造 actualBuckets
        List<StockPricePoint> points = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            points.add(point(rangeStart.plusMinutes(15L * i)));
        }
        when(stocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(points);

        StockDerivedDataRebuildResult result = service.rebuildRange(rangeStart, rangeEnd);

        assertTrue(result.isSuccess());
        assertEquals(501, result.processedBucketCount());
        verify(roundDao, times(2)).upsertRepairedDataOnlyRounds(any());
        verify(roundDao, never()).selectByRoundTime(any());
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
        verify(roundDao, never()).updateById(any());
    }

    private TornStocksDO stock(int id, String shortname) {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(id);
        stock.setStocksName("Test Stock " + id);
        stock.setStocksShortname(shortname);
        return stock;
    }

    private TornStockMarketBar15mDO bar(int stockId, String shortname, LocalDateTime start, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stockId);
        bar.setStocksShortname(shortname);
        bar.setBarStartTime(start);
        bar.setBarEndTime(start.plusMinutes(15));
        bar.setFirstSampleTime(start);
        bar.setLastSampleTime(start.plusMinutes(14));
        bar.setFirstPrice(new BigDecimal("100.00"));
        bar.setLastPrice(new BigDecimal("100.00"));
        bar.setLowPrice(new BigDecimal("100.00"));
        bar.setHighPrice(new BigDecimal("100.00"));
        bar.setSampleCount(usable ? 15 : 1);
        bar.setDuplicateCount(0);
        bar.setUsable(usable);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private TornStocksDO stock() {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(1);
        stock.setStocksName("Test Stock");
        stock.setStocksShortname("TST");
        return stock;
    }

    private StockPricePoint point(LocalDateTime time) {
        return new StockPricePoint(null, 1, "TST", new BigDecimal("100.00"), null, time);
    }

    private TornStockMarketRoundDO persistedRound() {
        TornStockMarketRoundDO round = new StockMarketRoundFactory()
                .createRound(START, "PENDING");
        round.setId(1L);
        return round;
    }
}
