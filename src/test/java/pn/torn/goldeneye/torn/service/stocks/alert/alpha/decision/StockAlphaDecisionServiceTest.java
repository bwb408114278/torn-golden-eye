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
 * 只覆盖决策幂等与phase消费契约:新决策落库、同执行桶短路、跨桶与已消费phase不追补、
 * 非决策轮次不读取完整历史窗口。
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
    @DisplayName("首次决策_摘要目标与排名来自同一计算结果且排名先于决策落库")
    void decide_persistsSingleCalculationResultForNewDecision() {
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);
        stubWarmupCompleted();

        StockAlphaDecisionService.DecisionResult first = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertTrue(first.ready(), "预热完成且落在决策日应可决策");
        assertNotNull(first.targetStocksId());
        ArgumentCaptor<TornStockAlphaDecisionDO> decisionCaptor =
                ArgumentCaptor.forClass(TornStockAlphaDecisionDO.class);
        verify(decisionDAO).insertIgnoreConflict(decisionCaptor.capture());
        assertNotNull(decisionCaptor.getValue().getSourceSnapshotDigest(), "决策必须携带来源摘要");
        assertEquals(first.targetStocksId(), decisionCaptor.getValue().getSelectedStocksId());
        ArgumentCaptor<List<StockAlphaRankingResult>> rankingsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dailyCloseService).persistRankings(eq(DECISION_DATE), any(), rankingsCaptor.capture());
        assertEquals(first.rankings(), rankingsCaptor.getValue(),
                "新决策的摘要、目标和排名必须来自同一份不可变计算结果");
    }

    @Test
    @DisplayName("同执行桶重试_直接短路且不读取日线不重排名不写排名")
    void decide_shortCircuitsWhenExecutionBarAlreadyDecided() {
        firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult reused = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertTrue(reused.ready(), "同一执行桶重试必须复用原决策");
        assertEquals(DECISION_DATE, reused.decisionDate());
        assertEquals(PHASE, reused.phase());
        assertEquals(EXECUTION_BAR, reused.executionBarStartTime());
        verify(dailyCloseService, times(1)).loadDailyCloses(DECISION_DATE);
        verify(dailyCloseService, times(1)).persistRankings(eq(DECISION_DATE), any(), any());
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("跨执行桶或phase已消费_不消费决策且不改写持久化执行桶")
    void decide_skipsLaterBarWithoutCrossBucketConsumption() {
        TornStockAlphaDecisionDO persisted = firstDecision();
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult later = service.decide(DECISION_DATE, LATER_EXECUTION_BAR);

        assertFalse(later.ready(), "当前phase已由持久化执行桶消费,后续轮次不得追补");
        assertNull(later.targetStocksId());
        assertNull(later.phase());
        assertEquals(EXECUTION_BAR, persisted.getExecutionBarStartTime(), "延迟重试不得改写已持久化执行桶");
        verify(decisionDAO, times(1)).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
        verify(dailyCloseService, times(1)).loadDailyCloses(DECISION_DATE);
    }

    @Test
    @DisplayName("非决策轮次_不读取完整历史窗口且不排名")
    void decide_skipsHeavyRankingWhenNotOnPhaseBoundary() {
        when(dailyCloseService.commonValidDates(DECISION_DATE)).thenReturn(commonDates(61));
        StockAlphaDecisionService service = new StockAlphaDecisionService(dailyCloseService, decisionDAO);

        StockAlphaDecisionService.DecisionResult result = service.decide(DECISION_DATE, EXECUTION_BAR);

        assertFalse(result.ready(), "共同有效日不是60/65/70时不得决策");
        assertEquals(61, result.commonDayCount());
        verify(dailyCloseService, never()).loadDailyCloses(any());
        verify(dailyCloseService, never()).persistRankings(any(), any(), any());
        verify(decisionDAO, never()).insertIgnoreConflict(any(TornStockAlphaDecisionDO.class));
    }

    /**
     * 执行首次决策并返回已落库的决策对象,后续轮次将以该对象作为"已持久化决策"被读取。
     *
     * @return 已落库决策
     */
    private TornStockAlphaDecisionDO firstDecision() {
        stubWarmupCompleted();
        StockAlphaDecisionService.DecisionResult first =
                new StockAlphaDecisionService(dailyCloseService, decisionDAO).decide(DECISION_DATE, EXECUTION_BAR);
        assertTrue(first.ready(), "预热完成且决策日应可决策");
        return inserted[0];
    }

    /**
     * 桩化预热完成的共同有效日与日线收盘窗口。
     */
    private void stubWarmupCompleted() {
        when(dailyCloseService.commonValidDates(DECISION_DATE)).thenReturn(commonDates(60));
        when(dailyCloseService.loadDailyCloses(DECISION_DATE)).thenReturn(completeWindow());
        when(decisionDAO.selectLatestForUpdate()).thenAnswer(invocation -> inserted[0]);
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
     * 构造指定自然日结尾、满足预热要求的完整共同有效日收盘窗口。
     *
     * @return 按日期和股票ID索引的收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> completeWindow() {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily = new HashMap<>();
        int warmupDays = StockAlphaRuleDefinition.WARMUP_COMMON_DAYS;
        for (int dayIndex = 0; dayIndex < warmupDays; dayIndex++) {
            LocalDate date = DECISION_DATE.minusDays(warmupDays - 1L - dayIndex);
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> byStock = new HashMap<>();
            for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
                byStock.put(stocksId, new StockAlphaDailyCloseCalculator.CloseResult(stocksId, date,
                        BigDecimal.valueOf(10L + dayIndex + stocksId), (long) stocksId, date.atTime(23, 45)));
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
