package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


/**
 * OC刷新指令规划器测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("OC刷新指令规划")
class OcRefreshInstructionPlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 15, 0);

    @Test
    @DisplayName("应在同一安全边界上应用模式容量比例")
    void shouldApplyConfiguredCapacityPercentToSameSafetyBoundary() {
        OcPlanningSnapshot snapshot = snapshot();
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan conservative = planner.plan(snapshot, OcPlanMode.CONSERVATIVE);
        OcRefreshInstructionPlan balanced = planner.plan(snapshot, OcPlanMode.BALANCED);
        OcRefreshInstructionPlan profit = planner.plan(snapshot, OcPlanMode.PROFIT);

        assertEquals(1, conservative.normalRefreshCount());
        assertEquals(3, balanced.normalRefreshCount());
        assertEquals(7, profit.normalRefreshCount());
        assertEquals(0, profit.highRefreshCount());
        assertFalse(profit.lowerBound());
        assertEquals(Map.of("8:Normal", 1), profit.plannedEmptyOcCounts());
    }

    @Test
    @DisplayName("计划内高阶根链冲突时应停止刷新建议")
    void shouldFailClosedWhenPlannedHighRootHasConflictingChains() {
        OcPlanningSnapshot snapshot = snapshot();
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        Map<String, TornSettingOcPlanProfileDO> profiles = new HashMap<>(snapshot.profiles());
        profiles.put(rootKey, profile("Root", "HIGH_CHAIN_ROOT"));
        profiles.put(OcPlanningSnapshot.ocKey(9, "Child"), profile("Child", "CHAIN_ONLY"));
        profiles.put(OcPlanningSnapshot.ocKey(9, "Other"), profile("Other", "CHAIN_ONLY"));
        Map<String, List<OcPlanSlot>> slots = new HashMap<>(snapshot.slotTemplates());
        profiles.keySet().forEach(key -> slots.putIfAbsent(key,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null))));
        OcPlanningSnapshot conflicted = new OcPlanningSnapshot(snapshot.factionId(), NOW,
                new OcFactionPlanningPolicy(1L, OcEvaluationMode.POSITION_WEIGHT,
                        20, 25, 50, 100, Set.of(rootKey), List.of()),
                snapshot.activeOcs(), snapshot.slotsByOcId(), snapshot.members(), profiles,
                List.of(edge("A", "Child"), edge("B", "Other")), slots,
                Set.of(), List.of());
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan result = planner.plan(conflicted, OcPlanMode.PROFIT);

        assertEquals(0, result.normalRefreshCount());
        assertEquals(0, result.highRefreshCount());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("策略存在校验警告时应停止刷新建议")
    void shouldFailClosedWhenPolicyContainsValidationWarning() {
        OcPlanningSnapshot invalid = invalidPolicySnapshot();
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan result = planner.plan(invalid, OcPlanMode.PROFIT);

        assertEquals(0, result.normalRefreshCount());
        assertEquals(0, result.highRefreshCount());
        assertFalse(result.warnings().isEmpty());
    }

    private OcPlanningSnapshot invalidPolicySnapshot() {
        OcPlanningSnapshot snapshot = snapshot();
        OcFactionPlanningPolicy invalidPolicy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, 20, 25, 50, 100,
                snapshot.policy().enabledOcKeys(), List.of("容量比例配置非法"));
        return new OcPlanningSnapshot(snapshot.factionId(), NOW,
                invalidPolicy, snapshot.activeOcs(), snapshot.slotsByOcId(), snapshot.members(),
                snapshot.profiles(), snapshot.chains(), snapshot.slotTemplates(),
                snapshot.invalidOcKeys(), snapshot.warnings());
    }

    private TornSettingOcPlanProfileDO profile(String name, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank("Root".equals(name) ? 8 : 9);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        return profile;
    }

    private TornSettingOcChainDO edge(String code, String child) {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode(code);
        edge.setParentOcName("Root");
        edge.setParentRank(8);
        edge.setChildOcName(child);
        edge.setChildRank(9);
        edge.setSequenceNo(1);
        return edge;
    }

    private OcRefreshInstructionPlanner planner() {
        return new OcRefreshInstructionPlanner(
                new OcRefreshSafetyRequestFactory(new OcChainPlanningService()),
                new OcRefreshModeSelector());
    }

    private OcPlanningSnapshot snapshot() {
        String key = OcPlanningSnapshot.ocKey(8, "Normal");
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setName("Normal");
        oc.setRank(8);
        oc.setStatus("Recruiting");
        oc.setCreateTime(NOW);
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName("Normal");
        profile.setRank(8);
        profile.setSpawnPool("NORMAL_7_8");
        profile.setPlanStatus("READY");
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, 20, 25, 50, 100,
                Set.of(key), List.of());
        OcMemberCandidate member = new OcMemberCandidate(10L, "member", NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90), Map.of());
        return new OcPlanningSnapshot(1L, NOW, policy, List.of(oc), Map.of(),
                List.of(member), Map.of(key, profile), List.of(),
                Map.of(key, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null))),
                Set.of(), List.of());
    }
}
