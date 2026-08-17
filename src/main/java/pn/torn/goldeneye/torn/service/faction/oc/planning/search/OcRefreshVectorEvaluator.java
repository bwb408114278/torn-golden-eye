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
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
class OcRefreshVectorEvaluator {
    private final OcTimelineEventScheduler scheduler;
    private final OcPausePolicyEvaluator pauseEvaluator;

    OcRefreshVectorEvaluator(OcTimelineEventScheduler scheduler,
                             OcPausePolicyEvaluator pauseEvaluator) {
        this.scheduler = scheduler;
        this.pauseEvaluator = pauseEvaluator;
    }

    VectorEvaluation evaluateVector(OcRefreshSafetyRequest request,
                                    Map<String, OcValueEvidence> evidenceByTemplate,
                                    OcRefreshVector vector, long deadline,
                                    CombinationBudget budget,
                                    OcProofWindow proofWindow) {
        if (proofWindow.newRefreshBlocked()) {
            return VectorEvaluation.failed();
        }
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
                        normalCombination, highCombination, deadline, budget, proofWindow,
                        worst);
                if (terminal != null) {
                    return terminal;
                }
            }
        }
        return worst.toEvaluation(vector);
    }

    private VectorEvaluation evaluateCombination(OcRefreshSafetyRequest request,
                                                 Map<String, OcValueEvidence> evidenceByTemplate,
                                                 int[] normalCombination, int[] highCombination,
                                                 long deadline, CombinationBudget budget,
                                                 OcProofWindow proofWindow,
                                                 WorstCase worst) {
        if (System.nanoTime() >= deadline) {
            return VectorEvaluation.timeout();
        }
        if (!budget.tryConsume()) {
            return VectorEvaluation.budgetExhausted();
        }
        List<CandidateRoot> roots = candidateRoots(request, normalCombination,
                highCombination);
        SimulationResult zeroBaseline = simulate(request, roots, Duration.ZERO,
                proofWindow);
        if (zeroBaseline.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(zeroBaseline, roots, evidenceByTemplate,
                    worst, normalCombination, highCombination);
        }
        if (zeroBaseline.feasible()) {
            mergeWorstCase(worst, zeroBaseline, zeroBaseline, roots, evidenceByTemplate,
                    SafeCandidate.PauseTier.ZERO_PAUSE);
            return null;
        }
        SimulationResult balanced = simulate(request, roots,
                OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE, proofWindow);
        if (balanced.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(balanced, roots, evidenceByTemplate,
                    worst, normalCombination, highCombination);
        }
        if (balanced.feasible()) {
            mergeWorstCase(worst, balanced, null, roots, evidenceByTemplate,
                    SafeCandidate.PauseTier.WITHIN_BALANCED);
            return null;
        }
        SimulationResult profit = simulate(request, roots,
                OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE, proofWindow);
        if (profit.searchBudgetExhausted()) {
            return budgetExhaustedWithCandidate(profit, roots, evidenceByTemplate,
                    worst, normalCombination, highCombination);
        }
        if (profit.feasible()) {
            mergeWorstCase(worst, profit, null, roots, evidenceByTemplate,
                    SafeCandidate.PauseTier.WITHIN_PROFIT);
            return null;
        }
        return VectorEvaluation.failed();
    }

    private VectorEvaluation budgetExhaustedWithCandidate(
            SimulationResult result, List<CandidateRoot> roots,
            Map<String, OcValueEvidence> evidenceByTemplate, WorstCase worst,
            int[] normalCombination, int[] highCombination) {
        if (result.feasible()) {
            mergeWorstCase(worst, result, null, roots, evidenceByTemplate,
                    tierFor(result));
            return new VectorEvaluation(VectorEvaluation.Status.BUDGET_EXHAUSTED,
                    worst.toEvaluation(vectorOf(normalCombination, highCombination))
                            .candidate());
        }
        return VectorEvaluation.budgetExhausted();
    }

    private SimulationResult simulate(OcRefreshSafetyRequest request,
                                      List<CandidateRoot> roots, Duration allowedPause,
                                      OcProofWindow proofWindow) {
        return scheduler.simulate(request, roots, allowedPause, true,
                proofWindow.proofWindowEnd());
    }

    private OcRefreshVector vectorOf(int[] normalCombination, int[] highCombination) {
        return new OcRefreshVector(java.util.Arrays.stream(normalCombination).sum(),
                java.util.Arrays.stream(highCombination).sum());
    }

    private void mergeWorstCase(WorstCase worst, SimulationResult result,
                                SimulationResult zeroBaseline,
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
            boolean comparable = zeroBaseline != null
                    && zeroBaseline.feasible()
                    && !zeroBaseline.searchBudgetExhausted();
            worst.zeroPauseBaselineComparable &= comparable;
            worst.pauseCandidateStrictlyBetter &= comparable
                    && strictlyBetterThanZeroBaseline(summary, zeroBaseline, evidenceByTemplate,
                    roots);
        }
    }

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

    private boolean strictlyBetterThanZeroBaseline(OcTimelineValueSummary candidate,
                                                   SimulationResult zeroBaseline,
                                                   Map<String, OcValueEvidence> evidenceByTemplate,
                                                   List<CandidateRoot> roots) {
        OcValueEvidence baselineEvidence = combinationEvidence(roots, evidenceByTemplate);
        OcTimelineValueSummary baseline = mergeSummary(zeroBaseline.timelineValue(),
                baselineEvidence);
        return new OcEconomicValueComparator()
                .isStrictlyBetterThanZeroPauseBaseline(candidate, baseline);
    }

    private LocalDateTime earliestCompletion(List<OcTimelineEvent> events) {
        return events.stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.COMPLETION_RELEASE)
                .map(OcTimelineEvent::eventTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

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

    private OcValueEvidence evidence(Map<String, OcValueEvidence> evidenceByTemplate,
                                     CandidateRoot root) {
        String ocKey = root.obligation().demand().rank() + ":"
                + root.obligation().demand().ocName();
        return root.successors().isEmpty()
                ? evidenceByTemplate.get(ocKey)
                : evidenceByTemplate.get("chain:" + ocKey);
    }

    private OcValueEvidence.Level weakerLevel(OcValueEvidence.Level left,
                                              OcValueEvidence.Level right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private SafeCandidate.PauseTier maxTier(SafeCandidate.PauseTier current,
                                            SafeCandidate.PauseTier tier) {
        return tier.compareTo(current) > 0 ? tier : current;
    }

    private SafeCandidate.PauseTier tierFor(SimulationResult result) {
        if (pauseEvaluator.requiresProfitTier(result.maxNewPause())) {
            return SafeCandidate.PauseTier.WITHIN_PROFIT;
        }
        if (pauseEvaluator.requiresBalancedTier(result.maxNewPause())) {
            return SafeCandidate.PauseTier.WITHIN_BALANCED;
        }
        return SafeCandidate.PauseTier.ZERO_PAUSE;
    }

    private boolean hasMissingTemplate(OcRefreshSafetyRequest request, OcRefreshVector vector) {
        return vector.normalCount() > 0 && request.normalTemplates().isEmpty()
                || vector.highCount() > 0 && request.highChains().isEmpty();
    }

    private List<int[]> nonEmptyCombinations(int typeCount, int total) {
        List<int[]> result = combinations(typeCount, total);
        return result.isEmpty() ? List.of(new int[0]) : result;
    }

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

    record VectorEvaluation(
            Status status,
            SafeCandidate candidate) {
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

        private void mergeRelease(LocalDateTime completion) {
            if (completion == null) {
                anyReleaseMissing = true;
                return;
            }
            latestEarliestRelease = latestEarliestRelease == null
                    ? completion : (completion.isAfter(latestEarliestRelease)
                    ? completion : latestEarliestRelease);
        }

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
