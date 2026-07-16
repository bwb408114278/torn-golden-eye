package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Set;

/**
 * 帮派OC新队规划策略。
 *
 * @param factionId 帮派ID
 * @param evaluationMode 岗位候选人评价模式
 * @param normalPoolReservePercent 普通队规划需要保留的成员比例
 * @param enabledOcKeys 允许自动规划的OC键集合
 * @param validationWarnings 策略配置校验警告
 */public record OcFactionPlanningPolicy(long factionId, OcEvaluationMode evaluationMode,
                                      int normalPoolReservePercent,
                                      Set<String> enabledOcKeys,
                                      List<String> validationWarnings) {
    public OcFactionPlanningPolicy {
        enabledOcKeys = enabledOcKeys == null ? Set.of() : Set.copyOf(enabledOcKeys);
        validationWarnings = validationWarnings == null ? List.of() : List.copyOf(validationWarnings);
    }

    /**
     * 判断当前策略是否使用差异工时评价模式。
     *
     * @return 使用差异工时评价模式时返回true
     */
    public boolean isDifferentialWorkingHour() {
        return OcEvaluationMode.DIFFERENTIAL_WORK_HOUR.equals(evaluationMode);
    }
}
