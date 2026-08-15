package pn.torn.goldeneye.torn.service.stocks.backfill;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tornsy m1 分钟报价不可变数据对象
 * <p>
 * 由 {@link TornsyMinuteQuoteParser} 从 Tornsy m1 接口行级数组解析并校验后产生，
 * 时间已转换为 {@code Asia/Shanghai} 自然分钟（秒与纳秒均为 0），价格与总股数均为正数，
 * 市值在数据源未提供时为 {@code null}（禁止以 0 代表未知）。
 *
 * @param minuteTime  自然分钟时间（Asia/Shanghai，秒与纳秒为 0）
 * @param price       该分钟价格（必为正数）
 * @param totalShares 该分钟总股数（必为正数）
 * @param marketCap   该分钟市值（可空，数据源提供且为正数时才写值）
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
public record TornsyMinuteQuote(
        LocalDateTime minuteTime,
        BigDecimal price,
        long totalShares,
        Long marketCap) {
}
