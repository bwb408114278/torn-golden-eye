package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberInterval;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberInterval.IntervalSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流动性锚点验证器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("流动性锚点验证")
class OcLiquidityPathVerifierTest {
    private final OcLiquidityPathVerifier verifier = new OcLiquidityPathVerifier();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("存在完整释放锚点时应有连续流动性")
    void shouldHaveContinuousAnchorWhenFullReleaseExists() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 3, false));

        assertTrue(verifier.hasContinuousAnchor(anchors));
        assertEquals(NOW.plusHours(8), verifier.nextCriticalReleaseAt(anchors));
    }

    @Test
    @DisplayName("释放成员数为零的锚点不构成连续流动性")
    void shouldNotTreatZeroReleaseAnchorAsContinuous() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 0, false));

        assertFalse(verifier.hasContinuousAnchor(anchors));
    }

    @Test
    @DisplayName("无锚点时不得声称流动性")
    void shouldRejectEmptyAnchorChain() {
        assertFalse(verifier.hasContinuousAnchor(List.of()));
        assertFalse(verifier.hasContinuousAnchor(null));
        assertNull(verifier.nextCriticalReleaseAt(List.of()));
    }

    @Test
    @DisplayName("前一锚点释放成员再次投入并完整释放时替换成立")
    void shouldMarkReplacementWhenReleasedMemberReinvested() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(1L, NOW.plusHours(8), NOW.plusHours(32),
                        IntervalSource.PLANNED_EMPTY));

        List<OcLiquidityAnchor> verified = verifier.verifyReplacementAnchors(anchors,
                intervals);

        assertFalse(verified.get(0).replacesPrevious());
        assertTrue(verified.get(1).replacesPrevious());
    }

    @Test
    @DisplayName("释放成员未被再次投入时替换不成立")
    void shouldNotMarkReplacementWithoutReinvestment() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(2L, NOW, NOW.plusHours(32), IntervalSource.PLANNED_EMPTY));

        List<OcLiquidityAnchor> verified = verifier.verifyReplacementAnchors(anchors,
                intervals);

        assertFalse(verified.get(1).replacesPrevious());
    }

    @Test
    @DisplayName("再投入在当前锚点释放之后完成时替换不成立")
    void shouldNotMarkReplacementWhenReinvestmentCompletesLater() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(1L, NOW.plusHours(8), NOW.plusHours(56),
                        IntervalSource.RANDOM_CANDIDATE));

        List<OcLiquidityAnchor> verified = verifier.verifyReplacementAnchors(anchors,
                intervals);

        assertFalse(verified.get(1).replacesPrevious());
    }

    private OcMemberInterval interval(long userId, LocalDateTime from, LocalDateTime until,
                                      IntervalSource source) {
        return new OcMemberInterval(userId, from, until, source);
    }
}
