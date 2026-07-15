package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OcChainPlanningServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 0, 0);

    @Test
    void shouldRejectDifferentChainsSharingTheSameCommittedRoot() {
        String rootKey = OcPlanningSnapshot.ocKey(8, "Root");
        String childAKey = OcPlanningSnapshot.ocKey(9, "Child A");
        String childBKey = OcPlanningSnapshot.ocKey(9, "Child B");
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(1L, NOW,
                new OcFactionPlanningPolicy(1L, OcEvaluationMode.POSITION_WEIGHT,
                        0, Set.of(rootKey), List.of()),
                List.of(rootOc()), Map.of(500L, List.of(occupiedRootSlot())),
                List.of(member()), profiles(rootKey, childAKey, childBKey),
                List.of(chain("A", "Child A"), chain("B", "Child B")),
                templates(rootKey, childAKey, childBKey), Set.of(), List.of());
        ExistingTeamRescueResult rescue = new ExistingTeamRescueResult(
                List.of(), snapshot.members());

        ChainPlanningResult result = new OcChainPlanningService().calculate(snapshot, rescue);

        assertFalse(result.committedObligationsFeasible());
        assertEquals(1, result.capacity().committedCount());
        assertEquals(0, result.capacity().provenSafeConcurrentCount());
    }

    private TornFactionOcDO rootOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(500L);
        oc.setName("Root");
        oc.setRank(8);
        oc.setStatus("Planning");
        oc.setReadyTime(NOW.plusDays(1));
        return oc;
    }

    private TornFactionOcSlotDO occupiedRootSlot() {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(500L);
        slot.setPosition("Hacker");
        slot.setUserId(10L);
        return slot;
    }

    private OcMemberCandidate member() {
        return new OcMemberCandidate(10L, "U10", NOW.plusDays(1), true,
                Map.of("8:Root:Hacker", 100), Map.of());
    }

    private Map<String, TornSettingOcPlanProfileDO> profiles(String... keys) {
        Map<String, TornSettingOcPlanProfileDO> result = new HashMap<>();
        for (String key : keys) {
            String[] parts = key.split(":", 2);
            TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
            profile.setRank(Integer.parseInt(parts[0]));
            profile.setOcName(parts[1]);
            profile.setPlanStatus("READY");
            profile.setSpawnPool(parts[0].equals("8") ? "HIGH_CHAIN_ROOT" : "HIGH_CHAIN_CHILD");
            profile.setRewardFloor(0L);
            result.put(key, profile);
        }
        return result;
    }

    private TornSettingOcChainDO chain(String code, String childName) {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode(code);
        edge.setSequenceNo(1);
        edge.setParentRank(8);
        edge.setParentOcName("Root");
        edge.setChildRank(9);
        edge.setChildOcName(childName);
        edge.setEnabled(true);
        return edge;
    }

    private Map<String, List<OcPlanSlot>> templates(String root, String childA, String childB) {
        return Map.of(root, List.of(slot("Hacker#1", "Hacker")),
                childA, List.of(slot("Driver#1", "Driver")),
                childB, List.of(slot("Muscle#1", "Muscle")));
    }

    private OcPlanSlot slot(String code, String position) {
        return new OcPlanSlot(code, position, 0, 0, BigDecimal.ONE);
    }
}
