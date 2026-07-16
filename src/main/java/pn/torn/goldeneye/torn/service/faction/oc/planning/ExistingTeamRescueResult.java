package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.util.List;

/**
 * 旧队救援后的计划及成员时间线。
 *
 * @param plans 需要展示或人工处理的旧队补位方案
 * @param memberTimeline 完成旧队占用与补位后的成员时间线
 */public record ExistingTeamRescueResult(List<OcTeamPlan> plans,
                                       List<OcMemberCandidate> memberTimeline) {
    public ExistingTeamRescueResult {
        plans = List.copyOf(plans);
        memberTimeline = List.copyOf(memberTimeline);
    }
}
