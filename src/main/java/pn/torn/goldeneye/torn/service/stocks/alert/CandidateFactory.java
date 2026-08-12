package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;

import java.util.List;

/**
 * 候选工厂 - 从信号评估结果构建 {@link CandidateInfo}。
 * <p>
 * 纯规则组件,无DAO、无事务、无写操作。由 {@link StockBuySignalEvaluator} 在
 * 候选被正式接纳时调用,提取主策略、命中策略编码与质量分。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class CandidateFactory {

    /**
     * 从信号评估结果构建候选信息。
     *
     * @param evaluation 已通过资格的信号评估
     * @return 候选信息
     */
    public CandidateInfo build(StockBuySignalResult.SignalEvaluation evaluation) {
        List<String> matchedStrategyCodes = evaluation.matchedStrategies().stream()
                .map(s -> s.getStrategyType().getCode())
                .toList();
        return new CandidateInfo(
                evaluation.stocksId(),
                evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType(),
                matchedStrategyCodes,
                evaluation.qualityScore()
        );
    }
}
