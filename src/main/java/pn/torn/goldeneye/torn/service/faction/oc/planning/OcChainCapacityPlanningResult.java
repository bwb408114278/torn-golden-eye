package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;

import java.util.List;

/**
 * 高阶链容量证明及已承诺后继预留后的成员时间线。
 */
public record OcChainCapacityPlanningResult(OcSafeChainCapacityResult capacity,
                                            boolean committedObligationsFeasible,
                                            List<OcMemberCandidate> memberTimeline,
                                            List<OcPlannedAssignment> reservedAssignments) {
    public OcChainCapacityPlanningResult {
        memberTimeline = List.copyOf(memberTimeline);
        reservedAssignments = List.copyOf(reservedAssignments);
    }
}
