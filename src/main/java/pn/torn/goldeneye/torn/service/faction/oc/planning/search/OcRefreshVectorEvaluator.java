package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcEconomicValueComparator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorSearcher.CombinationBudget;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcPausePolicyEvaluator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcProofWindow;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线刷新向量组合评估器。验证单个刷新向量的全部随机结果组合，
 * 按全部组合做顺序无关聚合：停转层级取最严格层级、保证释放取各组合最早
 * 完整释放中的最晚值、价值取最弱组合证据。纯内存对象，不访问数据库、HTTP或Redis。
 *
 * <p>收益级停转候选的零新增停转基准是一条<b>真实替代时间线</b>：同一快照、
 * 硬义务、岗位能力、链义务与随机结果边界下，由向量搜索先证明可行的最优
 * 零新增停转候选（含零向量），而不是同一随机组合用零暂停重跑的成败结果。
 * 基准不存在或不可比较时fail-closed，不提高收益级停转建议。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
class OcRefreshVectorEvaluator {
    private final OcTimelineEventScheduler scheduler;
    private final OcPausePolicyEvaluator pauseEvaluator;
    private final OcEconomicValueComparator valueComparator = new OcEconomicValueComparator();

    /**
     * 创建组合评估器。
     *
     * @param scheduler      时间线事件推进器
     * @param pauseEvaluator 停转政策评估器
     */
    OcRefreshVectorEvaluator(OcTimelineEventScheduler scheduler,
                             OcPausePolicyEvaluator pauseEvaluator) {
        this.scheduler = scheduler;
        this.pauseEvaluator = pauseEvaluator;
    }

    /**
     * 评估单个刷新向量的全部普通池/高阶池组合，并返回最弱组合聚合结果。
     *
     * @param run    本次评估的共享上下文，含零停转基准替代时间线与搜索遥测
     * @param vector 待评估刷新向量
     * @return 向量评估结果；任一组合提前终止时直接返回终止状态
     */
    VectorEvaluation evaluateVector(EvaluationRun run, OcRefreshVector vector) {
        if (run.proofWindow().newRefreshBlocked()) {
            return VectorEvaluation.failed();
        }
        if (hasMissingTemplate(run.request(), vector)) {
            return VectorEvaluation.failed();
        }
        List<int[]> normalCombinations = nonEmptyCombinations(
                run.request().normalTemplates().size(), vector.normalCount());
        List<int[]> highCombinations = nonEmptyCombinations(
                run.request().highChains().size(), vector.highCount());
        WorstCase worst = new WorstCase();
        for (int[] normalCombination : normalCombinations) {
            for (int[] highCombination : highCombinations) {
                VectorEvaluation terminal = evaluateCombination(run,
                        new Combination(normalCombination, highCombination), worst);
                if (terminal != null) {
                    return terminal;
                }
            }
        }
        return worst.toEvaluation(vector);
    }

