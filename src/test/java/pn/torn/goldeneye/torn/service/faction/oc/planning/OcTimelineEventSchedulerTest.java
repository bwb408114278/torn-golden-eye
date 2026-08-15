package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线事件推进器测试。聚焦同模板多次刷新的义务键身份契约。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("时间线事件推进器")
class OcTimelineEventSchedulerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("同模板两次高阶刷新的全部义务键应唯一且互不覆盖")
    void shouldKeepUniqueObligationKeysForSameTemplateRepeatedRefresh() {
        OcMemberCandidate first = chainMember(1L);
        OcMemberCandidate second = chainMember(2L);
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(first, second), Set.of(), List.of(), Map.of(),
                List.of(), List.of(chain()), NOW);
        List<OcTeamDemand> successors = chain().subList(1, chain().size());
        List<OcTimelineEventScheduler.CandidateRoot> roots = List.of(
                new OcTimelineEventScheduler.CandidateRoot(
                        rootObligation("chain:8:Root:0"), successors),
                new OcTimelineEventScheduler.CandidateRoot(
                        rootObligation("chain:8:Root:1"), successors));

        OcTimelineEventScheduler.SimulationResult result = new OcTimelineEventScheduler()
                .simulate(request, roots, Duration.ZERO, true);

        assertTrue(result.feasible(), result.toString());
        List<String> keys = result.events().stream()
                .map(OcTimelineEvent::obligationKey).toList();
        Set<String> distinctKeys = new HashSet<>(keys);
        assertEquals(Set.of("chain:8:Root:0", "chain:8:Root:0->1:9:Child",
                        "chain:8:Root:1", "chain:8:Root:1->1:9:Child"), distinctKeys,
                "两条同模板链的根和后继义务键必须各自独立: " + keys);
        List<String> anchorKeys = result.anchors().stream()
                .map(OcLiquidityAnchor::anchorKey).toList();
        assertEquals(anchorKeys.size(), new HashSet<>(anchorKeys).size(),
                "同模板多次刷新的锚点键必须唯一: " + anchorKeys);
    }

    @Test
    @DisplayName("两条同模板链应产生独立的后继生成事件")
    void shouldGenerateIndependentSuccessorEventsForSameTemplateChains() {
        OcMemberCandidate first = chainMember(1L);
        OcMemberCandidate second = chainMember(2L);
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(
                List.of(first, second), Set.of(), List.of(), Map.of(),
                List.of(), List.of(chain()), NOW);
        List<OcTeamDemand> successors = chain().subList(1, chain().size());
        List<OcTimelineEventScheduler.CandidateRoot> roots = List.of(
                new OcTimelineEventScheduler.CandidateRoot(
                        rootObligation("chain:8:Root:0"), successors),
                new OcTimelineEventScheduler.CandidateRoot(
                        rootObligation("chain:8:Root:1"), successors));

        OcTimelineEventScheduler.SimulationResult result = new OcTimelineEventScheduler()
                .simulate(request, roots, Duration.ZERO, true);

        List<String> successorKeys = result.events().stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.CHAIN_SUCCESSOR_GENERATED)
                .map(OcTimelineEvent::obligationKey).toList();
        assertEquals(2, successorKeys.size());
        assertEquals(2, successorKeys.stream().map(key -> key.split("->")[0]).distinct().count(),
                "两条同模板链的后继应挂在各自实例键下");
    }

    private List<OcTeamDemand> chain() {
        OcPlanSlot slot = new OcPlanSlot("Worker#1", "Worker", 60, 1, null);
        return List.of(
                new OcTeamDemand(0L, "Root", 8, null, null, true, List.of(slot),
                        Set.of(), Set.of()),
                new OcTeamDemand(0L, "Child", 9, null, null, true, List.of(slot),
                        Set.of(), Set.of()));
    }

    private OcTimelineObligation rootObligation(String key) {
        OcTeamDemand root = chain().getFirst();
        OcTeamDemand demand = new OcTeamDemand(0L, root.ocName(), root.rank(), null,
                NOW.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS), true,
                root.slots(), Set.of(), Set.of());
        return new OcTimelineObligation(key,
                OcTimelineObligation.ObligationKind.CONDITIONAL_RANDOM, demand,
                demand.expiresAt(), null);
    }

    private OcMemberCandidate chainMember(long id) {
        return new OcMemberCandidate(id, "user" + id, NOW, false,
                Map.of(OcMemberCandidate.capabilityKey(8, "Root", "Worker"), 90,
                        OcMemberCandidate.capabilityKey(9, "Child", "Worker"), 90), Map.of());
    }
}
