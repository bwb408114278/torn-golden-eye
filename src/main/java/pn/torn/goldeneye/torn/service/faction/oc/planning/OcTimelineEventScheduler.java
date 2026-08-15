package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 有限事件时间线事件推进器。按义务层级和期限顺序推进单个随机组合的完整时间线。
 *
 * <p>处理顺序：先确保已投入OC和已启动链义务，再处理计划内无人OC，最后处理本轮随机结果；
 * 链节点完成后按真实完成时间生成首人期限为完成时间后7天的后继义务；
 * 推进过程记录完成释放、停转、恢复和链后继生成事件。纯内存对象，
 * 不访问数据库、HTTP或Redis。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
final class OcTimelineEventScheduler {
    /**
     * 已有人OC属于既成事实，其被迫停转不受模式停转上限约束。
     */
    private static final Duration FACT_MAX_PAUSE = Duration.ofDays(3650);

    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    /**
     * 单个随机组合的完整时间线模拟结果。
     *
     * @param feasible             全部必须承接的义务是否均可完整排程
     * @param deterministicFailure 失败是否由岗位能力或人数的确定性矛盾引起
     * @param hardObligationFailed 是否存在已投入义务无法履约
     * @param plannedEmptyExpired  是否存在无法在期限前启动的计划内无人OC
     * @param anchors              已证明完成—释放锚点链
     * @param pauses               停转评估列表
     * @param events               已发生的时间线事件
     * @param maxNewPause          全部义务中的最大单次主动新增停转时长
     */
    record SimulationResult(
            boolean feasible,
            boolean deterministicFailure,
            boolean hardObligationFailed,
            boolean plannedEmptyExpired,
            List<OcLiquidityAnchor> anchors,
            List<OcPauseAssessment> pauses,
            List<OcTimelineEvent> events,
            Duration maxNewPause) {
    }

    /**
     * 单个义务的排程结果。
     *
     * @param scheduled            是否成功完整排程
     * @param deterministicFailure 失败是否为确定性矛盾
     * @param completionAt         完成时间；失败时为null
     */
    private record ObligationOutcome(
            boolean scheduled,
            boolean deterministicFailure,
            LocalDateTime completionAt) {
    }

    /**
     * 模拟一条完整时间线：事实义务加本轮候选义务，按层级与期限推进。
     *
     * @param request             求解请求
     * @param candidates          本轮条件性随机结果义务；链后继模板挂在根义务上
     * @param allowedPause        单次主动新增停转上限；已启动链节点始终为零
     * @param requirePlannedEmpty 计划内无人OC无法启动时是否判定整体不可行
     * @return 时间线模拟结果
     */
    SimulationResult simulate(OcRefreshSafetyRequest request,
                              List<CandidateRoot> candidates,
                              Duration allowedPause,
                              boolean requirePlannedEmpty) {
        OcTimelineState state = new OcTimelineState(request);
        PriorityQueue<Task> queue = new PriorityQueue<>(taskComparator());
        request.obligations().forEach(obligation -> queue.add(new Task(obligation,
                request.chainSuccessorsByKey().getOrDefault(obligation.key(), List.of()))));
        candidates.forEach(candidate -> queue.add(new Task(candidate.obligation(),
                candidate.successors())));
        boolean deterministicFailure = false;
        boolean plannedEmptyExpired = false;
        while (!queue.isEmpty()) {
            Task task = queue.poll();
            ObligationOutcome outcome = scheduleObligation(state, task, allowedPause, request);
            if (outcome.scheduled()) {
                spawnSuccessors(state, queue, task, outcome.completionAt());
                continue;
            }
            deterministicFailure |= outcome.deterministicFailure();
            if (task.obligation().isHardObligation()) {
                return failedResult(state, deterministicFailure, plannedEmptyExpired, true);
            }
            if (task.obligation().kind() != OcTimelineObligation.ObligationKind.PLANNED_EMPTY
                    || requirePlannedEmpty) {
                return failedResult(state, deterministicFailure, plannedEmptyExpired, false);
            }
            plannedEmptyExpired = true;
        }
        if (!state.hasNoOverlappingIntervals()) {
            return new SimulationResult(false, deterministicFailure, false,
                    plannedEmptyExpired, state.anchors(), state.pauses(), state.events(),
                    maxNewPause(state));
        }
        return new SimulationResult(true, deterministicFailure, false,
                plannedEmptyExpired, state.anchors(), state.pauses(), state.events(),
                maxNewPause(state));
    }