    /**
     * 评估一个普通池/高阶池组合：依次尝试零停转、均衡停转和收益停转，
     * 并将可行结果归并进最坏聚合。
     *
     * <p>收益级停转可行时，与{@link EvaluationRun#zeroPauseBaseline()}代表的
     * 零新增停转替代时间线按冻结经济层级比较；只有严格更优才置位
     * {@code pauseCandidateStrictlyBetterThanBaseline}。</p>
     *
     * @param run         本次评估的共享上下文
     * @param combination 当前普通池/高阶池组合
     * @param worst       最坏聚合累加器
     * @return 终止结果；组合已正常归并时返回null
     */
    private VectorEvaluation evaluateCombination(EvaluationRun run, Combination combination,
                                                 WorstCase worst) {
        if (System.nanoTime() >= run.deadline()) {
            return VectorEvaluation.timeout();
        }
        if (!run.budget().tryConsume()) {
            return VectorEvaluation.budgetExhausted();
        }
        List<CandidateRoot> roots = candidateRoots(run.request(), combination.normalCombination(),
                combination.highCombination());
        SimulationResult zeroBaseline = simulate(run, roots, Duration.ZERO);
        if (zeroBaseline.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(run, zeroBaseline, roots, worst, combination);
        }
        if (zeroBaseline.feasible()) {
            mergeWorstCase(worst, zeroBaseline, null, roots,
                    run.evidenceByTemplate(), SafeCandidate.PauseTier.ZERO_PAUSE);
            return null;
        }
        SimulationResult balanced = simulate(run, roots,
                OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE);
        if (balanced.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(run, balanced, roots, worst, combination);
        }
        if (balanced.feasible()) {
            mergeWorstCase(worst, balanced, null, roots,
                    run.evidenceByTemplate(), SafeCandidate.PauseTier.WITHIN_BALANCED);
            return null;
        }
        SimulationResult profit = simulate(run, roots,
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE);
        if (profit.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(run, profit, roots, worst, combination);
        }
        if (profit.feasible()) {
            mergeWorstCase(worst, profit, run.zeroPauseBaseline(), roots,
                    run.evidenceByTemplate(), SafeCandidate.PauseTier.WITHIN_PROFIT);
            return null;
        }
        return VectorEvaluation.failed();
    }

    /**
     * 组合级预算耗尽但当前模拟结果可行时，先把可行候选归并进最坏聚合，
     * 再返回带候选的预算耗尽结果；结果不可行时直接返回无候选的预算耗尽。
     *
     * @param run         本次评估的共享上下文
     * @param result      当前模拟结果
     * @param roots       组合根义务
     * @param worst       最坏聚合累加器
     * @param combination 当前普通池/高阶池组合
     * @return 预算耗尽评估结果
     */
    private VectorEvaluation budgetExhaustedWithCandidate(EvaluationRun run,
                                                          SimulationResult result,
                                                          List<CandidateRoot> roots,
                                                          WorstCase worst,
                                                          Combination combination) {
        if (result.feasible()) {
            mergeWorstCase(worst, result, run.zeroPauseBaseline(), roots,
                    run.evidenceByTemplate(), tierFor(result));
            return new VectorEvaluation(VectorEvaluation.Status.BUDGET_EXHAUSTED,
                    worst.toEvaluation(combination.vector()).candidate());
        }
        return VectorEvaluation.budgetExhausted();
    }

    /**
     * 按指定允许停转时长模拟组合根义务，并记录匿名搜索遥测。
     *
     * @param run          本次评估的共享上下文
     * @param roots        组合根义务
     * @param allowedPause 允许的最大新增停转
     * @return 模拟结果
     */
    private SimulationResult simulate(EvaluationRun run, List<CandidateRoot> roots,
                                      Duration allowedPause) {
        SimulationResult result = scheduler.simulate(run.request(), roots, allowedPause,
                true, run.proofWindow().proofWindowEnd());
        run.metrics().accumulate(result);
        return result;
    }

