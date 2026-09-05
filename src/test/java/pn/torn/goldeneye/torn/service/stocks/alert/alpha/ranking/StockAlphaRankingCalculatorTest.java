package pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * α策略排名计算器测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
class StockAlphaRankingCalculatorTest {
    @Test
    void incompleteUniverseIsRejected() {
        Map<Integer, List<BigDecimal>> closes = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> StockAlphaRankingCalculator.calculate(closes));
    }

    @Test
    void usesStableOrderAverageRankAndScale18() {
        Map<Integer, List<BigDecimal>> closes = new HashMap<>();
        for (int stockId = 1; stockId <= 35; stockId++) {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < 20; i++) values.add(new BigDecimal("100"));
            values.add(new BigDecimal(stockId == 1 ? "90" : stockId == 2 ? "90" : "110"));
            closes.put(stockId, values);
        }
        List<StockAlphaRankingResult> result = StockAlphaRankingCalculator.calculate(closes);
        assertEquals(35, result.size());
        assertEquals(1, result.getFirst().rankPosition());
        assertEquals(18, result.get(17).rankPosition());
        assertEquals(18, result.get(17).alphaScore().scale());
        assertEquals(result.stream().filter(item -> item.r20().compareTo(result.getFirst().r20()) == 0)
                .map(StockAlphaRankingResult::r20Rank).distinct().count(), 1);
    }
}
