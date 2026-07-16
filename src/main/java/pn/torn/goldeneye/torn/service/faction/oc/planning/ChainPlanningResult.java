package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.util.List;

/**
 * 高阶链容量与实际根队/后继预留结果。
 *
 * @param capacity 高阶链安全容量证明
 * @param committedObligationsFeasible 已承诺高阶链后继是否全部可履约
 * @param provenRootKey 容量证明对应的高阶根OC键
 * @param chainNames 参与规划的高阶链名称
 * @param memberTimeline 预留已承诺后继后的成员时间线
 * @param reservedAssignments 已承诺后继的岗位预留明细
 * @param warnings 高阶链规划警告
 */public record ChainPlanningResult(OcSafeChainCapacityResult capacity,
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
