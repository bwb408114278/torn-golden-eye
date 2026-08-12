package pn.torn.goldeneye.repository.model.torn.stocks;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票价格时间点
 *
 * @param id              原始历史记录ID
 * @param stocksId        股票ID
 * @param stocksShortname 股票简称
 * @param price           价格
 * @param investors       投资人数
 * @param time            时间
 * @author Bai
 * @version 1.2.12
 * @since 2026.06.02
 */
public record StockPricePoint(
        Long id,
        Integer stocksId,
        String stocksShortname,
        BigDecimal price,
        Integer investors,
        LocalDateTime time) {
}