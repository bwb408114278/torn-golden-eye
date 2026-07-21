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
 * @version 1.2.11
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

    @Test
    @DisplayName("部分入队OC缺少阶段时间时无停转匹配应停止规划")
    void shouldFailClosedWithoutPauseWhenPartiallyJoinedOcHasNoReadyTime() {
        OcTeamDemand demand = demand(null, Set.of("Worker#1"), Set.of(1L), 2);

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(member(2L, SNAPSHOT)), SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("空OC首人恰好在期限时加入应允许无停转匹配")
    void shouldAllowWithoutPauseWhenFirstMemberJoinsAtExpiry() {
        LocalDateTime expiresAt = SNAPSHOT;
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null, expiresAt,
                false, slots(1), Set.of(), Set.of());

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(member(1L, expiresAt)), SNAPSHOT);

        assertTrue(result.complete());
    }

    @Test
    @DisplayName("空OC首人晚于期限时应拒绝无停转匹配")
    void shouldRejectWithoutPauseWhenFirstMemberJoinsAfterExpiry() {
        LocalDateTime expiresAt = SNAPSHOT.minusMinutes(1);
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null, expiresAt,
                false, slots(1), Set.of(), Set.of());

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(member(1L, SNAPSHOT)), SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("新增空OC的首位成员不能延迟加入")
    void shouldRejectNewEmptyOcWhenFirstMemberCannotJoinNow() {
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null,
                SNAPSHOT.plusDays(7), false, slots(1), Set.of(), Set.of());
        OcMemberCandidate lateMember = member(1L, SNAPSHOT.plusMinutes(1));

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(lateMember), SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("后续成员晚于当前阶段时间时应拒绝新增停转")
    void shouldRejectNewPauseWhenMemberJoinsAfterReadyTime() {
        LocalDateTime readyTime = SNAPSHOT.plusHours(1);
        OcTeamDemand demand = demand(readyTime,
                Set.of("Worker#1"), Set.of(1L), 2);
        OcMemberCandidate lateMember = member(2L, readyTime.plusMinutes(1));

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(lateMember), SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("后续成员在对应准备阶段前释放时应允许无停转加入")
    void shouldAllowMembersReleasedBeforeEachPreparationDeadline() {
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null,
                SNAPSHOT.plusDays(7), false, slots(3), Set.of(), Set.of());
        List<OcMemberCandidate> members = List.of(
                member(1L, SNAPSHOT),
                member(2L, SNAPSHOT.plusHours(20)),
                member(3L, SNAPSHOT.plusHours(47)));

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, members, SNAPSHOT);

        assertTrue(result.complete());
        assertEquals(SNAPSHOT, result.assignments().get(0).joinAt());
        assertEquals(SNAPSHOT.plusHours(20), result.assignments().get(1).joinAt());
        assertEquals(SNAPSHOT.plusHours(47), result.assignments().get(2).joinAt());
        assertEquals(SNAPSHOT.plusHours(72), result.completionAt());
    }

    @Test
    @DisplayName("成员晚于对应准备阶段释放时应拒绝无停转加入")
    void shouldRejectMemberReleasedAfterPreparationDeadline() {
        OcTeamDemand demand = new OcTeamDemand(0L, "Test", 8, null,
                SNAPSHOT.plusDays(7), false, slots(3), Set.of(), Set.of());
        List<OcMemberCandidate> members = List.of(
                member(1L, SNAPSHOT),
                member(2L, SNAPSHOT.plusHours(20)),
                member(3L, SNAPSHOT.plusHours(49)));

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, members, SNAPSHOT);

        assertFalse(result.complete());
    }

    @Test
    @DisplayName("阶段排程应为稀缺岗位保留唯一合格成员")
    void shouldReserveUniqueMemberForScarceSlot() {
        OcTeamDemand demand = new OcTeamDemand(0L, "Scarce", 8, null,
                SNAPSHOT.plusDays(7), false,
                List.of(new OcPlanSlot("Common#1", "Common", 60, 1, null),
                        new OcPlanSlot("Scarce#1", "Scarce", 60, 2, null)),
                Set.of(), Set.of());
        OcMemberCandidate flexible = new OcMemberCandidate(1L, "flexible", SNAPSHOT, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Scarce", "Common"), 90,
                        OcMemberCandidate.capabilityKey(8, "Scarce", "Scarce"), 90), Map.of());
        OcMemberCandidate common = new OcMemberCandidate(2L, "common", SNAPSHOT.plusHours(20), false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Scarce", "Common"), 90), Map.of());

        OcRosterMatchResult result = new OcRosterMatcher().matchWithoutPause(
                demand, List.of(flexible, common), SNAPSHOT);

        assertTrue(result.complete());
        assertEquals("Scarce#1", result.assignments().getFirst().slotCode());
        assertEquals("Common#1", result.assignments().getLast().slotCode());
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
