package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcChainPlanningService;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcExistingTimelineReconstructor;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcRewardEvidenceCalculator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC刷新时间线求解请求工厂测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("OC刷新时间线求解请求构造")
class OcRefreshSafetyRequestFactoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 15, 0);

    private final OcRefreshSafetyRequestFactory factory = new OcRefreshSafetyRequestFactory(
            new OcChainPlanningService(), new OcExistingTimelineReconstructor(),
            new OcRewardEvidenceCalculator());

    @Test
    @DisplayName("仅计划内无人OC应进入义务和展示")
    void shouldIncludeOnlyPlannedEmptyOcInObligationsAndDisplay() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        String ignoredKey = OcPlanningSnapshot.ocKey(8, "Ignored");
        OcPlanningSnapshot snapshot = snapshot(plannedKey, ignoredKey, false);

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertEquals(Map.of(plannedKey, 1), context.plannedEmptyOcCounts());
        assertEquals(OcConfigurationStatusEnum.VALID, context.configurationStatus());
        assertEquals(1, context.request().obligations().size());
        assertEquals(OcTimelineObligation.ObligationKind.PLANNED_EMPTY,
                context.request().obligations().getFirst().kind());
        assertFalse(context.request().members().getFirst().fixed());
    }

    @Test
    @DisplayName("应忽略档案未就绪的已启用OC")
    void shouldIgnoreEnabledOcWhoseProfileIsNotReady() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        String ignoredKey = OcPlanningSnapshot.ocKey(8, "Ignored");
        OcPlanningSnapshot snapshot = snapshot(plannedKey, ignoredKey, false);
        snapshot.profiles().get(plannedKey).setPlanStatus("OBSERVE_ONLY");

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertEquals(Map.of(), context.plannedEmptyOcCounts());
        assertEquals(0, context.request().obligations().size());
    }

    @Test
    @DisplayName("已有人OC缺少阶段时间时应标记不可证明占用")
    void shouldMarkJoinedOcWithoutReadyTimeAsUnprovable() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        OcPlanningSnapshot snapshot = snapshot(plannedKey, plannedKey, true);

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertTrue(context.request().unprovableMemberIds().contains(10L));
        assertEquals(0, context.request().obligations().size());
    }

    @Test
    @DisplayName("链实例分叉时上下文应保留硬义务风险和映射歧义原因码")
    void shouldPreserveChainRiskFactsWhenInstancesForked() {
        TornFactionOcDO root = oc(1L, "Root", NOW.plusHours(8));
        root.setPreviousOcId(null);
        TornFactionOcDO firstChild = oc(2L, "Child", NOW.plusHours(24));
        firstChild.setPreviousOcId(1L);
        firstChild.setRank(9);
        TornFactionOcDO secondChild = oc(3L, "Child", NOW.plusHours(24));
        secondChild.setPreviousOcId(1L);
        secondChild.setRank(9);
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        String childKey = OcPlanningSnapshot.ocKey(9, "Child");
        Map<String, TornSettingOcPlanProfileDO> profiles = new java.util.HashMap<>();
        profiles.put(rootKey, profile("Root", "NORMAL_7_8"));
        profiles.put(childKey, profile("Child", "CHAIN_ONLY"));
        Map<String, List<OcPlanSlot>> templates = new java.util.HashMap<>();
        templates.put(rootKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        templates.put(childKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode("A");
        edge.setParentOcName("Root");
        edge.setParentRank(8);
        edge.setChildOcName("Child");
        edge.setChildRank(9);
        edge.setSequenceNo(1);
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, Set.of(rootKey, childKey), List.of());
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(1L, NOW, policy,
                List.of(root, firstChild, secondChild), Map.of(), List.of(),
                profiles, List.of(edge), templates, Set.of(), Map.of(), List.of());

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertTrue(context.riskFlags().contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK),
                context.riskFlags().toString());
        assertTrue(context.reasonCodes().contains(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS),
                context.reasonCodes().toString());
        assertEquals(OcConfigurationStatusEnum.INVALID, context.configurationStatus());
    }

    @Test
    @DisplayName("高阶链配置冲突时配置状态应为无效")
    void shouldReturnInvalidConfigurationWhenChainConflicts() {
        String rootKey = OcPlanningSnapshot.ocKey(8, "Planned");
        OcPlanningSnapshot snapshot = snapshot(rootKey, rootKey, false);
        snapshot.profiles().get(rootKey).setSpawnPool("HIGH_CHAIN_ROOT");

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertEquals(OcConfigurationStatusEnum.INVALID, context.configurationStatus());
        assertFalse(context.warnings().isEmpty());
    }

    private OcPlanningSnapshot snapshot(String plannedKey, String ignoredKey,
                                        boolean joinedWithoutReadyTime) {
        TornFactionOcDO planned = oc(1L, "Planned", null);
        TornFactionOcDO ignored = oc(2L, "Ignored", null);
        TornFactionOcSlotDO joinedSlot = new TornFactionOcSlotDO();
        joinedSlot.setOcId(1L);
        joinedSlot.setPosition("Worker#1");
        joinedSlot.setUserId(10L);
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, Set.of(plannedKey), List.of());
        OcMemberCandidate member = new OcMemberCandidate(10L, "member", NOW,
                false, Map.of(), Map.of());
        Map<String, List<OcPlanSlot>> templates = new java.util.HashMap<>();
        templates.put(plannedKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        templates.put(ignoredKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        Map<String, TornSettingOcPlanProfileDO> profiles = new java.util.HashMap<>();
        profiles.put(plannedKey, profile("Planned", "NORMAL_7_8"));
        profiles.put(ignoredKey, profile("Ignored", "NORMAL_7_8"));
        Map<Long, List<TornFactionOcSlotDO>> slots = joinedWithoutReadyTime
                ? Map.of(1L, List.of(joinedSlot)) : Map.of();
        return new OcPlanningSnapshot(1L, NOW, policy, List.of(planned, ignored),
                slots, List.of(member), profiles, List.of(), templates,
                Set.of(), Map.of(), List.of());
    }

    private TornFactionOcDO oc(long id, String name, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setName(name);
        oc.setRank(8);
        oc.setStatus("Recruiting");
        oc.setReadyTime(readyTime);
        oc.setCreateTime(NOW);
        return oc;
    }

    private TornSettingOcPlanProfileDO profile(String name, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(8);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        return profile;
    }
}
