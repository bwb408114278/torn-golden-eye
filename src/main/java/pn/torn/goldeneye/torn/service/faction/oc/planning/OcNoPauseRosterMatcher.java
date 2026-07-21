package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OC无停转阵容搜索器。
 *
 * <p>联合搜索成员、岗位和逐阶段准备时间，只有完整排程能在每个阶段截止前加入时才返回成功。
 * 搜索失败表示在有限节点预算内未证明存在无停转排程。</p>
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.17
 */
final class OcNoPauseRosterMatcher {
    private static final int MAX_SEARCH_NODES = 20_000;

    /**
     * 搜索满足逐阶段截止时间且不产生停转的完整排程。
     *
     * @param demand 队伍需求
     * @param vacantSlots 空缺岗位
     * @param candidates 可用成员
     * @param planningTime 规划基准时间
     * @return 已证明可行的完整排程；搜索失败时返回空集合
     */
    List<OcPlannedAssignment> match(
            OcTeamDemand demand, List<OcPlanSlot> vacantSlots,
            List<OcMemberCandidate> candidates, LocalDateTime planningTime) {
        if (!demand.fixedMemberIds().isEmpty() && demand.readyAt() == null) {
            return List.of();
        }
        List<OcPlannedAssignment> assignments = new ArrayList<>(vacantSlots.size());
        boolean found = searchNoPause(demand, new ArrayList<>(vacantSlots),
                new ArrayList<>(candidates), planningTime, demand.readyAt(),
                assignments, new SearchBudget(MAX_SEARCH_NODES));
        return found ? List.copyOf(assignments) : List.of();
    }

    /**
     * 递归联合搜索当前阶段可加入的成员与岗位。
     *
     * @param demand 队伍需求
     * @param remainingSlots 剩余岗位
     * @param remainingMembers 剩余成员
     * @param planningTime 规划基准时间
     * @param currentReadyTime 当前下一阶段时间
     * @param assignments 当前排程
     * @param budget 搜索节点预算
     * @return 找到完整无停转排程时返回true
     */
    private static boolean searchNoPause(OcTeamDemand demand,
                                         List<OcPlanSlot> remainingSlots,
                                         List<OcMemberCandidate> remainingMembers,
                                         LocalDateTime planningTime,
                                         LocalDateTime currentReadyTime,
                                         List<OcPlannedAssignment> assignments,
                                         SearchBudget budget) {
        if (remainingSlots.isEmpty()) {
            return true;
        }
        if (!budget.tryVisit()) {
            return false;
        }
        LocalDateTime deadline = noPauseDeadline(currentReadyTime, planningTime);
        List<NoPauseCandidate> candidates = noPauseCandidates(
                demand, remainingSlots, remainingMembers, deadline);
        for (NoPauseCandidate candidate : candidates) {
            LocalDateTime joinAt = planningTime.isAfter(candidate.member().availableAt())
                    ? planningTime : candidate.member().availableAt();
            if (isFirstMemberAfterExpiry(demand, currentReadyTime, joinAt)) {
                continue;
            }
            LocalDateTime nextReadyTime = OcPreparationTimeCalculator.nextReadyTime(
                    currentReadyTime, joinAt);
            assignments.add(toAssignment(demand, candidate, joinAt, nextReadyTime));
            List<OcPlanSlot> nextSlots = new ArrayList<>(remainingSlots);
            nextSlots.remove(candidate.slot());
            List<OcMemberCandidate> nextMembers = new ArrayList<>(remainingMembers);
            nextMembers.remove(candidate.member());
            if (searchNoPause(demand, nextSlots, nextMembers, planningTime,
                    nextReadyTime, assignments, budget)) {
                return true;
            }
            assignments.removeLast();
        }
        return false;
    }

    /**
     * 判断空OC首位成员是否错过首次加入期限。
     *
     * @param demand 队伍需求
     * @param currentReadyTime 当前下一阶段时间
     * @param joinAt 候选成员实际加入时间
     * @return 尚无准备阶段且加入时间晚于期限时返回true
     */
    private static boolean isFirstMemberAfterExpiry(OcTeamDemand demand,
                                                    LocalDateTime currentReadyTime,
                                                    LocalDateTime joinAt) {
        return currentReadyTime == null && demand.expiresAt() != null
                && joinAt.isAfter(demand.expiresAt());
    }

