package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcPlanningCoreTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 14, 0, 0);

    @Test
    void commandModeShouldParseThreeSecondaryCommands() {
        assertEquals(OcPlanMode.CONSERVATIVE, OcPlanMode.parse("保守"));
        assertEquals(OcPlanMode.BALANCED, OcPlanMode.parse("均衡"));
        assertEquals(OcPlanMode.PROFIT, OcPlanMode.parse("收益"));
        assertThrows(IllegalArgumentException.class, () -> OcPlanMode.parse(""));
        assertThrows(IllegalArgumentException.class, () -> OcPlanMode.parse("未知"));
    }

    @Test
    void shouldCalculateSequentialPlanningPersonDays() {
        assertEquals(10, OcPlanningTimeCalculator.calculateSequentialMemberDays(4));
        assertEquals(15, OcPlanningTimeCalculator.calculateSequentialMemberDays(5));
        assertEquals(21, OcPlanningTimeCalculator.calculateSequentialMemberDays(6));
        assertEquals(new BigDecimal("12.00"),
                OcPlanningTimeCalculator.calculateRemainingHours(new BigDecimal("50")));
    }

    @Test
    void shouldKeepFixedMemberAndAssignDistinctCandidatesToVacancies() {
        OcTeamDemand demand = new OcTeamDemand(100L, "Test OC", 8, NOW,
                NOW.plusDays(7), false,
                List.of(slot("Hacker#1", "Hacker", 60), slot("Driver#1", "Driver", 60)),
                Set.of("Hacker#1"), Set.of(1L));
        List<OcMemberCandidate> candidates = List.of(
                member(1L, true, NOW, Map.of(capability(8, "Test OC", "Hacker"), 100)),
                member(2L, false, NOW, Map.of(capability(8, "Test OC", "Driver"), 80)));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, candidates, NOW);

        assertTrue(result.complete());
        assertEquals(1, result.assignments().size());
        assertEquals(2L, result.assignments().getFirst().userId());
        assertEquals("Driver#1", result.assignments().getFirst().slotCode());
    }

    @Test
    void shouldRejectPartialRosterWhenOneSlotHasNoEligibleCandidate() {
        OcTeamDemand demand = new OcTeamDemand(101L, "Test OC", 8, NOW,
                NOW.plusDays(7), false,
                List.of(slot("Hacker#1", "Hacker", 60), slot("Driver#1", "Driver", 60)),
                Set.of(), Set.of());
        List<OcMemberCandidate> candidates = List.of(
                member(2L, false, NOW, Map.of(capability(8, "Test OC", "Hacker"), 80)),
                member(3L, false, NOW, Map.of(capability(8, "Test OC", "Hacker"), 75)));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, candidates, NOW);

        assertFalse(result.complete());
        assertTrue(result.assignments().isEmpty());
    }

    @Test
    void shouldCalculateSafeCapacityOnlyWhenEveryChainNodeHasRoster() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        List<OcMemberCandidate> twoCompleteRosters = List.of(
                member(10L, false, NOW, Map.of(capability(8, "Root", "Hacker"), 80,
                        capability(9, "Child", "Driver"), 80)),
                member(11L, false, NOW, Map.of(capability(8, "Root", "Hacker"), 80,
                        capability(9, "Child", "Driver"), 80)));

        OcSafeChainCapacityResult capacity = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, twoCompleteRosters, List.of(), 3, NOW).capacity();

        assertEquals(3, capacity.provenSafeConcurrentCount());
        assertFalse(capacity.maximumProven());
    }

    @Test
    void shouldReturnZeroAdditionalCapacityWhenChildRosterIsMissing() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        List<OcMemberCandidate> onlyRoot = List.of(member(10L, false, NOW,
                Map.of(capability(8, "Root", "Hacker"), 80)));

        OcSafeChainCapacityResult capacity = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, onlyRoot, List.of(), 2, NOW).capacity();

        assertEquals(0, capacity.provenAdditionalCount());
    }

    @Test
    void shouldNotProveCommittedChainWhenChildRosterIsMissing() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        List<OcMemberCandidate> onlyRoot = List.of(member(10L, false, NOW,
                Map.of(capability(8, "Root", "Hacker"), 80)));

        CommittedChainObligation obligation = new CommittedChainObligation(
                100L, chain, 1, NOW);
        OcChainCapacityPlanningResult result = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, onlyRoot, List.of(obligation), 3, NOW);
        OcSafeChainCapacityResult capacity = result.capacity();

        assertEquals(1, capacity.committedCount());
        assertEquals(0, capacity.provenSafeConcurrentCount());
        assertEquals(0, capacity.provenAdditionalCount());
        assertFalse(result.committedObligationsFeasible());
        assertFalse(capacity.maximumProven());
    }

    @Test
    void shouldStartCommittedChainFromSuccessorWithoutRematchingRoot() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        LocalDateTime rootCompletionAt = NOW.plusDays(2);
        OcMemberCandidate childOnly = member(21L, false, rootCompletionAt,
                Map.of(capability(9, "Child", "Driver"), 90));
        CommittedChainObligation obligation = new CommittedChainObligation(
                200L, chain, 1, rootCompletionAt);

        OcChainCapacityPlanningResult result = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, List.of(childOnly), List.of(obligation), 0, NOW);

        assertTrue(result.committedObligationsFeasible());
        assertEquals(1, result.capacity().committedCount());
        assertEquals(0, result.capacity().provenAdditionalCount());
        assertEquals(1, result.reservedAssignments().size());
        assertEquals("Driver#1", result.reservedAssignments().getFirst().slotCode());
        assertTrue(result.memberTimeline().getFirst().availableAt().isAfter(rootCompletionAt));
    }

    @Test
    void shouldGiveConditionalSuccessorASevenDayWindowFromItsStartTime() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        LocalDateTime lateRootCompletion = NOW.plusDays(10);
        OcMemberCandidate childOnly = member(22L, false, lateRootCompletion,
                Map.of(capability(9, "Child", "Driver"), 90));
        CommittedChainObligation obligation = new CommittedChainObligation(
                201L, chain, 1, lateRootCompletion);

        OcChainCapacityPlanningResult result = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, List.of(childOnly), List.of(obligation), 0, NOW);

        assertTrue(result.committedObligationsFeasible());
        assertEquals(1, result.reservedAssignments().size());
        assertTrue(result.reservedAssignments().getFirst().stageCompleteAt()
                .isBefore(lateRootCompletion.plusDays(7)));
    }

    @Test
    void shouldReserveCommittedObligationsBySuccessorStartTime() {
        List<OcTeamDemand> chain = List.of(
                demand("Root", "Hacker#1", "Hacker"),
                demand("Child", "Driver#1", "Driver"));
        OcMemberCandidate driver = member(23L, false, NOW,
                Map.of(capability(9, "Child", "Driver"), 90));
        CommittedChainObligation late = new CommittedChainObligation(
                302L, chain, 1, NOW.plusDays(2));
        CommittedChainObligation early = new CommittedChainObligation(
                301L, chain, 1, NOW);

        OcChainCapacityPlanningResult result = new OcSafeConcurrentChainCapacitySolver()
                .calculate(chain, List.of(driver), List.of(late, early), 0, NOW);

        assertTrue(result.committedObligationsFeasible());
        assertEquals(2, result.reservedAssignments().size());
        assertEquals(2, result.capacity().committedCount());
    }

    private OcTeamDemand demand(String name, String slotCode, String position) {
        int rank = "Root".equals(name) ? 8 : 9;
        return new OcTeamDemand(0L, name, rank, NOW, NOW.plusDays(7), true,
                List.of(slot(slotCode, position, 60)), Set.of(), Set.of());
    }

    private OcPlanSlot slot(String code, String position, int passRate) {
        return new OcPlanSlot(code, position, passRate, 1, BigDecimal.ONE);
    }

    private OcMemberCandidate member(long id, boolean fixed, LocalDateTime availableAt,
                                     Map<String, Integer> capabilities) {
        return new OcMemberCandidate(id, "U" + id, availableAt, fixed,
                new HashMap<>(capabilities), Map.of());
    }

    private String capability(int rank, String name, String position) {
        return OcMemberCandidate.capabilityKey(rank, name, position);
    }
}
