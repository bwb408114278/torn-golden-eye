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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * α策略原子换仓服务编排契约测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@ExtendWith(MockitoExtension.class)
class StockAlphaRebalanceServiceTest {
    private static final LocalDateTime DECISION_TIME = LocalDateTime.of(2026, 9, 5, 0, 10);
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
        when(decisionDAO.selectByBusinessKeyForUpdate(LocalDate.of(2026, 9, 5), 0)).thenReturn(decision);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.insertIgnoreConflict(any())).thenReturn(1);
        when(batchDAO.selectByBatchNoForUpdate(persisted.getBatchNo())).thenReturn(null, persisted);
        when(portfolioService.settleSlotBacked(current, slot, new BigDecimal("110"),
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenAnswer(invocation -> {
                    slot.setAvailableCash(new BigDecimal("1049.45"));
                    return new BigDecimal("549.45");
                });

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter);
        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                LocalDate.of(2026, 9, 5), DECISION_TIME, LocalDateTime.of(2026, 9, 5, 0, 31), snapshot());

        assertEquals(99L, result.boughtBatchId());
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

        assertThrows(IllegalStateException.class, () -> service.rebalance(
                LocalDate.of(2026, 9, 5), DECISION_TIME, LocalDateTime.of(2026, 9, 5, 0, 31), snapshot()));
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
        batch.setLedgerType(StockLedgerTypeEnum.VIP_ALPHA.getCode());
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
