package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC刷新指令规划器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("OC刷新指令规划")
class OcRefreshInstructionPlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 15, 0);

    @Test
    @DisplayName("三模式使用同一事实时间线且不按比例缩放")
    void shouldUseSameTimelineForAllModesWithoutPercentageScaling() {
        OcPlanningSnapshot snapshot = snapshot();
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan conservative = planner.plan(snapshot, OcPlanMode.CONSERVATIVE);
        OcRefreshInstructionPlan balanced = planner.plan(snapshot, OcPlanMode.BALANCED);
        OcRefreshInstructionPlan profit = planner.plan(snapshot, OcPlanMode.PROFIT);

        assertEquals(2, conservative.normalRefreshCount());
        assertEquals(2, balanced.normalRefreshCount());
        assertEquals(2, profit.normalRefreshCount());
        assertEquals(0, profit.highRefreshCount());
        assertEquals(OcConfigurationStatusEnum.VALID, profit.configurationStatus());
        assertEquals(OcProofStatusEnum.PROVEN_SAFE, profit.proofStatus());
        assertFalse(profit.replanWindow().nextReplanAt().isBefore(NOW));
        assertFalse(profit.replanWindow().latestReplanAt().isBefore(NOW));
        assertEquals(Map.of(), profit.plannedEmptyOcCounts());
        assertEquals(0, profit.occupancySummary().currentTeamCount());
        assertEquals(6, profit.occupancySummary().qualifiedMemberCount());
        assertEquals(6, profit.occupancySummary().idleQualifiedMemberCount());
    }

    @Test
    @DisplayName("计划内高阶根链冲突时应停止刷新建议")
    void shouldFailClosedWhenPlannedHighRootHasConflictingChains() {
        OcPlanningSnapshot snapshot = snapshot();
        String rootKey = OcPlanningSnapshot.ocKey(8, "Normal");
        Map<String, TornSettingOcPlanProfileDO> profiles = new HashMap<>(snapshot.profiles());
        profiles.put(rootKey, profile("Normal", 8, "HIGH_CHAIN_ROOT"));
        profiles.put(OcPlanningSnapshot.ocKey(9, "Child"), profile("Child", 9, "CHAIN_ONLY"));
        profiles.put(OcPlanningSnapshot.ocKey(9, "Other"), profile("Other", 9, "CHAIN_ONLY"));
        Map<String, List<OcPlanSlot>> slots = new HashMap<>(snapshot.slotTemplates());
        profiles.keySet().forEach(key -> slots.putIfAbsent(key,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null))));
        OcPlanningSnapshot conflicted = new OcPlanningSnapshot(snapshot.factionId(), NOW,
                snapshot.policy(), snapshot.activeOcs(), snapshot.slotsByOcId(),
                snapshot.members(), profiles,
                List.of(edge("A", "Child"), edge("B", "Other")), slots,
                Set.of(), Map.of(), List.of());
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan result = planner.plan(conflicted, OcPlanMode.PROFIT);

        assertEquals(0, result.normalRefreshCount());
        assertEquals(0, result.highRefreshCount());
        assertEquals(OcConfigurationStatusEnum.INVALID, result.configurationStatus());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("策略存在校验警告时应停止刷新建议")
    void shouldFailClosedWhenPolicyContainsValidationWarning() {
        OcPlanningSnapshot snapshot = snapshot();
        OcFactionPlanningPolicy invalidPolicy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT,
                snapshot.policy().enabledOcKeys(), List.of("容量比例配置非法"));
        OcPlanningSnapshot invalid = new OcPlanningSnapshot(snapshot.factionId(), NOW,
                invalidPolicy, snapshot.activeOcs(), snapshot.slotsByOcId(), snapshot.members(),
                snapshot.profiles(), snapshot.chains(), snapshot.slotTemplates(),
                snapshot.invalidOcKeys(), Map.of(), List.of());
        OcRefreshInstructionPlanner planner = planner();

        OcRefreshInstructionPlan result = planner.plan(invalid, OcPlanMode.PROFIT);

        assertEquals(0, result.normalRefreshCount());
        assertEquals(0, result.highRefreshCount());
        assertTrue(result.warnings().stream().anyMatch(
                warning -> warning.contains("容量比例")));
    }

    @Test
    @DisplayName("刚刷新随机结果后规划应收敛为立即重评估并输出随机结果变化原因码")
    void shouldCollapseReplanWindowWhenRandomOutcomeRefreshed() {
        OcRefreshInstructionPlan plan = planner().plan(snapshot(), OcPlanMode.BALANCED, true);

        assertTrue(plan.reasonCodes().stream().anyMatch(code ->
                code == pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED));
        assertEquals(NOW, plan.replanWindow().nextReplanAt());
        assertEquals(NOW, plan.replanWindow().latestReplanAt());
    }

    private TornSettingOcPlanProfileDO profile(String name, int rank, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(rank);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        return profile;
    }

    private TornSettingOcChainDO edge(String code, String child) {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode(code);
        edge.setParentOcName("Normal");
        edge.setParentRank(8);
        edge.setChildOcName(child);
        edge.setChildRank(9);
        edge.setSequenceNo(1);
        return edge;
    }

    private OcRefreshInstructionPlanner planner() {
        return new OcRefreshInstructionPlanner(
                new OcRefreshSafetyRequestFactory(new OcChainPlanningService(),
                        new OcExistingTimelineReconstructor(), new OcRewardEvidenceCalculator()),
                new OcRefreshModeSelector(), new OcCurrentOccupancyCalculator(),
                new OcReplanWindowCalculator(), new OcLiquidityPathVerifier());
    }

    private OcPlanningSnapshot snapshot() {
        String key = OcPlanningSnapshot.ocKey(8, "Normal");
        TornSettingOcPlanProfileDO profile = profile("Normal", 8, "NORMAL_7_8");
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, Set.of(key), List.of());
        List<OcMemberCandidate> members = java.util.stream.LongStream.rangeClosed(10L, 15L)
                .mapToObj(id -> new OcMemberCandidate(id, "member" + id, NOW, false,
                        Map.of(OcMemberCandidate.capabilityKey(
                                8, "Normal", "Worker"), 90), Map.of()))
                .toList();
        Map<String, OcPlanningRewardStatsDO> rewardStats = Map.of(key,
                new OcPlanningRewardStatsDO(8, "Normal", 10, 10, 10, 100_000L,
                        BigDecimal.valueOf(10_000L), 9_000L));
        return new OcPlanningSnapshot(1L, NOW, policy, List.of(), Map.of(),
                members, Map.of(key, profile), List.of(),
                Map.of(key, java.util.stream.IntStream.rangeClosed(1, 6)
                        .mapToObj(index -> new OcPlanSlot("Worker#" + index,
                                "Worker", 60, index, null)).toList()),
                Set.of(), rewardStats, List.of());
    }
}
