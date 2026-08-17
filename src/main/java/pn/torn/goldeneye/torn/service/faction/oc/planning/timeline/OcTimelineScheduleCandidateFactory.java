package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcPreparationTimeCalculator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcRosterMatchResult;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcRosterMatcher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 时间线候选方案工厂。负责基础匹配、替代匹配、阶段排序和候选签名，
 * 由时间线事件推进器显式构造。每个分支与义务组合只执行一次基础匹配，
 * 该不可变结果供加入排序和替代生成复用。纯内存对象，不访问数据库、HTTP或Redis。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
final class OcTimelineScheduleCandidateFactory {
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    /**
     * 创建时间线候选方案工厂。
     */
    OcTimelineScheduleCandidateFactory() {
    }

    /**
     * 义务的完整阶段时间线。
     *
     * @param assignments  按加入顺序排列的岗位安排
     * @param joinTimes    与安排对应的实际加入时间
     * @param completionAt 最终完成时间
     */
    record StageSchedule(
            List<OcPlannedAssignment> assignments,
            List<LocalDateTime> joinTimes,
            LocalDateTime completionAt) {
    }

    /**
     * 生成当前义务的候选完整方案：单个基础完整匹配的两种加入排序加有界的替代成员—岗位匹配。
     *
     * <p>基础最小费用流匹配只执行一次，其不可变结果同时供
     * 两种加入排序和替代匹配生成复用；替代候选数达到技术预算上限前停止生成。</p>
     *
     * @param state        当前时间线状态
     * @param obligation   待排程义务
     * @param allowedPause 单次主动新增停转上限
     * @param request      求解请求
     * @param progress     搜索进度与预算命中标记
     * @return 去重后的候选方案；全部不可行时为空
     */
    List<StageSchedule> candidateSchedules(OcTimelineState state,
                                           OcTimelineObligation obligation,
                                           Duration allowedPause,
                                           OcRefreshSafetyRequest request,
                                           OcTimelineSimulationResultFactory.SearchProgress progress) {
        List<OcMemberCandidate> stateMembers = stateCandidates(state, request);
        Duration effectivePause = switch (obligation.kind()) {
            case COMMITTED_CHAIN_SUCCESSOR -> Duration.ZERO;
            case EXISTING_JOINED, PLANNED_EMPTY, CONDITIONAL_RANDOM -> allowedPause;
        };
        OcRosterMatchResult baseMatch = rosterMatcher.matchDeterministic(
                obligation.demand(), stateMembers, state.snapshotTime());
        if (!baseMatch.complete()) {
            return List.of();
        }
        ScheduleSearch search = new ScheduleSearch(new LinkedHashMap<>(), state,
                obligation, stateMembers, effectivePause, baseMatch);
        for (boolean earliestFirst : List.of(false, true)) {
            addSchedule(search, earliestFirst);
        }
        if (search.unique().isEmpty()) {
            return List.of();
        }
        appendAlternativeMatches(search, progress);
        return List.copyOf(search.unique().values());
    }

    /**
     * 追加有界的替代成员—岗位匹配：在基础完整匹配上把某个岗位的成员换成
     * 其他未被占用的合格成员，覆盖跨事件稀缺岗位需要不同局部选择的情形。
     *
     * <p>交换后的安排仍是完整且互不重复的匹配：被换入成员具备该岗位资格，
     * 且不在基础匹配已被占用的成员之中。</p>
     *
     * @param search   候选方案搜索上下文
     * @param progress 搜索进度与预算命中标记
     */
    private void appendAlternativeMatches(ScheduleSearch search,
                                          OcTimelineSimulationResultFactory.SearchProgress progress) {
        Set<Long> assignedIds = new HashSet<>();
        search.baseMatch().assignments().forEach(assignment ->
                assignedIds.add(assignment.userId()));
        for (OcPlannedAssignment assigned : search.baseMatch().assignments()) {
            if (appendAlternativesForAssignment(search, assigned, assignedIds, progress)) {
                return;
            }
        }
    }

