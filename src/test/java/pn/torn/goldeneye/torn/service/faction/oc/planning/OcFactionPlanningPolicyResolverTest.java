package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 帮派OC规划策略解析器测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("帮派规划策略解析")
class OcFactionPlanningPolicyResolverTest {

    @Test
    @DisplayName("应优先使用帮派策略并对非法比例回退")
    void shouldPreferFactionPolicyAndFallbackInvalidPercent() {
        TornSettingOcPlanningManager manager = mock(TornSettingOcPlanningManager.class);
        TornSettingFactionOcPlanningPolicyDO global = policy(0L, 10, 20, 30);
        TornSettingFactionOcPlanningPolicyDO faction = policy(20465L, 0, 150, 90);
        when(manager.getPolicies()).thenReturn(List.of(global, faction));
        when(manager.getFactionPlans()).thenReturn(List.of());

        OcFactionPlanningPolicy result = new OcFactionPlanningPolicyResolver(manager).resolve(20465L);

        assertEquals(25, result.conservativeCapacityPercent());
        assertEquals(50, result.balancedCapacityPercent());
        assertEquals(90, result.profitCapacityPercent());
        assertTrue(result.validationWarnings().stream()
                .anyMatch(message -> message.contains("保守模式安全刷新容量比例")));
    }

    @Test
    @DisplayName("帮派策略缺失时应使用全局策略")
    void shouldUseGlobalPolicyWhenFactionPolicyIsMissing() {
        TornSettingOcPlanningManager manager = mock(TornSettingOcPlanningManager.class);
        when(manager.getPolicies()).thenReturn(List.of(policy(0L, 15, 45, 95)));
        when(manager.getFactionPlans()).thenReturn(List.of());

        OcFactionPlanningPolicy result = new OcFactionPlanningPolicyResolver(manager).resolve(999L);

        assertEquals(15, result.conservativeCapacityPercent());
        assertEquals(45, result.balancedCapacityPercent());
        assertEquals(95, result.profitCapacityPercent());
    }

    private TornSettingFactionOcPlanningPolicyDO policy(long factionId, int conservative,
                                                         int balanced, int profit) {
        TornSettingFactionOcPlanningPolicyDO policy = new TornSettingFactionOcPlanningPolicyDO();
        policy.setFactionId(factionId);
        policy.setEvaluationMode("POSITION_WEIGHT");
        policy.setNormalPoolReservePercent(20);
        policy.setConservativeCapacityPercent(conservative);
        policy.setBalancedCapacityPercent(balanced);
        policy.setProfitCapacityPercent(profit);
        return policy;
    }
}
