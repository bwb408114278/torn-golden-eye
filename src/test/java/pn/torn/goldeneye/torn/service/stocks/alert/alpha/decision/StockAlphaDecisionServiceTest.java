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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * α策略日线决策服务测试。
 * <p>
 * 只覆盖决策幂等与执行桶一致性契约:同桶复用、跨桶拒绝、已完成决策只走幂等短路、来源事实不一致fail-closed。
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
     * 首次决策使用的执行桶。
     */
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 9, 6, 0, 15);
    /**
     * 延迟重试使用的执行桶。
     */
    private static final LocalDateTime LATER_EXECUTION_BAR = EXECUTION_BAR.plusMinutes(15);
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
    @DisplayName("已有决策执行桶与当前轮次一致_复用持久化决策且不重复插入")
    void decide_reusesPersistedDecisionWhenExecutionBarMatches() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult reused = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertTrue(reused.ready(), "同一执行桶重试必须复用原决策");
        assertEquals(persisted.getSelectedStocksId(), reused.targetStocksId());
        assertEquals(DECISION_DATE, reused.decisionDate());
        assertEquals(PHASE, reused.phase());
        assertEquals(EXECUTION_BAR, reused.executionBarStartTime());
        assertFalse(reused.rankings().isEmpty(), "同桶复用时排名来源与持久化摘要一致");
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
        verify(dailyCloseService, times(1)).persistRankings(eq(DECISION_DATE), any(), any());
    }

    @Test
    @DisplayName("PENDING决策执行桶与当前轮次不一致_拒绝消费且不新建决策")
    void decide_rejectsPendingDecisionFromOtherExecutionBar() {
        firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult later = service.decide(DECISION_DATE, LATER_EXECUTION_BAR);

        assertFalse(later.ready(), "待消费决策只能由持久化执行桶消费");
        assertNull(later.targetStocksId(), "跨桶不得返回目标股票");
        assertNull(later.phase(), "跨桶不得消费phase");
        assertEquals(EXECUTION_BAR, inserted[0].getExecutionBarStartTime(), "延迟重试不得改写已持久化执行桶");
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("已完成决策_只进入幂等短路并返回持久化目标与执行桶")
    void decide_returnsPersistedFactsForExecutedDecision() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        persisted.setExecutionStatus("EXECUTED");
        persisted.setCurrentBatchId(99L);
        persisted.setSelectedStocksId(StockAlphaRuleDefinition.MEMBER_COUNT);
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult result = service.decide(DECISION_DATE, LATER_EXECUTION_BAR);

        assertTrue(result.ready(), "已完成决策必须进入幂等短路");
        assertEquals(StockAlphaRuleDefinition.MEMBER_COUNT, result.targetStocksId(),
                "已完成决策不得被本次新算目标覆盖");
        assertEquals(EXECUTION_BAR, result.executionBarStartTime(), "已完成决策不得改写执行桶");
        assertTrue(result.rankings().isEmpty(), "跨执行桶不得返回本次新算排名,避免两套事实混用");
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
        verify(dailyCloseService, times(1)).persistRankings(eq(DECISION_DATE), any(), any());
    }

    @Test
    @DisplayName("来源摘要与本次计算不一致_fail-closed不消费")
    void decide_rejectsDecisionWithInconsistentSourceDigest() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        persisted.setSourceSnapshotDigest("OTHER_DIGEST");
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult result = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertFalse(result.ready(), "来源摘要不一致时不得消费已有决策");
        assertNull(result.targetStocksId());
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("决策类型与本次计算不一致_fail-closed不消费")
    void decide_rejectsDecisionWithInconsistentDecisionType() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        persisted.setDecisionType(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name());
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult result = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertFalse(result.ready(), "决策类型不一致时不得复用已有决策");
        assertNull(result.targetStocksId());
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
    }

    /**
     * 执行首次决策并返回已落库的决策对象,后续轮次将以该对象作为"已持久化决策"被读取。
     *
     * @return 已落库决策
     */
    private TornStockAlphaDecisionDO firstDecision() {
        when(dailyCloseService.loadDailyCloses(DECISION_DATE)).thenReturn(completeWindow(DECISION_DATE));
        when(decisionDAO.insertIgnoreConflict(any(TornStockAlphaDecisionDO.class))).thenAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(DECISION_ID);
            return 1;
        });
        when(decisionDAO.selectByBusinessKeyForUpdate(DECISION_DATE, PHASE))
                .thenAnswer(invocation -> inserted[0]);
        StockAlphaDecisionService.DecisionResult first =
                new StockAlphaDecisionService(dailyCloseService, decisionDAO).decide(DECISION_DATE, EXECUTION_BAR);
        assertTrue(first.ready(), "预热完成且决策日应可决策");
        ArgumentCaptor<TornStockAlphaDecisionDO> captor =
                ArgumentCaptor.forClass(TornStockAlphaDecisionDO.class);
        verify(decisionDAO).insertIgnoreConflict(captor.capture());
        return captor.getValue();
    }

    /**
     * 构造满足预热要求的完整共同有效日收盘窗口。
     *
     * @param endDate 最后一个共同有效日
     * @return 按日期和股票ID索引的收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> completeWindow(LocalDate endDate) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily = new HashMap<>();
        int warmupDays = StockAlphaRuleDefinition.WARMUP_COMMON_DAYS;
        for (int dayIndex = 0; dayIndex < warmupDays; dayIndex++) {
            LocalDate date = endDate.minusDays(warmupDays - 1L - dayIndex);
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> byStock = new HashMap<>();
            for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
                byStock.put(stocksId, new StockAlphaDailyCloseCalculator.CloseResult(stocksId, date,
                        BigDecimal.valueOf(10L + dayIndex + stocksId), (long) stocksId, date.atTime(23, 45)));
            }
            daily.put(date, byStock);
        }
        return daily;
    }
}
