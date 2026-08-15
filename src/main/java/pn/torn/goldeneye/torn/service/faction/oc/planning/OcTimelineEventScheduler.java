package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 有限事件时间线事件推进器。按义务层级和期限顺序推进单个随机组合的完整时间线。
 *
 * <p>处理顺序：先确保已投入OC和已启动链义务，再处理计划内无人OC，最后处理本轮随机结果；
 * 链节点完成后按真实完成时间生成首人期限为完成时间后7天的后继义务；
 * 推进过程记录完成释放、停转、恢复和链后继生成事件。</p>
 *
 * <p>跨事件匹配采用有界多状态搜索：对每个关键事件以不同成员—岗位匹配展开有限的
 * 候选状态，用{@link OcTimelineStatePruner}做支配剪枝和状态上限控制，
 * 每个候选分支的产生均消费{@link OcPausePolicyEvaluator}的停转政策；
 * 状态上限或展开预算耗尽时结果标记搜索预算截断，由上层映射为
 * {@code UNPROVEN_SEARCH_BUDGET}，不得误写为已证明不可行或卡死。
 * 纯内存对象，不访问数据库、HTTP或Redis。</p>
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
    /**
     * 单次模拟允许的义务展开总次数上限，超出即视为搜索预算截断。
     */
    private static final int MAX_TASK_EXPANSIONS = 256;
    /**
     * 每个义务在基础匹配之外保留的替代匹配方案上限。
     */
    private static final int MAX_MATCH_ALTERNATIVES = 4;

    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();
    private final OcTimelineStatePruner statePruner = new OcTimelineStatePruner();
    private final OcPausePolicyEvaluator pauseEvaluator = new OcPausePolicyEvaluator();
    private final OcLiquidityPathVerifier liquidityVerifier = new OcLiquidityPathVerifier();
    private final int maxTaskExpansions;

    /**
     * 创建使用默认搜索预算的时间线事件推进器。
     */
    OcTimelineEventScheduler() {
        this(MAX_TASK_EXPANSIONS);
    }

    /**
     * 创建指定义务展开预算的时间线事件推进器，仅供需要人为缩小搜索预算的测试使用。
     *
     * @param maxTaskExpansions 单次模拟允许的义务展开总次数上限
     */
    OcTimelineEventScheduler(int maxTaskExpansions) {
        this.maxTaskExpansions = maxTaskExpansions;
    }

    /**
     * 单个随机组合的完整时间线模拟结果。
     *
     * @param feasible              全部必须承接的义务是否均可完整排程
     * @param deterministicFailure  失败是否由岗位能力或人数的确定性矛盾引起
     * @param hardObligationFailed  是否存在已投入义务无法履约
     * @param plannedEmptyExpired   是否存在无法在期限前启动的计划内无人OC
     * @param anchors               已证明完成—释放锚点链，替换标记经成员级验证
     * @param pauses                停转评估列表
     * @param events                已发生的时间线事件
     * @param maxNewPause           全部义务中的最大单次主动新增停转时长
     * @param searchBudgetExhausted 多状态搜索是否因状态上限或展开预算截断
     */
    record SimulationResult(
            boolean feasible,
            boolean deterministicFailure,
            boolean hardObligationFailed,
            boolean plannedEmptyExpired,
            List<OcLiquidityAnchor> anchors,
            List<OcPauseAssessment> pauses,
            List<OcTimelineEvent> events,
            Duration maxNewPause,
            boolean searchBudgetExhausted) {
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

    /**
     * 多状态搜索中的一个分支：一份时间线状态及其剩余任务。
     *
     * @param state               分支时间线状态
     * @param remaining           按处理顺序排列的剩余任务
     * @param completedCount      已完整排程的义务数量，用于支配剪枝
     * @param plannedEmptyExpired 分支内是否出现过被跳过的计划内无人OC
     */
    private record SearchBranch(
            OcTimelineState state,
            List<Task> remaining,
            int completedCount,
            boolean plannedEmptyExpired) {
    }

    /**
     * 一次模拟的搜索进度与失败语义累积状态。
     */
    private static final class SearchProgress {
        private final OcRefreshSafetyRequest request;
        private int expansions;
        private boolean deterministicFailure;
        private boolean hardObligationFailed;
        private boolean plannedEmptyExpired;
        private OcTimelineState representativeState;

        private SearchProgress(OcRefreshSafetyRequest request) {
            this.request = request;
            this.representativeState = new OcTimelineState(request);
        }
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
        List<Task> initial = new ArrayList<>();
        request.obligations().forEach(obligation -> initial.add(new Task(obligation,
                request.chainSuccessorsByKey().getOrDefault(obligation.key(), List.of()))));
        candidates.forEach(candidate -> initial.add(new Task(candidate.obligation(),
                candidate.successors())));
        initial.sort(taskComparator());
        List<SearchBranch> branches = new ArrayList<>();
        branches.add(new SearchBranch(new OcTimelineState(request), initial, 0, false));
        SearchProgress progress = new SearchProgress(request);
        while (true) {
            SearchBranch complete = pollCompleteBranch(branches);
            if (complete != null) {
                return assembleResult(complete, progress, false);
            }
            if (branches.isEmpty()) {
                return assembleResult(null, progress, false);
            }
            List<SearchBranch> expanded = new ArrayList<>();
            for (SearchBranch branch : branches) {
                if (progress.expansions >= maxTaskExpansions) {
                    return assembleResult(pollCompleteBranch(expanded), progress, true);
                }
                expanded.addAll(expandBranch(branch, allowedPause, requirePlannedEmpty,
                        progress));
            }
            OcTimelineStatePruner.PruneResult<SearchBranch> pruned = pruneBranches(expanded,
                    request);
            branches = pruned.kept();
            if (pruned.truncated()) {
                return assembleResult(pollCompleteBranch(branches), progress, true);
            }
        }
    }

    /**
     * 移除并返回首个无重叠且任务全部完成的分支；存在重叠的完成分支按一致性失败消亡。
     *
     * <p>优先返回无计划内无人OC过期压力的完成分支：只要存在避开过期压力的
     * 匹配选择，就不把过期压力当作不可避免事实输出。</p>
     *
     * @param branches 当前分支集合，会被就地移除无效完成分支
     * @return 首个有效完成分支；无时为null
     */
    private SearchBranch pollCompleteBranch(List<SearchBranch> branches) {
        branches.removeIf(branch -> branch.remaining().isEmpty()
                && !branch.state().hasNoOverlappingIntervals());
        return branches.stream()
                .filter(branch -> branch.remaining().isEmpty()
                        && !branch.plannedEmptyExpired())
                .findFirst().orElse(firstComplete(branches));
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
     * 装配一次模拟的最终结果：可行分支经锚点替换验证，不可行时输出累积失败语义。
     *
     * @param complete        可行完成分支；不可行时为null
     * @param progress        搜索进度与失败标记
     * @param budgetExhausted 搜索是否因预算截断
     * @return 时间线模拟结果
     */
    private SimulationResult assembleResult(SearchBranch complete, SearchProgress progress,
                                            boolean budgetExhausted) {
        if (complete == null) {
            OcTimelineState state = progress.representativeState;
            return new SimulationResult(false, progress.deterministicFailure,
                    progress.hardObligationFailed, progress.plannedEmptyExpired,
                    state.anchors(), state.pauses(), state.events(), maxNewPause(state),
                    budgetExhausted);
        }
        OcTimelineState state = complete.state();
        List<OcLiquidityAnchor> verified = liquidityVerifier.verifyReplacementAnchors(
                state.anchors(), state.intervals());
        return new SimulationResult(true, false, false, complete.plannedEmptyExpired(),
                verified, state.pauses(), state.events(), maxNewPause(state),
                budgetExhausted);
    }

    /**
     * 用支配剪枝和状态上限裁剪分支集合。
     *
     * @param branches 待裁剪分支集合
     * @param request  求解请求，用于成员可用性维度
     * @return 裁剪结果
     */
    private OcTimelineStatePruner.PruneResult<SearchBranch> pruneBranches(
            List<SearchBranch> branches, OcRefreshSafetyRequest request) {
        return statePruner.prune(branches,
                SearchBranch::completedCount,
                branch -> branch.state().anchors().size(),
                branch -> pauseNanos(branch.state()),
                branch -> availabilitySum(branch.state(), request));
    }

    /**
     * 计算状态中全部主动新增停转的纳秒合计。
     *
     * @param state 时间线状态
     * @return 新增停转纳秒合计
     */
    private long pauseNanos(OcTimelineState state) {
        return state.pauses().stream()
                .filter(pause -> !pause.preExistingPause())
                .mapToLong(pause -> pause.newPauseDuration().toNanos())
                .sum();
    }

    /**
     * 计算全部成员可用时间的纪元秒合计，越早可用越优。
     *
     * @param state   时间线状态
     * @param request 求解请求
     * @return 可用时间纪元秒合计
     */
    private long availabilitySum(OcTimelineState state, OcRefreshSafetyRequest request) {
        long sum = 0;
        for (OcMemberCandidate member : request.members()) {
            sum += state.availableAt(member.userId()).toEpochSecond(ZoneOffset.UTC);
        }
        return sum;
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
    private List<SearchBranch> expandBranch(SearchBranch branch, Duration allowedPause,
                                            boolean requirePlannedEmpty,
                                            SearchProgress progress) {
        Task task = branch.remaining().getFirst();
        List<Task> rest = branch.remaining().subList(1, branch.remaining().size());
        OcTimelineObligation obligation = task.obligation();
        progress.expansions++;
        progress.representativeState = branch.state();
        if (obligation.demand().getVacantSlots().isEmpty()) {
            return expandFullObligation(branch, task, rest);
        }
        List<StageSchedule> schedules = candidateSchedules(branch.state(), obligation,
                allowedPause, progress.request);
        if (schedules.isEmpty()) {
            return onObligationUnschedulable(branch, obligation, requirePlannedEmpty,
                    progress, rest);
        }
        List<SearchBranch> result = new ArrayList<>();
        for (StageSchedule schedule : schedules) {
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
    private List<SearchBranch> expandFullObligation(SearchBranch branch, Task task,
                                                    List<Task> rest) {
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
                                                         SearchProgress progress,
                                                         List<Task> rest) {
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
     * 生成当前义务的候选完整方案：基础匹配的两种加入排序加有界的替代成员—岗位匹配。
     *
     * @param state        当前时间线状态
     * @param obligation   待排程义务
     * @param allowedPause 单次主动新增停转上限
     * @param request      求解请求
     * @return 去重后的候选方案；全部不可行时为空
     */
    private List<StageSchedule> candidateSchedules(OcTimelineState state,
                                                   OcTimelineObligation obligation,
                                                   Duration allowedPause,
                                                   OcRefreshSafetyRequest request) {
        List<OcMemberCandidate> stateMembers = stateCandidates(state, request);
        Duration effectivePause = switch (obligation.kind()) {
            case EXISTING_JOINED -> FACT_MAX_PAUSE;
            case COMMITTED_CHAIN_SUCCESSOR -> Duration.ZERO;
            case PLANNED_EMPTY, CONDITIONAL_RANDOM -> allowedPause;
        };
        ScheduleSearch search = new ScheduleSearch(new LinkedHashMap<>(), state,
                obligation, stateMembers, effectivePause);
        for (boolean earliestFirst : List.of(false, true)) {
            addSchedule(search, earliestFirst);
        }
        if (search.unique().isEmpty()) {
            return List.of();
        }
        appendAlternativeMatches(search);
        return List.copyOf(search.unique().values());
    }

    /**
     * 追加有界的替代成员—岗位匹配：在基础完整匹配上把某个岗位的成员换成
     * 其他未被占用的合格成员，覆盖跨事件稀缺岗位需要不同局部选择的情形。
     *
     * <p>交换后的安排仍是完整且互不重复的匹配：被换入成员具备该岗位资格，
     * 且不在基础匹配已被占用的成员之中。</p>
     *
     * @param search 候选方案搜索上下文
     */
    private void appendAlternativeMatches(ScheduleSearch search) {
        OcTeamDemand demand = search.obligation().demand();
        OcRosterMatchResult base = rosterMatcher.matchDeterministic(demand,
                search.stateMembers(), search.state().snapshotTime());
        if (!base.complete()) {
            return;
        }
        Set<Long> assignedIds = new HashSet<>();
        base.assignments().forEach(assignment -> assignedIds.add(assignment.userId()));
        for (OcPlannedAssignment assigned : base.assignments()) {
            if (appendAlternativesForAssignment(search, base.assignments(), assigned,
                    assignedIds)) {
                return;
            }
        }
    }

    /**
     * 为基础匹配的单个岗位安排追加替代成员方案。
     *
     * @param search      候选方案搜索上下文
     * @param assignments 基础匹配的全部岗位安排
     * @param assigned    当前岗位安排
     * @param assignedIds 基础匹配已占用成员ID集合
     * @return 替代方案数已达上限时返回true
     */
    private boolean appendAlternativesForAssignment(ScheduleSearch search,
                                                    List<OcPlannedAssignment> assignments,
                                                    OcPlannedAssignment assigned,
                                                    Set<Long> assignedIds) {
        OcTeamDemand demand = search.obligation().demand();
        OcPlanSlot slot = slotOf(demand, assigned.slotCode());
        if (slot == null) {
            return false;
        }
        for (OcMemberCandidate alternative : eligibleAlternatives(demand, slot,
                search.stateMembers(), assigned.userId())) {
            if (assignedIds.contains(alternative.userId())) {
                continue;
            }
            appendSwapSchedule(search, assignments, assigned, slot, alternative);
            if (search.unique().size() > MAX_MATCH_ALTERNATIVES) {
                return true;
            }
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
        OcTeamDemand demand = search.obligation().demand();
        OcRosterMatchResult match = rosterMatcher.matchDeterministic(demand,
                search.stateMembers(), search.state().snapshotTime());
        if (!match.complete()) {
            return;
        }
        StageSchedule schedule = buildStageSchedule(search.state(), search.obligation(),
                match.assignments(), search.stateMembers(), search.effectivePause(),
                earliestFirst);
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
     */
    private record ScheduleSearch(
            Map<String, StageSchedule> unique,
            OcTimelineState state,
            OcTimelineObligation obligation,
            List<OcMemberCandidate> stateMembers,
            Duration effectivePause) {
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
    private List<Task> withSpawnedSuccessors(Task task, List<Task> rest,
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
        List<Task> updated = new ArrayList<>(rest);
        updated.add(new Task(successor, task.successors().subList(1,
                task.successors().size())));
        updated.sort(taskComparator());
        return updated;
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
}
