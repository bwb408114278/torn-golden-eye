package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCancelReasonEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEntrySettlementService.EntrySettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * 股票批次成交结算服务测试，覆盖待买入批次成交/取消/过期和待卖出批次成交的核心逻辑。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.26
 */
@DisplayName("股票批次成交结算服务测试")
@ExtendWith(MockitoExtension.class)
class StockEntrySettlementServiceTest {

    @InjectMocks
    private StockEntrySettlementService entrySettlementService;

    private static final Integer STOCKS_ID = 1;
    private static final String STOCKS_SHORTNAME = "TST";
    private static final String BATCH_NO = "F2026072610001";
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 26, 10, 15);
    private static final LocalDateTime EARLIER_TIME = LocalDateTime.of(2026, 7, 26, 10, 0);
    private static final BigDecimal SIGNAL_PRICE = new BigDecimal("100.00");
    private static final BigDecimal ENTRY_PRICE = new BigDecimal("100.50");

    @BeforeEach
    void setUp() {
        entrySettlementService = new StockEntrySettlementService(new StockPortfolioService());
    }

    @Test
    @DisplayName("待买入批次过期_取消并返回ENTRY_DATA_STALE")
    void processEntryPending_batchExpired_cancelledWithStaleReason() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setEntryStaleAt(ROUND_TIME.minusMinutes(1));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(), ROUND_TIME);

        assertEquals(0, result.filledBatches().size());
        assertEquals(1, result.cancelledBatches().size());
        TornStockVirtualBatchDO cancelled = result.cancelledBatches().getFirst();
        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), cancelled.getBatchStatus());
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(), cancelled.getCancelReason());
    }

    @Test
    @DisplayName("待买入批次bar不可用_保持ENTRY_PENDING")
    void processEntryPending_barNotUsable_remainsEntryPending() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.filledBatches().size());
        assertEquals(0, result.cancelledBatches().size());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), batch.getBatchStatus());
    }

    @Test
    @DisplayName("待买入批次bar非连续_保持ENTRY_PENDING")
    void processEntryPending_barNotConsecutive_remainsEntryPending() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setExpectedEntryBarTime(ROUND_TIME.plusMinutes(15));
        TornStockMarketBar15mDO bar = buildBar(true);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.filledBatches().size());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), batch.getBatchStatus());
    }

    @Test
    @DisplayName("待买入批次价格偏离超限_取消并返回ENTRY_PRICE_DEVIATION")
    void processEntryPending_priceDeviationExceeded_cancelledWithDeviationReason() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("102.00"));

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.checkEntryPriceDeviation(SIGNAL_PRICE, new BigDecimal("102.00")))
                    .thenReturn(true);

            RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
            EntrySettlementResult result = entrySettlementService.processEntryPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(0, result.filledBatches().size());
            assertEquals(1, result.cancelledBatches().size());
            TornStockVirtualBatchDO cancelled = result.cancelledBatches().getFirst();
            assertEquals(StockCancelReasonEnum.ENTRY_PRICE_DEVIATION.getCode(), cancelled.getCancelReason());
        }
    }

    @Test
    @DisplayName("待买入批次全部条件满足_成交状态置为OPEN")
    void processEntryPending_allConditionsMet_filledToOpen() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setSlotId(1L);
        TornStockMarketBar15mDO bar = buildBar(true);
        TornStockPortfolioSlotDO slot = buildSlot(1L, 1);

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.checkEntryPriceDeviation(SIGNAL_PRICE, ENTRY_PRICE))
                    .thenReturn(false);
            mocked.when(() -> StockPortfolioService.calculateQuantity(any(BigDecimal.class), any(BigDecimal.class)))
                    .thenReturn(1000L);
            mocked.when(() -> StockPortfolioService.indexSlotsById(any()))
                    .thenReturn(Map.of(1L, slot));

            RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
            EntrySettlementResult result = entrySettlementService.processEntryPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(1, result.filledBatches().size());
            assertEquals(0, result.cancelledBatches().size());
            TornStockVirtualBatchDO filled = result.filledBatches().getFirst();
            assertEquals(StockBatchStatusEnum.OPEN.getCode(), filled.getBatchStatus());
            assertEquals(ENTRY_PRICE, filled.getEntryReferencePrice());
            assertEquals(ROUND_TIME, filled.getEntryTime());
            assertEquals(1000L, filled.getQuantity());
        }
    }

    @Test
    @DisplayName("Shadow待买入批次成交_不读取正式槽位且使用理论单位")
    void processEntryPending_shadowBatch_filledWithoutFormalSlot() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.checkEntryPriceDeviation(SIGNAL_PRICE, ENTRY_PRICE))
                    .thenReturn(false);
            EntrySettlementResult result = entrySettlementService.processEntryPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(1, result.filledBatches().size());
            TornStockVirtualBatchDO filled = result.filledBatches().getFirst();
            assertEquals(StockBatchStatusEnum.OPEN.getCode(), filled.getBatchStatus());
            assertEquals(1L, filled.getQuantity());
            assertEquals(ENTRY_PRICE, filled.getEntryReferencePrice());
            assertEquals(ENTRY_PRICE, filled.getInvestedCash());
            mocked.verify(() -> StockPortfolioService.indexSlotsById(any()));
            mocked.verify(() -> StockPortfolioService.checkEntryPriceDeviation(SIGNAL_PRICE, ENTRY_PRICE));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    @DisplayName("Shadow待卖出批次成交_不结算正式槽位资金")
    void processExitPending_shadowBatch_filledWithoutFormalSettlement() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setQuantity(1L);
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.00"));
        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(1, result.size());
            TornStockVirtualBatchDO filled = result.getFirst();
            assertEquals(StockBatchStatusEnum.CLOSED_TARGET.getCode(), filled.getBatchStatus());
            assertEquals(new BigDecimal("101.00"), filled.getExitReferencePrice());
            mocked.verify(() -> StockPortfolioService.indexSlotsById(any()));
            mocked.verify(() -> StockPortfolioService.calculateNetReturn(ENTRY_PRICE, new BigDecimal("101.00")));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    @DisplayName("待卖出批次bar连续_成交并设置exitReferencePrice")
    void processExitPending_barConsecutive_filledWithExitPrice() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        BigDecimal exitPrice = new BigDecimal("101.00");
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(exitPrice);

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.calculateNetReturn(ENTRY_PRICE, exitPrice))
                    .thenReturn(new BigDecimal("0.008"));

            RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
            List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(1, result.size());
            TornStockVirtualBatchDO filled = result.getFirst();
            assertEquals(exitPrice, filled.getExitReferencePrice());
            assertEquals(ROUND_TIME, filled.getExitTime());
        }
    }

    @Test
    @DisplayName("待卖出批次bar不可用_转为DATA_STALE_EXIT且不返回成交列表")
    void processExitPending_barNotUsable_returnsEmptyAndMarksStaleExit() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.size());
        assertEquals(StockBatchStatusEnum.DATA_STALE_EXIT.getCode(), batch.getBatchStatus());
    }

    // ==================== DATA_STALE_EXIT 灾难处置 ====================

    @Test
    @DisplayName("灾难处置_无合法恢复bar_保持DATA_STALE_EXIT继续占槽且不结算")
    void processExitPending_staleExitNoRecoveryBar_keepsStaleExit() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.size());
        assertEquals(StockBatchStatusEnum.DATA_STALE_EXIT.getCode(), batch.getBatchStatus());
        assertNull(batch.getExitReferencePrice(), "无恢复bar不得设置任何退出参考价");
        assertNull(batch.getExitTime(), "无恢复bar不得伪造退出时间");
    }

    @Test
    @DisplayName("灾难处置_恢复bar可用_按ADMIN_CLOSED灾难参考价结算并释放槽位")
    void processExitPending_staleExitRecoveryBar_disasterCloseFormal() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        BigDecimal recoveryPrice = new BigDecimal("101.50");
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(recoveryPrice);
        TornStockPortfolioSlotDO slot = buildSlot(1L, 1);
        slot.setSlotStatus("OCCUPIED");

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(1, result.size(), "灾难关闭应返回批次");
        TornStockVirtualBatchDO closed = result.getFirst();
        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), closed.getBatchStatus());
        assertEquals(recoveryPrice, closed.getExitReferencePrice());
        assertEquals(ROUND_TIME.plusMinutes(15), closed.getExitTime(), "exitTime应为恢复bar的barEndTime");
        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), closed.getBatchStatus());
        assertEquals(ROUND_TIME.plusMinutes(15).plusHours(48), closed.getCooldownUntil(), "灾难关闭冷却应为48小时");
        assertFalse(closed.getResetObserved(), "灾难关闭resetObserved应为false");
        assertEquals("CLOSED_TARGET", closed.getExitReason(), "应保留原退出原因");
        assertEquals(ROUND_TIME, closed.getExpectedExitBarTime(), "应保留原预期成交bar时间");
        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus(), "正式槽位应同事务释放");
        assertNull(slot.getCurrentBatchId(), "释放后槽位应解绑批次");
        assertNotNull(closed.getNetReturn(), "灾难关闭应计算净收益");
        assertNotNull(closed.getSellProceeds(), "灾难关闭应计算回笼资金");
    }

    @Test
    @DisplayName("灾难处置_恢复bar非连续_不要求连续且按灾难参考价结算")
    void processExitPending_staleExitRecoveryBarNonConsecutive_disasterClose() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        // 恢复bar与原预期成交bar时间不同(非连续),灾难处置不要求连续
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setBarStartTime(ROUND_TIME.plusMinutes(30));
        bar.setBarEndTime(ROUND_TIME.plusMinutes(45));
        bar.setLastSampleTime(ROUND_TIME.plusMinutes(44));
        bar.setLastPrice(new BigDecimal("102.00"));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(1, result.size());
        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), result.getFirst().getBatchStatus());
        assertEquals(new BigDecimal("102.00"), result.getFirst().getExitReferencePrice());
    }

    @Test
    @DisplayName("灾难处置_影子批次_只算理论收益不结算正式槽位")
    void processExitPending_staleExitShadow_disasterCloseTheoretical() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(1, result.size());
        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), result.getFirst().getBatchStatus());
        assertEquals(new BigDecimal("101.50"), result.getFirst().getSellProceeds(), "影子理论回笼为卖出参考价");
    }

    // ==================== Helper Methods ====================

    private TornStockVirtualBatchDO buildEntryPendingBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setBatchNo(BATCH_NO);
        batch.setStocksId(STOCKS_ID);
        batch.setStocksShortname(STOCKS_SHORTNAME);
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSignalReferencePrice(SIGNAL_PRICE);
        batch.setExpectedEntryBarTime(ROUND_TIME);
        batch.setEntryStaleAt(ROUND_TIME.plusMinutes(35));
        batch.setResetObserved(false);
        return batch;
    }

    private TornStockVirtualBatchDO buildExitPendingBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setBatchNo(BATCH_NO);
        batch.setStocksId(STOCKS_ID);
        batch.setStocksShortname(STOCKS_SHORTNAME);
        batch.setBatchStatus(StockBatchStatusEnum.EXIT_PENDING.getCode());
        batch.setEntryReferencePrice(ENTRY_PRICE);
        batch.setEntryTime(EARLIER_TIME);
        batch.setQuantity(1000L);
        batch.setExpectedExitBarTime(ROUND_TIME);
        batch.setExitReason("CLOSED_TARGET");
        batch.setSlotId(1L);
        return batch;
    }

    private TornStockMarketBar15mDO buildBar(boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(STOCKS_ID);
        bar.setStocksShortname(STOCKS_SHORTNAME);
        bar.setBarStartTime(ROUND_TIME);
        bar.setBarEndTime(ROUND_TIME.plusMinutes(15));
        bar.setLastPrice(ENTRY_PRICE);
        bar.setSampleCount(usable ? 15 : 5);
        bar.setLastSampleTime(usable ? ROUND_TIME.plusMinutes(14) : ROUND_TIME.minusMinutes(10));
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        bar.setUsable(usable);
        return bar;
    }

    private TornStockPortfolioSlotDO buildSlot(Long id, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setSlotNo(slotNo);
        slot.setAvailableCash(new BigDecimal("2000000000.00"));
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setSlotStatus("AVAILABLE");
        return slot;
    }

    private RoundSnapshot buildSnapshot(List<TornStockVirtualBatchDO> activeBatches,
                                        List<TornStockPortfolioSlotDO> slots) {
        return new RoundSnapshot(
                List.of(), List.of(), List.of(), activeBatches, List.of(), List.of(), slots, ROUND_TIME
        );
    }
}
