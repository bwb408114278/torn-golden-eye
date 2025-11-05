package pn.torn.goldeneye.torn.service.faction.oc.create;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.create.MemberTimeline;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * OC管理服务
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcManageService {
    private final TornSettingOcManager settingOcManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final OcTypeAnalyzer analyzer;

    /**
     * 分析并推荐
     */
    public Recommendation analyze() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 收集数据
        List<TornFactionOcDO> activeOcList = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID);
        Set<Long> recruitUserList = getOccupyUser(activeOcList);
        List<TornFactionOcDO> planOcList = activeOcList.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .toList();
        log.debug("活跃OC: 招募中用户={}, 计划中OC={}", recruitUserList.size(), planOcList.size());

        // 2. 构建时间线
        MemberTimeline timeline = buildTimeline(planOcList);

        // 3. 分析各OC类型
        List<TornSettingOcDO> settingList = settingOcManager.getList().stream()
                .filter(c -> TornConstants.REASSIGN_OC_NAME.contains(c.getOcName()))
                .toList();
        List<OcTypeAnalyzer.Analysis> analyseList = settingList.stream()
                .map(config -> analyzer.analyze(config, recruitUserList, timeline, now))
                .toList();

        // 6. 生成总结
        Recommendation result = new Recommendation();
        result.setTimestamp(now);
        result.setActiveOcCount(activeOcList.size());
        result.setRecruitingUserCount(recruitUserList.size());

        // 6.1 获取各类型的Recruiting和即将停转数量
        Map<String, Integer> recruitingByType = countByType(activeOcList,
                oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()));
        Map<String, Integer> nearStopByType = countNearStopByType(activeOcList, now);

        // 6.2 计算各类型详情
        List<Recommendation.TypeDetail> details = analyseList.stream()
                .map(a -> buildTypeDetail(a, recruitingByType, nearStopByType))
                .toList();
        result.setTypeDetails(details);

        // 6.3 计算保守建议
        int conservative = calculateConservativeSuggestion(analyseList,
                nearStopByType.values().stream().mapToInt(i -> i).sum());
        result.setConservativeSuggestion(conservative);

        // 6.4 计算加权建议
        int weighted = calculateWeightedSuggestion(details);
        result.setWeightedSuggestion(weighted);

        // 6.5 生成最后总结
        result.setSummary(buildDetailedSummary(result));
        return result;
    }

    /**
     * 获取被占用的用户
     */
    private Set<Long> getOccupyUser(List<TornFactionOcDO> ocList) {
        List<Long> ocIds = ocList.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .map(TornFactionOcDO::getId)
                .toList();

        if (ocIds.isEmpty()) return new HashSet<>();

        return slotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list()
                .stream()
                .map(TornFactionOcSlotDO::getUserId)
                .collect(Collectors.toSet());
    }

    /**
     * 构建成员释放时间线
     */
    private MemberTimeline buildTimeline(List<TornFactionOcDO> planOcList) {
        MemberTimeline timeline = new MemberTimeline();
        List<TornFactionOcSlotDO> planSlotList = slotDao.queryListByOc(planOcList);

        for (TornFactionOcDO oc : planOcList) {
            Set<Long> userIdSet = planSlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .map(TornFactionOcSlotDO::getUserId)
                    .collect(Collectors.toSet());
            String key = oc.getName() + "_" + oc.getRank();
            timeline.addRelease(key, oc.getReadyTime(), userIdSet);
        }

        return timeline;
    }

    /**
     * 构建每种类型的分析输出
     */
    private Recommendation.TypeDetail buildTypeDetail(OcTypeAnalyzer.Analysis analysis,
                                                      Map<String, Integer> recruitingByType,
                                                      Map<String, Integer> nearStopByType) {
        Recommendation.TypeDetail detail = new Recommendation.TypeDetail();
        detail.setOcTypeKey(analysis.getOcTypeKey());
        detail.setOcName(analysis.getOcName());

        // 设置OC刷新概率
        detail.setProbability(getHistoricalProbability(analysis.getOcName()));

        detail.setCurrentRecruiting(recruitingByType.getOrDefault(analysis.getOcTypeKey(), 0));
        detail.setNearStopCount(nearStopByType.getOrDefault(analysis.getOcTypeKey(), 0));
        detail.setIdleCount(analysis.getCurrentIdleCount());
        detail.setMaxSustainable(analysis.getMaxSustainableOcs());

        // 评估状态
        int needed = detail.getNearStopCount() * analysis.getRequiredMembers();
        if (detail.getIdleCount() >= needed * 2) {
            detail.setStatus("✅ 充足");
        } else if (detail.getIdleCount() >= needed) {
            detail.setStatus("⚠️ 紧张");
        } else {
            detail.setStatus("❌ 危险");
        }

        // 生成建议
        detail.setRecommendation(generateTypeRecommendation(detail, analysis));
        return detail;
    }

    private double getHistoricalProbability(String ocName) {
        return switch (ocName) {
            case "Blast from the Past" -> 0.50;
            case "Clinical Precision", "Break the Bank" -> 0.25;
            default -> 0.333;
        };
    }

    private String generateTypeRecommendation(Recommendation.TypeDetail detail,
                                              OcTypeAnalyzer.Analysis analysis) {
        if ("❌ 危险".equals(detail.getStatus())) {
            return String.format("⛔ 建议暂停新建，优先维持现有%d个即将停转的OC",
                    detail.getNearStopCount());
        } else if ("⚠️ 紧张".equals(detail.getStatus())) {
            return String.format("⚠️ 可新建1队，但需密切监控（已有%d个Recruiting）",
                    detail.getCurrentRecruiting());
        } else {
            int safe = (int) (analysis.getMaxSustainableOcs() * 0.8);
            return String.format("✅ 可新建%d-%d队（空闲%d人，可持续%d队）",
                    Math.max(1, safe - 1), safe,
                    detail.getIdleCount(), analysis.getMaxSustainableOcs());
        }
    }

    /**
     * 保守策略：取所有类型的最小可持续数
     * 确保无论随机到哪个类型都能安全运作
     */
    private int calculateConservativeSuggestion(List<OcTypeAnalyzer.Analysis> analyseList, int totalNearStopCount) {
        // 1. 取最小的可持续数量（木桶效应）
        int min = analyseList.stream()
                .mapToInt(OcTypeAnalyzer.Analysis::getMaxSustainableOcs)
                .min()
                .orElse(0);

        // 2. 如果有即将停转的OC，进一步限制
        if (totalNearStopCount > 0) {
            // 获取最小空闲人数
            int minIdle = analyseList.stream()
                    .mapToInt(OcTypeAnalyzer.Analysis::getCurrentIdleCount)
                    .min()
                    .orElse(0);

            // 估算每个OC平均需要的人数（取平均值）
            double avgRequired = analyseList.stream()
                    .mapToDouble(OcTypeAnalyzer.Analysis::getRequiredMembers)
                    .average()
                    .orElse(6.0);

            int needForNearStop = (int) Math.ceil(totalNearStopCount * avgRequired);

            // 如果空闲人数不足以维持即将停转的OC，降低建议数
            if (minIdle < needForNearStop) {
                log.warn("紧急：即将停转{}个OC，但最小空闲人数仅{}", totalNearStopCount, minIdle);
                min = Math.max(0, min - (int) Math.ceil(totalNearStopCount / 2.0));
            }
        }

        // 3. 应用80%安全系数
        int safe = (int) (min * 0.8);

        // 4. 限制最大5个
        return Math.min(safe, 5);
    }

    /**
     * 加权策略
     */
    private int calculateWeightedSuggestion(List<Recommendation.TypeDetail> details) {
        // 按概率加权计算期望可持续数
        double expectedSustainable = details.stream()
                .mapToDouble(d -> d.getProbability() * d.getMaxSustainable())
                .sum();

        // 应用80%安全系数
        int weighted = (int) (expectedSustainable * 0.8);

        // 如果有类型处于危险状态，额外减少
        long dangerCount = details.stream()
                .filter(d -> "❌ 危险".equals(d.getStatus()))
                .count();

        if (dangerCount > 0) {
            weighted = Math.max(0, weighted - (int) dangerCount);
        }

        return Math.min(weighted, 5);
    }

    /**
     * 构建总结
     */
    private String buildDetailedSummary(Recommendation rec) {
        return "【综合建议】\n" +
                String.format("  🎯 加权建议: 新建 %d 个队伍\n",
                        rec.getWeightedSuggestion()) +
                String.format("  🛡️ 保守建议: 新建 %d 个队伍\n",
                        rec.getConservativeSuggestion()) +
                "【当前状态】\n" +
                String.format("  • 活跃OC总数: %d 个\n", rec.getActiveOcCount()) +
                String.format("  • 招募中用户: %d 人\n", rec.getRecruitingUserCount()) +
                String.format("  • 24h内停转OC: %d 个\n",
                        rec.getTypeDetails().stream().mapToInt(Recommendation.TypeDetail::getNearStopCount).sum());
    }

    /**
     * 分类型统计个数
     */
    private Map<String, Integer> countByType(List<TornFactionOcDO> ocs, Predicate<TornFactionOcDO> filter) {
        return ocs.stream()
                .filter(filter)
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 分类型统计停转数量
     */
    private Map<String, Integer> countNearStopByType(List<TornFactionOcDO> activeOcs, LocalDateTime now) {
        LocalDateTime threshold = now.plusHours(24);
        return activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime() == null || oc.getReadyTime().isBefore(threshold))
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 推荐结果
     */
    @Data
    public static class Recommendation {
        private LocalDateTime timestamp;
        private int activeOcCount;
        private int recruitingUserCount;
        private int conservativeSuggestion;
        private int weightedSuggestion;
        private List<TypeDetail> typeDetails;
        private String summary;

        @Data
        public static class TypeDetail {
            private String ocTypeKey;
            private String ocName;
            private double probability;
            private int qualifiedCount;
            private int currentRecruiting;
            private int nearStopCount;
            private int idleCount;
            private int maxSustainable;
            private String status;  // "充足" / "紧张" / "危险"
            private String recommendation; // 具体建议
        }
    }
}