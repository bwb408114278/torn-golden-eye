package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
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
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("帮派规划策略解析")
class OcFactionPlanningPolicyResolverTest {

    @Test
    @DisplayName("应优先使用帮派策略并解析显式规划范围")
    void shouldPreferFactionPolicyAndResolveExplicitScope() {
        TornSettingOcPlanningManager manager = mock(TornSettingOcPlanningManager.class);
        when(manager.getPolicies()).thenReturn(List.of(policy(0L), policy(20465L)));
        when(manager.getFactionPlans()).thenReturn(List.of(plan(20465L, 8, "Planned", true)));

        OcFactionPlanningPolicy result = new OcFactionPlanningPolicyResolver(manager).resolve(20465L);

        assertEquals(List.of("8:Planned"), result.enabledOcKeys().stream().sorted().toList());
        assertTrue(result.validationWarnings().isEmpty());
    }

    @Test
    @DisplayName("帮派策略缺失时应回退全局策略")
    void shouldUseGlobalPolicyWhenFactionPolicyIsMissing() {
        TornSettingOcPlanningManager manager = mock(TornSettingOcPlanningManager.class);
        when(manager.getPolicies()).thenReturn(List.of(policy(0L)));
        when(manager.getFactionPlans()).thenReturn(List.of(plan(999L, 8, "Planned", true)));

        OcFactionPlanningPolicy result = new OcFactionPlanningPolicyResolver(manager).resolve(999L);

        assertEquals(1, result.enabledOcKeys().size());
        assertTrue(result.validationWarnings().isEmpty());
    }

    @Test
    @DisplayName("无显式规划范围或无启用项时应输出禁用警告")
    void shouldWarnWhenExplicitScopeMissingOrDisabled() {
        TornSettingOcPlanningManager manager = mock(TornSettingOcPlanningManager.class);
        when(manager.getPolicies()).thenReturn(List.of(policy(0L)));
        when(manager.getFactionPlans()).thenReturn(List.of(
                plan(999L, 8, "Disabled", false)));

        OcFactionPlanningPolicy result = new OcFactionPlanningPolicyResolver(manager).resolve(999L);

        assertTrue(result.enabledOcKeys().isEmpty());
        assertTrue(result.validationWarnings().stream()
                .anyMatch(message -> message.contains("自动规划已禁用")));
    }

    private TornSettingFactionOcPlanningPolicyDO policy(long factionId) {
        TornSettingFactionOcPlanningPolicyDO policy = new TornSettingFactionOcPlanningPolicyDO();
        policy.setFactionId(factionId);
        policy.setEvaluationMode("POSITION_WEIGHT");
        return policy;
    }

    private TornSettingFactionOcPlanDO plan(long factionId, int rank, String ocName, boolean enabled) {
        TornSettingFactionOcPlanDO plan = new TornSettingFactionOcPlanDO();
        plan.setFactionId(factionId);
        plan.setRank(rank);
        plan.setOcName(ocName);
        plan.setEnabled(enabled);
        return plan;
    }
}
