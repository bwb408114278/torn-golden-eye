package pn.torn.goldeneye.torn.model.faction.crime.planning;

import pn.torn.goldeneye.torn.service.faction.oc.planning.OcSafeChainCapacityResult;

import java.util.List;

/**
 * OC新队单个方案分支。
 *
 * @param mode 规划模式
 * @param score 分支综合评分
 * @param existingTeamPlans 旧队补位规划
 * @param newTeamPlans 新队规划
 * @param chainCapacity 高阶链安全容量证明
 * @param recommendedAdditionalChains 建议新增的高阶链数量
 * @param refreshAdvice OC刷新建议
 * @param warnings 分支规划警告
 */public record OcPlanBranch(OcPlanMode mode, long score,
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
