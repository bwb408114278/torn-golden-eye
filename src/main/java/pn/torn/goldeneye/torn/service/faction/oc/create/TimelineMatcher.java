package pn.torn.goldeneye.torn.service.faction.oc.create;

import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.torn.model.faction.crime.create.FeasibilityResult;
import pn.torn.goldeneye.torn.model.faction.crime.create.MemberTimeline;
import pn.torn.goldeneye.torn.model.faction.crime.create.OcDemand;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 时间线匹配器 - 检查人员供给是否能满足需求
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.05
 */
@Slf4j
public class TimelineMatcher {
    /**
     * 检查N个OC是否可行
     */
    public FeasibilityResult checkFeasibility(int ocCount, int requiredPerOc, Set<Long> currentIdle,
                                              MemberTimeline timeline, String ocTypeKey, Set<Long> qualifiedIds,
                                              LocalDateTime now) {
        // 创建N个OC的需求
        List<OcDemand> demands = new ArrayList<>();
        for (int i = 0; i < ocCount; i++) {
            demands.add(new OcDemand(requiredPerOc, now));
        }

        // 收集所有需求时间点
        TreeMap<LocalDateTime, Integer> demandAtTime = new TreeMap<>();
        for (OcDemand demand : demands) {
            for (OcDemand.SlotDemand slot : demand.getSlots()) {
                demandAtTime.merge(slot.getJoinTime(), 1, Integer::sum);
            }
        }

        // 逐个时间点检查
        List<FeasibilityResult.TimeCheck> checks = new ArrayList<>();
        Set<Long> occupied = new HashSet<>();

        for (Map.Entry<LocalDateTime, Integer> entry : demandAtTime.entrySet()) {
            LocalDateTime time = entry.getKey();
            int needed = entry.getValue();

            // 计算可用人数
            Set<Long> available = new HashSet<>(currentIdle);
            available.addAll(timeline.getReleasedBy(ocTypeKey, time));
            available.retainAll(qualifiedIds);
            available.removeAll(occupied);

            checks.add(new FeasibilityResult.TimeCheck(time, needed, available.size()));

            if (available.size() < needed) {
                String reason = String.format("时间点%s需要%d人，只有%d人可用",
                        time.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                        needed, available.size());
                return new FeasibilityResult(ocCount, false, reason, checks);
            }

            // 模拟占用这批人员
            occupied.addAll(available.stream().limit(needed).toList());
        }

        return new FeasibilityResult(ocCount, true, String.format("可支持%d个OC", ocCount), checks);
    }
}