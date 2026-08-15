package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 有限事件时间线规划引擎。在单个快照上枚举普通/高阶随机结果联合向量，
 * 对每个结果组合推进全局成员区间、待启动义务和链义务，验证连续完成—释放路径
 * 与停转政策，在已证明安全的候选中按价值证据评分。纯内存，无DB/HTTP/Redis访问。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcTimelinePlanningEngine {
    /**
     * 组合评估预算：以确定性计数截断搜索，保证同一快照结果确定；时间预算仅作兜底。
     */
    private static final int MAX_COMBINATION_EVALUATIONS = 200;

    private final Duration timeout;
    private final int maxSearch;
    private final OcTimelineEventScheduler scheduler = new OcTimelineEventScheduler();
    private final OcLiquidityPathVerifier liquidityVerifier = new OcLiquidityPathVerifier();
    private final OcPausePolicyEvaluator pauseEvaluator = new OcPausePolicyEvaluator();

    /**
     * 创建时间线规划引擎。
     *
     * @param timeout   单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     */
    public OcTimelinePlanningEngine(Duration timeout, int maxSearch) {
        this.timeout = timeout;
        this.maxSearch = maxSearch;
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

        SearchOutcome outcome = searchVectors(request, evidenceByTemplate, deadline);
        boolean timedOut = outcome.timedOut();
        boolean budgetExhausted = outcome.budgetExhausted();
        boolean touchesLimit = touchesSearchLimit(outcome.candidates());
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
     * 按总刷新次数递增搜索全部普通池和高阶池向量。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param deadline           求解截止纳秒时间
     * @return 向量搜索结果
     */
    private SearchOutcome searchVectors(OcRefreshSafetyRequest request,
                                        Map<String, OcValueEvidence> evidenceByTemplate,
                                        long deadline) {
        List<OcRefreshSafetyResult.SafeCandidate> safe = new ArrayList<>();
        List<OcRefreshVector> failed = new ArrayList<>();
        CombinationBudget budget = new CombinationBudget(MAX_COMBINATION_EVALUATIONS);
        boolean timedOut = false;
        for (int total = 0; total <= maxSearch * 2 && !timedOut; total++) {
            timedOut = searchTotal(request, evidenceByTemplate, total, deadline, budget,
                    safe, failed);
        }
        return new SearchOutcome(safe, timedOut, budget.exhausted());
    }

    /**
     * 搜索指定总刷新次数下的全部普通/高阶次数分配。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param total              当前总刷新次数
     * @param deadline           求解截止纳秒时间
     * @param budget             组合评估预算
     * @param safe               已证明安全候选输出集合
     * @param failed             已失败向量输出集合
     * @return 是否因时间预算终止
     */
    private boolean searchTotal(OcRefreshSafetyRequest request,
                                Map<String, OcValueEvidence> evidenceByTemplate,
                                int total, long deadline, CombinationBudget budget,
                                List<OcRefreshSafetyResult.SafeCandidate> safe,
                                List<OcRefreshVector> failed) {
        for (int high = Math.max(0, total - maxSearch); high <= Math.min(maxSearch, total);
             high++) {
            StepStatus status = tryVector(request, evidenceByTemplate,
                    new OcRefreshVector(total - high, high), deadline, budget, safe, failed);
            if (status != StepStatus.CONTINUE) {
                return status == StepStatus.STOP_TIMEOUT;
            }
        }
        return false;
    }

    /**
     * 评估单个刷新向量并归类结果。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param vector             待评估刷新向量
     * @param deadline           求解截止纳秒时间
     * @param budget             组合评估预算
     * @param safe               已证明安全候选输出集合
     * @param failed             已失败向量输出集合
     * @return 向量处理结果
     */
    private StepStatus tryVector(OcRefreshSafetyRequest request,
                                 Map<String, OcValueEvidence> evidenceByTemplate,
                                 OcRefreshVector vector, long deadline, CombinationBudget budget,
                                 List<OcRefreshSafetyResult.SafeCandidate> safe,
                                 List<OcRefreshVector> failed) {
        if (hasFailedSubset(vector, failed)) {
            return StepStatus.CONTINUE;
        }
        VectorEvaluation evaluation = evaluateVector(request, evidenceByTemplate, vector,
                deadline, budget);
        if (evaluation.status() == VectorEvaluation.Status.TIMEOUT) {
            return StepStatus.STOP_TIMEOUT;
        }
        if (evaluation.status() == VectorEvaluation.Status.BUDGET_EXHAUSTED) {
            return StepStatus.STOP_BUDGET;
        }
        if (evaluation.status() == VectorEvaluation.Status.FAILED) {
            failed.add(vector);
        } else {
            safe.add(evaluation.candidate());
        }
        return StepStatus.CONTINUE;
    }

    /**
     * 验证单个刷新向量的全部随机结果组合，并确定其最小停转层级和评分。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param vector             待验证刷新向量
     * @param deadline           求解截止纳秒时间
     * @param budget             组合评估预算
     * @return 向量验证结果
     */
    private VectorEvaluation evaluateVector(OcRefreshSafetyRequest request,
                                            Map<String, OcValueEvidence> evidenceByTemplate,
                                            OcRefreshVector vector, long deadline,
                                            CombinationBudget budget) {
        if (hasMissingTemplate(request, vector)) {
            return VectorEvaluation.failed();
        }
        List<int[]> normalCombinations = nonEmptyCombinations(
                request.normalTemplates().size(), vector.normalCount());
        List<int[]> highCombinations = nonEmptyCombinations(
                request.highChains().size(), vector.highCount());
        WorstCase worst = new WorstCase();
        for (int[] normalCombination : normalCombinations) {
            for (int[] highCombination : highCombinations) {
                VectorEvaluation terminal = evaluateCombination(request, evidenceByTemplate,
                        normalCombination, highCombination, deadline, budget, worst);
                if (terminal != null) {
                    return terminal;
                }
            }
        }
        return worst.toEvaluation(vector);
    }

    /**
     * 评估单个随机结果组合并累积最坏评分。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param normalCombination  普通池各模板出现次数
     * @param highCombination    各高阶链出现次数
     * @param deadline           求解截止纳秒时间
     * @param budget             组合评估预算
     * @param worst              最坏评分累积状态
     * @return 终止性验证结果；组合可继续评估时返回null
     */
    private VectorEvaluation evaluateCombination(OcRefreshSafetyRequest request,
                                                 Map<String, OcValueEvidence> evidenceByTemplate,
                                                 int[] normalCombination, int[] highCombination,
                                                 long deadline, CombinationBudget budget,
                                                 WorstCase worst) {
        if (System.nanoTime() >= deadline) {
            return VectorEvaluation.timeout();
        }
        if (!budget.tryConsume()) {
            return VectorEvaluation.budgetExhausted();
        }
        List<CandidateRoot> roots = candidateRoots(request, normalCombination,
                highCombination);
        SimulationResult result = simulateTiered(request, roots);
        if (result == null) {
            return VectorEvaluation.failed();
        }
        mergeWorstCase(worst, result, roots, evidenceByTemplate);
        return null;
    }

    /**
     * 将单个组合的模拟结果合并进最坏评分累积状态。
     *
     * @param worst              最坏评分累积状态
     * @param result             组合模拟结果
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     */
    private void mergeWorstCase(WorstCase worst, SimulationResult result,
                                List<CandidateRoot> roots,
                                Map<String, OcValueEvidence> evidenceByTemplate) {
        worst.tier = maxTier(worst.tier, result);
        worst.worstValue = minEvidence(worst.worstValue,
                combinationValue(roots, evidenceByTemplate));
        worst.anyValued &= combinationValued(roots, evidenceByTemplate);
        worst.worstMemberDays = Math.max(worst.worstMemberDays,
                combinationMemberDays(roots, evidenceByTemplate));
        LocalDateTime completion = earliestCompletion(result.events());
        if (worst.earliestCompletion == null || completion != null
                && completion.isBefore(worst.earliestCompletion)) {
            worst.earliestCompletion = completion;
        }
        worst.minAnchorCount = Math.min(worst.minAnchorCount, result.anchors().size());
        worst.level = minLevel(worst.level, evidenceLevel(roots, evidenceByTemplate));
    }

    /**
     * 从完成释放事件中获取本组合的最早完整释放时间。
     *
     * @param events 时间线事件列表
     * @return 最早完成释放时间；无完成事件时为null
     */
    private LocalDateTime earliestCompletion(List<OcTimelineEvent> events) {
        return events.stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.COMPLETION_RELEASE)
                .map(OcTimelineEvent::eventTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 按停转层级从零到收益上限逐级尝试模拟组合。
     *
     * @param request 求解请求
     * @param roots   随机结果候选根义务
     * @return 第一个可行层级的模拟结果；全部层级不可行时返回null
     */
    private SimulationResult simulateTiered(OcRefreshSafetyRequest request,
                                            List<CandidateRoot> roots) {
        SimulationResult result = scheduler.simulate(request, roots, Duration.ZERO, true);
        if (result.feasible()) {
            return result;
        }
        result = scheduler.simulate(request, roots,
                OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE, true);
        if (result.feasible()) {
            return result;
        }
        result = scheduler.simulate(request, roots,
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, true);
        return result.feasible() ? result : null;
    }

    /**
     * 构造一组随机结果组合对应的候选根义务集合。
     *
     * @param request           求解请求
     * @param normalCombination 普通池各模板出现次数
     * @param highCombination   各高阶链出现次数
     * @return 候选根义务集合
     */
    private List<CandidateRoot> candidateRoots(OcRefreshSafetyRequest request,
                                               int[] normalCombination,
                                               int[] highCombination) {
        List<CandidateRoot> roots = new ArrayList<>();
        for (int index = 0; index < normalCombination.length; index++) {
            OcTeamDemand template = request.normalTemplates().get(index);
            for (int count = 0; count < normalCombination[index]; count++) {
                roots.add(new CandidateRoot(freshObligation(template,
                        request.planningTime(), "tpl"), List.of()));
            }
        }
        for (int chainIndex = 0; chainIndex < highCombination.length; chainIndex++) {
            List<OcTeamDemand> chain = request.highChains().get(chainIndex);
            for (int count = 0; count < highCombination[chainIndex]; count++) {
                OcTimelineObligation root = freshObligation(chain.getFirst(),
                        request.planningTime(), "chain");
                roots.add(new CandidateRoot(root,
                        chain.subList(1, chain.size())));
            }
        }
        return roots;
    }

    /**
     * 根据随机结果模板创建新的无人OC义务。
     *
     * @param template  随机结果模板
     * @param createdAt 创建时间
     * @param keyPrefix 义务键前缀
     * @return 带首人期限的条件性随机结果义务
     */
    private OcTimelineObligation freshObligation(OcTeamDemand template,
                                                 LocalDateTime createdAt, String keyPrefix) {
        LocalDateTime deadline = createdAt.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS);
        OcTeamDemand demand = new OcTeamDemand(0L, template.ocName(), template.rank(), null,
                deadline, template.chain(), template.slots(), Set.of(), Set.of());
        return new OcTimelineObligation(keyPrefix + ":" + template.rank() + ":"
                + template.ocName(), OcTimelineObligation.ObligationKind.CONDITIONAL_RANDOM,
                demand, deadline, null);
    }

    /**
     * 计算组合内全部候选的价值合计；任一候选金额证据不足时返回null。
     *
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 组合价值合计；证据不足时为null
     */
    private BigDecimal combinationValue(List<CandidateRoot> roots,
                                        Map<String, OcValueEvidence> evidenceByTemplate) {
        BigDecimal total = BigDecimal.ZERO;
        for (CandidateRoot root : roots) {
            OcValueEvidence evidence = evidence(evidenceByTemplate, root);
            if (evidence == null || evidence.totalValue() == null) {
                return null;
            }
            total = total.add(evidence.totalValue());
        }
        return total;
    }

    /**
     * 判断组合内全部候选是否均具备金额证据。
     *
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 全部候选均有金额证据时返回true
     */
    private boolean combinationValued(List<CandidateRoot> roots,
                                      Map<String, OcValueEvidence> evidenceByTemplate) {
        return combinationValue(roots, evidenceByTemplate) != null;
    }

    /**
     * 计算组合内全部候选的增量成员人天合计。
     *
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 增量成员人天合计
     */
    private int combinationMemberDays(List<CandidateRoot> roots,
                                      Map<String, OcValueEvidence> evidenceByTemplate) {
        int total = 0;
        for (CandidateRoot root : roots) {
            OcValueEvidence evidence = evidence(evidenceByTemplate, root);
            if (evidence != null) {
                total += evidence.incrementalMemberDays();
            }
        }
        return total;
    }

    /**
     * 获取组合内全部候选的最弱价值证据层级。
     *
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 最弱证据层级
     */
    private OcValueEvidence.Level evidenceLevel(List<CandidateRoot> roots,
                                                Map<String, OcValueEvidence> evidenceByTemplate) {
        OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;
        for (CandidateRoot root : roots) {
            OcValueEvidence evidence = evidence(evidenceByTemplate, root);
            if (evidence != null) {
                level = minLevel(level, evidence.level());
            }
        }
        return level;
    }

    /**
     * 查询候选根义务对应的价值证据；链候选使用chain前缀键。
     *
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param root               候选根义务
     * @return 价值证据；缺失时为null
     */
    private OcValueEvidence evidence(Map<String, OcValueEvidence> evidenceByTemplate,
                                     CandidateRoot root) {
        String ocKey = root.obligation().demand().rank() + ":"
                + root.obligation().demand().ocName();
        return root.successors().isEmpty()
                ? evidenceByTemplate.get(ocKey)
                : evidenceByTemplate.get("chain:" + ocKey);
    }

    /**
     * 取两个证据层级中较弱的一个。
     *
     * @param left  层级一
     * @param right 层级二
     * @return 较弱层级
     */
    private OcValueEvidence.Level minLevel(OcValueEvidence.Level left, OcValueEvidence.Level right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /**
     * 取两个非空金额中较小的一个作为最坏组合价值。
     *
     * @param current   当前最坏价值
     * @param candidate 新组合价值
     * @return 较小价值；任一为null时返回null
     */
    private BigDecimal minEvidence(BigDecimal current, BigDecimal candidate) {
        if (current == null || candidate == null) {
            return null;
        }
        return current.min(candidate);
    }

    /**
     * 合并向量在全部组合下的最小停转层级。
     *
     * @param current 当前层级
     * @param result  组合模拟结果
     * @return 更宽的层级
     */
    private OcRefreshSafetyResult.SafeCandidate.PauseTier maxTier(
            OcRefreshSafetyResult.SafeCandidate.PauseTier current, SimulationResult result) {
        if (pauseEvaluator.requiresProfitTier(result.maxNewPause())) {
            return OcRefreshSafetyResult.SafeCandidate.PauseTier.WITHIN_PROFIT;
        }
        if (pauseEvaluator.requiresBalancedTier(result.maxNewPause())
                || current == OcRefreshSafetyResult.SafeCandidate.PauseTier.WITHIN_PROFIT) {
            return OcRefreshSafetyResult.SafeCandidate.PauseTier.WITHIN_BALANCED;
        }
        return current;
    }

    /**
     * 判断待刷新池是否缺少计划模板。
     *
     * @param request 求解请求
     * @param vector  待验证刷新向量
     * @return 任一正次数刷新池缺少模板时返回true
     */
    private boolean hasMissingTemplate(OcRefreshSafetyRequest request, OcRefreshVector vector) {
        return vector.normalCount() > 0 && request.normalTemplates().isEmpty()
                || vector.highCount() > 0 && request.highChains().isEmpty();
    }

    /**
     * 判断当前向量是否包含已失败的子向量。
     *
     * @param vector 当前刷新向量
     * @param failed 已失败向量集合
     * @return 存在失败子向量时返回true
     */
    private boolean hasFailedSubset(OcRefreshVector vector, List<OcRefreshVector> failed) {
        return failed.stream().anyMatch(item -> item.normalCount() <= vector.normalCount()
                && item.highCount() <= vector.highCount());
    }

    /**
     * 判断已证明安全的候选是否触及单池搜索上限。
     *
     * @param candidates 已证明安全的候选集合
     * @return 任一池次数达到搜索上限时返回true
     */
    private boolean touchesSearchLimit(List<OcRefreshSafetyResult.SafeCandidate> candidates) {
        return candidates.stream().anyMatch(candidate ->
                candidate.vector().normalCount() == maxSearch
                        || candidate.vector().highCount() == maxSearch);
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
     * 计算组合列表，并为零类型零次数场景补充空组合。
     *
     * @param typeCount 随机结果类型数
     * @param total     刷新总次数
     * @return 组合列表
     */
    private List<int[]> nonEmptyCombinations(int typeCount, int total) {
        List<int[]> result = combinations(typeCount, total);
        return result.isEmpty() ? List.of(new int[0]) : result;
    }

    /**
     * 枚举指定次数在随机结果类型之间的全部非负整数分配。
     *
     * @param typeCount 随机结果类型数
     * @param total     刷新总次数
     * @return 计数组合列表
     */
    private List<int[]> combinations(int typeCount, int total) {
        List<int[]> result = new ArrayList<>();
        if (typeCount == 0) {
            if (total == 0) {
                result.add(new int[0]);
            }
            return result;
        }
        buildCombinations(result, new int[typeCount], 0, total);
        return result;
    }

    /**
     * 递归构造随机结果计数组合。
     *
     * @param result    组合结果集合
     * @param current   当前组合缓冲区
     * @param index     当前类型索引
     * @param remaining 尚未分配的次数
     */
    private void buildCombinations(List<int[]> result, int[] current,
                                   int index, int remaining) {
        if (index == current.length - 1) {
            current[index] = remaining;
            result.add(current.clone());
            return;
        }
        for (int value = 0; value <= remaining; value++) {
            current[index] = value;
            buildCombinations(result, current, index + 1, remaining - value);
        }
    }

    /**
     * 单个刷新向量的验证结果。
     *
     * @param status    验证状态
     * @param candidate 已证明安全的候选；仅SAFE状态非空
     */
    private record VectorEvaluation(
            Status status,
            OcRefreshSafetyResult.SafeCandidate candidate) {
        private enum Status {
            SAFE, FAILED, TIMEOUT, BUDGET_EXHAUSTED
        }

        private static VectorEvaluation failed() {
            return new VectorEvaluation(Status.FAILED, null);
        }

        private static VectorEvaluation timeout() {
            return new VectorEvaluation(Status.TIMEOUT, null);
        }

        private static VectorEvaluation budgetExhausted() {
            return new VectorEvaluation(Status.BUDGET_EXHAUSTED, null);
        }
    }

    /**
     * 向量搜索结果。
     *
     * @param candidates      已证明安全的候选集合
     * @param timedOut        是否达到时间预算
     * @param budgetExhausted 是否达到组合评估预算
     */
    private record SearchOutcome(
            List<OcRefreshSafetyResult.SafeCandidate> candidates,
            boolean timedOut,
            boolean budgetExhausted) {
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

    /**
     * 单个向量在向量搜索循环中的处理结果。
     */
    private enum StepStatus {
        /**
         * 继续评估下一个向量。
         */
        CONTINUE,
        /**
         * 达到时间预算，终止搜索。
         */
        STOP_TIMEOUT,
        /**
         * 达到组合评估预算，终止搜索。
         */
        STOP_BUDGET
    }

    /**
     * 向量在全部随机组合下的最坏评分累积状态。
     */
    private static final class WorstCase {
        private OcRefreshSafetyResult.SafeCandidate.PauseTier tier =
                OcRefreshSafetyResult.SafeCandidate.PauseTier.ZERO_PAUSE;
        private BigDecimal worstValue = null;
        private boolean anyValued = true;
        private int worstMemberDays = 0;
        private LocalDateTime earliestCompletion = null;
        private int minAnchorCount = Integer.MAX_VALUE;
        private OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;

        private VectorEvaluation toEvaluation(OcRefreshVector vector) {
            int anchorCount = minAnchorCount == Integer.MAX_VALUE ? 0 : minAnchorCount;
            return new VectorEvaluation(VectorEvaluation.Status.SAFE,
                    new OcRefreshSafetyResult.SafeCandidate(vector, tier,
                            anyValued ? worstValue : null, worstMemberDays,
                            earliestCompletion, anchorCount, level));
        }
    }

    /**
     * 确定性组合评估预算。按评估次数截断，保证同一快照重复求解结果一致。
     */
    private static final class CombinationBudget {
        private int remaining;

        private CombinationBudget(int maxEvaluations) {
            this.remaining = maxEvaluations;
        }

        private boolean tryConsume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        private boolean exhausted() {
            return remaining <= 0;
        }
    }
}
