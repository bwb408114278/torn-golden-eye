package pn.torn.goldeneye.torn.service.faction.oc.create;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.create.FeasibilityResult;
import pn.torn.goldeneye.torn.model.faction.crime.create.MemberTimeline;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC类型分析器
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcTypeAnalyzer {
    private final TornSettingOcSlotManager slotManager;
    private final TornFactionOcUserDAO userDao;
    private final TimelineMatcher matcher = new TimelineMatcher();

    /**
     * 分析OC类型可行性
     */
    public Analysis analyze(TornSettingOcDO setting, Set<Long> recruitingUsers,
                            MemberTimeline timeline, LocalDateTime now) {
        String ocTypeKey = setting.getOcName() + "_" + setting.getRank();

        Analysis result = new Analysis();
        result.setOcTypeKey(ocTypeKey);
        result.setOcName(setting.getOcName());
        result.setRank(setting.getRank());
        result.setRequiredMembers(setting.getRequiredMembers());

        // 1. 获取合格用户
        Set<Long> qualified = getQualifiedUsers(setting);
        result.setQualifiedCount(qualified.size());

        // 2. 计算当前空闲
        Set<Long> currentIdle = new HashSet<>(qualified);
        currentIdle.removeAll(recruitingUsers);
        currentIdle.removeAll(timeline.getReleasedBy(ocTypeKey, now.plusYears(10)));
        result.setCurrentIdleCount(currentIdle.size());

        // 3. 时间窗口统计
        result.setWindowStats(buildWindowStats(ocTypeKey, qualified, currentIdle, timeline, now));

        // 4. 测试可行性（1-5个OC）
        List<FeasibilityResult> tests = new ArrayList<>();
        int maxSustainable = 0;

        for (int i = 1; i <= 5; i++) {
            FeasibilityResult test = matcher.checkFeasibility(
                    i, setting.getRequiredMembers(), currentIdle,
                    timeline, ocTypeKey, qualified, now);
            tests.add(test);

            if (test.isFeasible()) {
                maxSustainable = i;
            } else {
                break;
            }
        }

        result.setMaxSustainableOcs(maxSustainable);
        result.setFeasibilityTests(tests);
        result.setRiskLevel(assessRisk(currentIdle.size(), setting.getRequiredMembers(), maxSustainable));
        log.info("{}: 合格={}, 空闲={}, 最大可持续={}",
                ocTypeKey, qualified.size(), currentIdle.size(), maxSustainable);

        return result;
    }

    /**
     * 获取合格用户
     */
    private Set<Long> getQualifiedUsers(TornSettingOcDO setting) {
        List<TornSettingOcSlotDO> slots = slotManager.getList().stream()
                .filter(s -> s.getOcName().equals(setting.getOcName()))
                .filter(s -> s.getRank().equals(setting.getRank()))
                .toList();

        if (slots.isEmpty()) return new HashSet<>();
        List<TornFactionOcUserDO> abilities = userDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, TornConstants.FACTION_PN_ID)
                .eq(TornFactionOcUserDO::getOcName, setting.getOcName())
                .eq(TornFactionOcUserDO::getRank, setting.getRank())
                .list();

        Map<Long, List<TornFactionOcUserDO>> byUser = abilities.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        Set<Long> qualified = new HashSet<>();

        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : byUser.entrySet()) {
            Map<String, Integer> userPassRates = entry.getValue().stream()
                    .collect(Collectors.toMap(
                            TornFactionOcUserDO::getPosition,
                            TornFactionOcUserDO::getPassRate,
                            Math::max));

            boolean qualifies = slots.stream().anyMatch(slot -> {
                Integer rate = userPassRates.get(slot.getSlotShortCode());
                return rate != null && rate >= slot.getPassRate();
            });

            if (qualifies) qualified.add(entry.getKey());
        }

        return qualified;
    }

    /**
     * 构建时间窗口统计
     */
    private Map<String, Integer> buildWindowStats(
            String ocTypeKey, Set<Long> qualified, Set<Long> currentIdle,
            MemberTimeline timeline, LocalDateTime now) {

        Map<String, Integer> stats = new LinkedHashMap<>();
        int[] windows = {6, 12, 24, 48, 72};

        for (int hours : windows) {
            Set<Long> available = new HashSet<>(currentIdle);
            available.addAll(timeline.getReleasedBy(ocTypeKey, now.plusHours(hours)));
            available.retainAll(qualified);
            stats.put(hours + "h", available.size());
        }

        return stats;
    }

    /**
     * 评估风险
     */
    private String assessRisk(int idle, int required, int maxSustainable) {
        double ratio = (double) idle / required;

        if (maxSustainable >= 3) {
            return ratio >= 3 ? "低风险" : "中低风险";
        } else if (maxSustainable >= 2) {
            return ratio >= 2 ? "中风险" : "中高风险";
        } else if (maxSustainable >= 1) {
            return "高风险";
        }
        return "极高风险";
    }

    /**
     * 分析结果
     */
    @Data
    public static class Analysis {
        private String ocTypeKey;
        private String ocName;
        private Integer rank;
        private int requiredMembers;
        private int qualifiedCount;
        private int currentIdleCount;
        private int maxSustainableOcs;
        private List<FeasibilityResult> feasibilityTests;
        private Map<String, Integer> windowStats;  // 时间窗口 -> 可用人数
        private String riskLevel;
    }
}