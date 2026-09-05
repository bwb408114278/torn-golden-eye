package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * α策略初始入场服务测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
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
                decisionDAO, virtualBatchDAO, new StockPortfolioService(), noticeWriter);
        TornStockVirtualBatchDO result = service.createInitialEntry(
                ROUND_TIME, snapshot(slot, bar), decision.getDecisionBusinessDate(), decision.getPhase());

        assertNotNull(result);
        ArgumentCaptor<TornStockVirtualBatchDO> insertedBatch = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).insertIgnoreConflict(insertedBatch.capture());
        assertEquals("ALPHA", insertedBatch.getValue().getPrimaryStrategy());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), insertedBatch.getValue().getBatchStatus());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), result.getBatchStatus());
        assertEquals(StockSlotStatusEnum.RESERVED.getCode(), slot.getSlotStatus());
        assertEquals(BigDecimal.ZERO, slot.getAvailableCash());
        assertEquals(BigDecimal.TEN, slot.getReservedCash());
        assertEquals("EXECUTED", decision.getExecutionStatus());
        assertEquals(persisted.getId(), decision.getCurrentBatchId());
        verify(decisionDAO).updateById(decision);
        verify(noticeWriter).writeNoticeAudits(List.of(persisted), List.of(), ROUND_TIME);
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
