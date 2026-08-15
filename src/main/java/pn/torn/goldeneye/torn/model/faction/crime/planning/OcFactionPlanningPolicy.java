package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Set;

/**
 * 帮派OC新队规划策略。旧容量比例列保留在数据库中兼容历史数据，但不再进入新规划模型。
 *
 * @param factionId          帮派ID
 * @param evaluationMode     岗位候选人评价模式
 * @param enabledOcKeys      允许自动规划的OC键集合
 * @param validationWarnings 策略配置校验警告
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public record OcFactionPlanningPolicy(
        long factionId,
        OcEvaluationMode evaluationMode,
        Set<String> enabledOcKeys,
        List<String> validationWarnings) {
    public OcFactionPlanningPolicy {
        enabledOcKeys = enabledOcKeys == null ? Set.of() : Set.copyOf(enabledOcKeys);
        validationWarnings = validationWarnings == null ? List.of() : List.copyOf(validationWarnings);
    }
}
