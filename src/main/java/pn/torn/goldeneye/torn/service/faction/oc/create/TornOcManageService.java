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
        List<TornFactionOcDO> activeOcs = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID);
        Set<Long> recruitingUsers = getOccupiedUsers(activeOcs);
        List<TornFactionOcDO> planningOcs = activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .toList();
        log.debug("活跃OC: 招募中用户={}, 计划中OC={}", recruitingUsers.size(), planningOcs.size());

        // 2. 构建时间线
        MemberTimeline timeline = buildTimeline(planningOcs, now);

        // 3. 分析各OC类型
        List<TornSettingOcDO> configs = settingOcManager.getList().stream()
                .filter(c -> TornConstants.REASSIGN_OC_NAME.contains(c.getOcName()))
                .toList();

        List<OcTypeAnalyzer.Analysis> analyses = configs.stream()
                .map(config -> analyzer.analyze(config, recruitingUsers, timeline, now))
                .toList();

        // 6. 生成总结
        Recommendation result = new Recommendation();
        result.setTimestamp(now);
        result.setActiveOcCount(activeOcs.size());
        result.setRecruitingUserCount(recruitingUsers.size());

        // 6.1 获取各类型的Recruiting和即将停转数量
        Map<String, Integer> recruitingByType = countByType(activeOcs,
                oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()));
        Map<String, Integer> nearStopByType = countNearStopByType(activeOcs, now);

        // 6.2 计算各类型详情（使用历史概率）
        List<Recommendation.TypeDetail> details = analyses.stream()
                .map(a -> buildTypeDetail(a, recruitingByType, nearStopByType))
                .toList();
        result.setTypeDetails(details);

        // 6.3 计算保守建议（当前逻辑）
        int conservative = calculateConservativeSuggestion(analyses,
                nearStopByType.values().stream().mapToInt(i -> i).sum());
        result.setConservativeSuggestion(conservative);

        // 6.4 计算加权建议
        int weighted = calculateWeightedSuggestion(details);
        result.setWeightedSuggestion(weighted);

        // 6.5 生成风险总结
        result.setRiskSummary(buildRiskSummary(details, weighted));
        result.setSummary(buildDetailedSummary(result));

        return result;
    }

    /**
     * 获取被占用的用户
     */
    private Set<Long> getOccupiedUsers(List<TornFactionOcDO> ocList) {
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
    private MemberTimeline buildTimeline(List<TornFactionOcDO> planningOcs, LocalDateTime now) {
        MemberTimeline timeline = new MemberTimeline();

        for (TornFactionOcDO oc : planningOcs) {
            if (oc.getReadyTime() == null || oc.getReadyTime().isBefore(now)) {
                log.warn("跳过无效OC: id={}, readyTime={}", oc.getId(), oc.getReadyTime());
                continue;
            }

            List<TornFactionOcSlotDO> slots = slotDao.lambdaQuery()
                    .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                    .isNotNull(TornFactionOcSlotDO::getUserId)
                    .list();

            if (slots.isEmpty()) continue;

            Set<Long> userIds = slots.stream()
                    .map(TornFactionOcSlotDO::getUserId)
                    .collect(Collectors.toSet());

            String key = oc.getName() + "_" + oc.getRank();
            timeline.addRelease(key, oc.getReadyTime(), userIds);
        }

        return timeline;
    }

    private Recommendation.TypeDetail buildTypeDetail(OcTypeAnalyzer.Analysis analysis,
                                                      Map<String, Integer> recruitingByType,
                                                      Map<String, Integer> nearStopByType) {
        Recommendation.TypeDetail detail = new Recommendation.TypeDetail();
        detail.setOcTypeKey(analysis.getOcTypeKey());
        detail.setOcName(analysis.getOcName());

        // 使用历史概率（需要配置或从数据库读取）
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
    private int calculateConservativeSuggestion(List<OcTypeAnalyzer.Analysis> analyses,
                                                int totalNearStopCount) {
        // 1. 取最小的可持续数量（木桶效应）
        int min = analyses.stream()
                .mapToInt(OcTypeAnalyzer.Analysis::getMaxSustainableOcs)
                .min()
                .orElse(0);

        // 2. 如果有即将停转的OC，进一步限制
        if (totalNearStopCount > 0) {
            // 获取最小空闲人数
            int minIdle = analyses.stream()
                    .mapToInt(OcTypeAnalyzer.Analysis::getCurrentIdleCount)
                    .min()
                    .orElse(0);

            // 估算每个OC平均需要的人数（取平均值）
            double avgRequired = analyses.stream()
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
     * 构建风险总结
     */
    private String buildRiskSummary(List<Recommendation.TypeDetail> details, int weightedSuggestion) {
        StringBuilder sb = new StringBuilder();

        // 找出危险和紧张的类型
        List<Recommendation.TypeDetail> dangerTypes = details.stream()
                .filter(d -> "❌ 危险".equals(d.getStatus()))
                .toList();

        List<Recommendation.TypeDetail> warningTypes = details.stream()
                .filter(d -> "⚠️ 紧张".equals(d.getStatus()))
                .toList();

        if (dangerTypes.isEmpty() && warningTypes.isEmpty()) {
            return "  ✅ 当前各类型人手均较充足，风险较低";
        }

        // 危险类型
        if (!dangerTypes.isEmpty()) {
            for (Recommendation.TypeDetail detail : dangerTypes) {
                int needed = detail.getNearStopCount() * 6; // 假设每个OC需要6人
                sb.append(String.format("  ⚠️ %s 人手严重不足！\n", detail.getOcName()));
                sb.append(String.format("     • 当前仅%d人空闲，但有%d个OC即将停转需要约%d人维持\n",
                        detail.getIdleCount(), detail.getNearStopCount(), needed));
                sb.append(String.format("     • 如果新建OC随机到此类型(%.1f%%概率)，将面临人员短缺\n",
                        detail.getProbability() * 100));
                sb.append("     • 建议：如随机到此类型，立即取消或等待人员释放\n\n");
            }
        }

        // 紧张类型
        if (!warningTypes.isEmpty()) {
            for (Recommendation.TypeDetail detail : warningTypes) {
                sb.append(String.format("  ⚠️ %s 人手较紧张\n", detail.getOcName()));
                sb.append(String.format("     • 空闲%d人，已有%d个Recruiting中，%d个即将停转\n",
                        detail.getIdleCount(), detail.getCurrentRecruiting(), detail.getNearStopCount()));
                sb.append(String.format("     • 随机概率: %.1f%%\n", detail.getProbability() * 100));
                sb.append("     • 建议：可新建但需密切监控\n\n");
            }
        }

        // 整体成功率评估
        if (weightedSuggestion > 0) {
            double successProb = details.stream()
                    .filter(d -> !"❌ 危险".equals(d.getStatus()))
                    .mapToDouble(Recommendation.TypeDetail::getProbability)
                    .sum();

            sb.append(String.format("  📊 新建OC成功率预估: %.1f%% (不会随机到危险类型的概率)\n",
                    successProb * 100));
        }

        return sb.toString().trim();
    }


    private String buildDetailedSummary(Recommendation rec) {
        StringBuilder sb = new StringBuilder();

        sb.append("=".repeat(60)).append("\n");
        sb.append("📊 OC新建可行性分析报告\n");
        sb.append("=".repeat(60)).append("\n\n");

        // 总体建议
        sb.append("【综合建议】\n");
        sb.append(String.format("  🎯 加权建议: 新建 %d 个队伍（考虑历史概率分布）\n",
                rec.getWeightedSuggestion()));
        sb.append(String.format("  🛡️ 保守建议: 新建 %d 个队伍（确保所有类型都安全）\n\n",
                rec.getConservativeSuggestion()));

        // 当前状态
        sb.append("【当前状态】\n");
        sb.append(String.format("  • 活跃OC总数: %d 个\n", rec.getActiveOcCount()));
        sb.append(String.format("  • 招募中用户: %d 人\n", rec.getRecruitingUserCount()));
        sb.append(String.format("  • 即将停转OC: %d 个\n\n",
                rec.getTypeDetails().stream().mapToInt(Recommendation.TypeDetail::getNearStopCount).sum()));

        // 分类型详情
        sb.append("【分类型详细分析】\n\n");
        for (Recommendation.TypeDetail detail : rec.getTypeDetails()) {
            sb.append(String.format("🎲 %s (历史概率: %.1f%%)\n",
                    detail.getOcName(), detail.getProbability() * 100));
            sb.append(String.format("   状态: %s\n", detail.getStatus()));
            sb.append(String.format("   • 合格人员: 需查询\n"));
            sb.append(String.format("   • 当前空闲: %d 人\n", detail.getIdleCount()));
            sb.append(String.format("   • 最大可持续: %d 队\n", detail.getMaxSustainable()));
            sb.append(String.format("   • Recruiting中: %d 个OC\n", detail.getCurrentRecruiting()));
            sb.append(String.format("   • 即将停转: %d 个OC\n", detail.getNearStopCount()));
            sb.append(String.format("   ➜ 建议: %s\n\n", detail.getRecommendation()));
        }

        // 风险提示
        if (rec.getRiskSummary() != null && !rec.getRiskSummary().isEmpty()) {
            sb.append("【风险提示】\n");
            sb.append(rec.getRiskSummary()).append("\n\n");
        }

        // 决策建议
        sb.append("【决策建议】\n");
        sb.append(generateDecisionGuidance(rec));

        sb.append("\n").append("=".repeat(60));

        return sb.toString();
    }

    private String generateDecisionGuidance(Recommendation rec) {
        StringBuilder sb = new StringBuilder();

        int weighted = rec.getWeightedSuggestion();

        if (weighted == 0) {
            sb.append("  ⛔ 当前不建议新建OC\n");
            sb.append("  📌 原因: 存在高风险类型，人手紧张\n");
            sb.append("  🔧 建议: 等待部分OC完成释放人力后再考虑\n");
        } else if (weighted <= 2) {
            sb.append(String.format("  ✅ 可尝试新建 %d 个队伍\n", weighted));
            sb.append("  ⚠️ 注意: 如果随机到Break the Bank，建议取消或暂停\n");
            sb.append("  📊 期望成功率: 约75% (50%+25% 概率分配到人手充足的类型)\n");
        } else {
            sb.append(String.format("  ✅ 推荐新建 %d 个队伍\n", weighted));
            sb.append("  📊 各类型人手均较充足，风险可控\n");
        }

        return sb.toString();
    }


    private Map<String, Integer> countByType(List<TornFactionOcDO> ocs,
                                             Predicate<TornFactionOcDO> filter) {
        return ocs.stream()
                .filter(filter)
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)
                ));
    }

    private Map<String, Integer> countNearStopByType(List<TornFactionOcDO> activeOcs,
                                                     LocalDateTime now) {
        LocalDateTime threshold = now.plusHours(24);
        return activeOcs.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime() == null || oc.getReadyTime().isBefore(threshold))
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)
                ));
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
        private String riskSummary;
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