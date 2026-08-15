package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcChainTemplateResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcConfigurationStatusEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshPlanningContext;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcExistingTimelineReconstructor.ReconstructionResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将不可变规划快照转换为匿名时间线求解上下文。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Service
@RequiredArgsConstructor
public class OcRefreshSafetyRequestFactory {
    private static final String NORMAL_POOL = "NORMAL_7_8";
    private static final String READY = "READY";
    private static final String CHAIN_EVIDENCE_PREFIX = "chain:";

    private final OcChainPlanningService chainPlanningService;
    private final OcExistingTimelineReconstructor timelineReconstructor;
    private final OcRewardEvidenceCalculator rewardEvidenceCalculator;

    /**
     * 构造当前时间线事实义务、随机池模板、价值证据和配置状态。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @return 匿名时间线求解上下文
     */
    public OcRefreshPlanningContext create(OcPlanningSnapshot snapshot) {
        OcChainTemplateResult chainResult = chainPlanningService.buildReadyChainResult(snapshot);
        ReconstructionResult reconstruction = timelineReconstructor.reconstruct(snapshot,
                chainResult);

        Set<Long> plannedJoinedIds = new HashSet<>();
        snapshot.activeOcs().forEach(oc -> {
            if (isInScope(snapshot, ocKey(oc))) {
                snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                        .map(TornFactionOcSlotDO::getUserId)
                        .filter(java.util.Objects::nonNull)
                        .forEach(plannedJoinedIds::add);
            }
        });
        Map<String, Integer> emptyCounts = plannedEmptyCounts(snapshot);

        List<OcMemberCandidate> members = buildMembers(snapshot, reconstruction,
                plannedJoinedIds);
        OcRefreshSafetyRequest request = new OcRefreshSafetyRequest(members,
                reconstruction.unprovableMemberIds(), reconstruction.obligations(),
                reconstruction.chainSuccessorsByKey(), normalTemplates(snapshot),
                chainResult.chains(), snapshot.snapshotTime());
        List<String> warnings = new ArrayList<>(chainResult.warnings());
        OcConfigurationStatusEnum configurationStatus = configurationStatus(snapshot, chainResult,
                reconstruction, warnings);
        return new OcRefreshPlanningContext(request, emptyCounts, configurationStatus, warnings);
    }

    /**
     * 构造价值证据映射：普通模板按OC键，高阶链按chain前缀根键。
     *
     * @param context 刷新规划上下文
     * @param snapshot 规划快照
     * @return 按模板键索引的价值证据
     */
    public Map<String, OcValueEvidence> buildEvidenceByTemplate(OcRefreshPlanningContext context,
                                                                OcPlanningSnapshot snapshot) {
        Map<String, OcValueEvidence> result = new HashMap<>();
        for (OcTeamDemand template : context.request().normalTemplates()) {
            String key = ocKey(template.rank(), template.ocName());
            result.put(key, singleEvidence(snapshot, template));
        }
        for (List<OcTeamDemand> chain : context.request().highChains()) {
            if (chain.isEmpty()) {
                continue;
            }
            List<OcValueEvidence> nodeEvidences = chain.stream()
                    .map(template -> singleEvidence(snapshot, template)).toList();
            result.put(CHAIN_EVIDENCE_PREFIX + ocKey(chain.getFirst().rank(),
                    chain.getFirst().ocName()),
                    rewardEvidenceCalculator.aggregateChainEvidence(nodeEvidences));
        }
        return result;
    }

    /**
     * 按当前可用性重建成员时间线：不可证明占用成员整窗不可用，
     * 计划内已有人成员由义务排程释放，其余按快照可用时间。
     *
     * @param snapshot 规划快照
     * @param reconstruction 时间线重建结果
     * @param plannedJoinedIds 计划内已加入成员ID集合
     * @return 成员时间线
     */
    private List<OcMemberCandidate> buildMembers(OcPlanningSnapshot snapshot,
                                                 ReconstructionResult reconstruction,
                                                 Set<Long> plannedJoinedIds) {
        List<OcMemberCandidate> result = new ArrayList<>();
        for (OcMemberCandidate member : snapshot.members()) {
            long userId = member.userId();
            if (reconstruction.unprovableMemberIds().contains(userId)) {
                result.add(member.withAvailability(OcTimelineState.NEVER, true));
                continue;
            }
            LocalDateTime provableRelease = reconstruction.provableReleaseByMember().get(userId);
            if (provableRelease != null) {
                result.add(member.withAvailability(
                        later(member.availableAt(), provableRelease), false));
                continue;
            }
            if (plannedJoinedIds.contains(userId)) {
                result.add(member.withAvailability(OcTimelineState.NEVER, true));
                continue;
            }
            result.add(member.withAvailability(member.availableAt(), false));
        }
        return result;
    }

