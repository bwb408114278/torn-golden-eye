package pn.torn.goldeneye.torn.service.stocks.rebuild;

import java.time.LocalDateTime;

/**
 * 全范围派生数据重建结果汇总。
 * <p>
 * 该 record 用于调度层生成最终群回执，并记录失败分片与错误摘要。成功时
 * {@code failedSliceStart}/{@code failedSliceEnd}/{@code errorSummary} 为 {@code null}；
 * 失败时这些字段承载可运维定位信息，已写入部分保留并可通过相同范围幂等重跑。
 *
 * @param startInclusive             实际对齐后的起始时间（含，Asia/Shanghai）
 * @param endExclusive               实际对齐后的结束时间（不含）
 * @param stockCount                 本次参与重建的当前有效股票数量
 * @param processedBucketCount       实际存在分钟事实的 15 分钟桶数量
 * @param barWriteCount              bar 批量 UPSERT 写入条数
 * @param featureWriteCount          feature 批量 UPSERT 写入条数
 * @param repairedDataOnlyRoundCount 写入 {@code REPAIRED_DATA_ONLY} 的轮次数
 * @param skippedEmptyBucketCount    范围内无分钟事实而被跳过的 15 分钟桶数量
 * @param elapsedMillis              本次重建耗时（毫秒）
 * @param failedSliceStart           失败时当前处理分片起始时间；成功时为 null
 * @param failedSliceEnd             失败时当前处理分片结束时间；成功时为 null
 * @param errorSummary               失败时的错误摘要；成功时为 null
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record StockDerivedDataRebuildResult(
        LocalDateTime startInclusive,
        LocalDateTime endExclusive,
        int stockCount,
        int processedBucketCount,
        int barWriteCount,
        int featureWriteCount,
        int repairedDataOnlyRoundCount,
        int skippedEmptyBucketCount,
        long elapsedMillis,
        LocalDateTime failedSliceStart,
        LocalDateTime failedSliceEnd,
        String errorSummary
) {

    /**
     * 是否成功完成。
     *
     * @return true 表示没有失败分片，且错误摘要为 null
     */
    public boolean isSuccess() {
        return failedSliceStart == null && failedSliceEnd == null && errorSummary == null;
    }
}
