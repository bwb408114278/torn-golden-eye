package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcConfigurationStatusEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcProofStatusEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRiskFlagEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineObligation;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineSafetyAssessment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final OcReplanWindowCalculator replanCalculator = new OcReplanWindowCalculator();

    /**
     * 创建时间线规划引擎。
     *
     * @param timeout 单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     */
    public OcTimelinePlanningEngine(Duration timeout, int maxSearch) {
        this.timeout = timeout;
        this.maxSearch = maxSearch;
    }

    /**
     * 求解普通池与高阶池的联合安全候选集合。
     *
     * @param request 求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据；高阶链使用chain前缀键
     * @param configurationStatus 配置状态
     * @return 含安全评估与已评分候选向量的求解结果
     */
    public OcRefreshSafetyResult solve(OcRefreshSafetyRequest request,
                                       Map<String, OcValueEvidence> evidenceByTemplate,
                                       OcConfigurationStatusEnum configurationStatus) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        Set<OcRiskFlagEnum> riskFlags = new LinkedHashSet<>();
        Set<OcPlanReasonCodeEnum> reasonCodes = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        SimulationResult baseline = scheduler.simulate(request, List.of(),
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, false);        List<OcLiquidityAnchor> anchors = baseline.anchors();
        LocalDateTime nextCriticalReleaseAt = liquidityVerifier.nextCriticalReleaseAt(anchors);
        if (!baseline.pauses().isEmpty()) {
            riskFlags.add(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT);
        }
        if (baseline.plannedEmptyExpired()) {
            riskFlags.add(OcRiskFlagEnum.EMPTY_OC_EXPIRY_PRESSURE);
            reasonCodes.add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
        }
        if (!baseline.feasible()) {
            return infeasibleBaselineResult(request, baseline, riskFlags, reasonCodes,
                    nextCriticalReleaseAt, configurationStatus, startedAt, warnings);
        }

        SearchOutcome outcome = searchVectors(request, evidenceByTemplate, deadline);
        boolean timedOut = outcome.timedOut();
        boolean budgetExhausted = outcome.budgetExhausted();
        boolean touchesLimit = touchesSearchLimit(outcome.candidates());
        boolean lowerBound = timedOut || budgetExhausted || touchesLimit;
        if (timedOut) {
            warnings.add("时间线求解达到时间预算，仅返回已证明安全下界");
            reasonCodes.add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        } else if (budgetExhausted) {
            warnings.add("时间线求解达到组合评估预算，仅返回已证明安全下界");
            reasonCodes.add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        } else if (touchesLimit) {
            warnings.add("时间线求解达到搜索上限，仅返回已证明安全下界");
            reasonCodes.add(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY);
        }
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                configurationStatus, proofStatus(outcome), riskFlags, lowerBound,
                reasonCodes, anchors, nextCriticalReleaseAt,
                latestReplanAt(request));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return new OcRefreshSafetyResult(assessment, outcome.candidates(), lowerBound,
                elapsedMillis, warnings);
    }

    /**
     * 构造基线不可行时的求解结果。
     *
     * @param request 求解请求
     * @param baseline 基线模拟结果
     * @param riskFlags 风险标记集合
     * @param reasonCodes 原因码集合
     * @param nextCriticalReleaseAt 下一关键释放时间
     * @param configurationStatus 配置状态
     * @param startedAt 求解开始纳秒时间
     * @param warnings 求解警告
     * @return 无安全候选的求解结果
     */
    private OcRefreshSafetyResult infeasibleBaselineResult(
            OcRefreshSafetyRequest request, SimulationResult baseline,
            Set<OcRiskFlagEnum> riskFlags, Set<OcPlanReasonCodeEnum> reasonCodes,
            LocalDateTime nextCriticalReleaseAt,
            OcConfigurationStatusEnum configurationStatus, long startedAt, List<String> warnings) {
        if (baseline.deterministicFailure()) {
            if (!liquidityVerifier.hasContinuousAnchor(baseline.anchors())) {
                riskFlags.add(OcRiskFlagEnum.DEADLOCK_RISK);
                reasonCodes.add(OcPlanReasonCodeEnum.NO_REPLACEMENT_LIQUIDITY_ANCHOR);
            }
            reasonCodes.add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
        } else {
            reasonCodes.add(OcPlanReasonCodeEnum.NO_QUALIFIED_MEMBER_BEFORE_DEADLINE);
            warnings.add("当前预算内未证明存在可行时间线，建议已保守降为0");
        }
        if (baseline.hardObligationFailed()) {
            riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            reasonCodes.add(OcPlanReasonCodeEnum.COMMITTED_CHAIN_BLOCKED);
        }
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                configurationStatus,
                baseline.deterministicFailure()
                        ? OcProofStatusEnum.PROVEN_INFEASIBLE
                        : OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS,
                riskFlags, false, reasonCodes, baseline.anchors(), nextCriticalReleaseAt,
                latestReplanAt(request));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return new OcRefreshSafetyResult(assessment, List.of(), false, elapsedMillis, warnings);
    }

    /**
     * 按总刷新次数递增搜索全部普通池和高阶池向量。
     *
     * @param request 求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param deadline 求解截止纳秒时间
     * @param warnings 求解警告
     * @param reasonCodes 原因码集合
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
            int minHigh = Math.max(0, total - maxSearch);
            int maxHigh = Math.min(maxSearch, total);
            for (int high = minHigh; high <= maxHigh; high++) {
                OcRefreshVector vector = new OcRefreshVector(total - high, high);
                if (hasFailedSubset(vector, failed)) {
                    continue;
                }
                VectorEvaluation evaluation = evaluateVector(request, evidenceByTemplate,
                        vector, deadline, budget);
                if (evaluation.status() == VectorEvaluation.Status.TIMEOUT
                        || evaluation.status() == VectorEvaluation.Status.BUDGET_EXHAUSTED) {
                    timedOut |= evaluation.status() == VectorEvaluation.Status.TIMEOUT;
                    return new SearchOutcome(safe, timedOut, true);
                }
                if (evaluation.status() == VectorEvaluation.Status.FAILED) {
                    failed.add(vector);
                } else {
                    safe.add(evaluation.candidate());
                }
                if (budget.exhausted()) {
                    return new SearchOutcome(safe, timedOut, true);
                }
            }
        }
        return new SearchOutcome(safe, timedOut, false);
    }

    /**
     * 验证单个刷新向量的全部随机结果组合，并确定其最小停转层级和评分。
     *
     * @param request 求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param vector 待验证刷新向量
     * @param deadline 求解截止纳秒时间
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
        OcRefreshSafetyResult.SafeCandidate.PauseTier tier =
                OcRefreshSafetyResult.SafeCandidate.PauseTier.ZERO_PAUSE;
        BigDecimal worstValue = null;
        int worstMemberDays = 0;
        LocalDateTime earliestCompletion = null;
        int minAnchorCount = Integer.MAX_VALUE;
        boolean anyValued = true;
        OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;
        for (int[] normalCombination : normalCombinations) {
            for (int[] highCombination : highCombinations) {
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
                tier = maxTier(tier, result);
                worstValue = minEvidence(worstValue, combinationValue(roots,
                        evidenceByTemplate));
                anyValued &= combinationValued(roots, evidenceByTemplate);
                worstMemberDays = Math.max(worstMemberDays,
                        combinationMemberDays(roots, evidenceByTemplate));
                LocalDateTime completion = liquidityVerifier.nextCriticalReleaseAt(
                        result.anchors());
                earliestCompletion = earliestCompletion == null || completion != null
                        && completion.isBefore(earliestCompletion) ? completion : earliestCompletion;
                minAnchorCount = Math.min(minAnchorCount, result.anchors().size());
                level = level == null ? evidenceLevel(roots, evidenceByTemplate)
                        : minLevel(level, evidenceLevel(roots, evidenceByTemplate));
            }
        }
        if (minAnchorCount == Integer.MAX_VALUE) {
            minAnchorCount = 0;
        }
        return new VectorEvaluation(VectorEvaluation.Status.SAFE,
                new OcRefreshSafetyResult.SafeCandidate(vector,
                        tier, anyValued ? worstValue : null, worstMemberDays, earliestCompletion,
                        minAnchorCount, level));
    }

    /**
     * 按停转层级从零到收益上限逐级尝试模拟组合。
     *
     * @param request 求解请求
     * @param roots 随机结果候选根义务
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
     * @param request 求解请求
     * @param normalCombination 普通池各模板出现次数
     * @param highCombination 各高阶链出现次数
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
     * @param template 随机结果模板
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
     * @param roots 候选根义务
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
     * @param roots 候选根义务
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
     * @param roots 候选根义务
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
     * @param roots 候选根义务
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
     * @param root 候选根义务
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
     * @param left 层级一
     * @param right 层级二
     * @return 较弱层级
     */
    private OcValueEvidence.Level minLevel(OcValueEvidence.Level left, OcValueEvidence.Level right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /**
     * 取两个非空金额中较小的一个作为最坏组合价值。
     *
     * @param current 当前最坏价值
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
     * @param result 组合模拟结果
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
     * @param vector 待验证刷新向量
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
     * @param total 刷新总次数
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
     * @param total 刷新总次数
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
     * @param result 组合结果集合
     * @param current 当前组合缓冲区
     * @param index 当前类型索引
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
     * @param status 验证状态
     * @param candidate 已证明安全的候选；仅SAFE状态非空
     */
    private record VectorEvaluation(Status status,
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
     * @param candidates 已证明安全的候选集合
     * @param timedOut 是否达到时间预算
     * @param budgetExhausted 是否达到组合评估预算
     */
    private record SearchOutcome(List<OcRefreshSafetyResult.SafeCandidate> candidates,
                                 boolean timedOut, boolean budgetExhausted) {
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
