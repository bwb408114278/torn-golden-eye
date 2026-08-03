package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 拒绝观察结算服务测试，验证批量读取、到期结算和结果回写。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.29
 */
@DisplayName("拒绝观察结算服务测试")
@ExtendWith(MockitoExtension.class)
class StockRejectedObservationServiceTest {

    @Mock
    private TornStockSignalEventDAO signalEventDao;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private TornStockMarketBar15mDAO barDao;

    @Test
    @DisplayName("观察窗口到期_批量回写后续收益并只更新一次")
    void resolveDueObservations_deadlineReached_updatesEventsInBatch() {
        LocalDateTime signalTime = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime observedAt = signalTime.plusDays(14).plusHours(1);
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(11L);
        event.setStocksId(1001);
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setRoundTime(signalTime);
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(21L);
        batch.setSignalEventId(11L);
        batch.setStocksId(1001);
        batch.setExpectedEntryBarTime(signalTime.plusMinutes(15));
        batch.setEntryStaleAt(signalTime.plusMinutes(35));
        TornStockMarketBar15mDO entry = bar(signalTime.plusMinutes(15), new BigDecimal("100.00"));
        TornStockMarketBar15mDO later = bar(signalTime.plusDays(1), new BigDecimal("105.00"));

        when(signalEventDao.selectPendingRejectedObservationEvents(any(), any())).thenReturn(List.of(event));
        when(virtualBatchDao.selectRejectedObservationBatches(List.of(11L))).thenReturn(List.of(batch));
        when(barDao.selectByStocksAndTimeRange(eq(List.of(1001)), any(), any(), any()))
                .thenReturn(List.of(entry, later));

        int count = new StockRejectedObservationService(signalEventDao, virtualBatchDao, barDao)
                .resolveDueObservations(signalTime, signalTime.plusMinutes(15), observedAt);

        assertEquals(1, count);
        assertEquals(0, event.getLaterMfe().compareTo(new BigDecimal("0.05")));
        assertEquals(0, event.getLaterMae().compareTo(new BigDecimal("0.05")));
        assertEquals("OBSERVATION_COMPLETED", event.getObservationResult());
        assertTrue(event.getObservationDataIncomplete());
        verify(signalEventDao).updateObservationResultsByIds(List.of(event));
    }

    @Test
    @DisplayName("观察窗口未到期_不查询行情且不回写事件")
    void resolveDueObservations_beforeDeadline_keepsEventsPending() {
        LocalDateTime signalTime = LocalDateTime.of(2026, 7, 1, 10, 0);
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(11L);
        event.setStocksId(1001);
        event.setRoundTime(signalTime);
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(21L);
        batch.setSignalEventId(11L);
        batch.setExpectedEntryBarTime(signalTime.plusMinutes(15));
        batch.setEntryStaleAt(signalTime.plusMinutes(35));

        when(signalEventDao.selectPendingRejectedObservationEvents(any(), any())).thenReturn(List.of(event));
        when(virtualBatchDao.selectRejectedObservationBatches(List.of(11L))).thenReturn(List.of(batch));
        when(barDao.selectByStocksAndTimeRange(anyList(), any(), any(), any())).thenReturn(List.of());

        int count = new StockRejectedObservationService(signalEventDao, virtualBatchDao, barDao)
                .resolveDueObservations(signalTime, signalTime.plusMinutes(15), signalTime.plusMinutes(34));

        assertEquals(0, count);
    }

    @Test
    @DisplayName("紧邻下一桶不可用且达到入场过期点_立即结算为无法理论入场")
    void resolveDueObservations_entryDeadlineReachedWithoutEntry_resolvesImmediately() {
        LocalDateTime signalTime = LocalDateTime.of(2026, 7, 1, 10, 0);
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(12L);
        event.setStocksId(1001);
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setRoundTime(signalTime);
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setSignalEventId(12L);
        batch.setExpectedEntryBarTime(signalTime.plusMinutes(15));
        batch.setEntryStaleAt(signalTime.plusMinutes(35));

        when(signalEventDao.selectPendingRejectedObservationEvents(any(), any())).thenReturn(List.of(event));
        when(virtualBatchDao.selectRejectedObservationBatches(List.of(12L))).thenReturn(List.of(batch));
        when(barDao.selectByStocksAndTimeRange(anyList(), any(), any(), any())).thenReturn(List.of());

        int count = new StockRejectedObservationService(signalEventDao, virtualBatchDao, barDao)
                .resolveDueObservations(signalTime, signalTime.plusMinutes(15), signalTime.plusMinutes(35));

        assertEquals(1, count);
        assertEquals(signalTime.plusMinutes(35), event.getResolvedAt());
        assertEquals("NO_THEORETICAL_ENTRY", event.getObservationResult());
        assertFalse(event.getObservationDataIncomplete());
        verify(signalEventDao).updateObservationResultsByIds(List.of(event));
    }

