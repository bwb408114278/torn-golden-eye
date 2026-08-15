package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 有限事件时间线规划引擎测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("有限事件时间线规划引擎")
class OcTimelinePlanningEngineTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("已有人OC被迫等待16:00释放属可恢复停转且不计为主动新增")
    void shouldTreatForcedFactPauseAsRecoverableButNotPlannerCreated() {
        OcMemberCandidate releasedAtSixteen = member(1L, NOW.plusHours(8));
        OcTimelineObligation teamC = joinedObligation(1L, NOW.plusHours(6));
        OcRefreshSafetyRequest request = request(List.of(releasedAtSixteen),
                Set.of(), List.of(teamC));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        SafeCandidate zeroVector = candidate(result, 0, 0);
        assertEquals(SafeCandidate.PauseTier.ZERO_PAUSE, zeroVector.pauseTier());
        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT));
    }

    @Test
    @DisplayName("18:00边界前16:00释放成员可零停转承接且不要求08:00永久预留")
    void shouldAllowSixteenOClockReleaseToCoverEighteenOClockBoundary() {
        OcMemberCandidate releasedAtSixteen = member(1L, NOW.plusHours(8));
        OcTimelineObligation teamD = joinedObligation(1L, NOW.plusHours(10));
        OcRefreshSafetyRequest request = request(List.of(releasedAtSixteen),
                Set.of(), List.of(teamD));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertEquals(SafeCandidate.PauseTier.ZERO_PAUSE, candidate(result, 0, 0).pauseTier());
        assertFalse(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT));
    }

    @Test
    @DisplayName("释放成员不满足岗位门槛时不能视为恢复保障")
    void shouldNotTreatIncompatibleReleaseAsRecovery() {
        OcMemberCandidate incompatible = new OcMemberCandidate(1L, "user1",
                NOW.plusHours(8), false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 30), Map.of());
        OcTimelineObligation teamC = joinedObligation(1L, NOW.plusHours(6));
        OcRefreshSafetyRequest request = request(List.of(incompatible),
                Set.of(), List.of(teamC));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertEquals(OcProofStatusEnum.PROVEN_INFEASIBLE, result.assessment().proofStatus());
        assertTrue(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    @DisplayName("不可证明占用成员整窗不可用且输出原因码")
    void shouldExcludeUnprovableMembersFromWholeWindow() {
        OcMemberCandidate occupied = member(1L, NOW);
        OcTimelineObligation plannedEmpty = plannedEmptyObligation(20L, NOW.plusDays(7));
        OcRefreshSafetyRequest request = request(List.of(occupied),
                Set.of(1L), List.of(plannedEmpty));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.EMPTY_OC_EXPIRY_PRESSURE));
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    @DisplayName("已启动链后继无法履约时应阻断全部新增刷新")
    void shouldBlockAllRefreshWhenCommittedChainSuccessorCannotFulfill() {
        OcTimelineObligation successor = chainSuccessorObligation(30L, NOW.plusDays(7));
        OcRefreshSafetyRequest request = request(List.of(),
                Set.of(), List.of(successor));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertEquals(OcProofStatusEnum.PROVEN_INFEASIBLE, result.assessment().proofStatus());
        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    @DisplayName("证明窗口结束时间应取最早业务边界减30分钟")
    void shouldComputeProofWindowEndFromEarliestBoundary() {
        OcTimelineObligation plannedEmpty = plannedEmptyObligation(20L, NOW.plusDays(3));
        OcRefreshSafetyRequest request = request(List.of(member(1L, NOW)),
                Set.of(), List.of(plannedEmpty));

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertEquals(NOW.plusDays(3).minusMinutes(30), result.assessment().proofWindowEnd());
    }

    private OcTimelinePlanningEngine engine() {
        return new OcTimelinePlanningEngine(Duration.ofSeconds(5), 6);
    }

    private SafeCandidate candidate(OcRefreshSafetyResult result, int normal, int high) {
        return result.candidates().stream()
                .filter(candidate -> candidate.vector()
                        .equals(new OcRefreshVector(normal, high)))
                .findFirst().orElseThrow();
    }

    private OcRefreshSafetyRequest request(List<OcMemberCandidate> members,
                                           Set<Long> unprovableMemberIds,
                                           List<OcTimelineObligation> obligations) {
        return new OcRefreshSafetyRequest(members, unprovableMemberIds, obligations, Map.of(),
                List.of(), List.of(), NOW);
    }

    private OcTimelineObligation joinedObligation(long ocId, LocalDateTime readyAt) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, readyAt, null, false,
                slots(), Set.of("Worker#1"), Set.of(ocId * 100));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelineObligation chainSuccessorObligation(long ocId, LocalDateTime firstJoinDeadline) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Child", 9, null, firstJoinDeadline,
                true, slots(), Set.of(), Set.of());
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR, demand,
                firstJoinDeadline, NOW);
    }

    private OcTimelineObligation plannedEmptyObligation(long ocId,
                                                        LocalDateTime firstJoinDeadline) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, null, firstJoinDeadline,
                false, slots(), Set.of(), Set.of());
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.PLANNED_EMPTY, demand,
                firstJoinDeadline, null);
    }

    private List<OcPlanSlot> slots() {
        return List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null),
                new OcPlanSlot("Worker#2", "Worker", 60, 2, null));
    }

    private OcMemberCandidate member(long id, LocalDateTime availableAt) {
        return new OcMemberCandidate(id, "user" + id, availableAt, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90), Map.of());
    }
}