    /**
     * 为基础匹配的单个岗位安排追加替代成员方案。
     *
     * @param search      候选方案搜索上下文
     * @param assigned    当前岗位安排
     * @param assignedIds 基础匹配已占用成员ID集合
     * @param progress    搜索进度与预算命中标记
     * @return 替代候选数已达到技术预算上限时返回true
     */
    private boolean appendAlternativesForAssignment(ScheduleSearch search,
                                                    OcPlannedAssignment assigned,
                                                    Set<Long> assignedIds,
                                                    OcTimelineSimulationResultFactory.SearchProgress progress) {
        OcTeamDemand demand = search.obligation().demand();
        OcPlanSlot slot = slotOf(demand, assigned.slotCode());
        if (slot == null) {
            return false;
        }
        for (OcMemberCandidate alternative : eligibleAlternatives(demand, slot,
                search.stateMembers(), assigned.userId())) {
            if (search.unique().size() >= OcSearchBudgetLimits.MAX_MATCH_ALTERNATIVES) {
                progress.matchAlternativesCapped = true;
                return true;
            }
            if (assignedIds.contains(alternative.userId())) {
                continue;
            }
            appendSwapSchedule(search, search.baseMatch().assignments(), assigned, slot,
                    alternative);
        }
        return false;
    }

    /**
     * 追加一个替代成员方案：替换该岗位成员后按两种加入顺序尝试排程。
     *
     * @param search      候选方案搜索上下文
     * @param assignments 基础匹配的全部岗位安排
     * @param assigned    被替换的岗位安排
     * @param slot        被替换的岗位
     * @param alternative 替代成员
     */
    private void appendSwapSchedule(ScheduleSearch search,
                                    List<OcPlannedAssignment> assignments,
                                    OcPlannedAssignment assigned, OcPlanSlot slot,
                                    OcMemberCandidate alternative) {
        OcPlannedAssignment forced = forcedAssignment(search.state(),
                search.obligation().demand(), slot, alternative);
        List<OcPlannedAssignment> swapped = swapAssignment(assignments, assigned, forced);
        for (boolean earliestFirst : List.of(false, true)) {
            StageSchedule schedule = buildStageSchedule(search.state(),
                    search.obligation(), swapped, search.stateMembers(),
                    search.effectivePause(), earliestFirst);
            if (schedule != null) {
                search.unique().putIfAbsent(scheduleSignature(schedule), schedule);
            }
        }
    }

    /**
     * 获取指定编码的空缺岗位需求。
     *
     * @param demand   队伍需求
     * @param slotCode 岗位编码
     * @return 岗位需求；不存在时为null
     */
    private OcPlanSlot slotOf(OcTeamDemand demand, String slotCode) {
        return demand.getVacantSlots().stream()
                .filter(slot -> slot.code().equals(slotCode)).findFirst().orElse(null);
    }

    /**
     * 用替代安排替换基础匹配中的一个岗位安排。
     *
     * @param assignments 基础匹配安排
     * @param assigned    被替换的安排
     * @param forced      替代安排
     * @return 替换后的安排列表
     */
    private List<OcPlannedAssignment> swapAssignment(List<OcPlannedAssignment> assignments,
                                                     OcPlannedAssignment assigned,
                                                     OcPlannedAssignment forced) {
        List<OcPlannedAssignment> result = new ArrayList<>(assignments.size());
        assignments.forEach(item -> result.add(item == assigned ? forced : item));
        return result;
    }

    /**
     * 构造岗位的替代合格成员列表，按成员ID稳定排序。
     *
     * @param demand       队伍需求
     * @param slot         空缺岗位
     * @param stateMembers 当前可用候选成员
     * @param assigned     基础匹配中该岗位的成员ID；无时为-1
     * @return 替代成员列表
     */
    private List<OcMemberCandidate> eligibleAlternatives(OcTeamDemand demand, OcPlanSlot slot,
                                                         List<OcMemberCandidate> stateMembers,
                                                         long assigned) {
        return stateMembers.stream()
                .filter(member -> member.userId() != assigned)
                .filter(member -> !member.fixed())
                .filter(member -> !demand.fixedMemberIds().contains(member.userId()))
                .filter(member -> member.getPassRate(demand.rank(), demand.ocName(),
                        slot.position()) >= slot.requiredPassRate())
                .sorted(Comparator.comparingLong(OcMemberCandidate::userId))
                .toList();
    }