    /**
     * 把单个组合的模拟结果归并进最坏聚合；收益级停转还须与零新增停转
     * 替代时间线基准比较。
     *
     * <p>基准不是当前组合的零暂停重跑结果：当组合在零停转下本就可行时
     * 层级为零停转，无需比较；只有需要收益级停转时，才与本次搜索先证明的
     * 最优零新增停转替代时间线比较。基准为null表示不存在可行的零停转
     * 替代时间线，fail-closed不提高建议。</p>
     *
     * @param worst              最坏聚合累加器
     * @param result             当前组合模拟结果
     * @param zeroPauseBaseline  零新增停转替代时间线摘要；非收益级或比较不可用时为null
     * @param roots              组合根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param tier               当前组合所需的停转层级
     */
    private void mergeWorstCase(WorstCase worst, SimulationResult result,
                                OcTimelineValueSummary zeroPauseBaseline,
                                List<CandidateRoot> roots,
                                Map<String, OcValueEvidence> evidenceByTemplate,
                                SafeCandidate.PauseTier tier) {
        worst.tier = maxTier(worst.tier, tier);
        OcValueEvidence staticEvidence = combinationEvidence(roots, evidenceByTemplate);
        OcTimelineValueSummary summary = mergeSummary(result.timelineValue(),
                staticEvidence);
        worst.mergeValue(summary);
        LocalDateTime completion = earliestCompletion(result.events());
        worst.mergeRelease(completion);
        worst.minAnchorCount = Math.min(worst.minAnchorCount,
                result.liquidityProof().anchors().size());
        worst.level = weakerLevel(worst.level, staticEvidence.level());
        if (tier == SafeCandidate.PauseTier.WITHIN_PROFIT) {
            boolean comparable = zeroPauseBaseline != null;
            worst.zeroPauseBaselineComparable &= comparable;
            worst.pauseCandidateStrictlyBetter &= comparable
                    && valueComparator.isStrictlyBetterThanZeroPauseBaseline(summary,
                    zeroPauseBaseline);
        }
    }

    /**
     * 用静态证据覆盖模拟摘要中的金额与先验字段，保留实际人天、实际停转、
     * 既有延迟、可避免过期和保证释放等模拟事实。
     *
     * @param actual         模拟实际时间线价值摘要；缺失时取空摘要
     * @param staticEvidence 组合静态价值证据
     * @return 合并后的时间线价值摘要
     */
    private OcTimelineValueSummary mergeSummary(OcTimelineValueSummary actual,
                                                OcValueEvidence staticEvidence) {
        if (actual == null) {
            actual = OcTimelineValueSummary.empty();
        }
        return new OcTimelineValueSummary(
                staticEvidence.totalValue(),
                actual.actualIncrementalMemberDays(),
                actual.actualNewPause(),
                actual.existingObligationDelay(),
                actual.avoidableExpiryPressure(),
                actual.guaranteedReleaseAt(),
                staticEvidence.highestRank(),
                staticEvidence.totalRequiredMembers(),
                staticEvidence.chainNodeCount(),
                staticEvidence.level());
    }

    /**
     * 计算事件流中的最早完整释放时间。
     *
     * @param events 模拟产生的事件
     * @return 最早完整释放时间；无完整释放事件时返回null
     */
    private LocalDateTime earliestCompletion(List<OcTimelineEvent> events) {
        return events.stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.COMPLETION_RELEASE)
                .map(OcTimelineEvent::eventTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 根据组合次数构造普通模板根义务与高阶链根义务。
     *
     * @param request           求解请求
     * @param normalCombination 普通池各模板出现次数
     * @param highCombination   高阶池各链出现次数
     * @return 组合根义务列表
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
     * 为随机结果模板构造一条匿名条件随机义务。
     *
     * @param template   随机结果模板
     * @param createdAt  义务创建时间
     * @param keyPrefix  匿名义务键前缀
     * @param occurrence 同模板第几次出现
     * @return 匿名条件随机义务
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
     * 聚合组合内全部根义务的静态价值证据：金额求和，层级取最弱，
     * 先验等级取最大，人数与节点数取加和。
     *
     * @param roots              组合根义务
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @return 组合静态价值证据
     */
    private OcValueEvidence combinationEvidence(List<CandidateRoot> roots,
                                                Map<String, OcValueEvidence> evidenceByTemplate) {
        if (roots.isEmpty()) {
            return new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD, BigDecimal.ZERO,
                    0, null, true, 0, 0, 1);
        }
        BigDecimal totalValue = BigDecimal.ZERO;
        int memberDays = 0;
        int highestRank = 0;
        int totalMembers = 0;
        int nodeCount = 0;
        OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;
        boolean allValued = true;
        for (CandidateRoot root : roots) {
            OcValueEvidence evidence = evidence(evidenceByTemplate, root);
            if (evidence == null) {
                evidence = new OcValueEvidence(OcValueEvidence.Level.INSUFFICIENT, null,
                        0, null, false, 0, 0, 1);
            }
            level = weakerLevel(level, evidence.level());
            highestRank = Math.max(highestRank, evidence.highestRank());
            totalMembers += evidence.totalRequiredMembers();
            nodeCount += evidence.chainNodeCount();
            memberDays += evidence.incrementalMemberDays();
            if (evidence.totalValue() == null) {
                allValued = false;
            } else {
                totalValue = totalValue.add(evidence.totalValue());
            }
        }
        return new OcValueEvidence(level, allValued ? totalValue : null, memberDays, null,
                !roots.isEmpty() && level != OcValueEvidence.Level.INSUFFICIENT,
                highestRank, totalMembers, nodeCount);
    }

