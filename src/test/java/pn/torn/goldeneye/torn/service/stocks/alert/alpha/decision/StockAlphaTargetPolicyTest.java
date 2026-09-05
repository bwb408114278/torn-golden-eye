package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;

import java.math.BigDecimal;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * α策略目标策略测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
class StockAlphaTargetPolicyTest {
    @Test
    void decidesAt60AndEveryFiveDays() {
        var rankings = rankings();
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(59, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(60, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(61, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(65, rankings, null).event());
    }

    @Test
    void holdsTop3AndChangesOnlyWhenOutsideTop3() {
        var rankings = rankings();
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_HELD, StockAlphaTargetPolicy.decide(60, rankings, 3).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED, StockAlphaTargetPolicy.decide(60, rankings, 5).event());
    }

    private java.util.List<StockAlphaRankingResult> rankings() {
        return IntStream.rangeClosed(1, 5).mapToObj(id -> new StockAlphaRankingResult(id, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(6 - id), id)).toList();
    }
}
