package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一支OC的人员岗位匹配门面。
 *
 * <p>统一处理空缺岗位和候选成员准备，并将普通最小费用流匹配与无停转联合搜索
 * 分派给各自的专用匹配器。</p>
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.17
 */
public class OcRosterMatcher {
    private final OcFlowRosterMatcher flowMatcher;
    private final OcNoPauseRosterMatcher noPauseMatcher;

    /**
     * 创建组合普通匹配与无停转匹配算法的门面。
     */
    public OcRosterMatcher() {
        this.flowMatcher = new OcFlowRosterMatcher();
        this.noPauseMatcher = new OcNoPauseRosterMatcher();
    }

    /**
     * 使用均衡模式为队伍需求匹配完整阵容。
     *
     * @param demand 队伍岗位需求
     * @param candidates 当前成员时间线中的候选成员
     * @param planningTime 规划基准时间
     * @return 完整匹配结果；无法补齐时返回缺失岗位
     */
    public OcRosterMatchResult match(OcTeamDemand demand, List<OcMemberCandidate> candidates,
                                     LocalDateTime planningTime) {
        return match(demand, candidates, planningTime, OcPlanMode.BALANCED, false);
    }

    /**
     * 按指定规划模式和评价策略为队伍需求匹配完整阵容。
     *
     * @param demand 队伍岗位需求
     * @param candidates 当前成员时间线中的候选成员
     * @param planningTime 规划基准时间
     * @param mode 规划模式
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 完整匹配结果；无法补齐时返回缺失岗位
     */
    public OcRosterMatchResult match(OcTeamDemand demand, List<OcMemberCandidate> candidates,
                                     LocalDateTime planningTime, OcPlanMode mode,
                                     boolean differentialWorkingHour) {
        return matchWithFlow(demand, candidates, planningTime, mode,
                differentialWorkingHour, false);
    }

    /**
     * 使用确定性岗位顺序匹配完整阵容，供安全边界批量证明使用。
     *
     * @param demand 队伍岗位需求
     * @param candidates 当前成员时间线中的候选成员
     * @param planningTime 规划基准时间
     * @return 完整匹配结果；失败结果只表示当前确定性排程未证明可行
     */
    public OcRosterMatchResult matchDeterministic(OcTeamDemand demand,
                                                  List<OcMemberCandidate> candidates,
                                                  LocalDateTime planningTime) {
        return matchWithFlow(demand, candidates, planningTime,
                OcPlanMode.BALANCED, false, true);
    }

    /**
     * 使用联合搜索匹配不产生新增停转的完整阵容。
     *
     * @param demand 队伍岗位需求
     * @param candidates 当前成员时间线中的候选成员
     * @param planningTime 规划基准时间
     * @return 无新增停转的完整匹配结果；搜索预算内未证明可行时返回失败
     */
    public OcRosterMatchResult matchWithoutPause(OcTeamDemand demand,
                                                 List<OcMemberCandidate> candidates,
                                                 LocalDateTime planningTime) {
        RosterMatchPreparation preparation = prepareRosterMatch(
                demand, candidates, planningTime);
        if (preparation.immediateResult().isPresent()) {
            return preparation.immediateResult().orElseThrow();
        }
        List<OcPlannedAssignment> schedule = noPauseMatcher.match(
                demand, preparation.vacantSlots(), preparation.usableMembers(), planningTime);
        return schedule.isEmpty()
                ? missingAllVacantSlots(preparation.vacantSlots())
                : OcRosterMatchResult.success(schedule, schedule.getLast().stageCompleteAt());
    }

    /**
     * 执行普通最小费用流匹配。
     *
     * @param demand 队伍岗位需求
     * @param candidates 候选成员
     * @param planningTime 规划基准时间
     * @param mode 规划模式
     * @param differentialWorkingHour 是否使用差异工时评价
     * @param deterministicSchedule 是否使用确定性岗位顺序
     * @return 完整匹配结果
     */
    private OcRosterMatchResult matchWithFlow(OcTeamDemand demand,
                                              List<OcMemberCandidate> candidates,
                                              LocalDateTime planningTime,
                                              OcPlanMode mode,
                                              boolean differentialWorkingHour,
                                              boolean deterministicSchedule) {
        RosterMatchPreparation preparation = prepareRosterMatch(
                demand, candidates, planningTime);
        if (preparation.immediateResult().isPresent()) {
            return preparation.immediateResult().orElseThrow();
        }
        return flowMatcher.match(demand, preparation.vacantSlots(),
                preparation.usableMembers(), planningTime, mode,
                differentialWorkingHour, deterministicSchedule);
    }

