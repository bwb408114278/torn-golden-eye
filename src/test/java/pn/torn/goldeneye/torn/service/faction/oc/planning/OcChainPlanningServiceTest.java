package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 高阶链模板构造服务测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("高阶链模板构造")
class OcChainPlanningServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 0, 0);

    @Test
    @DisplayName("应构造已启用且配置完整的高阶链")
    void shouldBuildOnlyReadyEnabledCompleteChain() {
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        String childKey = OcPlanningSnapshot.ocKey(9, "Child");
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(1L, NOW,
                new OcFactionPlanningPolicy(1L, OcEvaluationMode.POSITION_WEIGHT,
                        20, 25, 50, 100, Set.of(rootKey), List.of()),
                List.of(), Map.of(), List.of(),
                Map.of(rootKey, profile("Root", 8, "HIGH_CHAIN_ROOT"),
                        childKey, profile("Child", 9, "CHAIN_ONLY")),
                List.of(edge()),
                Map.of(rootKey, List.of(slot()), childKey, List.of(slot())),
                Set.of(), List.of());

        List<List<OcTeamDemand>> chains = new OcChainPlanningService().buildReadyChains(snapshot);

        assertEquals(1, chains.size());
        assertEquals(List.of("Root", "Child"), chains.getFirst().stream()
                .map(OcTeamDemand::ocName).toList());
    }

    @Test
    @DisplayName("应拒绝父子节点不连续的高阶链")
    void shouldRejectBrokenChain() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                edge("BROKEN", "Root", 8, "Child", 9, 1),
                edge("BROKEN", "Other", 9, "Last", 10, 2)),
                Set.of(OcPlanningSnapshot.ocKey(8, "Root")));

        var result = new OcChainPlanningService().buildReadyChainResult(snapshot);

        assertEquals(0, result.chains().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    @DisplayName("应拒绝同一根节点配置多条高阶链")
    void shouldRejectMultipleChainsSharingSameRoot() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                edge("CHAIN_A", "Root", 8, "Child", 9, 1),
                edge("CHAIN_B", "Root", 8, "Last", 10, 1)),
                Set.of(OcPlanningSnapshot.ocKey(8, "Root")));

        var result = new OcChainPlanningService().buildReadyChainResult(snapshot);

        assertEquals(0, result.chains().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    @DisplayName("应忽略不在规划范围内的断链配置")
    void shouldIgnoreBrokenChainWhoseRootIsNotPlanned() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                edge("IGNORED", "Root", 8, "Child", 9, 1),
                edge("IGNORED", "Other", 9, "Last", 10, 2)), Set.of());

        var result = new OcChainPlanningService().buildReadyChainResult(snapshot);

        assertEquals(0, result.chains().size());
        assertEquals(0, result.warnings().size());
    }

    @Test
    @DisplayName("应拒绝后继节点未就绪的计划内高阶链")
    void shouldRejectPlannedChainWhenChildProfileIsNotReady() {
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        OcPlanningSnapshot snapshot = snapshot(List.of(
                edge("NOT_READY", "Root", 8, "Child", 9, 1)), Set.of(rootKey));
        snapshot.profiles().get(OcPlanningSnapshot.ocKey(9, "Child"))
                .setPlanStatus("OBSERVE_ONLY");

        var result = new OcChainPlanningService().buildReadyChainResult(snapshot);

        assertEquals(0, result.chains().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    @DisplayName("应拒绝缺少链定义的计划内高阶根")
    void shouldRejectPlannedHighRootWithoutChainDefinition() {
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        OcPlanningSnapshot snapshot = snapshot(List.of(), Set.of(rootKey));

        var result = new OcChainPlanningService().buildReadyChainResult(snapshot);

        assertEquals(0, result.chains().size());
        assertEquals(1, result.warnings().size());
    }

    private OcPlanningSnapshot snapshot(List<TornSettingOcChainDO> edges,
                                        Set<String> enabledKeys) {
        Map<String, TornSettingOcPlanProfileDO> profiles = Map.of(
                OcPlanningSnapshot.ocKey(8, "Root"), profile("Root", 8, "HIGH_CHAIN_ROOT"),
                OcPlanningSnapshot.ocKey(9, "Child"), profile("Child", 9, "CHAIN_ONLY"),
                OcPlanningSnapshot.ocKey(9, "Other"), profile("Other", 9, "CHAIN_ONLY"),
                OcPlanningSnapshot.ocKey(10, "Last"), profile("Last", 10, "CHAIN_ONLY"));
        Map<String, List<OcPlanSlot>> slots = new java.util.HashMap<>();
        profiles.keySet().forEach(key -> slots.put(key, List.of(slot())));
        return new OcPlanningSnapshot(1L, NOW,
                new OcFactionPlanningPolicy(1L, OcEvaluationMode.POSITION_WEIGHT,
                        20, 25, 50, 100, enabledKeys, List.of()),
                List.of(), Map.of(), List.of(), profiles, edges,
                slots, Set.of(), List.of());
    }

    private TornSettingOcChainDO edge(String code, String parentName, int parentRank,
                                      String childName, int childRank, int sequence) {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode(code);
        edge.setParentOcName(parentName);
        edge.setParentRank(parentRank);
        edge.setChildOcName(childName);
        edge.setChildRank(childRank);
        edge.setSequenceNo(sequence);
        return edge;
    }

    private TornSettingOcPlanProfileDO profile(String name, int rank, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(rank);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        return profile;
    }

    private TornSettingOcChainDO edge() {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode("ROOT_CHILD");
        edge.setParentOcName("Root");
        edge.setParentRank(8);
        edge.setChildOcName("Child");
        edge.setChildRank(9);
        edge.setSequenceNo(1);
        return edge;
    }

    private OcPlanSlot slot() {
        return new OcPlanSlot("Worker#1", "Worker", 60, 1, null);
    }
}
