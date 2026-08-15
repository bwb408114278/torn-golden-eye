package pn.torn.goldeneye.torn.service.stocks.backfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockHistoryMinuteCount;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.backfill.TornsyStockHistoryBackfillScheduler.BackfillSubmission;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tornsy 股票历史回填调度器单元测试
 * <p>
 * 覆盖每日昨天连续性巡检（非生产跳过、全连续不请求 Tornsy、缺口投递专用执行器、
 * 运行中防重入）、人工范围提交（稳定截止、环境校验、执行器拒绝）、人工与每日
 * 共用 JVM 防重入不并行，以及 failedSlices/异常/拒绝后防重入释放。
 * 测试捕获并实际运行投递的 {@link Runnable}，不只验证 executor.execute(any())。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tornsy股票历史回填调度器测试")
class TornsyStockHistoryBackfillSchedulerTest {

    private static final LocalDateTime TODAY_START = LocalDateTime.of(2026, 8, 15, 0, 0);
    private static final LocalDateTime YESTERDAY_START = LocalDateTime.of(2026, 8, 14, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 10, 0, 30);

    /**
     * 成功回填汇总（failedSlices=0），成功路径显式stub，避免以Mockito默认null触发NPE假覆盖执行成功
     */
    private static final TornsyStockHistoryBackfillService.BackfillSummary SUCCESS_SUMMARY =
            new TornsyStockHistoryBackfillService.BackfillSummary(100, 100, 50, 50, 0, 0, 3, 3);

    @Mock
    private TornsyStockHistoryBackfillService backfillService;
    @Mock
    private TornStocksDAO stocksDao;
    @Mock
    private TornStocksHistoryDAO stocksHistoryDao;
    @Mock
    private StockMarketClock clock;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private ThreadPoolTaskExecutor stockBackfillExecutor;

    @InjectMocks
    private TornsyStockHistoryBackfillScheduler scheduler;

    // ====================每日巡检====================

    @Test
    @DisplayName("每日巡检_非生产环境 -> 不查分钟计数、不投递执行器、不调用回填服务")
    void dailyInspection_notProd_doesNothing() {
        when(projectProperty.getEnv()).thenReturn("dev");

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        verifyNoInteractions(stocksDao, stocksHistoryDao, stockBackfillExecutor, backfillService);
    }

