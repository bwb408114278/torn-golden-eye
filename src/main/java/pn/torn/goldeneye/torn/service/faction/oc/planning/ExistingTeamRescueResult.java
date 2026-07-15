package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.util.List;

/**
 * 旧队救援后的计划及成员时间线。
 */
public record ExistingTeamRescueResult(List<OcTeamPlan> plans,
                                       List<OcMemberCandidate> memberTimeline) {
    public ExistingTeamRescueResult {
        plans = List.copyOf(plans);
        memberTimeline = List.copyOf(memberTimeline);
    }
}
