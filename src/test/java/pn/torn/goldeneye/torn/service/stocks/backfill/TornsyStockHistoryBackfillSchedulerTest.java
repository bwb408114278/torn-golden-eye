package pn.torn.goldeneye.torn.service.stocks.backfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.StockHistoryBackfillProperty;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 股票历史回填调度器单元测试 - 覆盖防重入、最近5分钟排除、24小时自动上限、
 * 实验窗口重试不漂移与成功后关闭实验开关
 * <p>
 * 验证 {@link TornsyStockHistoryBackfillScheduler} 的自动补正窗口计算（排除最近 5 分钟、
 * 最多 24 小时）、JVM 防重入，以及实验固定窗口不随执行时刻漂移且成功后不再重复执行。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票历史回填调度器测试")
class TornsyStockHistoryBackfillSchedulerTest {

    @Mock
    private TornsyStockHistoryBackfillService backfillService;
    @Mock
    private StockHistoryBackfillProperty property;
    @Mock
    private StockMarketClock clock;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;

    @InjectMocks
    private TornsyStockHistoryBackfillScheduler scheduler;

    @Test
    @DisplayName("自动补正_防重入 -> 上一轮未完成时跳过本次")
    void autoBackfill_reentrant_skips() {
        ReflectionTestUtils.setField(scheduler, "autoProcessing", new AtomicBoolean(true));

        scheduler.autoBackfill(LocalDateTime.of(2026, 8, 13, 10, 0, 0));

        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("自动补正_窗口计算 -> 排除最近5分钟且上限24小时")
    void autoBackfill_windowExcludesLast5MinutesAndCaps24Hours() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 10, 0, 0);

        scheduler.autoBackfill(now);

        verify(backfillService, times(1)).backfillRange(
                LocalDateTime.of(2026, 8, 12, 10, 0, 0),
                LocalDateTime.of(2026, 8, 13, 9, 55, 0));
    }

    @Test
    @DisplayName("实验_固定窗口重试不漂移 -> 两次解析结果一致")
    void resolveExperimentWindow_fixedWindow_doesNotDrift() {
        when(property.getExperimentStart()).thenReturn("2026-01-01 00:00:00");
        when(property.getExperimentEnd()).thenReturn("2026-07-01 00:00:00");

        TornsyStockHistoryBackfillScheduler.ExperimentWindow w1 =
                scheduler.resolveExperimentWindow(LocalDateTime.of(2026, 8, 1, 10, 0, 0));
        TornsyStockHistoryBackfillScheduler.ExperimentWindow w2 =
                scheduler.resolveExperimentWindow(LocalDateTime.of(2026, 8, 2, 10, 0, 0));

        assertEquals(w1, w2);
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), w1.start());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), w1.end());
    }

    @Test
    @DisplayName("实验_成功后关闭实验开关 -> 第二次不再执行")
    void runExperimentOnce_success_disablesSwitch() {
        when(property.getExperimentStart()).thenReturn("2026-01-01 00:00:00");
        when(property.getExperimentEnd()).thenReturn("2026-07-01 00:00:00");

        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 10, 0, 0);
        scheduler.runExperimentOnce(now);
        scheduler.runExperimentOnce(now);

        verify(backfillService, times(1)).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
