package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * α策略目标策略测试。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.05
 */
@DisplayName("α策略目标迟滞策略测试")
class StockAlphaTargetPolicyTest {
    /**
     * 本测试使用的正式α相位轨道(偏移0)。
     */
    private static final StockAlphaPhaseTrack TRACK = StockAlphaTrackRegistry.productionTrack();

    @Test
    @DisplayName("决策日_第60个共同有效日决策且之后每5个共同有效日决策_非决策日数据不足")
    void decidesAt60AndEveryFiveDays() {
        var rankings = rankings();
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(TRACK, 59, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(TRACK, 60, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, StockAlphaTargetPolicy.decide(TRACK, 61, rankings, null).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, StockAlphaTargetPolicy.decide(TRACK, 65, rankings, null).event());
    }

    @Test
    @DisplayName("迟滞_持仓在Top3内保持且跌出Top3才换仓")
    void holdsTop3AndChangesOnlyWhenOutsideTop3() {
        var rankings = rankings();
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_HELD, StockAlphaTargetPolicy.decide(TRACK, 60, rankings, 3).event());
        assertEquals(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED, StockAlphaTargetPolicy.decide(TRACK, 60, rankings, 5).event());
    }

    private List<StockAlphaRankingResult> rankings() {
        return IntStream.rangeClosed(1, 5).mapToObj(id -> new StockAlphaRankingResult(id, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(6 - id), id)).toList();
    }
}