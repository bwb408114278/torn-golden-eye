package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPauseAssessment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模式停转政策评估器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("模式停转政策评估")
class OcPausePolicyEvaluatorTest {
    private final OcPausePolicyEvaluator evaluator = new OcPausePolicyEvaluator();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("保守模式不允许任何主动新增停转")
    void conservativeShouldRejectAnyNewPause() {
        List<OcPauseAssessment> pauses = List.of(
                new OcPauseAssessment("oc:1", Duration.ofMinutes(30), NOW, false));

        assertFalse(evaluator.withinPolicy(pauses, OcPlanMode.CONSERVATIVE));
        assertTrue(evaluator.withinPolicy(pauses, OcPlanMode.BALANCED));
        assertTrue(evaluator.withinPolicy(pauses, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("均衡模式单次新增停转不超过6小时")
    void balancedShouldAllowPauseWithinSixHours() {
        List<OcPauseAssessment> within = List.of(
                new OcPauseAssessment("oc:1", Duration.ofHours(6), NOW, false));
        List<OcPauseAssessment> beyond = List.of(
                new OcPauseAssessment("oc:1", Duration.ofHours(6).plusMinutes(1),
                        NOW, false));

        assertTrue(evaluator.withinPolicy(within, OcPlanMode.BALANCED));
        assertFalse(evaluator.withinPolicy(beyond, OcPlanMode.BALANCED));
        assertTrue(evaluator.withinPolicy(beyond, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("收益模式单次新增停转不超过12小时")
    void profitShouldAllowPauseWithinTwelveHours() {
        List<OcPauseAssessment> beyond = List.of(
                new OcPauseAssessment("oc:1", Duration.ofHours(13), NOW, false));

        assertFalse(evaluator.withinPolicy(beyond, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("已发生停转的恢复不计为新增")
    void preExistingPauseRecoveryShouldNotCountAsNew() {
        List<OcPauseAssessment> pauses = List.of(
                new OcPauseAssessment("oc:1", Duration.ofHours(30), NOW, true));

        assertTrue(evaluator.withinPolicy(pauses, OcPlanMode.CONSERVATIVE));
    }

    @Test
    @DisplayName("停转层级判断应区分均衡与收益容忍")
    void shouldDistinguishBalancedAndProfitTiers() {
        assertTrue(evaluator.requiresBalancedTier(Duration.ofHours(2)));
        assertFalse(evaluator.requiresProfitTier(Duration.ofHours(2)));
        assertTrue(evaluator.requiresProfitTier(Duration.ofHours(7)));
        assertFalse(evaluator.requiresProfitTier(Duration.ofHours(13)));
    }
}
