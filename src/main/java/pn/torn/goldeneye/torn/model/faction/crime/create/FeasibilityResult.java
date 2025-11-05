package pn.torn.goldeneye.torn.model.faction.crime.create;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可行性检查结果
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.05
 */
@Data
@AllArgsConstructor
public class FeasibilityResult {
    /**
     * 测试的OC数量
     */
    private int ocCount;
    /**
     * 是否可行
     */
    private boolean feasible;
    /**
     * 原因
     */
    private String reason;
    /**
     * 时间点检查
     */
    private List<TimeCheck> timeChecks;

    @Data
    @AllArgsConstructor
    public static class TimeCheck {
        private LocalDateTime time;
        private int required;
        private int available;

        public boolean isSufficient() {
            return available >= required;
        }
    }
}