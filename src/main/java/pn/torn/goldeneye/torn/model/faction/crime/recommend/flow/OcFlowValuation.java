package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

import java.math.BigDecimal;

/**
 * OC流程图的货币估值结果。
 *
 * @param successRate   所有好结局的到达概率之和，取值0~1
 * @param expectedValue 分结局奖励加权的期望收益；链式前置为成功概率乘后置链值
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
public record OcFlowValuation(
        double successRate,
        BigDecimal expectedValue) {
}
