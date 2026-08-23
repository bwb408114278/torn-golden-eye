package pn.torn.goldeneye.torn.service.stocks.rebuild;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockDerivedDataRebuildScheduler.DerivedRebuildSubmission;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 全范围派生数据重建调度器单元测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("全范围派生数据重建调度器测试")
class StockDerivedDataRebuildSchedulerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 2, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 10, 0, 30);

    @Mock
    private StockDerivedDataRebuildService rebuildService;
    @Mock
    private StockMarketClock clock;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private ThreadPoolTaskExecutor stockBackfillExecutor;
    @Mock
    private StockHistoricalMaintenanceGate maintenanceGate;
    @Mock
    private Bot bot;

    @InjectMocks
    private StockDerivedDataRebuildScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        lenient().when(maintenanceGate.tryAcquire()).thenReturn(true);
        lenient().when(clock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("合法提交_受理并执行重建")
    void submit_valid_acceptedAndRuns() {
        StockDerivedDataRebuildResult success = new StockDerivedDataRebuildResult(
                START, END, 1, 1, 1, 0, 1, 0, 1L, null, null, null);
        when(rebuildService.rebuildRange(START, END)).thenReturn(success);

        assertEquals(DerivedRebuildSubmission.ACCEPTED, scheduler.submit(START, END, 0L));

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(stockBackfillExecutor).execute(captor.capture());
        captor.getValue().run();
        verify(rebuildService).rebuildRange(START, END);
        verify(maintenanceGate).release();
    }

    @Test
    @DisplayName("互斥门被占用_返回ALREADY_PROCESSING且不投递")
    void submit_gateOccupied_rejected() {
        when(maintenanceGate.tryAcquire()).thenReturn(false);

        assertEquals(DerivedRebuildSubmission.ALREADY_PROCESSING, scheduler.submit(START, END, 0L));
        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("执行器拒绝_返回EXECUTOR_REJECTED并释放互斥门")
    void submit_executorRejected_rejectedAndReleases() {
        doThrow(new RejectedExecutionException("full"))
                .when(stockBackfillExecutor).execute(any(Runnable.class));

        assertEquals(DerivedRebuildSubmission.EXECUTOR_REJECTED, scheduler.submit(START, END, 0L));
        verify(maintenanceGate).release();
    }
}
