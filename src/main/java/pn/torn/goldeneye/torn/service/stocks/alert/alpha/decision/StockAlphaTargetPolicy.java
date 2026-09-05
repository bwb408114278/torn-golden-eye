package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;

import java.util.List;

/**
 * α策略目标迟滞策略。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaTargetPolicy {

    /**
     * 根据共同有效日序号和排名决定目标事件。
     *
     * @param commonDayIndex  共同有效日序号，从1开始
     * @param rankings        当前排名
     * @param currentStocksId 当前持仓股票ID
     * @return 目标结果
     */
    public static TargetResult decide(int commonDayIndex, List<StockAlphaRankingResult> rankings,
                                      Integer currentStocksId) {
        if (commonDayIndex < StockAlphaRuleDefinition.WARMUP_COMMON_DAYS
                || (commonDayIndex - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
                % StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS != 0) {
            return new TargetResult(TargetEvent.DATA_INSUFFICIENT, null);
        }
        if (rankings == null || rankings.isEmpty()) return new TargetResult(TargetEvent.DATA_INSUFFICIENT, null);
        Integer top1 = rankings.getFirst().stocksId();
        if (currentStocksId == null) return new TargetResult(TargetEvent.ALPHA_INITIAL_ENTRY, top1);
        boolean inTop3 = rankings.stream().limit(StockAlphaRuleDefinition.HYSTERESIS_TOP)
                .anyMatch(result -> currentStocksId.equals(result.stocksId()));
        if (inTop3 || currentStocksId.equals(top1)) {
            return new TargetResult(TargetEvent.ALPHA_TARGET_HELD, currentStocksId);
        }
        if (!currentStocksId.equals(top1)) {
            return new TargetResult(TargetEvent.ALPHA_TARGET_CHANGED, top1);
        }
        return new TargetResult(TargetEvent.DATA_INSUFFICIENT, null);
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
