package pn.torn.goldeneye.torn.service.faction.oc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC计划执行时间计算测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC计划执行时间计算测试")
class OcPreparationTimeCalculatorTest {

    @Test
    @DisplayName("准备时间截断到分钟后加一分钟")
    void calculatePlannedTime_shouldUseNextMinute() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 8, 31, 12, 34, 56, 789);

        assertEquals(LocalDateTime.of(2026, 8, 31, 12, 35),
                OcPreparationTimeCalculator.calculatePlannedTime(readyTime));
    }
}
