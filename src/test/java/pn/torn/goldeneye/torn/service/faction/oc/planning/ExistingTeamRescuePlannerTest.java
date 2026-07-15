package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistingTeamRescuePlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 0, 0);
    private static final LocalDateTime READY_TIME = NOW.plusDays(2);
    private static final String OC_NAME = "Full Team";
    private static final String SLOT_CODE = "Hacker#1";
    private static final long USER_ID = 10L;

    @Test
    void shouldKeepFullTeamOutOfActionsWithoutMovingReleaseTimeBackward() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setFactionId(20465L);
        oc.setName(OC_NAME);
        oc.setRank(8);
        oc.setStatus("Planning");
        oc.setReadyTime(READY_TIME);

        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(1L);
        slot.setOcId(oc.getId());
        slot.setPosition(SLOT_CODE);
        slot.setUserId(USER_ID);
        slot.setPassRate(90);

        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(OC_NAME);
        profile.setRank(8);
        profile.setPlanStatus("READY");
        profile.setSpawnPool("NORMAL_7_8");
        profile.setRewardFloor(1L);

        String key = OcPlanningSnapshot.ocKey(8, OC_NAME);
        OcMemberCandidate member = new OcMemberCandidate(USER_ID, "member", READY_TIME, false,
                Map.of(OcMemberCandidate.capabilityKey(8, OC_NAME, "Hacker"), 90), Map.of());
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(20465L,
                OcEvaluationMode.POSITION_WEIGHT, 20, Set.of(key), List.of());
        OcPlanningSnapshot snapshot = new OcPlanningSnapshot(20465L, NOW, policy,
                List.of(oc), Map.of(oc.getId(), List.of(slot)), List.of(member),
                Map.of(key, profile), List.of(),
                Map.of(key, List.of(new OcPlanSlot(SLOT_CODE, "Hacker", 50, 1,
                        BigDecimal.ONE))), Set.of(), List.of());

        ExistingTeamRescueResult result = new ExistingTeamRescuePlanner()
                .plan(snapshot, OcPlanMode.CONSERVATIVE);

        assertTrue(result.plans().isEmpty());
        assertEquals(READY_TIME, result.memberTimeline().getFirst().availableAt());
    }
}
