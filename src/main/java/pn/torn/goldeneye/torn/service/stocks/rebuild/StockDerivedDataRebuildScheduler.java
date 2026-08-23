package pn.torn.goldeneye.torn.service.stocks.rebuild;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

/**
 * VIP 股票派生数据全范围重建调度器。
 * <p>
 * 接收超管“重建VIP股票派生数据”指令，校验生产环境、范围、15 分钟桶对齐、稳定截止后，
 * 通过共享 {@link StockHistoricalMaintenanceGate} 与 Tornsy 回填互斥，并投递
 * {@code stockBackfillExecutor} 专用执行器异步执行。执行完成后向原群发送一次最终回执，
 * 任何结束路径都释放共享互斥门。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDerivedDataRebuildScheduler {

    /**
     * 派生数据重建稳定截止：最近多少分钟不允许重建（避免与实时采集和最新 15m 轮次争用）。
     */
    static final int STABLE_CUTOFF_MINUTES = 30;

    private final StockDerivedDataRebuildService rebuildService;
    private final StockMarketClock clock;
    private final ProjectProperty projectProperty;
    private final ThreadPoolTaskExecutor stockBackfillExecutor;
    private final StockHistoricalMaintenanceGate maintenanceGate;
    private final Bot bot;

    /**
     * 派生数据重建提交结果。
     */
    public enum DerivedRebuildSubmission {
        /**
         * 已成功投递专用执行器（不代表重建完成）。
         */
        ACCEPTED,
        /**
         * 非生产环境，未受理。
         */
        NOT_PROD,
        /**
         * 时间范围无效或对齐后为空。
         */
        INVALID_RANGE,
        /**
         * 结束时间不早于 30 分钟稳定截止，范围过新。
         */
        TOO_RECENT,
        /**
         * 已有 Tornsy 回填或派生重建维护任务在执行中。
         */
        ALREADY_PROCESSING,
        /**
         * 专用维护执行器已满，投递被拒绝。
         */
        EXECUTOR_REJECTED
    }

    /**
     * 提交全范围派生数据重建任务。
     *
     * @param startInclusive 起始时间（含，Asia/Shanghai）
     * @param endExclusive   结束时间（不含）
     * @param groupId        发起指令的群号，用于最终回执
     * @return 提交结果
     */
    public DerivedRebuildSubmission submit(LocalDateTime startInclusive, LocalDateTime endExclusive, long groupId) {
        if (!isProd()) {
            log.warn("派生重建-未受理, 原因=非生产环境, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return DerivedRebuildSubmission.NOT_PROD;
        }
        if (startInclusive == null || endExclusive == null) {
            return DerivedRebuildSubmission.INVALID_RANGE;
        }
        LocalDateTime start = Stock15mBarBuildService.alignToBucket(startInclusive);
        LocalDateTime end = Stock15mBarBuildService.alignToBucket(endExclusive);
        if (!start.isBefore(end)) {
            log.warn("派生重建-未受理, 原因=范围无效, requestedStart={}, requestedEnd={}, alignedStart={}, alignedEnd={}",
                    startInclusive, endExclusive, start, end);
            return DerivedRebuildSubmission.INVALID_RANGE;
        }
        LocalDateTime stableCutoff = Stock15mBarBuildService.alignToBucket(
                clock.now().minusMinutes(STABLE_CUTOFF_MINUTES));
        if (!end.isBefore(stableCutoff)) {
            log.warn("派生重建-未受理, 原因=结束时间不早于{}分钟稳定截止, end={}, stableCutoff={}",
                    STABLE_CUTOFF_MINUTES, end, stableCutoff);
            return DerivedRebuildSubmission.TOO_RECENT;
        }
        if (!maintenanceGate.tryAcquire()) {
            log.warn("派生重建-未受理, 原因=已有历史数据维护任务在执行中, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return DerivedRebuildSubmission.ALREADY_PROCESSING;
        }
        try {
            stockBackfillExecutor.execute(() -> runRebuild(start, end, groupId));
        } catch (RejectedExecutionException e) {
            maintenanceGate.release();
            log.warn("派生重建-未受理, 原因=执行器已满, requestedStart={}, requestedEnd={}",
                    startInclusive, endExclusive);
            return DerivedRebuildSubmission.EXECUTOR_REJECTED;
        }
        log.info("派生重建-已受理, start={}, end={}, groupId={}", start, end, groupId);
        return DerivedRebuildSubmission.ACCEPTED;
    }

    private void runRebuild(LocalDateTime start, LocalDateTime end, long groupId) {
        long begin = System.currentTimeMillis();
        try {
            StockDerivedDataRebuildResult result = rebuildService.rebuildRange(start, end);
            if (result.isSuccess()) {
                log.info("派生数据重建完成, start={}, end={}, result={}", start, end, result);
                sendReceipt(groupId, buildSuccessReceipt(result));
            } else {
                log.error("派生数据重建失败完成, start={}, end={}, result={}", start, end, result);
                sendReceipt(groupId, buildFailureReceipt(result));
            }
        } catch (RuntimeException e) {
            log.error("派生数据重建异常, start={}, end={}: {}", start, end, e.getMessage(), e);
            sendReceipt(groupId, buildExceptionReceipt(start, end, System.currentTimeMillis() - begin, e.getMessage()));
        } finally {
            maintenanceGate.release();
        }
    }

    private String buildSuccessReceipt(StockDerivedDataRebuildResult result) {
        return """
                【VIP股票派生数据重建完成】
                范围：[%s, %s)
                耗时：%dms
                处理桶数：%d
                bar写入数：%d
                feature写入数：%d
                REPAIRED_DATA_ONLY轮次数：%d
                跳过空分钟桶数：%d
                """.formatted(
                result.startInclusive(), result.endExclusive(), result.elapsedMillis(),
                result.processedBucketCount(), result.barWriteCount(), result.featureWriteCount(),
                result.repairedDataOnlyRoundCount(), result.skippedEmptyBucketCount());
    }

    private String buildFailureReceipt(StockDerivedDataRebuildResult result) {
        return """
                【VIP股票派生数据重建失败】
                范围：[%s, %s)
                耗时：%dms
                已完成分片：%d
                失败分片：[%s, %s)
                错误摘要：%s
                可使用相同范围重新提交；已写入部分保持幂等。
                """.formatted(
                result.startInclusive(), result.endExclusive(), result.elapsedMillis(),
                result.processedBucketCount(),
                result.failedSliceStart() == null ? "未知" : result.failedSliceStart(),
                result.failedSliceEnd() == null ? "未知" : result.failedSliceEnd(),
                result.errorSummary() == null ? "未知异常" : result.errorSummary());
    }

    private String buildExceptionReceipt(LocalDateTime start, LocalDateTime end, long elapsed, String error) {
        return """
                【VIP股票派生数据重建失败】
                范围：[%s, %s)
                耗时：%dms
                已完成分片：0
                失败分片：[%s, %s)
                错误摘要：%s
                可使用相同范围重新提交；已写入部分保持幂等。
                """.formatted(start, end, elapsed, start, end, error == null ? "未知异常" : error);
    }

    private void sendReceipt(long groupId, String text) {
        if (groupId <= 0L) {
            log.info("派生重建-无有效回执群号, 仅记录日志: {}", text.replace('\n', ' '));
            return;
        }
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(groupId)
                    .addMsg(new TextQqMsg(text))
                    .build();
            ResponseEntity<String> response = bot.sendRequest(param, String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                log.error("派生重建-最终回执发送失败, groupId={}, responseNull={}", groupId, response == null);
            }
        } catch (Exception e) {
            log.error("派生重建-最终回执发送异常, groupId={}: {}", groupId, e.getMessage(), e);
        }
    }

    private boolean isProd() {
        return BotConstants.ENV_PROD.equals(projectProperty.getEnv());
    }
}
