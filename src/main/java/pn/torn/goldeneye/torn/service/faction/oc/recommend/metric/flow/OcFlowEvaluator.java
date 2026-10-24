package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.*;

import java.util.HashMap;
import java.util.Map;

/**
 * OC流程图求值器。
 *
 * <p>沿拓扑序把概率质量从起点推到结局：判定节点按成功率分流到PASS与FAIL，结局节点只累积不再分流。
 * 成功率为所有好结局的命中概率之和，因此多分支汇流、单判定直接失败等情形都由同一套推进规则覆盖，
 * 不需要为每种结构单独写公式。</p>
 *
 * <p>participants 为空或参与者不在给定成功率表里时，该参与者按缺省成功率计：这样既能表达
 * 「这个判定不真正依赖某个具体岗位」，也不会因为配置缺一个人就让整张图求值失败。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Component
public class OcFlowEvaluator {

    /**
     * 求值一张流程图。
     *
     * @param graph            已通过结构校验的流程图
     * @param slotPassRates    岗位编码到成功率的映射，取值0~1
     * @param fallbackPassRate 参与者缺失时使用的缺省成功率，取值0~1
     * @return 成功率与结局分布
     * @throws IllegalArgumentException 流程图为空或成功率超出0~1
     */
    public OcFlowOutcome evaluate(OcFlowGraph graph, Map<String, Double> slotPassRates, double fallbackPassRate) {
        if (graph == null) {
            throw new IllegalArgumentException("流程图为空");
        }
        requireRate(fallbackPassRate, "缺省成功率");
        Map<String, Double> rates = slotPassRates == null ? Map.of() : slotPassRates;

        Map<String, Double> mass = new HashMap<>();
        Map<String, Double> endingRates = new HashMap<>();
        mass.put(graph.startNodeCode(), 1.0);
        for (String nodeCode : graph.topologicalOrder()) {
            double current = mass.getOrDefault(nodeCode, 0.0);
            if (current <= 0.0) {
                continue;
            }
            if (graph.node(nodeCode).isCheck()) {
                advanceCheck(graph, nodeCode, current, rates, fallbackPassRate, mass);
            } else {
                endingRates.merge(nodeCode, current, Double::sum);
            }
        }

        double successRate = endingRates.entrySet().stream()
                .filter(entry -> graph.node(entry.getKey()).nodeType() == OcFlowNodeTypeEnum.GOOD_END)
                .mapToDouble(Map.Entry::getValue)
                .sum();
        return new OcFlowOutcome(successRate, endingRates);
    }

    /**
     * 判定节点按成功率把当前质量分到PASS与FAIL两侧。
     */
    private void advanceCheck(OcFlowGraph graph, String nodeCode, double current, Map<String, Double> rates,
                              double fallbackPassRate, Map<String, Double> mass) {
        double passRate = checkPassRate(graph.node(nodeCode), rates, fallbackPassRate);
        mass.merge(graph.targetOf(nodeCode, OcFlowBranchEnum.PASS), current * passRate, Double::sum);
        mass.merge(graph.targetOf(nodeCode, OcFlowBranchEnum.FAIL), current * (1 - passRate), Double::sum);
    }

    /**
     * 计算单个判定的成功率：多参与者取算术平均，与站点计算器的flex口径一致。
     */
    private double checkPassRate(OcFlowNode node, Map<String, Double> rates, double fallbackPassRate) {
        if (node.participants().isEmpty()) {
            return fallbackPassRate;
        }
        double sum = 0.0;
        for (String participant : node.participants()) {
            Double rate = rates.get(participant);
            double value = rate == null ? fallbackPassRate : rate;
            requireRate(value, participant);
            sum += value;
        }
        return sum / node.participants().size();
    }

    /**
     * 校验成功率取值，避免把百分数当成概率传入后静默算出离谱结果。
     */
    private void requireRate(double rate, String source) {
        if (Double.isNaN(rate) || rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("成功率超出0~1范围: " + source + "=" + rate);
        }
    }
}
