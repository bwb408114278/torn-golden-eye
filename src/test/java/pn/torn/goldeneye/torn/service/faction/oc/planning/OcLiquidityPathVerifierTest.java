package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;

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
    @DisplayName("无锚点时不允许声称流动性或替换路径")
    void shouldRejectEmptyAnchorChain() {
        assertFalse(verifier.hasContinuousAnchor(List.of()));
        assertFalse(verifier.hasContinuousAnchor(null));
        assertFalse(verifier.hasReplacementPath(List.of()));
        assertNull(verifier.nextCriticalReleaseAt(List.of()));
    }

    @Test
    @DisplayName("锚点替换后仍应存在替换路径")
    void shouldAllowAnchorReplacement() {
        List<OcLiquidityAnchor> anchors = List.of(
                new OcLiquidityAnchor("oc:1", NOW.plusHours(8), 3, false),
                new OcLiquidityAnchor("oc:2", NOW.plusHours(32), 3, true));

        assertTrue(verifier.hasReplacementPath(anchors));
        assertEquals(NOW.plusHours(8), verifier.nextCriticalReleaseAt(anchors));
    }
}
