package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

import java.util.List;

/**
 * 全股票分钟覆盖汇总。
 *
 * @param stockCount                    当前有效股票数
 * @param stockWithoutAnyMinuteCount    范围内没有任何分钟事实的股票数
 * @param gapSegmentCount               全空/leading/internal/trailing 正缺口段数合计
 * @param maxGapMinutes                 所有缺口的最大分钟数
 * @param totalMissingStockMinutes      所有当前有效股票缺失分钟数之和
 * @param duplicateMinuteGroupCount     自然分钟重复组数
 * @param duplicateMinuteRedundantRowCount 自然分钟重复冗余行数（组内超出首行的行数合计）
 * @param coverages                     每股覆盖统计
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record StockMinuteCoverageSummary(
        int stockCount,
        long stockWithoutAnyMinuteCount,
        long gapSegmentCount,
        long maxGapMinutes,
        long totalMissingStockMinutes,
        long duplicateMinuteGroupCount,
        long duplicateMinuteRedundantRowCount,
        List<StockMinuteCoverage> coverages) {
}
