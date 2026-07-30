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
 * 股票影子账本服务测试,覆盖Shadow批次和拒绝观察批次的账本隔离契约。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("股票影子账本服务测试")
@ExtendWith(MockitoExtension.class)
class StockShadowServiceTest {

    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 28, 10, 0);

    @Mock
    private TornStockSignalEventDAO signalEventDAO;

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDAO;

    @Test
    @DisplayName("创建无限资金Shadow批次_携带事件ID且不占正式槽位")
    void createUnlimitedShadowBatch_carriesEventIdWithoutFormalSlot() {
        StockShadowService service = new StockShadowService(signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(11L);

        service.createUnlimitedShadowBatch(event);

        ArgumentCaptor<TornStockVirtualBatchDO> captor = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).save(captor.capture());
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
        StockShadowService service = new StockShadowService(signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(12L);

        service.createRejectedObservationBatch(event, "COOLDOWN_ACTIVE");

        ArgumentCaptor<TornStockVirtualBatchDO> captor = ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(virtualBatchDAO).save(captor.capture());
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
        StockShadowService service = new StockShadowService(signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(13L);
        event.setFormalBatchId(101L);
        event.setShadowBatchId(202L);

        service.updateEventBatchIds(event);

        verify(signalEventDAO).updateById(event);
    }

    @Test
    @DisplayName("创建Shadow批次_事件未保存时拒绝创建")
    void createUnlimitedShadowBatch_eventIdMissing_throwsException() {
        StockShadowService service = new StockShadowService(signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> service.createUnlimitedShadowBatch(event));
    }

    @Test
    @DisplayName("拒绝观察结果回写_保存MFE和MAE并记录结算时间")
    void resolveRejectedObservation_updatesResultAndResolutionTime() {
        StockShadowService service = new StockShadowService(signalEventDAO, virtualBatchDAO);
        TornStockSignalEventDO event = buildEvent(14L);
        BigDecimal laterMfe = new BigDecimal("0.12");
        BigDecimal laterMae = new BigDecimal("-0.08");

        service.resolveRejectedObservation(event, laterMfe, laterMae, ROUND_TIME.plusDays(14));

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
        event.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        return event;
    }
}
