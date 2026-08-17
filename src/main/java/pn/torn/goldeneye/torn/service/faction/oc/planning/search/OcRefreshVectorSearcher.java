package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcEconomicValueComparator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorEvaluator.EvaluationRun;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorEvaluator.SearchMetrics;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorEvaluator.VectorEvaluation;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcPausePolicyEvaluator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcProofWindow;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcVectorSearchPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 时间线刷新向量搜索器。按总刷新次数递增枚举普通/高阶次数分配，
 * 委托组合评估器验证每个向量，并用确定性组合评估预算截断搜索，
 * 保证同一快照的搜索结果确定。纯内存对象，不访问数据库、HTTP或Redis。
 *
 * <p>评估每个向量前，从已证明候选中选出当前最优零新增停转替代时间线，
 * 作为收益级停转候选严格优于基准比较中的真实基准传入组合评估器。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcRefreshVectorSearcher implements OcVectorSearchPort {
    /**
     * 组合评估预算：以确定性计数截断搜索，保证同一快照结果确定；时间预算仅作兜底。
     */
    private static final int MAX_COMBINATION_EVALUATIONS = 60;

    private static final OcEconomicValueComparator VALUE_COMPARATOR =
            new OcEconomicValueComparator();

    private final int maxSearch;
    private final OcRefreshVectorEvaluator evaluator;

    /**
     * 创建刷新向量搜索器。
     *
     * @param maxSearch 单个池的最大搜索次数
     * @param evaluator 单向量组合评估器
     */
    OcRefreshVectorSearcher(int maxSearch, OcRefreshVectorEvaluator evaluator) {
        this.maxSearch = maxSearch;
        this.evaluator = evaluator;
    }

    /**
     * 创建自带组合评估器的刷新向量搜索器。
     *
     * @param maxSearch 单个池的最大搜索次数
     * @param scheduler 时间线事件推进器
     */
    public OcRefreshVectorSearcher(int maxSearch, OcTimelineEventScheduler scheduler) {
        this(maxSearch, new OcRefreshVectorEvaluator(scheduler,
                new OcPausePolicyEvaluator()));
    }

    /**
     * 按总刷新次数递增搜索全部普通池和高阶池向量。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param deadline           求解截止纳秒时间
     * @param proofWindow        由引擎统一计算的有限证明窗口
     * @return 向量搜索结果
     */
    @Override
    public OcVectorSearchOutcome search(OcRefreshSafetyRequest request,
                                        Map<String, OcValueEvidence> evidenceByTemplate,
                                        long deadline,
                                        OcProofWindow proofWindow) {
        List<OcRefreshSafetyResult.SafeCandidate> safe = new ArrayList<>();
        List<OcRefreshVector> failed = new ArrayList<>();
        SearchOutput output = new SearchOutput(safe, failed);
        CombinationBudget budget = new CombinationBudget(MAX_COMBINATION_EVALUATIONS);
        SearchMetrics metrics = new SearchMetrics();
        EvaluationRun run = new EvaluationRun(request, evidenceByTemplate, deadline,
                budget, proofWindow, null, metrics);
        boolean timedOut = false;
        for (int total = 0; total <= maxSearch * 2 && !timedOut; total++) {
            timedOut = searchTotal(run, total, output);
        }
        return new OcVectorSearchOutcome(output.safe(), timedOut, budget.exhausted(),
                budget.consumed(), metrics.budgetTruncations(), metrics.alternativesCapHits());
    }

    /**
     * 判断已证明安全的候选是否触及单池搜索上限。
     *
     * @param candidates 已证明安全的候选集合
     * @return 任一池次数达到搜索上限时返回true
     */
    @Override
    public boolean touchesSearchLimit(List<OcRefreshSafetyResult.SafeCandidate> candidates) {
        return candidates.stream().anyMatch(candidate ->
                candidate.vector().normalCount() == maxSearch
                        || candidate.vector().highCount() == maxSearch);
    }

    /**
     * 搜索指定总刷新次数下的全部普通/高阶次数分配。
     *
     * @param run    本次搜索的共享评估上下文
     * @param total  当前总刷新次数
     * @param output 搜索结果输出集合
     * @return 是否因时间预算终止
     */
    private boolean searchTotal(EvaluationRun run, int total, SearchOutput output) {
        for (int high = Math.max(0, total - maxSearch); high <= Math.min(maxSearch, total);
             high++) {
            StepStatus status = tryVector(run,
                    new OcRefreshVector(total - high, high), output);
            if (status != StepStatus.CONTINUE) {
                return status == StepStatus.STOP_TIMEOUT;
            }
        }
        return false;
    }

    /**
     * 评估单个刷新向量并归类结果。评估前从已证明候选中刷新零停转基准，
     * 保证收益级停转组合始终与当前最优零新增停转替代时间线比较。
     *
     * @param run    本次搜索的共享评估上下文
     * @param vector 待评估刷新向量
     * @param output 搜索结果输出集合
     * @return 向量处理结果
     */
    private StepStatus tryVector(EvaluationRun run, OcRefreshVector vector,
                                 SearchOutput output) {
        if (hasFailedSubset(vector, output.failed())) {
            return StepStatus.CONTINUE;
        }
        EvaluationRun current = run.withZeroPauseBaseline(
                VALUE_COMPARATOR.bestZeroPauseBaseline(output.safe()));
        VectorEvaluation evaluation = evaluator.evaluateVector(current, vector);
        if (evaluation.status() == VectorEvaluation.Status.TIMEOUT) {
            return StepStatus.STOP_TIMEOUT;
        }
        if (evaluation.status() == VectorEvaluation.Status.BUDGET_EXHAUSTED) {
            if (evaluation.candidate() != null) {
                output.safe().add(evaluation.candidate());
            }
            return StepStatus.STOP_BUDGET;
        }
        if (evaluation.status() == VectorEvaluation.Status.FAILED) {
            output.failed().add(vector);
        } else {
            output.safe().add(evaluation.candidate());
        }
        return StepStatus.CONTINUE;
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
     * 向量搜索的可变结果集合：已证明安全候选与已失败向量。
     *
     * @param safe   已证明安全候选输出集合
     * @param failed 已失败向量输出集合
     */
    private record SearchOutput(List<OcRefreshSafetyResult.SafeCandidate> safe,
                                List<OcRefreshVector> failed) {
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
     * 确定性组合评估预算。按评估次数截断，保证同一快照重复求解结果一致。
     */
    static final class CombinationBudget {
        private int remaining;
        private int consumed;

        private CombinationBudget(int maxEvaluations) {
            this.remaining = maxEvaluations;
        }

        boolean tryConsume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            consumed++;
            return true;
        }

        boolean exhausted() {
            return remaining <= 0;
        }

        int consumed() {
            return consumed;
        }
    }
}
