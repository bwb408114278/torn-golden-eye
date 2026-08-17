package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineBranchExpander.SearchBranch;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineSimulationResultFactory.SearchProgress;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineTaskOrder.Task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 有限事件时间线事件推进器。按义务层级和期限顺序推进单个随机组合的完整时间线。
 *
 * <p>处理顺序：先确保已投入OC和已启动链义务，再处理计划内无人OC，最后处理本轮随机结果；
 * 链节点完成后按真实完成时间生成首人期限为完成时间后7天的后继义务；
 * 推进过程记录完成释放、停转、恢复和链后继生成事件。</p>
 *
 * <p>本类只保留单次模拟的多状态搜索协调：分支推进、候选生成、结果装配和任务排序
 * 分别委托{@link OcTimelineBranchExpander}、{@link OcTimelineScheduleCandidateFactory}、
 * {@link OcTimelineSimulationResultFactory}和{@link OcTimelineTaskOrder}。
 * 跨事件匹配采用有界多状态搜索：对每个关键事件以不同成员—岗位匹配展开有限的
 * 候选状态，用{@link OcTimelineStatePruner}按剩余义务签名等价、稀缺岗位覆盖等维度
 * 做支配剪枝和状态上限控制；状态上限或展开预算耗尽时结果标记搜索预算截断，
 * 由上层映射为{@code UNPROVEN_SEARCH_BUDGET}，不得误写为已证明不可行或卡死。
 * 纯内存对象，不访问数据库、HTTP或Redis。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public final class OcTimelineEventScheduler {
    private final OcTimelineStatePruner statePruner = new OcTimelineStatePruner();
    private final OcLiquidityPathVerifier liquidityVerifier = new OcLiquidityPathVerifier();
    private final OcTimelineBranchExpander branchExpander;
    private final OcTimelineSimulationResultFactory resultFactory =
            new OcTimelineSimulationResultFactory(liquidityVerifier);
    private final OcTimelineTaskOrder taskOrder = new OcTimelineTaskOrder();
    private final int maxTaskExpansions;

    /**
     * 创建使用默认搜索预算的时间线事件推进器。
     */
    public OcTimelineEventScheduler() {
        this(OcSearchBudgetLimits.MAX_TASK_EXPANSIONS);
    }

    /**
     * 创建指定义务展开预算的时间线事件推进器，仅供需要人为缩小搜索预算的测试使用。
     *
     * @param maxTaskExpansions 单次模拟允许的义务展开总次数上限
     */
    public OcTimelineEventScheduler(int maxTaskExpansions) {
        this.maxTaskExpansions = maxTaskExpansions;
        this.branchExpander = new OcTimelineBranchExpander(
                new OcTimelineScheduleCandidateFactory(), taskOrder, liquidityVerifier);
    }

    /**
     * 单个随机组合的完整时间线模拟结果。
     *
     * @param feasible                全部必须承接的义务是否均可完整排程
     * @param deterministicFailure    失败是否由岗位能力、人数的确定性矛盾或流动性连续性证明失败引起
     * @param hardObligationFailed    是否存在已投入义务无法履约
     * @param plannedEmptyExpired     是否存在无法在期限前启动的计划内无人OC
     * @param liquidityProof          匿名流动性证明状态：锚点链、成员占用区间和连续路径判定
     * @param pauses                  停转评估列表
     * @param events                  已发生的时间线事件
     * @param maxNewPause             全部义务中的最大单次主动新增停转时长
     * @param searchBudgetExhausted   多状态搜索是否因状态上限或展开预算截断
     * @param matchAlternativesCapped 本次模拟的替代成员—岗位候选是否达到技术预算上限
     * @param timelineValue           本次模拟的真实时间线价值摘要
     */
    public record SimulationResult(
            boolean feasible,
            boolean deterministicFailure,
            boolean hardObligationFailed,
            boolean plannedEmptyExpired,
            LiquidityProof liquidityProof,
            List<OcPauseAssessment> pauses,
            List<OcTimelineEvent> events,
            Duration maxNewPause,
            boolean searchBudgetExhausted,
            boolean matchAlternativesCapped,
            OcTimelineValueSummary timelineValue) {
    }

    /**
     * 时间线的最小匿名流动性证明状态：当前锚点链、锚点释放资源覆盖的完整义务
     * （成员占用区间）以及贯穿证明窗口的连续完成—释放路径判定。
     * 不包含成员名、岗位或内部排程，不写入对外DTO。
     *
     * @param anchors        已证明完成—释放锚点链，替换标记经成员级验证
     * @param intervals      全部成员占用区间，即锚点资源覆盖的后续完整义务证据
     * @param continuousPath 是否拥有贯穿有限证明窗口的连续完成—释放路径
     */
    public record LiquidityProof(
            List<OcLiquidityAnchor> anchors,
            List<OcMemberInterval> intervals,
            boolean continuousPath) {
    }

    /**
     * 一个条件性随机结果候选根义务及其完整链后继模板。
     *
     * @param obligation 根义务
     * @param successors 根完成后的剩余链节点模板；普通池候选为空
     */
    public record CandidateRoot(
            OcTimelineObligation obligation,
            List<OcTeamDemand> successors) {
    }

    /**
     * 模拟一条完整时间线：事实义务加本轮候选义务，按层级与期限推进。
     *
     * @param request             求解请求
     * @param candidates          本轮条件性随机结果义务；链后继模板挂在根义务上
     * @param allowedPause        单次主动新增停转上限；已启动链节点始终为零
     * @param requirePlannedEmpty 计划内无人OC无法启动时是否判定整体不可行
     * @param proofWindowEnd      有限证明窗口结束时间，约束流动性连续性判定
     * @return 时间线模拟结果
     */
    public SimulationResult simulate(OcRefreshSafetyRequest request,
                                     List<CandidateRoot> candidates,
                                     Duration allowedPause,
                                     boolean requirePlannedEmpty,
                                     LocalDateTime proofWindowEnd) {
        List<Task> initial = new ArrayList<>();
        request.obligations().forEach(obligation -> initial.add(new Task(obligation,
                request.chainSuccessorsByKey().getOrDefault(obligation.key(), List.of()))));
        candidates.forEach(candidate -> initial.add(new Task(candidate.obligation(),
                candidate.successors())));
        initial.sort(taskOrder.taskComparator());
        List<SearchBranch> branches = new ArrayList<>();
        branches.add(new SearchBranch(new OcTimelineState(request), initial, 0, false));
        SearchProgress progress = resultFactory.newProgress(request);
        while (true) {
            SearchBranch complete = branchExpander.pollCompleteBranch(branches, progress, proofWindowEnd);
            if (complete != null) {
                return resultFactory.assembleResult(complete, progress, false,
                        proofWindowEnd);
            }
            if (branches.isEmpty()) {
                return resultFactory.assembleResult(null, progress, false, proofWindowEnd);
            }
            List<SearchBranch> expanded = new ArrayList<>();
            for (SearchBranch branch : branches) {
                if (progress.expansions >= maxTaskExpansions) {
                    return resultFactory.assembleResult(
                            branchExpander.pollCompleteBranch(expanded, progress, proofWindowEnd),
                            progress, true, proofWindowEnd);
                }
                expanded.addAll(branchExpander.expandBranch(branch, allowedPause,
                        requirePlannedEmpty, progress));
            }
            OcTimelineStatePruner.PruneResult<SearchBranch> pruned = pruneBranches(expanded,
                    request);
            branches = pruned.kept();
            if (pruned.truncated()) {
                progress.stateCapTruncated = true;
                return resultFactory.assembleResult(
                        branchExpander.pollCompleteBranch(branches, progress, proofWindowEnd),
                        progress, true, proofWindowEnd);
            }
        }
    }

    /**
     * 用支配剪枝和状态上限裁剪分支集合。支配关系要求剩余义务签名等价，
     * 并保留稀缺岗位覆盖维度，不得仅以成员可用时间合计判定支配。
     *
     * @param branches 待裁剪分支集合
     * @param request  求解请求，用于成员可用性维度
     * @return 裁剪结果
     */
    private OcTimelineStatePruner.PruneResult<SearchBranch> pruneBranches(
            List<SearchBranch> branches, OcRefreshSafetyRequest request) {
        return statePruner.prune(branches, new OcTimelineStatePruner.DominanceDimensions<>(
                SearchBranch::completedCount,
                branch -> branch.state().anchors().size(),
                branch -> pauseNanos(branch.state()),
                branch -> availabilitySum(branch.state(), request),
                this::remainingObligationSignature,
                branch -> scarceSlotCoverage(branch, request)));
    }

    /**
     * 计算分支的剩余义务稳定签名：按义务键排序拼接。
     *
     * @param branch 搜索分支
     * @return 剩余义务签名
     */
    private String remainingObligationSignature(SearchBranch branch) {
        StringJoiner joiner = new StringJoiner("|");
        branch.remaining().stream().map(task -> task.obligation().key())
                .sorted().forEach(joiner::add);
        return joiner.toString();
    }

    /**
     * 计算分支剩余义务的稀缺岗位覆盖数：全部剩余空缺岗位中可用且合格成员数的最小值。
     *
     * @param branch  搜索分支
     * @param request 求解请求
     * @return 稀缺岗位覆盖数；无剩余空缺岗位时为最大值
     */
    private long scarceSlotCoverage(SearchBranch branch, OcRefreshSafetyRequest request) {
        long coverage = Long.MAX_VALUE;
        for (Task task : branch.remaining()) {
            OcTeamDemand demand = task.obligation().demand();
            for (OcPlanSlot slot : demand.getVacantSlots()) {
                coverage = Math.min(coverage, eligibleUsableCount(branch.state(), request,
                        demand, slot));
            }
        }
        return coverage;
    }

    /**
     * 统计状态中可参与指定岗位的合格可用成员数。
     *
     * @param state   时间线状态
     * @param request 求解请求
     * @param demand  队伍需求
     * @param slot    空缺岗位
     * @return 可用且达到岗位门槛的成员数
     */
    private long eligibleUsableCount(OcTimelineState state, OcRefreshSafetyRequest request,
                                     OcTeamDemand demand, OcPlanSlot slot) {
        return request.members().stream()
                .filter(member -> state.isUsable(member.userId()))
                .filter(member -> member.getPassRate(demand.rank(), demand.ocName(),
                        slot.position()) >= slot.requiredPassRate())
                .count();
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
}
