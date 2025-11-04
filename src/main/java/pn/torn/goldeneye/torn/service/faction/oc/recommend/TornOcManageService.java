package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.NewOcSuggestionVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcAnalysisDetail;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcTypeAnalysis;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.RecommendedOcType;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OC管理服务
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcManageService {
    private final TornSettingOcManager settingOcManager;
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 分析是否需要新建OC队伍，并返回建议数量
     */
    public NewOcSuggestionVO analyzeNewOcNeeds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold24h = now.plusHours(24);

        // 1. 查询所有未完成的OC
        List<TornFactionOcDO> activeOcs = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID);

        // 2. 统计招募中和计划中的OC占用成员
        Map<String, Set<Long>> statusOccupiedUsers = calculateOccupiedUsers(activeOcs);
        Set<Long> recruitingUsers = statusOccupiedUsers.getOrDefault(TornOcStatusEnum.RECRUITING.getCode(), new HashSet<>());
        Set<Long> planningUsers = statusOccupiedUsers.getOrDefault(TornOcStatusEnum.PLANNING.getCode(), new HashSet<>());

        // 3. 分析即将停转的OC
        List<TornFactionOcDO> nearStopOcs = activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime() != null && oc.getReadyTime().isBefore(threshold24h))
                .toList();
        int nearStopVacantSlots = calculateVacantSlots(nearStopOcs);

        // 4. 统计即将完成的OC
        List<TornFactionOcDO> planningOcs = activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .toList();

        // 5. 查询3个OC类型的配置
        List<TornSettingOcDO> ocConfigs = settingOcManager.getList().stream()
                .filter(o -> TornConstants.REASSIGN_OC_NAME.contains(o.getOcName()))
                .sorted((o1, o2) -> o2.getExpectedReward().compareTo(o1.getExpectedReward()))
                .toList();

        // 6. 针对每种OC类型，分别统计有效成员
        List<OcTypeAnalysis> ocTypeAnalyses = new ArrayList<>();
        for (TornSettingOcDO config : ocConfigs) {
            OcTypeAnalysis analysis = analyzeOcType(config, recruitingUsers, planningUsers);
            ocTypeAnalyses.add(analysis);
        }

        // 7. 构建分析详情
        OcAnalysisDetail analysisDetail = buildAnalysisDetail(
                ocTypeAnalyses, recruitingUsers.size(), planningUsers.size(),
                nearStopOcs.size(), nearStopVacantSlots, planningOcs.size(), now);

        // 8. 计算建议新建队伍数量
        CalculationResult calculationResult = calculateNewOcCount(
                ocTypeAnalyses, nearStopOcs.size(), nearStopVacantSlots);

        return new NewOcSuggestionVO(calculationResult.getSuggestedCount(), calculationResult.getRecommendedTypes(),
                analysisDetail, calculationResult.getSuggestion());
    }

    /**
     * 分析单个OC类型的有效成员情况
     */
    private OcTypeAnalysis analyzeOcType(TornSettingOcDO setting, Set<Long> recruitingUsers, Set<Long> planningUsers) {
        // 1. 查询该OC的所有岗位配置
        List<TornSettingOcSlotDO> slotSettings = settingOcSlotManager.getList().stream()
                .filter(s -> s.getOcName().equals(setting.getOcName()))
                .filter(s -> s.getRank().equals(setting.getRank()))
                .toList();

        // 2. 查询所有用户对该OC的能力数据
        List<TornFactionOcUserDO> allUserAbilities = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, TornConstants.FACTION_PN_ID)
                .eq(TornFactionOcUserDO::getOcName, setting.getOcName())
                .eq(TornFactionOcUserDO::getRank, setting.getRank())
                .list();

        // 3. 按用户分组，检查每个用户是否至少能胜任一个岗位
        Map<Long, List<TornFactionOcUserDO>> userAbilityMap = allUserAbilities.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));

        Set<Long> qualifiedUserIds = new HashSet<>();
        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : userAbilityMap.entrySet()) {
            Long userId = entry.getKey();
            List<TornFactionOcUserDO> abilities = entry.getValue();

            // 构建用户的岗位能力映射
            Map<String, Integer> userPositionPassRate = abilities.stream()
                    .collect(Collectors.toMap(
                            TornFactionOcUserDO::getPosition,  // 这里存的就是短Code
                            TornFactionOcUserDO::getPassRate,
                            Math::max));

            // 检查是否至少有一个具体岗位达标
            boolean isQualified = slotSettings.stream().anyMatch(slotSetting -> {
                String shortCode = slotSetting.getSlotShortCode();
                Integer userPassRate = userPositionPassRate.get(shortCode);
                if (userPassRate == null) {
                    return false;
                }

                return userPassRate >= slotSetting.getPassRate();
            });

            if (isQualified) {
                qualifiedUserIds.add(userId);
            }
        }

        // 4. 计算各类成员数
        int qualifiedMembers = qualifiedUserIds.size();

        // 空闲的合格成员 = 合格成员 - 正在招募中的 - 正在计划中的
        Set<Long> idleQualifiedUserIds = new HashSet<>(qualifiedUserIds);
        idleQualifiedUserIds.removeAll(recruitingUsers);
        idleQualifiedUserIds.removeAll(planningUsers);
        int idleQualifiedMembers = idleQualifiedUserIds.size();

        // 即将释放的合格成员 = 计划中的合格成员
        Set<Long> nearReleaseUserIds = new HashSet<>(qualifiedUserIds);
        nearReleaseUserIds.retainAll(planningUsers);
        int nearReleaseMembers = nearReleaseUserIds.size();

        // 可用成员 = 空闲 + 即将释放
        int availableMembers = idleQualifiedMembers + nearReleaseMembers;
        return new OcTypeAnalysis(setting, qualifiedMembers, idleQualifiedMembers, idleQualifiedMembers, availableMembers);
    }

    /**
     * 计算各状态OC占用的用户ID集合
     */
    private Map<String, Set<Long>> calculateOccupiedUsers(List<TornFactionOcDO> ocs) {
        if (CollectionUtils.isEmpty(ocs)) {
            return Map.of();
        }

        List<Long> ocIds = ocs.stream().map(TornFactionOcDO::getId).toList();
        // 查询这些OC的所有已占用slot
        List<TornFactionOcSlotDO> occupiedSlots = ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list();

        // 创建OC ID到OC的映射
        Map<Long, TornFactionOcDO> ocMap = ocs.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));

        // 按状态分组用户ID
        Map<String, Set<Long>> statusUsers = new HashMap<>();
        for (TornFactionOcSlotDO slot : occupiedSlots) {
            TornFactionOcDO oc = ocMap.get(slot.getOcId());
            if (oc != null) {
                statusUsers.computeIfAbsent(oc.getStatus(), k -> new HashSet<>()).add(slot.getUserId());
            }
        }

        return statusUsers;
    }

    /**
     * 计算空闲岗位数
     */
    private Integer calculateVacantSlots(List<TornFactionOcDO> ocs) {
        if (CollectionUtils.isEmpty(ocs)) {
            return 0;
        }

        List<Long> ocIds = ocs.stream().map(TornFactionOcDO::getId).toList();
        return ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .isNull(TornFactionOcSlotDO::getUserId)
                .count().intValue();
    }

    /**
     * 构建分析详情
     */
    private OcAnalysisDetail buildAnalysisDetail(List<OcTypeAnalysis> ocTypeAnalyses,
                                                 int recruitingOcMembers, int planningOcMembers,
                                                 int nearStopOcCount, int nearStopVacantSlots,
                                                 int nearCompleteOcCount, LocalDateTime checkTime) {

        // 使用最大值作为总成员数的估算
        int totalQualifiedMembers = ocTypeAnalyses.stream()
                .mapToInt(OcTypeAnalysis::getQualifiedMembers)
                .max()
                .orElse(0);

        int totalIdleMembers = ocTypeAnalyses.stream()
                .mapToInt(OcTypeAnalysis::getIdleQualifiedMembers)
                .max()
                .orElse(0);

        return new OcAnalysisDetail(totalQualifiedMembers, totalIdleMembers, recruitingOcMembers, planningOcMembers,
                nearStopOcCount, nearStopVacantSlots, nearCompleteOcCount, checkTime, ocTypeAnalyses);
    }

    /**
     * 计算新建OC数量和推荐类型
     */
    private CalculationResult calculateNewOcCount(List<OcTypeAnalysis> ocTypeAnalyses,
                                                  int nearStopOcCount, int nearStopVacantSlots) {

        // 如果即将停转的OC已经很多了，优先填充现有队伍
        if (nearStopOcCount >= 3) {
            return new CalculationResult(0, List.of(),
                    String.format("当前有%d个OC即将停转，建议优先填充现有队伍", nearStopOcCount));
        }

        // 为每种OC类型计算可以开几个新队
        List<RecommendedOcType> recommendedTypes = new ArrayList<>();
        int totalSuggestedCount = 0;

        for (OcTypeAnalysis analysis : ocTypeAnalyses) {
            // 可用于新队的成员 = 可用成员 - (即将停转的空闲岗位 / OC类型数量)
            // 这里简化处理，假设空闲岗位均匀分布
            int allocatedVacantSlots = nearStopVacantSlots / ocTypeAnalyses.size();
            int availableForNewOc = analysis.getAvailableMembers() - allocatedVacantSlots;

            if (availableForNewOc <= 0) {
                analysis.setSuggestedNewOcCount(0);
                continue;
            }

            // 保守策略：确保至少留20%的缓冲
            int safeAvailableMembers = (int) (availableForNewOc * 0.8);

            // 计算可以开几个队
            int suggestedCount = safeAvailableMembers / analysis.getRequiredMembers();

            // 限制每种OC类型最多开2个
            suggestedCount = Math.min(suggestedCount, 2);

            analysis.setSuggestedNewOcCount(suggestedCount);

            if (suggestedCount > 0) {
                recommendedTypes.add(RecommendedOcType.builder()
                        .ocName(analysis.getOcName())
                        .rank(analysis.getRank())
                        .requiredMembers(analysis.getRequiredMembers())
                        .priority(recommendedTypes.size() + 1)
                        .reason(buildRecommendReason(analysis))
                        .suggestedCount(suggestedCount)
                        .build());

                totalSuggestedCount += suggestedCount;
            }
        }

        // 总数限制：最多一次建议开3个新队
        if (totalSuggestedCount > 3) {
            // 按优先级裁剪
            recommendedTypes.sort(Comparator.comparing(RecommendedOcType::getPriority));
            int remaining = 3;
            List<RecommendedOcType> finalTypes = new ArrayList<>();

            for (RecommendedOcType type : recommendedTypes) {
                if (remaining <= 0) break;

                int actualCount = Math.min(type.getSuggestedCount(), remaining);
                type.setSuggestedCount(actualCount);
                finalTypes.add(type);
                remaining -= actualCount;
            }

            recommendedTypes = finalTypes;
            totalSuggestedCount = 3;
        }

        String suggestion;
        if (totalSuggestedCount == 0) {
            suggestion = "当前有效成员不足或已充分分配，暂无需新建队伍";
        } else {
            suggestion = String.format("建议新建%d个队伍：%s",
                    totalSuggestedCount,
                    recommendedTypes.stream()
                            .map(t -> String.format("%s×%d", t.getOcName(), t.getSuggestedCount()))
                            .collect(Collectors.joining("、"))
            );
        }

        return new CalculationResult(totalSuggestedCount, recommendedTypes, suggestion);
    }

    /**
     * 构建推荐理由
     */
    private String buildRecommendReason(OcTypeAnalysis analysis) {
        List<String> reasons = new ArrayList<>();

        // 基于可用成员数
        if (analysis.getAvailableMembers() >= analysis.getRequiredMembers() * 2) {
            reasons.add(String.format("有%d名合格成员可用", analysis.getAvailableMembers()));
        }

        // 基于收益
        if (analysis.getExpectedReward() > 18000000L) {
            reasons.add("高收益");
        }

        // 基于级别
        if (analysis.getRank() >= 8) {
            reasons.add("高级OC");
        }

        return reasons.isEmpty() ? "推荐" : String.join("、", reasons);
    }

    /**
     * 计算结果
     */
    @Data
    @AllArgsConstructor
    private static class CalculationResult {
        private Integer suggestedCount;
        private List<RecommendedOcType> recommendedTypes;
        private String suggestion;
    }
}