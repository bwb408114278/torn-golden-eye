package pn.torn.goldeneye.repository.model.torn.stocks;

/**
 * 股票历史自然分钟计数
 * <p>
 * 用于每日连续性巡检：聚合统计每支有效股票在固定时间窗口内的有效自然分钟数量。
 * SQL 缺失行由调用方解释为 {@code minuteCount=0}。
 *
 * @param stocksId    股票ID
 * @param minuteCount 有效自然分钟数（distinct minute，不含逻辑删除）
 * @author Bai
 * @version 1.2.18
 * @since 2026.07.17
 */
public record StockHistoryMinuteCount(
        Integer stocksId,
        long minuteCount) {
}
