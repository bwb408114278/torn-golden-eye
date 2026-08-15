package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC现实占用摘要统计测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("OC现实占用摘要统计")
class OcCurrentOccupancyCalculatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 17, 15, 0);

    @Test
    @DisplayName("应统计全部现实OC占用并按计划岗位门槛筛选达标成员")
    void shouldCountRealOccupancyAndQualifiedMembers() {
        String plannedKey = OcPlanningSnapshot.ocKey(8, "Planned");
        TornFactionOcDO joinedPlanned = oc(1L, "Planned");
        TornFactionOcDO joinedUnplanned = oc(2L, "Unplanned");
        TornFactionOcDO empty = oc(3L, "Planned");
        Map<Long, List<TornFactionOcSlotDO>> slots = Map.of(
                1L, List.of(slot(1L, 10L)),
                2L, List.of(slot(2L, 30L)),
                3L, List.of(slot(3L, null)));
        List<OcMemberCandidate> members = List.of(
                member(10L, 80),
                member(20L, 70),
                member(30L, 50));
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName("Planned");
        profile.setRank(8);
        profile.setPlanStatus("READY");
        profile.setSpawnPool("NORMAL_7_8");
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT,
                Set.of(plannedKey), List.of());
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(1L, NOW, policy,
                List.of(joinedPlanned, joinedUnplanned, empty), slots, members,
                Map.of(plannedKey, profile), List.of(),
                Map.of(plannedKey, List.of(new OcPlanSlot(
                        "Worker#1", "Worker", 60, 1, null))), Set.of(), Map.of(), List.of());

        OcCurrentOccupancySummary result = new OcCurrentOccupancyCalculator().calculate(snapshot);

        assertEquals(3, result.currentTeamCount());
        assertEquals(2, result.joinedTeamCount());
        assertEquals(1, result.emptyTeamCount());
        assertEquals(2, result.occupiedMemberCount());
        assertEquals(2, result.qualifiedMemberCount());
        assertEquals(1, result.occupiedQualifiedMemberCount());
        assertEquals(1, result.idleQualifiedMemberCount());
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

    private TornFactionOcSlotDO slot(long ocId, Long userId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setPosition("Worker#1");
        slot.setUserId(userId);
        return slot;
    }

    private OcMemberCandidate member(long userId, int passRate) {
        return new OcMemberCandidate(userId, "user" + userId, NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(
                        8, "Planned", "Worker"), passRate), Map.of());
    }
}
