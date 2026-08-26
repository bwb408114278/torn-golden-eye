package pn.torn.goldeneye.torn.service.stocks.readiness;

import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyEvidenceStatus;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyStateCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverage;

import java.util.List;
import java.util.Map;

/**
 * 股票数据就绪只读快照。
 * <p>
 * 由 {@link StockDataReadinessReportRunner} 在单一 {@code READ ONLY + REPEATABLE READ}
 * 事务内加载，所有字段来自数据库真实统计，不硬编码零值。
 *
 * @param stockCount                       当前有效股票数
 * @param stockMinuteCoverages             每股分钟覆盖与缺口统计
 * @param stockWithoutAnyMinuteCount       范围内无任何分钟事实的股票数
 * @param minuteSourceDistribution         分钟事实来源分布
 * @param validMinuteCount                 有效分钟行数
 * @param duplicateMinuteGroupCount        自然分钟重复组数
 * @param duplicateMinuteRedundantRowCount 自然分钟重复冗余行数
 * @param invalidMinuteCount               价格/总股数非法分钟行数
 * @param gapSegmentCount                  全空/leading/internal/trailing 正缺口段数
 * @param maxGapMinutes                    最大缺口分钟数
 * @param totalMissingStockMinutes         所有当前有效股票累计缺失分钟数
 * @param theoreticalBucketCount           理论 15 分钟桶数（股票×桶）
 * @param barCount                         bar 行数
 * @param usableBarCount                   可用 bar 行数
 * @param unusableBarReasonCounts          不可用 bar 按原因分组
 * @param noMinuteFactBucketCount          无分钟事实的理论桶数
 * @param featureCount                     feature 行数
 * @param usableBarMissingFeatureCount     usable bar 缺 feature 数
 * @param featureOrphanCount               feature orphan 数
 * @param strategyReadyFeatureCount        strategyReady=true 的 feature 数
 * @param notReadyFeatureReasonCounts      未就绪 feature 按原因分组
 * @param monthlyStateCounts               月度状态分组计数
 * @param monthlyEvidenceStatuses          逐月逐股月度证据状态(含raw/adjusted口径,V1快照新键为null)
 * @param monthlyIncompleteReasonCounts    DRAFT月度未完整原因汇总
 * @param roundStatusCounts                轮次状态计数
 * @param roundVersionMismatchCount        版本不一致轮次数
 * @param auditSettings                    当前 VIP_STOCK_* 开关只读值
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.23
 */
public record StockDataReadinessSnapshot(
        int stockCount,
        List<StockMinuteCoverage> stockMinuteCoverages,
        long stockWithoutAnyMinuteCount,
        Map<String, Long> minuteSourceDistribution,
        long validMinuteCount,
        long duplicateMinuteGroupCount,
        long duplicateMinuteRedundantRowCount,
        long invalidMinuteCount,
        long gapSegmentCount,
        long maxGapMinutes,
        long totalMissingStockMinutes,
        long theoreticalBucketCount,
        long barCount,
        long usableBarCount,
        Map<String, Long> unusableBarReasonCounts,
        long noMinuteFactBucketCount,
        long featureCount,
        long usableBarMissingFeatureCount,
        long featureOrphanCount,
        long strategyReadyFeatureCount,
        Map<String, Long> notReadyFeatureReasonCounts,
        List<MonthlyStateCount> monthlyStateCounts,
        List<MonthlyEvidenceStatus> monthlyEvidenceStatuses,
        Map<String, Long> monthlyIncompleteReasonCounts,
        Map<String, Long> roundStatusCounts,
        long roundVersionMismatchCount,
        Map<String, String> auditSettings
) {
}