    @Test
    @DisplayName("事件已经结算_重复调度不再次回写")
    void resolveDueObservations_resolvedEvent_skipsUpdate() {
        LocalDateTime signalTime = LocalDateTime.of(2026, 7, 1, 10, 0);
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(13L);
        event.setStocksId(1001);
        event.setRoundTime(signalTime);
        event.setResolvedAt(signalTime.plusDays(14));
        when(signalEventDao.selectPendingRejectedObservationEvents(any(), any())).thenReturn(List.of(event));
        when(virtualBatchDao.selectRejectedObservationBatches(List.of(13L)))
                .thenReturn(List.of(batchForEvent(13L)));

        int count = new StockRejectedObservationService(signalEventDao, virtualBatchDao, barDao)
                .resolveDueObservations(signalTime, signalTime.plusMinutes(15), signalTime.plusDays(15));

        assertEquals(0, count);
        verify(signalEventDao, never()).updateBatchById(any());
    }

    @Test
    @DisplayName("理论入场bar积压后补建_按真实bar结算而非提前写NO_THEORETICAL_ENTRY")
    void resolveDueObservations_entryBarRebuiltAfterBacklog_computesRealObservation() {
        LocalDateTime signalTime = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime observedAt = signalTime.plusDays(15);
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(14L);
        event.setStocksId(1001);
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setRoundTime(signalTime);
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setSignalEventId(14L);
        batch.setExpectedEntryBarTime(signalTime.plusMinutes(15));
        batch.setEntryStaleAt(signalTime.plusMinutes(35));
        // 积压期间理论入场bar缺失,调度器先补建轮次bar后再结算拒绝观察,
        // 因此本次传入的bars中必须包含真实理论入场bar。
        TornStockMarketBar15mDO entry = bar(signalTime.plusMinutes(15), new BigDecimal("100.00"));
        TornStockMarketBar15mDO later = bar(signalTime.plusDays(1), new BigDecimal("108.00"));

        when(signalEventDao.selectPendingRejectedObservationEvents(any(), any())).thenReturn(List.of(event));
        when(virtualBatchDao.selectRejectedObservationBatches(List.of(14L))).thenReturn(List.of(batch));
        when(barDao.selectByStocksAndTimeRange(eq(List.of(1001)), any(), any(), any()))
                .thenReturn(List.of(entry, later));

        int count = new StockRejectedObservationService(signalEventDao, virtualBatchDao, barDao)
                .resolveDueObservations(signalTime, signalTime.plusMinutes(15), observedAt);

        assertEquals(1, count);
        assertEquals("OBSERVATION_COMPLETED", event.getObservationResult(),
                "补建bar后必须按真实bar计算,不得提前写NO_THEORETICAL_ENTRY");
        assertNotEquals(StockObservationResultEnum.NO_THEORETICAL_ENTRY.getCode(), event.getObservationResult());
        assertEquals(0, event.getLaterMfe().compareTo(new BigDecimal("0.08")));
        assertEquals(0, event.getLaterMae().compareTo(new BigDecimal("0.08")));
        verify(signalEventDao).updateObservationResultsByIds(List.of(event));
    }

    private TornStockVirtualBatchDO batchForEvent(Long eventId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setSignalEventId(eventId);
        batch.setExpectedEntryBarTime(LocalDateTime.of(2026, 7, 1, 10, 15));
        batch.setEntryStaleAt(LocalDateTime.of(2026, 7, 1, 10, 35));
        return batch;
    }

    private TornStockMarketBar15mDO bar(LocalDateTime start, BigDecimal price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1001);
        bar.setBarStartTime(start);
        bar.setBarEndTime(start.plusMinutes(15));
        bar.setLastPrice(price);
        bar.setUsable(true);
        bar.setSampleCount(15);
        bar.setLastSampleTime(start.plusMinutes(14));
        bar.setTailGapSeconds(60);
        return bar;
    }
}
