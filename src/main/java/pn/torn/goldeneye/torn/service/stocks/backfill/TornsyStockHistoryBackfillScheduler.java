package pn.torn.goldeneye.torn.service.stocks.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockHistoryMinuteCount;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Tornsy 股票历史回填调度器 - 超管人工范围回填入口 + 每日昨天连续性巡检入口
 * <p>
 * 所有回填入口收敛到本类：统一投递 {@code stockBackfillExecutor} 执行器隔离、
 * 人工 30 分钟稳定截止校验与 JVM {@link AtomicBoolean} 防重入（人工与每日巡检
 * 共用同一标记，任一回填执行期间另一入口 fail-closed / 跳过，不允许并行）。
 * <p>
 * 每日巡检仅在昨天自然日存在分钟缺口时才请求 Tornsy；无缺口直接结束，
 * 不写表、不重建 bar/feature。回填 HTTP、事实写入、bar/feature 重建全部在
 * 专用执行器 Runnable 内执行，调度线程只做缺口聚合查询、状态判断与任务投递。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornsyStockHistoryBackfillScheduler {

    /**
     * 人工历史补数的稳定截止：最近多少分钟不回填（避免与实时采集竞争）
     */
    static final int MANUAL_STABLE_CUTOFF_MINUTES = 30;
    /**
     * 每日巡检的理论分钟数：昨天完整自然日 24 × 60
     */
    static final int DAILY_EXPECTED_MINUTES = 1440;

    private final TornsyStockHistoryBackfillService backfillService;
    private final TornStocksDAO stocksDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final StockMarketClock clock;
    private final ProjectProperty projectProperty;
    private final ThreadPoolTaskExecutor stockBackfillExecutor;

    /**
     * 人工与每日巡检共用的 JVM 防重入标记
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 人工范围回填提交结果
     * <p>
     * {@code ACCEPTED} 仅表示已成功投递专用执行器，不表示 Tornsy HTTP、入库或重建完成。
     *
     * @author Bai
     * @version 1.2.19
     * @since 2026.08.15
     */
    public enum BackfillSubmission {
        /**
         * 已成功投递专用执行器（不代表回填已完成）
         */
        ACCEPTED,
        /**
         * 非生产环境，未受理
         */
        NOT_PROD,
        /**
         * 时间范围无效（start >= end 或参数缺失）
         */
        INVALID_RANGE,
        /**
         * 结束时间不早于 30 分钟稳定截止，范围过新
         */
        TOO_RECENT,
        /**
         * 已有人工回填或每日巡检在执行中
         */
        ALREADY_PROCESSING,
        /**
         * 专用回填执行器已满，投递被拒绝
         */
        EXECUTOR_REJECTED
    }

    /**
     * 提交人工范围回填任务
     * <p>
     * 校验环境、范围合法性与 30 分钟稳定截止后投递专用执行器异步执行，
     * 不抛业务异常；执行体结果通过日志观察。
     *
     * @param startInclusive 起始时间（含，Asia/Shanghai）
     * @param endExclusive   结束时间（不含，必须早于稳定截止）
     * @return 提交结果
     */
    public BackfillSubmission submitManualBackfill(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        if (!isProd()) {
            log.warn("历史回填-人工提交未受理, 原因=非生产环境, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return BackfillSubmission.NOT_PROD;
        }
        if (startInclusive == null || endExclusive == null || !startInclusive.isBefore(endExclusive)) {
            log.warn("历史回填-人工提交未受理, 原因=范围无效, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return BackfillSubmission.INVALID_RANGE;
        }
        LocalDateTime stableCutoff = floorToMinute(clock.now().minusMinutes(MANUAL_STABLE_CUTOFF_MINUTES));
        if (!endExclusive.isBefore(stableCutoff)) {
            log.warn("历史回填-人工提交未受理, 原因=结束时间不早于{}分钟稳定截止, requestedStart={}, requestedEnd={}, stableCutoff={}",
                    MANUAL_STABLE_CUTOFF_MINUTES, startInclusive, endExclusive, stableCutoff);
            return BackfillSubmission.TOO_RECENT;
        }
        if (!processing.compareAndSet(false, true)) {
            log.warn("历史回填-人工提交未受理, 原因=已有回填任务在执行中, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return BackfillSubmission.ALREADY_PROCESSING;
        }
        try {
            stockBackfillExecutor.execute(() -> runBackfill("MANUAL", startInclusive, endExclusive));
        } catch (RejectedExecutionException e) {
            processing.set(false);
            log.warn("历史回填-人工提交未受理, 原因=回填执行器已满, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return BackfillSubmission.EXECUTOR_REJECTED;
        }
        log.info("历史回填-人工任务已受理, trigger=MANUAL, requestedStart={}, requestedEnd={}",
                startInclusive, endExclusive);
        return BackfillSubmission.ACCEPTED;
    }

    /**
     * 每日连续性巡检（每天 08:45，Asia/Shanghai）
     * <p>
     * 检查昨天完整自然日 [昨天00:00, 今天00:00) 内每支有效股票的 distinct 自然分钟数；
     * 任意股票不足 {@value #DAILY_EXPECTED_MINUTES} 即判定缺口，投递专用执行器回填整个昨天窗口。
     * 无缺口直接结束：不请求 Tornsy、不写表、不重建 bar/feature。
     * 巡检失败不永久关闭，下一日仍会再次巡检。
     */
    @Scheduled(cron = "0 45 8 * * ?", zone = "Asia/Shanghai")
    public void inspectYesterdayAndBackfillIfNeeded() {
        if (!isProd()) {
            return;
        }
        LocalDate today = clock.today();
        LocalDateTime start = today.minusDays(1).atStartOfDay();
        LocalDateTime end = today.atStartOfDay();

        List<TornStocksDO> stocks = stocksDao.list();
        if (stocks == null || stocks.isEmpty()) {
            log.warn("历史回填-每日巡检未执行, 原因=有效股票清单为空, requestedStart={}, requestedEnd={}", start, end);
            return;
        }
        List<Integer> stocksIds = stocks.stream().map(TornStocksDO::getId).toList();

        Map<Integer, Long> minuteCounts = stocksHistoryDao
                .selectMinuteCountsByStocksAndRange(stocksIds, start, end)
                .stream()
                .collect(Collectors.toMap(StockHistoryMinuteCount::stocksId,
                        StockHistoryMinuteCount::minuteCount));
        List<Integer> missingStockIds = stocks.stream()
                .map(TornStocksDO::getId)
                .filter(id -> minuteCounts.getOrDefault(id, 0L) < DAILY_EXPECTED_MINUTES)
                .toList();
        if (missingStockIds.isEmpty()) {
            log.info("历史回填-每日巡检昨天分钟连续, trigger=DAILY_INSPECTION, requestedStart={}, requestedEnd={}, "
                            + "stockCount={}, expectedMinuteCount={}, 无缺口不请求Tornsy", start, end, stocks.size(),
                    DAILY_EXPECTED_MINUTES);
            return;
        }
        long minimumMinuteCount = missingStockIds.stream()
                .mapToLong(id -> minuteCounts.getOrDefault(id, 0L))
                .min()
                .orElse(0L);
        log.warn("历史回填-每日巡检发现昨天分钟缺口, trigger=DAILY_INSPECTION, requestedStart={}, requestedEnd={}, "
                        + "missingStockCount={}, minimumMinuteCount={}, expectedMinuteCount={}",
                start, end, missingStockIds.size(), minimumMinuteCount, DAILY_EXPECTED_MINUTES);

        if (!processing.compareAndSet(false, true)) {
            log.warn("历史回填-每日巡检回填跳过, 原因=已有回填任务在执行中, requestedStart={}, requestedEnd={}",
                    start, end);
            return;
        }
        try {
            stockBackfillExecutor.execute(() -> runBackfill("DAILY_INSPECTION", start, end));
        } catch (RejectedExecutionException e) {
            processing.set(false);
            log.warn("历史回填-每日巡检回填投递被拒绝, 原因=回填执行器已满, requestedStart={}, requestedEnd={}",
                    start, end);
        }
    }

    /**
     * 在专用回填执行器内执行回填并记录统计（人工与每日巡检共用执行体）
     * <p>
     * {@code failedSlices > 0} 视为任务失败完成（ERROR 日志）；异常只记录不外抛。
     * 无论结果如何 finally 释放防重入标记。
     *
     * @param trigger 触发来源（MANUAL / DAILY_INSPECTION）
     * @param start   起始时间（含）
     * @param end     结束时间（不含）
     */
    private void runBackfill(String trigger, LocalDateTime start, LocalDateTime end) {
        try {
            TornsyStockHistoryBackfillService.BackfillSummary summary = backfillService.backfillRange(start, end);
            if (summary.failedSlices() > 0) {
                log.error("历史回填任务失败完成, trigger={}, requestedStart={}, requestedEnd={}, sourceRows={}, "
                                + "insertedRows={}, failedSlices={}, affectedBucketCount={}, rebuiltBucketCount={}",
                        trigger, start, end, summary.sourceRows(), summary.insertedRows(),
                        summary.failedSlices(), summary.affectedBucketCount(), summary.rebuiltBucketCount());
            } else {
                log.info("历史回填任务完成, trigger={}, requestedStart={}, requestedEnd={}, sourceRows={}, "
                                + "insertedRows={}, failedSlices={}, affectedBucketCount={}, rebuiltBucketCount={}",
                        trigger, start, end, summary.sourceRows(), summary.insertedRows(),
                        summary.failedSlices(), summary.affectedBucketCount(), summary.rebuiltBucketCount());
            }
        } catch (RuntimeException e) {
            log.error("历史回填任务异常, trigger={}, requestedStart={}, requestedEnd={}: {}",
                    trigger, start, end, e.getMessage(), e);
        } finally {
            processing.set(false);
        }
    }

    /**
     * 将时间向下截断到分钟（秒与纳秒清零）
     */
    private LocalDateTime floorToMinute(LocalDateTime time) {
        return time.withSecond(0).withNano(0);
    }

    /**
     * 是否生产环境
     */
    private boolean isProd() {
        return BotConstants.ENV_PROD.equals(projectProperty.getEnv());
    }
}
