package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcChainSuccessorReservationPlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 0, 0);
    private static final LocalDateTime ROOT_COMPLETION = NOW.plusHours(24);
    private static final String ROOT = "Root";
    private static final String CHILD = "Child";

    @Test
    void shouldReserveSuccessorAfterRootCompletion() {
        OcMemberCandidate original = member(Map.of(
                capability(ROOT, "Hacker"), 90,
                capability(CHILD, "Driver"), 90), NOW);
        OcMemberCandidate afterRoot = original.asAvailableAt(ROOT_COMPLETION);
        ChainPlanningResult capacity = capacity(original);

        ChainPlanningResult result = new OcChainSuccessorReservationPlanner().reserve(
                snapshot(), capacity, rootPlans(afterRoot), OcPlanMode.BALANCED);

        assertEquals(1, result.reservedAssignments().size());
        assertEquals(ROOT_COMPLETION, result.reservedAssignments().getFirst().joinAt());
        assertEquals(ROOT_COMPLETION.plusHours(24),
                result.reservedAssignments().getFirst().stageCompleteAt());
        assertEquals(ROOT_COMPLETION.plusHours(24),
                result.memberTimeline().getFirst().availableAt());
        assertEquals(1, result.capacity().provenAdditionalCount());
    }

    @Test
    void shouldRollbackRootTimelineWhenSuccessorCannotBeReserved() {
        OcMemberCandidate original = member(Map.of(capability(ROOT, "Hacker"), 90), NOW);
        OcMemberCandidate afterRoot = original.asAvailableAt(ROOT_COMPLETION);
        ChainPlanningResult capacity = capacity(original);

        ChainPlanningResult result = new OcChainSuccessorReservationPlanner().reserve(
                snapshot(), capacity, rootPlans(afterRoot), OcPlanMode.BALANCED);

        assertEquals(0, result.capacity().provenAdditionalCount());
        assertFalse(result.capacity().maximumProven());
        assertTrue(result.reservedAssignments().isEmpty());
        assertEquals(NOW, result.memberTimeline().getFirst().availableAt());
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("取消本次新增高阶建议")));
    }

    private ChainPlanningResult capacity(OcMemberCandidate original) {
        return new ChainPlanningResult(new OcSafeChainCapacityResult(0, 1, 1, true),
                true, OcPlanningSnapshot.ocKey(8, "Root"), List.of("Root → Child"),
                List.of(original), List.of(), List.of());
    }

    private OcPipelinePlanningResult rootPlans(OcMemberCandidate afterRoot) {
        OcTeamPlan root = new OcTeamPlan(1L, ROOT, 8, false, true, ROOT_COMPLETION,
                List.of(), List.of(), 0L, 0L, "root");
        return new OcPipelinePlanningResult(List.of(root), List.of(afterRoot));
    }

    private OcPlanningSnapshot snapshot() {
        TornSettingOcPlanProfileDO root = profile(1L, ROOT, 8, "HIGH_CHAIN_ROOT");
        TornSettingOcPlanProfileDO child = profile(2L, CHILD, 9, "CHAIN_ONLY");
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode("ROOT_CHILD");
        edge.setSequenceNo(1);
        edge.setParentOcName(ROOT);
        edge.setParentRank(8);
        edge.setChildOcName(CHILD);
        edge.setChildRank(9);
        String rootKey = OcPlanningSnapshot.ocKey(8, ROOT);
        String childKey = OcPlanningSnapshot.ocKey(9, CHILD);
        OcFactionPlanningPolicy policy = new OcFactionPlanningPolicy(1L,
                OcEvaluationMode.POSITION_WEIGHT, 0, Set.of(rootKey), List.of());
        return new OcPlanningSnapshot(1L, NOW, policy, List.of(), Map.of(),
                List.of(member(Map.of(
                        capability(ROOT, "Hacker"), 90,
                        capability(CHILD, "Driver"), 90), NOW)),
                Map.of(rootKey, root, childKey, child), List.of(edge),
                Map.of(rootKey, List.of(slot("Hacker#1", "Hacker")),
                        childKey, List.of(slot("Driver#1", "Driver"))),
                Set.of(), List.of());
    }

    private TornSettingOcPlanProfileDO profile(long id, String name, int rank, String pool) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(rank);
        profile.setSpawnPool(pool);
        profile.setPlanStatus("READY");
        profile.setRewardFloor(0L);
        return profile;
    }

    private OcPlanSlot slot(String code, String position) {
        return new OcPlanSlot(code, position, 50, 1, BigDecimal.ONE);
    }

    private OcMemberCandidate member(Map<String, Integer> passRates, LocalDateTime availableAt) {
        return new OcMemberCandidate(10L, "member", availableAt, false,
                passRates, Map.of());
    }

    private String capability(String ocName, String position) {
        return OcMemberCandidate.capabilityKey(ocName.equals(ROOT) ? 8 : 9,
                ocName, position);
    }
}
