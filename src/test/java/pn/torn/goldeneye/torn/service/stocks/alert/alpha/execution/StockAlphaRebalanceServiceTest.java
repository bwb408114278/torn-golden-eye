package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * α策略原子换仓服务编排契约测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@ExtendWith(MockitoExtension.class)
class StockAlphaRebalanceServiceTest {
    private static final LocalDateTime EXECUTION_TIME = LocalDateTime.of(2026, 9, 5, 0, 15);

    @Mock
    private TornStockAlphaDecisionDAO decisionDAO;
    @Mock
    private TornStockPortfolioSlotDAO slotDAO;
    @Mock
    private TornStockVirtualBatchDAO batchDAO;
    @Mock
    private StockPortfolioService portfolioService;
    @Mock
    private StockShadowRecordWriter noticeWriter;

    @Test
    void rebalance_persistsAndBindsDatabaseReplacementId() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        TornStockPortfolioSlotDO slot = slot();
        TornStockVirtualBatchDO persisted = new TornStockVirtualBatchDO();
        persisted.setId(99L);
        persisted.setBatchNo("AR-2026-09-05-0-11");
        persisted.setExpectedExitBarTime(EXECUTION_TIME.plusMinutes(15));
        persisted.setEntryReferencePrice(new BigDecimal("10"));
        persisted.setQuantity(1L);
        persisted.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        persisted.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        persisted.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        when(decisionDAO.selectByBusinessKeyForUpdate(LocalDate.of(2026, 9, 5), 0)).thenReturn(decision);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.insertIgnoreConflict(any())).thenReturn(1);
        when(batchDAO.selectByBatchNoForUpdate(persisted.getBatchNo())).thenAnswer(invocation -> {
            persisted.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
            persisted.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
            return null;
        }).thenReturn(persisted);
        when(portfolioService.settleSlotBacked(current, slot, new BigDecimal("110"),
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenAnswer(invocation -> {
                    slot.setAvailableCash(new BigDecimal("1049.45"));
                    return new BigDecimal("549.45");
                });

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter);
        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                LocalDate.of(2026, 9, 5), 0, LocalDateTime.of(2026, 9, 5, 0, 31), snapshot());

        assertEquals(99L, result.boughtBatchId());
        verify(batchDAO).insertIgnoreConflict(any());
        ArgumentCaptor<TornStockVirtualBatchDO> replacementCaptor = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(batchDAO).insertIgnoreConflict(replacementCaptor.capture());
        TornStockVirtualBatchDO replacement = replacementCaptor.getValue();
        assertEquals(StockLedgerTypeEnum.FORMAL.getCode(), replacement.getLedgerType());
        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, replacement.getPortfolioCode());
        assertNotNull(replacement.getFollowUntil());
        assertNotNull(replacement.getFollowMaxPrice());
        assertEquals(99L, slot.getCurrentBatchId());
        assertEquals(99L, decision.getCurrentBatchId());
        ArgumentCaptor<List<TornStockVirtualBatchDO>> entries = ArgumentCaptor.forClass(List.class);
        verify(noticeWriter).writeNoticeAudits(entries.capture(), any(), any(), eq(Boolean.TRUE));
        TornStockVirtualBatchDO noticeBatch = entries.getValue().getFirst();
        assertEquals(99L, noticeBatch.getId());
        assertNotNull(noticeBatch.getExpectedExitBarTime());
    }

    @Test
    void rebalance_rejectsExistingReplacementBatchNo() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        when(decisionDAO.selectByBusinessKeyForUpdate(LocalDate.of(2026, 9, 5), 0)).thenReturn(decision);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.selectByBatchNoForUpdate("AR-2026-09-05-0-11")).thenReturn(current);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter);

        RoundSnapshot snapshot = snapshot();
        LocalDate decisionDate = LocalDate.of(2026, 9, 5);
        LocalDateTime processingTime = LocalDateTime.of(2026, 9, 5, 0, 31);
        assertThrows(IllegalStateException.class, () -> service.rebalance(
                decisionDate, 0, processingTime, snapshot));
    }

    @Test
    void rebalance_alreadyExecuted_retryWritesNoDuplicateNoticeOrFact() {
        TornStockAlphaDecisionDO decision = decision(11L);
        decision.setExecutionStatus("EXECUTED");
        decision.setRebalanceBatchId(99L);
        when(decisionDAO.selectByBusinessKeyForUpdate(LocalDate.of(2026, 9, 5), 0)).thenReturn(decision);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter);
        RoundSnapshot snapshot = snapshot();
        LocalDate decisionDate = LocalDate.of(2026, 9, 5);
        LocalDateTime processingTime = LocalDateTime.of(2026, 9, 5, 0, 31);

        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                decisionDate, 0, processingTime, snapshot);

        assertNull(result.soldBatchId(), "已执行换仓重试不得再次卖出原仓");
        assertEquals(99L, result.boughtBatchId(), "重试必须复用已持久化的换仓批次");
        assertEquals(EXECUTION_TIME, result.executionBarStartTime(), "重试不得改写已持久化执行桶");
        verifyNoInteractions(slotDAO, batchDAO);
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), anyBoolean());
        verify(decisionDAO, never()).updateById(decision);
    }

    @Test
    void rebalance_insertFailure_persistsNoSingleLegFact() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        TornStockPortfolioSlotDO slot = slot();
        when(decisionDAO.selectByBusinessKeyForUpdate(LocalDate.of(2026, 9, 5), 0)).thenReturn(decision);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.insertIgnoreConflict(any())).thenReturn(0);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter);
        RoundSnapshot snapshot = snapshot();
        LocalDate decisionDate = LocalDate.of(2026, 9, 5);
        LocalDateTime processingTime = LocalDateTime.of(2026, 9, 5, 0, 31);

        assertThrows(IllegalStateException.class, () -> service.rebalance(
                decisionDate, 0, processingTime, snapshot));
        assertEquals(7L, decision.getCurrentBatchId());
        assertNull(decision.getRebalanceBatchId());
        assertEquals("PENDING", decision.getExecutionStatus());
        // 两腿持久化、决策状态、槽位与通知审计均未落库,异常由@Transactional整体回滚,不产生单腿事实
        verify(batchDAO, never()).updateById(any(TornStockVirtualBatchDO.class));
        verify(decisionDAO, never()).updateById(decision);
        verify(slotDAO, never()).updateById(slot);
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), anyBoolean());
    }

    private TornStockAlphaDecisionDO decision(Long id) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setId(id);
        decision.setDecisionBusinessDate(LocalDate.of(2026, 9, 5));
        decision.setPhase(0);
        decision.setDecisionType(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name());
        decision.setCurrentBatchId(7L);
        decision.setSelectedStocksId(2002);
        decision.setExecutionStatus("PENDING");
        decision.setExecutionBarStartTime(EXECUTION_TIME);
        return decision;
    }

    private TornStockVirtualBatchDO currentBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(7L);
        batch.setBatchNo("A-OLD");
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setStocksId(1001);
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(new BigDecimal("100"));
        batch.setExpectedExitBarTime(EXECUTION_TIME);
        batch.setQuantity(5L);
        batch.setRemainingCash(new BigDecimal("500"));
        return batch;
    }

    private TornStockPortfolioSlotDO slot() {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(1L);
        slot.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        slot.setSlotNo(1);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        slot.setAvailableCash(new BigDecimal("500"));
        slot.setCurrentBatchId(7L);
        return slot;
    }

    private RoundSnapshot snapshot() {
        return new RoundSnapshot(List.of(bar(1001, new BigDecimal("110")), bar(2002, new BigDecimal("10"))),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), EXECUTION_TIME);
    }

    private TornStockMarketBar15mDO bar(Integer stocksId, BigDecimal price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname("S" + stocksId);
        bar.setBarStartTime(EXECUTION_TIME);
        bar.setBarEndTime(EXECUTION_TIME.plusMinutes(15));
        bar.setLastPrice(price);
        bar.setUsable(true);
        return bar;
    }
}
