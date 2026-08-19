package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineValueSummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 候选时间线经济价值比较器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("候选时间线经济价值比较")
class OcEconomicValueComparatorTest {
    private final OcEconomicValueComparator comparator = new OcEconomicValueComparator();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("窗口总价值更高者更优")
    void shouldPreferHigherWindowValue() {
        int result = comparator.compare(BigDecimal.valueOf(500), 10, NOW,
                BigDecimal.valueOf(300), 10, NOW);

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("总价值接近时应按增量单位成员人天比较")
    void shouldComparePerMemberDayWhenValueEqual() {
        int result = comparator.compare(BigDecimal.valueOf(300), 10, NOW,
                BigDecimal.valueOf(300), 21, NOW);

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("价值与人天相同时更早释放者更优")
    void shouldPreferEarlierReleaseWhenValueEqual() {
        int result = comparator.compare(BigDecimal.valueOf(300), 10, NOW.plusHours(8),
                BigDecimal.valueOf(300), 10, NOW.plusHours(16));

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("金额证据不足时不得据价值区分候选")
    void shouldNotDistinguishWhenValueEvidenceInsufficient() {
        int result = comparator.compare(null, 10, NOW, BigDecimal.valueOf(300), 10, NOW);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("金额均缺失时应按第三层业务先验区分候选")
    void shouldCompareByPriorWhenBothValuesMissing() {
        OcTimelineValueSummary left = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, true, NOW, 9, 5, 1,
                OcValueEvidence.Level.PRIOR_ONLY);
        OcTimelineValueSummary right = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, true, NOW, 8, 5, 1,
                OcValueEvidence.Level.PRIOR_ONLY);

        assertTrue(comparator.compareTimelineValue(left, right) < 0);
    }

    @Test
    @DisplayName("收益停转候选导致既有义务延迟时不得优于零停转基准")
    void shouldRejectPauseCandidateThatDelaysExistingObligation() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(BigDecimal.valueOf(300),
                10, Duration.ZERO, Duration.ZERO, true, NOW, 8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(BigDecimal.valueOf(300),
                10, Duration.ofHours(2), Duration.ofHours(10), true, NOW.plusHours(10),
                8, 2, 1, OcValueEvidence.Level.OBSERVED_REWARD);

        assertTrue(comparator.compareTimelineValue(candidate, baseline) > 0);
        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(
                safeCandidate(candidate, 2, SafeCandidate.PauseTier.WITHIN_PROFIT),
                safeCandidate(baseline, 2, SafeCandidate.PauseTier.ZERO_PAUSE)));
    }

    @Test
    @DisplayName("收益停转候选金额更高且无负向延迟时严格优于零停转基准")
    void shouldAcceptPauseCandidateWithHigherValueAndNoDelay() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(BigDecimal.valueOf(300),
                10, Duration.ZERO, Duration.ZERO, true, NOW, 8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(BigDecimal.valueOf(500),
                10, Duration.ofHours(2), Duration.ZERO, true, NOW,
                8, 2, 1, OcValueEvidence.Level.OBSERVED_REWARD);

        assertTrue(comparator.compareTimelineValue(candidate, baseline) < 0);
        assertTrue(comparator.isStrictlyBetterThanZeroPauseBaseline(
                safeCandidate(candidate, 2, SafeCandidate.PauseTier.WITHIN_PROFIT),
                safeCandidate(baseline, 2, SafeCandidate.PauseTier.ZERO_PAUSE)));
    }

    @Test
    @DisplayName("零停转基准证据不足时不得判定收益停转候选严格更优")
    void shouldRejectWhenBaselineEvidenceInsufficient() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, true, NOW, 0, 0, 1,
                OcValueEvidence.Level.INSUFFICIENT);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(BigDecimal.valueOf(500),
                10, Duration.ofHours(2), Duration.ZERO, true, NOW.plusHours(2),
                8, 2, 1, OcValueEvidence.Level.OBSERVED_REWARD);

        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(
                safeCandidate(candidate, 2, SafeCandidate.PauseTier.WITHIN_PROFIT),
                safeCandidate(baseline, 2, SafeCandidate.PauseTier.ZERO_PAUSE)));
    }

    @Test
    @DisplayName("金额更高但新增无人OC可避免过期时必须拒绝")
    void shouldRejectHigherValueWhenExpiryPressureWorsens() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                true, NOW);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额更高但单位成员人天价值降低时必须拒绝")
    void shouldRejectHigherValueWhenUnitMemberDayValueDrops() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 100,
                false, NOW);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额相同且候选人天更低时允许严格更优")
    void shouldAcceptEqualValueWithFewerMemberDays() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 20,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(300), 10,
                false, NOW);

        assertTrue(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额更高但保证释放延后时必须拒绝")
    void shouldRejectHigherValueWhenReleaseIsLater() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                false, NOW.plusHours(1));

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("候选锚点减少时必须拒绝")
    void shouldRejectWhenAnchorCountDrops() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                false, NOW);

        assertFalse(strict(candidate, 1, baseline, 2));
    }

    @Test
    @DisplayName("候选释放时间缺失时必须fail-closed")
    void shouldFailClosedWhenCandidateReleaseIsMissing() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                false, null);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("基准释放时间缺失时必须fail-closed")
    void shouldFailClosedWhenBaselineReleaseIsMissing() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, null);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                false, NOW);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额均缺失但先验完整时应按先验严格比较")
    void shouldCompareByCompletePriorWhenValuesAreMissing() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 8, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 9, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);

        assertTrue(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额缺失且先验不完整时必须fail-closed")
    void shouldFailClosedWhenPriorEvidenceIsIncomplete() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 0, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 9, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("实际增量成员人天为0时必须fail-closed")
    void shouldFailClosedWhenMemberDaysAreZero() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 0,
                false, NOW);

        assertFalse(strict(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("零停转正向基准应优先于零刷新保底")
    void shouldPreferPositiveZeroPauseBaseline() {
        SafeCandidate fallback = safeCandidate(summary(BigDecimal.valueOf(100), 10,
                false, NOW), 1, SafeCandidate.PauseTier.ZERO_PAUSE);
        SafeCandidate positive = new SafeCandidate(new OcRefreshVector(1, 0),
                SafeCandidate.PauseTier.ZERO_PAUSE,
                summary(BigDecimal.valueOf(200), 10, false, NOW), 2,
                OcValueEvidence.Level.OBSERVED_REWARD, true, true, false);

        assertEquals(positive, comparator.bestZeroPauseBaseline(List.of(fallback, positive)));
    }

    @Test
    @DisplayName("同价值零停转基准应优先选择锚点更多者且不受输入顺序影响")
    void shouldPreferMoreAnchorsForEqualValueBaselineRegardlessOfOrder() {
        SafeCandidate fewerAnchors = candidate(new OcRefreshVector(1, 0),
                summary(BigDecimal.valueOf(300), 10, false, NOW), 1,
                SafeCandidate.PauseTier.ZERO_PAUSE);
        SafeCandidate moreAnchors = candidate(new OcRefreshVector(0, 1),
                summary(BigDecimal.valueOf(300), 10, false, NOW), 3,
                SafeCandidate.PauseTier.ZERO_PAUSE);

        assertEquals(moreAnchors,
                comparator.bestZeroPauseBaseline(List.of(fewerAnchors, moreAnchors)));
        assertEquals(moreAnchors,
                comparator.bestZeroPauseBaseline(List.of(moreAnchors, fewerAnchors)));
    }

    @Test
    @DisplayName("同价值同锚点基准应按刷新向量稳定决胜")
    void shouldUseStableVectorTieBreakForEqualValueAndAnchors() {
        SafeCandidate normalFirst = candidate(new OcRefreshVector(1, 0),
                summary(BigDecimal.valueOf(300), 10, false, NOW), 2,
                SafeCandidate.PauseTier.ZERO_PAUSE);
        SafeCandidate highOnly = candidate(new OcRefreshVector(0, 1),
                summary(BigDecimal.valueOf(300), 10, false, NOW), 2,
                SafeCandidate.PauseTier.ZERO_PAUSE);

        assertEquals(normalFirst,
                comparator.bestZeroPauseBaseline(List.of(highOnly, normalFirst)));
        assertEquals(normalFirst,
                comparator.bestZeroPauseBaseline(List.of(normalFirst, highOnly)));
    }

    @Test
    @DisplayName("收益候选锚点介于同价值基准时必须按最强基准稳定拒绝")
    void shouldUseStrongestEqualValueBaselineForAnchorGate() {
        OcTimelineValueSummary baselineSummary = summary(BigDecimal.valueOf(300), 10,
                false, NOW);
        SafeCandidate weakBaseline = candidate(new OcRefreshVector(1, 0), baselineSummary,
                1, SafeCandidate.PauseTier.ZERO_PAUSE);
        SafeCandidate strongBaseline = candidate(new OcRefreshVector(0, 1), baselineSummary,
                3, SafeCandidate.PauseTier.ZERO_PAUSE);
        SafeCandidate profit = candidate(new OcRefreshVector(2, 0),
                summary(BigDecimal.valueOf(500), 10, false, NOW), 2,
                SafeCandidate.PauseTier.WITHIN_PROFIT);

        SafeCandidate firstOrderBaseline = comparator.bestZeroPauseBaseline(
                List.of(weakBaseline, strongBaseline));
        SafeCandidate secondOrderBaseline = comparator.bestZeroPauseBaseline(
                List.of(strongBaseline, weakBaseline));

        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(profit,
                firstOrderBaseline));
        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(profit,
                secondOrderBaseline));
    }

    @Test
    @DisplayName("无零停转候选时基准应为空")
    void shouldReturnNoBaselineWhenZeroPauseCandidateIsAbsent() {
        SafeCandidate candidate = safeCandidate(summary(BigDecimal.valueOf(200), 10,
                false, NOW), 1, SafeCandidate.PauseTier.WITHIN_PROFIT);

        assertNull(comparator.bestZeroPauseBaseline(List.of(candidate)));
    }

    @Test
    @DisplayName("均衡停转候选价值更低且释放更晚时必须拒绝准入")
    void shouldRejectBalancedPauseCandidateWithLowerValueAndLaterRelease() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(200), 10,
                false, NOW.plusHours(1));

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选金额不可比较且释放相同时必须拒绝准入")
    void shouldRejectBalancedPauseCandidateWhenValueIncomparableAndReleaseEqual() {
        OcTimelineValueSummary baseline = summary(null, 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10, false, NOW);

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("双方金额缺失且先验不可比时均衡准入必须fail-closed")
    void shouldRejectBalancedPauseCandidateWhenPriorNotComparable() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 0, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 9, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选价值严格提高且共同门禁不差时允许准入")
    void shouldAdmitBalancedPauseCandidateWithStrictValueImprovement() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 100, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10, false, NOW);

        assertTrue(balanced(candidate, 2, baseline, 2),
                "均衡准入只按金额严格提高判断，不适用单位成员人天门禁");
    }

    @Test
    @DisplayName("双方金额缺失但完整先验严格更优时均衡准入按先验允许")
    void shouldAdmitBalancedPauseCandidateByPriorWhenValuesMissing() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 8, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(null, 10,
                Duration.ZERO, Duration.ZERO, false, NOW, 9, 2, 1,
                OcValueEvidence.Level.PRIOR_ONLY);

        assertTrue(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("金额不可比较但完整释放严格提前时均衡准入允许")
    void shouldAdmitBalancedPauseCandidateWhenReleaseStrictlyEarlier() {
        OcTimelineValueSummary baseline = summary(null, 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10,
                false, NOW.minusHours(1));

        assertTrue(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选价值更高但既有义务延后时必须拒绝")
    void shouldRejectBalancedPauseCandidateThatDelaysExistingObligation() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(BigDecimal.valueOf(500),
                10, Duration.ofHours(2), Duration.ofHours(10), false, NOW, 8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选价值更高但新增可避免过期时必须拒绝")
    void shouldRejectBalancedPauseCandidateWhenExpiryPressureWorsens() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10, true, NOW);

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选价值更高但锚点减少时必须拒绝")
    void shouldRejectBalancedPauseCandidateWhenAnchorCountDrops() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10, false, NOW);

        assertFalse(balanced(candidate, 1, baseline, 2));
    }

    @Test
    @DisplayName("均衡停转候选价值相同且释放相同时必须拒绝准入")
    void shouldRejectBalancedPauseCandidateWithoutStrictImprovement() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(300), 10, false, NOW);

        assertFalse(balanced(candidate, 2, baseline, 2));
    }

    @Test
    @DisplayName("非均衡级候选或非零停转基准不得进入均衡准入判断")
    void shouldRejectBalancedAdmissionForWrongTierInputs() {
        OcTimelineValueSummary baseline = summary(BigDecimal.valueOf(300), 10, false, NOW);
        OcTimelineValueSummary candidate = summary(BigDecimal.valueOf(500), 10, false, NOW);

        assertFalse(comparator.isEligibleForBalancedPause(null, null));
        assertFalse(comparator.isEligibleForBalancedPause(
                        safeCandidate(candidate, 2, SafeCandidate.PauseTier.WITHIN_PROFIT),
                        safeCandidate(baseline, 2, SafeCandidate.PauseTier.ZERO_PAUSE)),
                "收益级候选不得借均衡准入绕过单位成员人天门禁");
        assertFalse(comparator.isEligibleForBalancedPause(
                        safeCandidate(candidate, 2, SafeCandidate.PauseTier.WITHIN_BALANCED),
                        safeCandidate(baseline, 2, SafeCandidate.PauseTier.WITHIN_BALANCED)),
                "基准停转层级不为零时必须拒绝");
    }

    private boolean balanced(OcTimelineValueSummary candidate, int candidateAnchors,
                             OcTimelineValueSummary baseline, int baselineAnchors) {
        return comparator.isEligibleForBalancedPause(
                safeCandidate(candidate, candidateAnchors,
                        SafeCandidate.PauseTier.WITHIN_BALANCED),
                safeCandidate(baseline, baselineAnchors,
                        SafeCandidate.PauseTier.ZERO_PAUSE));
    }

    private boolean strict(OcTimelineValueSummary candidate, int candidateAnchors,
                           OcTimelineValueSummary baseline, int baselineAnchors) {
        return comparator.isStrictlyBetterThanZeroPauseBaseline(
                safeCandidate(candidate, candidateAnchors, SafeCandidate.PauseTier.WITHIN_PROFIT),
                safeCandidate(baseline, baselineAnchors, SafeCandidate.PauseTier.ZERO_PAUSE));
    }

    private OcTimelineValueSummary summary(BigDecimal value, int memberDays,
                                           boolean avoidableExpiry, LocalDateTime releaseAt) {
        return new OcTimelineValueSummary(value, memberDays, Duration.ZERO, Duration.ZERO,
                avoidableExpiry, releaseAt, 8, 2, 1, OcValueEvidence.Level.OBSERVED_REWARD);
    }

    private SafeCandidate safeCandidate(OcTimelineValueSummary summary, int anchorCount,
                                        SafeCandidate.PauseTier pauseTier) {
        return candidate(new OcRefreshVector(1, 0), summary, anchorCount, pauseTier);
    }

    private SafeCandidate candidate(OcRefreshVector vector, OcTimelineValueSummary summary,
                                    int anchorCount, SafeCandidate.PauseTier pauseTier) {
        return new SafeCandidate(vector, pauseTier, summary, anchorCount,
                summary.evidenceLevel(), true, true, false);
    }
}
