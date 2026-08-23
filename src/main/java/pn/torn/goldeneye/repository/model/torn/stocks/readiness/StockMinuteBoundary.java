package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

import java.time.LocalDateTime;

/**
 * 单支股票在报告范围内的分钟事实边界。
 *
 * @param stocksId        股票ID
 * @param stocksShortname 股票简称
 * @param earliestMinute  最早分钟时间（含）
 * @param latestMinute    最晚分钟时间（含）
 * @param minuteCount     有效自然分钟数
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record StockMinuteBoundary(
        Integer stocksId,
        String stocksShortname,
        LocalDateTime earliestMinute,
        LocalDateTime latestMinute,
        long minuteCount) {
}
