package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量加载一次规划需要的所有数据。搜索过程中不得再访问数据库。
 */
@Component
@RequiredArgsConstructor
public class OcPlanningSnapshotLoader {
    private static final int OC_EXPIRE_DAYS = 7;

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

    /**
     * 批量加载指定帮派在同一时间点的不可变规划快照。
     *
     * @param factionId 帮派ID
     * @param snapshotTime 快照时间
     * @return 包含活跃OC、岗位、成员能力和配置校验结果的规划快照
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
        Map<Long, LocalDateTime> occupiedUntil = calculateOccupiedUntil(activeOcs, activeSlots, snapshotTime);
        Set<Long> fixedUserIds = activeSlots.stream().map(TornFactionOcSlotDO::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        List<TornFactionOcUserDO> capabilityRows = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .eq(TornFactionOcUserDO::getDeleted, 0)
                .list();
        Set<Long> memberIds = capabilityRows.stream().map(TornFactionOcUserDO::getUserId)
                .collect(Collectors.toSet());
        Map<Long, TornUserDO> users = userDao.queryUserMap(memberIds);
        Map<Long, List<TornFactionOcUserDO>> capabilitiesByUser = capabilityRows.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        List<OcMemberCandidate> members = buildMembers(capabilitiesByUser, users, fixedUserIds,
                occupiedUntil, factionId, snapshotTime);

        Map<String, TornSettingOcPlanProfileDO> profiles = planningManager.getProfiles().stream()
                .collect(Collectors.toMap(profile -> OcPlanningSnapshot.ocKey(profile.getRank(),
                        profile.getOcName()), Function.identity()));
        Map<String, List<OcPlanSlot>> slotTemplates = buildSlotTemplates(factionId);
        return new OcPlanningSnapshot(factionId, snapshotTime, policy, activeOcs, slotsByOc, members,
                profiles, planningManager.getChains(), slotTemplates,
                validation.invalidOcKeys(), warnings);
    }

    private Map<Long, LocalDateTime> calculateOccupiedUntil(List<TornFactionOcDO> activeOcs,
                                                             List<TornFactionOcSlotDO> slots,
                                                             LocalDateTime snapshotTime) {
        Map<Long, LocalDateTime> result = new HashMap<>();
        Map<Long, TornFactionOcDO> ocMap = activeOcs.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));
        for (TornFactionOcSlotDO slot : slots) {
            if (slot.getUserId() == null) {
                continue;
            }
            TornFactionOcDO oc = ocMap.get(slot.getOcId());
            LocalDateTime releaseAt = snapshotTime.plusDays(OC_EXPIRE_DAYS);
            if (oc != null && TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus())
                    && oc.getReadyTime() != null) {
                releaseAt = oc.getReadyTime();
            }
            result.merge(slot.getUserId(), releaseAt,
                    (left, right) -> left.isAfter(right) ? left : right);
        }
        return result;
    }

    private List<OcMemberCandidate> buildMembers(
            Map<Long, List<TornFactionOcUserDO>> capabilitiesByUser,
            Map<Long, TornUserDO> users, Set<Long> fixedUserIds,
            Map<Long, LocalDateTime> occupiedUntil, long factionId, LocalDateTime snapshotTime) {
        List<TornSettingOcCoefficientDO> coefficients = coefficientManager.getList();
        List<OcMemberCandidate> result = new ArrayList<>(capabilitiesByUser.size());
        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : capabilitiesByUser.entrySet()) {
            long userId = entry.getKey();
            Map<String, Integer> passRates = new HashMap<>();
            Map<String, BigDecimal> userCoefficients = new HashMap<>();
            for (TornFactionOcUserDO row : entry.getValue()) {
                String key = OcMemberCandidate.capabilityKey(row.getRank(), row.getOcName(),
                        row.getPosition());
                passRates.merge(key, row.getPassRate(), Math::max);
                Map<String, TornSettingOcCoefficientDO> selectedBySlot = new HashMap<>();
                for (TornSettingOcCoefficientDO item : coefficients) {
                    if (!(item.getFactionId().equals(factionId) || item.getFactionId().equals(0L))
                            || !item.getRank().equals(row.getRank())
                            || !item.getOcName().equals(row.getOcName())
                            || !item.getSlotCode().startsWith(row.getPosition() + "#")
                            || item.getPassRateMin() >= row.getPassRate()
                            || item.getPassRateMax() < row.getPassRate()) {
                        continue;
                    }
                    selectedBySlot.merge(item.getSlotCode(), item,
                            (left, right) -> right.getFactionId().equals(factionId) ? right : left);
                }
                selectedBySlot.values().forEach(selected -> userCoefficients.put(
                        OcMemberCandidate.capabilityKey(row.getRank(), row.getOcName(),
                                selected.getSlotCode()), selected.getCoefficient()));
            }
            TornUserDO user = users.get(userId);
            if (user == null || user.getFactionId() == null
                    || !user.getFactionId().equals(factionId)) {
                continue;
            }
            String nickname = user.getNickname();
            result.add(new OcMemberCandidate(userId, nickname,
                    occupiedUntil.getOrDefault(userId, snapshotTime), false,
                    passRates, userCoefficients));
        }
        return result;
    }

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
