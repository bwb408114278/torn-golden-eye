package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.util.List;

/**
 * 高阶链容量与实际根队/后继预留结果。
 */
public record ChainPlanningResult(OcSafeChainCapacityResult capacity,
                                  boolean committedObligationsFeasible,
                                  String provenRootKey,
                                  List<String> chainNames,
                                  List<OcMemberCandidate> memberTimeline,
                                  List<OcPlannedAssignment> reservedAssignments,
                                  List<String> warnings) {
    public ChainPlanningResult {
        chainNames = List.copyOf(chainNames);
        memberTimeline = List.copyOf(memberTimeline);
        reservedAssignments = List.copyOf(reservedAssignments);
        warnings = List.copyOf(warnings);
    }
}
