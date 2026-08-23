package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

import java.time.LocalDateTime;

/**
 * 单支当前有效股票在报告范围内的分钟覆盖统计。
 *
 * @param stocksId               股票ID
 * @param stocksShortname        股票简称
 * @param firstMinute            范围内最早分钟事实（无分钟时为 null）
 * @param lastMinute             范围内最晚分钟事实（无分钟时为 null）
 * @param minuteCount            范围内自然分钟事实数
 * @param leadingGapMinutes      范围起点到首个分钟事实之间的缺口分钟数
 * @param internalGapSegmentCount 相邻分钟事实之间的正缺口段数
 * @param internalMaxGapMinutes   相邻分钟事实之间的最大缺口分钟数
 * @param trailingGapMinutes      最后一个分钟事实之后到范围终点之间的缺口分钟数
 * @param totalMissingMinutes     该股票范围内累计缺失分钟数
 * @param duplicateGroupCount     该股票自然分钟重复组数（内部统计，供汇总）
 * @param duplicateRedundantRowCount 该股票自然分钟重复冗余行数（内部统计，供汇总）
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record StockMinuteCoverage(
        Integer stocksId,
        String stocksShortname,
        LocalDateTime firstMinute,
        LocalDateTime lastMinute,
        long minuteCount,
        long leadingGapMinutes,
        long internalGapSegmentCount,
        long internalMaxGapMinutes,
        long trailingGapMinutes,
        long totalMissingMinutes,
        long duplicateGroupCount,
        long duplicateRedundantRowCount) {
}
