package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcRefreshVectorSearcher.CombinationBudget;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线刷新向量组合评估器。验证单个刷新向量的全部随机结果组合，
 * 确定其最小停转层级并按价值证据评分，取全部组合下的最坏评分。
 * 纯内存对象，不访问数据库、HTTP或Redis。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
class OcRefreshVectorEvaluator {
    private final OcTimelineEventScheduler scheduler;
    private final OcPausePolicyEvaluator pauseEvaluator;

    /**
     * 创建刷新向量组合评估器。
     *
     * @param scheduler      时间线事件推进器
     * @param pauseEvaluator 模式停转政策评估器
     */
    OcRefreshVectorEvaluator(OcTimelineEventScheduler scheduler,
                             OcPausePolicyEvaluator pauseEvaluator) {
        this.scheduler = scheduler;
        this.pauseEvaluator = pauseEvaluator;
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
    VectorEvaluation evaluateVector(OcRefreshSafetyRequest request,
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
        if (result.searchBudgetExhausted()) {
            if (result.feasible()) {
                mergeWorstCase(worst, result, roots, evidenceByTemplate);
                return new VectorEvaluation(VectorEvaluation.Status.BUDGET_EXHAUSTED,
                        worst.toEvaluation(vectorOf(normalCombination, highCombination))
                                .candidate());
            }
            return VectorEvaluation.budgetExhausted();
        }
        if (!result.feasible()) {
            return VectorEvaluation.failed();
        }
        mergeWorstCase(worst, result, roots, evidenceByTemplate);
        return null;
    }

    /**
     * 由普通池与高阶池组合还原刷新向量。
     *
     * @param normalCombination 普通池各模板出现次数
     * @param highCombination   各高阶链出现次数
     * @return 刷新向量
     */
    private OcRefreshVector vectorOf(int[] normalCombination, int[] highCombination) {
        return new OcRefreshVector(java.util.Arrays.stream(normalCombination).sum(),
                java.util.Arrays.stream(highCombination).sum());
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
        worst.anyUsableForAdvice &= combinationUsableForAdvice(roots, evidenceByTemplate);
        worst.worstMemberDays = Math.max(worst.worstMemberDays,
                combinationMemberDays(roots, evidenceByTemplate));
        LocalDateTime completion = earliestCompletion(result.events());
        if (worst.earliestCompletion == null || completion != null
                && completion.isBefore(worst.earliestCompletion)) {
            worst.earliestCompletion = completion;
        }
        worst.minAnchorCount = Math.min(worst.minAnchorCount, result.anchors().size());
        worst.level = weakerLevel(worst.level, evidenceLevel(roots, evidenceByTemplate));
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
     * <p>任一层级达到搜索预算截断即立即返回该结果，由上层映射为
     * {@code UNPROVEN_SEARCH_BUDGET}；全部层级不可行且未截断时返回null。</p>
     *
     * @param request 求解请求
     * @param roots   随机结果候选根义务
     * @return 第一个可行层级或预算截断的模拟结果；全部层级不可行时返回null
     */
    private SimulationResult simulateTiered(OcRefreshSafetyRequest request,
                                            List<CandidateRoot> roots) {
        SimulationResult result = scheduler.simulate(request, roots, Duration.ZERO, true);
        if (result.feasible() || result.searchBudgetExhausted()) {
            return result;
        }
        result = scheduler.simulate(request, roots,
                OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE, true);
        if (result.feasible() || result.searchBudgetExhausted()) {
            return result;
        }
        return scheduler.simulate(request, roots,
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, true);
    }

    /**
     * 构造一组随机结果组合对应的候选根义务集合。同模板多次刷新按出现序号
     * 生成独立义务键，保证事件、停转、锚点和后继义务互不冲突。
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
                        request.planningTime(), "tpl", count), List.of()));
            }
        }
        for (int chainIndex = 0; chainIndex < highCombination.length; chainIndex++) {
            List<OcTeamDemand> chain = request.highChains().get(chainIndex);
            for (int count = 0; count < highCombination[chainIndex]; count++) {
                OcTimelineObligation root = freshObligation(chain.getFirst(),
                        request.planningTime(), "chain", count);
                roots.add(new CandidateRoot(root,
                        chain.subList(1, chain.size())));
            }
        }
        return roots;
    }

    /**
     * 根据随机结果模板创建新的无人OC义务。
     *
     * @param template   随机结果模板
     * @param createdAt  创建时间
     * @param keyPrefix  义务键前缀
     * @param occurrence 同模板在当前组合内的出现序号，从0开始
     * @return 带首人期限的条件性随机结果义务
     */
    private OcTimelineObligation freshObligation(OcTeamDemand template,
                                                 LocalDateTime createdAt, String keyPrefix,
                                                 int occurrence) {
        LocalDateTime deadline = createdAt.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS);
        OcTeamDemand demand = new OcTeamDemand(0L, template.ocName(), template.rank(), null,
                deadline, template.chain(), template.slots(), Set.of(), Set.of());
        return new OcTimelineObligation(keyPrefix + ":" + template.rank() + ":"
                + template.ocName() + ":" + occurrence,
                OcTimelineObligation.ObligationKind.CONDITIONAL_RANDOM,
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
                level = weakerLevel(level, evidence.level());
            }
        }
        return level;
    }

    /**
     * 判断组合内全部候选的证据是否均可用于提高刷新建议。
     *
     * @param roots              候选根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 全部候选证据可用时返回true
     */
    private boolean combinationUsableForAdvice(List<CandidateRoot> roots,
                                               Map<String, OcValueEvidence> evidenceByTemplate) {
        for (CandidateRoot root : roots) {
            OcValueEvidence evidence = evidence(evidenceByTemplate, root);
            if (evidence == null || !evidence.usableForAdviceIncrease()) {
                return false;
            }
        }
        return true;
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
    private OcValueEvidence.Level weakerLevel(OcValueEvidence.Level left,
                                              OcValueEvidence.Level right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    /**
     * 取两个非空金额中较小的一个作为最坏组合价值。
     *
     * <p>首个组合的金额直接作为初始最坏值；任一组合金额证据不足时整体为null。</p>
     *
     * @param current   当前最坏价值；尚未累积时为null
     * @param candidate 新组合价值
     * @return 较小价值；新组合证据不足时为null
     */
    private BigDecimal minEvidence(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return null;
        }
        return current == null ? candidate : current.min(candidate);
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
    record VectorEvaluation(
            Status status,
            OcRefreshSafetyResult.SafeCandidate candidate) {
        enum Status {
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
     * 向量在全部随机组合下的最坏评分累积状态。
     */
    private static final class WorstCase {
        private OcRefreshSafetyResult.SafeCandidate.PauseTier tier =
                OcRefreshSafetyResult.SafeCandidate.PauseTier.ZERO_PAUSE;
        private BigDecimal worstValue = null;
        private boolean anyValued = true;
        private boolean anyUsableForAdvice = true;
        private int worstMemberDays = 0;
        private LocalDateTime earliestCompletion = null;
        private int minAnchorCount = Integer.MAX_VALUE;
        private OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;

        private VectorEvaluation toEvaluation(OcRefreshVector vector) {
            int anchorCount = minAnchorCount == Integer.MAX_VALUE ? 0 : minAnchorCount;
            return new VectorEvaluation(VectorEvaluation.Status.SAFE,
                    new OcRefreshSafetyResult.SafeCandidate(vector, tier,
                            anyValued ? worstValue : null, worstMemberDays,
                            earliestCompletion, anchorCount, level, anyUsableForAdvice));
        }
    }
}
