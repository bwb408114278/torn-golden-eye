package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcProofWindow;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 刷新向量组合评估器测试。通过搜索器间接验证联合随机组合的顺序无关聚合、
 * 保证释放最坏值和收益级停转的零停转基准比较状态。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("刷新向量组合评估")
@ExtendWith(MockitoExtension.class)
class OcRefreshVectorEvaluatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Mock
    private OcTimelineEventScheduler scheduler;

    @Test
    @DisplayName("停转层级应按全部组合严格单调max且不受后续零停转组合降低")
    void shouldKeepStrictMaxPauseTierAcrossCombinations() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (twoBeta(roots) && allowedPause.isZero()) {
                        return infeasibleResult();
                    }
                    if (twoBeta(roots) && allowedPause.equals(Duration.ofHours(6))) {
                        return infeasibleResult();
                    }
                    if (twoBeta(roots) && allowedPause.equals(Duration.ofHours(12))) {
                        return feasibleResult(NOW.plusHours(20));
                    }
                    if (mixed(roots)) {
                        return feasibleResult(NOW.plusHours(12));
                    }
                    if (twoAlpha(roots)) {
                        return feasibleResult(NOW.plusHours(8));
                    }
                    return feasibleResult(NOW.plusHours(1));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate vectorTwoZero = candidate(outcome, 2, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, vectorTwoZero.pauseTier(),
                "后续零停转组合不得降低已看到的收益级层级");
        assertFalse(vectorTwoZero.zeroPauseBaselineComparable());
        assertFalse(vectorTwoZero.pauseCandidateStrictlyBetterThanBaseline());
    }

    @Test
    @DisplayName("保证释放时间应取全部组合最早完整释放中的最晚值")
    void shouldUseWorstCombinationReleaseAsGuaranteedRelease() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    if (twoBeta(roots)) {
                        return feasibleResult(NOW.plusHours(20));
                    }
                    if (mixed(roots)) {
                        return feasibleResult(NOW.plusHours(12));
                    }
                    if (twoAlpha(roots)) {
                        return feasibleResult(NOW.plusHours(8));
                    }
                    return feasibleResult(NOW.plusHours(1));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate vectorTwoZero = candidate(outcome, 2, 0);
        assertEquals(NOW.plusHours(20), vectorTwoZero.guaranteedEarliestReleaseAt(),
                "保证释放必须取组合最早释放中的最晚值");
    }

    @Test
    @DisplayName("缺失释放事件的组合应显式降级为保证释放不可用")
    void shouldDowngradeGuaranteedReleaseWhenAnyCombinationHasNoRelease() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    if (mixed(roots)) {
                        return feasibleResult(null);
                    }
                    if (twoAlpha(roots)) {
                        return feasibleResult(NOW.plusHours(8));
                    }
                    return feasibleResult(NOW.plusHours(1));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate vectorTwoZero = candidate(outcome, 2, 0);
        assertNull(vectorTwoZero.guaranteedEarliestReleaseAt(),
                "任一组合无释放事件时保证释放必须为null");
    }

    private OcRefreshVectorSearcher.OcVectorSearchOutcome search() {
        OcRefreshVectorSearcher searcher = new OcRefreshVectorSearcher(3, scheduler);
        return searcher.search(request(), evidence(),
                System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                OcProofWindow.valid(NOW.plusDays(1)));
    }

    private OcRefreshSafetyRequest request() {
        OcPlanSlot slot = new OcPlanSlot("Worker#1", "Worker", 60, 1, null);
        OcTeamDemand alpha = new OcTeamDemand(0L, "Alpha", 8, null,
                NOW.plusDays(7), false, List.of(slot), Set.of(), Set.of());
        OcTeamDemand beta = new OcTeamDemand(0L, "Beta", 8, null,
                NOW.plusDays(7), false, List.of(slot), Set.of(), Set.of());
        return new OcRefreshSafetyRequest(List.of(), Set.of(), List.of(), Map.of(),
                List.of(alpha, beta), List.of(), NOW);
    }

    private Map<String, OcValueEvidence> evidence() {
        return Map.of(
                OcPlanningSnapshot.ocKey(8, "Alpha"),
                new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                        BigDecimal.valueOf(100), 10, NOW.plusHours(8), true, 8, 2, 1),
                OcPlanningSnapshot.ocKey(8, "Beta"),
                new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                        BigDecimal.valueOf(200), 10, NOW.plusHours(8), true, 8, 2, 1));
    }

    private SafeCandidate candidate(OcRefreshVectorSearcher.OcVectorSearchOutcome outcome,
                                    int normal, int high) {
        return outcome.candidates().stream()
                .filter(candidate -> candidate.vector().equals(new OcRefreshVector(normal, high)))
                .findFirst().orElseThrow(() -> new AssertionError("缺少候选 " + normal + "," + high));
    }

    private boolean twoBeta(List<CandidateRoot> roots) {
        return roots.size() == 2 && roots.stream().allMatch(root ->
                root.obligation().demand().ocName().equals("Beta"));
    }

    private boolean twoAlpha(List<CandidateRoot> roots) {
        return roots.size() == 2 && roots.stream().allMatch(root ->
                root.obligation().demand().ocName().equals("Alpha"));
    }

    private boolean mixed(List<CandidateRoot> roots) {
        return roots.size() == 2 && roots.stream()
                .map(root -> root.obligation().demand().ocName()).distinct().count() == 2;
    }

    private SimulationResult feasibleResult(LocalDateTime completionAt) {
        List<OcTimelineEvent> events = completionAt == null ? List.of()
                : List.of(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, "stub"));
        return new SimulationResult(true, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), events, Duration.ZERO, false,
                new OcTimelineValueSummary(null, 0, Duration.ZERO, Duration.ZERO,
                        true, completionAt, 8, 2, 1,
                        OcValueEvidence.Level.OBSERVED_REWARD));
    }

    private SimulationResult infeasibleResult() {
        return new SimulationResult(false, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), List.of(), Duration.ZERO, false,
                new OcTimelineValueSummary(null, 0, Duration.ZERO, Duration.ZERO,
                        false, null, 0, 0, 1, OcValueEvidence.Level.INSUFFICIENT));
    }
}
