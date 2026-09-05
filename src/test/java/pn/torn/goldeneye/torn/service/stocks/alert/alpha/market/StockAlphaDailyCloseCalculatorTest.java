package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * α策略日线收盘计算器测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
class StockAlphaDailyCloseCalculatorTest {
    @Test
    void selectsLastUsablePositiveBarOnBusinessDate() {
        LocalDate date = LocalDate.of(2026, 9, 5);
        TornStockMarketBar15mDO first = bar(date.atTime(23, 45), "100", true);
        TornStockMarketBar15mDO last = bar(date.atTime(23, 55), "101", true);
        TornStockMarketBar15mDO unusable = bar(date.atTime(23, 59), "102", false);
        assertEquals(new BigDecimal("101"), StockAlphaDailyCloseCalculator.calculate(date, List.of(first, last, unusable)).closePrice());
    }

    @Test
    void missingDateOrInvalidPriceReturnsNull() {
        LocalDate date = LocalDate.of(2026, 9, 5);
        assertNull(StockAlphaDailyCloseCalculator.calculate(date, List.of(bar(date.atStartOfDay(), "0", true))));
    }

    private TornStockMarketBar15mDO bar(LocalDateTime start, String price, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1);
        bar.setBarStartTime(start);
        bar.setBarEndTime(start.plusMinutes(15));
        bar.setLastSampleTime(start.plusMinutes(14));
        bar.setSampleCount(usable ? 15 : 1);
        bar.setUsable(usable);
        bar.setLastPrice(new BigDecimal(price));
        return bar;
    }
}
