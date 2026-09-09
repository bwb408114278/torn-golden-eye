package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * α策略日线决策服务测试。
 * <p>
 * 只覆盖决策时点到执行bar的因果契约与来源审计契约:决策桶到下一根严格连续执行桶、
 * 已存在决策复用原执行桶且不重算、非决策轮次不做重负载、来源摘要一致与不一致的复核结果。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.08
 */
@DisplayName("α策略日线决策服务测试")
@ExtendWith(MockitoExtension.class)
class StockAlphaDecisionServiceTest {
    /**
     * 决策自然日(最后一个共同有效日)。
     */
    private static final LocalDate DECISION_DATE = LocalDate.of(2026, 9, 5);
    /**
     * 决策时点(决策桶)。
     */
    private static final LocalDateTime DECISION_TIME = LocalDateTime.of(2026, 9, 6, 9, 45);
    /**
     * 决策时点推导出的下一根严格连续执行桶。
     */
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 9, 6, 10, 0);
    /**
     * 延迟重试时的决策时点。
     */
    private static final LocalDateTime LATER_DECISION_TIME = LocalDateTime.of(2026, 9, 6, 10, 0);
    /**
     * 预热完成时的phase。
     */
    private static final int PHASE = 0;
    /**
     * 已持久化决策ID。
     */
    private static final long DECISION_ID = 11L;

    @Mock
    private StockAlphaDailyCloseService dailyCloseService;
    @Mock
    private TornStockAlphaDecisionDAO decisionDAO;

    /**
     * 首次决策落库的决策对象,供后续轮次模拟"已持久化决策"。
     */
    private final TornStockAlphaDecisionDO[] inserted = new TornStockAlphaDecisionDO[1];

    @Test
    @DisplayName("首次决策_执行桶为决策桶后第一根bar且信号参考价来自决策事实")
    void decide_persistsExecutionBarAndSignalPriceFromDecisionFacts() {
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);
        stubWarmupCompleted();

        StockAlphaDecisionService.DecisionResult first = service.decide(DECISION_DATE, DECISION_TIME, decisionPrices());

        assertTrue(first.ready(), "预热完成且落在决策日应可决策");
        assertEquals(EXECUTION_BAR, first.executionBarStartTime(), "执行桶必须是决策桶之后第一根严格连续bar");
        assertNotNull(first.targetStocksId());
        ArgumentCaptor<TornStockAlphaDecisionDO> decisionCaptor =
                ArgumentCaptor.forClass(TornStockAlphaDecisionDO.class);
        verify(decisionDAO).insertIgnoreConflict(decisionCaptor.capture());
        assertEquals(EXECUTION_BAR, decisionCaptor.getValue().getExecutionBarStartTime());
        assertNotNull(decisionCaptor.getValue().getSourceSnapshotDigest(), "决策必须携带来源摘要");
        assertEquals(first.targetStocksId(), decisionCaptor.getValue().getSelectedStocksId());
        assertEquals(decisionPrices().get(first.targetStocksId()),
                decisionCaptor.getValue().getSignalReferencePrice(),
                "信号参考价必须固化为决策时点事实,不得使用执行bar价格");
        ArgumentCaptor<List<StockAlphaRankingResult>> rankingsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dailyCloseService).persistRankings(eq(DECISION_DATE), any(), rankingsCaptor.capture());
        assertEquals(first.rankings(), rankingsCaptor.getValue(),
                "新决策的摘要、目标和排名必须来自同一份不可变计算结果");
    }

    @Test
    @DisplayName("已存在决策_延迟重试复用原执行桶且不重排名不改写执行桶")
    void decide_reusesPersistedExecutionBarOnLaterDecisionTime() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult sameBar = service.decide(DECISION_DATE, DECISION_TIME, decisionPrices());
        StockAlphaDecisionService.DecisionResult later = service.decide(DECISION_DATE, LATER_DECISION_TIME, decisionPrices());

        assertTrue(sameBar.ready(), "同一决策桶重试必须复用原决策");
        assertTrue(later.ready(), "已存在决策时后续轮次必须复用同一决策");
        assertEquals(EXECUTION_BAR, later.executionBarStartTime(), "延迟重试不得改写已持久化执行桶");
        assertEquals(EXECUTION_BAR, persisted.getExecutionBarStartTime());
        assertEquals(PHASE, later.phase());
        verify(dailyCloseService, times(1)).loadDailyCloses(DECISION_DATE);
        verify(dailyCloseService, times(1)).persistRankings(eq(DECISION_DATE), any(), any());
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("非决策轮次_不读取完整历史窗口且不排名")
    void decide_skipsHeavyRankingWhenNotOnPhaseBoundary() {
        when(dailyCloseService.commonValidDates(DECISION_DATE)).thenReturn(commonDates(61));
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult result = service.decide(DECISION_DATE, DECISION_TIME, decisionPrices());

        assertFalse(result.ready(), "共同有效日不是60/65/70时不得决策");
        assertEquals(61, result.commonDayCount());
        verify(dailyCloseService, never()).loadDailyCloses(any());
        verify(dailyCloseService, never()).persistRankings(any(), any(), any());
        verify(decisionDAO, never()).selectByBusinessKeyForUpdate(any(), anyInt());
        verify(decisionDAO, never()).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("来源审计_摘要与回查排名一致时可复核且不一致时不可复核")
    void isSourceReproducible_matchesDigestAgainstCurrentRankingSnapshot() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        assertTrue(service.isSourceReproducible(persisted), "来源未变更时决策必须可复核");

        when(dailyCloseService.loadDailyCloses(DECISION_DATE)).thenReturn(completeWindow(100));

        assertFalse(service.isSourceReproducible(persisted),
                "日线快照被后续重建导致排名变化后,决策必须判定为不可复核");
    }

    /**
     * 执行首次决策并返回已落库的决策对象,后续轮次将以该对象作为"已持久化决策"被读取。
     *
     * @return 已落库决策
     */
    private TornStockAlphaDecisionDO firstDecision() {
        stubWarmupCompleted();
        StockAlphaDecisionService.DecisionResult first = new StockAlphaDecisionService(dailyCloseService, decisionDAO)
                .decide(DECISION_DATE, DECISION_TIME, decisionPrices());
        assertTrue(first.ready(), "预热完成且决策日应可决策");
        return inserted[0];
    }

    /**
     * 桩化预热完成的共同有效日与日线收盘窗口。
     */
    private void stubWarmupCompleted() {
        when(dailyCloseService.commonValidDates(DECISION_DATE)).thenReturn(commonDates(60));
        when(dailyCloseService.loadDailyCloses(DECISION_DATE)).thenReturn(completeWindow(0));
        when(decisionDAO.selectByBusinessKeyForUpdate(DECISION_DATE, PHASE))
                .thenAnswer(invocation -> inserted[0]);
        when(decisionDAO.insertIgnoreConflict(any(TornStockAlphaDecisionDO.class)))
                .thenAnswer(invocation -> {
                    inserted[0] = invocation.getArgument(0);
                    inserted[0].setId(DECISION_ID);
                    return 1;
                });
    }

    /**
     * 构造决策时点各股票的已结束bar最后价。
     *
     * @return 股票ID到决策时点参考价的映射
     */
    private Map<Integer, BigDecimal> decisionPrices() {
        Map<Integer, BigDecimal> prices = new HashMap<>();
        for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
            prices.put(stocksId, BigDecimal.valueOf(20L + stocksId));
        }
        return prices;
    }

    /**
     * 构造指定自然日结尾、满足预热要求的完整共同有效日收盘窗口。
     *
     * @param priceOffset 收盘价偏移量,用于模拟日线快照被重建后的价格变化
     * @return 按日期和股票ID索引的收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> completeWindow(int priceOffset) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily = new HashMap<>();
        int warmupDays = StockAlphaRuleDefinition.WARMUP_COMMON_DAYS;
        for (int dayIndex = 0; dayIndex < warmupDays; dayIndex++) {
            LocalDate date = DECISION_DATE.minusDays(warmupDays - 1L - dayIndex);
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> byStock = new HashMap<>();
            for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
                byStock.put(stocksId, new StockAlphaDailyCloseCalculator.CloseResult(stocksId, date,
                        BigDecimal.valueOf(10L + priceOffset + dayIndex + stocksId), (long) stocksId,
                        date.atTime(23, 45)));
            }
            daily.put(date, byStock);
        }
        return daily;
    }

    /**
     * 构造以指定日期结尾的共同有效日序列。
     *
     * @param count 共同有效日数量
     * @return 升序共同有效日
     */
    private List<LocalDate> commonDates(int count) {
        List<LocalDate> dates = new ArrayList<>();
        for (int offset = count - 1; offset >= 0; offset--) {
            dates.add(DECISION_DATE.minusDays(offset));
        }
        return dates;
    }
}
