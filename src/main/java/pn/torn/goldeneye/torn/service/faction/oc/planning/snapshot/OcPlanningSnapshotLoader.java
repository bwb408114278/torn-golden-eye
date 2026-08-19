package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.OcRankNameKey;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcRewardEvidenceCalculator;

/**
 * 批量加载一次规划需要的所有数据。搜索过程中不得再访问数据库。
 *
 * <p>成员释放时间不在此处猜测：占用语义由时间线重建器按可证明事实写入。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.15
 */
@Component
@RequiredArgsConstructor
public class OcPlanningSnapshotLoader {
    private static final String READY = "READY";

    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornUserDAO userDao;
    private final TornSettingOcSlotManager slotManager;
    private final TornSettingFactionOcManager factionOcManager;
    private final TornSettingOcCoefficientManager coefficientManager;
    private final TornSettingOcPlanningManager planningManager;
    private final OcFactionPlanningPolicyResolver policyResolver;
    private final OcPlanCatalogValidator catalogValidator;
    private final OcRewardEvidenceCalculator rewardEvidenceCalculator;

    /**
     * 批量加载指定帮派在同一时间点的不可变规划快照。
     *
     * @param factionId    帮派ID
     * @param snapshotTime 快照时间
     * @return 包含活跃OC、岗位、成员能力、收益统计和配置校验结果的规划快照
     */
    public OcPlanningSnapshot load(long factionId, LocalDateTime snapshotTime) {
        OcFactionPlanningPolicy policy = policyResolver.resolve(factionId);
        OcCatalogValidationResult validation = catalogValidator.validate(policy);
        List<String> warnings = new ArrayList<>(validation.warnings());
        List<TornFactionOcDO> activeOcs = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(),
                        TornOcStatusEnum.PLANNING.getCode())
                .eq(TornFactionOcDO::getDeleted, 0)
                .list();
        List<Long> ocIds = activeOcs.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> activeSlots = ocIds.isEmpty() ? List.of() : slotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .eq(TornFactionOcSlotDO::getDeleted, 0)
                .list();
        Map<Long, List<TornFactionOcSlotDO>> slotsByOc = activeSlots.stream()
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));

        List<TornFactionOcUserDO> capabilityRows = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .eq(TornFactionOcUserDO::getDeleted, 0)
                .list();
        Set<Long> memberIds = capabilityRows.stream().map(TornFactionOcUserDO::getUserId)
                .collect(Collectors.toSet());
        Map<Long, TornUserDO> users = userDao.queryUserMap(memberIds);
        Map<Long, List<TornFactionOcUserDO>> capabilitiesByUser = capabilityRows.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        List<OcMemberCandidate> members = buildMembers(capabilitiesByUser, users,
                factionId, snapshotTime);

        Map<String, TornSettingOcPlanProfileDO> profiles = planningManager.getProfiles().stream()
                .collect(Collectors.toMap(profile -> OcPlanningSnapshot.ocKey(profile.getRank(),
                        profile.getOcName()), Function.identity()));
        Map<String, List<OcPlanSlot>> slotTemplates = buildSlotTemplates(factionId);
        Map<String, OcPlanningRewardStatsDO> rewardStats = loadRewardStats(policy, profiles);
        return new OcPlanningSnapshot(factionId, snapshotTime, policy, activeOcs, slotsByOc, members,
                profiles, planningManager.getChains(), slotTemplates,
                validation.invalidOcKeys(), rewardStats, warnings);
    }

    /**
     * 按当前启用档案范围批量加载历史收益统计。
     *
     * @param policy   帮派规划策略
     * @param profiles 按OC键索引的规划档案
     * @return 按OC键索引的收益统计
     */
    private Map<String, OcPlanningRewardStatsDO> loadRewardStats(
            OcFactionPlanningPolicy policy, Map<String, TornSettingOcPlanProfileDO> profiles) {
        Map<String, OcRankNameKey> targetKeys = new LinkedHashMap<>();
        profiles.forEach((key, profile) -> {
            if (policy.enabledOcKeys().contains(key) && READY.equals(profile.getPlanStatus())) {
                targetKeys.putIfAbsent(key, new OcRankNameKey(profile.getRank(),
                        profile.getOcName()));
            }
        });
        List<TornFactionOcDO> completedOcs = ocDao.queryCompletedByOcKeys(targetKeys.values());
        return rewardEvidenceCalculator.aggregate(completedOcs);
    }

    /**
     * 构造候选成员能力视图。
     *
     * @param capabilitiesByUser 按用户ID分组的能力记录
     * @param users              用户基础信息映射
     * @param factionId          帮派ID
     * @param snapshotTime       快照时间
     * @return 候选成员列表
     */
    private List<OcMemberCandidate> buildMembers(
            Map<Long, List<TornFactionOcUserDO>> capabilitiesByUser,
            Map<Long, TornUserDO> users, long factionId, LocalDateTime snapshotTime) {
        List<TornSettingOcCoefficientDO> coefficients = coefficientManager.getList();
        List<OcMemberCandidate> result = new ArrayList<>(capabilitiesByUser.size());
        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : capabilitiesByUser.entrySet()) {
            long userId = entry.getKey();
            TornUserDO user = users.get(userId);
            if (user == null || user.getFactionId() == null
                    || !user.getFactionId().equals(factionId)) {
                continue;
            }
            result.add(buildMember(userId, user.getNickname(), entry.getValue(), coefficients,
                    factionId, snapshotTime));
        }
        return result;
    }

    /**
     * 构造单个候选成员的能力视图。
     *
     * @param userId       用户ID
     * @param nickname     用户昵称
     * @param rows         该用户的能力记录
     * @param coefficients 全部系数配置
     * @param factionId    帮派ID
     * @param snapshotTime 快照时间
     * @return 候选成员
     */
    private OcMemberCandidate buildMember(long userId, String nickname,
                                          List<TornFactionOcUserDO> rows,
                                          List<TornSettingOcCoefficientDO> coefficients,
                                          long factionId, LocalDateTime snapshotTime) {
        Map<String, Integer> passRates = new HashMap<>();
        Map<String, BigDecimal> userCoefficients = new HashMap<>();
        for (TornFactionOcUserDO row : rows) {
            String key = OcMemberCandidate.capabilityKey(row.getRank(), row.getOcName(),
                    row.getPosition());
            passRates.merge(key, row.getPassRate(), Math::max);
            selectedCoefficients(row, coefficients, factionId).forEach((slotCode, coefficient) ->
                    userCoefficients.put(OcMemberCandidate.capabilityKey(row.getRank(),
                            row.getOcName(), slotCode), coefficient));
        }
        return new OcMemberCandidate(userId, nickname, snapshotTime, false, passRates,
                userCoefficients);
    }

    /**
     * 按岗位选择适用于当前能力记录的系数：帮派级优先于全局默认。
     *
     * @param row          用户能力记录
     * @param coefficients 全部系数配置
     * @param factionId    帮派ID
     * @return 按完整岗位编码索引的系数
     */
    private Map<String, BigDecimal> selectedCoefficients(TornFactionOcUserDO row,
                                                         List<TornSettingOcCoefficientDO> coefficients,
                                                         long factionId) {
        Map<String, BigDecimal> selected = new HashMap<>();
        for (TornSettingOcCoefficientDO item : coefficients) {
            if (!coefficientMatches(item, row, factionId)) {
                continue;
            }
            selected.merge(item.getSlotCode(), item.getCoefficient(),
                    (left, right) -> item.getFactionId().equals(factionId) ? right : left);
        }
        return selected;
    }

    /**
     * 判断系数配置是否适用于当前能力记录。
     *
     * @param item      系数配置
     * @param row       用户能力记录
     * @param factionId 帮派ID
     * @return 帮派范围、OC、岗位前缀和通过率区间均匹配时返回true
     */
    private boolean coefficientMatches(TornSettingOcCoefficientDO item, TornFactionOcUserDO row,
                                       long factionId) {
        return (item.getFactionId().equals(factionId) || item.getFactionId().equals(0L))
                && item.getRank().equals(row.getRank())
                && item.getOcName().equals(row.getOcName())
                && item.getSlotCode().startsWith(row.getPosition() + "#")
                && item.getPassRateMin() < row.getPassRate()
                && item.getPassRateMax() >= row.getPassRate();
    }

    /**
     * 构造按OC键索引的岗位模板。
     *
     * @param factionId 帮派ID
     * @return 岗位模板映射
     */
    private Map<String, List<OcPlanSlot>> buildSlotTemplates(long factionId) {
        Map<String, TornSettingFactionOcSlotDO> overrides = factionOcManager.getSlotList().stream()
                .filter(item -> item.getFactionId().equals(factionId))
                .collect(Collectors.toMap(item -> key(item.getRank(), item.getOcName(),
                        item.getSlotCode()), Function.identity(), (left, right) -> left));
        Map<String, List<OcPlanSlot>> result = new HashMap<>();
        for (TornSettingOcSlotDO slot : slotManager.getList()) {
            TornSettingFactionOcSlotDO override = overrides.get(key(slot.getRank(), slot.getOcName(),
                    slot.getSlotCode()));
            int requiredPassRate = override == null ? slot.getPassRate() : override.getPassRate();
            OcPlanSlot planSlot = new OcPlanSlot(slot.getSlotCode(), basePosition(slot.getSlotCode()),
                    requiredPassRate, slot.getPriority(), slot.getBestSuccess());
            result.computeIfAbsent(OcPlanningSnapshot.ocKey(slot.getRank(), slot.getOcName()),
                    ignored -> new ArrayList<>()).add(planSlot);
        }
        return result;
    }

    private String basePosition(String slotCode) {
        int separator = slotCode.indexOf('#');
        return separator < 0 ? slotCode : slotCode.substring(0, separator);
    }

    private String key(int rank, String ocName, String slotCode) {
        return rank + ":" + ocName + ":" + slotCode;
    }
}
