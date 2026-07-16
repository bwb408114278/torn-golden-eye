package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
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
 * 在旧队救援之后，为当前已刷出的空OC构建阶梯流水线。
 */
public class OcNewTeamPipelinePlanner {
    private static final int EXPIRE_DAYS = 7;
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    /**
     * 按成员时间线规划高阶根或普通空OC队伍。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @param members 当前可用成员时间线
     * @param rescuePlans 已完成的旧队补位方案
     * @param mode 规划模式
     * @param maxHighChainRoots 本次最多允许启动的高阶根数量
     * @param highRootsOnly 是否仅规划高阶根
     * @param allowedHighRootKey 容量证明允许启动的高阶根OC键；普通队规划时为空
     * @return 新队方案及执行后的成员时间线
     */
    public OcPipelinePlanningResult plan(OcPlanningSnapshot snapshot,
                                         List<OcMemberCandidate> members,
                                         List<OcTeamPlan> rescuePlans, OcPlanMode mode,
                                         int maxHighChainRoots, boolean highRootsOnly,
                                         String allowedHighRootKey) {
        Set<Long> rescueOcIds = rescuePlans.stream().map(OcTeamPlan::ocId).collect(HashSet::new,
                Set::add, Set::addAll);
        List<TornFactionOcDO> candidates = snapshot.activeOcs().stream()
                .filter(oc -> !rescueOcIds.contains(oc.getId()))
                .filter(oc -> !snapshot.invalidOcKeys().contains(
                        OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName())))
                .filter(oc -> snapshot.policy().enabledOcKeys().contains(
                        OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName())))
                .filter(oc -> snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                        .noneMatch(slot -> slot.getUserId() != null))
                .sorted(teamComparator(snapshot, mode))
                .toList();
        Map<Long, OcMemberCandidate> state = new HashMap<>();
        members.forEach(member -> state.put(member.userId(), member));
        List<OcTeamPlan> plans = new ArrayList<>();
        int startedHighRoots = 0;
        String selectedHighRoot = null;
        for (TornFactionOcDO oc : candidates) {
            TornSettingOcPlanProfileDO profile = snapshot.profiles().get(
                    OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName()));
            boolean highRoot = profile != null && "HIGH_CHAIN_ROOT".equals(profile.getSpawnPool());
            if (highRoot != highRootsOnly) {
                continue;
            }
            String ocKey = OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
            if (highRoot && allowedHighRootKey != null
                    && !allowedHighRootKey.equals(ocKey)) {
                continue;
            }
            if (highRoot && (startedHighRoots >= maxHighChainRoots
                    || selectedHighRoot != null && !selectedHighRoot.equals(oc.getName()))) {
                continue;
            }
            OcTeamPlan plan = planOne(snapshot, oc, new ArrayList<>(state.values()), mode);
            if (!plan.complete()) {
                continue;
            }
            plans.add(plan);
            if (highRoot) {
                startedHighRoots++;
                selectedHighRoot = oc.getName();
            }
            plan.assignments().forEach(assignment -> {
                OcMemberCandidate member = state.get(assignment.userId());
                state.put(assignment.userId(), member.asAvailableAt(plan.completionAt()));
            });
        }
        return new OcPipelinePlanningResult(plans, new ArrayList<>(state.values()));
    }

    private OcTeamPlan planOne(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                               List<OcMemberCandidate> members, OcPlanMode mode) {
        String ocKey = OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
        TornSettingOcPlanProfileDO profile = snapshot.profiles().get(ocKey);
        if (profile == null || !"READY".equals(profile.getPlanStatus())) {
            return unavailablePlan(oc, profile, "配置未达到READY状态");
        }
        List<OcPlanSlot> slots = snapshot.slotTemplates().getOrDefault(ocKey, List.of());
        if (slots.isEmpty()) {
            return unavailablePlan(oc, profile, "缺少岗位模板");
        }
        LocalDateTime expiresAt = oc.getCreateTime() == null
                ? snapshot.snapshotTime().plusDays(EXPIRE_DAYS)
                : oc.getCreateTime().plusDays(EXPIRE_DAYS);
        OcTeamDemand demand = new OcTeamDemand(oc.getId(), oc.getName(), oc.getRank(),
                snapshot.snapshotTime(), expiresAt, oc.getPreviousOcId() != null, slots,
                Set.of(), Set.of());
        OcRosterMatchResult result = rosterMatcher.match(demand, members, snapshot.snapshotTime(),
                mode, snapshot.policy().isDifferentialWorkingHour());
        boolean complete = result.complete() && !result.completionAt().isAfter(expiresAt);
        long rewardFloor = profile.getRewardFloor() == null ? 0L : profile.getRewardFloor();
        long occupiedHours = complete ? result.assignments().stream()
                .mapToLong(assignment -> java.time.Duration.between(assignment.joinAt(),
                        result.completionAt()).toHours()).sum() : 0L;
        String note = complete
                ? "阶梯加入预计占用" + occupiedHours / 24.0 + "人天"
                : "过期前无法形成完整阵容，暂不投入人员";
        return new OcTeamPlan(oc.getId(), oc.getName(), oc.getRank(), false, complete,
                complete ? result.completionAt() : null,
                complete ? result.assignments() : List.of(), result.missingSlots(),
                complete ? score(profile, occupiedHours, mode) : Long.MIN_VALUE,
                rewardFloor, note);
    }

    private Comparator<TornFactionOcDO> teamComparator(OcPlanningSnapshot snapshot, OcPlanMode mode) {
        return Comparator.comparingLong((TornFactionOcDO oc) -> {
                    TornSettingOcPlanProfileDO profile = snapshot.profiles()
                            .get(OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName()));
                    long reward = profile == null || profile.getRewardFloor() == null
                            ? 0L : profile.getRewardFloor();
                    long rankValue = (long) oc.getRank() * 1_000_000_000L;
                    return mode == OcPlanMode.CONSERVATIVE ? -oc.getRank() : -(rankValue + reward);
                }).thenComparing(TornFactionOcDO::getCreateTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(TornFactionOcDO::getId);
    }

    private long score(TornSettingOcPlanProfileDO profile, long occupiedHours, OcPlanMode mode) {
        long reward = profile.getRewardFloor() == null ? 0L : profile.getRewardFloor();
        long productivity = occupiedHours <= 0 ? reward : reward * 24L / occupiedHours;
        return switch (mode) {
            case CONSERVATIVE -> -occupiedHours;
            case BALANCED -> productivity;
            case PROFIT -> (long) profile.getRank() * 1_000_000_000L + reward;
        };
    }

    private OcTeamPlan unavailablePlan(TornFactionOcDO oc, TornSettingOcPlanProfileDO profile,
                                       String note) {
        long rewardFloor = profile == null || profile.getRewardFloor() == null
                ? 0L : profile.getRewardFloor();
        return new OcTeamPlan(oc.getId(), oc.getName(), oc.getRank(), false, false, null,
                List.of(), List.of(), Long.MIN_VALUE, rewardFloor, note);
    }
}
