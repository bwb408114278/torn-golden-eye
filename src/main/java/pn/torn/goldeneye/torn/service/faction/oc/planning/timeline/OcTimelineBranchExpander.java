package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcPreparationTimeCalculator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcRosterMatcher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 时间线分支推进协作类。负责分支推进、不可排程处理、后继任务追加
 * 和完成分支的有效性筛选，由时间线事件推进器显式构造。
 * 纯内存对象，不访问数据库、HTTP或Redis。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
final class OcTimelineBranchExpander {
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();
    private final OcPausePolicyEvaluator pauseEvaluator = new OcPausePolicyEvaluator();
    private final OcLiquidityPathVerifier liquidityVerifier;
    private final OcTimelineScheduleCandidateFactory candidateFactory;
    private final OcTimelineTaskOrder taskOrder;

    /**
     * 创建时间线分支推进器。
     *
     * @param candidateFactory  候选方案工厂
     * @param taskOrder         任务排序器
     * @param liquidityVerifier 流动性路径验证器
     */
    OcTimelineBranchExpander(OcTimelineScheduleCandidateFactory candidateFactory,
                             OcTimelineTaskOrder taskOrder,
                             OcLiquidityPathVerifier liquidityVerifier) {
        this.candidateFactory = candidateFactory;
        this.taskOrder = taskOrder;
        this.liquidityVerifier = liquidityVerifier;
    }

    /**
     * 多状态搜索中的一个分支：一份时间线状态及其剩余任务。
     *
     * @param state               分支时间线状态
     * @param remaining           按处理顺序排列的剩余任务
     * @param completedCount      已完整排程的义务数量，用于支配剪枝
     * @param plannedEmptyExpired 分支内是否出现过被跳过的计划内无人OC
     */
    record SearchBranch(
            OcTimelineState state,
            List<OcTimelineTaskOrder.Task> remaining,
            int completedCount,
            boolean plannedEmptyExpired) {
    }

    /**
     * 展开单个分支的当前任务：按候选匹配方案分裂为后继分支或让分支消亡。
     *
     * @param branch              待展开分支
     * @param allowedPause        单次主动新增停转上限
     * @param requirePlannedEmpty 计划内无人OC无法启动时是否判定整体不可行
     * @param progress            搜索进度与失败标记
     * @return 展开后的后继分支集合
     */
    List<SearchBranch> expandBranch(SearchBranch branch, Duration allowedPause,
                                    boolean requirePlannedEmpty,
                                    OcTimelineSimulationResultFactory.SearchProgress progress) {
        OcTimelineTaskOrder.Task task = branch.remaining().getFirst();
        List<OcTimelineTaskOrder.Task> rest = branch.remaining()
                .subList(1, branch.remaining().size());
        OcTimelineObligation obligation = task.obligation();
        progress.expansions++;
        progress.representativeState = branch.state();
        if (obligation.demand().getVacantSlots().isEmpty()) {
            return expandFullObligation(branch, task, rest);
        }
        List<OcTimelineScheduleCandidateFactory.StageSchedule> schedules =
                candidateFactory.candidateSchedules(branch.state(), obligation,
                        allowedPause, progress.request, progress);
        if (schedules.isEmpty()) {
            return onObligationUnschedulable(branch, obligation, requirePlannedEmpty,
                    progress, rest);
        }
        List<SearchBranch> result = new ArrayList<>();
        for (OcTimelineScheduleCandidateFactory.StageSchedule schedule : schedules) {
            OcTimelineState state = new OcTimelineState(branch.state());
            applySchedule(state, obligation, schedule);
            if (!pauseEvaluator.withinPolicy(state.pauses(), modeForPause(allowedPause))) {
                continue;
            }
            result.add(new SearchBranch(state, withSpawnedSuccessors(task, rest, state,
                    schedule.completionAt()), branch.completedCount() + 1,
                    branch.plannedEmptyExpired()));
        }
        return result;
    }

