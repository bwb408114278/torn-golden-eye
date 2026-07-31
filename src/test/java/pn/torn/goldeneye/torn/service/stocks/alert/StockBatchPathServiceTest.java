package pn.torn.goldeneye.torn.service.stocks.alert;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService.ExitEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 股票批次路径服务测试，覆盖开放批次路径更新(peak/trough/MFE/MAE)和退出条件评估的核心逻辑。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.26
 */
@DisplayName("股票批次路径服务测试")
@ExtendWith(MockitoExtension.class)
class StockBatchPathServiceTest {

    @Mock
    private StockBatchExitService batchExitService;

    @InjectMocks
    private StockBatchPathService batchPathService;

    private static final Integer STOCKS_ID = 1;
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 26, 10, 15);
    private static final BigDecimal ENTRY_PRICE = new BigDecimal("100.00");

    @BeforeEach
    void setUp() {
        batchPathService = new StockBatchPathService(batchExitService);
    }

    @Test
    @DisplayName("路径更新_bar不可用_跳过返回空列表")
    void updatePaths_barNotUsable_returnsEmptyList() {
        TornStockVirtualBatchDO batch = buildOpenBatch();
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch));
        List<TornStockBatchMarkDO> marks = batchPathService.updatePathsAndEvaluateExits(
                snapshot, Map.of(STOCKS_ID, bar), Map.of(), ROUND_TIME);

        assertTrue(marks.isEmpty());
    }

    @Test
    @DisplayName("路径更新_未命中退出_记录真实HOLD决定和特征快照")
    void updatePathsAndEvaluateExits_noExit_recordsHoldDecisionAndFeatureSnapshot() {
        TornStockVirtualBatchDO batch = buildOpenBatch();
        batch.setPeakPrice(new BigDecimal("105.00"));
        batch.setTroughPrice(new BigDecimal("98.00"));
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("110.00"));
        TornStockStrategyFeature15mDO feature = buildFeature();
        ExitEvaluation noExit = new ExitEvaluation(false, null, "未命中任何退出规则");
        when(batchExitService.evaluateExit(any(), any(), any(), any(), any(), any())).thenReturn(noExit);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch));
        List<TornStockBatchMarkDO> marks = batchPathService.updatePathsAndEvaluateExits(
                snapshot, Map.of(STOCKS_ID, bar), Map.of(STOCKS_ID, feature), ROUND_TIME);

        assertEquals(1, marks.size());
        assertEquals(new BigDecimal("110.00"), batch.getPeakPrice());
        assertEquals(new BigDecimal("98.00"), batch.getTroughPrice());
        TornStockBatchMarkDO mark = marks.getFirst();
        assertEquals("HOLD", mark.getFormalDecision());
        assertEquals("未命中任何退出规则", mark.getFormalReason());
        assertFeatureDecimal(mark.getFeatureSnapshot(), "currentPrice", new BigDecimal("110.00"));
        assertFeatureDecimal(mark.getFeatureSnapshot(), "position30", new BigDecimal("0.50"));
        assertFeatureDecimal(mark.getFeatureSnapshot(), "low30d", new BigDecimal("90.00"));
        assertFeatureDecimal(mark.getFeatureSnapshot(), "high30d", new BigDecimal("110.00"));
        assertFeatureText(mark.getFeatureSnapshot(), "featureVersion", "feature-1");
        assertFeatureText(mark.getFeatureSnapshot(), "sellRuleVersion",
                StockRoundTransactionService.SELL_RULE_VERSION);
    }

    @Test
    @DisplayName("路径更新_价格上涨_mfe正确计算")
    void updatePaths_priceRises_mfeCorrectlyCalculated() {
        TornStockVirtualBatchDO batch = buildOpenBatch();
        batch.setPeakPrice(ENTRY_PRICE);
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("105.00"));
        stubNoExit();

        RoundSnapshot snapshot = buildSnapshot(List.of(batch));
        batchPathService.updatePathsAndEvaluateExits(snapshot, Map.of(STOCKS_ID, bar), Map.of(), ROUND_TIME);

        BigDecimal expectedMfe = new BigDecimal("105.00")
                .subtract(ENTRY_PRICE)
                .divide(ENTRY_PRICE, 18, java.math.RoundingMode.HALF_UP);
        assertEquals(0, batch.getMfe().compareTo(expectedMfe));
    }

    @Test
    @DisplayName("路径更新_价格下跌_mae正确计算")
    void updatePaths_priceFalls_maeCorrectlyCalculated() {
        TornStockVirtualBatchDO batch = buildOpenBatch();
        batch.setTroughPrice(ENTRY_PRICE);
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("95.00"));
        stubNoExit();

        RoundSnapshot snapshot = buildSnapshot(List.of(batch));
        batchPathService.updatePathsAndEvaluateExits(snapshot, Map.of(STOCKS_ID, bar), Map.of(), ROUND_TIME);

        BigDecimal expectedMae = new BigDecimal("95.00")
                .subtract(ENTRY_PRICE)
                .divide(ENTRY_PRICE, 18, java.math.RoundingMode.HALF_UP);
        assertEquals(0, batch.getMae().compareTo(expectedMae));
    }

    @Test
    @DisplayName("路径更新_命中目标退出_记录真实SELL决定和特征快照")
    void updatePathsAndEvaluateExits_targetExit_recordsSellDecisionAndFeatureSnapshot() {
        TornStockVirtualBatchDO batch = buildOpenBatch();
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.00"));
        TornStockStrategyFeature15mDO feature = buildFeature();

        ExitEvaluation exitEval = new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_TARGET, "目标退出");
        when(batchExitService.evaluateExit(any(), any(), any(), any(), any(), any())).thenReturn(exitEval);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch));
        List<TornStockBatchMarkDO> marks = batchPathService.updatePathsAndEvaluateExits(
                snapshot, Map.of(STOCKS_ID, bar), Map.of(STOCKS_ID, feature), ROUND_TIME);

        assertEquals(StockBatchStatusEnum.EXIT_PENDING.getCode(), batch.getBatchStatus());
        assertEquals(ROUND_TIME, batch.getExitSignalTime());
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET.getCode(), batch.getExitReason());
        TornStockBatchMarkDO mark = marks.getFirst();
        assertEquals("SELL", mark.getFormalDecision());
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET.getCode(), mark.getFormalReason());
        assertFeatureDecimal(mark.getFeatureSnapshot(), "currentPrice", new BigDecimal("101.00"));
        assertFeatureDecimal(mark.getFeatureSnapshot(), "position30", new BigDecimal("0.50"));
        assertFeatureText(mark.getFeatureSnapshot(), "sellRuleVersion",
                StockRoundTransactionService.SELL_RULE_VERSION);
    }

    // ==================== Helper Methods ====================

    /**
     * 断言特征快照中的数值字段，先校验节点存在再取值，避免空指针。
     *
     * @param json     特征快照JSON
     * @param field    字段名
     * @param expected 期望值
     */
    private static void assertFeatureDecimal(String json, String field, BigDecimal expected) {
        JsonNode node = JsonUtils.getNode(json, field);
        assertNotNull(node, "特征快照缺少字段: " + field);
        assertEquals(0, node.decimalValue().compareTo(expected));
    }

    /**
     * 断言特征快照中的文本字段，先校验节点存在再取值，避免空指针。
     *
     * @param json     特征快照JSON
     * @param field    字段名
     * @param expected 期望值
     */
    private static void assertFeatureText(String json, String field, String expected) {
        JsonNode node = JsonUtils.getNode(json, field);
        assertNotNull(node, "特征快照缺少字段: " + field);
        assertEquals(expected, node.asText());
    }


    private TornStockVirtualBatchDO buildOpenBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setBatchNo("F2026072610001");
        batch.setStocksId(STOCKS_ID);
        batch.setStocksShortname("TST");
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(ENTRY_PRICE);
        batch.setEntryTime(ROUND_TIME.minusMinutes(15));
        batch.setQuantity(1000L);
        batch.setPeakPrice(ENTRY_PRICE);
        batch.setTroughPrice(ENTRY_PRICE);
        batch.setMfe(BigDecimal.ZERO);
        batch.setMae(BigDecimal.ZERO);
        batch.setPeakDrawdown(BigDecimal.ZERO);
        batch.setCurrentNetReturn(BigDecimal.ZERO);
        return batch;
    }

    private TornStockMarketBar15mDO buildBar(boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(STOCKS_ID);
        bar.setBarStartTime(ROUND_TIME);
        bar.setBarEndTime(ROUND_TIME.plusMinutes(15));
        bar.setLastPrice(ENTRY_PRICE);
        bar.setSampleCount(usable ? 15 : 5);
        bar.setLastSampleTime(usable ? ROUND_TIME.plusMinutes(14) : ROUND_TIME.minusMinutes(10));
        bar.setUsable(usable);
        return bar;
    }

    private TornStockStrategyFeature15mDO buildFeature() {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(STOCKS_ID);
        feature.setPosition30(new BigDecimal("0.50"));
        feature.setLow30d(new BigDecimal("90.00"));
        feature.setHigh30d(new BigDecimal("110.00"));
        feature.setFeatureVersion("feature-1");
        return feature;
    }

    private void stubNoExit() {
        when(batchExitService.evaluateExit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExitEvaluation(false, null, "未命中任何退出规则"));
    }

    private RoundSnapshot buildSnapshot(List<TornStockVirtualBatchDO> activeBatches) {
        return new RoundSnapshot(
                List.of(), List.of(), List.of(), activeBatches, List.of(), List.of(), List.of(), ROUND_TIME
        );
    }
}
