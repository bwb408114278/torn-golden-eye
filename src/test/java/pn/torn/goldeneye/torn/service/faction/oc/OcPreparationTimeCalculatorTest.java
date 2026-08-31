package pn.torn.goldeneye.torn.service.faction.oc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcImageTitleFormatter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC计划执行时间计算器测试。
 * <p>
 * 验证分钟截断加一分钟的统一公式，以及图片标题预计执行文案与该权威实现使用同一结果。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@DisplayName("OC计划执行时间计算测试")
class OcPreparationTimeCalculatorTest {

    private static final DateTimeFormatter HH_MM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Test
    @DisplayName("readyTime含秒时先截断到分钟再加1分钟")
    void readyTimeWithSeconds_shouldTruncateThenPlusOneMinute() {
        assertEquals(LocalDateTime.of(2026, 9, 1, 12, 45),
                OcPreparationTimeCalculator.calculatePlannedTime(LocalDateTime.of(2026, 9, 1, 12, 44, 30)));
    }

    @Test
    @DisplayName("分钟边界跨小时跨天时正确落到下一分钟")
    void minuteBoundary_shouldCrossHourAndDay() {
        assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0),
                OcPreparationTimeCalculator.calculatePlannedTime(LocalDateTime.of(2026, 9, 1, 23, 59, 59)));
        assertEquals(LocalDateTime.of(2026, 9, 1, 12, 1),
                OcPreparationTimeCalculator.calculatePlannedTime(LocalDateTime.of(2026, 9, 1, 12, 0, 0)));
    }

    @Test
    @DisplayName("图片标题预计执行文案与计算器使用同一结果")
    void plannedTitleText_shouldUseCalculatorResult() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        LocalDateTime readyTime = LocalDateTime.of(2026, 9, 1, 12, 44, 30);

        OcImageTitleFormatter formatter = new OcImageTitleFormatter();
        assertEquals("预计" + OcPreparationTimeCalculator.calculatePlannedTime(readyTime)
                        .format(HH_MM_FORMATTER) + "开始执行",
                formatter.format("Planning", readyTime, now));
    }
}
