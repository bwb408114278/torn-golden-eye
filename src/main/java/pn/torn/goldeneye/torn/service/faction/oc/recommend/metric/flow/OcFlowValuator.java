package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把DAG结局分布折算成帮派期望收益。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@Component
@RequiredArgsConstructor
public class OcFlowValuator {
    private static final double DEFAULT_FALLBACK_RATE = 0.6;

    private final OcFlowEvaluator evaluator;

    /**
     * 计算一张图的分结局期望收益。
     *
     * @param graph          流程图
     * @param ratesBySlot    归一化岗位编码到0~1成功率的映射
     * @param chainValue     前置OC对应的后置链值；独立OC传null
     * @return 估值结果；参与者缺失或奖励未配置完整时返回empty
     */
    public Optional<OcFlowValuation> evaluate(OcFlowGraph graph, Map<String, Double> ratesBySlot,
                                              BigDecimal chainValue) {
        Map<String, Double> slotRates = new HashMap<>();
        for (String nodeCode : graph.topologicalOrder()) {
            OcFlowNode node = graph.node(nodeCode);
            for (String participant : node.participants()) {
                Double rate = ratesBySlot.get(OcFlowSlotCodes.normalize(participant));
                if (rate == null) {
                    return Optional.empty();
                }
                slotRates.put(participant, rate);
            }
        }

        boolean directRewardsComplete = directRewardsComplete(graph);
        if (!directRewardsComplete && chainValue == null) {
            return Optional.empty();
        }
        OcFlowOutcome outcome = evaluator.evaluate(graph, slotRates, DEFAULT_FALLBACK_RATE);
        BigDecimal expectedValue = directRewardsComplete
                ? directValue(graph, outcome) : chainValue.multiply(BigDecimal.valueOf(outcome.successRate()));
        return Optional.of(new OcFlowValuation(outcome.successRate(), expectedValue));
    }

    /**
     * 独立OC必须给每个好结局配置奖励；前置OC的好结局奖励留空，由链值承接。
     */
    private boolean directRewardsComplete(OcFlowGraph graph) {
        for (String nodeCode : graph.topologicalOrder()) {
            OcFlowNode node = graph.node(nodeCode);
            if (node.nodeType() == OcFlowNodeTypeEnum.GOOD_END && node.rewardValue() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 概率只用于换算，货币加法与乘法统一使用BigDecimal。
     */
    private BigDecimal directValue(OcFlowGraph graph, OcFlowOutcome outcome) {
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<String, Double> entry : outcome.endingRates().entrySet()) {
            OcFlowNode node = graph.node(entry.getKey());
            if (node.nodeType() == OcFlowNodeTypeEnum.GOOD_END) {
                value = value.add(BigDecimal.valueOf(node.rewardValue())
                        .multiply(BigDecimal.valueOf(entry.getValue())));
            }
        }
        return value;
    }
}
