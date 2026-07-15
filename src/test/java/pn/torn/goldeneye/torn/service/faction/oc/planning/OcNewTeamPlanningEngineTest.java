package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcNewTeamPlanningEngineTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 0, 0);

    @Test
    void shouldDisablePlanningAndRefreshWhenFactionHasNoExplicitScope() {
        OcFactionPlanningPolicy policy = policy(Set.of());
        OcPlanningSnapshot snapshot = snapshot(policy, Set.of());

        OcNewTeamPlan plan = engine().plan(snapshot, OcPlanMode.PROFIT);

        assertDisabled(plan, "未配置OC新队规划范围");
    }

    @Test
    void shouldDisablePlanningAndRefreshWhenAllEnabledProfilesAreInvalid() {
        String invalidKey = OcPlanningSnapshot.ocKey(8, "Invalid OC");
        OcFactionPlanningPolicy policy = policy(Set.of(invalidKey));
        OcPlanningSnapshot snapshot = snapshot(policy, Set.of(invalidKey));

        OcNewTeamPlan plan = engine().plan(snapshot, OcPlanMode.BALANCED);

        assertDisabled(plan, "规划范围配置全部无效");
    }

    private OcNewTeamPlanningEngine engine() {
        return new OcNewTeamPlanningEngine(new OcRefreshStrategyPlanner());
    }

    private OcFactionPlanningPolicy policy(Set<String> enabledKeys) {
        return new OcFactionPlanningPolicy(2095L, OcEvaluationMode.DIFFERENTIAL_WORK_HOUR,
                30, enabledKeys, List.of());
    }

    private OcPlanningSnapshot snapshot(OcFactionPlanningPolicy policy, Set<String> invalidKeys) {
        return new OcPlanningSnapshot(2095L, NOW, policy, List.of(), Map.of(), List.of(),
                Map.of(), List.of(), Map.of(), invalidKeys, List.of());
    }

    private void assertDisabled(OcNewTeamPlan plan, String expectedWarning) {
        assertEquals(3, plan.alternatives().size());
        plan.alternatives().forEach(branch -> {
            assertTrue(branch.existingTeamPlans().isEmpty());
            assertTrue(branch.newTeamPlans().isEmpty());
            assertEquals(0, branch.recommendedAdditionalChains());
            assertFalse(branch.refreshAdvice().refreshRecommended());
        });
        assertTrue(plan.catalogWarnings().stream()
                .anyMatch(message -> message.contains(expectedWarning)));
    }
}
