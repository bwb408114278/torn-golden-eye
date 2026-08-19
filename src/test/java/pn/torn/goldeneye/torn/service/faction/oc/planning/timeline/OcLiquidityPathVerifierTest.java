package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

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

    @Test
    @DisplayName("再投入形成无关锚点时替换不成立")
    void shouldNotMarkReplacementForUnrelatedAnchor() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(1L, NOW.plusHours(8), NOW.plusHours(20),
                        IntervalSource.RANDOM_CANDIDATE),
                interval(2L, NOW, NOW.plusHours(32), IntervalSource.PLANNED_EMPTY));

        List<OcLiquidityAnchor> verified = verifier.verifyReplacementAnchors(anchors,
                intervals);

        assertFalse(verified.get(1).replacesPrevious(),
                "当前锚点必须由释放成员参与形成，无关锚点不得被标记为替换");
    }

    @Test
    @DisplayName("释放资源被消耗且结构上无接续完整释放时连续路径不成立")
    void shouldRejectConsumedAnchorWithoutStructuralSuccessor() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(1L, NOW.plusHours(8), NOW.plusHours(24),
                        IntervalSource.RANDOM_CANDIDATE),
                interval(2L, NOW, NOW.plusHours(32), IntervalSource.PLANNED_EMPTY));

        assertFalse(verifier.hasContinuousCompletionPath(anchors, intervals, null),
                "再投入区间未结束在任何锚点释放时间上，不得视为连续完成—释放路径");
    }

    @Test
    @DisplayName("窗口内消耗的资源必须在窗口内形成接续完整释放")
    void shouldRequireInWindowSuccessorForInWindowConsumedAnchor() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC),
                interval(1L, NOW.plusHours(8), NOW.plusHours(32),
                        IntervalSource.PLANNED_EMPTY));

        assertFalse(verifier.hasContinuousCompletionPath(anchors, intervals,
                        NOW.plusHours(16)),
                "接续释放落在证明窗口之后时，窗口内连续路径不成立");
        assertTrue(verifier.hasContinuousCompletionPath(anchors, intervals,
                        NOW.plusHours(32)),
                "接续释放落在证明窗口内时，窗口内连续路径成立");
        assertTrue(verifier.hasContinuousCompletionPath(anchors, intervals, null),
                "不施加窗口约束时按结构性连续判定");
    }

    @Test
    @DisplayName("未被消耗的锚点不要求接续释放")
    void shouldNotRequireSuccessorForIdleReleasedMembers() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 1, false));
        List<OcMemberInterval> intervals = List.of(
                interval(1L, NOW, NOW.plusHours(8), IntervalSource.EXISTING_OC));

        assertTrue(verifier.hasContinuousCompletionPath(anchors, intervals,
                NOW.plusHours(16)));
    }

    private OcMemberInterval interval(long userId, LocalDateTime from, LocalDateTime until,
                                      IntervalSource source) {
        return new OcMemberInterval(userId, from, until, source);
    }
}
