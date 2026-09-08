package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * α策略初始入场服务测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@DisplayName("α策略初始入场服务测试")
@ExtendWith(MockitoExtension.class)
class StockAlphaEntryServiceTest {
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 9, 5, 10, 0);

    @Mock
    private TornStockAlphaDecisionDAO decisionDAO;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDAO;
    @Mock
    private StockShadowRecordWriter noticeWriter;

    @Test
    @DisplayName("初始入场_持久化待入场批次并预留资金且标记决策已执行")
    void createInitialEntry_shouldPersistAlphaBatchReserveSlotAndExecuteDecision() {
        TornStockAlphaDecisionDO decision = decision();
        TornStockPortfolioSlotDO slot = slot();
        TornStockMarketBar15mDO bar = bar();
        TornStockVirtualBatchDO persisted = persistedBatch(decision, slot);
        when(decisionDAO.selectPendingInitialEntryForUpdate(ROUND_TIME.toLocalDate().minusDays(1), 0, ROUND_TIME))
                .thenReturn(decision);
        when(virtualBatchDAO.insertIgnoreConflict(any(TornStockVirtualBatchDO.class))).thenReturn(1);
        when(virtualBatchDAO.selectByBatchNoForUpdate(any())).thenReturn(persisted);

        StockAlphaEntryService service = new StockAlphaEntryService(
                decisionDAO, virtualBatchDAO, new StockPortfolioService());
        TornStockVirtualBatchDO result = service.createInitialEntry(
                ROUND_TIME, snapshot(slot, bar), decision.getDecisionBusinessDate(), decision.getPhase(),
                ROUND_TIME.plusMinutes(16));

        assertNotNull(result);
        ArgumentCaptor<TornStockVirtualBatchDO> insertedBatch = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).insertIgnoreConflict(insertedBatch.capture());
        assertEquals("ALPHA", insertedBatch.getValue().getPrimaryStrategy());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), insertedBatch.getValue().getBatchStatus());
        assertAlphaAuditSource(insertedBatch.getValue(), decision);
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), result.getBatchStatus());
        assertEquals(StockSlotStatusEnum.RESERVED.getCode(), slot.getSlotStatus());
        assertEquals(BigDecimal.ZERO, slot.getAvailableCash());
        assertEquals(BigDecimal.TEN, slot.getReservedCash());
        assertEquals("EXECUTED", decision.getExecutionStatus());
        assertEquals(persisted.getId(), decision.getCurrentBatchId());
        verify(decisionDAO).updateById(decision);
    }

    @Test
    @DisplayName("初始入场_轮次不是持久化执行桶时拒绝且不写批次")
    void createInitialEntry_rejectsRoundTimeOtherThanPersistedExecutionBar() {
        TornStockAlphaDecisionDO decision = decision();
        TornStockPortfolioSlotDO slot = slot();
        TornStockMarketBar15mDO bar = bar();
        when(decisionDAO.selectPendingInitialEntryForUpdate(any(), anyInt(), any())).thenReturn(decision);

        StockAlphaEntryService service = new StockAlphaEntryService(
                decisionDAO, virtualBatchDAO, new StockPortfolioService());
        RoundSnapshot snapshot = snapshot(slot, bar);
        LocalDate decisionDate = decision.getDecisionBusinessDate();
        int phase = decision.getPhase();
        LocalDateTime laterRoundTime = ROUND_TIME.plusMinutes(15);
        LocalDateTime actualProcessingTime = ROUND_TIME.plusMinutes(31);

        assertThrows(IllegalStateException.class, () -> service.createInitialEntry(
                laterRoundTime, snapshot, decisionDate, phase, actualProcessingTime));
        verify(virtualBatchDAO, never()).insertIgnoreConflict(any(TornStockVirtualBatchDO.class));
        verify(decisionDAO, never()).updateById(decision);
    }

    /**
     * 校验α批次的来源决策、规则版本与旧版风格/风险字段取值。
     *
     * @param batch    已组装批次
     * @param decision 来源决策
     */
    private void assertAlphaAuditSource(TornStockVirtualBatchDO batch, TornStockAlphaDecisionDO decision) {
        assertEquals(decision.getId(), batch.getAlphaDecisionId(), "α批次必须可回查来源决策");
        assertEquals(StockAlphaRuleDefinition.RULE_VERSION, batch.getBuyRuleVersion(), "α批次必须保存α规则版本");
        assertEquals(StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getStylePrior());
        assertEquals(StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getStyleMaturity());
        assertEquals(StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getRiskLevel());
        assertEquals(StockAlphaRuleDefinition.STYLE_RULE_VERSION, batch.getStyleRuleVersion());
        assertEquals(StockAlphaRuleDefinition.RISK_RULE_VERSION, batch.getRiskRuleVersion());
    }

    private TornStockAlphaDecisionDO decision() {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setId(11L);
        decision.setDecisionBusinessDate(ROUND_TIME.toLocalDate().minusDays(1));
        decision.setCommonDayIndex(60);
        decision.setPhase(0);
        decision.setDecisionType("ALPHA_INITIAL_ENTRY");
        decision.setSelectedStocksId(1001);
        decision.setExecutionBarStartTime(ROUND_TIME);
        decision.setSourceSnapshotDigest("digest");
        decision.setExecutionStatus("PENDING");
        return decision;
    }

    private TornStockPortfolioSlotDO slot() {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(21L);
        slot.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        slot.setSlotNo(1);
        slot.setAvailableCash(BigDecimal.TEN);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        return slot;
    }

    private TornStockMarketBar15mDO bar() {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1001);
        bar.setStocksShortname("TEST");
        bar.setBarStartTime(ROUND_TIME);
        bar.setBarEndTime(ROUND_TIME.plusMinutes(15));
        bar.setUsable(true);
        bar.setLastPrice(BigDecimal.ONE);
        return bar;
    }

    private TornStockVirtualBatchDO persistedBatch(TornStockAlphaDecisionDO decision,
                                                   TornStockPortfolioSlotDO slot) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(31L);
        batch.setBatchNo("A20260904-0");
        batch.setAlphaDecisionId(decision.getId());
        batch.setSlotId(slot.getId());
        batch.setStocksId(decision.getSelectedStocksId());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        return batch;
    }

    private RoundSnapshot snapshot(TornStockPortfolioSlotDO slot, TornStockMarketBar15mDO bar) {
        return new RoundSnapshot(List.of(bar), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(slot), ROUND_TIME);
    }
}