    /**
     * 展开已满员义务：按readyTime生成确定完成—释放事件并生成链后继。
     *
     * @param branch 待展开分支
     * @param task   当前任务
     * @param rest   其余任务
     * @return 展开后的后继分支集合
     */
    private List<SearchBranch> expandFullObligation(SearchBranch branch,
                                                    OcTimelineTaskOrder.Task task,
                                                    List<OcTimelineTaskOrder.Task> rest) {
        OcTimelineObligation obligation = task.obligation();
        LocalDateTime readyAt = obligation.demand().readyAt();
        if (readyAt == null) {
            return List.of();
        }
        OcTimelineState state = new OcTimelineState(branch.state());
        LocalDateTime completionAt = readyAt.isBefore(state.snapshotTime())
                ? state.snapshotTime() : readyAt;
        applyCompletion(state, obligation, completionAt, List.of());
        return List.of(new SearchBranch(state,
                withSpawnedSuccessors(task, rest, state, completionAt),
                branch.completedCount() + 1, branch.plannedEmptyExpired()));
    }

    /**
     * 处理义务无法排程的分支：记录失败语义，或按计划内无人OC容错跳过。
     *
     * @param branch              待展开分支
     * @param obligation          无法排程的义务
     * @param requirePlannedEmpty 计划内无人OC无法启动时是否判定整体不可行
     * @param progress            搜索进度与失败标记
     * @param rest                其余任务
     * @return 展开后的后继分支集合；分支消亡时为空
     */
    private List<SearchBranch> onObligationUnschedulable(SearchBranch branch,
                                                         OcTimelineObligation obligation,
                                                         boolean requirePlannedEmpty,
                                                         OcTimelineSimulationResultFactory.SearchProgress progress,
                                                         List<OcTimelineTaskOrder.Task> rest) {
        if (isDeterministicShortage(obligation, progress.request)) {
            progress.deterministicFailure = true;
        }
        if (obligation.isHardObligation()) {
            progress.hardObligationFailed = true;
        }
        if (!obligation.isHardObligation()
                && obligation.kind() == OcTimelineObligation.ObligationKind.PLANNED_EMPTY
                && !requirePlannedEmpty) {
            progress.plannedEmptyExpired = true;
            return List.of(new SearchBranch(new OcTimelineState(branch.state()), rest,
                    branch.completedCount(), true));
        }
        return List.of();
    }

