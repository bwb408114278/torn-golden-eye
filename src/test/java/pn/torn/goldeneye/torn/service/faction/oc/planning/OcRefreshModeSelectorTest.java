package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC刷新模式选点器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
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
                        candidate(new OcRefreshVector(2, 0), SafeCandidate.PauseTier.WITHIN_PROFIT),
                        candidate(new OcRefreshVector(1, 0), SafeCandidate.PauseTier.ZERO_PAUSE)),
                OcProofStatusEnum.PROVEN_SAFE, Set.of());

        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(2, 0),
                selector.select(safety, OcPlanMode.PROFIT));
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

    private OcRefreshSafetyResult result(List<SafeCandidate> candidates,
                                         OcProofStatusEnum proofStatus,
                                         Set<OcRiskFlagEnum> riskFlags) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                OcConfigurationStatusEnum.VALID, proofStatus, riskFlags, false, Set.of(),
                List.of(), null, null);
        return new OcRefreshSafetyResult(assessment, candidates, false, 1L, List.of());
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier) {
        return candidate(vector, tier, BigDecimal.valueOf(1000));
    }

    private SafeCandidate candidate(OcRefreshVector vector,
                                    SafeCandidate.PauseTier tier, BigDecimal value) {
        return new SafeCandidate(vector, tier, value, 10, null, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
    }
}
