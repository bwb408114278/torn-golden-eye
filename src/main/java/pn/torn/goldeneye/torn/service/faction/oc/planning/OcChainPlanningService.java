package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从配置的有向链构造完整节点序列，并证明当前安全并行容量。
 */
public class OcChainPlanningService {
    private static final int MAX_CAPACITY_SEARCH = 20;
    private final OcSafeConcurrentChainCapacitySolver capacitySolver =
            new OcSafeConcurrentChainCapacitySolver();

    /**
     * 优先保留已承诺后继责任并证明可新增高阶链的安全容量。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @param rescue 旧队补位结果及成员时间线
     * @return 高阶链容量证明、后继预留和更新后的成员时间线
     */
    public ChainPlanningResult calculate(OcPlanningSnapshot snapshot,
                                         ExistingTeamRescueResult rescue) {
        List<OcMemberCandidate> members = rescue.memberTimeline();
        List<List<OcTeamDemand>> readyChains = buildReadyChains(snapshot);
        if (readyChains.isEmpty()) {
            return new ChainPlanningResult(new OcSafeChainCapacityResult(0, 0, 0, true), true,
                    null, List.of(), members, List.of(), List.of());
        }
        CommittedObligationBuildResult committed = buildCommittedObligations(snapshot,
                readyChains, rescue);
        if (!committed.feasible()) {
            String warning = "已承诺高阶根队状态不完整或链配置不唯一，无法证明后继责任可完成";
            return new ChainPlanningResult(new OcSafeChainCapacityResult(
                    committed.committedRootCount(), 0, 0, false),
                    false, null, readyChains.stream()
                    .map(chain -> chain.stream().map(OcTeamDemand::ocName)
                            .reduce((left, right) -> left + " → " + right).orElse(""))
                    .toList(), members, List.of(), List.of(warning));
        }
        List<CommittedChainObligation> obligations = committed.obligations();
        List<String> warnings = new ArrayList<>();
        List<String> chainNames = readyChains.stream()
                .map(chain -> chain.stream().map(OcTeamDemand::ocName)
                        .reduce((left, right) -> left + " → " + right).orElse(""))
                .toList();
        OcChainCapacityPlanningResult best = null;
        String provenRootKey = null;
        for (List<OcTeamDemand> chain : readyChains) {
            int upperBound = Math.min(MAX_CAPACITY_SEARCH,
                    obligations.size() + Math.max(0, members.size() / Math.max(1,
                            chain.getFirst().slots().size())));
            OcChainCapacityPlanningResult candidate = capacitySolver.calculate(chain, members,
                    obligations, upperBound, snapshot.snapshotTime());
            boolean betterCapacity = best == null
                    || candidate.capacity().provenAdditionalCount()
                    > best.capacity().provenAdditionalCount();
            boolean betterProof = best != null
                    && candidate.capacity().provenAdditionalCount()
                    == best.capacity().provenAdditionalCount()
                    && candidate.capacity().maximumProven()
                    && !best.capacity().maximumProven();
            if (betterCapacity || betterProof) {
                best = candidate;
                OcTeamDemand root = chain.getFirst();
                provenRootKey = OcPlanningSnapshot.ocKey(root.rank(), root.ocName());
            }
        }
        if (best == null) {
            return new ChainPlanningResult(new OcSafeChainCapacityResult(0, 0, 0, true), true,
                    null, chainNames, members, List.of(), warnings);
        }
        if (!best.committedObligationsFeasible()) {
            warnings.add("当前人员时间线无法证明已承诺高阶链的全部后继可完成，请停止新增并人工检查");
        } else if (!best.capacity().maximumProven()) {
            warnings.add("高阶链计算达到搜索上限，仅展示已证明安全下界");
        }
        return new ChainPlanningResult(best.capacity(), best.committedObligationsFeasible(),
                provenRootKey, chainNames, best.memberTimeline(), best.reservedAssignments(), warnings);
    }

