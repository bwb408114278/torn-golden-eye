package pn.torn.goldeneye.torn.model.faction.crime.create;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个OC的人员需求
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.05
 */
@Data
public class OcDemand {
    /**
     * 需要的总人数
     */
    private int requiredMembers;
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    /**
     * 需求岗位
     */
    private List<SlotDemand> slots;

    public OcDemand(int requiredMembers, LocalDateTime startTime) {
        this.requiredMembers = requiredMembers;
        this.startTime = startTime;
        this.slots = new ArrayList<>();

        // 构建需求：每个位置需要在不同时间点加入
        for (int i = 0; i < requiredMembers; i++) {
            LocalDateTime joinTime = startTime.plusHours(i * 24L);
            slots.add(new SlotDemand(i, joinTime));
        }
    }

    public LocalDateTime getCompletionTime() {
        if (slots.isEmpty()) return startTime;
        return slots.getLast().joinTime.plusHours(24);
    }

    @Data
    @AllArgsConstructor
    public static class SlotDemand {
        private int index;
        private LocalDateTime joinTime;
    }
}