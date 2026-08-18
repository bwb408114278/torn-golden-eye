package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.policy.OcRefreshModeSelector;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcProofWindow;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 刷新向量组合评估器测试。通过搜索器间接验证联合随机组合的顺序无关聚合、
 * 保证释放最坏值和收益级停转相对零新增停转替代时间线基准的严格比较。
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
        stubTwoBetaProfitOnly();

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate vectorTwoZero = candidate(outcome, 2, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, vectorTwoZero.pauseTier(),
                "后续零停转组合不得降低已看到的收益级层级");
        assertTrue(vectorTwoZero.zeroPauseBaselineComparable(),
                "已证明零停转正向量(1,0)必须构成可比较基准");
        OcRefreshModeSelector selector = new OcRefreshModeSelector();
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(selectorResult(outcome), OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("存在可行零停转替代时间线基准且价值严格更优时收益级正向量可达")
    void shouldPromoteProfitVectorOnlyWhenStrictlyBetterThanProvenZeroPauseBaseline() {
        stubTwoBetaProfitOnly();

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate vectorTwoZero = candidate(outcome, 2, 0);
        assertTrue(vectorTwoZero.zeroPauseBaselineComparable(),
                "评估(2,0)时(1,0)零停转正向量基准必须参与比较");
        assertFalse(vectorTwoZero.pauseCandidateStrictlyBetterThanBaseline(),
                "候选保证释放延后时，即使金额更高也必须拒绝");
        OcRefreshModeSelector selector = new OcRefreshModeSelector();
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(selectorResult(outcome), OcPlanMode.PROFIT),
                "保证释放延后的WITHIN_PROFIT正向量不得被收益模式实际选中");
    }

    @Test
    @DisplayName("候选令既有义务延后10小时而零停转基准不延后时应被拒绝")
    void shouldRejectProfitCandidateThatDelaysExistingObligations() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.size() >= 3) {
                        return infeasibleResult();
                    }
                    if (roots.isEmpty()) {
                        return feasibleResult(NOW.plusHours(2));
                    }
                    if (allowedPause.isZero()
                            || allowedPause.equals(Duration.ofHours(6))) {
                        return infeasibleResult();
                    }
                    return profitResult(Duration.ofHours(10), 3, NOW.plusHours(4));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search(
                request("Alpha"), evidence("Alpha", BigDecimal.ZERO,
                        OcValueEvidence.Level.OBSERVED_REWARD));

        SafeCandidate vectorOneZero = candidate(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, vectorOneZero.pauseTier());
        assertTrue(vectorOneZero.zeroPauseBaselineComparable());
        assertFalse(vectorOneZero.pauseCandidateStrictlyBetterThanBaseline(),
                "价值相同但既有义务延迟10小时的候选不得视为严格更优");
        OcRefreshModeSelector selector = new OcRefreshModeSelector();
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(selectorResult(outcome), OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("金额与单位人天价值未严格提高时收益级正向量必须拒绝")
    void shouldRejectProfitCandidateWhenUnitValueIsNotStrictlyBetter() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.size() >= 3) {
                        return infeasibleResult();
                    }
                    if (roots.isEmpty()) {
                        return feasibleResult(NOW.plusHours(2));
                    }
                    if (allowedPause.isZero()
                            || allowedPause.equals(Duration.ofHours(6))) {
                        return infeasibleResult();
                    }
                    return profitResult(Duration.ZERO, 10, NOW.plusHours(1));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search(
                request("Alpha"), evidence("Alpha", BigDecimal.ZERO,
                        OcValueEvidence.Level.OBSERVED_REWARD));

        SafeCandidate vectorOneZero = candidate(outcome, 1, 0);
        assertTrue(vectorOneZero.zeroPauseBaselineComparable());
        assertFalse(vectorOneZero.pauseCandidateStrictlyBetterThanBaseline());
        OcRefreshModeSelector selector = new OcRefreshModeSelector();
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(selectorResult(outcome), OcPlanMode.PROFIT),
                "金额证据存在但单位价值未严格提高时必须fail-closed");
    }

    @Test
    @DisplayName("后发现更优零停转正向量时收益级候选必须按最终基准回退")
    void shouldReevaluateProfitCandidateAgainstLaterBetterZeroPauseBaseline() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.isEmpty()) {
                        return feasibleResult(NOW.plusHours(1));
                    }
                    if (roots.size() == 2 && allowedPause.isZero()) {
                        return feasibleResult(NOW.plusHours(3));
                    }
                    if (roots.size() > 1) {
                        return infeasibleResult();
                    }
                    String name = roots.getFirst().obligation().demand().ocName();
                    if (name.equals("Alpha") && !allowedPause.isZero()
                            && !allowedPause.equals(Duration.ofHours(6))) {
                        return feasibleResult(NOW.plusHours(2));
                    }
                    if (name.equals("Beta") && allowedPause.isZero()) {
                        return feasibleResult(NOW.plusHours(3));
                    }
                    return infeasibleResult();
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search();

        SafeCandidate profitCandidate = candidate(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profitCandidate.pauseTier());
        assertTrue(outcome.candidates().stream().anyMatch(item ->
                item.vector().equals(new OcRefreshVector(2, 0))
                        && item.pauseTier() == SafeCandidate.PauseTier.ZERO_PAUSE));
        assertFalse(profitCandidate.pauseCandidateStrictlyBetterThanBaseline(),
                "最终基准必须使用后发现的更优正向零停转候选");
    }

    @Test
    @DisplayName("替代匹配上限命中时收益级候选必须统一fail-closed")
    void shouldFailClosedProfitCandidateWhenAlternativesAreCapped() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.isEmpty()) {
                        return feasibleResult(NOW.plusHours(1));
                    }
                    if (allowedPause.isZero()
                            || allowedPause.equals(Duration.ofHours(6))) {
                        return infeasibleResult();
                    }
                    return feasibleResultWithAlternativesCap(NOW.plusHours(2));
                });

        SafeCandidate candidate = candidate(search(request("Alpha"),
                evidence("Alpha", BigDecimal.valueOf(100),
                        OcValueEvidence.Level.OBSERVED_REWARD)), 1, 0);

        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, candidate.pauseTier());
        assertFalse(candidate.zeroPauseBaselineComparable());
        assertFalse(candidate.pauseCandidateStrictlyBetterThanBaseline());
    }

    @Test
    @DisplayName("PRIOR_ONLY收益级候选与零向量金额基准不可稳定比较时应拒绝")
    void shouldRejectProfitCandidateWhenPriorNotComparableWithBaseline() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.size() >= 3) {
                        return infeasibleResult();
                    }
                    if (roots.isEmpty()) {
                        return feasibleResult(NOW.plusHours(2));
                    }
                    if (allowedPause.isZero()
                            || allowedPause.equals(Duration.ofHours(6))) {
                        return infeasibleResult();
                    }
                    return profitResult(Duration.ZERO, 0, NOW.plusHours(1));
                });

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search(
                request("Beta"), evidence("Beta", null,
                        OcValueEvidence.Level.PRIOR_ONLY));

        SafeCandidate vectorOneZero = candidate(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, vectorOneZero.pauseTier());
        assertTrue(vectorOneZero.zeroPauseBaselineComparable());
        assertFalse(vectorOneZero.pauseCandidateStrictlyBetterThanBaseline(),
                "PRIOR_ONLY候选无法与零向量金额基准稳定比较时必须fail-closed");
        OcRefreshModeSelector selector = new OcRefreshModeSelector();
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(selectorResult(outcome), OcPlanMode.PROFIT));
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

    private void stubTwoBetaProfitOnly() {
        when(scheduler.simulate(any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    List<CandidateRoot> roots = invocation.getArgument(1);
                    Duration allowedPause = invocation.getArgument(2);
                    if (roots.size() >= 3) {
                        return infeasibleResult();
                    }
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
    }

    private OcRefreshVectorSearcher.OcVectorSearchOutcome search() {
        return search(request(), evidence());
    }

    private OcRefreshVectorSearcher.OcVectorSearchOutcome search(
            OcRefreshSafetyRequest request, Map<String, OcValueEvidence> evidence) {
        OcRefreshVectorSearcher searcher = new OcRefreshVectorSearcher(3, scheduler);
        return searcher.search(request, evidence,
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

    /**
     * 构造单模板请求：仅含一个普通池模板，用于收益级停转场景的基准比较。
     *
     * @param templateName 模板名
     * @return 单模板求解请求
     */
    private OcRefreshSafetyRequest request(String templateName) {
        OcPlanSlot slot = new OcPlanSlot("Worker#1", "Worker", 60, 1, null);
        OcTeamDemand template = new OcTeamDemand(0L, templateName, 8, null,
                NOW.plusDays(7), false, List.of(slot), Set.of(), Set.of());
        return new OcRefreshSafetyRequest(List.of(), Set.of(), List.of(), Map.of(),
                List.of(template), List.of(), NOW);
    }

    private Map<String, OcValueEvidence> evidence() {
        return Map.of(
                OcPlanningSnapshot.ocKey(8, "Alpha"),
                evidenceEntry(BigDecimal.valueOf(100)),
                OcPlanningSnapshot.ocKey(8, "Beta"),
                new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                        BigDecimal.valueOf(200), 10, NOW.plusHours(8), true, 8, 2, 1));
    }

    private OcValueEvidence evidenceEntry(BigDecimal value) {
        return new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                value, 10, NOW.plusHours(8), true, 8, 2, 1);
    }

    private Map<String, OcValueEvidence> evidence(String templateName, BigDecimal value,
                                                  OcValueEvidence.Level level) {
        return Map.of(OcPlanningSnapshot.ocKey(8, templateName),
                new OcValueEvidence(level, value, 10, NOW.plusHours(8), true, 8, 2, 1));
    }

    private OcRefreshSafetyResult selectorResult(
            OcRefreshVectorSearcher.OcVectorSearchOutcome outcome) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE, Set.of(),
                false, Set.of(), List.of(), null, null);
        return new OcRefreshSafetyResult(assessment, outcome.candidates(), false, 1L,
                OcSearchTelemetry.empty(), List.of());
    }

    private SafeCandidate candidate(OcRefreshVectorSearcher.OcVectorSearchOutcome outcome,
                                    int normal, int high) {
        Optional<SafeCandidate> found = outcome.candidates().stream()
                .filter(item -> item.vector().equals(new OcRefreshVector(normal, high)))
                .findFirst();
        return found.orElseThrow(() ->
                new AssertionError("缺少候选 " + normal + "," + high));
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
                List.of(), events, Duration.ZERO, false, false,
                new OcTimelineValueSummary(null, 10, Duration.ZERO, Duration.ZERO,
                        true, completionAt, 8, 2, 1,
                        OcValueEvidence.Level.OBSERVED_REWARD));
    }

    private SimulationResult profitResult(Duration existingDelay, int memberDays,
                                          LocalDateTime completionAt) {
        List<OcTimelineEvent> events = List.of(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, "stub"));
        return new SimulationResult(true, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), events, Duration.ofHours(12), false, false,
                new OcTimelineValueSummary(null, memberDays, Duration.ofHours(12),
                        existingDelay, true, completionAt, 8, 2, 1,
                        OcValueEvidence.Level.OBSERVED_REWARD));
    }

    private SimulationResult feasibleResultWithAlternativesCap(LocalDateTime completionAt) {
        List<OcTimelineEvent> events = List.of(new OcTimelineEvent(completionAt,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, "stub"));
        return new SimulationResult(true, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), events, Duration.ofHours(12), false, true,
                new OcTimelineValueSummary(null, 0, Duration.ofHours(12), Duration.ZERO,
                        true, completionAt, 8, 2, 1,
                        OcValueEvidence.Level.OBSERVED_REWARD));
    }

    private SimulationResult infeasibleResult() {
        return new SimulationResult(false, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), List.of(), Duration.ZERO, false, false,
                new OcTimelineValueSummary(null, 0, Duration.ZERO, Duration.ZERO,
                        false, null, 0, 0, 1, OcValueEvidence.Level.INSUFFICIENT));
    }
}
