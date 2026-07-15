package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧队救援规划器。旧队已有成员和岗位保持固定，只给空位补人。
 */
public class ExistingTeamRescuePlanner {
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    public ExistingTeamRescueResult plan(OcPlanningSnapshot snapshot, OcPlanMode mode) {
        List<TornFactionOcDO> teams = snapshot.activeOcs().stream()
                .filter(oc -> snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                        .anyMatch(slot -> slot.getUserId() != null))
                .filter(oc -> !snapshot.invalidOcKeys().contains(
                        OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName())))
                .filter(oc -> snapshot.policy().enabledOcKeys().contains(
                        OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName())))
                .sorted(teamPriority(snapshot))
                .toList();
        Map<Long, OcMemberCandidate> state = new HashMap<>();
        snapshot.members().forEach(member -> state.put(member.userId(), member));
        Set<Long> lockedByUnresolvedTeam = allOccupiedUsers(snapshot);
        List<OcTeamPlan> plans = new ArrayList<>();
        for (TornFactionOcDO team : teams) {
            Set<Long> ownMembers = currentMemberIds(snapshot, team.getId());
            List<OcMemberCandidate> candidates = state.values().stream()
                    .map(member -> lockedByUnresolvedTeam.contains(member.userId())
                            ? member.asFixed() : member)
                    .toList();
            OcTeamPlan plan = planTeam(snapshot, team, mode, candidates, ownMembers);
            if (!plan.missingSlots().isEmpty() || !plan.assignments().isEmpty()
                    || plan.note().contains("OBSERVE_ONLY")) {
                plans.add(plan);
            }
            if (!plan.complete() || plan.completionAt() == null) {
                continue;
            }
            Set<Long> participants = new HashSet<>(ownMembers);
            plan.assignments().stream().map(OcPlannedAssignment::userId).forEach(participants::add);
            participants.forEach(userId -> {
                OcMemberCandidate member = state.get(userId);
                if (member != null) {
                    state.put(userId, member.asAvailableAt(plan.completionAt()));
                }
            });
            lockedByUnresolvedTeam.removeAll(ownMembers);
        }
        lockedByUnresolvedTeam.forEach(userId -> {
            OcMemberCandidate member = state.get(userId);
            if (member != null) {
                state.put(userId, member.asFixed());
            }
        });
        return new ExistingTeamRescueResult(plans, new ArrayList<>(state.values()));
    }

    private Comparator<TornFactionOcDO> teamPriority(OcPlanningSnapshot snapshot) {
        return Comparator.comparingInt((TornFactionOcDO oc) ->
                        snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                                .map(TornFactionOcSlotDO::getUserId)
                                .filter(java.util.Objects::nonNull).toList().size())
                .reversed().thenComparing(TornFactionOcDO::getReadyTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(TornFactionOcDO::getId);
    }

    private OcTeamPlan planTeam(OcPlanningSnapshot snapshot, TornFactionOcDO oc, OcPlanMode mode,
                                List<OcMemberCandidate> candidates, Set<Long> ownMembers) {
        TornSettingOcPlanProfileDO profile = snapshot.profiles()
                .get(OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName()));
        if (profile == null || !"READY".equals(profile.getPlanStatus())) {
            return observeOnlyPlan(oc, profile);
        }
        List<TornFactionOcSlotDO> currentSlots = snapshot.slotsByOcId()
                .getOrDefault(oc.getId(), List.of());
        Set<String> fixedSlotCodes = new HashSet<>();
        currentSlots.stream().filter(slot -> slot.getUserId() != null)
                .map(TornFactionOcSlotDO::getPosition).forEach(fixedSlotCodes::add);
        List<OcPlanSlot> templateSlots = snapshot.slotTemplates()
                .getOrDefault(OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName()), List.of());
        if (templateSlots.size() <= fixedSlotCodes.size()) {
            return new OcTeamPlan(oc.getId(), oc.getName(), oc.getRank(), true, true,
                    oc.getReadyTime(), List.of(), List.of(), 0L,
                    profile.getRewardFloor() == null ? 0L : profile.getRewardFloor(),
                    "队伍岗位已满，无需补位");
        }
        // 已经有成员加入的旧队不再受“无人加入7天过期”约束。
        LocalDateTime expiresAt = snapshot.snapshotTime().plusYears(10);
        OcTeamDemand demand = new OcTeamDemand(oc.getId(), oc.getName(), oc.getRank(),
                oc.getReadyTime(), expiresAt, oc.getPreviousOcId() != null, templateSlots,
                fixedSlotCodes, ownMembers);
        OcRosterMatchResult result = rosterMatcher.match(demand, candidates, snapshot.snapshotTime(),
                mode, snapshot.policy().isDifferentialWorkingHour());
        boolean beforeExpiry = result.complete() && result.completionAt() != null
                && !result.completionAt().isAfter(expiresAt);
        long unlockScore = ownMembers.size() * 1000L
                - demand.getVacantSlots().size() * 100L - (beforeExpiry ? 0L : 10000L);
        long rewardFloor = profile.getRewardFloor() == null ? 0L : profile.getRewardFloor();
        String note = beforeExpiry
                ? "补齐后可释放" + (ownMembers.size() + result.assignments().size()) + "人"
                : "在过期前不存在完整补齐路径，不建议继续投入人员";
        return new OcTeamPlan(oc.getId(), oc.getName(), oc.getRank(), true, beforeExpiry,
                beforeExpiry ? result.completionAt() : null,
                beforeExpiry ? result.assignments() : List.of(), result.missingSlots(), unlockScore,
                rewardFloor, note);
    }

    private Set<Long> allOccupiedUsers(OcPlanningSnapshot snapshot) {
        Set<Long> result = new HashSet<>();
        snapshot.slotsByOcId().values().stream().flatMap(List::stream)
                .map(TornFactionOcSlotDO::getUserId).filter(java.util.Objects::nonNull)
                .forEach(result::add);
        return result;
    }

    private Set<Long> currentMemberIds(OcPlanningSnapshot snapshot, long ocId) {
        Set<Long> result = new HashSet<>();
        snapshot.slotsByOcId().getOrDefault(ocId, List.of()).stream()
                .map(TornFactionOcSlotDO::getUserId).filter(java.util.Objects::nonNull)
                .forEach(result::add);
        return result;
    }

    private OcTeamPlan observeOnlyPlan(TornFactionOcDO oc, TornSettingOcPlanProfileDO profile) {
        long rewardFloor = profile == null || profile.getRewardFloor() == null
                ? 0L : profile.getRewardFloor();
        return new OcTeamPlan(oc.getId(), oc.getName(), oc.getRank(), true, false, null,
                List.of(), List.of(), 0L, rewardFloor,
                "配置为OBSERVE_ONLY或资料不完整，旧队保持不动并交由人工处理");
    }
}