    /**
     * 枚举当前阶段截止前可加入的合格成员岗位组合。
     *
     * @param demand 队伍需求
     * @param slots 剩余岗位
     * @param members 剩余成员
     * @param deadline 当前阶段最晚加入时间
     * @return 按岗位稀缺性、成员释放时间和能力覆盖排序的候选组合
     */
    private static List<NoPauseCandidate> noPauseCandidates(OcTeamDemand demand,
                                                      List<OcPlanSlot> slots,
                                                      List<OcMemberCandidate> members,
                                                      LocalDateTime deadline) {
        Map<String, Long> eligibleCounts = new HashMap<>();
        for (OcPlanSlot slot : slots) {
            long count = members.stream()
                    .filter(member -> !member.availableAt().isAfter(deadline))
                    .filter(member -> isQualified(demand, slot, member))
                    .count();
            eligibleCounts.put(slot.code(), count);
        }
        List<NoPauseCandidate> result = new ArrayList<>();
        for (OcPlanSlot slot : slots) {
            for (OcMemberCandidate member : members) {
                if (!member.availableAt().isAfter(deadline)
                        && isQualified(demand, slot, member)) {
                    result.add(new NoPauseCandidate(member, slot,
                            member.getPassRate(demand.rank(), demand.ocName(), slot.position()),
                            eligibleCounts.get(slot.code())));
                }
            }
        }
        result.sort(Comparator.comparingLong(NoPauseCandidate::eligibleMemberCount)
                .thenComparing(candidate -> candidate.member().availableAt())
                .thenComparingInt(candidate -> candidate.member().getCapabilityCount())
                .thenComparingInt(candidate -> candidate.slot().priority())
                .thenComparingLong(candidate -> candidate.member().userId()));
        return result;
    }

    /**
     * 判断成员是否满足指定岗位通过率要求。
     *
     * @param demand 队伍需求
     * @param slot 岗位需求
     * @param member 候选成员
     * @return 满足岗位要求时返回true
     */
    private static boolean isQualified(OcTeamDemand demand, OcPlanSlot slot,
                                OcMemberCandidate member) {
        return member.getPassRate(demand.rank(), demand.ocName(), slot.position())
                >= slot.requiredPassRate();
    }

    /**
     * 计算当前阶段最晚加入时间。
     *
     * @param currentReadyTime 当前下一阶段时间
     * @param planningTime 规划基准时间
     * @return 当前阶段截止时间；空OC或已停转OC要求当前加入
     */
    private static LocalDateTime noPauseDeadline(LocalDateTime currentReadyTime,
                                          LocalDateTime planningTime) {
        return currentReadyTime == null || currentReadyTime.isBefore(planningTime)
                ? planningTime : currentReadyTime;
    }

    /**
     * 将无停转候选转换为成员岗位安排。
     *
     * @param demand 队伍需求
     * @param candidate 成员岗位候选
     * @param joinAt 实际加入时间
     * @param completeAt 当前岗位准备完成时间
     * @return 成员岗位安排
     */
    private static OcPlannedAssignment toAssignment(OcTeamDemand demand,
                                              NoPauseCandidate candidate,
                                              LocalDateTime joinAt,
                                              LocalDateTime completeAt) {
        OcMemberCandidate member = candidate.member();
        OcPlanSlot slot = candidate.slot();
        BigDecimal coefficient = member.getCoefficient(
                demand.rank(), demand.ocName(), slot.code());
        return new OcPlannedAssignment(member.userId(), member.nickname(), slot.code(),
                candidate.passRate(), slot.requiredPassRate(), joinAt, completeAt, coefficient);
    }

    /**
     * 无停转联合搜索中的成员岗位候选。
     *
     * @param member 候选成员
     * @param slot 候选岗位
     * @param passRate 岗位通过率
     * @param eligibleMemberCount 当前阶段可承担该岗位的成员数量
     */
    private record NoPauseCandidate(OcMemberCandidate member,
                                    OcPlanSlot slot,
                                    int passRate,
                                    long eligibleMemberCount) {
    }

    /**
     * 无停转联合搜索的节点预算。
     */
    private static final class SearchBudget {
        private int remaining;

        /**
         * 创建搜索预算。
         *
         * @param maxNodes 最大搜索节点数
         */
        private SearchBudget(int maxNodes) {
            this.remaining = maxNodes;
        }

        /**
         * 尝试消费一个搜索节点。
         *
         * @return 尚有预算时返回true
         */
        private boolean tryVisit() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}
