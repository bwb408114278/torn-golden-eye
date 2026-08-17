package pn.torn.goldeneye.torn.service.faction.oc.planning.chain;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePolicy;

import java.time.LocalDateTime;
import java.util.*;
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
     * @param obligations             快照事实义务
     * @param unprovableMemberIds     占用无法证明释放的成员ID集合
     * @param provableReleaseByMember 非计划满员OC的可证明成员释放时间
     * @param chainSuccessorsByKey    按义务键索引的已启动链剩余后继模板
     * @param committedChains         真实链根实例去重后的剩余链义务
     * @param chainBlocked            已启动链是否因不可证明占用或映射歧义被阻断
     * @param reasonCodes             匿名原因码集合
     * @param riskFlags               业务风险标记集合
     */
    public record ReconstructionResult(
            List<OcTimelineObligation> obligations,
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
     * @param snapshot    规划快照
     * @param chainResult 已通过配置校验的完整高阶链模板
     * @return 重建结果
     */
    public ReconstructionResult reconstruct(OcPlanningSnapshot snapshot,
                                            OcChainTemplateResult chainResult) {
        Map<String, List<OcTeamDemand>> chainByRootKey = new LinkedHashMap<>();
        chainResult.chains().forEach(chain -> {
            if (!chain.isEmpty()) {
                OcTeamDemand root = chain.getFirst();
                chainByRootKey.put(OcPlanningSnapshot.ocKey(root.rank(), root.ocName()), chain);
            }
        });
        TimelineAccumulator accumulator = new TimelineAccumulator();
        for (TornFactionOcDO oc : snapshot.activeOcs()) {
            reconstructOc(snapshot, oc, chainByRootKey, accumulator);
        }
        reconstructChains(snapshot, chainByRootKey, accumulator);
        return new ReconstructionResult(accumulator.obligations, accumulator.unprovableMemberIds,
                accumulator.provableReleaseByMember, accumulator.successorsByKey,
                accumulator.committedChains, accumulator.chainBlocked,
                accumulator.reasonCodes, accumulator.riskFlags);
    }

    /**
     * 重建单个现实OC的义务或占用事实。
     *
     * @param snapshot       规划快照
     * @param oc             现实OC
     * @param chainByRootKey 按根键索引的链模板
     * @param accumulator    重建累积状态
     */
    private void reconstructOc(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                               Map<String, List<OcTeamDemand>> chainByRootKey,
                               TimelineAccumulator accumulator) {
        String key = OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
        List<TornFactionOcSlotDO> slots = snapshot.slotsByOcId()
                .getOrDefault(oc.getId(), List.of());
        Set<Long> joinedIds = new LinkedHashSet<>();
        Set<String> fixedSlotCodes = new LinkedHashSet<>();
        slots.stream().filter(slot -> slot.getUserId() != null).forEach(slot -> {
            joinedIds.add(slot.getUserId());
            fixedSlotCodes.add(slot.getPosition());
        });
        if (joinedIds.isEmpty()) {
            appendEmptyObligation(snapshot, oc, isInScope(snapshot, key), accumulator);
            return;
        }
        boolean chainNode = isChainNode(key, chainByRootKey) || oc.getPreviousOcId() != null;
        if (oc.getReadyTime() == null) {
            appendUnprovableOccupation(joinedIds, chainNode, accumulator);
            return;
        }
        appendJoinedFact(snapshot, oc, key, joinedIds, fixedSlotCodes, accumulator);
    }

    /**
     * 重建计划内无人OC的待启动义务。
     *
     * @param snapshot    规划快照
     * @param oc          现实OC
     * @param inScope     是否属于当前自动规划范围
     * @param accumulator 重建累积状态
     */
    private void appendEmptyObligation(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                                       boolean inScope, TimelineAccumulator accumulator) {
        if (!inScope) {
            return;
        }
        LocalDateTime deadline = firstJoinDeadline(snapshot, oc);
        boolean committedChainSuccessor = oc.getPreviousOcId() != null;
        OcTimelineObligation.ObligationKind kind = committedChainSuccessor
                ? OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR
                : OcTimelineObligation.ObligationKind.PLANNED_EMPTY;
        LocalDateTime predecessorCompletedAt = committedChainSuccessor
                ? oc.getCreateTime() : null;
        accumulator.obligations.add(new OcTimelineObligation(ocKey(oc), kind,
                demand(snapshot, oc, null, deadline, Set.of(), Set.of()), deadline,
                predecessorCompletedAt));
    }

    /**
     * 记录readyTime缺失OC的不可证明占用。
     *
     * @param joinedIds   已加入成员ID集合
     * @param chainNode   是否为链节点
     * @param accumulator 重建累积状态
     */
    private void appendUnprovableOccupation(Set<Long> joinedIds, boolean chainNode,
                                            TimelineAccumulator accumulator) {
        accumulator.unprovableMemberIds.addAll(joinedIds);
        accumulator.reasonCodes.add(OcPlanReasonCodeEnum.UNPROVABLE_OCCUPATION_PRESENT);
        if (chainNode) {
            accumulator.riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            accumulator.reasonCodes.add(OcPlanReasonCodeEnum.COMMITTED_CHAIN_BLOCKED);
        }
    }

    /**
     * 重建已有人OC的义务或可证明释放事实。
     *
     * @param snapshot       规划快照
     * @param oc             现实OC
     * @param key            OC规划键
     * @param joinedIds      已加入成员ID集合
     * @param fixedSlotCodes 已有成员占用岗位
     * @param accumulator    重建累积状态
     */
    private void appendJoinedFact(OcPlanningSnapshot snapshot, TornFactionOcDO oc, String key,
                                  Set<Long> joinedIds, Set<String> fixedSlotCodes,
                                  TimelineAccumulator accumulator) {
        boolean full = isFull(snapshot, key, fixedSlotCodes);
        if (!isInScope(snapshot, key)) {
            appendOutOfScopeFact(joinedIds, full, oc.getReadyTime(), accumulator);
            return;
        }
        accumulator.obligations.add(new OcTimelineObligation(ocKey(oc),
                OcTimelineObligation.ObligationKind.EXISTING_JOINED,
                demand(snapshot, oc, oc.getReadyTime(), null, fixedSlotCodes, joinedIds),
                null, null));
        if (full) {
            joinedIds.forEach(userId -> accumulator.provableReleaseByMember
                    .put(userId, oc.getReadyTime()));
        }
    }

    /**
     * 记录范围外已有人OC的占用事实：满员可证明释放，否则不可证明。
     *
     * @param joinedIds   已加入成员ID集合
     * @param full        是否满员
     * @param readyTime   可证明的就绪时间
     * @param accumulator 重建累积状态
     */
    private void appendOutOfScopeFact(Set<Long> joinedIds, boolean full,
                                      LocalDateTime readyTime,
                                      TimelineAccumulator accumulator) {
        if (full) {
            joinedIds.forEach(userId -> accumulator.provableReleaseByMember
                    .put(userId, readyTime));
        } else {
            accumulator.unprovableMemberIds.addAll(joinedIds);
            accumulator.reasonCodes.add(OcPlanReasonCodeEnum.UNPROVABLE_OCCUPATION_PRESENT);
        }
    }

    /**
     * 重建真实链实例：按previousOcId识别运行中的根和后继，每个真实根仅一条剩余链义务。
     *
     * <p>规划范围内所有属于完整配置链的根实例均进入重建，无论Torn是否已创建后继实例：
     * 仅根实例存在时从根的真实完成时间挂接全部剩余后继模板；
     * 已有根→子→孙实例时从最深现实节点挂接尚未创建的剩余模板。</p>
     *
     * @param snapshot       规划快照
     * @param chainByRootKey 按根键索引的链模板
     * @param accumulator    重建累积状态
     */
    private void reconstructChains(OcPlanningSnapshot snapshot,
                                   Map<String, List<OcTeamDemand>> chainByRootKey,
                                   TimelineAccumulator accumulator) {
        Map<Long, List<TornFactionOcDO>> childrenByParent = snapshot.activeOcs().stream()
                .filter(oc -> oc.getPreviousOcId() != null)
                .collect(Collectors.groupingBy(TornFactionOcDO::getPreviousOcId));
        Map<String, String> chainCodeByRootKey = chainCodeByRoot(snapshot);
        Set<Long> processedRoots = new HashSet<>();
        List<TornFactionOcDO> roots = snapshot.activeOcs().stream()
                .filter(root -> root.getPreviousOcId() == null)
                .filter(root -> isInScope(snapshot, rootKey(root)))
                .filter(root -> chainByRootKey.containsKey(rootKey(root)))
                .toList();
        for (TornFactionOcDO root : roots) {
            appendChainInstance(snapshot, root, chainByRootKey, chainCodeByRootKey,
                    childrenByParent, processedRoots, accumulator);
        }
    }

    /**
     * 重建单个真实链根实例的剩余链义务和后继模板。
     *
     * @param snapshot           规划快照
     * @param root               链根实例
     * @param chainByRootKey     按根键索引的链模板
     * @param chainCodeByRootKey 按根键索引的链编码
     * @param childrenByParent   按父实例ID索引的后继实例
     * @param processedRoots     已处理根实例ID集合
     * @param accumulator        重建累积状态
     */
    private void appendChainInstance(OcPlanningSnapshot snapshot, TornFactionOcDO root,
                                     Map<String, List<OcTeamDemand>> chainByRootKey,
                                     Map<String, String> chainCodeByRootKey,
                                     Map<Long, List<TornFactionOcDO>> childrenByParent,
                                     Set<Long> processedRoots,
                                     TimelineAccumulator accumulator) {
        String rootKey = rootKey(root);
        List<OcTeamDemand> chain = chainByRootKey.get(rootKey);
        if (chain == null || !processedRoots.add(root.getId())) {
            return;
        }
        List<TornFactionOcDO> instances = chainInstances(root, childrenByParent);
        if (instances == null) {
            accumulator.reasonCodes.add(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS);
            accumulator.riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            accumulator.chainBlocked = true;
            return;
        }
        int matchedDepth = matchChainInstances(instances, chain);
        if (matchedDepth < 0) {
            accumulator.reasonCodes.add(OcPlanReasonCodeEnum.CHAIN_MAPPING_AMBIGUOUS);
            accumulator.riskFlags.add(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK);
            accumulator.chainBlocked = true;
            return;
        }
        TornFactionOcDO deepest = instances.get(matchedDepth);
        List<OcTeamDemand> remaining = chain.subList(matchedDepth + 1, chain.size());
        if (deepest.getReadyTime() == null && hasJoinedMember(snapshot, deepest)) {
            accumulator.chainBlocked = true;
        }
        if (!remaining.isEmpty() && isInScope(snapshot, deepestKey(deepest))) {
            accumulator.successorsByKey.put(ocKey(deepest), remaining);
        }
        accumulator.committedChains.add(new OcCommittedChainObligation(root.getId(),
                chainCodeByRootKey.getOrDefault(rootKey, rootKey),
                matchedDepth + 1, remaining, deepest.getReadyTime()));
    }

    /**
     * 沿previousOcId收集一条真实链的全部实例，从根开始按序排列。
     *
     * <p>仅根实例存在时返回只含根的序列：根完成后Torn才会创建后继，
     * 此时链义务从根的真实完成时间挂接。同一父OC存在多个活动子实例属于
     * 现实链实例分叉，无法与配置唯一映射，返回null由调用方硬阻断。</p>
     *
     * @param root             链根实例
     * @param childrenByParent 按父实例ID索引的后继实例
     * @return 从根开始的实例序列；出现分叉时返回null
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
            if (children.size() > 1) {
                return List.of();
            }
            current = children.getFirst();
            instances.add(current);
        }
        return instances;
    }

    /**
     * 校验真实实例序列与链配置的唯一映射，返回已匹配到的最深配置节点下标。
     *
     * @param instances 真实实例序列
     * @param chain     链配置节点模板
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
     * @param key      OC规划键
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
     * 判断已有人OC是否已满员。
     *
     * @param snapshot       规划快照
     * @param key            OC规划键
     * @param fixedSlotCodes 已有成员占用岗位
     * @return 已占岗位数达到完整岗位数时返回true
     */
    private boolean isFull(OcPlanningSnapshot snapshot, String key, Set<String> fixedSlotCodes) {
        return !fixedSlotCodes.isEmpty()
                && fixedSlotCodes.size() >= totalSlotCount(snapshot, key);
    }

    /**
     * 判断OC键是否为任一链配置的根节点。
     *
     * @param key            OC规划键
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
     * @param key      OC规划键
     * @return 岗位模板数量
     */
    private int totalSlotCount(OcPlanningSnapshot snapshot, String key) {
        return snapshot.slotTemplates().getOrDefault(key, List.of()).size();
    }

    /**
     * 计算无人OC首位成员最晚加入期限：创建时间后7天。
     *
     * @param snapshot 规划快照
     * @param oc       当前OC
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
     * @param snapshot       规划快照
     * @param oc             现实OC
     * @param readyAt        当前阶段时间；无人加入时为null
     * @param expiresAt      首人最晚加入期限；已有人时为null
     * @param fixedSlotCodes 已有成员占用岗位
     * @param joinedIds      已加入成员ID
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
     * @param oc       现实OC
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
     * 构造现实OC的规划键。
     *
     * @param oc 现实OC
     * @return OC规划键
     */
    private String rootKey(TornFactionOcDO oc) {
        return OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
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
     * 重建过程的可变累积状态。
     */
    private static final class TimelineAccumulator {
        private final List<OcTimelineObligation> obligations = new ArrayList<>();
        private final Set<Long> unprovableMemberIds = new HashSet<>();
        private final Map<Long, LocalDateTime> provableReleaseByMember = new HashMap<>();
        private final Map<String, List<OcTeamDemand>> successorsByKey = new LinkedHashMap<>();
        private final List<OcCommittedChainObligation> committedChains = new ArrayList<>();
        private final Set<OcPlanReasonCodeEnum> reasonCodes = new LinkedHashSet<>();
        private final Set<OcRiskFlagEnum> riskFlags = new LinkedHashSet<>();
        private boolean chainBlocked;
    }
}
