package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcVectorSearchPort.OcVectorSearchOutcome;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 有限事件时间线规划引擎。在单个快照上编排基线模拟、刷新向量搜索和最终安全评估装配：
 * 向量搜索通过{@link OcVectorSearchPort}契约委托搜索子包完成，单向量全部随机
 * 结果组合的验证与评分由搜索实现内部完成。纯内存，无DB/HTTP/Redis访问。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcTimelinePlanningEngine {
    private final Duration timeout;
    private final OcTimelineEventScheduler scheduler;
    private final OcLiquidityPathVerifier liquidityVerifier = new OcLiquidityPathVerifier();
    private final OcVectorSearchPort vectorSearch;

    /**
     * 创建自带事件推进器和向量搜索的时间线规划引擎。
     *
     * @param timeout   单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     * @param scheduler 时间线事件推进器
     * @param vectorSearch 刷新向量搜索端口实现
     */
    public OcTimelinePlanningEngine(Duration timeout, int maxSearch,
                                    OcTimelineEventScheduler scheduler,
                                    OcVectorSearchPort vectorSearch) {
        this.timeout = timeout;
        this.scheduler = scheduler;
        this.vectorSearch = vectorSearch;
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
        LocalDateTime proofWindowEnd = OcTimelinePolicy.proofWindowEnd(request);
        SimulationResult baseline = scheduler.simulate(request, List.of(),
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, false, proofWindowEnd);
        List<OcLiquidityAnchor> anchors = baseline.liquidityProof().anchors();
        LocalDateTime nextCriticalReleaseAt = liquidityVerifier.nextCriticalReleaseAt(anchors);
        collectBaselineFlags(baseline, inputs);
        if (!baseline.feasible()) {
            return infeasibleBaselineResult(baseline, inputs, nextCriticalReleaseAt,
                    proofWindowEnd);
        }

        OcVectorSearchOutcome outcome = vectorSearch.search(request, evidenceByTemplate, deadline);
        boolean timedOut = outcome.timedOut();
        boolean budgetExhausted = outcome.budgetExhausted()
                || baseline.searchBudgetExhausted();
        boolean touchesLimit = vectorSearch.touchesSearchLimit(outcome.candidates());
        boolean lowerBound = timedOut || budgetExhausted || touchesLimit;
        recordLowerBoundReason(timedOut, budgetExhausted, touchesLimit, inputs);
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                configurationStatus, proofStatus(outcome, budgetExhausted),
                inputs.riskFlags(), lowerBound,
                inputs.reasonCodes(), anchors, nextCriticalReleaseAt,
                proofWindowEnd);
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
            inputs.warnings().add("时间线求解达到组合或状态搜索预算，仅返回已证明安全下界");
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
     * @param proofWindowEnd        证明窗口结束时间
     * @return 无安全候选的求解结果
     */
    private OcRefreshSafetyResult infeasibleBaselineResult(SimulationResult baseline,
                                                           AssessmentInputs inputs,
                                                           LocalDateTime nextCriticalReleaseAt,
                                                           LocalDateTime proofWindowEnd) {
        if (baseline.searchBudgetExhausted()) {
            inputs.warnings().add("基线时间线搜索达到状态或展开预算，仅返回未证明结果");
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        } else if (baseline.deterministicFailure()) {
            collectDeterministicFailureReasons(baseline, inputs);
        } else {
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
            inputs.warnings().add("当前预算内未证明存在可行时间线，建议已保守降为0");
        }
        if (baseline.hardObligationFailed() && !baseline.searchBudgetExhausted()) {
            inputs.riskFlags().add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.COMMITTED_CHAIN_BLOCKED);
        }
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                inputs.configurationStatus(),
                baselineProofStatus(baseline),
                inputs.riskFlags(), baseline.searchBudgetExhausted(), inputs.reasonCodes(),
                baseline.liquidityProof().anchors(),
                nextCriticalReleaseAt, proofWindowEnd);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - inputs.startedAt()).toMillis();
        return new OcRefreshSafetyResult(assessment, List.of(),
                baseline.searchBudgetExhausted(), elapsedMillis,
                inputs.warnings());
    }

    /**
     * 判定基线不可行时的证明状态：搜索预算截断只能未证明，不得误写为不可行。
     *
     * @param baseline 基线模拟结果
     * @return 证明状态
     */
    private OcProofStatusEnum baselineProofStatus(SimulationResult baseline) {
        if (baseline.searchBudgetExhausted()) {
            return OcProofStatusEnum.UNPROVEN_SEARCH_BUDGET;
        }
        return baseline.deterministicFailure()
                ? OcProofStatusEnum.PROVEN_INFEASIBLE
                : OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS;
    }

    /**
     * 记录确定性矛盾时的卡死风险与原因码。流动性连续路径在成员级证明中断裂，
     * 或不存在任何完整释放锚点时，才判定卡死风险。
     *
     * @param baseline 基线模拟结果
     * @param inputs   评估输入
     */
    private void collectDeterministicFailureReasons(SimulationResult baseline,
                                                    AssessmentInputs inputs) {
        if (!liquidityVerifier.hasContinuousAnchor(baseline.liquidityProof().anchors())
                || !baseline.liquidityProof().continuousPath()) {
            inputs.riskFlags().add(OcRiskFlagEnum.DEADLOCK_RISK);
            inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_REPLACEMENT_LIQUIDITY_ANCHOR);
        }
        inputs.reasonCodes().add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
    }

    /**
     * 根据搜索结果判定证明状态。多状态搜索预算截断时即使存在安全候选，
     * 也不得声称已证明安全，只能作为已证明下界返回。
     *
     * @param outcome         向量搜索结果契约
     * @param budgetExhausted 搜索或基线是否达到状态预算
     * @return 证明状态
     */
    private OcProofStatusEnum proofStatus(OcVectorSearchOutcome outcome, boolean budgetExhausted) {
        if (outcome.timedOut()) {
            return OcProofStatusEnum.UNPROVEN_TIMEOUT;
        }
        if (budgetExhausted) {
            return OcProofStatusEnum.UNPROVEN_SEARCH_BUDGET;
        }
        if (!outcome.candidates().isEmpty()) {
            return OcProofStatusEnum.PROVEN_SAFE;
        }
        return OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS;
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
