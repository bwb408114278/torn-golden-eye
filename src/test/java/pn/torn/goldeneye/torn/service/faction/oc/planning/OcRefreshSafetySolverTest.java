package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC刷新联合安全边界求解器测试。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.17
 */
@DisplayName("OC刷新安全边界求解")
class OcRefreshSafetySolverTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 15, 0);

    @Test
    @DisplayName("计划内无人OC应占用刷新容量")
    void shouldCountPlannedEmptyDemandAgainstCapacity() {
        OcMemberCandidate member = memberForBoth(1L);
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(List.of(member),
                List.of(demand("Normal")), List.of(demand("Normal")), List.of(), NOW);
        OcRefreshSafetyRequest withoutBase = new OcRefreshSafetyRequest(List.of(member),
                List.of(), List.of(demand("Normal")), List.of(), NOW);

        OcRefreshSafetyResult result = solver(8).solve(request);
        OcRefreshSafetyResult resultWithoutBase = solver(8).solve(withoutBase);

        assertTrue(isUnsafe(result, 1, 0), result.toString());
        assertTrue(isSafe(resultWithoutBase, 1, 0), resultWithoutBase.toString());
        assertTrue(isUnsafe(resultWithoutBase, 2, 0), resultWithoutBase.toString());
    }

    @Test
    @DisplayName("应保障普通池所有计划内随机结果")
    void shouldProtectAgainstEveryPlannedNormalPoolOutcome() {
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, "Easy"), member(2L, "Hard"), member(3L, "Hard"),
                        member(4L, "Hard"), member(5L, "Hard")),
                List.of(), List.of(demand("Easy"), multiSlotDemand("Hard")),
                List.of(), NOW);

        OcRefreshSafetyResult result = solver(3).solve(request);

        assertTrue(isSafe(result, 1, 0), result.toString());
        assertTrue(isUnsafe(result, 2, 0), result.toString());
    }

    @Test
    @DisplayName("根节点成员释放后可加入后继")
    void shouldAllowRootMemberToJoinSuccessorAfterRelease() {
        List<OcTeamDemand> chain = List.of(demand("Root"), demand("Child"));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(memberForChain(1L)), List.of(), List.of(), List.of(chain), NOW);

        OcRefreshSafetyResult result = solver(2).solve(request);

        assertTrue(isSafe(result, 0, 1), result.toString());
    }

    @Test
    @DisplayName("未来释放的固定成员不能支撑本轮新增OC")
    void shouldNotUseFutureReleasedFixedMemberForCurrentBatch() {
        OcMemberCandidate fixedMember = new OcMemberCandidate(1L, "fixed",
                NOW.plusDays(7), true,
                Map.of(OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90),
                Map.of());
        OcTeamDemand current = new OcTeamDemand(100L, "Current", 8,
                NOW.plusDays(1), NOW.plusYears(1), false,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of("Worker#1"), Set.of(1L));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(fixedMember), List.of(current), List.of(demand("Normal")),
                List.of(), NOW);

        OcRefreshSafetyResult result = solver(1).solve(request);

        assertTrue(isUnsafe(result, 1, 0), result.toString());
    }

    @Test
    @DisplayName("当前计划内高阶链应预留后继")
    void shouldReserveSuccessorForCurrentPlannedHighChain() {
        OcMemberCandidate member = new OcMemberCandidate(1L, "chain-member", NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Root", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(8, "Child", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(8, "Normal", "Worker"), 90),
                Map.of());
        List<OcTeamDemand> currentChain = List.of(demand("Root"), demand("Child"));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member), List.of(), List.of(currentChain),
                List.of(demand("Normal")), List.of(), NOW);

        OcRefreshSafetyResult result = solver(7).solve(request);

        assertTrue(isUnsafe(result, 1, 0), result.toString());
    }

    @Test
    @DisplayName("应保障高阶池所有计划内随机结果")
    void shouldProtectAgainstEveryPlannedHighPoolOutcome() {
        List<OcTeamDemand> easyChain = List.of(demand("EasyRoot"));
        List<OcTeamDemand> hardChain = List.of(multiSlotDemand("HardRoot"));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, "EasyRoot"), member(2L, "HardRoot"),
                        member(3L, "HardRoot"), member(4L, "HardRoot"),
                        member(5L, "HardRoot")),
                List.of(), List.of(), List.of(), List.of(easyChain, hardChain), NOW);

        OcRefreshSafetyResult result = solver(3).solve(request);

        assertTrue(isSafe(result, 0, 1), result.toString());
        assertTrue(isUnsafe(result, 0, 2), result.toString());
    }

    @Test
    @DisplayName("刷新池没有计划模板时应拒绝刷新")
    void shouldRejectRefreshWhenPoolHasNoPlannedTemplate() {
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, "Unused")), List.of(), List.of(), List.of(), NOW);

        OcRefreshSafetyResult result = solver(2).solve(request);

        assertTrue(isUnsafe(result, 1, 0), result.toString());
        assertTrue(isUnsafe(result, 0, 1), result.toString());
    }


    @Test
    @DisplayName("时间预算耗尽时应返回已证明安全下界")
    void shouldReturnProvenLowerBoundWhenTimeBudgetIsExhausted() {
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, "Normal")), List.of(),
                List.of(demand("Normal")), List.of(), NOW);

        OcRefreshSafetyResult result = new OcRefreshSafetySolver(Duration.ZERO, 20).solve(request);

        assertTrue(result.lowerBound());
        assertTrue(result.warnings().stream()
                .anyMatch(message -> message.contains("时间预算")));
    }

    @Test
    @DisplayName("本轮新增普通OC之间不得复用同一成员")
    void shouldNotReuseMemberAcrossNewNormalOcsInSameBatch() {
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(member(1L, "Normal")), List.of(),
                List.of(demand("Normal")), List.of(), NOW);

        OcRefreshSafetyResult result = solver(2).solve(request);

        assertTrue(isSafe(result, 1, 0), result.toString());
        assertTrue(isUnsafe(result, 2, 0), result.toString());
    }

    @Test
    @DisplayName("同一高阶链内部允许复用成员但不同链之间禁止复用")
    void shouldReuseMemberInsideHighChainButNotAcrossHighChains() {
        List<OcTeamDemand> chain = List.of(demand("Root"), demand("Child"));
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(memberForChain(1L)), List.of(), List.of(), List.of(chain), NOW);

        OcRefreshSafetyResult result = solver(2).solve(request);

        assertTrue(isSafe(result, 0, 1), result.toString());
        assertTrue(isUnsafe(result, 0, 2), result.toString());
    }

    private OcRefreshSafetySolver solver(int maxSearch) {
        return new OcRefreshSafetySolver(Duration.ofSeconds(1), maxSearch);
    }

    private boolean isSafe(OcRefreshSafetyResult result, int normal, int high) {
        return result.frontier().stream().anyMatch(bound -> bound.normalCount() >= normal
                && bound.highCount() >= high);
    }

    private boolean isUnsafe(OcRefreshSafetyResult result, int normal, int high) {
        return !isSafe(result, normal, high);
    }

    private OcTeamDemand demand(String name) {
        return new OcTeamDemand(0L, name, 8, null, NOW.plusDays(7), false,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of(), Set.of());
    }

    private OcTeamDemand multiSlotDemand(String name) {
        List<OcPlanSlot> slots = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(index -> new OcPlanSlot("Worker#" + index, "Worker", 60,
                        index, null))
                .toList();
        return new OcTeamDemand(0L, name, 8, null, NOW.plusDays(7), false,
                slots, Set.of(), Set.of());
    }

    private OcMemberCandidate member(long id, String ocName) {
        return new OcMemberCandidate(id, "u" + id, NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, ocName, "Worker"), 90), Map.of());
    }

    private OcMemberCandidate memberForBoth(long id) {
        return member(id, "Normal");
    }

    private OcMemberCandidate memberForChain(long id) {
        return new OcMemberCandidate(id, "u" + id, NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Root", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(8, "Child", "Worker"), 90), Map.of());
    }
}
