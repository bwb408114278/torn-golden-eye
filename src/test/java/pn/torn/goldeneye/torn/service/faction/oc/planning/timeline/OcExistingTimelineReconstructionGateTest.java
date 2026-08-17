package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcExistingTimelineReconstructor;
import pn.torn.goldeneye.torn.service.faction.oc.planning.policy.OcRefreshModeSelector;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorSearcher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC新队第二批第五轮真实重建到收益门禁的最小链夹具测试。
 *
 * <p>不使用 Mockito，以真实 {@link OcExistingTimelineReconstructor} 重建现实空链后继，
 * 构造匿名求解请求后经真实时间线搜索/累积器/选点器验证 P1-7 的现实后继前置事实透传。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.17
 */
@DisplayName("OC现实空链后继收益门禁链夹具")
class OcExistingTimelineReconstructionGateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);
    private static final BigDecimal ALPHA_VALUE = BigDecimal.valueOf(1000);
    private static final String CHILD_KEY = OcPlanningSnapshot.ocKey(9, "Child");
    private static final String ALPHA_KEY = OcPlanningSnapshot.ocKey(8, "Alpha");

    private final OcExistingTimelineReconstructor reconstructor =
            new OcExistingTimelineReconstructor();
    private final OcRefreshModeSelector selector = new OcRefreshModeSelector();
    private final OcTimelineValueAccumulator accumulator = new OcTimelineValueAccumulator();

    @Test
    @DisplayName("现实后继createTime传入请求时按理想时间完成可比较为零延迟且收益候选可选")
    void shouldAcceptProfitVectorWhenReconstructedSuccessorHasProvenZeroDelay() {
        OcRefreshSafetyRequest request = requestWithSuccessor(NOW, memberSetForZeroDelay());

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search(request);

        SafeCandidate profit = candidate(outcome, 3, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profit.pauseTier());
        assertEquals(Duration.ZERO, profit.timelineValue().existingObligationDelay());
        assertTrue(profit.pauseCandidateStrictlyBetterThanBaseline(),
                "现实后继零延迟且价值更优时收益级候选必须严格优于零停转基准");

        OcRefreshSafetyResult safety = safety(outcome);
        assertEquals(new OcRefreshVector(3, 0), selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("现实后继缺失createTime时收益级候选必须fail-closed")
    void shouldFailClosedWhenReconstructedSuccessorHasNoCreateTime() {
        OcRefreshSafetyRequest request = requestWithSuccessor(null, memberSetForZeroDelay());

        OcRefreshVectorSearcher.OcVectorSearchOutcome outcome = search(request);

        SafeCandidate profit = candidate(outcome, 3, 0);
        assertEquals(SafeCandidate.PauseTier.WITHIN_PROFIT, profit.pauseTier());
        assertTrue(profit.timelineValue().hasUnprovableExistingObligationDelay());
        assertFalse(profit.pauseCandidateStrictlyBetterThanBaseline(),
                "现实后继缺少createTime时收益级候选不得标记为严格更优");

        OcRefreshSafetyResult safety = safety(outcome);
        assertNotEquals(new OcRefreshVector(3, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "现实后继缺少createTime时收益级停转候选不得被选中");
    }

    @Test
    @DisplayName("现实后继实际晚10小时完成时收益级主动停转必须被拒绝")
    void shouldRejectProfitVectorWhenReconstructedSuccessorCompletesTenHoursLate() {
        OcRefreshSafetyRequest request = requestWithSuccessor(NOW, memberSetForZeroDelay());
        OcTimelineObligation successor = request.obligations().stream()
                .filter(obligation -> obligation.kind()
                        == OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR)
                .findFirst().orElseThrow();

        OcTimelineState state = new OcTimelineState(request);
        LocalDateTime ideal = NOW.plusHours(24);
        LocalDateTime actual = ideal.plus(Duration.ofHours(10));
        state.addAnchor(new OcLiquidityAnchor(successor.key(), actual, 1, false));
        state.addEvent(new OcTimelineEvent(actual,
                OcTimelineEvent.EventType.COMPLETION_RELEASE, successor.key()));
        OcTimelineValueSummary lateSummary = accumulator.accumulate(state, false, request);

        assertEquals(Duration.ofHours(10), lateSummary.existingObligationDelay());
        OcRefreshSafetyResult safety = safetyWith(
                List.of(
                        candidate(new OcRefreshVector(0, 0),
                                SafeCandidate.PauseTier.ZERO_PAUSE,
                                zeroDelaySummary(), true),
                        candidate(new OcRefreshVector(1, 0),
                                SafeCandidate.PauseTier.WITHIN_PROFIT,
                                withValue(lateSummary), true)));
        assertEquals(new OcRefreshVector(0, 0), selector.select(safety, OcPlanMode.PROFIT),
                "现实后继晚10小时完成时收益级主动停转候选必须被拒绝");
    }

    private OcRefreshSafetyRequest requestWithSuccessor(LocalDateTime createTime,
                                                        List<OcMemberCandidate> members) {
        TornFactionOcDO child = new TornFactionOcDO();
        child.setId(1L);
        child.setName("Child");
        child.setRank(9);
        child.setStatus("Recruiting");
        child.setReadyTime(null);
        child.setCreateTime(createTime);
        child.setPreviousOcId(100L);
        TornSettingOcPlanProfileDO childProfile = profile("Child", 9, "CHAIN_ONLY");
        TornSettingOcPlanProfileDO alphaProfile = profile("Alpha", 8, "NORMAL_7_8");
        Map<String, TornSettingOcPlanProfileDO> profiles = Map.of(
                CHILD_KEY, childProfile, ALPHA_KEY, alphaProfile);
        Map<String, List<OcPlanSlot>> templates = Map.of(
                CHILD_KEY, List.of(slot("Worker#1")),
                ALPHA_KEY, List.of(slot("Worker#1"), slot("Worker#2")));
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, Set.of(CHILD_KEY, ALPHA_KEY), List.of());
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(1L, NOW, policy,
                List.of(child), Map.of(), members, profiles, List.of(), templates,
                Set.of(), Map.of(), List.of());

        OcExistingTimelineReconstructor.ReconstructionResult reconstruction =
                reconstructor.reconstruct(snapshot, new OcChainTemplateResult(List.of(), List.of()));
        OcTeamDemand alphaTemplate = new OcTeamDemand(0L, "Alpha", 8, null,
                NOW.plusDays(7), false, templates.get(ALPHA_KEY), Set.of(), Set.of());
        return new OcRefreshSafetyRequest(members, reconstruction.unprovableMemberIds(),
                reconstruction.obligations(), reconstruction.chainSuccessorsByKey(),
                List.of(alphaTemplate), List.of(), NOW);
    }

    private List<OcMemberCandidate> memberSetForZeroDelay() {
        return List.of(
                member(1L, NOW, Map.of(
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90)),
                member(2L, NOW, Map.of(
                        OcMemberCandidate.capabilityKey(8, "Alpha", "Worker"), 90)),
                member(3L, NOW.plusHours(36), Map.of(
                        OcMemberCandidate.capabilityKey(8, "Alpha", "Worker"), 90)));
    }

    private OcMemberCandidate member(long userId, LocalDateTime availableAt,
                                     Map<String, Integer> passRates) {
        return new OcMemberCandidate(userId, "user" + userId, availableAt, false,
                passRates, Map.of());
    }

    private OcPlanSlot slot(String code) {
        return new OcPlanSlot(code, "Worker", 60, 1, null);
    }

    private TornSettingOcPlanProfileDO profile(String name, int rank, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(rank);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        return profile;
    }

    private OcRefreshVectorSearcher.OcVectorSearchOutcome search(OcRefreshSafetyRequest request) {
        OcRefreshVectorSearcher searcher = new OcRefreshVectorSearcher(3,
                new OcTimelineEventScheduler());
        return searcher.search(request, evidence(), System.nanoTime()
                + Duration.ofSeconds(5).toNanos(), OcProofWindow.valid(NOW.plusDays(7)));
    }

    private Map<String, OcValueEvidence> evidence() {
        return Map.of(ALPHA_KEY, new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                ALPHA_VALUE, 2, NOW.plusHours(24), true, 8, 2, 1));
    }

    private OcRefreshSafetyResult safety(OcRefreshVectorSearcher.OcVectorSearchOutcome outcome) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE, Set.of(),
                false, Set.of(), List.of(), null, null);
        return new OcRefreshSafetyResult(assessment, outcome.candidates(), false, 1L,
                OcSearchTelemetry.empty(), List.of());
    }

    private OcRefreshSafetyResult safetyWith(List<SafeCandidate> candidates) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE, Set.of(),
                false, Set.of(), List.of(), null, null);
        return new OcRefreshSafetyResult(assessment, candidates, false, 1L,
                OcSearchTelemetry.empty(), List.of());
    }

    private SafeCandidate candidate(OcRefreshVectorSearcher.OcVectorSearchOutcome outcome,
                                    int normal, int high) {
        return outcome.candidates().stream()
                .filter(item -> item.vector().equals(new OcRefreshVector(normal, high)))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing vector " + normal + "," + high + " candidates="
                                + outcome.candidates()));
    }

    private SafeCandidate candidate(OcRefreshVector vector, SafeCandidate.PauseTier tier,
                                    OcTimelineValueSummary summary,
                                    boolean strictlyBetter) {
        return new SafeCandidate(vector, tier, summary, 1,
                OcValueEvidence.Level.OBSERVED_REWARD, true, strictlyBetter);
    }

    private OcTimelineValueSummary zeroDelaySummary() {
        OcTimelineObligation existing = new OcTimelineObligation("oc:1",
                OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR,
                new OcTeamDemand(1L, "Child", 9, null, NOW.plusDays(7), true,
                        List.of(slot("Worker#1")), Set.of(), Set.of()),
                NOW.plusDays(7), NOW);
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, NOW, Map.of(
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90))), Set.of(),
                List.of(existing), Map.of(), List.of(), List.of(), NOW);
        OcTimelineState state = new OcTimelineState(request);
        state.addAnchor(new OcLiquidityAnchor(existing.key(), NOW.plusHours(24), 1, false));
        state.addEvent(new OcTimelineEvent(NOW.plusHours(24),
                OcTimelineEvent.EventType.COMPLETION_RELEASE, existing.key()));
        return accumulator.accumulate(state, false, request);
    }

    private OcTimelineValueSummary withValue(OcTimelineValueSummary summary) {
        return new OcTimelineValueSummary(ALPHA_VALUE,
                summary.actualIncrementalMemberDays(),
                summary.actualNewPause(),
                summary.existingObligationDelay(),
                summary.avoidableExpiryPressure(),
                summary.guaranteedReleaseAt(),
                8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
    }
}
