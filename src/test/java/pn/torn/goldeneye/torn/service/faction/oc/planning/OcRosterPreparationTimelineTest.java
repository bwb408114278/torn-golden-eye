package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC阵容准备时间线测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("OC阵容准备时间线")
class OcRosterPreparationTimelineTest {
    private static final LocalDateTime SNAPSHOT = LocalDateTime.of(2026, 7, 17, 6, 0);

    @Test
    @DisplayName("成员在当前阶段完成前加入时应顺延阶段时间")
    void shouldExtendReadyTimeWhenMemberJoinsBeforeCurrentStageCompletes() {
        OcTeamDemand demand = demand(LocalDateTime.of(2026, 7, 18, 7, 0),
                Set.of("Worker#1"), Set.of(1L), 2);
        OcMemberCandidate member = member(2L, LocalDateTime.of(2026, 7, 17, 15, 0));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, List.of(member), SNAPSHOT);

        assertTrue(result.complete());
        assertEquals(LocalDateTime.of(2026, 7, 17, 15, 0),
                result.assignments().getFirst().joinAt());
        assertEquals(LocalDateTime.of(2026, 7, 19, 7, 0), result.completionAt());
    }

    @Test
    @DisplayName("成员在OC停转后加入时应重启阶段时间")
    void shouldRestartReadyTimeWhenMemberJoinsAfterOcPaused() {
        OcTeamDemand demand = demand(LocalDateTime.of(2026, 7, 19, 7, 0),
                Set.of("Worker#1", "Worker#2"), Set.of(1L, 2L), 3);
        OcMemberCandidate member = member(3L, LocalDateTime.of(2026, 7, 19, 9, 0));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, List.of(member), SNAPSHOT);

        assertTrue(result.complete());
        assertEquals(LocalDateTime.of(2026, 7, 20, 9, 0), result.completionAt());
    }

    @Test
    @DisplayName("首人按时加入后允许整队在空OC期限后完成")
    void shouldAllowCompletionAfterEmptyOcExpiryWhenFirstMemberJoinedInTime() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 24, 6, 0);
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null, expiresAt,
                false, slots(8), Set.of(), Set.of());
        List<OcMemberCandidate> members = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> member(id, LocalDateTime.of(2026, 7, 24, 5, 0)))
                .toList();

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, members, SNAPSHOT);

        assertTrue(result.complete());
        assertFalse(result.completionAt().isBefore(expiresAt));
        assertEquals(LocalDateTime.of(2026, 8, 1, 5, 0), result.completionAt());
    }

    @Test
    @DisplayName("首人无法在期限内加入时应拒绝空OC")
    void shouldRejectEmptyOcWhenFirstMemberCannotJoinBeforeExpiry() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 24, 6, 0);
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null, expiresAt,
                false, slots(1), Set.of(), Set.of());
        OcMemberCandidate member = member(1L, expiresAt.plusMinutes(1));

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, List.of(member), SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("满员OC缺少阶段时间时应停止规划")
    void shouldFailClosedWhenFullJoinedOcHasNoReadyTime() {
        OcTeamDemand demand = demand(null, Set.of("Worker#1"), Set.of(1L), 1);

        OcRosterMatchResult result = new OcRosterMatcher().match(demand, List.of(), SNAPSHOT);

        assertFalse(result.complete());
    }

    private OcTeamDemand demand(LocalDateTime readyTime, Set<String> fixedSlots,
                                Set<Long> fixedMembers, int totalSlots) {
        return new OcTeamDemand(1L, "Test", 8, readyTime, null,
                false, slots(totalSlots), fixedSlots, fixedMembers);
    }

    private List<OcPlanSlot> slots(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new OcPlanSlot("Worker#" + index,
                        "Worker", 60, index, null))
                .toList();
    }

    private OcMemberCandidate member(long id, LocalDateTime availableAt) {
        return new OcMemberCandidate(id, "u" + id, availableAt, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Test", "Worker"), 90), Map.of());
    }
}
