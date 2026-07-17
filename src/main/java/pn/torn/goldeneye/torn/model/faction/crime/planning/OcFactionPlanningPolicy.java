package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Set;

/**
 * 帮派OC新队规划策略。
 *
 * @param factionId 帮派ID
 * @param evaluationMode 岗位候选人评价模式
 * @param normalPoolReservePercent 普通队规划需要保留的成员比例
 * @param conservativeCapacityPercent 保守模式使用的安全刷新容量比例
 * @param balancedCapacityPercent 均衡模式使用的安全刷新容量比例
 * @param profitCapacityPercent 收益模式使用的安全刷新容量比例
 * @param enabledOcKeys 允许自动规划的OC键集合
 * @param validationWarnings 策略配置校验警告
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcFactionPlanningPolicy(long factionId,
                                      OcEvaluationMode evaluationMode,
                                      int normalPoolReservePercent,
                                      int conservativeCapacityPercent,
                                      int balancedCapacityPercent,
                                      int profitCapacityPercent,
                                      Set<String> enabledOcKeys,
                                      List<String> validationWarnings) {
    public OcFactionPlanningPolicy {
        enabledOcKeys = enabledOcKeys == null ? Set.of() : Set.copyOf(enabledOcKeys);
        validationWarnings = validationWarnings == null ? List.of() : List.copyOf(validationWarnings);
    }
}
