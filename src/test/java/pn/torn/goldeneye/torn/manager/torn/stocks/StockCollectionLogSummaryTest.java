package pn.torn.goldeneye.torn.manager.torn.stocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票实时采集日志汇总组件单元测试。
 * <p>
 * 验证 {@link StockCollectionLogSummary} 的窗口边界、累计字段、跨窗口处理和线程安全语义，
 * 不启动 Spring，不连接 PostgreSQL/Redis。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@DisplayName("股票实时采集日志汇总组件测试")
class StockCollectionLogSummaryTest {

    /**
     * 构造单分钟成功指标。
     *
     * @param plannedMinute 计划自然分钟键
     * @param expected      预期股票行数
     * @param inserted      实际插入行数
     * @param queueMs       开始延迟毫秒
     * @param apiMs         API耗时毫秒
     * @param dbMs          历史写入耗时毫秒
     * @return 单分钟指标
     */
    private StockCollectionLogSummary.MinuteMetric metric(
            LocalDateTime plannedMinute, int expected, int inserted,
            long queueMs, long apiMs, long dbMs) {
        return new StockCollectionLogSummary.MinuteMetric(
                plannedMinute, expected, inserted, queueMs, apiMs, dbMs);
    }

    @Test
    @DisplayName("完整窗口_10:00到10:14共15次成功记录_最后一次返回快照且累计与最大值正确")
    void recordSuccess_fullWindow_returnsSummaryAtLastMinute() {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();

        for (int minute = 0; minute < 14; minute++) {
            LocalDateTime plannedMinute = LocalDateTime.of(2026, 8, 21, 10, minute);
            StockCollectionLogSummary.WindowRecordResult result =
                    summary.recordSuccess(metric(plannedMinute, 100, 100, 100, 200, 300));
            assertTrue(result.completedWindow().isEmpty(), "非窗口末分钟不应返回完成快照");
            assertFalse(result.discardedIncompleteWindow(), "正常同窗口累计不应丢弃旧窗口");
        }

        StockCollectionLogSummary.WindowRecordResult last =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 14),
                        100, 100, 500, 400, 600));
        assertTrue(last.completedWindow().isPresent(), "窗口末分钟应返回完成快照");
        assertFalse(last.discardedIncompleteWindow(), "正常完整窗口不应标记丢弃");

        StockCollectionLogSummary.WindowSummary window = last.completedWindow().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 0), window.windowStart());
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 15), window.windowEndExclusive());
        assertEquals(15, window.successfulMinuteCount());
        assertEquals(1500, window.expectedStockRows());
        assertEquals(1500, window.insertedStockRows());
        assertEquals(500, window.maxQueueOrStartDelayMs());
        assertEquals(400, window.maxApiCostMs());
        assertEquals(600, window.maxDbCostMs());
    }

    @Test
    @DisplayName("窗口未结束_10:00到10:13不返回快照")
    void recordSuccess_incompleteWindow_returnsEmpty() {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();

        for (int minute = 0; minute <= 13; minute++) {
            StockCollectionLogSummary.WindowRecordResult result =
                    summary.recordSuccess(metric(
                            LocalDateTime.of(2026, 8, 21, 10, minute),
                            10, 10, 1, 2, 3));
            assertTrue(result.completedWindow().isEmpty(), "10:00到10:13均不应返回快照");
        }
    }

    @Test
    @DisplayName("窗口结束后_10:15开始新窗口_不继承旧窗口统计")
    void recordSuccess_afterWindowEnd_startsNewWindowWithoutInheritance() {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();

        StockCollectionLogSummary.WindowRecordResult first =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 14), 100, 100, 100, 100, 100));
        assertTrue(first.completedWindow().isPresent());
        assertEquals(1, first.completedWindow().orElseThrow().successfulMinuteCount());

        StockCollectionLogSummary.WindowRecordResult next =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 15), 50, 50, 10, 10, 10));
        assertTrue(next.completedWindow().isEmpty(), "新窗口非末分钟不应返回旧窗口快照");
        assertFalse(next.discardedIncompleteWindow(), "正常新窗口不应标记丢弃");

        StockCollectionLogSummary.WindowRecordResult end =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 29), 50, 50, 20, 20, 20));
        assertTrue(end.completedWindow().isPresent());
        StockCollectionLogSummary.WindowSummary window = end.completedWindow().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 15), window.windowStart());
        assertEquals(2, window.successfulMinuteCount());
        assertEquals(100, window.expectedStockRows());
        assertEquals(100, window.insertedStockRows());
    }

    @Test
    @DisplayName("应用启动于窗口中间_10:10到10:14快照分钟数为5_不伪造为15")
    void recordSuccess_startMidWindow_reportsActualCountOnly() {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();

        for (int minute = 10; minute <= 13; minute++) {
            summary.recordSuccess(metric(
                    LocalDateTime.of(2026, 8, 21, 10, minute), 10, 10, 1, 2, 3));
        }

        StockCollectionLogSummary.WindowRecordResult result =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 14), 10, 10, 4, 5, 6));
        StockCollectionLogSummary.WindowSummary window = result.completedWindow().orElseThrow();
        assertEquals(5, window.successfulMinuteCount());
        assertEquals(50, window.expectedStockRows());
        assertEquals(50, window.insertedStockRows());
        assertEquals(4, window.maxQueueOrStartDelayMs());
        assertEquals(5, window.maxApiCostMs());
        assertEquals(6, window.maxDbCostMs());
    }

    @Test
    @DisplayName("跨窗口跳跃_10:10后直接收到10:30_丢弃未完成窗口且不混合数据")
    void recordSuccess_windowJump_discardsIncompleteWindowAndStartsNew() {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();
        summary.recordSuccess(metric(
                LocalDateTime.of(2026, 8, 21, 10, 10), 10, 10, 1, 2, 3));

        StockCollectionLogSummary.WindowRecordResult jump =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 30), 20, 20, 4, 5, 6));
        assertTrue(jump.completedWindow().isEmpty(), "跨窗口跳跃且未到新窗口末分钟时不应返回快照");
        assertTrue(jump.discardedIncompleteWindow(), "应标记丢弃未完成旧窗口");

        StockCollectionLogSummary.WindowRecordResult end =
                summary.recordSuccess(metric(
                        LocalDateTime.of(2026, 8, 21, 10, 44), 20, 20, 7, 8, 9));
        StockCollectionLogSummary.WindowSummary window = end.completedWindow().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 30), window.windowStart());
        assertEquals(2, window.successfulMinuteCount(), "新窗口只累计10:30与10:44，不能混入10:10");
        assertEquals(40, window.expectedStockRows());
        assertEquals(40, window.insertedStockRows());
    }

    @Test
    @DisplayName("并发调用_同一窗口多线程记录后不产生负数或跨字段不一致")
    void recordSuccess_concurrentCalls_keepsConsistentCounters() throws Exception {
        StockCollectionLogSummary summary = new StockCollectionLogSummary();
        int nonEndMinuteCount = 14;
        ExecutorService executor = Executors.newFixedThreadPool(nonEndMinuteCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int minute = 0; minute < nonEndMinuteCount; minute++) {
                int currentMinute = minute;
                futures.add(executor.submit(() -> {
                    summary.recordSuccess(metric(
                            LocalDateTime.of(2026, 8, 21, 10, currentMinute),
                            10, 10, 1, 2, 3));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            StockCollectionLogSummary.WindowRecordResult result =
                    summary.recordSuccess(metric(
                            LocalDateTime.of(2026, 8, 21, 10, 14),
                            10, 10, 4, 5, 6));
            StockCollectionLogSummary.WindowSummary window = result.completedWindow().orElse(null);
            assertNotNull(window, "并发累计后由末分钟返回完整窗口快照");
            assertEquals(15, window.successfulMinuteCount());
            assertEquals(150, window.expectedStockRows());
            assertEquals(150, window.insertedStockRows());
            assertTrue(window.maxQueueOrStartDelayMs() >= 4);
            assertTrue(window.maxApiCostMs() >= 5);
            assertTrue(window.maxDbCostMs() >= 6);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
