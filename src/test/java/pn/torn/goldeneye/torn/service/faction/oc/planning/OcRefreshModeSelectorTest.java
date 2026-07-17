package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC刷新模式选点器测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("OC刷新模式选点")
class OcRefreshModeSelectorTest {

    private final OcRefreshModeSelector selector = new OcRefreshModeSelector();
    private final OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L, null, 20,
            25, 50, 100, Set.of(), List.of());
    private final OcRefreshSafetyResult safety = new OcRefreshSafetyResult(
            List.of(new OcRefreshVector(6, 0), new OcRefreshVector(5, 1),
                    new OcRefreshVector(3, 2)), false, 10L, List.of());

    @Test
    @DisplayName("应按配置比例和模式偏好选择安全向量")
    void shouldUseConfiguredCapacityAndModePreference() {
        assertEquals(new OcRefreshVector(1, 0),
                selector.select(safety, policy, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(2, 1),
                selector.select(safety, policy, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(3, 2),
                selector.select(safety, policy, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("没有安全刷新向量时应返回零")
    void shouldReturnZeroWhenNoSafeRefreshExists() {
        OcRefreshSafetyResult empty = new OcRefreshSafetyResult(
                List.of(new OcRefreshVector(0, 0)), false, 1L, List.of());
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(empty, policy, OcPlanMode.PROFIT));
    }

    @Test
    @DisplayName("容量比例向下取整为零时不应强制刷新")
    void shouldNotForceOneRefreshWhenConfiguredPercentageRoundsDownToZero() {
        OcRefreshSafetyResult one = new OcRefreshSafetyResult(
                List.of(new OcRefreshVector(0, 1)), false, 1L, List.of());

        assertEquals(new OcRefreshVector(0, 0),
                selector.select(one, policy, OcPlanMode.CONSERVATIVE));
        assertEquals(new OcRefreshVector(0, 0),
                selector.select(one, policy, OcPlanMode.BALANCED));
        assertEquals(new OcRefreshVector(0, 1),
                selector.select(one, policy, OcPlanMode.PROFIT));
    }
}
