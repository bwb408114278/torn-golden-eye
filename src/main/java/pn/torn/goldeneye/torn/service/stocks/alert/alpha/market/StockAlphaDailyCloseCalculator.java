package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * α策略自然日收盘纯计算器。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaDailyCloseCalculator {

    /**
     * 取指定自然日最后一根可用且价格合法的15分钟bar。
     *
     * @param businessDate 自然日
     * @param bars         当日bar
     * @return 收盘结果；无合法bar时为空
     */
    public static CloseResult calculate(LocalDate businessDate, List<TornStockMarketBar15mDO> bars) {
        return bars == null ? null : bars.stream()
                .filter(bar -> isOnDate(bar, businessDate))
                .filter(Stock15mBarBuildService::isUsable)
                .filter(bar -> bar.getLastPrice() != null && bar.getLastPrice().signum() > 0)
                .max(Comparator.comparing(TornStockMarketBar15mDO::getBarStartTime))
                .map(bar -> new CloseResult(bar.getStocksId(), businessDate, bar.getLastPrice(), bar.getId(),
                        bar.getBarStartTime()))
                .orElse(null);
    }

    /**
     * 判断bar是否属于指定自然日。
     *
     * @param bar  15分钟bar
     * @param date 自然日
     * @return 是否属于指定日期
     */
    private static boolean isOnDate(TornStockMarketBar15mDO bar, LocalDate date) {
        return bar != null && date != null && bar.getBarStartTime() != null
                && date.equals(bar.getBarStartTime().toLocalDate());
    }

    /**
     * 日线收盘结果。
     *
     * @param stocksId           股票ID
     * @param businessDate       自然日
     * @param closePrice         收盘价
     * @param sourceBarId        来源bar ID
     * @param sourceBarStartTime 来源bar开始时间
     */
    public record CloseResult(
            Integer stocksId,
            LocalDate businessDate,
            BigDecimal closePrice,
            Long sourceBarId,
            LocalDateTime sourceBarStartTime) {
    }
}
