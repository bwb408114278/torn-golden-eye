package pn.torn.goldeneye.torn.manager.torn.stocks;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 股票实时采集分钟日志汇总组件（仅内存）。
 * <p>
 * 用于将每分钟成功且全量插入的实时采集指标按自然 15 分钟窗口聚合成一条低频 INFO 摘要，
 * 降低高频正常流程对 Docker/Loki 的持续小块日志写入。组件只负责统计累计与窗口边界判断，
 * 仅作为 Spring 组件被实例化，不注入 DAO、Redis、Torn API、Logger、配置或业务开关，
 * 不写日志、不持久化、不创建定时任务。
 * <p>
 * 窗口以 {@code plannedMinute} 所属自然 15 分钟段为准，例如 10:00:00~10:14:00 为一个窗口，
 * 在处理 10:14 分钟成功后返回该窗口摘要并清空当前窗口。应用重启、窗口不足 15 个分钟、
 * 采集异常或跳过时允许丢弃未完成摘要；摘要仅是可观测数据，不是业务事实。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@Component
public class StockCollectionLogSummary {

    /**
     * 窗口跨度（分钟）
     */
    private static final int WINDOW_MINUTES = 15;

    /**
     * 当前窗口起点；无累计时为 null
     */
    private LocalDateTime windowStart;

    /**
     * 当前窗口终点（不含）；无累计时为 null
     */
    private LocalDateTime windowEndExclusive;

    /**
     * 当前窗口成功记录分钟数
     */
    private int successfulMinuteCount;

    /**
     * 当前窗口预期股票行累计数
     */
    private long expectedStockRows;

    /**
     * 当前窗口实际新插入股票行累计数
     */
    private long insertedStockRows;

    /**
     * 当前窗口最大开始延迟毫秒数
     */
    private long maxQueueOrStartDelayMs;

    /**
     * 当前窗口最大 API 耗时毫秒数
     */
    private long maxApiCostMs;

    /**
     * 当前窗口最大历史写入耗时毫秒数
     */
    private long maxDbCostMs;

    /**
     * 记录一次成功分钟采集指标。
     * <p>
     * 当该分钟不属于当前窗口时，先丢弃未完成旧窗口并从新窗口重新累计；调用方可通过
     * {@link WindowRecordResult#discardedIncompleteWindow()} 感知丢弃事件并输出 DEBUG。
     * 当该分钟是窗口末分钟（:14/:29/:44/:59）时返回完整窗口快照并清空当前窗口；
     * 否则返回空结果。本方法使用 {@code synchronized} 保护“累计 + 边界快照 + 清空”操作，
     * 保证并发调用不会出现负数、重复清空或跨字段窗口撕裂。
     *
     * @param metric 单分钟成功采集指标，不可为 null
     * @return 窗口记录结果；包含已完成的窗口快照（若有）与是否丢弃了未完成旧窗口
     */
    public synchronized WindowRecordResult recordSuccess(MinuteMetric metric) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(metric.plannedMinute(), "plannedMinute");
        LocalDateTime plannedMinute = metric.plannedMinute().withSecond(0).withNano(0);

        if (windowStart == null) {
            startWindow(plannedMinute, metric);
            if (isWindowEnd(plannedMinute)) {
                WindowSummary summary = snapshot();
                clear();
                return WindowRecordResult.completed(summary, false);
            }
            return WindowRecordResult.empty(false);
        }

        if (!belongsToCurrentWindow(plannedMinute)) {
            boolean discardedIncompleteWindow = true;
            startWindow(plannedMinute, metric);
            if (isWindowEnd(plannedMinute)) {
                WindowSummary summary = snapshot();
                clear();
                return WindowRecordResult.completed(summary, discardedIncompleteWindow);
            }
            return WindowRecordResult.empty(discardedIncompleteWindow);
        }

