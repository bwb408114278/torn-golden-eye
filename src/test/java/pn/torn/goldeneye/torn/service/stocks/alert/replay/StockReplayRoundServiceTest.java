package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StrictReboundConfirmBuyStrategy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 股票回放完整最小轮次服务测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放最小轮次服务测试")
class StockReplayRoundServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    @DisplayName("输入特征不命中策略_生成拒绝记录和净值曲线")
    void run_featureDoesNotMatch_generatesRejectionAndEquityPoint() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusMinutes(15);
        StockReplayRequest request = new StockReplayRequest("VIP_FORMAL", start, end,
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                outputDirectory, EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));
        TornStockMarketBar15mDAO barDao = Mockito.mock(TornStockMarketBar15mDAO.class);
        TornStockStrategyFeature15mDAO featureDao = Mockito.mock(TornStockStrategyFeature15mDAO.class);
        when(barDao.selectByTimeRange(start, end, "BAR_V1")).thenReturn(List.of(bar(start)));
        when(featureDao.selectByTimeRange(start, end, "FEATURE_V1"))
                .thenReturn(List.of(incompleteFeature(start)));

        StockReplayService service = new StockReplayService(new StockReplayArtifactWriter(),
                new StockReplayInputLoader(barDao, featureDao),
                new StockReplayDecisionEngine(new StockReplayPortfolioEngine(),
                        List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                                new StrictReboundConfirmBuyStrategy()), new StockEligibilityService()));

        StockReplayResult result = service.run(request);

        assertEquals("COMPLETED", result.status());
        String rejections = Files.readString(outputDirectory.resolve(result.runId() + "-rejections.csv"));
        String equity = Files.readString(outputDirectory.resolve(result.runId() + "-equity-curve.csv"));
        assertTrue(rejections.contains("STYLE_MISSING"));
        assertTrue(equity.contains(result.runId()));
    }

    @Test
    @DisplayName("下一根十五分钟bar缺失_不跨越数据断层成交")
    void run_missingNextBar_doesNotCrossDataGap() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime afterGap = start.plusMinutes(30);
        StockReplayRequest request = new StockReplayRequest("VIP_FORMAL_GAP", start,
                afterGap.plusMinutes(15), "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1",
                "ALLOC_V1", "MSG_V1", outputDirectory, EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));
        TornStockMarketBar15mDAO barDao = Mockito.mock(TornStockMarketBar15mDAO.class);
        TornStockStrategyFeature15mDAO featureDao = Mockito.mock(TornStockStrategyFeature15mDAO.class);
        TornStockMonthlyStateDAO monthlyStateDao = Mockito.mock(TornStockMonthlyStateDAO.class);
        TornStockSignalStateDAO signalStateDao = Mockito.mock(TornStockSignalStateDAO.class);
        when(barDao.selectByTimeRange(start, afterGap.plusMinutes(15), "BAR_V1"))
                .thenReturn(List.of(bar(start, new BigDecimal("100")),
                        bar(afterGap, new BigDecimal("102"))));
        when(featureDao.selectByTimeRange(start, afterGap.plusMinutes(15), "FEATURE_V1"))
                .thenReturn(List.of(feature(start, true), feature(afterGap, false)));
        when(monthlyStateDao.selectConfirmedByMonth(start.toLocalDate().withDayOfMonth(1)))
                .thenReturn(List.of(monthlyState()));
        when(signalStateDao.selectAll()).thenReturn(List.of());

        StockReplayService service = new StockReplayService(new StockReplayArtifactWriter(),
                new StockReplayInputLoader(barDao, featureDao, monthlyStateDao, signalStateDao),
                new StockReplayDecisionEngine(new StockReplayPortfolioEngine(),
                        List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                                new StrictReboundConfirmBuyStrategy()), new StockEligibilityService()));

        StockReplayResult result = service.run(request);

        String trades = Files.readString(outputDirectory.resolve(result.runId() + "-trades.csv"));
        String rejections = Files.readString(outputDirectory.resolve(result.runId() + "-rejections.csv"));
        assertFalse(trades.contains("CLOSED_"));
        assertTrue(rejections.contains("ENTRY_DATA_STALE"));
    }

    @Test
    @DisplayName("第二轮达到目标退出_生成交易记录并复用月度状态")
    void run_targetReachedOnNextRound_generatesTradeRecord() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime secondRound = start.plusMinutes(15);
        LocalDateTime thirdRound = secondRound.plusMinutes(15);
        LocalDateTime end = thirdRound.plusMinutes(15);
        StockReplayRequest request = new StockReplayRequest("VIP_FORMAL", start, end,
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                outputDirectory, EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));
        TornStockMarketBar15mDAO barDao = Mockito.mock(TornStockMarketBar15mDAO.class);
        TornStockStrategyFeature15mDAO featureDao = Mockito.mock(TornStockStrategyFeature15mDAO.class);
        TornStockMonthlyStateDAO monthlyStateDao = Mockito.mock(TornStockMonthlyStateDAO.class);
        TornStockSignalStateDAO signalStateDao = Mockito.mock(TornStockSignalStateDAO.class);
        when(barDao.selectByTimeRange(start, end, "BAR_V1"))
                .thenReturn(List.of(bar(start, new BigDecimal("100")),
                        bar(secondRound, new BigDecimal("101")),
                        bar(thirdRound, new BigDecimal("102")),
                        bar(end, new BigDecimal("103"))));
        when(featureDao.selectByTimeRange(start, end, "FEATURE_V1"))
                .thenReturn(List.of(feature(start, true), feature(secondRound, false),
                        feature(thirdRound, false), feature(end, false)));
        when(monthlyStateDao.selectConfirmedByMonth(start.toLocalDate().withDayOfMonth(1)))
                .thenReturn(List.of(monthlyState()));
        when(signalStateDao.selectAll()).thenReturn(List.of());

        StockReplayService service = new StockReplayService(new StockReplayArtifactWriter(),
                new StockReplayInputLoader(barDao, featureDao, monthlyStateDao, signalStateDao),
                new StockReplayDecisionEngine(new StockReplayPortfolioEngine(),
                        List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                                new StrictReboundConfirmBuyStrategy()), new StockEligibilityService()));

        StockReplayResult result = service.run(request);

        String trades = Files.readString(outputDirectory.resolve(result.runId() + "-trades.csv"));
        assertTrue(trades.contains("CLOSED_TARGET"));
    }

    private TornStockMarketBar15mDO bar(LocalDateTime time) {
        return bar(time, new BigDecimal("100"));
    }

    private TornStockMarketBar15mDO bar(LocalDateTime time, BigDecimal price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1001);
        bar.setBarStartTime(time);
        bar.setBarEndTime(time.plusMinutes(15));
        bar.setLastSampleTime(time.plusMinutes(15));
        bar.setSampleCount(10);
        bar.setUsable(Boolean.TRUE);
        bar.setLastPrice(price);
        return bar;
    }


    private TornStockStrategyFeature15mDO feature(LocalDateTime time, boolean ready) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(1001);
        feature.setStocksShortname("TEST");
        feature.setBarStartTime(time);
        feature.setReferencePrice(new BigDecimal("100"));
        feature.setMa1d(new BigDecimal("100"));
        feature.setMa7d(new BigDecimal("100"));
        feature.setMa30d(new BigDecimal("100"));
        feature.setZscore1d(new BigDecimal("-3.5"));
        feature.setZscore7d(new BigDecimal("-1"));
        feature.setZscore30d(new BigDecimal("-1"));
        feature.setReturn6h(new BigDecimal("-0.01"));
        feature.setReturn1d(new BigDecimal("-0.01"));
        feature.setReturn7d(new BigDecimal("-0.005"));
        feature.setReturn14d(new BigDecimal("-0.03"));
        feature.setLow30d(new BigDecimal("90"));
        feature.setHigh30d(new BigDecimal("110"));
        feature.setWidth30d(new BigDecimal("0.20"));
        feature.setPosition30(new BigDecimal("0.05"));
        feature.setPctAbove30dLow(new BigDecimal("0.001"));
        feature.setPctBelow30dHigh(new BigDecimal("0.09"));
        feature.setStrategyReady(ready);
        feature.setFeatureVersion("FEATURE_V1");
        return feature;
    }

    private TornStockStrategyFeature15mDO incompleteFeature(LocalDateTime time) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(1001);
        feature.setStocksShortname("TEST");
        feature.setBarStartTime(time);
        feature.setReferencePrice(new BigDecimal("100"));
        feature.setStrategyReady(Boolean.TRUE);
        feature.setFeatureVersion("FEATURE_V1");
        return feature;
    }

    private TornStockMonthlyStateDO monthlyState() {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(1001);
        state.setStrategyFitPrior("NARROW");
        state.setMaturity("M4_MATURE");
        state.setRiskLevel("NONE");
        return state;
    }
}
