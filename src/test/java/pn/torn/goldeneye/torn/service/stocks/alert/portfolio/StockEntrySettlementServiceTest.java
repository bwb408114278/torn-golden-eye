package pn.torn.goldeneye.torn.service.stocks.alert.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockEntrySettlementService.EntrySettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader;

/**
 * 股票批次成交结算服务测试，覆盖待买入批次成交/取消/过期和待卖出批次成交的核心逻辑。
 *
 * @author Bai
 * @version 1.2.14
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
                snapshot, Map.of(), ROUND_TIME, ROUND_TIME);

        assertEquals(0, result.filledBatches().size());
        assertEquals(1, result.cancelledBatches().size());
        TornStockVirtualBatchDO cancelled = result.cancelledBatches().getFirst();
        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), cancelled.getBatchStatus());
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(), cancelled.getCancelReason());
    }

    @Test
    @DisplayName("待买入批次过期_实际处理时刻晚于staleAt即使历史bar可补也取消")
    void processEntryPending_actualProcessingAfterStaleAt_rebuildableBarCancelled() {
        // 启动补偿晚恢复: 历史roundTime早于staleAt,但实际处理时刻已晚于staleAt,必须取消
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setEntryStaleAt(ROUND_TIME.plusMinutes(35));
        TornStockMarketBar15mDO bar = buildBar(true);
        LocalDateTime actualProcessingTime = batch.getEntryStaleAt().plusMinutes(10);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, actualProcessingTime);

        assertEquals(0, result.filledBatches().size(), "晚于staleAt不得成交");
        assertEquals(1, result.cancelledBatches().size());
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(),
                result.cancelledBatches().getFirst().getCancelReason());
    }

    @Test
    @DisplayName("待买入批次过期_实际处理时刻等于staleAt_其他入场条件成立时仍成交")
    void processEntryPending_actualProcessingEqualsStaleAt_fills() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setEntryStaleAt(ROUND_TIME.plusMinutes(35));
        batch.setSlotId(1L);
        TornStockMarketBar15mDO bar = buildBar(true);
        TornStockPortfolioSlotDO slot = buildSlot(1L, 1);
        LocalDateTime actualProcessingTime = batch.getEntryStaleAt();

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.checkEntryPriceDeviation(SIGNAL_PRICE, ENTRY_PRICE))
                    .thenReturn(false);
            mocked.when(() -> StockPortfolioService.calculateQuantity(any(BigDecimal.class), any(BigDecimal.class)))
                    .thenReturn(1000L);
            mocked.when(() -> StockPortfolioService.indexSlotsById(any()))
                    .thenReturn(Map.of(1L, slot));

            RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
            EntrySettlementResult result = entrySettlementService.processEntryPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, actualProcessingTime);

            assertEquals(1, result.filledBatches().size(), "等于staleAt边界在其他入场条件成立时应成交");
            assertEquals(0, result.cancelledBatches().size(), "等于staleAt边界不应取消");
            assertEquals(StockBatchStatusEnum.OPEN.getCode(), batch.getBatchStatus(), "批次应成交为OPEN");
        }
    }

    @Test
    @DisplayName("待买入批次过期_实际处理时刻晚于staleAt_取消并返回ENTRY_DATA_STALE")
    void processEntryPending_actualProcessingAfterStaleAt_cancelled() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setEntryStaleAt(ROUND_TIME.plusMinutes(35));
        TornStockMarketBar15mDO bar = buildBar(true);
        LocalDateTime actualProcessingTime = batch.getEntryStaleAt().plusNanos(1);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, actualProcessingTime);

        assertEquals(0, result.filledBatches().size(), "晚于staleAt不得成交");
        assertEquals(1, result.cancelledBatches().size());
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(),
                result.cancelledBatches().getFirst().getCancelReason());
    }

    @Test
    @DisplayName("待买入批次过期_正式取消释放槽位预留资金并解绑批次")
    void processEntryPending_staleCancel_releasesFormalSlotReservedCash() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setEntryStaleAt(ROUND_TIME.minusMinutes(1));
        batch.setSlotId(1L);
        TornStockPortfolioSlotDO slot = buildSlot(1L, 1);
        slot.setAvailableCash(new BigDecimal("1900000000.00"));
        slot.setReservedCash(new BigDecimal("100000000.00"));
        slot.setCurrentBatchId(batch.getId());
        slot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(), ROUND_TIME, ROUND_TIME);

        assertEquals(1, result.cancelledBatches().size());
        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), batch.getBatchStatus());
        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus(), "槽位应释放为AVAILABLE");
        assertNull(slot.getCurrentBatchId(), "槽位应解绑批次");
        assertEquals(new BigDecimal("2000000000.00"), slot.getAvailableCash(), "预留资金应完整退回可用现金");
        assertEquals(BigDecimal.ZERO, slot.getReservedCash(), "预留资金应清零");
    }

    @Test
    @DisplayName("待买入批次过期_影子批次取消且不进入OPEN")
    void processEntryPending_staleCancel_shadowNotFilledToOpen() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setEntryStaleAt(ROUND_TIME.minusMinutes(1));
        TornStockMarketBar15mDO bar = buildBar(true);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

        assertEquals(0, result.filledBatches().size(), "影子过期不得伪造成交");
        assertEquals(1, result.cancelledBatches().size());
        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), batch.getBatchStatus());
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(), batch.getCancelReason());
    }

    @Test
    @DisplayName("待买入批次bar不可用_保持ENTRY_PENDING")
    void processEntryPending_barNotUsable_remainsEntryPending() {
        TornStockVirtualBatchDO batch = buildEntryPendingBatch();
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        EntrySettlementResult result = entrySettlementService.processEntryPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

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
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

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
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

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
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

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
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME, ROUND_TIME);

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
    @DisplayName("待卖出批次bar连续_正式批次成交并精确结算槽位资金")
    void processExitPending_barConsecutive_filledWithExitPrice() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        BigDecimal exitPrice = new BigDecimal("101.00");
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(exitPrice);
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        try (MockedStatic<StockPortfolioService> mocked = mockStatic(StockPortfolioService.class)) {
            mocked.when(() -> StockPortfolioService.calculateNetReturn(ENTRY_PRICE, exitPrice))
                    .thenReturn(new BigDecimal("0.008"));
            mocked.when(() -> StockPortfolioService.indexSlotsById(any()))
                    .thenReturn(Map.of(1L, slot));

            RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
            List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                    snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

            assertEquals(1, result.size());
            TornStockVirtualBatchDO filled = result.getFirst();
            assertEquals(exitPrice, filled.getExitReferencePrice());
            assertEquals(ROUND_TIME, filled.getExitTime());
            BigDecimal expectedSellProceeds = exitPrice.multiply(BigDecimal.valueOf(1000))
                    .multiply(StockPortfolioService.SELL_FEE_RATE);
            assertEquals(expectedSellProceeds, filled.getSellProceeds(), "卖出所得=股数×卖出价×0.999");
            assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus(), "正式槽位应释放");
            assertNull(slot.getCurrentBatchId(), "释放后槽位应解绑批次");
            assertEquals(new BigDecimal("1999990000.00").add(expectedSellProceeds), slot.getAvailableCash(),
                    "槽位可用现金应等于批次余款+卖出所得");
            assertEquals(BigDecimal.ZERO, slot.getReservedCash(), "结算后预留资金应为0");
        }
    }

    @Test
    @DisplayName("待卖出批次bar不可用_转为DATA_STALE_EXIT且冻结原退出原因")
    void processExitPending_barNotUsable_returnsEmptyAndMarksStaleExit() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        TornStockMarketBar15mDO bar = buildBar(false);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.size());
        assertEquals(StockBatchStatusEnum.DATA_STALE_EXIT.getCode(), batch.getBatchStatus());
        assertEquals("CLOSED_TARGET", batch.getOriginalExitReason(), "首次迁移应冻结原退出原因");
    }

    // ==================== 灾难处置 fail-closed ====================

    @Test
    @DisplayName("灾难处置_正式批次缺ledgerType_抛异常并回滚")
    void processExitPending_staleExitNullLedgerType_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setLedgerType(null);
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "未知或空ledgerType必须fail-closed,禁止非Shadow即正式");
    }

    @Test
    @DisplayName("灾难处置_正式批次槽位不存在_抛异常并回滚")
    void processExitPending_staleExitSlotMissing_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of());
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "正式批次槽位缺失必须抛异常,不得仅关闭批次");
    }

    @Test
    @DisplayName("灾难处置_slotNo不一致_抛异常并回滚")
    void processExitPending_staleExitSlotNoMismatch_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 2, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "槽位slotNo不一致必须抛异常");
    }

    @Test
    @DisplayName("灾难处置_currentBatchId不匹配_抛异常并回滚")
    void processExitPending_staleExitCurrentBatchIdMismatch_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, 999L);

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "槽位currentBatchId必须等于批次id,否则抛异常");
    }

    @Test
    @DisplayName("灾难处置_槽位状态非法_抛异常并回滚")
    void processExitPending_staleExitSlotStatusInvalid_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "槽位状态非法必须抛异常");
    }

    @Test
    @DisplayName("灾难处置_槽位reservedCash非零_抛异常并回滚")
    void processExitPending_staleExitReservedCashNonZero_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());
        slot.setReservedCash(new BigDecimal("100.00"));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "槽位仍有预留资金必须抛异常");
    }

    @Test
    @DisplayName("灾难处置_槽位余款与批次不一致_抛异常并回滚")
    void processExitPending_staleExitCashMismatch_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());
        slot.setAvailableCash(new BigDecimal("999.00"));

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "槽位可用现金与批次余款不一致必须抛异常");
    }

    @Test
    @DisplayName("灾难处置_批次数量非正_抛异常并回滚")
    void processExitPending_staleExitQuantityNonPositive_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setQuantity(0L);
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "正式批次股数非正必须抛异常");
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
        batch.setOriginalExitReason(StockBatchStatusEnum.CLOSED_TARGET.getCode());
        BigDecimal recoveryPrice = new BigDecimal("101.50");
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(recoveryPrice);
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

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

        BigDecimal expectedSellProceeds = recoveryPrice.multiply(BigDecimal.valueOf(1000))
                .multiply(StockPortfolioService.SELL_FEE_RATE);
        BigDecimal expectedNetReturn = StockPortfolioService.calculateNetReturn(ENTRY_PRICE, recoveryPrice);
        assertEquals(expectedNetReturn, closed.getNetReturn(), "灾难关闭应精确计算净收益");
        assertEquals(expectedSellProceeds, closed.getSellProceeds(), "灾难关闭应精确计算回笼资金");
        assertEquals(new BigDecimal("1999990000.00").add(expectedSellProceeds), slot.getAvailableCash(),
                "槽位可用现金应等于批次余款+灾难卖出所得");
        assertEquals(BigDecimal.ZERO, slot.getReservedCash(), "结算后预留资金应为0");

        assertEquals("CLOSED_TARGET", closed.getOriginalExitReason(), "应冻结原退出原因");
        assertEquals("DATA_STALE_EXIT_RECOVERY_CLOSE", closed.getAdminCloseReason(), "管理关闭原因应固定");
        assertEquals(ROUND_TIME, closed.getRecoveryBarStartTime(), "恢复bar开始时间应落库");
        assertEquals(ROUND_TIME.plusMinutes(15), closed.getRecoveryBarEndTime(), "恢复bar结束时间应落库");
        assertEquals(0L, closed.getStaleExitDurationSeconds(), "预期bar结束与恢复bar结束相同时陈旧持续为0");
    }

    @Test
    @DisplayName("灾难处置_恢复bar非连续_不要求连续且按灾难参考价结算")
    void processExitPending_staleExitRecoveryBarNonConsecutive_disasterClose() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        batch.setOriginalExitReason(StockBatchStatusEnum.CLOSED_TARGET.getCode());
        // 恢复bar与原预期成交bar时间不同(非连续),灾难处置不要求连续
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setBarStartTime(ROUND_TIME.plusMinutes(30));
        bar.setBarEndTime(ROUND_TIME.plusMinutes(45));
        bar.setLastSampleTime(ROUND_TIME.plusMinutes(44));
        bar.setLastPrice(new BigDecimal("102.00"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(1, result.size());
        TornStockVirtualBatchDO closed = result.getFirst();
        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), closed.getBatchStatus());
        assertEquals(new BigDecimal("102.00"), closed.getExitReferencePrice());
        assertEquals(ROUND_TIME.plusMinutes(45), closed.getExitTime(), "exitTime应为恢复bar结束时间");
        assertEquals(ROUND_TIME.plusMinutes(30), closed.getRecoveryBarStartTime(), "恢复bar开始时间应落库");
        assertEquals(ROUND_TIME.plusMinutes(45), closed.getRecoveryBarEndTime(), "恢复bar结束时间应落库");
        assertEquals(1800L, closed.getStaleExitDurationSeconds(),
                "陈旧持续秒数=恢复bar结束-(预期bar结束+15分钟)=45-30分钟=1800秒");
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

    // ==================== P1-4 正式结算来源状态校验 ====================

    @Test
    @DisplayName("P1-4_正常SELL缺exitSignalTime_抛异常且批次槽位资金通知不变")
    void processExitPending_normalSellMissingExitSignalTime_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setExitSignalTime(null);
        TornStockMarketBar15mDO bar = buildBar(true);
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "正常SELL缺exitSignalTime必须fail-closed抛异常");

        assertBatchAndSlotUnchanged(batch, slot);
    }

    @Test
    @DisplayName("P1-4_普通关闭来源状态非EXIT_PENDING(OPEN)_不进入正式结算且批次槽位不变")
    void processExitPending_normalSellSourceNotExitPending_notSettled() {
        // OPEN来源批次不属于正常SELL处理集,不得被结算: 不抛异常、不关批次、不释放槽位
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.size(), "OPEN来源批次不得作为正常SELL结算");
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), batch.getBatchStatus(), "批次状态不得变化");
        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), slot.getSlotStatus(), "槽位不得释放");
        assertEquals(batch.getId(), slot.getCurrentBatchId(), "槽位不得解绑批次");
    }

    @Test
    @DisplayName("P1-4_正常SELL退出原因不在冻结正式集合_抛异常且状态资金不变")
    void processExitPending_normalSellExitReasonNotFrozen_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setExitReason(StockCloseTypeEnum.ADMIN_CLOSED.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "正常SELL退出原因必须属于冻结正式关闭类型");

        assertBatchAndSlotUnchanged(batch, slot);
    }

    @Test
    @DisplayName("P1-4_灾难关闭缺exitSignalTime_抛异常且状态资金不变")
    void processExitPending_disasterCloseMissingExitSignalTime_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        batch.setOriginalExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        batch.setExitSignalTime(null);
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "灾难关闭缺exitSignalTime必须fail-closed抛异常");

        assertBatchAndSlotUnchanged(batch, slot);
    }

    @Test
    @DisplayName("P1-4_灾难关闭来源状态非DATA_STALE_EXIT(OPEN)_不进入灾难结算且批次槽位不变")
    void processExitPending_disasterCloseSourceNotDataStaleExit_notSettled() {
        // OPEN来源批次不属于灾难处置集,不得被灾难结算: 不抛异常、不关批次、不释放槽位
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setOriginalExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        List<TornStockVirtualBatchDO> result = entrySettlementService.processExitPending(
                snapshot, Map.of(STOCKS_ID, bar), ROUND_TIME);

        assertEquals(0, result.size(), "OPEN来源批次不得作为灾难关闭结算");
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), batch.getBatchStatus(), "批次状态不得变化");
        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), slot.getSlotStatus(), "槽位不得释放");
        assertEquals(batch.getId(), slot.getCurrentBatchId(), "槽位不得解绑批次");
    }

    @Test
    @DisplayName("P1-4_灾难关闭缺originalExitReason_抛异常且不自动补字段")
    void processExitPending_disasterCloseMissingOriginalExitReason_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        batch.setOriginalExitReason(null);
        batch.setExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "灾难关闭缺originalExitReason必须fail-closed抛异常,不得用exitReason替代");

        assertBatchAndSlotUnchanged(batch, slot);
    }

    @Test
    @DisplayName("P1-4_灾难关闭原退出原因不在合法正式集合_抛异常且状态资金不变")
    void processExitPending_disasterCloseOriginalReasonNotLegal_failClosed() {
        TornStockVirtualBatchDO batch = buildExitPendingBatch();
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        batch.setOriginalExitReason(StockCloseTypeEnum.ADMIN_CLOSED.getCode());
        batch.setExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        TornStockMarketBar15mDO bar = buildBar(true);
        bar.setLastPrice(new BigDecimal("101.50"));
        TornStockPortfolioSlotDO slot = buildOccupiedFormalSlot(1L, 1, batch.getId());

        RoundSnapshot snapshot = buildSnapshot(List.of(batch), List.of(slot));
        Map<Integer, TornStockMarketBar15mDO> barByStock = Map.of(STOCKS_ID, bar);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> entrySettlementService.processExitPending(snapshot, barByStock, ROUND_TIME),
                "灾难关闭原退出原因必须属于合法正式关闭类型");

        assertBatchAndSlotUnchanged(batch, slot);
    }

    /**
     * 断言失败后批次状态、槽位状态、资金与批次绑定均未变化(fail-closed)。
     *
     * @param batch 正式批次
     * @param slot  正式槽位
     */
    private void assertBatchAndSlotUnchanged(TornStockVirtualBatchDO batch,
                                             TornStockPortfolioSlotDO slot) {
        assertTrue(StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()),
                () -> "失败后批次状态不得变化,实际状态: " + batch.getBatchStatus());
        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), slot.getSlotStatus(), "失败后槽位不得释放");
        assertNotNull(slot.getCurrentBatchId(), "失败后槽位不得解绑批次");
        assertEquals(new BigDecimal("1999990000.00"), slot.getAvailableCash(), "失败后可用现金不得变化");
        assertEquals(BigDecimal.ZERO, slot.getReservedCash(), "失败后预留资金不得变化");
        assertNull(batch.getExitReferencePrice(), "失败后不得写入退出参考价");
        assertNull(batch.getExitTime(), "失败后不得写入退出时间");
    }

    // ==================== Helper Methods ====================

    private TornStockVirtualBatchDO buildEntryPendingBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setBatchNo(BATCH_NO);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
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
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(STOCKS_ID);
        batch.setStocksShortname(STOCKS_SHORTNAME);
        batch.setBatchStatus(StockBatchStatusEnum.EXIT_PENDING.getCode());
        batch.setEntryReferencePrice(ENTRY_PRICE);
        batch.setEntryTime(EARLIER_TIME);
        batch.setQuantity(1000L);
        batch.setExpectedExitBarTime(ROUND_TIME);
        batch.setExitSignalTime(EARLIER_TIME);
        batch.setExitReason("CLOSED_TARGET");
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setRemainingCash(new BigDecimal("1999990000.00"));
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

    private TornStockPortfolioSlotDO buildOccupiedFormalSlot(Long id, int slotNo, Long currentBatchId) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setSlotNo(slotNo);
        slot.setAvailableCash(new BigDecimal("1999990000.00"));
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(currentBatchId);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        return slot;
    }

    private RoundSnapshot buildSnapshot(List<TornStockVirtualBatchDO> activeBatches,
                                        List<TornStockPortfolioSlotDO> slots) {
        return new RoundSnapshot(
                List.of(), List.of(), List.of(), activeBatches, List.of(), List.of(), slots, ROUND_TIME
        );
    }
}
