package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.BuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.SignalEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.StockShadowService.StockSignalEventContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StrictReboundConfirmBuyStrategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RANGE绝对趋势守卫链路测试 - {@code StockBuySignalEvaluator → StockShadowRecordWriter}。
 * <p>
 * 保护R2-P1-4: 缺失 {@code return7d}、缺失 MA7、缺失 MA30 时,守卫必须记录为数据不足
 * ({@link RangeLowerBuyStrategy#TREND_GUARD_DATA_INSUFFICIENT}) 而非
 * {@code ABSOLUTE_TREND_GUARD_FAILED}; 正式候选为0, 信号事件与拒绝观察批次均记录数据不足语义。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RANGE绝对趋势守卫链路测试")
class RangeTrendGuardChainTest {

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private TornStockNoticeAuditDAO noticeAuditDao;
    @Mock
    private ProjectProperty projectProperty;

    private StockShadowService shadowService;
    private StockShadowRecordWriter shadowRecordWriter;
    private StockBuySignalEvaluator evaluator;

    private static final Integer STOCKS_ID = 1001;
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final LocalDate MONTH = ROUND_TIME.toLocalDate().withDayOfMonth(1);

    @BeforeEach
    void setUp() {
        shadowService = mock(StockShadowService.class);
        when(shadowService.recordSignalEvent(any())).thenAnswer(inv -> {
            StockSignalEventContext ctx = inv.getArgument(0);
            TornStockSignalEventDO event = new TornStockSignalEventDO();
            event.setId(1L);
            event.setStocksId(ctx.stocksId());
            event.setStocksShortname(ctx.stocksShortname());
            event.setStrategyType(ctx.strategyType());
            return event;
        });
        shadowRecordWriter = new StockShadowRecordWriter(shadowService, noticeAuditDao, projectProperty);
        List<StockBuyStrategy> strategies = List.of(
                new DeepMeanReversionBuyStrategy(),
                new RangeLowerBuyStrategy(),
                new StrictReboundConfirmBuyStrategy());
        evaluator = new StockBuySignalEvaluator(
                strategies, new StockEligibilityService(), new StockPortfolioService(),
                virtualBatchDao, shadowRecordWriter);
    }

    @Test
    @DisplayName("缺失return7d_正式批次为0_事件与拒绝观察记录数据不足")
    void missingReturn7d_formalBatchZero_dataInsufficientLedger() {
        BuySignalResult result = evaluateWithMissingFields(null, new BigDecimal("998"), new BigDecimal("1000"));

        assertFormalBatchZeroAndDataInsufficient(result);
    }

    @Test
    @DisplayName("缺失MA7_正式批次为0_事件与拒绝观察记录数据不足")
    void missingMa7_formalBatchZero_dataInsufficientLedger() {
        BuySignalResult result = evaluateWithMissingFields(new BigDecimal("-0.01"), null, new BigDecimal("1000"));

        assertFormalBatchZeroAndDataInsufficient(result);
    }

    @Test
    @DisplayName("缺失MA30_正式批次为0_事件与拒绝观察记录数据不足")
    void missingMa30_formalBatchZero_dataInsufficientLedger() {
        BuySignalResult result = evaluateWithMissingFields(new BigDecimal("-0.01"), new BigDecimal("998"), null);

        assertFormalBatchZeroAndDataInsufficient(result);
    }

    private void assertFormalBatchZeroAndDataInsufficient(BuySignalResult result) {
        assertEquals(0, result.formalCandidates().size(), "数据不足时正式候选必须为0");
        assertEquals(1, result.allEvaluations().size(), "应产生一个信号评估");
        SignalEvaluation evaluation = result.allEvaluations().getFirst();
        assertFalse(evaluation.acceptedFormal(), "数据不足时不得接纳正式批次");
        assertEquals(StockEligibilityResultEnum.REJECTED, evaluation.eligibilityResult().result(),
                "数据不足时资格必须为REJECTED");
        assertEquals(List.of(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT),
                evaluation.eligibilityResult().reasons(),
                "资格原因必须是数据不足而非阈值失败");

        shadowRecordWriter.writeShadowRecords(
                result.allEvaluations(), List.of(), Map.of(), Map.of(), ROUND_TIME);

        ArgumentCaptor<StockSignalEventContext> eventCaptor =
                ArgumentCaptor.forClass(StockSignalEventContext.class);
        verify(shadowService).recordSignalEvent(eventCaptor.capture());
        StockSignalEventContext eventCtx = eventCaptor.getValue();
        assertEquals("REJECTED", eventCtx.portfolioDecision(), "数据不足时组合决策必须为REJECTED");
        assertTrue(eventCtx.eligibilityReasons().contains(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT),
                "信号事件资格原因必须为数据不足");
        assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT, eventCtx.rejectReason(),
                "信号事件拒绝原因必须为数据不足");

        ArgumentCaptor<TornStockSignalEventDO> eventCaptor2 =
                ArgumentCaptor.forClass(TornStockSignalEventDO.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(shadowService).createRejectedObservationBatch(eventCaptor2.capture(), reasonCaptor.capture());
        assertEquals(RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT, reasonCaptor.getValue(),
                "拒绝观察批次原因必须为数据不足");
    }

    private BuySignalResult evaluateWithMissingFields(BigDecimal return7d, BigDecimal ma7d, BigDecimal ma30d) {
        TornStockStrategyFeature15mDO feature = buildFeature(return7d, ma7d, ma30d);
        TornStockMarketBar15mDO bar = buildBar();
        TornStockMonthlyStateDO monthlyState = buildMonthlyState();
        RoundSnapshot snapshot = new RoundSnapshot(
                List.of(bar), List.of(feature), List.of(monthlyState),
                List.of(), List.of(), List.of(), List.of(), ROUND_TIME);
        return evaluator.evaluateSignals(
                snapshot,
                Map.of(STOCKS_ID, bar),
                Map.of(STOCKS_ID, monthlyState),
                Map.of(),
                ROUND_TIME);
    }

    private TornStockStrategyFeature15mDO buildFeature(BigDecimal return7d, BigDecimal ma7d, BigDecimal ma30d) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(STOCKS_ID);
        feature.setStocksShortname("RANGE");
        feature.setBarStartTime(ROUND_TIME);
        feature.setReferencePrice(new BigDecimal("100.00"));
        feature.setMa1d(new BigDecimal("100.0"));
        feature.setMa7d(ma7d);
        feature.setMa30d(ma30d);
        feature.setZscore1d(new BigDecimal("-1.0"));
        feature.setZscore7d(new BigDecimal("-0.5"));
        feature.setZscore30d(new BigDecimal("-0.3"));
        feature.setReturn6h(new BigDecimal("-0.005"));
        feature.setReturn1d(new BigDecimal("-0.01"));
        feature.setReturn7d(return7d);
        feature.setReturn14d(new BigDecimal("-0.03"));
        feature.setLow30d(new BigDecimal("98.00"));
        feature.setHigh30d(new BigDecimal("102.00"));
        feature.setWidth30d(new BigDecimal("0.04"));
        feature.setPosition30(new BigDecimal("0.05"));
        feature.setPctAbove30dLow(new BigDecimal("0.020408"));
        feature.setPctBelow30dHigh(new BigDecimal("0.019608"));
        feature.setStrategyReady(true);
        feature.setDataQualityReason("");
        feature.setFeatureVersion("1.0.0");
        return feature;
    }

    private TornStockMarketBar15mDO buildBar() {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(STOCKS_ID);
        bar.setStocksShortname("RANGE");
        bar.setBarStartTime(ROUND_TIME);
        bar.setBarEndTime(ROUND_TIME.plusMinutes(15));
        bar.setFirstSampleTime(ROUND_TIME.plusMinutes(1));
        bar.setLastSampleTime(ROUND_TIME.plusMinutes(14));
        bar.setFirstPrice(new BigDecimal("100.00"));
        bar.setLastPrice(new BigDecimal("100.00"));
        bar.setLowPrice(new BigDecimal("99.90"));
        bar.setHighPrice(new BigDecimal("100.10"));
        bar.setSampleCount(12);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(true);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private TornStockMonthlyStateDO buildMonthlyState() {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(STOCKS_ID);
        state.setStocksShortname("RANGE");
        state.setEffectiveMonth(MONTH);
        state.setStrategyFitPrior("RANGING");
        state.setMaturity(StockMaturityEnum.M2_PROVISIONAL.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("RANGING");
        state.setManualOverride(false);
        state.setMetricSnapshot("{}");
        state.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        return state;
    }
}
