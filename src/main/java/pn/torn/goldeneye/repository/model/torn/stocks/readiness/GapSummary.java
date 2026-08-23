package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

/**
 * 分钟缺口汇总。
 *
 * @param gapSegmentCount 连续缺口段数
 * @param maxGapMinutes   最大缺口分钟数
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record GapSummary(
        long gapSegmentCount,
        long maxGapMinutes) {
}
