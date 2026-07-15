package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanBranch;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshAdvice;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于单个不可变快照计算三个OC新队方案分支。
 */
public class OcNewTeamPlanningEngine {
    private final OcRefreshStrategyPlanner refreshStrategyPlanner;

    public OcNewTeamPlanningEngine(OcRefreshStrategyPlanner refreshStrategyPlanner) {
        this.refreshStrategyPlanner = refreshStrategyPlanner;
    }

    public OcNewTeamPlan plan(OcPlanningSnapshot snapshot, OcPlanMode requestedMode) {
        long validKeyCount = snapshot.policy().enabledOcKeys().stream()
                .filter(key -> !snapshot.invalidOcKeys().contains(key))
                .count();
        if (validKeyCount == 0) {
            boolean scopeMissing = snapshot.policy().enabledOcKeys().isEmpty();
            String reason = scopeMissing ? "未配置规划范围" : "规划范围配置全部无效";
            List<String> warnings = new ArrayList<>(snapshot.warnings());
            warnings.add(scopeMissing
                    ? "当前帮派未配置OC新队规划范围，已禁止自动规划和刷新建议"
                    : "当前帮派OC新队规划范围配置全部无效，已禁止自动规划和刷新建议");
            List<OcPlanBranch> disabled = java.util.Arrays.stream(OcPlanMode.values())
                    .map(mode -> disabledBranch(mode, reason))
                    .toList();
            OcPlanBranch requested = disabled.stream()
                    .filter(branch -> branch.mode() == requestedMode).findFirst().orElseThrow();
            return new OcNewTeamPlan(snapshot.factionId(), snapshot.snapshotTime(), requestedMode,
                    requested, disabled, warnings);
        }
        List<OcPlanBranch> branches = new ArrayList<>();
        for (OcPlanMode mode : OcPlanMode.values()) {
            branches.add(buildBranch(snapshot, mode));
        }
        OcPlanBranch requested = branches.stream().filter(branch -> branch.mode() == requestedMode)
                .findFirst().orElseThrow();
        OcPlanBranch recommended = hasAction(requested) ? requested : branches.stream()
                .filter(this::hasAction)
                .max(java.util.Comparator.comparingLong(OcPlanBranch::score))
                .orElse(requested);
        return new OcNewTeamPlan(snapshot.factionId(), snapshot.snapshotTime(), requestedMode,
                recommended, branches, snapshot.warnings());
    }

    private OcPlanBranch disabledBranch(OcPlanMode mode, String reason) {
        return new OcPlanBranch(mode, 0L, List.of(), List.of(),
                new OcSafeChainCapacityResult(0, 0, 0, true), 0,
                new OcRefreshAdvice(false, "", 0, false, reason),
                List.of(reason));
    }

    private OcPlanBranch buildBranch(OcPlanningSnapshot snapshot, OcPlanMode mode) {
        ExistingTeamRescueResult rescue = new ExistingTeamRescuePlanner().plan(snapshot, mode);
        ChainPlanningResult chainCapacity = new OcChainPlanningService()
                .calculate(snapshot, rescue);
        List<String> notes = new ArrayList<>(chainCapacity.warnings());
        if (!chainCapacity.committedObligationsFeasible()) {
            String reason = "已承诺高阶链后继无法证明可完成，已停止所有新队和刷新建议";
            notes.add(reason);
            return new OcPlanBranch(mode, 0L, rescue.plans(), List.of(),
                    chainCapacity.capacity(), 0,
                    new OcRefreshAdvice(false, "", 0, false, reason), notes);
        }
        int recommendedAdditional = recommendedAdditional(mode, chainCapacity.capacity());
        OcPipelinePlanningResult highRoots = new OcNewTeamPipelinePlanner().plan(snapshot,
                chainCapacity.memberTimeline(), rescue.plans(), mode, recommendedAdditional, true,
                chainCapacity.provenRootKey());
        ChainPlanningResult chain = new OcChainSuccessorReservationPlanner().reserve(snapshot,
                chainCapacity, highRoots, mode);
        OcPipelinePlanningResult normalPipeline = new OcNewTeamPipelinePlanner().plan(snapshot,
                chain.memberTimeline(), rescue.plans(), mode, 0, false, null);
        List<OcTeamPlan> newTeams;
        if (chain.capacity().provenAdditionalCount() == 0 && !highRoots.plans().isEmpty()) {
            newTeams = normalPipeline.plans();
            recommendedAdditional = 0;
        } else {
            newTeams = java.util.stream.Stream.concat(highRoots.plans().stream(),
                    normalPipeline.plans().stream()).toList();
        }
        OcRefreshAdvice refreshAdvice = new OcRefreshStrategyPlanner().plan(mode,
                newTeams, chain.capacity());
        notes.addAll(chain.warnings());
        long score = score(mode, rescue.plans(), newTeams, recommendedAdditional);
        return new OcPlanBranch(mode, score, rescue.plans(), newTeams, chain.capacity(),
                recommendedAdditional, refreshAdvice, notes);
    }

    private boolean hasAction(OcPlanBranch branch) {
        return branch.existingTeamPlans().stream().anyMatch(OcTeamPlan::complete)
                || branch.newTeamPlans().stream().anyMatch(OcTeamPlan::complete)
                || branch.refreshAdvice().refreshRecommended();
    }

    private int recommendedAdditional(OcPlanMode mode, OcSafeChainCapacityResult capacity) {
        int safe = capacity.provenAdditionalCount();
        return switch (mode) {
            case CONSERVATIVE -> 0;
            case BALANCED -> safe == 0 ? 0 : Math.max(1, safe / 2);
            case PROFIT -> safe;
        };
    }

    private long score(OcPlanMode mode, List<OcTeamPlan> rescues, List<OcTeamPlan> newTeams,
                       int recommendedAdditional) {
        long rescuedUsers = rescues.stream().filter(OcTeamPlan::complete)
                .mapToLong(OcTeamPlan::unlockScore).sum();
        long pipelineValue = newTeams.stream().filter(OcTeamPlan::complete)
                .mapToLong(OcTeamPlan::unlockScore).sum();
        return switch (mode) {
            case CONSERVATIVE -> rescuedUsers * 10L + newTeams.size();
            case BALANCED -> rescuedUsers * 5L + pipelineValue * 5L + recommendedAdditional;
            case PROFIT -> pipelineValue * 10L + recommendedAdditional * 1_000_000L;
        };
    }
}