        accumulate(metric);
        if (isWindowEnd(plannedMinute)) {
            WindowSummary summary = snapshot();
            clear();
            return WindowRecordResult.completed(summary, false);
        }
        return WindowRecordResult.empty(false);
    }

    /**
     * 以指定分钟为起点开启新窗口并累计该分钟指标。
     *
     * @param plannedMinute 计划自然分钟键（秒与纳秒清零）
     * @param metric        该分钟成功采集指标
     */
    private void startWindow(LocalDateTime plannedMinute, MinuteMetric metric) {
        this.windowStart = alignToWindowStart(plannedMinute);
        this.windowEndExclusive = this.windowStart.plusMinutes(WINDOW_MINUTES);
        this.successfulMinuteCount = 0;
        this.expectedStockRows = 0L;
        this.insertedStockRows = 0L;
        this.maxQueueOrStartDelayMs = 0L;
        this.maxApiCostMs = 0L;
        this.maxDbCostMs = 0L;
        accumulate(metric);
    }

    /**
     * 将单分钟指标累计到当前窗口。
     *
     * @param metric 单分钟成功采集指标
     */
    private void accumulate(MinuteMetric metric) {
        successfulMinuteCount++;
        expectedStockRows += metric.expectedStockRows();
        insertedStockRows += metric.insertedStockRows();
        maxQueueOrStartDelayMs = Math.max(maxQueueOrStartDelayMs, metric.queueOrStartDelayMillis());
        maxApiCostMs = Math.max(maxApiCostMs, metric.apiCostMillis());
        maxDbCostMs = Math.max(maxDbCostMs, metric.dbCostMillis());
    }

    /**
     * 判断分钟是否属于当前窗口。
     *
     * @param plannedMinute 计划自然分钟键
     * @return true 表示属于当前窗口
     */
    private boolean belongsToCurrentWindow(LocalDateTime plannedMinute) {
        return !plannedMinute.isBefore(windowStart)
                && plannedMinute.isBefore(windowEndExclusive);
    }

    /**
     * 判断分钟是否为当前窗口的最后一个自然分钟（:14/:29/:44/:59）。
     *
     * @param plannedMinute 计划自然分钟键
     * @return true 表示窗口结束分钟
     */
    private boolean isWindowEnd(LocalDateTime plannedMinute) {
        return !plannedMinute.isBefore(windowEndExclusive.minusMinutes(1));
    }

    /**
     * 生成当前窗口的不可变快照。
     *
     * @return 当前窗口摘要
     */
    private WindowSummary snapshot() {
        return new WindowSummary(
                windowStart,
                windowEndExclusive,
                successfulMinuteCount,
                expectedStockRows,
                insertedStockRows,
                maxQueueOrStartDelayMs,
                maxApiCostMs,
                maxDbCostMs
        );
    }

    /**
     * 清空当前窗口状态。
     */
    private void clear() {
        windowStart = null;
        windowEndExclusive = null;
        successfulMinuteCount = 0;
        expectedStockRows = 0L;
        insertedStockRows = 0L;
        maxQueueOrStartDelayMs = 0L;
        maxApiCostMs = 0L;
        maxDbCostMs = 0L;
    }

    /**
     * 将分钟对齐到其所属的自然 15 分钟窗口起点。
     *
     * @param plannedMinute 计划自然分钟键
     * @return 窗口起点（秒与纳秒清零）
     */
    private LocalDateTime alignToWindowStart(LocalDateTime plannedMinute) {
        int alignedMinute = plannedMinute.getMinute() / WINDOW_MINUTES * WINDOW_MINUTES;
        return plannedMinute.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    /**
     * 单分钟成功采集指标。
     *
     * @param plannedMinute           计划自然分钟键（Asia/Shanghai，秒与纳秒清零）
     * @param expectedStockRows       预期股票行数
     * @param insertedStockRows       实际新插入股票行数
     * @param queueOrStartDelayMillis 窗口开始延迟（毫秒）
     * @param apiCostMillis           Torn API 耗时（毫秒）
     * @param dbCostMillis            历史写入耗时（毫秒）
     */
    public record MinuteMetric(
            LocalDateTime plannedMinute,
            int expectedStockRows,
            int insertedStockRows,
            long queueOrStartDelayMillis,
            long apiCostMillis,
            long dbCostMillis
    ) {
    }

    /**
     * 15 分钟窗口摘要快照。
     *
     * @param windowStart            窗口起点
     * @param windowEndExclusive     窗口终点（不含，为窗口起点 + 15 分钟）
     * @param successfulMinuteCount  本窗口成功完成并记录的分钟数
     * @param expectedStockRows      预期股票行累计数
     * @param insertedStockRows      实际新插入股票行累计数
     * @param maxQueueOrStartDelayMs 窗口最大开始延迟（毫秒）
     * @param maxApiCostMs           窗口最大 API 耗时（毫秒）
     * @param maxDbCostMs            窗口最大历史写入耗时（毫秒）
     */
    public record WindowSummary(
            LocalDateTime windowStart,
            LocalDateTime windowEndExclusive,
            int successfulMinuteCount,
            long expectedStockRows,
            long insertedStockRows,
            long maxQueueOrStartDelayMs,
            long maxApiCostMs,
            long maxDbCostMs
    ) {
    }

    /**
     * 单次记录结果。
     *
     * @param completedWindow           已完成的窗口快照；未完成时为空
     * @param discardedIncompleteWindow 是否因跨窗口丢弃了未完成旧窗口
     */
    public record WindowRecordResult(
            Optional<WindowSummary> completedWindow,
            boolean discardedIncompleteWindow
    ) {

        /**
         * 创建无完成快照、未丢弃旧窗口的空结果。
         *
         * @param discardedIncompleteWindow 是否丢弃了未完成旧窗口
         * @return 空结果
         */
        static WindowRecordResult empty(boolean discardedIncompleteWindow) {
            return new WindowRecordResult(Optional.empty(), discardedIncompleteWindow);
        }

        /**
         * 创建无完成快照、未丢弃旧窗口的空结果。
         *
         * @return 空结果
         */
        static WindowRecordResult empty() {
            return empty(false);
        }

        /**
         * 创建已完成窗口快照的结果。
         *
         * @param summary                   完成的窗口快照
         * @param discardedIncompleteWindow 是否同时丢弃了未完成旧窗口
         * @return 完成结果
         */
        static WindowRecordResult completed(WindowSummary summary, boolean discardedIncompleteWindow) {
            return new WindowRecordResult(Optional.of(summary), discardedIncompleteWindow);
        }
    }
}
