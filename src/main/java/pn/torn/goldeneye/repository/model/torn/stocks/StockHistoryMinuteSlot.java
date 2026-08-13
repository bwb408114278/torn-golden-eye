package pn.torn.goldeneye.repository.model.torn.stocks;

import java.time.LocalDateTime;

/**
 * 股票历史已占用的自然分钟槽位
 * <p>
 * 用于回填前批量读取某股票在时间范围内已存在的有效自然分钟，减少无效写入。
 *
 * @param stocksId   股票ID
 * @param minuteTime 自然分钟时间（已按分钟截断）
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
public record StockHistoryMinuteSlot(
        Integer stocksId,
        LocalDateTime minuteTime) {
}
