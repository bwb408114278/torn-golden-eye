package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.policy.OcRefreshModeSelector;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorSearcher;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.CandidateRoot;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间线价值累积器真实既有义务完成延迟测试。直接构造已完成 {@link OcTimelineState}，
 * 走真实累积器产出延迟，再经组合评估与模式选点器验证收益 fail-closed 行为。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("时间线价值累积器既有义务延迟")
class OcTimelineValueAccumulatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);
    private static final Duration TEN_HOURS = Duration.ofHours(10);

    private final OcTimelineValueAccumulator accumulator = new OcTimelineValueAccumulator();
    private final OcRefreshModeSelector selector = new OcRefreshModeSelector();

    @Test
    @DisplayName("收益级停转使既有C晚10小时完成时真实累积器必须产出10小时延迟")
    void shouldAccumulateTenHourExistingObligationDelay() {
        OcTimelineObligation existing = joinedObligation(1L, NOW.plusHours(6), 2, 1);
        LocalDateTime baseline = NOW.plusHours(30);
        LocalDateTime actual = baseline.plus(TEN_HOURS);
        OcTimelineValueSummary summary = accumulate(existing, actual);

        assertEquals(TEN_HOURS, summary.existingObligationDelay());
    }

    @Test
    @DisplayName("既有C按无主动停转原时间完成时真实累积器必须产出零延迟")
    void shouldAccumulateZeroExistingObligationDelay() {
        OcTimelineObligation existing = joinedObligation(1L, NOW.plusHours(6), 2, 1);
        LocalDateTime baseline = NOW.plusHours(30);
        OcTimelineValueSummary summary = accumulate(existing, baseline);

        assertEquals(Duration.ZERO, summary.existingObligationDelay());
    }

    @Test
    @DisplayName("既有义务基准完成时间不可证明时真实累积器必须返回不可比较哨兵")
    void shouldReturnUnprovableSentinelWhenBaselineCannotBeProved() {
        OcTimelineObligation existing = joinedObligation(1L, null, 2, 1);
        OcTimelineValueSummary summary = accumulate(existing, NOW.plusHours(40));

        assertTrue(summary.hasUnprovableExistingObligationDelay());
        assertEquals(OcTimelineValueSummary.UNPROVEN_OBLIGATION_DELAY,
                summary.existingObligationDelay());
    }

    @Test
    @DisplayName("真实累积器产出10小时延迟时收益模式必须拒绝该收益级正向量")
    void shouldRejectProfitVectorWhenRealAccumulatorProducesDelay() {
        OcTimelineValueSummary baseline = zeroDelaySummary();
        OcTimelineValueSummary profit = tenHourDelaySummary();
        OcRefreshSafetyResult safety = safety(
                List.of(
                        candidate(new OcRefreshVector(1, 0),
                                SafeCandidate.PauseTier.ZERO_PAUSE,
                                withValue(baseline, BigDecimal.valueOf(100)),
                                true),
                        candidate(new OcRefreshVector(2, 0),
                                SafeCandidate.PauseTier.WITHIN_PROFIT,
                                withValue(profit, BigDecimal.valueOf(1000)),
                                true)));

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "名义奖励更高但真实延迟10小时的收益级停转候选必须被拒绝");
    }

    @Test
    @DisplayName("真实累积器不产出延迟且完整价值严格更优时收益模式允许选择")
    void shouldAcceptProfitVectorWhenRealAccumulatorProducesNoDelay() {
        OcTimelineValueSummary baseline = zeroDelaySummary();
        OcTimelineValueSummary profit = zeroDelaySummary();
        OcRefreshSafetyResult safety = safety(
                List.of(
                        candidate(new OcRefreshVector(1, 0),
                                SafeCandidate.PauseTier.ZERO_PAUSE,
                                withValue(baseline, BigDecimal.valueOf(100)),
                                true),
                        candidate(new OcRefreshVector(2, 0),
                                SafeCandidate.PauseTier.WITHIN_PROFIT,
                                withValue(profit, BigDecimal.valueOf(1000)),
                                true)));

        assertEquals(new OcRefreshVector(2, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "不延后既有义务且价值严格更优的收益级停转候选必须可选");
    }

    @Test
    @DisplayName("真实累积器基准不可证明时收益级停转候选必须fail-closed")
    void shouldFailClosedWhenRealAccumulatorCannotProveBaseline() {
        OcTimelineValueSummary baseline = zeroDelaySummary();
        OcTimelineValueSummary profit = unprovableDelaySummary();
        OcRefreshSafetyResult safety = safety(
                List.of(
                        candidate(new OcRefreshVector(1, 0),
                                SafeCandidate.PauseTier.ZERO_PAUSE,
                                withValue(baseline, BigDecimal.valueOf(100)),
                                true),
                        candidate(new OcRefreshVector(2, 0),
                                SafeCandidate.PauseTier.WITHIN_PROFIT,
                                withValue(profit, BigDecimal.valueOf(1000)),
                                true)));

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "基准完成时间不可证明时收益级停转候选不得提高建议");
    }

    private OcTimelineValueSummary accumulate(OcTimelineObligation obligation,
                                              LocalDateTime actualCompletion) {
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(List.of(), Set.of(),
                List.of(obligation), Map.of(), List.of(), List.of(), NOW);
        OcTimelineState state = new OcTimelineState(request);
        state.addAnchor(new OcLiquidityAnchor(obligation.key(), actualCompletion, 2, false));
        state.addEvent(new OcTimelineEvent(actualCompletion,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, obligation.key()));
        return accumulator.accumulate(state, false, request);
    }

    private OcTimelineObligation joinedObligation(long ocId, LocalDateTime readyAt,
                                                  int totalSlots, int joinedCount) {
        List<OcPlanSlot> slots = IntStream.range(0, totalSlots)
                .mapToObj(index -> new OcPlanSlot("Worker#" + index, "Worker", 60, 1, null))
                .toList();
        Set<String> fixedSlotCodes = IntStream.range(0, joinedCount)
                .mapToObj(index -> "Worker#" + index)
                .collect(Collectors.toSet());
        Set<Long> fixedMemberIds = LongStream.range(1, joinedCount + 1)
                .boxed().collect(Collectors.toSet());
        OcTeamDemand demand = new OcTeamDemand(ocId, "C", 8, readyAt, null, false,
                slots, fixedSlotCodes, fixedMemberIds);
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelineValueSummary zeroDelaySummary() {
        OcTimelineObligation existing = joinedObligation(1L, NOW.plusHours(6), 2, 1);
        return accumulate(existing, NOW.plusHours(30));
    }

    private OcTimelineValueSummary tenHourDelaySummary() {
        OcTimelineObligation existing = joinedObligation(1L, NOW.plusHours(6), 2, 1);
        return accumulate(existing, NOW.plusHours(40));
    }

    private OcTimelineValueSummary unprovableDelaySummary() {
        OcTimelineObligation existing = joinedObligation(1L, null, 2, 1);
        return accumulate(existing, NOW.plusHours(40));
    }

    private OcTimelineValueSummary withValue(OcTimelineValueSummary summary,
                                             BigDecimal value) {
        return new OcTimelineValueSummary(value,
                summary.actualIncrementalMemberDays(),
                summary.actualNewPause(),
                summary.existingObligationDelay(),
                summary.avoidableExpiryPressure(),
                summary.guaranteedReleaseAt(),
                8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier,
                                    OcTimelineValueSummary summary,
                                    boolean strictlyBetter) {
        return new SafeCandidate(vector, tier, summary, 1,
                OcValueEvidence.Level.OBSERVED_REWARD, true, strictlyBetter);
    }

    private OcRefreshSafetyResult safety(List<SafeCandidate> candidates) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE, Set.of(),
                false, Set.of(), List.of(), null, null);
        return new OcRefreshSafetyResult(assessment, candidates, false, 1L,
                OcSearchTelemetry.empty(), List.of());
    }

    @Test
    @DisplayName("真实累积器延迟进入组合评估后收益级正向量必须被拒绝")
    void shouldRejectProfitVectorThroughEvaluatorWhenRealAccumulatorDelays() {
        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome =
                searchThroughEvaluator(true, false);

        SafeCandidate profit = candidateFrom(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profit.pauseTier());
        assertFalse(profit.pauseCandidateStrictlyBetterThanBaseline(),
                "真实延迟10小时进入组合评估后不得标记为严格优于零停转基准");
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety(outcome.candidates()), OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("真实累积器无延迟且价值更优时组合评估允许收益级正向量")
    void shouldAcceptProfitVectorThroughEvaluatorWhenRealAccumulatorNoDelay() {
        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome =
                searchThroughEvaluator(false, false);

        SafeCandidate profit = candidateFrom(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profit.pauseTier());
        assertTrue(profit.pauseCandidateStrictlyBetterThanBaseline(),
                "真实零延迟且价值更优时组合评估必须标记严格更优");
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety(outcome.candidates()), OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("真实累积器基准不可证明进入组合评估后收益级正向量必须fail-closed")
    void shouldFailClosedThroughEvaluatorWhenRealAccumulatorUnprovable() {
        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome =
                searchThroughEvaluator(false, true);

        SafeCandidate profit = candidateFrom(outcome, 1, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profit.pauseTier());
        assertFalse(profit.pauseCandidateStrictlyBetterThanBaseline(),
                "基准不可证明时组合评估不得标记严格更优");
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety(outcome.candidates()), OcPlanMode.PROFIT));
    }

    private OcRefreshVectorSearcher.OcVectorSearchOutcome searchThroughEvaluator(
            boolean profitDelayed, boolean profitUnprovable) {
        OcRefreshSafetyRequest request = evaluatorRequest();
        OcTimelineEventScheduler scheduler = Mockito.mock(OcTimelineEventScheduler.class);
        Mockito.when(scheduler.simulate(Mockito.any(), Mockito.anyList(), Mockito.any(),
                Mockito.anyBoolean(), Mockito.any())).thenAnswer(invocation -> {
            OcRefreshSafetyRequest req = invocation.getArgument(0);
            List<CandidateRoot> candidates = invocation.getArgument(1);
            Duration allowedPause = invocation.getArgument(2);
            if (candidates.isEmpty()) {
                return simulationResult(req, NOW.plusHours(30));
            }
            if (allowedPause.isZero()
                    || allowedPause.equals(Duration.ofHours(6))) {
                return infeasibleResult();
            }
            if (profitUnprovable) {
                OcTimelineState state = new OcTimelineState(req);
                return feasibleResult(accumulator.accumulate(state, false, req));
            }
            LocalDateTime actual = profitDelayed
                    ? NOW.plusHours(40) : NOW.plusHours(30);
            return simulationResult(req, actual);
        });
        OcRefreshVectorSearcher searcher = new OcRefreshVectorSearcher(1, scheduler);
        return searcher.search(request,
                Map.of(OcPlanningSnapshot.ocKey(8, "Alpha"),
                        new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                                BigDecimal.valueOf(1000), 10, NOW.plusHours(8), true,
                                8, 2, 1)),
                System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                OcProofWindow.valid(NOW.plusDays(1)));
    }

    private OcRefreshSafetyRequest evaluatorRequest() {
        OcTimelineObligation existing = joinedObligation(1L, NOW.plusHours(6), 2, 1);
        OcPlanSlot slot = new OcPlanSlot("Worker#1", "Worker", 60, 1, null);
        OcTeamDemand template = new OcTeamDemand(0L, "Alpha", 8, null,
                NOW.plusDays(7), false, List.of(slot), Set.of(), Set.of());
        return new OcRefreshSafetyRequest(List.of(), Set.of(), List.of(existing),
                Map.of(), List.of(template), List.of(), NOW);
    }

    private SimulationResult simulationResult(OcRefreshSafetyRequest request,
                                              LocalDateTime actualCompletion) {
        OcTimelineState state = new OcTimelineState(request);
        state.addAnchor(new OcLiquidityAnchor("oc:1", actualCompletion, 2, false));
        state.addEvent(new OcTimelineEvent(actualCompletion,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, "oc:1"));
        return feasibleResult(accumulator.accumulate(state, false, request));
    }

    private SimulationResult feasibleResult(OcTimelineValueSummary summary) {
        return new SimulationResult(true, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), List.of(), Duration.ZERO, false, false, summary);
    }

    private SimulationResult infeasibleResult() {
        return new SimulationResult(false, false, false, false,
                new OcTimelineEventScheduler.LiquidityProof(List.of(), List.of(), true),
                List.of(), List.of(), Duration.ZERO, false, false,
                OcTimelineValueSummary.empty());
    }

    private SafeCandidate candidateFrom(OcRefreshVectorSearcher.OcVectorSearchOutcome outcome,
                                        int normal, int high) {
        return outcome.candidates().stream()
                .filter(candidate -> candidate.vector()
                        .equals(new OcRefreshVector(normal, high)))
                .findFirst().orElseThrow();
    }

}
