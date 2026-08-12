package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

/**
 * 股票影子轨道记录器测试,覆盖Shadow批次和拒绝观察批次的账本隔离契约。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@DisplayName("股票影子轨道记录器测试")
@ExtendWith(MockitoExtension.class)
class StockShadowTrackRecorderTest {

    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 28, 10, 0);

    @Mock
    private TornStockSignalEventDAO signalEventDAO;

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDAO;

    @Test
    @DisplayName("创建无限资金Shadow批次_携带事件ID且不占正式槽位")
    void createUnlimitedShadowBatch_carriesEventIdWithoutFormalSlot() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(11L);
        TornStockVirtualBatchDO persisted = new TornStockVirtualBatchDO();
        persisted.setId(21L);
        persisted.setSignalEventId(event.getId());
        persisted.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        persisted.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        org.mockito.Mockito.when(virtualBatchDAO.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode())).thenReturn(null, persisted);

        recorder.createUnlimitedShadowBatch(event);

        ArgumentCaptor<TornStockVirtualBatchDO> captor = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).insertIgnoreConflict(captor.capture());
        TornStockVirtualBatchDO batch = captor.getValue();
        assertEquals(event.getId(), batch.getSignalEventId());
        assertEquals(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode(), batch.getLedgerType());
        assertEquals(StockBatchStatusEnum.ENTRY_PENDING.getCode(), batch.getBatchStatus());
        assertNull(batch.getSlotId());
        assertNull(batch.getSlotNo());
    }

    @Test
    @DisplayName("创建拒绝观察批次_直接取消且不占正式槽位")
    void createRejectedObservationBatch_createsCancelledNonSlotBatch() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(12L);
        TornStockVirtualBatchDO persisted = new TornStockVirtualBatchDO();
        persisted.setId(22L);
        persisted.setSignalEventId(event.getId());
        persisted.setLedgerType(StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode());
        persisted.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
        persisted.setCancelReason("COOLDOWN_ACTIVE");
        org.mockito.Mockito.when(virtualBatchDAO.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode())).thenReturn(null, persisted);

        recorder.createRejectedObservationBatch(event, "COOLDOWN_ACTIVE");

        ArgumentCaptor<TornStockVirtualBatchDO> captor = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).insertIgnoreConflict(captor.capture());
        TornStockVirtualBatchDO batch = captor.getValue();
        assertEquals(event.getId(), batch.getSignalEventId());
        assertEquals(StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode(), batch.getLedgerType());
        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), batch.getBatchStatus());
        assertEquals("COOLDOWN_ACTIVE", batch.getCancelReason());
        assertNull(batch.getSlotId());
        assertNull(batch.getSlotNo());
    }

    @Test
    @DisplayName("事件批次ID回写_只更新已保存事件")
    void updateEventBatchIds_updatesSavedEvent() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(13L);
        event.setFormalBatchId(101L);
        event.setShadowBatchId(202L);

        recorder.updateEventBatchIds(event);

        verify(signalEventDAO).updateById(event);
    }

    @Test
    @DisplayName("记录信号事件_编号使用业务轮次时间而非当前墙钟")
    void recordSignalEvent_usesRoundTimeForBusinessNumber() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        StockSignalEventContext context = new StockSignalEventContext(
                1001, "TST", "RANGE_LOWER_BUY", new BigDecimal("100.00"),
                "RANGING", "M2_PROVISIONAL", "NONE", ROUND_TIME.toLocalDate(),
                StockRuleVersion.BUY, BigDecimal.ONE, "{}", "{}", "ALLOWED",
                java.util.List.of(), 1, "SHADOW", null, ROUND_TIME);
        TornStockSignalEventDO persisted = new TornStockSignalEventDO();
        persisted.setId(15L);
        persisted.setEventNo("E2026072810001001RAN");
        org.mockito.Mockito.when(signalEventDAO.selectByBusinessKeyForUpdate(
                1001, "RANGE_LOWER_BUY", ROUND_TIME, StockRuleVersion.BUY)).thenReturn(persisted);

        recorder.recordSignalEvent(context);

        ArgumentCaptor<TornStockSignalEventDO> captor = ArgumentCaptor.forClass(TornStockSignalEventDO.class);
        verify(signalEventDAO).insertIgnoreConflict(captor.capture());
        assertEquals("E2026072810001001RAN", captor.getValue().getEventNo());
    }

    @Test
    @DisplayName("重复记录同一业务事件_复用已保存事件且不重复插入")
    void recordSignalEvent_existingBusinessEvent_reusesPersistedEvent() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        StockSignalEventContext context = new StockSignalEventContext(
                1001, "TST", "RANGE_LOWER_BUY", new BigDecimal("100.00"),
                "RANGING", "M2_PROVISIONAL", "NONE", ROUND_TIME.toLocalDate(),
                StockRuleVersion.BUY, BigDecimal.ONE, "{}", "{}", "ALLOWED",
                java.util.List.of(), 1, "SHADOW", null, ROUND_TIME);
        TornStockSignalEventDO existing = buildEvent(99L);
        org.mockito.Mockito.when(signalEventDAO.selectByBusinessKeyForUpdate(
                1001, "RANGE_LOWER_BUY", ROUND_TIME, StockRuleVersion.BUY)).thenReturn(existing);

        TornStockSignalEventDO result = recorder.recordSignalEvent(context);

        assertEquals(existing, result);
        org.mockito.Mockito.verify(signalEventDAO, org.mockito.Mockito.never()).save(
                org.mockito.ArgumentMatchers.any(TornStockSignalEventDO.class));
    }

    @Test
    @DisplayName("创建Shadow批次_事件未保存时拒绝创建")
    void createUnlimitedShadowBatch_eventIdMissing_throwsException() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> recorder.createUnlimitedShadowBatch(event));
    }

    @Test
    @DisplayName("拒绝观察结果回写_保存MFE和MAE并记录结算时间")
    void resolveRejectedObservation_updatesResultAndResolutionTime() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(
                signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(14L);
        BigDecimal laterMfe = new BigDecimal("0.12");
        BigDecimal laterMae = new BigDecimal("-0.08");

        recorder.resolveRejectedObservation(event, laterMfe, laterMae, ROUND_TIME.plusDays(14));

        assertEquals(laterMfe, event.getLaterMfe());
        assertEquals(laterMae, event.getLaterMae());
        assertEquals(ROUND_TIME.plusDays(14), event.getResolvedAt());
        verify(signalEventDAO).updateById(event);
    }

    private TornStockSignalEventDO buildEvent(Long id) {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(id);
        event.setStocksId(1001);
        event.setStocksShortname("TST");
        event.setStrategyType("RANGE_LOWER_BUY");
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setRoundTime(ROUND_TIME);
        event.setBuyRuleVersion(StockRuleVersion.BUY);
        event.setQualityScore(BigDecimal.ONE);
        event.setEventNo("E" + (id == null ? 0 : id) + "RAN");
        return event;
    }
}
