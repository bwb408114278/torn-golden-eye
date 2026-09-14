package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import org.junit.jupiter.api.DisplayName;
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
@DisplayName("α策略目标迟滞策略测试")
class StockAlphaTargetPolicyTest {
    @Test
    @DisplayName("决策日_第60个共同有效日决策且之后每5个共同有效日决策")
    void decidesAt60AndEveryFiveDays() {
        var rankings = rankings();
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(59, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(60, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(61, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(65, rankings, null).event());
    }

    @Test
    @DisplayName("迟滞_持仓在Top3内保持且跌出Top3才换仓")
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
