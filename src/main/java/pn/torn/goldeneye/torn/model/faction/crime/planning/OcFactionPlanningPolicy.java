package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Set;

/**
 * 帮派OC新队规划策略。
 */
public record OcFactionPlanningPolicy(long factionId, OcEvaluationMode evaluationMode,
                                      int normalPoolReservePercent,
                                      Set<String> enabledOcKeys,
                                      List<String> validationWarnings) {
    public OcFactionPlanningPolicy {
        enabledOcKeys = enabledOcKeys == null ? Set.of() : Set.copyOf(enabledOcKeys);
        validationWarnings = validationWarnings == null ? List.of() : List.copyOf(validationWarnings);
    }

    public boolean isDifferentialWorkingHour() {
        return OcEvaluationMode.DIFFERENTIAL_WORK_HOUR.equals(evaluationMode);
    }
}
