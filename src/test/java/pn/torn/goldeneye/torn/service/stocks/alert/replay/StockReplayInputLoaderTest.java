package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 股票回放只读输入加载测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放只读输入加载测试")
@ExtendWith(MockitoExtension.class)
class StockReplayInputLoaderTest {

    @Mock
    private TornStockMarketBar15mDAO barDao;
    @Mock
    private TornStockStrategyFeature15mDAO featureDao;
    @Mock
    private TornStockMonthlyStateDAO monthlyStateDao;
    @Mock
    private TornStockSignalStateDAO signalStateDao;

    @Test
    @DisplayName("指定股票和时间范围_批量读取bar与特征且不写数据库")
    void load_validRequest_readsBarsAndFeaturesInBatch() {
        StockReplayRequest request = request();
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();
        when(barDao.selectByStocksAndTimeRange(List.of(1001), start, end, "BAR_V1"))
                .thenReturn(List.of(bar(start, 1001)));
        when(featureDao.selectLatestByStocksIds(List.of(1001), end, "FEATURE_V1"))
                .thenReturn(List.of(feature(1001)));

        StockReplayInput input = new StockReplayInputLoader(barDao, featureDao).load(request, List.of(1001));

        assertEquals(1, input.bars().size());
        assertEquals(1, input.features().size());
        verify(barDao).selectByStocksAndTimeRange(List.of(1001), start, end, "BAR_V1");
        verify(featureDao).selectLatestByStocksIds(List.of(1001), end, "FEATURE_V1");
    }

    @Test
    @DisplayName("请求时间范围_批量读取全部bar与特征")
    void load_requestTimeRange_readsAllFactsInBatch() {
        StockReplayRequest request = request();
        when(barDao.selectByTimeRange(request.startTime(), request.endTime(), "BAR_V1"))
                .thenReturn(List.of(bar(request.startTime(), 1001)));
        when(featureDao.selectByTimeRange(request.startTime(), request.endTime(), "FEATURE_V1"))
                .thenReturn(List.of(feature(1001)));

        StockReplayInput input = new StockReplayInputLoader(barDao, featureDao).load(request);

        assertEquals(1, input.bars().size());
        assertEquals(1, input.features().size());
        verify(barDao).selectByTimeRange(request.startTime(), request.endTime(), "BAR_V1");
        verify(featureDao).selectByTimeRange(request.startTime(), request.endTime(), "FEATURE_V1");
    }

    @Test
    @DisplayName("请求时间范围_批量读取月度状态和信号状态")
    void load_requestTimeRange_readsMonthlyAndSignalStatesInBatch() {
        StockReplayRequest request = request();
        when(monthlyStateDao.selectConfirmedByMonth(request.startTime().toLocalDate().withDayOfMonth(1)))
                .thenReturn(List.of(new TornStockMonthlyStateDO()));
        when(signalStateDao.selectAll()).thenReturn(List.of(new TornStockSignalStateDO()));

        StockReplayInput input = new StockReplayInputLoader(barDao, featureDao, monthlyStateDao, signalStateDao)
                .load(request);

        assertEquals(1, input.monthlyStates().size());
        assertEquals(1, input.signalStates().size());
        verify(monthlyStateDao).selectConfirmedByMonth(request.startTime().toLocalDate().withDayOfMonth(1));
        verify(signalStateDao).selectAll();
    }

    @Test
    @DisplayName("空股票集合_拒绝加载请求")
    void load_emptyStocks_rejectsRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new StockReplayInputLoader(barDao, featureDao).load(request(), List.of()));
    }

    private StockReplayRequest request() {
        return new StockReplayRequest("VIP_FORMAL", LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0), "BAR_V1", "FEATURE_V1", "BUY_V1",
                "SELL_V1", "ALLOC_V1", "MSG_V1", Path.of("target/replay"),
                EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));
    }

    private TornStockMarketBar15mDO bar(LocalDateTime time, int stocksId) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setBarStartTime(time);
        bar.setLastPrice(new BigDecimal("100.00"));
        return bar;
    }

    private TornStockStrategyFeature15mDO feature(int stocksId) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stocksId);
        feature.setFeatureVersion("FEATURE_V1");
        return feature;
    }
}
