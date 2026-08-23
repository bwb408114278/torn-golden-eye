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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * 月度状态范围重算服务单元测试。
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
        when(monthlyStateInitService.initMonth(eq(LocalDate.of(2026, 1, 1)))).thenReturn(1);
        when(monthlyStateInitService.recalculateMonthDrafts(eq(LocalDate.of(2026, 1, 1)))).thenReturn(1);
        when(monthlyStateInitService.autoConfirmDraftStates(eq(LocalDate.of(2026, 1, 1)))).thenReturn(1);
        when(monthlyStateInitService.initMonth(eq(LocalDate.of(2026, 2, 1)))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(eq(LocalDate.of(2026, 2, 1)))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(eq(LocalDate.of(2026, 2, 1)))).thenReturn(0);
        when(monthlyStateInitService.initMonth(eq(LocalDate.of(2026, 3, 1)))).thenReturn(0);
        when(monthlyStateInitService.recalculateMonthDrafts(eq(LocalDate.of(2026, 3, 1)))).thenReturn(0);
        when(monthlyStateInitService.autoConfirmDraftStates(eq(LocalDate.of(2026, 3, 1)))).thenReturn(0);

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
}
