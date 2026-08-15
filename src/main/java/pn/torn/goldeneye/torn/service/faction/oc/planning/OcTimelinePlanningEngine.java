package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcRefreshVectorSearcher.SearchOutcome;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.SimulationResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 有限事件时间线规划引擎。在单个快照上编排基线模拟、刷新向量搜索和最终安全评估装配：
 * 向量搜索由{@link OcRefreshVectorSearcher}完成，单向量全部随机结果组合的验证与评分
 * 由{@link OcRefreshVectorEvaluator}完成。纯内存，无DB/HTTP/Redis访问。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcTimelinePlanningEngine {
    private final Duration timeout;
    private final OcTimelineEventScheduler scheduler = new OcTimelineEventScheduler();
    private final OcLiquidityPathVerifier liquidityVerifier = new OcLiquidityPathVerifier();
    private final OcRefreshVectorSearcher searcher;

    /**
     * 创建时间线规划引擎。
     *
     * @param timeout   单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     */
    public OcTimelinePlanningEngine(Duration timeout, int maxSearch) {
        this.timeout = timeout;
        this.searcher = new OcRefreshVectorSearcher(maxSearch,
                new OcRefreshVectorEvaluator(scheduler, new OcPausePolicyEvaluator()));
    }

    /**
     * 求解普通池与高阶池的联合安全候选集合。
     *
     * @param request             求解请求
     * @param evidenceByTemplate  按模板键索引的价值证据；高阶链使用chain前缀键
     * @param configurationStatus 配置状态
     * @return 含安全评估与已评分候选向量的求解结果
     */
    public OcRefreshSafetyResult solve(OcRefreshSafetyRequest request,
                                       Map<String, OcValueEvidence> evidenceByTemplate,
                                       OcConfigurationStatusEnum configurationStatus) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        AssessmentInputs inputs = new AssessmentInputs(request, configurationStatus, startedAt);
        SimulationResult baseline = scheduler.simulate(request, List.of(),
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, false);
        List<OcLiquidityAnchor> anchors = baseline.anchors();
        LocalDateTime nextCriticalReleaseAt = liquidityVerifier.nextCriticalReleaseAt(anchors);
        collectBaselineFlags(baseline, inputs);
        if (!baseline.feasible()) {
            return infeasibleBaselineResult(baseline, inputs, nextCriticalReleaseAt);
        }

        SearchOutcome outcome = searcher.search(request, evidenceByTemplate, deadline);
        boolean timedOut = outcome.timedOut();
        boolean budgetExhausted = outcome.budgetExhausted();
        boolean touchesLimit = searcher.touchesSearchLimit(outcome.candidates());
        boolean lowerBound = timedOut || budgetExhausted || touchesLimit;
        recordLowerBoundReason(timedOut, budgetExhausted, touchesLimit, inputs);
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                configurationStatus, proofStatus(outcome), inputs.riskFlags(), lowerBound,
                inputs.reasonCodes(), anchors, nextCriticalReleaseAt,
                latestReplanAt(request));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return new OcRefreshSafetyResult(assessment, outcome.candidates(), lowerBound,
                elapsedMillis, inputs.warnings());
    }

    /**
     * 记录基线模拟的风险标记与原因码。
     *
     * @param baseline 基线模拟结果
     * @param inputs   评估输入
     */
    private void collectBaselineFlags(SimulationResult baseline, AssessmentInputs inputs) {
        if (!baseline.pauses().isEmpty()) {
            inputs.riskFlags().add(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT);
        }
        if (baseline.plannedEmptyExpired()) {
            inputs.riskFlags().add(OcRiskFlagEnum.EMPTY_OC_EXPIRY_PRESSURE);
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
        }
    }

    /**
     * 记录仅返回已证明安全下界的原因与警告。
     *
     * @param timedOut        是否达到时间预算
     * @param budgetExhausted 是否达到组合评估预算
     * @param touchesLimit    是否触及单池搜索上限
     * @param inputs          评估输入
     */
    private void recordLowerBoundReason(boolean timedOut, boolean budgetExhausted,
                                        boolean touchesLimit, AssessmentInputs inputs) {
        if (timedOut) {
            inputs.warnings().add("时间线求解达到时间预算，仅返回已证明安全下界");
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        } else if (budgetExhausted) {
            inputs.warnings().add("时间线求解达到组合评估预算，仅返回已证明安全下界");
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        } else if (touchesLimit) {
            inputs.warnings().add("时间线求解达到搜索上限，仅返回已证明安全下界");
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        }
    }

    /**
     * 构造基线不可行时的求解结果。
     *
     * @param baseline              基线模拟结果
     * @param inputs                评估输入
     * @param nextCriticalReleaseAt 下一关键释放时间
     * @return 无安全候选的求解结果
     */
    private OcRefreshSafetyResult infeasibleBaselineResult(SimulationResult baseline,
                                                           AssessmentInputs inputs,
                                                           LocalDateTime nextCriticalReleaseAt) {
        if (baseline.deterministicFailure()) {
            collectDeterministicFailureReasons(baseline, inputs);
        } else {
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
            inputs.warnings().add("当前预算内未证明存在可行时间线，建议已保守降为0");
        }
        if (baseline.hardObligationFailed()) {
            inputs.riskFlags().add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.COMMITTED_CHAIN_BLOCKED);
        }
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                inputs.configurationStatus(),
                baseline.deterministicFailure()
                        ? OcProofStatusEnum.PROVEN_INFEASIBLE
                        : OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS,
                inputs.riskFlags(), false, inputs.reasonCodes(), baseline.anchors(),
                nextCriticalReleaseAt, latestReplanAt(inputs.request()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - inputs.startedAt()).toMillis();
        return new OcRefreshSafetyResult(assessment, List.of(), false, elapsedMillis,
                inputs.warnings());
    }

    /**
     * 记录确定性矛盾时的卡死风险与原因码。
     *
     * @param baseline 基线模拟结果
     * @param inputs   评估输入
     */
    private void collectDeterministicFailureReasons(SimulationResult baseline,
                                                    AssessmentInputs inputs) {
        if (!liquidityVerifier.hasContinuousAnchor(baseline.anchors())) {
            inputs.riskFlags().add(OcRiskFlagEnum.DEADLOCK_RISK);
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_REPLACEMENT_LIQUIDITY_ANCHOR);
        }
        inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
    }

    /**
     * 根据搜索结果判定证明状态。
     *
     * @param outcome 向量搜索结果
     * @return 证明状态
     */
    private OcProofStatusEnum proofStatus(SearchOutcome outcome) {
        if (outcome.timedOut()) {
            return OcProofStatusEnum.UNPROVEN_TIMEOUT;
        }
        if (outcome.budgetExhausted()) {
            return OcProofStatusEnum.UNPROVEN_SEARCH_BUDGET;
        }
        if (!outcome.candidates().isEmpty()) {
            return OcProofStatusEnum.PROVEN_SAFE;
        }
        return OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS;
    }

    /**
     * 计算证明窗口结束时间：最早业务边界减操作提前量。
     *
     * @param request 求解请求
     * @return 证明窗口结束时间
     */
    private LocalDateTime latestReplanAt(OcRefreshSafetyRequest request) {
        List<LocalDateTime> boundaries = new ArrayList<>();
        request.obligations().forEach(obligation -> {
            if (obligation.firstJoinDeadline() != null) {
                boundaries.add(obligation.firstJoinDeadline());
            }
            LocalDateTime readyAt = obligation.demand().readyAt();
            if (readyAt != null && readyAt.isAfter(request.planningTime())) {
                boundaries.add(readyAt);
            }
        });
        return boundaries.stream().min(LocalDateTime::compareTo)
                .map(boundary -> boundary.minus(OcTimelinePolicy.REPLAN_LEAD))
                .orElse(request.planningTime().plusDays(1));
    }

    /**
     * 构造安全评估所需的可变输入。
     *
     * @param request             求解请求
     * @param configurationStatus 配置状态
     * @param startedAt           求解开始纳秒时间
     * @param riskFlags           风险标记集合
     * @param reasonCodes         原因码集合
     * @param warnings            求解警告
     */
    private record AssessmentInputs(
            OcRefreshSafetyRequest request,
            OcConfigurationStatusEnum configurationStatus,
            long startedAt,
            Set<OcRiskFlagEnum> riskFlags,
            Set<OcPlanReasonCodeEnum> reasonCodes,
            List<String> warnings) {
        private AssessmentInputs(OcRefreshSafetyRequest request,
                                 OcConfigurationStatusEnum configurationStatus,
                                 long startedAt) {
            this(request, configurationStatus, startedAt, new LinkedHashSet<>(),
                    new LinkedHashSet<>(), new ArrayList<>());
        }
    }
}
