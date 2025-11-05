package pn.torn.goldeneye.torn.model.faction.crime.create;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 成员可用性时间线
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.05
 */
@Data
public class MemberTimeline {
    /**
     * OC类型 -> (释放时间 -> 成员ID集合)
     */
    private Map<String, TreeMap<LocalDateTime, Set<Long>>> releaseEvents = new HashMap<>();

    public void addRelease(String ocTypeKey, LocalDateTime time, Set<Long> userIds) {
        releaseEvents.computeIfAbsent(ocTypeKey, k -> new TreeMap<>())
                .computeIfAbsent(time, k -> new HashSet<>())
                .addAll(userIds);
    }

    /**
     * 获取指定时间点之前累计释放的成员
     */
    public Set<Long> getReleasedBy(String ocTypeKey, LocalDateTime time) {
        TreeMap<LocalDateTime, Set<Long>> timeline = releaseEvents.get(ocTypeKey);
        if (timeline == null) {
            return new HashSet<>();
        }

        Set<Long> released = new HashSet<>();
        timeline.headMap(time, true).values().forEach(released::addAll);
        return released;
    }
}