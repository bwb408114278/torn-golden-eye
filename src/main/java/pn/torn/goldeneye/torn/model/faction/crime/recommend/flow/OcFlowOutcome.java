package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

import java.util.Map;

/**
 * OC流程图求值结果。
 *
 * <p>成功率为「最终落到好结局的概率之和」，即排序使用的S。结局分布保留每个结局节点的命中概率，
 * 用于校验图与上游计算器是否一致，以及回测时定位偏差出在哪个结局上；排序本身只用成功率。</p>
 *
 * @param successRate 成功概率（0~1）
 * @param endingRates 结局节点编码到命中概率的映射
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public record OcFlowOutcome(
        double successRate,
        Map<String, Double> endingRates) {
    /**
     * 归一化结局分布，避免调用方修改求值结果。
     */
    public OcFlowOutcome {
        endingRates = endingRates == null ? Map.of() : Map.copyOf(endingRates);
    }

    /**
     * 查询指定结局的命中概率。
     *
     * @param nodeCode 结局节点编码
     * @return 命中概率；未到达时返回0
     */
    public double endingRate(String nodeCode) {
        return endingRates.getOrDefault(nodeCode, 0.0);
    }
}
