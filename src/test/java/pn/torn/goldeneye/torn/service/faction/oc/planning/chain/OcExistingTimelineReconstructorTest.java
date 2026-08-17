package pn.torn.goldeneye.torn.service.faction.oc.planning.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcExistingTimelineReconstructor.ReconstructionResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 既有OC时间线重建器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("既有OC时间线重建")
class OcExistingTimelineReconstructorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);
    private final OcExistingTimelineReconstructor reconstructor =
            new OcExistingTimelineReconstructor();

    @Test
    @DisplayName("满员OC应按readyTime生成义务且成员可证明释放")
    void shouldReconstructFullOcWithProvableRelease() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                        oc(1L, "Planned", NOW.plusHours(8), null)),
                Map.of(1L, List.of(slot(1L, "Worker#1", 10L))));

        ReconstructionResult result = reconstructor.reconstruct(snapshot, new OcChainTemplateResult(List.of(), List.of()));

        assertEquals(1, result.obligations().size());
        assertEquals(OcTimelineObligation.ObligationKind.EXISTING_JOINED,
                result.obligations().getFirst().kind());
        assertTrue(result.unprovableMemberIds().isEmpty());
        assertFalse(result.chainBlocked());
    }

    @Test
    @DisplayName("已有人OC缺少阶段时间时应输出不可证明占用且不生成义务")
    void shouldMarkJoinedOcWithoutReadyTimeUnprovable() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                        oc(1L, "Planned", null, null)),
                Map.of(1L, List.of(slot(1L, "Worker#1", 10L))));

        ReconstructionResult result = reconstructor.reconstruct(snapshot, new OcChainTemplateResult(List.of(), List.of()));

        assertTrue(result.unprovableMemberIds().contains(10L));
        assertEquals(0, result.obligations().size());
        assertTrue(result.reasonCodes().contains(OcPlanReasonCodeEnum.UNPROVABLE_OCCUPATION_PRESENT));
    }

    @Test
    @DisplayName("计划内无人OC应生成带首人期限的待启动义务")
    void shouldReconstructPlannedEmptyObligationWithDeadline() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                        oc(1L, "Planned", null, NOW.minusDays(2))),
                Map.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, new OcChainTemplateResult(List.of(), List.of()));

        assertEquals(1, result.obligations().size());
        OcTimelineObligation obligation = result.obligations().getFirst();
        assertEquals(OcTimelineObligation.ObligationKind.PLANNED_EMPTY, obligation.kind());
        assertEquals(NOW.minusDays(2).plusDays(7), obligation.firstJoinDeadline());
    }

    @Test
    @DisplayName("非计划无人OC不进入义务，非计划满员OC只提供可证明释放")
    void shouldIgnoreUnplannedEmptyAndTrackUnplannedFullRelease() {
        OcPlanningSnapshot snapshot = snapshot(List.of(
                oc(1L, "Unplanned", NOW.plusHours(8), null)), Map.of());
        Map<String, TornSettingOcPlanProfileDO> profiles = new HashMap<>(snapshot.profiles());
        profiles.remove("8:Unplanned");
        OcPlanningSnapshot withSlots = new OcPlanningSnapshot(snapshot.factionId(), NOW,
                snapshot.policy(), snapshot.activeOcs(),
                Map.of(1L, List.of(slot(1L, "Worker#1", 10L))), snapshot.members(),
                profiles, snapshot.chains(), snapshot.slotTemplates(),
                Set.of(), Map.of(), List.of());

        ReconstructionResult result = reconstructor.reconstruct(withSlots, new OcChainTemplateResult(List.of(), List.of()));

        assertEquals(0, result.obligations().size());
        assertEquals(NOW.plusHours(8), result.provableReleaseByMember().get(10L));
    }

    @Test
    @DisplayName("真实链实例应按根去重并给最深的实例挂接剩余后继")
    void shouldReconstructCommittedChainFromRealInstances() {
        TornFactionOcDO root = oc(1L, "Root", NOW.plusHours(8), null);
        root.setPreviousOcId(null);
        TornFactionOcDO child = oc(2L, "Child", null, NOW.minusDays(1));
        child.setPreviousOcId(1L);
        OcPlanningSnapshot snapshot = snapshot(List.of(root, child), Map.of(
                1L, List.of(slot(1L, "Worker#1", 10L))));
        OcChainTemplateResult chain = new OcChainTemplateResult(
                List.of(List.of(template("Root", 8), template("Child", 9),
                        template("GrandChild", 9))), List.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, chain);

        assertEquals(1, result.committedChains().size());
        assertEquals(1L, result.committedChains().getFirst().rootOcId());
        assertEquals(2, result.committedChains().getFirst().currentNodeSequence());
        assertEquals(List.of("GrandChild"), result.committedChains().getFirst()
                .remainingNodes().stream().map(OcTeamDemand::ocName).toList());
        assertEquals(List.of("GrandChild"), result.chainSuccessorsByKey()
                .get("oc:2").stream().map(OcTeamDemand::ocName).toList());
    }

    @Test
    @DisplayName("真实实例与链配置不一致时应判定映射歧义并阻断")
    void shouldBlockWhenRealInstanceDoesNotMatchChainConfig() {
        TornFactionOcDO root = oc(1L, "Root", NOW.plusHours(8), null);
        TornFactionOcDO wrongChild = oc(2L, "WrongChild", null, NOW.minusDays(1));
        wrongChild.setPreviousOcId(1L);
        OcPlanningSnapshot snapshot = snapshot(List.of(root, wrongChild), Map.of(
                1L, List.of(slot(1L, "Worker#1", 10L))));
        OcChainTemplateResult chain = new OcChainTemplateResult(
                List.of(List.of(template("Root", 8), template("Child", 9))), List.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, chain);

        assertTrue(result.chainBlocked());
        assertTrue(result.reasonCodes().contains(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS));
    }

    @Test
    @DisplayName("仅存在已有人根实例时也应挂接全部剩余后继模板")
    void shouldReconstructCommittedChainFromRootOnlyInstance() {
        TornFactionOcDO root = oc(1L, "Root", NOW.plusHours(8), null);
        root.setPreviousOcId(null);
        OcPlanningSnapshot snapshot = snapshot(List.of(root), Map.of(
                1L, List.of(slot(1L, "Worker#1", 10L))));
        OcChainTemplateResult chain = new OcChainTemplateResult(
                List.of(List.of(template("Root", 8), template("Child", 9),
                        template("GrandChild", 9))), List.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, chain);

        assertEquals(1, result.committedChains().size());
        assertEquals(1L, result.committedChains().getFirst().rootOcId());
        assertEquals(1, result.committedChains().getFirst().currentNodeSequence());
        assertEquals(NOW.plusHours(8), result.committedChains().getFirst().nextNodeStartAt());
        assertEquals(List.of("Child", "GrandChild"), result.committedChains().getFirst()
                .remainingNodes().stream().map(OcTeamDemand::ocName).toList());
        assertEquals(List.of("Child", "GrandChild"), result.chainSuccessorsByKey()
                .get("oc:1").stream().map(OcTeamDemand::ocName).toList());
        assertFalse(result.chainBlocked());
    }

    @Test
    @DisplayName("同一父OC存在多个活动子实例时应判定映射歧义并硬阻断")
    void shouldBlockWhenChainHasForkedActiveChildren() {
        TornFactionOcDO root = oc(1L, "Root", NOW.plusHours(8), null);
        TornFactionOcDO firstChild = oc(2L, "Child", null, NOW.minusDays(1));
        firstChild.setPreviousOcId(1L);
        TornFactionOcDO secondChild = oc(3L, "Child", null, NOW.minusDays(1));
        secondChild.setPreviousOcId(1L);
        OcPlanningSnapshot snapshot = snapshot(List.of(root, firstChild, secondChild), Map.of(
                1L, List.of(slot(1L, "Worker#1", 10L))));
        OcChainTemplateResult chain = new OcChainTemplateResult(
                List.of(List.of(template("Root", 8), template("Child", 9),
                        template("GrandChild", 9))), List.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, chain);

        assertTrue(result.chainBlocked());
        assertTrue(result.reasonCodes().contains(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS));
        assertTrue(result.riskFlags().contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
        assertTrue(result.chainSuccessorsByKey().isEmpty());
        assertTrue(result.committedChains().isEmpty());
    }

    @Test
    @DisplayName("同模板两个真实根实例应各自产生独立链义务和后继键")
    void shouldReconstructEachRealRootInstanceIndependently() {
        TornFactionOcDO firstRoot = oc(1L, "Root", NOW.plusHours(8), null);
        TornFactionOcDO secondRoot = oc(2L, "Root", NOW.plusHours(9), null);
        OcPlanningSnapshot snapshot = snapshot(List.of(firstRoot, secondRoot), Map.of(
                1L, List.of(slot(1L, "Worker#1", 10L)),
                2L, List.of(slot(2L, "Worker#1", 11L))));
        OcChainTemplateResult chain = new OcChainTemplateResult(
                List.of(List.of(template("Root", 8), template("Child", 9))), List.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot, chain);

        assertEquals(2, result.committedChains().size());
        assertEquals(Set.of(1L, 2L), result.committedChains().stream()
                .map(OcCommittedChainObligation::rootOcId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("Child"), result.chainSuccessorsByKey().get("oc:1")
                .stream().map(OcTeamDemand::ocName).toList());
        assertEquals(List.of("Child"), result.chainSuccessorsByKey().get("oc:2")
                .stream().map(OcTeamDemand::ocName).toList());
    }

    @Test
    @DisplayName("现实空链后继应保留Torn权威创建时间作为前置生成事实且不受本地审计时间影响")
    void shouldPreserveTornCreatedAtAsPredecessorCompletedAtForCommittedChainSuccessor() {
        LocalDateTime tornCreatedAt = NOW.minusHours(3);
        TornFactionOcDO child = oc(1L, "Child", null, null);
        child.setTornCreatedAt(tornCreatedAt);
        child.setCreateTime(NOW);
        child.setPreviousOcId(100L);
        OcPlanningSnapshot snapshot = snapshot(List.of(child), Map.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot,
                new OcChainTemplateResult(List.of(), List.of()));

        assertEquals(1, result.obligations().size());
        OcTimelineObligation obligation = result.obligations().getFirst();
        assertEquals(OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR,
                obligation.kind());
        assertEquals(tornCreatedAt, obligation.predecessorCompletedAt(),
                "前置事实必须取Torn权威创建时间而非本地审计createTime");
    }

    @Test
    @DisplayName("现实空链后继缺失Torn权威创建时间时前置生成事实应保持null")
    void shouldKeepPredecessorCompletedAtNullWhenTornCreatedAtMissing() {
        TornFactionOcDO child = oc(1L, "Child", null, null);
        child.setPreviousOcId(100L);
        OcPlanningSnapshot snapshot = snapshot(List.of(child), Map.of());

        ReconstructionResult result = reconstructor.reconstruct(snapshot,
                new OcChainTemplateResult(List.of(), List.of()));

        assertEquals(1, result.obligations().size());
        OcTimelineObligation obligation = result.obligations().getFirst();
        assertEquals(OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR,
                obligation.kind());
        assertNull(obligation.predecessorCompletedAt());
    }

    private OcTeamDemand template(String name, int rank) {
        return new OcTeamDemand(0L, name, rank, null, null, true,
                List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)),
                Set.of(), Set.of());
    }

    private OcPlanningSnapshot snapshot(List<TornFactionOcDO> ocs,
                                        Map<Long, List<TornFactionOcSlotDO>> slots) {
        Map<String, TornSettingOcPlanProfileDO> profiles = new HashMap<>();
        Map<String, List<OcPlanSlot>> templates = new HashMap<>();
        for (TornFactionOcDO oc : ocs) {
            String key = ocKey(oc);
            profiles.put(key, profile(oc.getName(), oc.getRank()));
            templates.put(key, List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        }
        profiles.put("9:Child", profile("Child", 9));
        profiles.put("9:GrandChild", profile("GrandChild", 9));
        templates.put("9:Child", List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        templates.put("9:GrandChild", List.of(new OcPlanSlot("Worker#1", "Worker", 60, 1, null)));
        List<OcMemberCandidate> members = List.of(new OcMemberCandidate(10L, "user10",
                NOW, false, Map.of(), Map.of()));
        Set<String> enabled = new java.util.HashSet<>(profiles.keySet());
        return new OcPlanningSnapshot(1L, NOW,
                new OcFactionPlanningPolicy(1L, OcEvaluationMode.POSITION_WEIGHT,
                        enabled, List.of()),
                ocs, slots, members, profiles, List.of(), templates, Set.of(), Map.of(),
                List.of());
    }

    private String ocKey(TornFactionOcDO oc) {
        return OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
    }

    private TornFactionOcDO oc(long id, String name, LocalDateTime readyTime,
                               LocalDateTime createTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setName(name);
        oc.setRank("Root".equals(name) || "Planned".equals(name) || "Unplanned".equals(name)
                ? 8 : 9);
        oc.setStatus("Recruiting");
        oc.setReadyTime(readyTime);
        oc.setCreateTime(createTime == null ? NOW : createTime);
        return oc;
    }

    private TornSettingOcPlanProfileDO profile(String name, int rank) {
        TornSettingOcPlanProfileDO profile = new TornSettingOcPlanProfileDO();
        profile.setOcName(name);
        profile.setRank(rank);
        profile.setSpawnPool("NORMAL_7_8");
        profile.setPlanStatus("READY");
        return profile;
    }

    private TornFactionOcSlotDO slot(long ocId, String position, Long userId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setPosition(position);
        slot.setUserId(userId);
        return slot;
    }
}