    /**
     * 当前义务完成后按真实完成时间生成链后继任务并记录生成事件。
     *
     * <p>后继键继承根实例键并附加链节点标识，保证同模板多次刷新的
     * 全部事件、停转、锚点和后继义务互不冲突。</p>
     *
     * @param task         已完成的当前任务
     * @param rest         其余任务
     * @param state        已写入完成事件的分支状态
     * @param completionAt 当前节点完成时间
     * @return 追加链后继后的剩余任务列表，保持处理顺序
     */
    private List<OcTimelineTaskOrder.Task> withSpawnedSuccessors(OcTimelineTaskOrder.Task task,
                                                                 List<OcTimelineTaskOrder.Task> rest,
                                                                 OcTimelineState state,
                                                                 LocalDateTime completionAt) {
        if (task.successors().isEmpty()) {
            return rest;
        }
        OcTeamDemand template = task.successors().getFirst();
        OcTeamDemand successorDemand = new OcTeamDemand(0L, template.ocName(),
                template.rank(), null,
                completionAt.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS), true,
                template.slots(), Set.of(), Set.of());
        String successorKey = task.obligation().key() + "->"
                + task.successors().size() + ":" + template.rank() + ":" + template.ocName();
        OcTimelineObligation successor = new OcTimelineObligation(successorKey,
                OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR,
                successorDemand, successorDemand.expiresAt(), completionAt);
        state.addEvent(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.CHAIN_SUCCESSOR_GENERATED, successorKey));
        List<OcTimelineTaskOrder.Task> updated = new ArrayList<>(rest);
        updated.add(new OcTimelineTaskOrder.Task(successor, task.successors().subList(1,
                task.successors().size())));
        updated.sort(taskOrder.taskComparator());
        return updated;
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
        return !rosterMatcher.matchDeterministic(obligation.demand(), relaxed,
                LocalDateTime.MIN).complete();
    }

    /**
     * 将完整阶段时间线写入状态：占用、停转评估、停转事件和完成—释放锚点。
     *
     * @param state      当前时间线状态
     * @param obligation 已排程义务
     * @param schedule   阶段时间线
     */
    private void applySchedule(OcTimelineState state, OcTimelineObligation obligation,
                               OcTimelineScheduleCandidateFactory.StageSchedule schedule) {
        LocalDateTime stageBoundary = obligation.demand().readyAt();
        for (int index = 0; index < schedule.assignments().size(); index++) {
            LocalDateTime joinAt = schedule.joinTimes().get(index);
            if (stageBoundary != null && joinAt.isAfter(stageBoundary)) {
                boolean preExisting = !stageBoundary.isAfter(state.snapshotTime());
                state.addPause(new OcPauseAssessment(obligation.key(), stageBoundary,
                        Duration.between(stageBoundary, joinAt), joinAt,
                        preExisting));
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
     * <p>锚点替换标记不再按锚点存在性直接填充，最终由
     * {@link OcLiquidityPathVerifier#verifyReplacementAnchors}以成员级占用区间回填。</p>
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
                false));
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
     * 移除并返回首个无重叠且任务全部完成的分支；存在重叠的完成分支按一致性失败消亡，
     * 未通过有限证明窗口内流动性连续性验证的完成分支按确定性失败消亡。
     *
     * <p>优先返回无计划内无人OC过期压力的完成分支：只要存在避开过期压力的
     * 匹配选择，就不把过期压力当作不可避免事实输出。</p>
     *
     * @param branches       当前分支集合，会被就地移除无效完成分支
     * @param progress       搜索进度与失败标记
     * @param proofWindowEnd 本次模拟的有限证明窗口结束时间
     * @return 首个有效完成分支；无时为null
     */
    SearchBranch pollCompleteBranch(List<SearchBranch> branches,
                                    OcTimelineSimulationResultFactory.SearchProgress progress,
                                    LocalDateTime proofWindowEnd) {
        branches.removeIf(branch -> branch.remaining().isEmpty()
                && !completeBranchValid(branch, progress, proofWindowEnd));
        return branches.stream()
                .filter(branch -> branch.remaining().isEmpty()
                        && !branch.plannedEmptyExpired())
                .findFirst().orElse(firstComplete(branches));
    }

    /**
     * 校验完成分支的有效性：占用区间互不重叠，且锚点链在本次模拟的有限证明窗口内
     * 拥有成员级证明的连续完成—释放路径。连续性验证失败的确定性结果记入搜索进度。
     *
     * <p>完成分支与不可行结果使用同一个证明窗口：窗口内被消耗的锚点资源，
     * 其接续完整释放必须不晚于证明窗口结束时间；窗口外释放只能作为下一次
     * 人工重评估的新快照事实，不能反向证明当前窗口内的连续流动性。</p>
     *
     * @param branch         完成分支
     * @param progress       搜索进度与失败标记
     * @param proofWindowEnd 本次模拟的有限证明窗口结束时间
     * @return 分支有效时返回true
     */
    private boolean completeBranchValid(SearchBranch branch,
                                        OcTimelineSimulationResultFactory.SearchProgress progress,
                                        LocalDateTime proofWindowEnd) {
        if (!branch.state().hasNoOverlappingIntervals()) {
            return false;
        }
        if (liquidityVerifier.hasContinuousCompletionPath(branch.state().anchors(),
                branch.state().intervals(), proofWindowEnd)) {
            return true;
        }
        progress.deterministicFailure = true;
        progress.liquidityPathBroken = true;
        return false;
    }

    /**
     * 获取首个任务全部完成的分支。
     *
     * @param branches 分支集合
     * @return 首个完成分支；无时为null
     */
    private SearchBranch firstComplete(List<SearchBranch> branches) {
        return branches.stream().filter(branch -> branch.remaining().isEmpty())
                .findFirst().orElse(null);
    }

    /**
     * 由本次模拟的停转上限反推模式停转政策。
     *
     * @param allowedPause 单次主动新增停转上限
     * @return 对应的规划模式
     */
    private OcPlanMode modeForPause(Duration allowedPause) {
        if (Duration.ZERO.equals(allowedPause)) {
            return OcPlanMode.CONSERVATIVE;
        }
        if (OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE.equals(allowedPause)) {
            return OcPlanMode.BALANCED;
        }
        return OcPlanMode.PROFIT;
    }
}