    /**
     * 构造单个义务排程失败时的整体模拟结果。
     *
     * @param state                当前时间线状态
     * @param deterministicFailure 是否已出现确定性矛盾
     * @param plannedEmptyExpired  是否已出现计划内无人OC过期压力
     * @param hardObligationFailed 失败义务是否为已投入硬义务
     * @return 不可行的时间线模拟结果
     */
    private SimulationResult failedResult(OcTimelineState state, boolean deterministicFailure,
                                          boolean plannedEmptyExpired,
                                          boolean hardObligationFailed) {
        return new SimulationResult(false, deterministicFailure, hardObligationFailed,
                plannedEmptyExpired, state.anchors(), state.pauses(), state.events(),
                maxNewPause(state));
    }

    /**
     * 当前义务完成后按真实完成时间生成链后继义务。
     *
     * @param state        当前时间线状态
     * @param queue        待处理任务队列
     * @param task         已完成的当前任务
     * @param completionAt 当前节点完成时间
     */
    private void spawnSuccessors(OcTimelineState state, PriorityQueue<Task> queue, Task task,
                                 LocalDateTime completionAt) {
        if (task.successors().isEmpty()) {
            return;
        }
        OcTeamDemand template = task.successors().getFirst();
        OcTeamDemand successorDemand = new OcTeamDemand(0L, template.ocName(),
                template.rank(), null,
                completionAt.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS), true,
                template.slots(), Set.of(), Set.of());
        String successorKey = task.obligation().key() + "->"
                + template.rank() + ":" + template.ocName();
        OcTimelineObligation successor = new OcTimelineObligation(successorKey,
                OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR,
                successorDemand, successorDemand.expiresAt(), completionAt);
        queue.add(new Task(successor, task.successors().subList(1, task.successors().size())));
        state.addEvent(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.CHAIN_SUCCESSOR_GENERATED, successorKey));
    }

    /**
     * 将单个义务排入当前时间线状态。
     *
     * @param state        当前时间线状态
     * @param task         待排程任务
     * @param allowedPause 单次主动新增停转上限
     * @param request      求解请求
     * @return 排程结果
     */
    private ObligationOutcome scheduleObligation(OcTimelineState state, Task task,
                                                 Duration allowedPause,
                                                 OcRefreshSafetyRequest request) {
        OcTimelineObligation obligation = task.obligation();
        OcTeamDemand demand = obligation.demand();
        if (demand.getVacantSlots().isEmpty()) {
            return scheduleFullObligation(state, obligation);
        }
        Duration effectivePause = switch (obligation.kind()) {
            case EXISTING_JOINED -> FACT_MAX_PAUSE;
            case COMMITTED_CHAIN_SUCCESSOR -> Duration.ZERO;
            case PLANNED_EMPTY, CONDITIONAL_RANDOM -> allowedPause;
        };
        List<OcMemberCandidate> stateMembers = stateCandidates(state, request);
        StageSchedule schedule = buildStageSchedule(state, obligation, stateMembers,
                effectivePause, false);
        if (schedule == null) {
            schedule = buildStageSchedule(state, obligation, stateMembers, effectivePause, true);
        }
        if (schedule == null) {
            return new ObligationOutcome(false,
                    isDeterministicShortage(obligation, request), null);
        }
        applySchedule(state, obligation, schedule);
        return new ObligationOutcome(true, false, schedule.completionAt());
    }

    /**
     * 排程已满员义务：按readyTime生成确定完成—释放事件。
     *
     * @param state      当前时间线状态
     * @param obligation 已满员义务
     * @return 排程结果；readyTime缺失时按不可证明失败处理
     */
    private ObligationOutcome scheduleFullObligation(OcTimelineState state,
                                                     OcTimelineObligation obligation) {
        LocalDateTime readyAt = obligation.demand().readyAt();
        if (readyAt == null) {
            return new ObligationOutcome(false, false, null);
        }
        LocalDateTime completionAt = readyAt.isBefore(state.snapshotTime())
                ? state.snapshotTime() : readyAt;
        applyCompletion(state, obligation, completionAt, List.of());
        return new ObligationOutcome(true, false, completionAt);
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
     * @param state         当前时间线状态
     * @param obligation    待排程义务
     * @param candidates    当前可用候选成员
     * @param allowedPause  单次主动新增停转上限
     * @param earliestFirst 成员加入顺序回退策略：true时最早可用优先
     * @return 阶段时间线；无法满足岗位、期限或停转政策时返回null
     */
    private StageSchedule buildStageSchedule(OcTimelineState state,
                                             OcTimelineObligation obligation,
                                             List<OcMemberCandidate> candidates,
                                             Duration allowedPause,
                                             boolean earliestFirst) {
        OcTeamDemand demand = obligation.demand();
        OcRosterMatchResult match = rosterMatcher.matchDeterministic(
                demand, candidates, state.snapshotTime());
        if (!match.complete() || match.completionAt() == null) {
            return null;
        }
        List<OcPlannedAssignment> assignments = orderAssignments(
                match.assignments(), demand, candidates, earliestFirst);
        LocalDateTime stageBoundary = demand.readyAt();
        List<LocalDateTime> joinTimes = new ArrayList<>();
        for (OcPlannedAssignment assignment : assignments) {
            LocalDateTime joinAt = effectiveJoinAt(state, assignment);
            if (!joinAllowed(state, joinAt, stageBoundary, obligation.firstJoinDeadline(),
                    allowedPause)) {
                return null;
            }
            joinTimes.add(joinAt);
            stageBoundary = OcPreparationTimeCalculator.nextReadyTime(stageBoundary, joinAt);
        }
        return joinTimes.isEmpty() ? null : new StageSchedule(assignments, joinTimes,
                stageBoundary);
    }

    /**
     * 计算成员在当前时间线状态下的实际加入时间：不早于快照时间。
     *
     * @param state      当前时间线状态
     * @param assignment 岗位安排
     * @return 实际加入时间
     */
    private LocalDateTime effectiveJoinAt(OcTimelineState state, OcPlannedAssignment assignment) {
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
     * @param allowedPause      单次主动新增停转上限
     * @return 允许加入时返回true
     */
    private boolean joinAllowed(OcTimelineState state, LocalDateTime joinAt,
                                LocalDateTime stageBoundary, LocalDateTime firstJoinDeadline,
                                Duration allowedPause) {
        if (stageBoundary == null) {
            return firstJoinDeadline == null || !joinAt.isAfter(firstJoinDeadline);
        }
        if (!joinAt.isAfter(stageBoundary)) {
            return true;
        }
        Duration pause = Duration.between(stageBoundary, joinAt);
        return !stageBoundary.isAfter(state.snapshotTime())
                || pause.compareTo(allowedPause) <= 0;
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

    /**
     * 判断义务失败是否为确定性岗位能力矛盾：以全帮除不可证明占用外的成员、
     * 忽略全部时间和占用约束后仍无法完成岗位匹配。
     *
     * @param obligation 待排程义务
     * @param request    求解请求
     * @return 确定性矛盾时返回true
     */
    private boolean isDeterministicShortage(OcTimelineObligation obligation,
                                            OcRefreshSafetyRequest request) {
        List<OcMemberCandidate> relaxed = request.members().stream()
                .filter(member -> !request.isUnprovableMember(member.userId()))
                .map(member -> member.withAvailability(LocalDateTime.MIN, false))
                .toList();
        OcRosterMatchResult relaxedMatch = rosterMatcher.matchDeterministic(
                obligation.demand(), relaxed, LocalDateTime.MIN);
        return !relaxedMatch.complete();
    }

    /**
     * 将完整阶段时间线写入状态：占用、停转评估、停转事件和完成—释放锚点。
     *
     * @param state      当前时间线状态
     * @param obligation 已排程义务
     * @param schedule   阶段时间线
     */
    private void applySchedule(OcTimelineState state, OcTimelineObligation obligation,
                               StageSchedule schedule) {
        LocalDateTime stageBoundary = obligation.demand().readyAt();
        boolean plannerCreated = obligation.kind()
                != OcTimelineObligation.ObligationKind.EXISTING_JOINED;
        for (int index = 0; index < schedule.assignments().size(); index++) {
            LocalDateTime joinAt = schedule.joinTimes().get(index);
            if (stageBoundary != null && joinAt.isAfter(stageBoundary)) {
                boolean preExisting = !plannerCreated
                        || !stageBoundary.isAfter(state.snapshotTime());
                state.addPause(new OcPauseAssessment(obligation.key(),
                        Duration.between(stageBoundary, joinAt), joinAt,
                        preExisting, true));
                state.addEvent(new OcTimelineEvent(stageBoundary,
                        OcTimelineEvent.EventType.PAUSE_STARTED, obligation.key()));
                state.addEvent(new OcTimelineEvent(joinAt,
                        OcTimelineEvent.EventType.PAUSE_RECOVERED, obligation.key()));
            }
            stageBoundary = OcPreparationTimeCalculator.nextReadyTime(stageBoundary, joinAt);
        }
        applyCompletion(state, obligation, schedule.completionAt(), schedule.assignments());
    }

    /**
     * 写入义务完成事件：释放固定与新增成员，记录占用区间、锚点和完成释放事件。
     *
     * @param state        当前时间线状态
     * @param obligation   已完成义务
     * @param completionAt 最终完成时间
     * @param assignments  新增加入安排；满员义务为空
     */
    private void applyCompletion(OcTimelineState state, OcTimelineObligation obligation,
                                 LocalDateTime completionAt,
                                 List<OcPlannedAssignment> assignments) {
        OcMemberInterval.IntervalSource source = intervalSource(obligation.kind());
        int released = 0;
        for (long userId : obligation.fixedMemberIds()) {
            state.occupy(userId, state.snapshotTime(), completionAt, source);
            released++;
        }
        for (OcPlannedAssignment assignment : assignments) {
            state.occupy(assignment.userId(), assignment.joinAt(), completionAt, source);
            released++;
        }
        state.addAnchor(new OcLiquidityAnchor(obligation.key(), completionAt, released,
                !state.anchors().isEmpty()));
        state.addEvent(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, obligation.key()));
    }

    /**
     * 获取义务类别对应的占用区间来源。
     *
     * @param kind 义务类别
     * @return 占用区间来源
     */
    private OcMemberInterval.IntervalSource intervalSource(
            OcTimelineObligation.ObligationKind kind) {
        return switch (kind) {
            case EXISTING_JOINED -> OcMemberInterval.IntervalSource.EXISTING_OC;
            case COMMITTED_CHAIN_SUCCESSOR -> OcMemberInterval.IntervalSource.COMMITTED_CHAIN;
            case PLANNED_EMPTY -> OcMemberInterval.IntervalSource.PLANNED_EMPTY;
            case CONDITIONAL_RANDOM -> OcMemberInterval.IntervalSource.RANDOM_CANDIDATE;
        };
    }

    /**
     * 计算当前时间线中最大单次主动新增停转时长。
     *
     * @param state 当前时间线状态
     * @return 最大新增停转时长；无停转时为零
     */
    private Duration maxNewPause(OcTimelineState state) {
        return state.pauses().stream()
                .filter(pause -> !pause.preExistingPause())
                .map(OcPauseAssessment::newPauseDuration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    /**
     * 任务稳定排序：层级、首人期限、阶段时间、等级、名称和键。
     *
     * @return 任务比较器
     */
    private Comparator<Task> taskComparator() {
        return Comparator.comparingInt((Task task) -> tier(task.obligation()))
                .thenComparing(task -> task.obligation().firstJoinDeadline(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(task -> task.obligation().demand().readyAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(task -> -task.obligation().demand().rank())
                .thenComparing(task -> task.obligation().demand().ocName())
                .thenComparing(task -> task.obligation().key());
    }

    /**
     * 获取义务的处理层级：已投入义务最先，计划内无人OC次之，随机结果最后。
     *
     * @param obligation 时间线义务
     * @return 层级编号，越小越优先
     */
    private int tier(OcTimelineObligation obligation) {
        if (obligation.isHardObligation()) {
            return 0;
        }
        return obligation.kind() == OcTimelineObligation.ObligationKind.PLANNED_EMPTY ? 1 : 2;
    }

    /**
     * 单个待排程任务及其剩余链后继模板。
     *
     * @param obligation 时间线义务
     * @param successors 当前节点之后的剩余链节点模板
     */
    private record Task(
            OcTimelineObligation obligation,
            List<OcTeamDemand> successors) {
    }

    /**
     * 一个条件性随机结果候选根义务及其完整链后继模板。
     *
     * @param obligation 根义务
     * @param successors 根完成后的剩余链节点模板；普通池候选为空
     */
    record CandidateRoot(
            OcTimelineObligation obligation,
            List<OcTeamDemand> successors) {
    }

    /**
     * 义务的完整阶段时间线。
     *
     * @param assignments  按加入顺序排列的岗位安排
     * @param joinTimes    与安排对应的实际加入时间
     * @param completionAt 最终完成时间
     */
    private record StageSchedule(
            List<OcPlannedAssignment> assignments,
            List<LocalDateTime> joinTimes,
            LocalDateTime completionAt) {
    }
}
