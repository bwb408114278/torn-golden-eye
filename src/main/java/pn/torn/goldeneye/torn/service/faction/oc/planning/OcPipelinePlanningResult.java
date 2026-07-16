package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.util.List;

/**
 * 一段新队流水线及其执行后的成员时间线。
 *
 * @param plans 流水线生成的队伍方案
 * @param memberTimeline 执行这些方案后的成员时间线
 */public record OcPipelinePlanningResult(List<OcTeamPlan> plans,
                                       List<OcMemberCandidate> memberTimeline) {
    public OcPipelinePlanningResult {
        plans = List.copyOf(plans);
        memberTimeline = List.copyOf(memberTimeline);
    }
}
