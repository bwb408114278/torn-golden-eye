package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已实际安排高阶根队后，为其后继节点保留成员时间线。
 */
public class OcChainSuccessorReservationPlanner {
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    public ChainPlanningResult reserve(OcPlanningSnapshot snapshot,
                                       ChainPlanningResult capacityResult,
                                       OcPipelinePlanningResult rootPlans,
                                       OcPlanMode mode) {
        List<List<OcTeamDemand>> chains = new OcChainPlanningService().buildReadyChains(snapshot);
        if (chains.isEmpty()) {
            return capacityResult;
        }
        Set<String> rootNames = chains.stream().map(List::getFirst).map(OcTeamDemand::ocName)
                .collect(java.util.stream.Collectors.toSet());
        List<LocalDateTime> rootCompletionTimes = rootPlans.plans().stream()
                .filter(plan -> rootNames.contains(plan.ocName()))
                .filter(plan -> plan.complete() && plan.completionAt() != null)
                .map(plan -> plan.completionAt()).sorted().toList();
        if (rootCompletionTimes.isEmpty()) {
            return new ChainPlanningResult(capacityResult.capacity(),
                    capacityResult.committedObligationsFeasible(), capacityResult.provenRootKey(),
                    capacityResult.chainNames(),
                    rootPlans.memberTimeline(), List.of(), capacityResult.warnings());
        }
        List<OcTeamDemand> selectedChain = chains.stream()
                .filter(chain -> rootPlans.plans().stream()
                        .anyMatch(plan -> plan.ocName().equals(chain.getFirst().ocName())))
                .findFirst().orElse(chains.getFirst());
        Map<Long, OcMemberCandidate> state = new HashMap<>();
        rootPlans.memberTimeline().forEach(member -> state.put(member.userId(), member));
        List<OcPlannedAssignment> reserved = new ArrayList<>();
        List<String> warnings = new ArrayList<>(capacityResult.warnings());
        LocalDateTime[] cursors = rootCompletionTimes.toArray(LocalDateTime[]::new);
        for (int nodeIndex = 1; nodeIndex < selectedChain.size(); nodeIndex++) {
            OcTeamDemand template = selectedChain.get(nodeIndex);
            for (int rootIndex = 0; rootIndex < cursors.length; rootIndex++) {
                LocalDateTime start = cursors[rootIndex];
                OcTeamDemand demand = new OcTeamDemand(-(rootIndex + 1L), template.ocName(),
                        template.rank(), start, start.plusDays(7), true, template.slots(),
                        Set.of(), Set.of());
                OcRosterMatchResult result = rosterMatcher.match(demand,
                        new ArrayList<>(state.values()), start, mode,
                        snapshot.policy().isDifferentialWorkingHour());
                if (!result.complete() || result.completionAt().isAfter(demand.expiresAt())) {
                    warnings.add("高阶根队已可启动，但无法为后继" + template.ocName()
                            + "生成无冲突预留；已取消本次新增高阶建议");
                    return new ChainPlanningResult(new OcSafeChainCapacityResult(
                            capacityResult.capacity().committedCount(),
                            capacityResult.capacity().committedCount(), 0, false),
                            false, capacityResult.provenRootKey(), capacityResult.chainNames(),
                            capacityResult.memberTimeline(),
                            List.of(), warnings);
                }
                reserved.addAll(result.assignments());
                cursors[rootIndex] = result.completionAt();
                result.assignments().forEach(assignment -> state.computeIfPresent(
                        assignment.userId(), (ignored, member) ->
                                member.asAvailableAt(result.completionAt())));
            }
        }
        return new ChainPlanningResult(capacityResult.capacity(),
                capacityResult.committedObligationsFeasible(), capacityResult.provenRootKey(),
                capacityResult.chainNames(),
                new ArrayList<>(state.values()), reserved, warnings);
    }
}
