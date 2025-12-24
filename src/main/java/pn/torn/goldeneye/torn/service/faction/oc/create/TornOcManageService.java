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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OC管理服务
 *
 * @author Bai
 * @version 0.4.0
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
     * 按时间流分析并推荐（主入口）
     */
    public TimeBasedRecommendation analyze(long factionId) {
        LocalDateTime now = LocalDateTime.now();
        List<LocalDateTime> timePoints = generateTimePoints(now);

        // 已经计算过的, 不会再次计算
        Set<Long> excludeIdSet = new HashSet<>();
        List<TimePointRecommendation> recommendations = timePoints.stream()
                .map(tp -> {
                    TimePointRecommendation rec = new TimePointRecommendation();
                    rec.setTimePoint(tp);
                    rec.setTimeLabel(formatTimeLabel(tp, now));
                    rec.setRecommendation(analyze(factionId, tp));
                    rec.setStatusChange(buildStatusChange(factionId, now, tp, excludeIdSet));
                    return rec;
                })
                .toList();

        TimeBasedRecommendation result = new TimeBasedRecommendation();
        result.setCurrentTime(now);
        result.setRecommendations(recommendations);
        result.setSummary(buildTimeFlowSummary(recommendations));
        return result;
    }

    /**
     * 核心分析方法（支持指定时间）
     */
    private Recommendation analyze(long factionId, LocalDateTime targetTime) {
        // 1. 预测目标时间的活跃OC和占用用户
        List<TornFactionOcDO> activeOcList = getActiveOcAt(factionId, targetTime);
        Set<Long> occupyUserSet = getOccupyUsersFrom(activeOcList);
        List<TornFactionOcDO> planOcs = activeOcList.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .toList();

        // 2. 构建时间线和分析
        MemberTimeline timeline = buildTimeline(planOcs);
        List<TornSettingOcDO> settings = settingOcManager.getList().stream()
                .filter(c -> TornConstants.ROTATION_OC_NAME.contains(c.getOcName()))
                .toList();
        List<OcTypeAnalyzer.Analysis> analyses = analyzer.analyze(factionId, settings,
                occupyUserSet, timeline, targetTime);

        // 3. 统计数据
        Map<String, Integer> recruiting = countByRecruiting(activeOcList);
        Map<String, Integer> emptyQueues = countEmptyQueues(activeOcList);
        Map<String, Integer> nearStop = countNearStop(activeOcList, targetTime);
        Map<String, Integer> nearComplete = countNearComplete(activeOcList, targetTime);

        // 4. 构建结果
        Recommendation result = new Recommendation();
        result.setTypeDetails(analyses.stream()
                .map(a -> buildTypeDetail(a, recruiting, emptyQueues, nearStop, nearComplete))
                .toList());
        result.setConservativeSuggestion(calculateConservative(analyses,
                nearStop.values().stream().mapToInt(i -> i).sum()));
        result.setWeightedSuggestion(calculateWeighted(result.getTypeDetails()));
        return result;
    }

    /**
     * 生成分析的时间点列表
     */
    private List<LocalDateTime> generateTimePoints(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);

        return Stream.of(LocalDateTime.of(today, LocalTime.of(9, 0)),
                        LocalDateTime.of(today, LocalTime.of(15, 0)),
                        LocalDateTime.of(today, LocalTime.of(21, 0)),
                        LocalDateTime.of(tomorrow, LocalTime.of(9, 0)),
                        LocalDateTime.of(tomorrow, LocalTime.of(15, 0)),
                        LocalDateTime.of(tomorrow, LocalTime.of(21, 0)))
                .filter(t -> t.isAfter(now))
                .sorted()
                .limit(3)
                .toList();
    }

    /**
     * 格式化时间标签
     */
    private String formatTimeLabel(LocalDateTime target, LocalDateTime now) {
        String datePrefix = target.toLocalDate().equals(now.toLocalDate()) ? "今日" : "明日";
        long hours = Duration.between(now, target).toHours();
        return String.format("%s %s (距现在%d小时)", datePrefix,
                target.format(DateTimeFormatter.ofPattern("HH:mm")), hours);
    }

    /**
     * 构建状态流转
     */
    private String buildStatusChange(long factionId, LocalDateTime from, LocalDateTime to,
                                     Set<Long> excludeOcIdSet) {
        List<TornFactionOcDO> activeOcs = ocDao.queryExecutingOc(factionId);

        List<Long> willComplete = activeOcs.stream()
                .filter(oc -> !excludeOcIdSet.contains(oc.getId()))
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime().isAfter(from) && !oc.getReadyTime().isAfter(to))
                .map(TornFactionOcDO::getId)
                .toList();
        excludeOcIdSet.addAll(willComplete);

        List<Long> needContinue = activeOcs.stream()
                .filter(oc -> !excludeOcIdSet.contains(oc.getId()))
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> !isEmptyQueue(oc))
                .filter(oc -> oc.getReadyTime() != null && oc.getReadyTime().isBefore(from))
                .map(TornFactionOcDO::getId)
                .toList();
        excludeOcIdSet.addAll(needContinue);

        List<String> changes = new ArrayList<>();
        if (!willComplete.isEmpty()) changes.add(String.format("✅ %d个OC将完成", willComplete.size()));
        if (!needContinue.isEmpty()) changes.add(String.format("⚠️ %d个OC将停转", needContinue.size()));
        return changes.isEmpty() ? "无明显变化" : String.join(" | ", changes);
    }

    /**
     * 构建推荐总结
     */
    private String buildTimeFlowSummary(List<TimePointRecommendation> recommendList) {
        StringBuilder sb = new StringBuilder("【7/8级新队建议】\n");
        for (TimePointRecommendation rec : recommendList) {
            Recommendation r = rec.getRecommendation();
            sb.append(String.format("📍 %s\n", rec.getTimeLabel()));
            sb.append(String.format("   激进: %d队 | 保守: %d队\n",
                    r.getWeightedSuggestion(), r.getConservativeSuggestion()));
            if (!"无明显变化".equals(rec.getStatusChange())) {
                sb.append("   ").append(rec.getStatusChange()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取目标时间还需要人的OC
     */
    private List<TornFactionOcDO> getActiveOcAt(long factionId, LocalDateTime targetTime) {
        return ocDao.queryExecutingOc(factionId).stream()
                .filter(oc -> {
                    if (TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus())) {
                        return true;
                    } else if (TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus())) {
                        return oc.getReadyTime().isAfter(targetTime);
                    } else {
                        return false;
                    }
                })
                .toList();
    }

    /**
     * 获取被占用的用户
     */
    private Set<Long> getOccupyUsersFrom(List<TornFactionOcDO> activeOcList) {
        if (activeOcList.isEmpty()) {
            return Set.of();
        }

        List<Long> ocIds = activeOcList.stream().map(TornFactionOcDO::getId).toList();
        return slotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list()
                .stream()
                .map(TornFactionOcSlotDO::getUserId)
                .collect(Collectors.toSet());
    }

    /**
     * 构建时间流
     */
    private MemberTimeline buildTimeline(List<TornFactionOcDO> planOcList) {
        MemberTimeline timeline = new MemberTimeline();
        List<TornFactionOcSlotDO> slots = slotDao.queryListByOc(planOcList);

        for (TornFactionOcDO oc : planOcList) {
            Set<Long> users = slots.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .map(TornFactionOcSlotDO::getUserId)
                    .collect(Collectors.toSet());
            timeline.addRelease(oc.getName() + "_" + oc.getRank(), oc.getReadyTime(), users);
        }
        return timeline;
    }

    /**
     * 判断是否为空队（没有任何成员）
     */
    private boolean isEmptyQueue(TornFactionOcDO oc) {
        long memberCount = slotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .count();
        return memberCount == 0;
    }

    /**
     * 统计空队数量
     */
    private Map<String, Integer> countEmptyQueues(List<TornFactionOcDO> ocList) {
        return ocList.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(this::isEmptyQueue)
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 统计缺人队伍数量
     */
    private Map<String, Integer> countByRecruiting(List<TornFactionOcDO> ocList) {
        return ocList.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 统计即将停转的队伍
     */
    private Map<String, Integer> countNearStop(List<TornFactionOcDO> ocs, LocalDateTime now) {
        LocalDateTime threshold = now.plusHours(6);
        return ocs.stream()
                .filter(oc -> TornOcStatusEnum.RECRUITING.getCode().equals(oc.getStatus()))
                .filter(oc -> !isEmptyQueue(oc))
                .filter(oc -> oc.getReadyTime() != null && oc.getReadyTime().isBefore(threshold))
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 统计即将完成的队伍
     */
    private Map<String, Integer> countNearComplete(List<TornFactionOcDO> ocs, LocalDateTime now) {
        LocalDateTime threshold = now.plusHours(6);
        return ocs.stream()
                .filter(oc -> TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()))
                .filter(oc -> oc.getReadyTime() != null && oc.getReadyTime().isBefore(threshold))
                .collect(Collectors.groupingBy(
                        oc -> oc.getName() + "_" + oc.getRank(),
                        Collectors.summingInt(oc -> 1)));
    }

    /**
     * 构建每种类型的推荐
     */
    private Recommendation.TypeDetail buildTypeDetail(OcTypeAnalyzer.Analysis analysis,
                                                      Map<String, Integer> recruiting,
                                                      Map<String, Integer> emptyQueues,
                                                      Map<String, Integer> nearStop,
                                                      Map<String, Integer> nearComplete) {
        Recommendation.TypeDetail detail = new Recommendation.TypeDetail();
        detail.setOcTypeKey(analysis.getOcTypeKey());
        detail.setOcName(analysis.getOcName());
        detail.setProbability(getProbability(analysis.getOcName()));
        detail.setQualifiedCount(analysis.getQualifiedCount());

        // 区分总Recruiting数和空队数
        int totalRecruiting = recruiting.getOrDefault(analysis.getOcTypeKey(), 0);
        int emptyCount = emptyQueues.getOrDefault(analysis.getOcTypeKey(), 0);
        detail.setCurrentRecruiting(totalRecruiting);
        detail.setEmptyQueueCount(emptyCount);
        detail.setActiveRecruitingCount(totalRecruiting - emptyCount);

        detail.setNearStopCount(nearStop.getOrDefault(analysis.getOcTypeKey(), 0));
        detail.setNearCompleteCount(nearComplete.getOrDefault(analysis.getOcTypeKey(), 0));
        detail.setIdleCount(analysis.getCurrentIdleCount());
        detail.setMaxSustainable(analysis.getMaxSustainableOcs());
        detail.setReleaseSchedule(analysis.getWindowStats());

        // 计算状态和建议
        int needed = detail.getNearStopCount() * analysis.getRequiredMembers();
        String status;
        if (detail.getIdleCount() >= needed * 2) {
            status = "✅ 充足";
        } else {
            status = detail.getIdleCount() >= needed ? "⚠️ 紧张" : "❌ 危险";
        }
        detail.setStatus(status);
        return detail;
    }

    /**
     * 计算保守建议
     */
    private int calculateConservative(List<OcTypeAnalyzer.Analysis> analyses, int nearStopCount) {
        int min = analyses.stream()
                .mapToInt(OcTypeAnalyzer.Analysis::getMaxSustainableOcs)
                .min()
                .orElse(0);

        if (nearStopCount > 0) {
            int minIdle = analyses.stream()
                    .mapToInt(OcTypeAnalyzer.Analysis::getCurrentIdleCount)
                    .min()
                    .orElse(0);
            double avgRequired = analyses.stream()
                    .mapToDouble(OcTypeAnalyzer.Analysis::getRequiredMembers)
                    .average()
                    .orElse(6.0);

            if (minIdle < nearStopCount * avgRequired) {
                min = Math.max(0, min - (int) Math.ceil(nearStopCount / 2.0));
            }
        }
        return Math.min(min, 5);
    }

    /**
     * 计算缺人权重
     */
    private int calculateWeighted(List<Recommendation.TypeDetail> details) {
        double expected = details.stream()
                .mapToDouble(d -> d.getProbability() * d.getMaxSustainable())
                .sum();

        long dangerCount = details.stream()
                .filter(d -> "❌ 危险".equals(d.getStatus()))
                .count();

        return Math.min((int) expected - (int) dangerCount, 5);
    }

    /**
     * 每种OC的刷新概率
     */
    private double getProbability(String ocName) {
        return switch (ocName) {
            case "Blast from the Past" -> 0.50;
            case "Clinical Precision", "Break the Bank" -> 0.25;
            default -> 0.333;
        };
    }

    @Data
    public static class TimeBasedRecommendation {
        private LocalDateTime currentTime;
        private List<TimePointRecommendation> recommendations;
        private String summary;
    }

    @Data
    public static class TimePointRecommendation {
        private LocalDateTime timePoint;
        private String timeLabel;
        private Recommendation recommendation;
        private String statusChange;
    }

    @Data
    public static class Recommendation {
        private int conservativeSuggestion;
        private int weightedSuggestion;
        private List<TypeDetail> typeDetails;

        @Data
        public static class TypeDetail {
            private String ocTypeKey;
            private String ocName;
            private double probability;
            private int qualifiedCount;
            private int currentRecruiting;        // 总缺人队伍数
            private int emptyQueueCount;          // 空队数
            private int activeRecruitingCount;    // 有人且缺人的队伍数
            private int nearStopCount;
            private int nearCompleteCount;
            private int idleCount;
            private Map<String, Integer> releaseSchedule;
            private int maxSustainable;
            private String status;
        }
    }
}