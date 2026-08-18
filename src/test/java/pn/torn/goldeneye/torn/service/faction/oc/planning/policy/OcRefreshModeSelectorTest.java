package pn.torn.goldeneye.torn.service.faction.oc.planning.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcEconomicValueComparator;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC刷新模式选点器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("OC刷新模式选点")
class OcRefreshModeSelectorTest {

    private final OcRefreshModeSelector selector = new OcRefreshModeSelector();

    @Test
    @DisplayName("已证明安全向量存在时不得因旧比例取整返回0")
    void shouldNotReturnZeroWhenSafeVectorExists() {
        OcRefreshSafetyResult safety = result(List.of(candidate(new OcRefreshVector(1, 0),
                SafeCandidate.PauseTier.ZERO_PAUSE)), OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("保守模式应拒绝需要停转容忍的候选，均衡和收益按层级放宽")
    void shouldFilterCandidatesByModePauseTier() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.WITHIN_PROFIT,
                                BigDecimal.valueOf(2000)),
                        candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(2, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "收益级停转候选严格优于零停转基准时收益模式必须可选");
    }

    @Test
    @DisplayName("收益级停转候选价值与零停转基准相同时不得被收益模式选中")
    void shouldNotSelectProfitTierCandidateNotStrictlyBetterThanBaseline() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.WITHIN_PROFIT,
                                BigDecimal.valueOf(1000), false),
                        candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.PROFIT),
                "未严格优于零停转替代时间线的收益级候选必须fail-closed");
    }

    @Test
    @DisplayName("无零停转正向量基准时收益级停转候选不得被收益模式选中")
    void shouldNotSelectProfitTierCandidateWithoutZeroPauseBaseline() {
        OcRefreshSafetyResult safety = result(List.of(candidate(new OcRefreshVector(2, 0),
                        SafeCandidate.PauseTier.WITHIN_PROFIT, BigDecimal.valueOf(2000))),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(0, 0), selector.select(safety, OcPlanMode.PROFIT),
                "候选集合中无可行的零新增停转替代时间线时必须fail-closed");
    }

    @Test
    @DisplayName("卡死风险时应硬阻断全部模式")
    void shouldBlockAllModesWhenDeadlockRiskPresent() {
        OcRefreshSafetyResult safety = result(
                List.of(candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of(OcRiskFlagEnum.DEADLOCK_RISK));

        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("已启动链义务风险时应硬阻断全部模式")
    void shouldBlockAllModesWhenHardObligationAtRisk() {
        OcRefreshSafetyResult safety = result(
                List.of(candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_INFEASIBLE, Set.of(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));

        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("收益模式在金额证据缺失时不得选择需要停转的候选")
    void shouldNotSelectPauseCandidateWithoutValueEvidenceInProfitMode() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.WITHIN_BALANCED,
                                null),
                        candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE,
                                BigDecimal.valueOf(100))),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(2, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("收益模式应按窗口总价值选择而不是高阶机械优先")
    void shouldSelectByWindowValueInsteadOfHighPoolPreference() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(0, 1), SafeCandidate.PauseTier.ZERO_PAUSE,
                                BigDecimal.valueOf(100)),
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.ZERO_PAUSE,
                                BigDecimal.valueOf(500))),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(2, 0),
                selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("无安全候选时应返回零向量")
    void shouldReturnZeroWhenNoSafeCandidateExists() {
        OcRefreshSafetyResult empty = result(List.of(), OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS,
                Set.of());

        assertEquals(new OcRefreshVector(0, 0),
                selector.select(empty, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("保守模式在零停转候选中应选择更大联合向量")
    void shouldPreferLargerJointVectorInConservativeMode() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(3, 0), SafeCandidate.PauseTier.ZERO_PAUSE),
                        candidate(new OcRefreshVector(2, 1), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(3, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
    }

    @Test
    @DisplayName("收益模式在零停转但金额证据不足的正向量上不得提高建议")
    void shouldNotSelectZeroPausePositiveVectorWithoutUsableValueEvidenceInProfitMode() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.ZERO_PAUSE,
                                null, OcValueEvidence.Level.PRIOR_ONLY)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(0, 0), selector.select(safety, OcPlanMode.PROFIT));
        assertEquals(new OcRefreshVector(2, 0), selector.select(safety, OcPlanMode.BALANCED));
    }

    @Test
    @DisplayName("收益模式可按冻结规则选择收益下界证据的正向量")
    void shouldSelectRewardFloorEvidenceVectorInProfitMode() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE,
                                BigDecimal.valueOf(80), OcValueEvidence.Level.REWARD_FLOOR)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 0), selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("链内任一节点证据不可用时聚合候选不得提高收益建议")
    void shouldNotSelectAggregatedChainCandidateWithoutUsableEvidenceInProfitMode() {
        OcRefreshSafetyResult safety = result(List.of(
                        candidate(new OcRefreshVector(0, 1), SafeCandidate.PauseTier.ZERO_PAUSE,
                                null, OcValueEvidence.Level.PRIOR_ONLY)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(0, 0), selector.select(safety, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("可区分的两个PRIOR_ONLY正向量应选择严格更优者")
    void shouldSelectStrictlyBetterPriorOnlyVectorWhenDistinguishable() {
        SafeCandidate weaker = priorCandidate(new OcRefreshVector(1, 0), 8, 2, 1);
        SafeCandidate better = priorCandidate(new OcRefreshVector(1, 1), 9, 4, 2);
        OcRefreshSafetyResult safety = result(List.of(weaker, better),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 1), selector.select(safety, OcPlanMode.PROFIT),
                "先验可稳定区分时收益模式必须选择严格更优的PRIOR_ONLY正向量");
    }

    @Test
    @DisplayName("先验完全相同的正向量不得因收益理由提高建议且记录经济证据不足")
    void shouldNotRaiseProfitSuggestionWhenPriorIdentical() {
        SafeCandidate first = priorCandidate(new OcRefreshVector(1, 0), 8, 2, 1);
        SafeCandidate second = priorCandidate(new OcRefreshVector(2, 0), 8, 2, 1);
        OcRefreshSafetyResult safety = result(List.of(first, second),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        Optional<SafeCandidate> selected = selector.selectCandidate(safety, OcPlanMode.PROFIT);
        assertFalse(selected.isPresent(),
                "先验完全相同时不得提高收益建议，回落等价零向量");
        assertTrue(selector.economicEvidenceInsufficient(safety.candidates(), selected.orElse(null)),
                "先验不可区分导致回落零向量时必须记录经济证据不足");
    }

    @Test
    @DisplayName("可区分PRIOR_ONLY正向量被选中时不标记经济证据不足")
    void shouldNotMarkEvidenceInsufficientWhenPriorSelectedAndDistinguishable() {
        SafeCandidate better = priorCandidate(new OcRefreshVector(1, 1), 9, 4, 2);
        SafeCandidate weaker = priorCandidate(new OcRefreshVector(1, 0), 8, 2, 1);
        OcRefreshSafetyResult safety = result(List.of(better, weaker),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        Optional<SafeCandidate> selected = selector.selectCandidate(safety, OcPlanMode.PROFIT);
        assertTrue(selected.isPresent());
        assertFalse(selector.economicEvidenceInsufficient(safety.candidates(), selected.orElse(null)));
    }

    private OcRefreshSafetyResult result(List<SafeCandidate> candidates,
                                         OcProofStatusEnum proofStatus,
                                         Set<OcRiskFlagEnum> riskFlags) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, proofStatus, riskFlags, false, Set.of(),
                List.of(), null, null);
        SafeCandidate baseline = new OcEconomicValueComparator().bestZeroPauseBaseline(candidates);
        return new OcRefreshSafetyResult(assessment, candidates, false, 1L,
                OcSearchTelemetry.empty(), List.of(), baseline, baseline != null);
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier) {
        return candidate(vector, tier, BigDecimal.valueOf(1000));
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier, BigDecimal value) {
        return candidate(vector, tier, value, OcValueEvidence.Level.OBSERVED_REWARD);
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier, BigDecimal value,
                                    OcValueEvidence.Level level) {
        return candidate(vector, tier, value, level, true);
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier, BigDecimal value,
                                    boolean strictlyBetter) {
        return candidate(vector, tier, value, OcValueEvidence.Level.OBSERVED_REWARD,
                strictlyBetter);
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier, BigDecimal value,
                                    OcValueEvidence.Level level, boolean strictlyBetter) {
        OcTimelineValueSummary summary = new OcTimelineValueSummary(value, 10,
                Duration.ZERO, Duration.ZERO, true, null, 8, 2, 1, level);
        return new SafeCandidate(vector, tier, summary, 1, level, true, strictlyBetter);
    }

    /**
     * 构造金额缺失、携带指定先验元组的PRIOR_ONLY候选。
     *
     * @param vector       刷新向量
     * @param highestRank  最高等级
     * @param totalMembers 完整链总需人数
     * @param chainNodes   链节点数
     * @return PRIOR_ONLY安全候选
     */
    private SafeCandidate priorCandidate(OcRefreshVector vector, int highestRank,
                                         int totalMembers, int chainNodes) {
        OcTimelineValueSummary summary = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, true, null, highestRank, totalMembers,
                chainNodes, OcValueEvidence.Level.PRIOR_ONLY);
        return new SafeCandidate(vector, SafeCandidate.PauseTier.ZERO_PAUSE, summary, 1,
                OcValueEvidence.Level.PRIOR_ONLY, true, true);
    }
}
