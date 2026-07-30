package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 股票回放四轨道隔离服务测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放四轨道隔离服务测试")
class StockReplayAllTracksServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    @DisplayName("四条轨道同时运行_每条轨道产生独立审计路径")
    void run_allTracks_generatesIndependentTrackRows() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        StockReplayRequest request = new StockReplayRequest("VIP_ALL_TRACKS", start,
                start.plusMinutes(15), "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1",
                "ALLOC_V1", "MSG_V1", outputDirectory, EnumSet.allOf(StockReplayTrackEnum.class));
        StockReplayInputLoader inputLoader = Mockito.mock(StockReplayInputLoader.class);
        when(inputLoader.load(request)).thenReturn(new StockReplayInput(
                List.of(bar(start)), List.of(feature(start)), List.of(), List.of()));
        StockReplayDecisionEngine decisionEngine = new StockReplayDecisionEngine(
                new StockReplayPortfolioEngine(),
                List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                        new StrictReboundConfirmBuyStrategy()),
                new StockEligibilityService());

        StockReplayResult result = new StockReplayService(new StockReplayArtifactWriter(), inputLoader,
                decisionEngine).run(request);

        String equity = Files.readString(outputDirectory.resolve(result.runId() + "-equity-curve.csv"));
        assertTrue(equity.contains("FORMAL_5_SLOT"));
        assertTrue(equity.contains("UNLIMITED_SHADOW"));
        assertTrue(equity.contains("REJECTED_OBSERVATION"));
        assertTrue(equity.contains("DYNAMIC_SELL_SHADOW"));
    }

    private TornStockMarketBar15mDO bar(LocalDateTime time) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1001);
        bar.setBarStartTime(time);
        bar.setBarEndTime(time.plusMinutes(15));
        bar.setLastSampleTime(time.plusMinutes(15));
        bar.setSampleCount(10);
        bar.setUsable(Boolean.TRUE);
        bar.setLastPrice(new BigDecimal("100"));
        return bar;
    }

    private TornStockStrategyFeature15mDO feature(LocalDateTime time) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(1001);
        feature.setStocksShortname("TEST");
        feature.setBarStartTime(time);
        feature.setReferencePrice(new BigDecimal("100"));
        feature.setStrategyReady(Boolean.FALSE);
        feature.setFeatureVersion("FEATURE_V1");
        return feature;
    }
}
