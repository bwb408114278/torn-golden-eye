package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;

import java.util.List;

/**
 * 高阶链容量证明及已承诺后继预留后的成员时间线。
 *
 * @param capacity 高阶链安全容量证明
 * @param committedObligationsFeasible 已承诺后继是否全部可履约
 * @param memberTimeline 预留已承诺后继后的成员时间线
 * @param reservedAssignments 已承诺后继的岗位预留明细
 */public record OcChainCapacityPlanningResult(OcSafeChainCapacityResult capacity,
                                            boolean committedObligationsFeasible,
                                            List<OcMemberCandidate> memberTimeline,
                                            List<OcPlannedAssignment> reservedAssignments) {
    public OcChainCapacityPlanningResult {
        memberTimeline = List.copyOf(memberTimeline);
        reservedAssignments = List.copyOf(reservedAssignments);
    }
}
