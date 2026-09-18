package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;

import java.util.List;

/**
 * α策略目标迟滞策略。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaTargetPolicy {

    /**
     * 根据相位轨道、共同有效日序号和排名决定目标事件。
     * <p>
     * "是否决策日"只委托{@link StockAlphaPhaseTrack#isDecisionDay(int)}:本类不得再出现
     * 决策间隔取模或相位偏移字面量,避免形成第二套相位判断。
     *
     * @param track           本次决策所属的相位轨道
     * @param commonDayIndex  共同有效日序号，从1开始
     * @param rankings        当前排名
     * @param currentStocksId 当前持仓股票ID
     * @return 目标结果
     */
    public static TargetResult decide(StockAlphaPhaseTrack track, int commonDayIndex,
                                      List<StockAlphaRankingResult> rankings, Integer currentStocksId) {
        if (track == null || !track.isDecisionDay(commonDayIndex)) {
            return new TargetResult(TargetEvent.DATA_INSUFFICIENT, null);
        }
        if (rankings == null || rankings.isEmpty()) return new TargetResult(TargetEvent.DATA_INSUFFICIENT, null);
        Integer top1 = rankings.getFirst().stocksId();
        if (currentStocksId == null) return new TargetResult(TargetEvent.ALPHA_INITIAL_ENTRY, top1);
        boolean inTop3 = rankings.stream().limit(StockAlphaRuleDefinition.HYSTERESIS_TOP)
                .anyMatch(result -> currentStocksId.equals(result.stocksId()));
        if (inTop3) {
            return new TargetResult(TargetEvent.ALPHA_TARGET_HELD, currentStocksId);
        }
        return new TargetResult(TargetEvent.ALPHA_TARGET_CHANGED, top1);
    }

    /**
     * 目标事件。
     */
    public enum TargetEvent {DATA_INSUFFICIENT, ALPHA_INITIAL_ENTRY, ALPHA_TARGET_HELD, ALPHA_TARGET_CHANGED}

    /**
     * 目标策略结果。
     *
     * @param event          事件
     * @param targetStocksId 目标股票ID
     */
    public record TargetResult(
            TargetEvent event,
            Integer targetStocksId) {
    }
}
