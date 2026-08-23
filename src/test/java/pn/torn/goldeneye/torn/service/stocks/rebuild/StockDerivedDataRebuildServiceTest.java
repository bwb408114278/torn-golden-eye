package pn.torn.goldeneye.torn.service.stocks.rebuild;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 全范围派生数据重建服务单元测试。
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
        when(roundDao.selectByRoundTime(START)).thenReturn(null, persistedRound());
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);

        StockDerivedDataRebuildResult result = service.rebuildRange(START, END);

        assertTrue(result.isSuccess());
        assertEquals(1, result.processedBucketCount());
        assertEquals(1, result.barWriteCount());
        assertEquals(0, result.featureWriteCount());
        assertEquals(1, result.repairedDataOnlyRoundCount());
        verify(bar15mDao).upsertBars(any());
        verify(roundDao).updateById(any());
        verify(monthlyStateRangeRebuildService).rebuild(START, END);
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