    /**
     * 查找单个根义务的静态价值证据。
     *
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param root               候选根义务
     * @return 普通模板使用本体键；高阶链使用chain前缀键；查无时返回null
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
     * 取两个证据层级中较弱者。
     *
     * @param left  左证据层级
     * @param right 右证据层级
     * @return 较弱层级
     */
    private OcValueEvidence.Level weakerLevel(OcValueEvidence.Level left,
                                              OcValueEvidence.Level right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    /**
     * 取两个停转层级中更严格者。
     *
     * @param current 当前最严格层级
     * @param tier    待比较层级
     * @return 更严格层级
     */
    private SafeCandidate.PauseTier maxTier(SafeCandidate.PauseTier current,
                                            SafeCandidate.PauseTier tier) {
        return tier.compareTo(current) > 0 ? tier : current;
    }

    /**
     * 依据最大新增停转推导所需停转层级。
     *
     * @param result 模拟结果
     * @return 所需停转层级
     */
    private SafeCandidate.PauseTier tierFor(SimulationResult result) {
        if (pauseEvaluator.requiresProfitTier(result.maxNewPause())) {
            return SafeCandidate.PauseTier.WITHIN_PROFIT;
        }
        if (pauseEvaluator.requiresBalancedTier(result.maxNewPause())) {
            return SafeCandidate.PauseTier.WITHIN_BALANCED;
        }
        return SafeCandidate.PauseTier.ZERO_PAUSE;
    }

    /**
     * 判断刷新向量要求的模板或链是否缺失。
     *
     * @param request 求解请求
     * @param vector  待评估刷新向量
     * @return 模板缺失时返回true
     */
    private boolean hasMissingTemplate(OcRefreshSafetyRequest request, OcRefreshVector vector) {
        return vector.normalCount() > 0 && request.normalTemplates().isEmpty()
                || vector.highCount() > 0 && request.highChains().isEmpty();
    }

    /**
     * 返回非空组合列表；无可组合项时返回一个空组合。
     *
     * @param typeCount 类型数
     * @param total     总出现次数
     * @return 非空组合列表
     */
    private List<int[]> nonEmptyCombinations(int typeCount, int total) {
        List<int[]> result = combinations(typeCount, total);
        return result.isEmpty() ? List.of(new int[0]) : result;
    }

    /**
     * 计算把总出现次数分配到指定类型数上的全部分配方案。
     *
     * @param typeCount 类型数
     * @param total     总出现次数
     * @return 全部分配方案；类型数为0且总数不为0时返回空列表
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
     * 递归构建组合分配方案。
     *
     * @param result    结果集合
     * @param current   当前分配方案
     * @param index     当前类型下标
     * @param remaining 剩余待分配次数
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
     * 单次向量评估的共享上下文：求解输入、组合预算、零停转基准替代时间线
     * 与匿名搜索遥测。基准由搜索器在评估当前向量前从已证明候选中选出。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param deadline           求解截止纳秒时间
     * @param budget             组合评估预算
     * @param proofWindow        有限证明窗口
     * @param zeroPauseBaseline  当前搜索已证明的最优零新增停转替代时间线摘要；不存在时为null
     * @param metrics            匿名搜索遥测累加器
     */
    record EvaluationRun(
            OcRefreshSafetyRequest request,
            Map<String, OcValueEvidence> evidenceByTemplate,
            long deadline,
            CombinationBudget budget,
            OcProofWindow proofWindow,
            OcTimelineValueSummary zeroPauseBaseline,
            SearchMetrics metrics) {

        /**
         * 用最新零停转基准替换当前上下文中的基准，其余共享状态不变。
         *
         * @param baseline 最新已证明的最优零新增停转替代时间线摘要；不存在时为null
         * @return 替换基准后的评估上下文
         */
        EvaluationRun withZeroPauseBaseline(OcTimelineValueSummary baseline) {
            return new EvaluationRun(request, evidenceByTemplate, deadline, budget,
                    proofWindow, baseline, metrics);
        }
    }

    /**
     * 单次向量搜索的匿名遥测累加器。逐模拟累计预算截断与替代上限命中次数，
     * 只计数不记录明细。
     */
    static final class SearchMetrics {
        private int budgetTruncations;
        private int alternativesCapHits;

        /**
         * 累计一次模拟结果中的预算命中事实。
         *
         * @param result 单次模拟结果
         */
        void accumulate(SimulationResult result) {
            if (result.searchBudgetExhausted()) {
                budgetTruncations++;
            }
            if (result.matchAlternativesCapped()) {
                alternativesCapHits++;
            }
        }

        int budgetTruncations() {
            return budgetTruncations;
        }

        int alternativesCapHits() {
            return alternativesCapHits;
        }
    }

    /**
     * 普通池与高阶池的组合选择；数组内容相同即视为同一组合。
     *
     * @param normalCombination 普通池各模板出现次数
     * @param highCombination   高阶池各链出现次数
     */
    private record Combination(int[] normalCombination, int[] highCombination) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Combination(
                    int[] otherNormalCombination,
                    int[] otherHighCombination
            ))) {
                return false;
            }
            return java.util.Arrays.equals(normalCombination, otherNormalCombination)
                    && java.util.Arrays.equals(highCombination, otherHighCombination);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(normalCombination);
            result = 31 * result + java.util.Arrays.hashCode(highCombination);
            return result;
        }

        @Override
        public String toString() {
            return "Combination{normalCombination="
                    + java.util.Arrays.toString(normalCombination)
                    + ", highCombination=" + java.util.Arrays.toString(highCombination) + '}';
        }

        /**
         * 计算组合对应的刷新向量：两个池的出现次数分别求和。
         *
         * @return 组合刷新向量
         */
        private OcRefreshVector vector() {
            return new OcRefreshVector(java.util.Arrays.stream(normalCombination).sum(),
                    java.util.Arrays.stream(highCombination).sum());
        }
    }

    /**
     * 单个刷新向量的组合评估结果。
     *
     * @param status    评估状态
     * @param candidate 评估出的安全候选；非SAFE或预算耗尽无候选时为null
     */
    record VectorEvaluation(
            Status status,
            SafeCandidate candidate) {
        /**
         * 向量评估状态。
         */
        enum Status {
            SAFE, FAILED, TIMEOUT, BUDGET_EXHAUSTED
        }

        /**
         * 构造评估失败结果。
         *
         * @return 失败结果
         */
        private static VectorEvaluation failed() {
            return new VectorEvaluation(Status.FAILED, null);
        }

        /**
         * 构造评估超时结果。
         *
         * @return 超时结果
         */
        private static VectorEvaluation timeout() {
            return new VectorEvaluation(Status.TIMEOUT, null);
        }

        /**
         * 构造组合评估预算耗尽结果。
         *
         * @return 预算耗尽结果
         */
        private static VectorEvaluation budgetExhausted() {
            return new VectorEvaluation(Status.BUDGET_EXHAUSTED, null);
        }
    }

    /**
     * 全部组合的最坏情况聚合器，负责跨组合取最严格层级、最弱价值证据、
     * 最晚保证释放和最小锚点数。
     */
    private static final class WorstCase {
        private SafeCandidate.PauseTier tier = SafeCandidate.PauseTier.ZERO_PAUSE;
        private BigDecimal worstValue = null;
        private int worstMemberDays = 0;
        private Duration worstActualNewPause = Duration.ZERO;
        private Duration worstExistingDelay = Duration.ZERO;
        private boolean anyAvoidableExpiry = true;
        private LocalDateTime latestEarliestRelease = null;
        private boolean anyReleaseMissing = false;
        private int minAnchorCount = Integer.MAX_VALUE;
        private OcValueEvidence.Level level = OcValueEvidence.Level.OBSERVED_REWARD;
        private int highestRank = 0;
        private int totalMembers = 0;
        private int nodeCount = 0;
        private boolean zeroPauseBaselineComparable = true;
        private boolean pauseCandidateStrictlyBetter = true;

        /**
         * 归并单个组合的时间线价值摘要：金额取最小，人天、停转和延迟取最大，
         * 可避免过期取逻辑与，先验字段取最强边界。
         *
         * @param summary 单个组合的时间线价值摘要
         */
        private void mergeValue(OcTimelineValueSummary summary) {
            BigDecimal value = summary.monetaryValue();
            if (value == null) {
                worstValue = null;
            } else if (worstValue == null) {
                worstValue = value;
            } else {
                worstValue = worstValue.min(value);
            }
            worstMemberDays = Math.max(worstMemberDays,
                    summary.actualIncrementalMemberDays());
            if (summary.actualNewPause().compareTo(worstActualNewPause) > 0) {
                worstActualNewPause = summary.actualNewPause();
            }
            if (summary.existingObligationDelay().compareTo(worstExistingDelay) > 0) {
                worstExistingDelay = summary.existingObligationDelay();
            }
            anyAvoidableExpiry &= summary.avoidableExpiryPressure();
            highestRank = Math.max(highestRank, summary.highestRank());
            totalMembers = Math.max(totalMembers, summary.totalRequiredMembers());
            nodeCount = Math.max(nodeCount, summary.chainNodeCount());
        }

        /**
         * 归并单个组合的最早完整释放时间；任一组合无释放事件时整体置为null。
         *
         * @param completion 单个组合的最早完整释放时间；无释放事件时为null
         */
        private void mergeRelease(LocalDateTime completion) {
            if (completion == null) {
                anyReleaseMissing = true;
                return;
            }
            if (latestEarliestRelease == null || completion.isAfter(latestEarliestRelease)) {
                latestEarliestRelease = completion;
            }
        }

        /**
         * 将最坏聚合结果转换为安全候选评估结果。
         *
         * @param vector 待评估刷新向量
         * @return 安全评估结果
         */
        private VectorEvaluation toEvaluation(OcRefreshVector vector) {
            int anchorCount = minAnchorCount == Integer.MAX_VALUE ? 0 : minAnchorCount;
            LocalDateTime guaranteedRelease = anyReleaseMissing
                    ? null : latestEarliestRelease;
            OcTimelineValueSummary summary = new OcTimelineValueSummary(worstValue,
                    worstMemberDays, worstActualNewPause, worstExistingDelay,
                    anyAvoidableExpiry, guaranteedRelease, highestRank, totalMembers,
                    nodeCount, level);
            return new VectorEvaluation(VectorEvaluation.Status.SAFE,
                    new SafeCandidate(vector, tier, summary, anchorCount, level,
                            zeroPauseBaselineComparable, pauseCandidateStrictlyBetter));
        }
    }
}