    /**
     * 准备岗位匹配所需的空缺岗位和可用成员，并统一处理可提前结束的场景。
     *
     * @param demand 队伍需求
     * @param candidates 原始候选成员
     * @param planningTime 规划基准时间
     * @return 匹配准备结果；无需继续匹配时包含可直接返回的结果
     */
    private RosterMatchPreparation prepareRosterMatch(OcTeamDemand demand,
                                                       List<OcMemberCandidate> candidates,
                                                       LocalDateTime planningTime) {
        List<OcPlanSlot> vacantSlots = demand.getVacantSlots();
        if (vacantSlots.isEmpty()) {
            return RosterMatchPreparation.completed(completedDemandResult(demand, planningTime));
        }
        List<OcMemberCandidate> usableMembers = usableMembers(demand, candidates);
        if (usableMembers.size() < vacantSlots.size()) {
            return RosterMatchPreparation.completed(missingAllVacantSlots(vacantSlots));
        }
        return RosterMatchPreparation.ready(vacantSlots, usableMembers);
    }

    /**
     * 构造全部空缺岗位均未匹配的失败结果。
     *
     * @param vacantSlots 空缺岗位
     * @return 包含全部空缺岗位编码的失败结果
     */
    private OcRosterMatchResult missingAllVacantSlots(List<OcPlanSlot> vacantSlots) {
        return OcRosterMatchResult.failure(vacantSlots.stream().map(OcPlanSlot::code).toList());
    }

    /**
     * 返回无需补位的既有队伍结果。
     *
     * @param demand 队伍需求
     * @param planningTime 规划基准时间
     * @return 已完成需求的匹配结果
     */
    private OcRosterMatchResult completedDemandResult(OcTeamDemand demand,
                                                       LocalDateTime planningTime) {
        if (!demand.fixedMemberIds().isEmpty() && demand.readyAt() == null) {
            return OcRosterMatchResult.failure(List.of("readyTime"));
        }
        LocalDateTime completionAt = demand.readyAt() == null
                ? planningTime : demand.readyAt();
        return OcRosterMatchResult.success(List.of(), completionAt);
    }

    /**
     * 筛选未被既有固定岗位占用的候选成员。
     *
     * @param demand 队伍需求
     * @param candidates 原始候选成员
     * @return 按释放时间和用户ID排序的可用成员
     */
    private List<OcMemberCandidate> usableMembers(OcTeamDemand demand,
                                                   List<OcMemberCandidate> candidates) {
        return candidates.stream()
                .filter(candidate -> !candidate.fixed())
                .filter(candidate -> !demand.fixedMemberIds().contains(candidate.userId()))
                .sorted(Comparator.comparing(OcMemberCandidate::availableAt)
                        .thenComparingLong(OcMemberCandidate::userId))
                .toList();
    }

    /**
     * 岗位匹配的统一前置准备结果。
     *
     * @param vacantSlots 待匹配的空缺岗位
     * @param usableMembers 未被既有固定岗位占用的可用成员
     * @param immediateResult 无需继续匹配时可直接返回的结果
     */
    private record RosterMatchPreparation(List<OcPlanSlot> vacantSlots,
                                          List<OcMemberCandidate> usableMembers,
                                          Optional<OcRosterMatchResult> immediateResult) {

        /**
         * 创建可继续执行匹配的准备结果。
         *
         * @param vacantSlots 待匹配的空缺岗位
         * @param usableMembers 可用成员
         * @return 不包含提前返回结果的准备结果
         */
        private static RosterMatchPreparation ready(List<OcPlanSlot> vacantSlots,
                                                    List<OcMemberCandidate> usableMembers) {
            return new RosterMatchPreparation(vacantSlots, usableMembers, Optional.empty());
        }

        /**
         * 创建无需继续匹配的准备结果。
         *
         * @param immediateResult 可直接返回的匹配结果
         * @return 包含提前返回结果的准备结果
         */
        private static RosterMatchPreparation completed(OcRosterMatchResult immediateResult) {
            return new RosterMatchPreparation(List.of(), List.of(), Optional.of(immediateResult));
        }
    }
}
