package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcReplanWindow;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重新评估窗口计算器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("重新评估窗口计算")
class OcReplanWindowCalculatorTest {
    private final OcReplanWindowCalculator calculator = new OcReplanWindowCalculator();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("最晚重评估时间应按业务边界提前30分钟")
    void shouldApplyThirtyMinuteLeadToBusinessBoundary() {
        OcReplanWindow window = calculator.calculate(NOW,
                List.of(NOW.plusHours(8)),
                List.of(NOW.plusHours(10)));

        assertEquals(NOW.plusHours(8), window.nextReplanAt());
        assertEquals(NOW.plusHours(10).minusMinutes(30), window.latestReplanAt());
    }

    @Test
    @DisplayName("边界减提前量不晚于快照时应立即重评估")
    void shouldRequireImmediateReplanWhenBoundaryTooClose() {
        OcReplanWindow window = calculator.calculate(NOW,
                List.of(NOW.plusHours(8)),
                List.of(NOW.plusMinutes(20)));

        assertEquals(NOW, window.nextReplanAt());
        assertEquals(NOW, window.latestReplanAt());
        assertTrue(window.reasonCodes().contains(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW));
    }

    @Test
    @DisplayName("最早事件晚于最晚边界时应收敛到最晚边界")
    void shouldClampNextReplanToLatestBoundary() {
        OcReplanWindow window = calculator.calculate(NOW,
                List.of(NOW.plusHours(9)),
                List.of(NOW.plusHours(5)));

        assertEquals(NOW.plusHours(5).minusMinutes(30), window.nextReplanAt());
        assertEquals(NOW.plusHours(5).minusMinutes(30), window.latestReplanAt());
    }

    @Test
    @DisplayName("随机结果变化时应输出立即重评估窗口")
    void shouldReturnImmediateWindowWhenRandomOutcomeChanged() {
        OcReplanWindow window = calculator.immediateReplan(NOW);

        assertEquals(NOW, window.nextReplanAt());
        assertEquals(NOW, window.latestReplanAt());
        assertTrue(window.reasonCodes().contains(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED));
    }
}
