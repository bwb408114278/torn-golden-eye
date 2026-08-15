package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC刷新时间线求解器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("OC刷新时间线求解")
class OcRefreshSafetySolverTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 8, 0);

    @Test
    @DisplayName("确定性岗位矛盾时应证明不可行并标记卡死风险")
    void shouldProveInfeasibleWithDeadlockRiskOnDeterministicContradiction() {
        OcTimelineObligation first = joinedObligation(1L, NOW.plusHours(24));
        OcTimelineObligation second = joinedObligation(2L, NOW.plusHours(24));
        OcRefreshSafetyRequest request = request(List.of(), List.of(first, second),
                templates("Normal"), List.of());

        OcRefreshSafetyResult result = solver().solve(request, evidence("Normal"));

        assertEquals(OcProofStatusEnum.PROVEN_INFEASIBLE, result.assessment().proofStatus());
        assertTrue(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.assessment().reasonCodes()
                .contains(OcPlanReasonCodeEnum.NO_REPLACEMENT_LIQUIDITY_ANCHOR));
    }

    @Test
    @DisplayName("高负载但存在确定释放事件时不得误判卡死")
    void shouldNotFlagDeadlockWhenReleaseEventProven() {
        OcMemberCandidate member = member(1L, "Normal", NOW.plusHours(8));
        OcTimelineObligation full = fullObligation(10L, NOW.plusHours(8));
        OcRefreshSafetyRequest request = request(List.of(member), List.of(full),
                templates("Normal"), List.of());

        OcRefreshSafetyResult result = solver().solve(request, evidence("Normal"));

        assertFalse(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
        assertTrue(isSafe(result, 1, 0), result.toString());
    }

    @Test
    @DisplayName("成员在A完成后可非重叠复用加入B")
    void shouldAllowFiniteNonOverlappingReuseAfterCompletion() {
        OcMemberCandidate member = member(1L, "Normal", NOW.plusHours(8));
        OcTimelineObligation full = fullObligation(10L, NOW.plusHours(8));
        OcRefreshSafetyRequest request = request(List.of(member), List.of(full),
                templates("Normal"), List.of());

        OcRefreshSafetyResult result = solver().solve(request, evidence("Normal"));

        assertTrue(isSafe(result, 1, 0), result.toString());
        assertTrue(isSafe(result, 2, 0), result.toString());
    }

    @Test
    @DisplayName("无任何确定释放边界时不能凭串行吞吐提高刷新次数")
    void shouldNotIncreaseRefreshWithoutAnyProvenReleaseBoundary() {
        OcMemberCandidate occupied = member(1L, "Normal", NOW.plusDays(99));
        OcRefreshSafetyRequest request = request(List.of(occupied), List.of(),
                templates("Normal"), List.of());

        OcRefreshSafetyResult result = solver().solve(request, evidence("Normal"));

        assertFalse(isSafe(result, 1, 0), result.toString());
        assertFalse(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
    }

    @Test
    @DisplayName("计划内无人OC无法启动时应输出过期压力且无新增刷新候选")
    void shouldFlagExpiryPressureWhenPlannedEmptyCannotStart() {
        OcTimelineObligation plannedEmpty = plannedEmpty(20L, NOW);
        OcRefreshSafetyRequest request = request(List.of(), List.of(plannedEmpty),
                templates("Normal"), List.of());

        OcRefreshSafetyResult result = solver().solve(request, evidence("Normal"));

        assertTrue(result.assessment().riskFlags()
                .contains(OcRiskFlagEnum.EMPTY_OC_EXPIRY_PRESSURE));
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    @DisplayName("应保障普通池所有计划内随机结果组合")
    void shouldProtectAgainstEveryPlannedNormalPoolOutcome() {
        OcMemberCandidate easyMember = member(1L, "Easy", NOW);
        List<OcMemberCandidate> lateHardMembers = List.of(
                member(2L, "Hard", NOW.plusDays(8)), member(3L, "Hard", NOW.plusDays(8)));
        List<OcTeamDemand> templates = List.of(template("Easy", 1), template("Hard", 2));
        OcRefreshSafetyRequest request = request(
                concat(easyMember, lateHardMembers), List.of(), templates, List.of());

        OcRefreshSafetyResult result = solver().solve(
                request, Map.of("8:Easy", evidenceOf(100), "8:Hard", evidenceOf(100)));

        assertTrue(isSafe(result, 0, 0), result.toString());
        assertFalse(isSafe(result, 1, 0), result.toString());
        assertFalse(result.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
    }

    @Test
    @DisplayName("根节点成员释放后可加入链后继")
    void shouldAllowRootMemberToJoinSuccessorAfterRelease() {
        OcMemberCandidate member = member(1L, "Root", NOW);
        member = new OcMemberCandidate(member.userId(), member.nickname(), NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Root", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90),
                Map.of());
        List<OcTeamDemand> chain = List.of(template("Root", 1), childTemplate("Child", 1));
        OcRefreshSafetyRequest request = request(List.of(member), List.of(),
                List.of(), List.of(chain));

        OcRefreshSafetyResult result = solver().solve(request, Map.of());

        assertTrue(isSafe(result, 0, 1), result.toString());
    }

    @Test
    @DisplayName("同一快照重复求解应产生确定结果")
    void shouldProduceDeterministicResultForSameSnapshot() {
        OcMemberCandidate member = member(1L, "Normal", NOW);
        OcRefreshSafetyRequest request = request(List.of(member), List.of(),
                templates("Normal"), List.of());

        OcRefreshSafetyResult first = solver().solve(request, evidence("Normal"));
        OcRefreshSafetyResult second = solver().solve(request, evidence("Normal"));

        assertEquals(first.candidates().stream()
                        .map(OcRefreshSafetyResult.SafeCandidate::vector).toList(),
                second.candidates().stream()
                        .map(OcRefreshSafetyResult.SafeCandidate::vector).toList());
    }

    @Test
    @DisplayName("多替代成员与多事件夹具重复求解后向量原因码证明状态一致")
    void shouldProduceDeterministicResultWithAlternativeMembersAndMultipleEvents() {
        List<OcMemberCandidate> members = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(id -> member(id, "Normal", NOW)).toList();
        List<OcTimelineObligation> obligations = List.of(
                fullObligation(10L, NOW.plusHours(4)),
                plannedEmpty(20L, NOW.plusDays(3)),
                plannedEmpty(21L, NOW.plusDays(3)));
        OcRefreshSafetyRequest request = request(members, obligations,
                templates("Normal"), List.of());

        OcRefreshSafetyResult first = solver().solve(request, evidence("Normal"));
        OcRefreshSafetyResult second = solver().solve(request, evidence("Normal"));

        assertEquals(candidateVectors(first), candidateVectors(second),
                "重复求解的安全候选向量必须一致");
        assertEquals(first.assessment().reasonCodes(), second.assessment().reasonCodes());
        assertEquals(first.assessment().proofStatus(), second.assessment().proofStatus());
    }

    /**
     * 提取结果中的全部安全候选向量。
     *
     * @param result 求解结果
     * @return 候选向量列表
     */
    private List<OcRefreshVector> candidateVectors(OcRefreshSafetyResult result) {
        return result.candidates().stream()
                .map(OcRefreshSafetyResult.SafeCandidate::vector).toList();
    }

    private OcRefreshSafetySolver solver() {
        return new OcRefreshSafetySolver(Duration.ofSeconds(5), 8);
    }

    private boolean isSafe(OcRefreshSafetyResult result, int normal, int high) {
        return result.candidates().stream().anyMatch(candidate ->
                candidate.vector().equals(new OcRefreshVector(normal, high)));
    }

    private List<OcMemberCandidate> concat(OcMemberCandidate first,
                                           List<OcMemberCandidate> rest) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(first),
                rest.stream()).toList();
    }

    private OcRefreshSafetyRequest request(List<OcMemberCandidate> members,
                                           List<OcTimelineObligation> obligations,
                                           List<OcTeamDemand> normalTemplates,
                                           List<List<OcTeamDemand>> highChains) {
        return new OcRefreshSafetyRequest(members, Set.of(), obligations, Map.of(),
                normalTemplates, highChains, NOW);
    }

    private List<OcTeamDemand> templates(String name) {
        return List.of(template(name, 1));
    }

    private OcTeamDemand template(String name, int slots) {
        return new OcTeamDemand(0L, name, 8, null,
                NOW.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS), false,
                slotList(slots), Set.of(), Set.of());
    }

    private OcTeamDemand childTemplate(String name, int slots) {
        return new OcTeamDemand(0L, name, 9, null, null, true,
                slotList(slots), Set.of(), Set.of());
    }

    private List<OcPlanSlot> slotList(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new OcPlanSlot("Worker#" + index, "Worker", 60, 1, null))
                .toList();
    }

    private OcTimelineObligation joinedObligation(long ocId, LocalDateTime readyAt) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, readyAt, null, false,
                slotList(1), Set.of(), Set.of(ocId * 100));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelineObligation fullObligation(long ocId, LocalDateTime readyAt) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, readyAt, null, false,
                slotList(1), Set.of("Worker#1"), Set.of(ocId * 100));
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.EXISTING_JOINED, demand, null, null);
    }

    private OcTimelineObligation plannedEmpty(long ocId, LocalDateTime deadline) {
        OcTeamDemand demand = new OcTeamDemand(ocId, "Normal", 8, null, deadline, false,
                slotList(1), Set.of(), Set.of());
        return new OcTimelineObligation("oc:" + ocId,
                OcTimelineObligation.ObligationKind.PLANNED_EMPTY, demand, deadline, null);
    }

    private OcMemberCandidate member(long id, String ocName, LocalDateTime availableAt) {
        return new OcMemberCandidate(id, "user" + id, availableAt, false,
                Map.of(OcMemberCandidate.capabilityKey(8, ocName, "Worker"), 90), Map.of());
    }

    private Map<String, OcValueEvidence> evidence(String name) {
        return Map.of("8:" + name, evidenceOf(100));
    }

    private OcValueEvidence evidenceOf(long value) {
        return new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                BigDecimal.valueOf(value), 1, NOW.plusHours(24), true);
    }
}
