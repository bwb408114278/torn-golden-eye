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

import java.text.DecimalFormat;
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

        // 1. 查询所有未完成的OC
        List<TornFactionOcDO> activeOcs = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID);

        // 2. 统计招募中和计划中的OC占用成员
        Map<String, Set<Long>> statusOccupiedUsers = calculateOccupiedUsers(activeOcs);
        Set<Long> recruitingUsers = statusOccupiedUsers.getOrDefault(
                TornOcStatusEnum.RECRUITING.getCode(), new HashSet<>());
        Set<Long> planningUsers = statusOccupiedUsers.getOrDefault(
                TornOcStatusEnum.PLANNING.getCode(), new HashSet<>());

        // 3. 分析即将停转的OC
        LocalDateTime threshold = now.plusHours(12);
        List<TornFactionOcDO> nearStopOcs = activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime() == null || oc.getReadyTime().isBefore(threshold))
                .toList();
        int nearStopVacantSlots = calculateVacantSlots(nearStopOcs);

        // 4. 统计即将完成的OC（计划中的）
        List<TornFactionOcDO> planningOcs = activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .toList();

        // 5. 查询3个OC类型的配置
        List<TornSettingOcDO> ocConfigs = settingOcManager.getList().stream()
                .filter(o -> TornConstants.REASSIGN_OC_NAME.contains(o.getOcName()))
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

        // 8. 计算建议新建队伍数量（不指定类型）
        CalculationResult calculationResult = calculateNewOcCount(
                ocTypeAnalyses, nearStopOcs.size(), planningUsers.size());

        return new NewOcSuggestionVO(calculationResult.getSuggestedCount(), analysisDetail,
                calculationResult.getSuggestion());
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

        if (slotSettings.isEmpty()) {
            log.warn("未找到OC岗位配置: ocName={}, rank={}", setting.getOcName(), setting.getRank());
            return new OcTypeAnalysis(setting, 0, 0, 0, 0);
        }

        // 2. 查询所有用户对该OC的能力数据
        List<TornFactionOcUserDO> allUserAbilities = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, TornConstants.FACTION_PN_ID)
                .eq(TornFactionOcUserDO::getOcName, setting.getOcName())
                .eq(TornFactionOcUserDO::getRank, setting.getRank())
                .list();

        if (allUserAbilities.isEmpty()) {
            log.warn("没有用户对该OC有能力数据: ocName={}, rank={}",
                    setting.getOcName(), setting.getRank());
            return new OcTypeAnalysis(setting, 0, 0, 0, 0);
        }

        // 3. 按用户分组，检查每个用户是否至少能胜任一个岗位
        Map<Long, List<TornFactionOcUserDO>> userAbilityMap = allUserAbilities.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));

        Set<Long> qualifiedUserIds = new HashSet<>();

        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : userAbilityMap.entrySet()) {
            Long userId = entry.getKey();
            List<TornFactionOcUserDO> abilities = entry.getValue();

            // 构建用户的短Code能力映射（position字段存的是短Code）
            Map<String, Integer> userShortCodePassRate = abilities.stream()
                    .collect(Collectors.toMap(
                            TornFactionOcUserDO::getPosition,
                            TornFactionOcUserDO::getPassRate,
                            Math::max));

            // 检查是否至少有一个具体岗位达标
            boolean isQualified = slotSettings.stream().anyMatch(slotSetting -> {
                String shortCode = slotSetting.getSlotShortCode();
                Integer userPassRate = userShortCodePassRate.get(shortCode);

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
        log.debug("OC类型分析: ocName={}, rank={}, 合格成员={}, 空闲={}, 即将释放={}, 可用={}",
                setting.getOcName(), setting.getRank(), qualifiedMembers,
                idleQualifiedMembers, nearReleaseMembers, availableMembers);

        return new OcTypeAnalysis(setting, qualifiedMembers, idleQualifiedMembers,
                nearReleaseMembers, availableMembers);
    }

    /**
     * 计算新建OC数量
     */
    private CalculationResult calculateNewOcCount(List<OcTypeAnalysis> ocTypeAnalyses,
                                                  int nearStopOcCount, int planningMembersCount) {
        // ========== 第1步：计算可持续人力 ==========

        // 立即可用成员（空闲成员）
        int immediateAvailable = ocTypeAnalyses.stream()
                .mapToInt(OcTypeAnalysis::getIdleQualifiedMembers)
                .min()
                .orElse(0);

        // 近期会释放的成员（计划中的成员）
        // 总可持续人力 = 立即可用 + 近期释放
        int totalSustainableMembers = immediateAvailable + planningMembersCount;

        log.info("人力分析: 立即可用={}, 预计近期释放={}, 总可持续={}",
                immediateAvailable, planningMembersCount, totalSustainableMembers);
        // ========== 第2步：维持即将停转的OC ==========

        if (nearStopOcCount > 0) {
            // 每个即将停转的OC只需1人维持（加入后延长24h）
            if (immediateAvailable < nearStopOcCount) {
                // 紧急：连短期维持都做不到
                return new CalculationResult(0,
                        String.format("""
                                        【紧急】有%d个OC即将停转，但立即可用成员仅%d人，无法全部维持！
                                        建议：
                                        1. 紧急动员更多成员
                                        2. 考虑放弃%d个低优先级OC
                                        3. 等待执行中的OC释放人员（约%d人）""",
                                nearStopOcCount, immediateAvailable,
                                nearStopOcCount - immediateAvailable, planningMembersCount));
            }

            // 减去用于维持即将停转OC的人力
            totalSustainableMembers -= nearStopOcCount;
            log.info("维持{}个即将停转的OC后，剩余可持续人力: {}", nearStopOcCount, totalSustainableMembers);
        }
        // ========== 第3步：计算可以开几个新队 ==========

        if (totalSustainableMembers <= 0) {
            return new CalculationResult(0,
                    buildNoNewOcSuggestion(nearStopOcCount, immediateAvailable, planningMembersCount));
        }
        // 激进策略：不考虑填满，只考虑每个OC至少1人维持
        // 但要避免过度分散，保留40%缓冲用于持续补充
        int safeAvailableMembers = (int) (totalSustainableMembers * 0.6);

        // 计算每种OC类型可以开几个队（每队至少需要1人，但要考虑满员人数）
        List<Integer> possibleNewOcCounts = new ArrayList<>();

        for (OcTypeAnalysis analysis : ocTypeAnalyses) {
            // 保守估计：按满员人数计算，确保有足够人力支持到满员
            int count = safeAvailableMembers / analysis.getRequiredMembers();
            possibleNewOcCounts.add(count);
            analysis.setSuggestedNewOcCount(count);

            log.debug("OC类型 {}-{}: 需要{}人/队, 可开{}队",
                    analysis.getOcName(), analysis.getRank(),
                    analysis.getRequiredMembers(), count);
        }
        // 取最小值（确保随机到任何OC类型都有足够人力）
        int suggestedCount = possibleNewOcCounts.stream()
                .min(Integer::compare)
                .orElse(0);
        // 限制最多5个（激进一点）
        suggestedCount = Math.min(suggestedCount, 5);
        // ========== 第4步：检查是否会过度分散 ==========
        DecimalFormat df = new DecimalFormat("#.0");
        if (suggestedCount > 0) {
            int totalOcCount = nearStopOcCount + suggestedCount;
            double avgMembersPerOc = (double) totalSustainableMembers / totalOcCount;

            log.info("开{}个新队后: 总OC数={}, 平均每OC人力={}",
                    suggestedCount, totalOcCount, df.format(avgMembersPerOc));

            // 如果平均每OC不到2人，风险太高，减少建议数量
            if (avgMembersPerOc < 2.0) {
                int adjustedCount = Math.max(1, (int) (totalSustainableMembers / 3.0) - nearStopOcCount);
                adjustedCount = Math.max(0, adjustedCount);

                log.warn("风险过高（平均{}人/OC），降低建议数量: {} -> {}",
                        df.format(avgMembersPerOc), suggestedCount, adjustedCount);

                suggestedCount = adjustedCount;
            }
        }
        // ========== 第5步：构建建议 ==========

        String suggestion = buildAggressiveSuggestion(
                suggestedCount, nearStopOcCount, immediateAvailable,
                planningMembersCount, totalSustainableMembers, ocTypeAnalyses);
        log.info("最终建议（激进策略）: 新建{}个队伍", suggestedCount);
        return new CalculationResult(suggestedCount, suggestion);
    }

    /**
     * 计算各状态OC占用的用户ID集合
     */
    private Map<String, Set<Long>> calculateOccupiedUsers(List<TornFactionOcDO> ocs) {
        if (CollectionUtils.isEmpty(ocs)) {
            return Map.of();
        }

        List<Long> ocIds = ocs.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> occupiedSlots = ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list();

        Map<Long, TornFactionOcDO> ocMap = ocs.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));

        Map<String, Set<Long>> statusUsers = new HashMap<>();
        for (TornFactionOcSlotDO slot : occupiedSlots) {
            TornFactionOcDO oc = ocMap.get(slot.getOcId());
            if (oc != null) {
                statusUsers.computeIfAbsent(oc.getStatus(), k -> new HashSet<>())
                        .add(slot.getUserId());
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
    private OcAnalysisDetail buildAnalysisDetail(List<OcTypeAnalysis> ocTypeAnalyses, int recruitingOcMembers,
                                                 int planningOcMembers, int nearStopOcCount, int nearStopVacantSlots,
                                                 int nearCompleteOcCount, LocalDateTime checkTime) {
        // 使用最小值作为保守估计（因为OC类型是随机的）
        int totalQualifiedMembers = ocTypeAnalyses.stream()
                .mapToInt(OcTypeAnalysis::getQualifiedMembers)
                .min()
                .orElse(0);

        int totalIdleMembers = ocTypeAnalyses.stream()
                .mapToInt(OcTypeAnalysis::getIdleQualifiedMembers)
                .min()
                .orElse(0);

        return new OcAnalysisDetail(totalQualifiedMembers, totalIdleMembers, recruitingOcMembers, planningOcMembers,
                nearStopOcCount, nearStopVacantSlots, nearCompleteOcCount, checkTime, ocTypeAnalyses);
    }

    /**
     * 构建不开新队的建议
     */
    private String buildNoNewOcSuggestion(int nearStopOcCount, int immediateAvailable, int planningMembersCount) {
        StringBuilder sb = new StringBuilder("❌ 当前不建议开新队\n\n");

        sb.append("【人力状况】\n");
        sb.append(String.format("- 立即可用：%d人%n", immediateAvailable));
        sb.append(String.format("- 执行中：%d人（预计近期释放约%d人）%n",
                planningMembersCount, (int) (planningMembersCount * 0.5)));

        if (nearStopOcCount > 0) {
            sb.append(String.format("- 即将停转：%d个OC（需至少%d人维持）%n", nearStopOcCount, nearStopOcCount));
        }

        sb.append("\n【建议】\n");
        if (nearStopOcCount > 0 && immediateAvailable < nearStopOcCount) {
            sb.append("1. 紧急：为每个即将停转的OC至少安排1人维持（只需1人即可延长24h）\n");
            sb.append("2. 等待执行中的成员释放后再考虑扩张\n");
        } else if (nearStopOcCount > 0) {
            sb.append("1. 优先维持现有OC不停转\n");
            sb.append("2. 等待更多成员释放后再考虑开新队\n");
        } else {
            sb.append("1. 当前可用人力不足以支持开设新队\n");
            sb.append("2. 建议等待执行中的OC完成，释放更多人力\n");
        }

        return sb.toString();
    }

    /**
     * 构建激进策略建议
     */
    private String buildAggressiveSuggestion(int suggestedCount, int nearStopOcCount,
                                             int immediateAvailable, int planningMembersCount,
                                             int totalSustainableMembers, List<OcTypeAnalysis> ocTypeAnalyses) {
        if (suggestedCount == 0) {
            return buildNoNewOcSuggestion(nearStopOcCount, immediateAvailable, planningMembersCount);
        }

        StringBuilder sb = new StringBuilder();
        int totalOcCount = nearStopOcCount + suggestedCount;
        double avgMembersPerOc = (double) totalSustainableMembers / totalOcCount;

        sb.append(String.format("✅ 建议新建 %d 个队伍%n", suggestedCount));

        // 人力分析
        sb.append("【人力分析】\n");
        sb.append(String.format("- 立即可用：%d人%n", immediateAvailable));
        sb.append(String.format("- 执行中：%d人（预计近期释放约%d人）%n",
                planningMembersCount, (int) (planningMembersCount * 0.5)));
        sb.append(String.format("- 总可持续人力：%d人%n", totalSustainableMembers));

        if (nearStopOcCount > 0) {
            sb.append(String.format("- 即将停转：%d个OC（需%d人维持）%n",
                    nearStopOcCount, nearStopOcCount));
        }

        // 开新队后的状况
        sb.append("\n【开新队后】\n");
        sb.append(String.format("- 总OC数：%d个%n", totalOcCount));
        sb.append(String.format("- 平均每OC人力：%.1f人%n", avgMembersPerOc));

        // 风险评估
        if (avgMembersPerOc >= 4.0) {
            sb.append("- ✅ 人力充足，可以积极扩张\n");
        } else if (avgMembersPerOc >= 3.0) {
            sb.append("- ✅ 人力较充足，适度扩张\n");
        } else if (avgMembersPerOc >= 2.0) {
            sb.append("- ⚠️  人力相对紧张，需持续关注招募\n");
        } else {
            sb.append("- ⚠️⚠️ 人力分散，强烈建议加强成员动员\n");
        }

        // 各OC类型分析
        sb.append("\n【各OC类型分析】\n");
        for (OcTypeAnalysis analysis : ocTypeAnalyses) {
            sb.append(String.format("- %s(Rank %d)：可用%d人，需%d人/队，可开%d队%n",
                    analysis.getOcName(), analysis.getRank(),
                    analysis.getAvailableMembers(),
                    analysis.getRequiredMembers(),
                    analysis.getSuggestedNewOcCount()));
        }

        return sb.toString();
    }

    /**
     * 计算结果
     */
    @Data
    @AllArgsConstructor
    private static class CalculationResult {
        private Integer suggestedCount;
        private String suggestion;
    }
}