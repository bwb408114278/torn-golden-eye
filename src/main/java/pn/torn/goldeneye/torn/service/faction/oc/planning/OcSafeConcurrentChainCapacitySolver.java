package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 证明当前能力矩阵最多能够安全承载多少条并行高阶链。
 *
 * <p>已承诺根只从后继节点开始模拟；新增链从根节点开始完整模拟。
 * 同一时段不会复用成员，每增加一条链都必须完整走通所有已知节点。</p>
 */
public class OcSafeConcurrentChainCapacitySolver {
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    public OcChainCapacityPlanningResult calculate(List<OcTeamDemand> chainNodes,
                                                    List<OcMemberCandidate> members,
                                                    List<CommittedChainObligation> committedObligations,
                                                    int searchUpperBound,
                                                    LocalDateTime planningTime) {
        List<OcMemberCandidate> committedTimeline = copyMembers(members);
        List<OcPlannedAssignment> reservedAssignments = new ArrayList<>();
        List<CommittedChainObligation> orderedObligations = committedObligations.stream()
                .sorted(java.util.Comparator.comparing(
                                CommittedChainObligation::successorAvailableAt)
                        .thenComparingLong(CommittedChainObligation::rootOcId))
                .toList();
        for (CommittedChainObligation obligation : orderedObligations) {
            ChainSimulation simulation = simulateChain(obligation.chain(), obligation.nextNodeIndex(),
                    committedTimeline, obligation.successorAvailableAt());
            if (!simulation.complete()) {
                return new OcChainCapacityPlanningResult(
                        new OcSafeChainCapacityResult(committedObligations.size(),
                                0, 0, false),
                        false,
                        members, List.of());
            }
            committedTimeline = simulation.members();
            reservedAssignments.addAll(simulation.assignments());
        }

        if (chainNodes == null || chainNodes.isEmpty()) {
            return new OcChainCapacityPlanningResult(
                    new OcSafeChainCapacityResult(committedObligations.size(),
                            committedObligations.size(), 0, true),
                    true,
                    committedTimeline, reservedAssignments);
        }

        int provenAdditional = 0;
        boolean maximumProven = false;
        int upperBound = Math.max(0, searchUpperBound);
        for (int additionalCount = 1; additionalCount <= upperBound; additionalCount++) {
            if (!canRunNewChains(chainNodes, committedTimeline, additionalCount, planningTime)) {
                maximumProven = true;
                break;
            }
            provenAdditional = additionalCount;
        }
        int safeConcurrent = committedObligations.size() + provenAdditional;
        return new OcChainCapacityPlanningResult(
                new OcSafeChainCapacityResult(committedObligations.size(), safeConcurrent,
                        provenAdditional, maximumProven),
                true,
                committedTimeline, reservedAssignments);
    }

    private boolean canRunNewChains(List<OcTeamDemand> chainNodes,
                                    List<OcMemberCandidate> baseMembers,
                                    int chainCount,
                                    LocalDateTime planningTime) {
        List<OcMemberCandidate> state = copyMembers(baseMembers);
        for (int chainIndex = 0; chainIndex < chainCount; chainIndex++) {
            ChainSimulation simulation = simulateChain(chainNodes, 0, state, planningTime);
            if (!simulation.complete()) {
                return false;
            }
            state = simulation.members();
        }
        return true;
    }

    private ChainSimulation simulateChain(List<OcTeamDemand> chainNodes,
                                           int startNodeIndex,
                                           List<OcMemberCandidate> baseMembers,
                                           LocalDateTime startAt) {
        List<OcMemberCandidate> state = copyMembers(baseMembers);
        List<OcPlannedAssignment> assignments = new ArrayList<>();
        LocalDateTime nodeStart = startAt;
        for (int nodeIndex = startNodeIndex; nodeIndex < chainNodes.size(); nodeIndex++) {
            OcTeamDemand template = chainNodes.get(nodeIndex);
            OcTeamDemand node = new OcTeamDemand(template.ocId(), template.ocName(), template.rank(),
                    nodeStart, nodeStart.plusDays(7), template.chain(), template.slots(),
                    template.fixedSlotCodes(), template.fixedMemberIds());
            OcRosterMatchResult match = rosterMatcher.match(node, state, nodeStart,
                    OcPlanMode.CONSERVATIVE, false);
            if (!match.complete()) {
                return new ChainSimulation(false, baseMembers, List.of());
            }
            LocalDateTime completionAt = match.completionAt();
            assignments.addAll(match.assignments());
            state = updateAssignedMembers(state, match.assignments(), completionAt);
            nodeStart = completionAt;
        }
        return new ChainSimulation(true, state, assignments);
    }

    private List<OcMemberCandidate> updateAssignedMembers(List<OcMemberCandidate> members,
                                                           List<OcPlannedAssignment> assignments,
                                                           LocalDateTime completionAt) {
        Map<Long, OcMemberCandidate> state = new HashMap<>();
        members.forEach(member -> state.put(member.userId(), member));
        assignments.forEach(assignment -> state.computeIfPresent(assignment.userId(),
                (ignored, member) -> member.asAvailableAt(completionAt)));
        return new ArrayList<>(state.values());
    }

    private List<OcMemberCandidate> copyMembers(List<OcMemberCandidate> members) {
        return new ArrayList<>(members);
    }

    private record ChainSimulation(boolean complete,
                                   List<OcMemberCandidate> members,
                                   List<OcPlannedAssignment> assignments) {
    }
}
