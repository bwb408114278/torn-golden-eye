package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineValueSummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

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
        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(candidate, baseline));
    }

    @Test
    @DisplayName("收益停转候选金额更高且无负向延迟时严格优于零停转基准")
    void shouldAcceptPauseCandidateWithHigherValueAndNoDelay() {
        OcTimelineValueSummary baseline = new OcTimelineValueSummary(BigDecimal.valueOf(300),
                10, Duration.ZERO, Duration.ZERO, true, NOW, 8, 2, 1,
                OcValueEvidence.Level.OBSERVED_REWARD);
        OcTimelineValueSummary candidate = new OcTimelineValueSummary(BigDecimal.valueOf(500),
                10, Duration.ofHours(2), Duration.ZERO, true, NOW.plusHours(2),
                8, 2, 1, OcValueEvidence.Level.OBSERVED_REWARD);

        assertTrue(comparator.compareTimelineValue(candidate, baseline) < 0);
        assertTrue(comparator.isStrictlyBetterThanZeroPauseBaseline(candidate, baseline));
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

        assertFalse(comparator.isStrictlyBetterThanZeroPauseBaseline(candidate, baseline));
    }
}
