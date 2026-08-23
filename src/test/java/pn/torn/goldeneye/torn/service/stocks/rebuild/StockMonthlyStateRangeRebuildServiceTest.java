package pn.torn.goldeneye.torn.service.stocks.rebuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMonthlyStateInitService;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 月度状态范围重算服务单元测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("月度状态范围重算服务测试")
class StockMonthlyStateRangeRebuildServiceTest {

    @Mock
    private StockMonthlyStateInitService monthlyStateInitService;

    @InjectMocks
    private StockMonthlyStateRangeRebuildService service;

    @Test
    @DisplayName("跨月范围_按月份升序依次初始化/重算/自动确认")
    void rebuild_crossMonth_callsMonthsInOrder() {
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 3, 1))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 3, 1))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 3, 1))).thenReturn(0);

        int total = service.rebuild(
                LocalDateTime.of(2026, 1, 15, 0, 0),
                LocalDateTime.of(2026, 3, 10, 0, 0));

        InOrder inOrder = inOrder(monthlyStateInitService);
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 3, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 3, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 3, 1));
        assertEquals(3, total);
    }

    @Test
    @DisplayName("右开边界恰为月初_只处理前一个月")
    void rebuild_endExactlyMonthStart_excludesEndMonth() {
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 1, 1))).thenReturn(1);

        int total = service.rebuild(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0));

        assertEquals(3, total);
        verify(monthlyStateInitService, never()).initMonth(LocalDate.of(2026, 2, 1));
        verify(monthlyStateInitService, never()).recalculateMonthDrafts(LocalDate.of(2026, 2, 1));
        verify(monthlyStateInitService, never()).autoConfirmDraftStates(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("右开边界在月中_处理包含该月")
    void rebuild_endMidMonth_includesEndMonth() {
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 1, 1))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 1, 1))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 1, 1))).thenReturn(0);
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 2, 1))).thenReturn(0);
        when(monthlyStateInitService.initMonth(LocalDate.of(2026, 3, 1))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(LocalDate.of(2026, 3, 1))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(LocalDate.of(2026, 3, 1))).thenReturn(0);

        int total = service.rebuild(
                LocalDateTime.of(2026, 1, 15, 0, 0),
                LocalDateTime.of(2026, 3, 10, 0, 0));

        assertEquals(0, total);
        InOrder inOrder = inOrder(monthlyStateInitService);
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 1, 1));
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 2, 1));
        inOrder.verify(monthlyStateInitService).initMonth(LocalDate.of(2026, 3, 1));
        inOrder.verify(monthlyStateInitService).recalculateMonthDrafts(LocalDate.of(2026, 3, 1));
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("start等于end或倒置_拒绝且零交互")
    void rebuild_invalidRange_rejectsWithoutInteractions() {
        LocalDateTime time = LocalDateTime.of(2026, 1, 15, 0, 0);

        LocalDateTime reversed = time.plusDays(1);

        assertThrows(IllegalArgumentException.class, () -> service.rebuild(time, time));
        assertThrows(IllegalArgumentException.class, () -> service.rebuild(reversed, time));

        verifyNoInteractions(monthlyStateInitService);
    }
}
