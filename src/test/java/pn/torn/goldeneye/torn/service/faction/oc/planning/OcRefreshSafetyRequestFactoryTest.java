package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshPlanningContext;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * OC刷新安全请求工厂测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("OC刷新安全请求构造")
class OcRefreshSafetyRequestFactoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 15, 0);

    @Test
    @DisplayName("仅计划内无人OC应进入需求和展示")
    void shouldIncludeOnlyPlannedEmptyOcInDemandAndDisplay() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        String ignoredKey = OcPlanningSnapshot.ocKey(8, "Ignored");
        OcPlanningSnapshot snapshot = snapshot(plannedKey, ignoredKey);
        OcRefreshSafetyRequestFactory factory = new OcRefreshSafetyRequestFactory(
                new OcChainPlanningService());

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertEquals(Map.of(plannedKey, 1), context.plannedEmptyOcCounts());
        assertEquals(List.of("Planned"), context.request().baseDemands().stream()
                .map(OcTeamDemand::ocName).toList());
        assertFalse(context.request().members().getFirst().fixed());
    }

    @Test
    @DisplayName("应忽略档案未就绪的已启用OC")
    void shouldIgnoreEnabledOcWhoseProfileIsNotReady() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        String ignoredKey = OcPlanningSnapshot.ocKey(8, "Ignored");
        OcPlanningSnapshot snapshot = snapshot(plannedKey, ignoredKey);
        snapshot.profiles().get(plannedKey).setPlanStatus("OBSERVE_ONLY");
        OcRefreshSafetyRequestFactory factory = new OcRefreshSafetyRequestFactory(
                new OcChainPlanningService());

        OcRefreshPlanningContext context = factory.create(snapshot);

        assertEquals(Map.of(), context.plannedEmptyOcCounts());
        assertEquals(List.of(), context.request().baseDemands());
    }

    private OcPlanningSnapshot snapshot(String plannedKey, String ignoredKey) {
        TornFactionOcDO planned = oc(1L, "Planned");
        TornFactionOcDO ignored = oc(2L, "Ignored");
        TornSettingOcPlanProfileDO plannedProfile = profile("Planned");
        TornSettingOcPlanProfileDO ignoredProfile = profile("Ignored");
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, 20, 25, 50, 100,
                Set.of(plannedKey), List.of());
        OcMemberCandidate member = new OcMemberCandidate(10L, "member", NOW.plusDays(1),
                true, Map.of(), Map.of());
        Map<String, List<OcPlanSlot>> templates = Map.of(
                plannedKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                ignoredKey, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        return new OcPlanningSnapshot(1L, NOW, policy, List.of(planned, ignored),
                Map.of(), List.of(member), Map.of(plannedKey, plannedProfile,
                ignoredKey, ignoredProfile), List.of(), templates, Set.of(), List.of());
    }

    private TornFactionOcDO oc(long id, String name) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setName(name);
        oc.setRank(8);
        oc.setStatus("Recruiting");
        oc.setCreateTime(NOW);
        return oc;
    }

    private TornSettingOcPlanProfileDO profile(String name) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(8);
        profile.setSpawnPool("NORMAL_7_8");
        profile.setPlanStatus("READY");
        return profile;
    }
}