    @Test
    @DisplayName("每日巡检_昨天全部1440分钟连续 -> 不投递执行器、不请求Tornsy")
    void dailyInspection_allMinutesContinuous_noBackfill() {
        stubProdYesterdayWithCounts(
                new StockHistoryMinuteCount(1, 1440L),
                new StockHistoryMinuteCount(2, 1440L));

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("每日巡检_任一股票缺SQL行(计0) -> 投递一次执行器, Runnable回填昨天完整窗口")
    void dailyInspection_missingSqlRow_dispatchesYesterdayWindow() {
        stubProdYesterdayWithCounts(new StockHistoryMinuteCount(1, 1440L));
        stubBackfillSuccess();

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        Runnable task = captureSingleRunnable();
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
        task.run();
        verify(backfillService, times(1)).backfillRange(YESTERDAY_START, TODAY_START);
    }

    @Test
    @DisplayName("每日巡检_任一股票count小于1440 -> 投递一次执行器并回填昨天窗口")
    void dailyInspection_countBelow1440_dispatchesYesterdayWindow() {
        stubProdYesterdayWithCounts(
                new StockHistoryMinuteCount(1, 1440L),
                new StockHistoryMinuteCount(2, 1439L));
        stubBackfillSuccess();

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        captureSingleRunnable().run();
        verify(backfillService, times(1)).backfillRange(YESTERDAY_START, TODAY_START);
    }

    @Test
    @DisplayName("每日巡检_上一轮运行中再次触发 -> 不重复投递、不重复调用服务")
    void dailyInspection_processingOccupied_skips() {
        stubProdYesterdayWithCounts(new StockHistoryMinuteCount(1, 100L));
        ReflectionTestUtils.setField(scheduler, "processing", new AtomicBoolean(true));

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("每日巡检_执行器拒绝 -> 记WARN语义并立即释放防重入")
    void dailyInspection_executorRejected_releasesProcessing() {
        stubProdYesterdayWithCounts(new StockHistoryMinuteCount(1, 100L));
        when(clock.now()).thenReturn(NOW);
        doThrow(new RejectedExecutionException("full"))
                .when(stockBackfillExecutor).execute(any(Runnable.class));

        scheduler.inspectYesterdayAndBackfillIfNeeded();

        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
        // 防重入已释放: 恢复执行器可用后人工任务可立即受理
        doNothing().when(stockBackfillExecutor).execute(any(Runnable.class));
        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
    }

    // ====================人工范围提交====================

    @Test
    @DisplayName("人工提交_非生产环境 -> 返回NOT_PROD且不投递")
    void manualSubmit_notProd_rejected() {
        when(projectProperty.getEnv()).thenReturn("dev");

        assertEquals(BackfillSubmission.NOT_PROD, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0)));

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("人工提交_范围无效(start>=end) -> 返回INVALID_RANGE且不投递")
    void manualSubmit_invalidRange_rejected() {
        stubProd();

        assertEquals(BackfillSubmission.INVALID_RANGE, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 2, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 0)));

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("人工提交_合法且早于30分钟稳定截止 -> ACCEPTED, Runnable调用一次服务")
    void manualSubmit_validRange_acceptedAndRuns() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        stubBackfillSuccess();

        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 2, 0, 0);
        assertEquals(BackfillSubmission.ACCEPTED, scheduler.submitManualBackfill(start, end));

        captureSingleRunnable().run();
        verify(backfillService, times(1)).backfillRange(start, end);
    }

    @Test
    @DisplayName("人工提交_结束时间晚于稳定截止 -> TOO_RECENT且不投递不调用服务")
    void manualSubmit_endAfterCutoff_tooRecent() {
        stubProd();
        // now=10:00:30 -> 稳定截止=09:30:00
        when(clock.now()).thenReturn(NOW);

        assertEquals(BackfillSubmission.TOO_RECENT, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 8, 15, 9, 0), LocalDateTime.of(2026, 8, 15, 9, 35)));

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("人工提交_结束时间等于稳定截止 -> TOO_RECENT(fail-closed)")
    void manualSubmit_endEqualsCutoff_tooRecent() {
        stubProd();
        when(clock.now()).thenReturn(NOW);

        assertEquals(BackfillSubmission.TOO_RECENT, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 8, 15, 9, 0), LocalDateTime.of(2026, 8, 15, 9, 30)));

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("人工提交_结束时间早于稳定截止一分钟 -> ACCEPTED")
    void manualSubmit_endJustBeforeCutoff_accepted() {
        stubProd();
        when(clock.now()).thenReturn(NOW);

        assertEquals(BackfillSubmission.ACCEPTED, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 8, 15, 9, 0), LocalDateTime.of(2026, 8, 15, 9, 29, 59)));
    }

    // ====================人工与每日竞争====================

    @Test
    @DisplayName("人工与每日竞争 -> 首个获processing的任务执行, 另一入口被拒绝/跳过且无并行回填")
    void manualAndDaily_compete_noParallelBackfill() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(LocalDate.of(2026, 8, 15));
        stubBackfillSuccess();

        // 人工先受理并占住 processing(Runnable 未执行模拟任务运行中)
        assertEquals(BackfillSubmission.ACCEPTED, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0)));
        verify(stockBackfillExecutor, times(1)).execute(any(Runnable.class));

        // 每日巡检发现缺口但人工任务运行中 -> 跳过投递
        when(stocksDao.list()).thenReturn(List.of(stock(1)));
        when(stocksHistoryDao.selectMinuteCountsByStocksAndRange(anyList(),
                eq(YESTERDAY_START), eq(TODAY_START)))
                .thenReturn(List.of(new StockHistoryMinuteCount(1, 100L)));
        scheduler.inspectYesterdayAndBackfillIfNeeded();

        verify(stockBackfillExecutor, times(1)).execute(any(Runnable.class));
        Runnable manualTask = captureSingleRunnable();
        manualTask.run();
        // 仅人工任务执行了一次回填, 每日巡检未并行请求 Tornsy
        verify(backfillService, times(1)).backfillRange(
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0));
    }

    @Test
    @DisplayName("人工提交_已有回填执行中 -> ALREADY_PROCESSING")
    void manualSubmit_alreadyProcessing_rejected() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        ReflectionTestUtils.setField(scheduler, "processing", new AtomicBoolean(true));

        assertEquals(BackfillSubmission.ALREADY_PROCESSING, scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0)));

        verify(stockBackfillExecutor, never()).execute(any(Runnable.class));
    }

    // ====================失败与释放语义====================

    @Test
    @DisplayName("failedSlices大于0 -> 任务失败完成但释放processing, 人工下次仍可受理")
    void runBackfill_failedSlices_releasesProcessing() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        when(backfillService.backfillRange(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new TornsyStockHistoryBackfillService.BackfillSummary(
                        100, 90, 10, 5, 0, 2, 3, 1));

        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
        captureSingleRunnable().run();

        // 失败完成后防重入已释放: 下一次人工提交仍可受理
        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
    }

    @Test
    @DisplayName("服务抛异常 -> 释放processing, 下一任务可执行")
    void runBackfill_serviceThrows_releasesProcessing() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        when(backfillService.backfillRange(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("http fail"))
                .thenReturn(SUCCESS_SUMMARY);

        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
        Runnable task = captureSingleRunnable();
        assertDoesNotThrow(task::run);

        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(stockBackfillExecutor, times(2)).execute(captor.capture());
        captor.getAllValues().get(1).run();
        verify(backfillService, times(2)).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("人工提交_执行器拒绝 -> 返回EXECUTOR_REJECTED且立即释放processing")
    void manualSubmit_executorRejected_returnsRejectedAndReleases() {
        stubProd();
        when(clock.now()).thenReturn(NOW);
        doThrow(new RejectedExecutionException("full"))
                .when(stockBackfillExecutor).execute(any(Runnable.class));

        assertEquals(BackfillSubmission.EXECUTOR_REJECTED, submitValidManual());

        verify(backfillService, never()).backfillRange(any(LocalDateTime.class), any(LocalDateTime.class));
        // 防重入已释放: 下一次提交可受理
        doNothing().when(stockBackfillExecutor).execute(any(Runnable.class));
        assertEquals(BackfillSubmission.ACCEPTED, submitValidManual());
    }

    // ====================辅助方法====================

    /**
     * 提交一个合法且远早于稳定截止的人工范围
     *
     * @return 提交结果
     */
    private BackfillSubmission submitValidManual() {
        return scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0));
    }

    /**
     * 捕获并返回唯一投递到专用执行器的Runnable(需实际运行以验证执行体)
     *
     * @return 捕获的Runnable
     */
    private Runnable captureSingleRunnable() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(stockBackfillExecutor, atLeastOnce()).execute(captor.capture());
        assertEquals(1, captor.getAllValues().size(), "应恰好投递一次执行器");
        return captor.getValue();
    }

    /**
     * 桩: 生产环境 + 昨天(2026-08-14)窗口 + 指定分钟计数
     *
     * @param counts 每支有数据股票的分钟计数
     */
    private void stubProdYesterdayWithCounts(StockHistoryMinuteCount... counts) {
        stubProd();
        when(clock.today()).thenReturn(LocalDate.of(2026, 8, 15));
        when(stocksDao.list()).thenReturn(List.of(stock(1), stock(2)));
        when(stocksHistoryDao.selectMinuteCountsByStocksAndRange(anyList(),
                eq(YESTERDAY_START), eq(TODAY_START)))
                .thenReturn(List.of(counts));
    }

    /**
     * 桩: 生产环境
     */
    private void stubProd() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
    }

    /**
     * 桩: 回填服务成功完成（failedSlices=0），供成功路径执行Runnable时显式返回
     */
    private void stubBackfillSuccess() {
        when(backfillService.backfillRange(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(SUCCESS_SUMMARY);
    }

    /**
     * 构建指定ID的股票DO
     *
     * @param id 股票ID
     * @return 股票DO
     */
    private TornStocksDO stock(int id) {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(id);
        stock.setStocksName("股票" + id);
        stock.setStocksShortname("S" + id);
        return stock;
    }
}
