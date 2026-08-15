package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcChainTemplateResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcCommittedChainObligation;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRiskFlagEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineObligation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 既有OC时间线重建器。将快照中的活动OC、slot事实和链实例转成时间线义务、
 * 成员真实占用状态和已证明事件，不猜测无法证明的释放时间。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcExistingTimelineReconstructor {
    private static final String READY = "READY";

    /**
     * 重建结果。
     *
     * @param obligations 快照事实义务
     * @param unprovableMemberIds 占用无法证明释放的成员ID集合
     * @param provableReleaseByMember 非计划满员OC的可证明成员释放时间
     * @param chainSuccessorsByKey 按义务键索引的已启动链剩余后继模板
     * @param committedChains 真实链根实例去重后的剩余链义务
     * @param chainBlocked 已启动链是否因不可证明占用或映射歧义被阻断
     * @param reasonCodes 匿名原因码集合
     * @param riskFlags 业务风险标记集合
     */
    public record ReconstructionResult(List<OcTimelineObligation> obligations,
                                       Set<Long> unprovableMemberIds,
                                       Map<Long, LocalDateTime> provableReleaseByMember,
                                       Map<String, List<OcTeamDemand>> chainSuccessorsByKey,
                                       List<OcCommittedChainObligation> committedChains,
                                       boolean chainBlocked,
                                       Set<OcPlanReasonCodeEnum> reasonCodes,
                                       Set<OcRiskFlagEnum> riskFlags) {
        public ReconstructionResult {
            obligations = List.copyOf(obligations);
            unprovableMemberIds = Set.copyOf(unprovableMemberIds);
            provableReleaseByMember = Map.copyOf(provableReleaseByMember);
            chainSuccessorsByKey = Map.copyOf(chainSuccessorsByKey);
            committedChains = List.copyOf(committedChains);
            reasonCodes = Set.copyOf(reasonCodes);
            riskFlags = Set.copyOf(riskFlags);
        }
    }

    /**
     * 重建当前快照的时间线义务和成员占用事实。
     *
     * @param snapshot 规划快照
     * @param chainResult 已通过配置校验的完整高阶链模板
     * @return 重建结果
     */
    public ReconstructionResult reconstruct(OcPlanningSnapshot snapshot,
                                            OcChainTemplateResult chainResult) {
        Map<Long, TornFactionOcDO> ocById = snapshot.activeOcs().stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));
        Map<String, List<OcTeamDemand>> chainByRootKey = new LinkedHashMap<>();
        chainResult.chains().forEach(chain -> {
            if (!chain.isEmpty()) {
                OcTeamDemand root = chain.getFirst();
                chainByRootKey.put(OcPlanningSnapshot.ocKey(root.rank(), root.ocName()), chain);
            }
        });
        Map<String, String> chainCodeByRootKey = chainCodeByRoot(snapshot);

        Set<OcPlanReasonCodeEnum> reasonCodes = new LinkedHashSet<>();
        Set<OcRiskFlagEnum> riskFlags = new LinkedHashSet<>();
        List<OcTimelineObligation> obligations = new ArrayList<>();
        Set<Long> unprovableMemberIds = new HashSet<>();
        Map<Long, LocalDateTime> provableReleaseByMember = new HashMap<>();

        for (TornFactionOcDO oc : snapshot.activeOcs()) {
            reconstructOc(snapshot, oc, ocById, chainByRootKey, obligations,
                    unprovableMemberIds, provableReleaseByMember, reasonCodes, riskFlags);
        }
        ChainInstanceResult chains = reconstructChains(snapshot, ocById, chainByRootKey,
                chainCodeByRootKey, unprovableMemberIds, reasonCodes, riskFlags);
        return new ReconstructionResult(obligations, unprovableMemberIds,
                provableReleaseByMember, chains.successorsByKey(), chains.committedChains(),
                chains.blocked(), reasonCodes, riskFlags);
    }

    /**
     * 重建单个现实OC的义务或占用事实。
     *
     * @param snapshot 规划快照
     * @param oc 现实OC
     * @param ocById 按ID索引的OC映射
     * @param chainByRootKey 按根键索引的链模板
     * @param obligations 输出义务集合
     * @param unprovableMemberIds 输出不可证明占用成员集合
     * @param provableReleaseByMember 输出可证明释放时间
     * @param reasonCodes 输出原因码集合
     * @param riskFlags 输出风险标记集合
     */
    private void reconstructOc(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                               Map<Long, TornFactionOcDO> ocById,
                               Map<String, List<OcTeamDemand>> chainByRootKey,
                               List<OcTimelineObligation> obligations,
                               Set<Long> unprovableMemberIds,
                               Map<Long, LocalDateTime> provableReleaseByMember,
                               Set<OcPlanReasonCodeEnum> reasonCodes,
                               Set<OcRiskFlagEnum> riskFlags) {
        String key = OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
        List<TornFactionOcSlotDO> slots = snapshot.slotsByOcId()
                .getOrDefault(oc.getId(), List.of());
        Set<Long> joinedIds = new LinkedHashSet<>();
        Set<String> fixedSlotCodes = new LinkedHashSet<>();
        slots.stream().filter(slot -> slot.getUserId() != null).forEach(slot -> {
            joinedIds.add(slot.getUserId());
            fixedSlotCodes.add(slot.getPosition());
        });
        boolean inScope = isInScope(snapshot, key);
        boolean chainNode = isChainNode(key, chainByRootKey) || oc.getPreviousOcId() != null;
        boolean full = !joinedIds.isEmpty()
                && fixedSlotCodes.size() >= totalSlotCount(snapshot, key);

        if (joinedIds.isEmpty()) {
            if (inScope) {
                LocalDateTime deadline = firstJoinDeadline(snapshot, oc);
                OcTimelineObligation.ObligationKind kind = oc.getPreviousOcId() != null
                        ? OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR
                        : OcTimelineObligation.ObligationKind.PLANNED_EMPTY;
                obligations.add(new OcTimelineObligation(ocKey(oc), kind,
                        demand(snapshot, oc, null, deadline, fixedSlotCodes, joinedIds),
                        deadline, null));
            }
            return;
        }
        if (oc.getReadyTime() == null) {
            unprovableMemberIds.addAll(joinedIds);
            reasonCodes.add(OcPlanReasonCodeEnum.UNPROVABLE_OCCUPATION_PRESENT);
            if (chainNode) {
                riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
                reasonCodes.add(OcPlanReasonCodeEnum.COMMITTED_CHAIN_BLOCKED);
            }
            return;
        }
        if (!inScope) {
            if (full) {
                joinedIds.forEach(userId -> provableReleaseByMember.put(userId, oc.getReadyTime()));
            } else {
                unprovableMemberIds.addAll(joinedIds);
                reasonCodes.add(OcPlanReasonCodeEnum.UNPROVABLE_OCCUPATION_PRESENT);
            }
            return;
        }
        obligations.add(new OcTimelineObligation(ocKey(oc),
                OcTimelineObligation.ObligationKind.EXISTING_JOINED,
                demand(snapshot, oc, oc.getReadyTime(), null, fixedSlotCodes, joinedIds),
                null, null));
        if (full) {
            joinedIds.forEach(userId -> provableReleaseByMember.put(userId, oc.getReadyTime()));
        }
    }

    /**
     * 重建真实链实例：按previousOcId识别运行中的根和后继，每个真实根仅一条剩余链义务。
     *
     * @param snapshot 规划快照
     * @param ocById 按ID索引的OC映射
     * @param chainByRootKey 按根键索引的链模板
     * @param chainCodeByRootKey 按根键索引的链编码
     * @param unprovableMemberIds 不可证明占用成员集合
     * @param reasonCodes 输出原因码集合
     * @param riskFlags 输出风险标记集合
     * @return 真实链实例重建结果
     */
    private ChainInstanceResult reconstructChains(OcPlanningSnapshot snapshot,
                                                  Map<Long, TornFactionOcDO> ocById,
                                                  Map<String, List<OcTeamDemand>> chainByRootKey,
                                                  Map<String, String> chainCodeByRootKey,
                                                  Set<Long> unprovableMemberIds,
                                                  Set<OcPlanReasonCodeEnum> reasonCodes,
                                                  Set<OcRiskFlagEnum> riskFlags) {
        Map<Long, List<TornFactionOcDO>> childrenByParent = snapshot.activeOcs().stream()
                .filter(oc -> oc.getPreviousOcId() != null)
                .collect(Collectors.groupingBy(TornFactionOcDO::getPreviousOcId));
        Map<String, List<OcTeamDemand>> successorsByKey = new LinkedHashMap<>();
        List<OcCommittedChainObligation> committedChains = new ArrayList<>();
        boolean blocked = false;
        Set<Long> processedRoots = new HashSet<>();
        for (TornFactionOcDO root : snapshot.activeOcs()) {
            if (root.getPreviousOcId() != null || !childrenByParent.containsKey(root.getId())) {
                continue;
            }
            String rootKey = OcPlanningSnapshot.ocKey(root.getRank(), root.getName());
            List<OcTeamDemand> chain = chainByRootKey.get(rootKey);
            if (chain == null || !processedRoots.add(root.getId())) {
                continue;
            }
            List<TornFactionOcDO> instances = chainInstances(root, childrenByParent);
            int matchedDepth = matchChainInstances(instances, chain);
            if (matchedDepth < 0) {
                reasonCodes.add(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS);
                riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
                blocked = true;
                continue;
            }
            TornFactionOcDO deepest = instances.get(matchedDepth);
            List<OcTeamDemand> remaining = chain.subList(matchedDepth + 1, chain.size());
            if (deepest.getReadyTime() == null && hasJoinedMember(snapshot, deepest)) {
                blocked = true;
            }
            if (!remaining.isEmpty() && isInScope(snapshot, deepestKey(deepest))) {
                successorsByKey.put(ocKey(deepest), remaining);
            }
            committedChains.add(new OcCommittedChainObligation(root.getId(),
                    chainCodeByRootKey.getOrDefault(rootKey, rootKey),
                    matchedDepth + 1, remaining, deepest.getReadyTime()));
        }
        return new ChainInstanceResult(successorsByKey, committedChains, blocked);
    }

    /**
     * 沿previousOcId收集一条真实链的全部实例，从根开始按序排列。
     *
     * @param root 链根实例
     * @param childrenByParent 按父实例ID索引的后继实例
     * @return 从根开始的实例序列；出现分叉时只保留第一条稳定路径
     */
    private List<TornFactionOcDO> chainInstances(TornFactionOcDO root,
                                                 Map<Long, List<TornFactionOcDO>> childrenByParent) {
        List<TornFactionOcDO> instances = new ArrayList<>();
        instances.add(root);
        TornFactionOcDO current = root;
        while (true) {
            List<TornFactionOcDO> children = childrenByParent.getOrDefault(current.getId(),
                    List.of());
            if (children.isEmpty()) {
                break;
            }
            children.sort(java.util.Comparator.comparing(TornFactionOcDO::getId));
            current = children.getFirst();
            instances.add(current);
        }
        return instances;
    }

    /**
     * 校验真实实例序列与链配置的唯一映射，返回已匹配到的最深配置节点下标。
     *
     * @param instances 真实实例序列
     * @param chain 链配置节点模板
     * @return 最深匹配下标；实例与配置不一致时返回-1
     */
    private int matchChainInstances(List<TornFactionOcDO> instances,
                                    List<OcTeamDemand> chain) {
        for (int index = 0; index < instances.size(); index++) {
            if (index >= chain.size()) {
                return -1;
            }
            TornFactionOcDO instance = instances.get(index);
            OcTeamDemand node = chain.get(index);
            if (instance.getRank() != node.rank()
                    || !instance.getName().equals(node.ocName())) {
                return -1;
            }
        }
        return instances.size() - 1;
    }

    /**
     * 判断OC是否属于当前帮派自动规划范围。
     *
     * @param snapshot 规划快照
     * @param key OC规划键
     * @return 档案READY、已启用且通过校验时返回true
     */
    private boolean isInScope(OcPlanningSnapshot snapshot, String key) {
        TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
        return profile != null && READY.equals(profile.getPlanStatus())
                && snapshot.policy().enabledOcKeys().contains(key)
                && !snapshot.invalidOcKeys().contains(key)
                && !snapshot.slotTemplates().getOrDefault(key, List.of()).isEmpty();
    }

    /**
     * 判断OC键是否为任一链配置的根节点。
     *
     * @param key OC规划键
     * @param chainByRootKey 按根键索引的链模板
     * @return 是链根时返回true
     */
    private boolean isChainNode(String key, Map<String, List<OcTeamDemand>> chainByRootKey) {
        return chainByRootKey.containsKey(key);
    }

    /**
     * 获取OC完整岗位数量。
     *
     * @param snapshot 规划快照
     * @param key OC规划键
     * @return 岗位模板数量
     */
    private int totalSlotCount(OcPlanningSnapshot snapshot, String key) {
        return snapshot.slotTemplates().getOrDefault(key, List.of()).size();
    }

    /**
     * 计算无人OC首位成员最晚加入期限：创建时间后7天。
     *
     * @param snapshot 规划快照
     * @param oc 当前OC
     * @return 首位成员最晚加入时间
     */
    private LocalDateTime firstJoinDeadline(OcPlanningSnapshot snapshot, TornFactionOcDO oc) {
        LocalDateTime createdAt = oc.getCreateTime() == null
                ? snapshot.snapshotTime() : oc.getCreateTime();
        return createdAt.plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS);
    }

    /**
     * 构造现有OC的队伍需求。
     *
     * @param snapshot 规划快照
     * @param oc 现实OC
     * @param readyAt 当前阶段时间；无人加入时为null
     * @param expiresAt 首人最晚加入期限；已有人时为null
     * @param fixedSlotCodes 已有成员占用岗位
     * @param joinedIds 已加入成员ID
     * @return 队伍需求
     */
    private OcTeamDemand demand(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                                LocalDateTime readyAt, LocalDateTime expiresAt,
                                Set<String> fixedSlotCodes, Set<Long> joinedIds) {
        List<OcPlanSlot> slots = snapshot.slotTemplates()
                .getOrDefault(OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName()), List.of());
        boolean chain = oc.getPreviousOcId() != null;
        return new OcTeamDemand(oc.getId(), oc.getName(), oc.getRank(), readyAt, expiresAt,
                chain, slots, fixedSlotCodes, joinedIds);
    }

    /**
     * 判断现实OC是否已有成员加入。
     *
     * @param snapshot 规划快照
     * @param oc 现实OC
     * @return 至少一名成员已加入时返回true
     */
    private boolean hasJoinedMember(OcPlanningSnapshot snapshot, TornFactionOcDO oc) {
        return snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                .map(TornFactionOcSlotDO::getUserId)
                .anyMatch(java.util.Objects::nonNull);
    }

    /**
     * 构造按链根键索引的链编码映射。
     *
     * @param snapshot 规划快照
     * @return 根键到链编码的映射
     */
    private Map<String, String> chainCodeByRoot(OcPlanningSnapshot snapshot) {
        Map<String, List<TornSettingOcChainDO>> byCode = new LinkedHashMap<>();
        snapshot.chains().forEach(edge -> byCode.computeIfAbsent(edge.getChainCode(),
                ignored -> new ArrayList<>()).add(edge));
        Map<String, String> result = new HashMap<>();
        byCode.values().stream().filter(edges -> !edges.isEmpty())
                .forEach(edges -> edges.sort(java.util.Comparator.comparingInt(
                        TornSettingOcChainDO::getSequenceNo)));
        byCode.forEach((code, edges) -> {
            if (!edges.isEmpty()) {
                TornSettingOcChainDO first = edges.getFirst();
                result.put(OcPlanningSnapshot.ocKey(first.getParentRank(),
                        first.getParentOcName()), code);
            }
        });
        return result;
    }

    /**
     * 构造现实OC的义务键。
     *
     * @param oc 现实OC
     * @return 以OC实例ID构造的义务键
     */
    private String ocKey(TornFactionOcDO oc) {
        return "oc:" + oc.getId();
    }

    /**
     * 获取最深实例的规划键。
     *
     * @param deepest 最深真实实例
     * @return OC规划键
     */
    private String deepestKey(TornFactionOcDO deepest) {
        return OcPlanningSnapshot.ocKey(deepest.getRank(), deepest.getName());
    }

    /**
     * 真实链实例重建结果。
     *
     * @param successorsByKey 按义务键索引的剩余后继模板
     * @param committedChains 每个真实根一条的剩余链义务
     * @param blocked 是否因不可证明占用或映射歧义被阻断
     */
    private record ChainInstanceResult(Map<String, List<OcTeamDemand>> successorsByKey,
                                       List<OcCommittedChainObligation> committedChains,
                                       boolean blocked) {
    }
}