    /**
     * 构造强制岗位安排：替代成员固定占用指定岗位。
     *
     * @param state       当前时间线状态
     * @param demand      队伍需求
     * @param slot        被强制的岗位
     * @param alternative 替代成员
     * @return 强制岗位安排
     */
    private OcPlannedAssignment forcedAssignment(OcTimelineState state, OcTeamDemand demand,
                                                 OcPlanSlot slot,
                                                 OcMemberCandidate alternative) {
        LocalDateTime availableAt = state.availableAt(alternative.userId());
        LocalDateTime joinAt = availableAt.isBefore(state.snapshotTime())
                ? state.snapshotTime() : availableAt;
        return new OcPlannedAssignment(alternative.userId(), alternative.nickname(),
                slot.code(), alternative.getPassRate(demand.rank(), demand.ocName(),
                slot.position()), slot.requiredPassRate(), joinAt, null,
                alternative.getCoefficient(demand.rank(), demand.ocName(), slot.code()));
    }

    /**
     * 计算基础完整匹配在指定加入顺序策略下的候选方案。
     *
     * @param search        候选方案搜索上下文
     * @param earliestFirst 成员加入顺序回退策略
     */
    private void addSchedule(ScheduleSearch search, boolean earliestFirst) {
        StageSchedule schedule = buildStageSchedule(search.state(), search.obligation(),
                search.baseMatch().assignments(), search.stateMembers(),
                search.effectivePause(), earliestFirst);
        if (schedule != null) {
            search.unique().putIfAbsent(scheduleSignature(schedule), schedule);
        }
    }

    /**
     * 单个义务候选方案搜索的共享上下文。
     *
     * @param unique         去重方案累积集合
     * @param state          当前时间线状态
     * @param obligation     待排程义务
     * @param stateMembers   当前可用候选成员
     * @param effectivePause 该义务的停转上限
     * @param baseMatch      唯一执行一次的基础完整匹配，供排序与替代生成复用
     */
    private record ScheduleSearch(
            Map<String, StageSchedule> unique,
            OcTimelineState state,
            OcTimelineObligation obligation,
            List<OcMemberCandidate> stateMembers,
            Duration effectivePause,
            OcRosterMatchResult baseMatch) {
    }

    /**
     * 构造候选方案的稳定签名：按加入顺序的成员与岗位序列。
     *
     * @param schedule 候选方案
     * @return 签名
     */
    private String scheduleSignature(StageSchedule schedule) {
        StringJoiner joiner = new StringJoiner("|");
        for (OcPlannedAssignment assignment : schedule.assignments()) {
            joiner.add(assignment.userId() + "@" + assignment.slotCode());
        }
        return joiner.toString();
    }

    /**
     * 构造反映当前时间线状态的候选成员视图。
     *
     * @param state   当前时间线状态
     * @param request 求解请求
     * @return 按状态可用时间更新的候选成员列表
     */
    private List<OcMemberCandidate> stateCandidates(OcTimelineState state,
                                                    OcRefreshSafetyRequest request) {
        List<OcMemberCandidate> result = new ArrayList<>();
        for (OcMemberCandidate member : request.members()) {
            if (!state.isUsable(member.userId())) {
                continue;
            }
            result.add(member.withAvailability(state.availableAt(member.userId()), false));
        }
        return result;
    }