    /**
     * 统计计划内无人OC数量。
     *
     * @param snapshot 规划快照
     * @return 按OC规划键索引的无人OC数量
     */
    private Map<String, Integer> plannedEmptyCounts(OcPlanningSnapshot snapshot) {
        Map<String, Integer> emptyCounts = new LinkedHashMap<>();
        for (TornFactionOcDO oc : snapshot.activeOcs()) {
            String key = ocKey(oc);
            if (!isInScope(snapshot, key)) {
                continue;
            }
            boolean empty = snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                    .map(TornFactionOcSlotDO::getUserId)
                    .noneMatch(java.util.Objects::nonNull);
            if (empty) {
                emptyCounts.merge(key, 1, Integer::sum);
            }
        }
        return emptyCounts;
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
            if (!isInScope(snapshot, key) || !NORMAL_POOL.equals(profile.getSpawnPool())) {
                continue;
            }
            List<OcPlanSlot> slots = snapshot.slotTemplates().getOrDefault(key, List.of());
            if (!slots.isEmpty()) {
                result.add(new OcTeamDemand(0L, profile.getOcName(), profile.getRank(),
                        null, snapshot.snapshotTime()
                        .plusDays(OcTimelinePolicy.FIRST_JOIN_EXPIRE_DAYS),
                        false, slots, Set.of(), Set.of()));
            }
        }
        return result.stream().sorted(java.util.Comparator
                .comparingInt(OcTeamDemand::rank).thenComparing(OcTeamDemand::ocName)).toList();
    }

    /**
     * 构造单个模板的价值证据。
     *
     * @param snapshot 规划快照
     * @param template 随机结果模板
     * @return 价值证据
     */
    private OcValueEvidence singleEvidence(OcPlanningSnapshot snapshot, OcTeamDemand template) {
        String key = ocKey(template.rank(), template.ocName());
        TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
        OcPlanningRewardStatsDO stats = snapshot.rewardStats().get(key);
        int memberDays = rewardEvidenceCalculator.incrementalMemberDays(
                template.slots().size(), 0);
        LocalDateTime expectedReleaseAt = snapshot.snapshotTime()
                .plusHours(24L * template.slots().size());
        return rewardEvidenceCalculator.buildEvidence(stats,
                profile == null ? null : profile.getMinSampleSize(),
                memberDays, expectedReleaseAt);
    }

    /**
     * 判定配置状态：任何显式配置错误均返回无效并选择(0,0)。
     *
     * @param snapshot 规划快照
     * @param chainResult 链模板结果
     * @param reconstruction 时间线重建结果
     * @param warnings 输出配置警告
     * @return 配置状态
     */
    private OcConfigurationStatusEnum configurationStatus(OcPlanningSnapshot snapshot,
                                                          OcChainTemplateResult chainResult,
                                                          ReconstructionResult reconstruction,
                                                          List<String> warnings) {
        if (snapshot.policy().enabledOcKeys().isEmpty()) {
            warnings.add("帮派未配置显式OC规划范围，自动规划已禁用");
            return OcConfigurationStatusEnum.INCOMPLETE;
        }
        if (!chainResult.warnings().isEmpty() || !snapshot.warnings().isEmpty()
                || !snapshot.policy().validationWarnings().isEmpty()) {
            warnings.addAll(snapshot.warnings());
            warnings.addAll(snapshot.policy().validationWarnings());
            return OcConfigurationStatusEnum.INVALID;
        }
        if (reconstruction.chainBlocked()) {
            warnings.add("已启动链存在不可证明占用或映射歧义，已阻断自动刷新建议");
            return OcConfigurationStatusEnum.INVALID;
        }
        return OcConfigurationStatusEnum.VALID;
    }

    /**
     * 判断OC键是否属于当前自动规划范围。
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
     * 取两个时间中较晚的一个；空值按另一侧处理。
     *
     * @param left 时间一
     * @param right 时间二
     * @return 较晚时间
     */
    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    /**
     * 构造现实OC的规划键。
     *
     * @param oc 现实OC
     * @return OC规划键
     */
    private String ocKey(TornFactionOcDO oc) {
        return ocKey(oc.getRank(), oc.getName());
    }

    /**
     * 构造OC规划键。
     *
     * @param rank OC等级
     * @param ocName OC名称
     * @return OC规划键
     */
    private String ocKey(int rank, String ocName) {
        return pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot
                .ocKey(rank, ocName);
    }
}
