package pn.torn.goldeneye.torn.model.faction.crime.planning;

import pn.torn.goldeneye.torn.service.faction.oc.planning.OcSafeChainCapacityResult;

import java.util.List;

/**
 * OC新队单个方案分支。
 */
public record OcPlanBranch(OcPlanMode mode, long score,
                           List<OcTeamPlan> existingTeamPlans,
                           List<OcTeamPlan> newTeamPlans,
                           OcSafeChainCapacityResult chainCapacity,
                           int recommendedAdditionalChains,
                           OcRefreshAdvice refreshAdvice,
                           List<String> warnings) {
    public OcPlanBranch {
        existingTeamPlans = List.copyOf(existingTeamPlans);
        newTeamPlans = List.copyOf(newTeamPlans);
        warnings = List.copyOf(warnings);
    }
}
