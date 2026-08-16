package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshVectorSearcher;

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

    @Test
    @DisplayName("已启动根的链后继成员不可被期限重叠的普通刷新抢占")
    void shouldReserveStartedRootSuccessorMemberAgainstOverlappingNormalRefresh() {
        OcMemberCandidate member = new OcMemberCandidate(1L, "user1", NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90), Map.of());
        OcTimelineObligation startedRoot = startedRootObligation(1L, NOW.plusHours(8));
        OcTeamDemand childNode = new OcTeamDemand(0L, "Child", 9, null, null, true,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)), Set.of(), Set.of());
        List<OcTeamDemand> successors = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(ignored -> childNode).toList();
        OcTeamDemand normal = new OcTeamDemand(0L, "Normal", 8, null, NOW.plusDays(7),
                false, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of(), Set.of());
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(List.of(member), Set.of(),
                List.of(startedRoot), Map.of("oc:1", successors),
                List.of(normal), List.of(), NOW);

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertTrue(result.candidates().stream().anyMatch(item ->
                item.vector().equals(new OcRefreshVector(0, 0))), result.toString());
        assertFalse(result.candidates().stream().anyMatch(item ->
                        item.vector().equals(new OcRefreshVector(1, 0))),
                "重叠普通刷新不得抢占已启动链后继的成员");
        assertEquals(OcProofStatusEnum.PROVEN_SAFE, result.assessment().proofStatus());
        assertFalse(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
    }

    @Test
    @DisplayName("跨事件稀缺岗位应通过多状态搜索选择不破坏后续义务的匹配")
    void shouldChooseNonConflictingMatchAcrossEventsForScarceSlot() {
        OcMemberCandidate scarceCapable = dualCapabilityMember(1L);
        OcMemberCandidate laterAlternative = new OcMemberCandidate(2L, "user2",
                NOW.plusHours(2), false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Alpha", "Worker"), 90), Map.of());
        OcTimelineObligation alpha = plannedEmptyNamed(20L, "Alpha", NOW.plusHours(10));
        OcTimelineObligation beta = plannedEmptyNamed(21L, "Beta", NOW.plusHours(12));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(scarceCapable, laterAlternative), Set.of(),
                List.of(alpha, beta), Map.of(), List.of(), List.of(), NOW);

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertFalse(result.assessment().riskFlags()
                        .contains(OcRiskFlagEnum.EMPTY_OC_EXPIRY_PRESSURE),
                "早义务应让出仅剩的稀缺成员给期限更紧的后续义务: " + result);
    }

    @Test
    @DisplayName("人为缩小状态预算时必须输出未证明搜索预算而不是不可行或卡死")
    void shouldReportUnprovenSearchBudgetWhenStateBudgetShrunk() {
        OcMemberCandidate member = member(1L, NOW);
        OcTimelineObligation first = plannedEmptyObligation(20L, NOW.plusDays(3));
        OcTimelineObligation second = plannedEmptyObligation(21L, NOW.plusDays(3));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member), Set.of(), List.of(first, second), Map.of(),
                List.of(), List.of(), NOW);
        OcTimelineEventScheduler budgetScheduler = new OcTimelineEventScheduler(1);
        OcTimelinePlanningEngine budgetEngine = new OcTimelinePlanningEngine(
                Duration.ofSeconds(5), 6, budgetScheduler,
                new OcRefreshVectorSearcher(6, budgetScheduler));

        OcRefreshSafetyResult result = budgetEngine.solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertEquals(OcProofStatusEnum.UNPROVEN_SEARCH_BUDGET,
                result.assessment().proofStatus(), result.toString());
        assertTrue(result.lowerBound());
        assertFalse(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
    }

    @Test
    @DisplayName("旧锚点释放后只有无关队伍完成且链后继不能维持时候选不得标记安全")
    void shouldNotMarkSafeWhenOnlyUnrelatedCompletionsAndSuccessorUnmaintainable() {
        OcMemberCandidate member = new OcMemberCandidate(1L, "user1",
                NOW.plusHours(32), false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90), Map.of());
        OcTimelineObligation unrelated = fullJoinedObligation(10L, NOW.plusHours(4));
        OcTimelineObligation anchorTeam = joinedObligation(11L, NOW.plusHours(6));
        OcTimelineObligation successor = chainSuccessorObligation(30L, NOW.plusHours(20));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member), Set.of(), List.of(unrelated, anchorTeam, successor),
                Map.of(), List.of(), List.of(), NOW);

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertTrue(result.candidates().isEmpty(),
                "已投入链后继不能维持时不得输出任何安全候选: " + result);
        assertEquals(OcProofStatusEnum.PROVEN_INFEASIBLE, result.assessment().proofStatus());
        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
    }

    @Test
    @DisplayName("窗口内释放成员投入新队但完整释放在证明窗口后不得输出已证明安全候选")
    void shouldNotProveSafeWhenReinvestedMemberCompletesAfterProofWindow() {
        OcMemberCandidate member = member(1L, NOW);
        OcTimelineObligation completedInWindow = fullJoinedObligationWithFixedMember(
                10L, NOW.minusHours(2), 1L);
        OcTimelineObligation completesAfterWindow = plannedEmptyNamed(
                20L, "Normal", NOW.plusHours(8));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member), Set.of(), List.of(completedInWindow, completesAfterWindow),
                Map.of(), List.of(), List.of(), NOW);

        OcRefreshSafetyResult result = engine().solve(request, Map.of(),
                OcConfigurationStatusEnum.VALID);

        assertTrue(result.candidates().isEmpty(),
                "窗口后释放不能证明窗口内连续流动性，不得输出安全候选: " + result);
        assertNotEquals(OcProofStatusEnum.PROVEN_SAFE,
                result.assessment().proofStatus(), result.toString());
        assertEquals(OcProofStatusEnum.PROVEN_INFEASIBLE,
                result.assessment().proofStatus(), result.toString());
        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.DEADLOCK_RISK), result.toString());
    }

    private OcTimelineObligation fullJoinedObligationWithFixedMember(long ocId,
                                                                     LocalDateTime readyAt,
                                                                     long fixedMemberId) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, readyAt, null, false,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of("Worker#1"), Set.of(fixedMemberId));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelineObligation fullJoinedObligation(long ocId, LocalDateTime readyAt) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, readyAt, null, false,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of("Worker#1"), Set.of(ocId * 100));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcMemberCandidate dualCapabilityMember(long id) {
        return new OcMemberCandidate(id, "user" + id, NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Alpha", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(8, "Beta", "Worker"), 90), Map.of());
    }

    private OcTimelineObligation plannedEmptyNamed(long ocId, String name,
                                                   LocalDateTime firstJoinDeadline) {
        OcTeamDemand demand = new OcTeamDemand(ocId, name, 8, null, firstJoinDeadline,
                false, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of(), Set.of());
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.PLANNED_EMPTY, demand,
                firstJoinDeadline, null);
    }

    private OcTimelineObligation startedRootObligation(long ocId, LocalDateTime readyAt) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Root", 8, readyAt, null, true,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of("Worker#1"), Set.of(ocId * 100));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelinePlanningEngine engine() {
        OcTimelineEventScheduler scheduler = new OcTimelineEventScheduler();
        return new OcTimelinePlanningEngine(Duration.ofSeconds(5), 6, scheduler,
                new OcRefreshVectorSearcher(6, scheduler));
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
