package pn.torn.goldeneye.torn.service.faction.oc.planning.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC规划核心能力测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("OC规划核心能力")
class OcPlanningCoreTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 14, 0, 0);

    @Test
    @DisplayName("应解析三种二级规划指令")
    void commandModeShouldParseThreeSecondaryCommands() {
        assertEquals(OcPlanMode.CONSERVATIVE, OcPlanMode.parse("保守"));
        assertEquals(OcPlanMode.BALANCED, OcPlanMode.parse("均衡"));
        assertEquals(OcPlanMode.PROFIT, OcPlanMode.parse("收益"));
        assertThrows(IllegalArgumentException.class, () -> OcPlanMode.parse(""));
        assertThrows(IllegalArgumentException.class, () -> OcPlanMode.parse("未知"));
    }

    @Test
    @DisplayName("应保留固定成员并为不同空位分配不同候选人")
    void shouldKeepFixedMemberAndAssignDistinctCandidatesToVacancies() {
        OcTeamDemand demand = new OcTeamDemand(100L, "Test OC", 8, NOW,
                NOW.plusDays(7), false,
                List.of(slot("Hacker#1", "Hacker", 60), slot("Driver#1", "Driver", 60)),
                Set.of("Hacker#1"), Set.of(1L));
        List<OcMemberCandidate> candidates = List.of(
                member(1L, true, Map.of(capability("Hacker"), 100)),
                member(2L, false, Map.of(capability("Driver"), 80)));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, candidates, NOW);

        assertTrue(result.complete());
        assertEquals(1, result.assignments().size());
        assertEquals(2L, result.assignments().getFirst().userId());
        assertEquals("Driver#1", result.assignments().getFirst().slotCode());
    }

    @Test
    @DisplayName("岗位无合格候选人时应拒绝不完整阵容")
    void shouldRejectPartialRosterWhenOneSlotHasNoEligibleCandidate() {
        OcTeamDemand demand = new OcTeamDemand(101L, "Test OC", 8, NOW,
                NOW.plusDays(7), false,
                List.of(slot("Hacker#1", "Hacker", 60), slot("Driver#1", "Driver", 60)),
                Set.of(), Set.of());
        List<OcMemberCandidate> candidates = List.of(
                member(2L, false, Map.of(capability("Hacker"), 80)),
                member(3L, false, Map.of(capability("Hacker"), 75)));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, candidates, NOW);

        assertFalse(result.complete());
        assertTrue(result.assignments().isEmpty());
    }

    private OcPlanSlot slot(String code, String position, int threshold) {
        return new OcPlanSlot(code, position, threshold, 1, null);
    }

    private OcMemberCandidate member(long id, boolean fixed, Map<String, Integer> passRates) {
        return new OcMemberCandidate(id, "u" + id, NOW, fixed, passRates, Map.of());
    }

    private String capability(String position) {
        return OcMemberCandidate.capabilityKey(8, "Test OC", position);
    }
}
