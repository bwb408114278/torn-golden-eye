package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcChainTemplateResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshPlanningContext;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将不可变规划快照转换为匿名刷新安全边界求解上下文。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@Service
@RequiredArgsConstructor
public class OcRefreshSafetyRequestFactory {
    private static final int EMPTY_OC_EXPIRE_DAYS = 7;

    private static final String NORMAL_POOL = "NORMAL_7_8";
    private static final String READY = "READY";

    private final OcChainPlanningService chainPlanningService;

    /**
     * 构造当前计划需求、随机池模板和计划内无人OC汇总。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @return 匿名安全求解上下文
     */
    public OcRefreshPlanningContext create(OcPlanningSnapshot snapshot) {
        Set<Long> plannedJoinedIds = new HashSet<>();
        List<OcTeamDemand> baseDemands = new ArrayList<>();
        OcChainTemplateResult chainResult = chainPlanningService.buildReadyChainResult(snapshot);
        List<List<OcTeamDemand>> readyChains = chainResult.chains();
        Map<String, List<OcTeamDemand>> chainByRootKey = new LinkedHashMap<>();
        readyChains.forEach(chain -> {
            if (!chain.isEmpty()) {
                OcTeamDemand root = chain.getFirst();
                chainByRootKey.put(OcPlanningSnapshot.ocKey(root.rank(), root.ocName()), chain);
            }
        });
        List<List<OcTeamDemand>> baseChains = new ArrayList<>();
        Map<String, Integer> emptyCounts = new LinkedHashMap<>();

        for (TornFactionOcDO oc : snapshot.activeOcs()) {
            String key = OcPlanningSnapshot.ocKey(oc.getRank(), oc.getName());
            if (isOutsidePlanningScope(snapshot, key)) {
                continue;
            }
            List<TornFactionOcSlotDO> currentSlots = snapshot.slotsByOcId()
                    .getOrDefault(oc.getId(), List.of());
            Set<Long> joinedIds = new HashSet<>();
            Set<String> fixedSlotCodes = new HashSet<>();
            currentSlots.stream().filter(slot -> slot.getUserId() != null).forEach(slot -> {
                joinedIds.add(slot.getUserId());
                fixedSlotCodes.add(slot.getPosition());
            });
            plannedJoinedIds.addAll(joinedIds);
            if (joinedIds.isEmpty()) {
                emptyCounts.merge(key, 1, Integer::sum);
            }
            OcTeamDemand demand = existingDemand(snapshot, oc, key,
                    fixedSlotCodes, joinedIds);
            if (demand != null) {
                List<OcTeamDemand> chain = chainByRootKey.get(key);
                if (chain == null) {
                    baseDemands.add(demand);
                } else {
                    List<OcTeamDemand> existingChain = new ArrayList<>();
                    existingChain.add(demand);
                    existingChain.addAll(chain.subList(1, chain.size()));
                    baseChains.add(existingChain);
                }
            }
        }

        List<OcMemberCandidate> members = snapshot.members().stream()
                .map(member -> member.withAvailability(member.availableAt(),
                        plannedJoinedIds.contains(member.userId())))
                .toList();
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(members, baseDemands,
                baseChains, normalTemplates(snapshot), readyChains,
                snapshot.snapshotTime());
        return new OcRefreshPlanningContext(request, emptyCounts, chainResult.warnings());
    }

    /**
     * 将当前计划内OC转换为现有队伍需求。
     *
     * @param snapshot 规划快照
     * @param oc 当前OC
     * @param key OC规划键
     * @param fixedSlotCodes 已有成员占用岗位
     * @param joinedIds 已加入成员ID
     * @return 岗位模板有效时返回队伍需求，否则返回null
     */
    private OcTeamDemand existingDemand(OcPlanningSnapshot snapshot, TornFactionOcDO oc,
                                        String key, Set<String> fixedSlotCodes,
                                        Set<Long> joinedIds) {
        List<OcPlanSlot> slots = snapshot.slotTemplates().getOrDefault(key, List.of());
        if (slots.isEmpty()) {
            return null;
        }
        boolean joined = !joinedIds.isEmpty();
        LocalDateTime readyAt = oc.getReadyTime();
        LocalDateTime expiresAt = joined ? null : emptyOcExpiresAt(snapshot, oc);
        return new OcTeamDemand(oc.getId(), oc.getName(), oc.getRank(), readyAt,
                expiresAt, false, slots, fixedSlotCodes, joinedIds);
    }

    /**
     * 构造当前帮派计划范围内的普通池随机结果模板。
     *
     * @param snapshot 规划快照
     * @return 按等级和名称稳定排序的普通池模板
     */
    private List<OcTeamDemand> normalTemplates(OcPlanningSnapshot snapshot) {
        List<OcTeamDemand> result = new ArrayList<>();
        for (Map.Entry<String, TornSettingOcPlanProfileDO> entry : snapshot.profiles().entrySet()) {
            String key = entry.getKey();
            TornSettingOcPlanProfileDO profile = entry.getValue();
            if (isOutsidePlanningScope(snapshot, key)
                    || !NORMAL_POOL.equals(profile.getSpawnPool())) {
                continue;
            }
            List<OcPlanSlot> slots = snapshot.slotTemplates().getOrDefault(key, List.of());
            if (!slots.isEmpty()) {
                result.add(new OcTeamDemand(0L, profile.getOcName(), profile.getRank(),
                        null,
                        snapshot.snapshotTime().plusDays(EMPTY_OC_EXPIRE_DAYS),
                        false, slots, Set.of(), Set.of()));
            }
        }
        return result.stream().sorted(java.util.Comparator
                .comparingInt(OcTeamDemand::rank).thenComparing(OcTeamDemand::ocName)).toList();
    }

    /**
     * 判断OC是否不属于当前自动规划范围。
     *
     * @param snapshot 规划快照
     * @param key OC规划键
     * @return 不在规划范围时返回true
     */
    private boolean isOutsidePlanningScope(OcPlanningSnapshot snapshot, String key) {
        TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
        return profile == null || !READY.equals(profile.getPlanStatus())
                || !snapshot.policy().enabledOcKeys().contains(key)
                || snapshot.invalidOcKeys().contains(key);
    }

    /**
     * 计算无人OC首位成员最晚加入时间。
     *
     * @param snapshot 规划快照
     * @param oc 当前OC
     * @return 首位成员最晚加入时间
     */
    private LocalDateTime emptyOcExpiresAt(OcPlanningSnapshot snapshot, TornFactionOcDO oc) {
        LocalDateTime createdAt = oc.getCreateTime() == null
                ? snapshot.snapshotTime() : oc.getCreateTime();
        return createdAt.plusDays(EMPTY_OC_EXPIRE_DAYS);
    }
}