    /**
     * 按逐阶段递推构建义务的完整加入时间线并执行停转政策与期限校验。
     *
     * @param state          当前时间线状态
     * @param obligation     待排程义务
     * @param assignments    已确定的岗位安排
     * @param candidates     当前可用候选成员，用于岗位稀缺性统计
     * @param effectivePause 该义务的停转上限
     * @param earliestFirst  成员加入顺序回退策略：true时最早可用优先
     * @return 阶段时间线；无法满足岗位、期限或停转政策时返回null
     */
    private StageSchedule buildStageSchedule(OcTimelineState state,
                                             OcTimelineObligation obligation,
                                             List<OcPlannedAssignment> assignments,
                                             List<OcMemberCandidate> candidates,
                                             Duration effectivePause,
                                             boolean earliestFirst) {
        OcTeamDemand demand = obligation.demand();
        List<OcPlannedAssignment> ordered = orderAssignments(assignments, demand, candidates,
                earliestFirst);
        LocalDateTime stageBoundary = demand.readyAt();
        List<LocalDateTime> joinTimes = new ArrayList<>();
        for (OcPlannedAssignment assignment : ordered) {
            LocalDateTime joinAt = effectiveJoinAt(state, assignment);
            if (!joinAllowed(state, joinAt, stageBoundary, obligation.firstJoinDeadline(),
                    effectivePause)) {
                return null;
            }
            joinTimes.add(joinAt);
            stageBoundary = OcPreparationTimeCalculator.nextReadyTime(stageBoundary, joinAt);
        }
        return joinTimes.isEmpty() ? null : new StageSchedule(ordered, joinTimes,
                stageBoundary);
    }

    /**
     * 计算成员在当前时间线状态下的实际加入时间：不早于快照时间。
     *
     * @param state      当前时间线状态
     * @param assignment 岗位安排
     * @return 实际加入时间
     */
    private LocalDateTime effectiveJoinAt(OcTimelineState state,
                                          OcPlannedAssignment assignment) {
        LocalDateTime availableAt = state.availableAt(assignment.userId());
        return availableAt.isBefore(state.snapshotTime()) ? state.snapshotTime() : availableAt;
    }

    /**
     * 校验一次成员加入是否满足首人期限、阶段边界和停转政策。
     *
     * @param state             当前时间线状态
     * @param joinAt            实际加入时间
     * @param stageBoundary     当前阶段边界；尚无成员时为null
     * @param firstJoinDeadline 首人最晚加入期限
     * @param effectivePause    该义务的单次新增停转上限
     * @return 允许加入时返回true
     */
    private boolean joinAllowed(OcTimelineState state, LocalDateTime joinAt,
                                LocalDateTime stageBoundary, LocalDateTime firstJoinDeadline,
                                Duration effectivePause) {
        if (stageBoundary == null) {
            return firstJoinDeadline == null || !joinAt.isAfter(firstJoinDeadline);
        }
        if (!joinAt.isAfter(stageBoundary)) {
            return true;
        }
        Duration pause = Duration.between(stageBoundary, joinAt);
        return !stageBoundary.isAfter(state.snapshotTime())
                || pause.compareTo(effectivePause) <= 0;
    }

    /**
     * 按加入顺序策略排序岗位匹配结果。
     *
     * <p>默认最晚可用成员先加入以最小化完成时间；回退策略按最早可用优先，
     * 用于期限压力下的替代顺序。同可用时间按岗位稀缺性和成员ID稳定排序。</p>
     *
     * @param assignments   岗位匹配结果
     * @param demand        队伍需求
     * @param candidates    当前可用候选成员，用于岗位稀缺性统计
     * @param earliestFirst 是否按最早可用优先
     * @return 排序后的加入安排
     */
    private List<OcPlannedAssignment> orderAssignments(List<OcPlannedAssignment> assignments,
                                                       OcTeamDemand demand,
                                                       List<OcMemberCandidate> candidates,
                                                       boolean earliestFirst) {
        Map<String, Long> eligibleCounts = new HashMap<>();
        for (OcPlanSlot slot : demand.getVacantSlots()) {
            eligibleCounts.put(slot.code(), candidates.stream()
                    .filter(member -> member.getPassRate(demand.rank(), demand.ocName(),
                            slot.position()) >= slot.requiredPassRate())
                    .count());
        }
        Comparator<OcPlannedAssignment> availability = Comparator.comparing(
                OcPlannedAssignment::joinAt);
        Comparator<OcPlannedAssignment> comparator = earliestFirst
                ? availability : availability.reversed();
        return assignments.stream()
                .sorted(comparator
                        .thenComparing(assignment -> eligibleCounts.getOrDefault(
                                assignment.slotCode(), Long.MAX_VALUE))
                        .thenComparingLong(OcPlannedAssignment::userId))
                .toList();
    }
}