    /**
     * 根据快照中的有效配置构造可参与自动规划的完整高阶链。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @return 通过状态、范围和完整性校验的高阶链节点列表
     */
    public List<List<OcTeamDemand>> buildReadyChains(OcPlanningSnapshot snapshot) {
        Map<String, List<TornSettingOcChainDO>> byCode = new LinkedHashMap<>();
        snapshot.chains().forEach(edge -> byCode.computeIfAbsent(edge.getChainCode(),
                ignored -> new ArrayList<>()).add(edge));
        List<List<OcTeamDemand>> result = new ArrayList<>();
        for (List<TornSettingOcChainDO> edges : byCode.values()) {
            edges.sort(java.util.Comparator.comparingInt(TornSettingOcChainDO::getSequenceNo));
            List<String> keys = new ArrayList<>();
            TornSettingOcChainDO first = edges.getFirst();
            keys.add(OcPlanningSnapshot.ocKey(first.getParentRank(), first.getParentOcName()));
            edges.forEach(edge -> keys.add(OcPlanningSnapshot.ocKey(edge.getChildRank(),
                    edge.getChildOcName())));
            boolean ready = keys.stream().allMatch(key -> {
                TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
                return profile != null && "READY".equals(profile.getPlanStatus())
                        && !snapshot.slotTemplates().getOrDefault(key, List.of()).isEmpty();
            });
            if (!ready || keys.stream().anyMatch(snapshot.invalidOcKeys()::contains)
                    || !snapshot.policy().enabledOcKeys().contains(keys.getFirst())) {
                continue;
            }
            List<OcTeamDemand> chain = new ArrayList<>();
            for (String key : keys) {
                TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
                List<OcPlanSlot> slots = snapshot.slotTemplates().get(key);
                chain.add(new OcTeamDemand(0L, profile.getOcName(), profile.getRank(),
                        snapshot.snapshotTime(), snapshot.snapshotTime().plusDays(7), true,
                        slots, Set.of(), Set.of()));
            }
            result.add(chain);
        }
        return result;
    }

    private CommittedObligationBuildResult buildCommittedObligations(
            OcPlanningSnapshot snapshot, List<List<OcTeamDemand>> readyChains,
            ExistingTeamRescueResult rescue) {
        Map<Long, OcMemberCandidate> memberById = rescue.memberTimeline().stream()
                .collect(java.util.stream.Collectors.toMap(OcMemberCandidate::userId,
                        member -> member));
        Map<Long, pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan> rescueByOcId =
                rescue.plans().stream().collect(java.util.stream.Collectors.toMap(
                        pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan::ocId,
                        plan -> plan));
        List<CommittedChainObligation> obligations = new ArrayList<>();
        Map<Long, String> chainKeyByRootOcId = new java.util.HashMap<>();
        boolean feasible = true;
        int committedRootCount = 0;
        for (List<OcTeamDemand> chain : readyChains) {
            OcTeamDemand root = chain.getFirst();
            for (TornFactionOcDO oc : snapshot.activeOcs()) {
                if (oc.getRank() != root.rank() || !oc.getName().equals(root.ocName())) {
                    continue;
                }
                List<Long> participantIds = snapshot.slotsByOcId()
                        .getOrDefault(oc.getId(), List.of()).stream()
                        .map(slot -> slot.getUserId())
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (participantIds.isEmpty()) {
                    continue;
                }
                String chainKey = chain.stream()
                        .map(node -> OcPlanningSnapshot.ocKey(node.rank(), node.ocName()))
                        .collect(java.util.stream.Collectors.joining("->"));
                String previousChainKey = chainKeyByRootOcId.putIfAbsent(oc.getId(), chainKey);
                if (previousChainKey != null) {
                    if (!previousChainKey.equals(chainKey)) {
                        feasible = false;
                    }
                    continue;
                }
                committedRootCount++;
                int occupiedSlots = participantIds.size();
                boolean hasVacancy = occupiedSlots < root.slots().size();
                pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan rescuePlan =
                        rescueByOcId.get(oc.getId());
                if (hasVacancy && (rescuePlan == null || !rescuePlan.complete()
                        || rescuePlan.completionAt() == null)) {
                    feasible = false;
                    continue;
                }
                LocalDateTime successorAvailableAt = hasVacancy
                        ? rescuePlan.completionAt()
                        : participantIds.stream().map(memberById::get)
                        .filter(java.util.Objects::nonNull)
                        .map(OcMemberCandidate::availableAt)
                        .max(LocalDateTime::compareTo)
                        .orElse(snapshot.snapshotTime());
                obligations.add(new CommittedChainObligation(oc.getId(), chain, 1,
                        successorAvailableAt));
            }
        }
        return new CommittedObligationBuildResult(obligations, feasible, committedRootCount);
    }


    private record CommittedObligationBuildResult(
            List<CommittedChainObligation> obligations, boolean feasible,
            int committedRootCount) {
        private CommittedObligationBuildResult {
            obligations = List.copyOf(obligations);
        }
    }
}
