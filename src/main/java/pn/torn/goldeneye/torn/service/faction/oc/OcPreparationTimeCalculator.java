package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * OC计划执行时间计算器。
 * <p>
 * 统一实现项目口径的 OC 计划执行时间规则：Torn 在准备时间所在分钟的下一分钟统一执行，
 * 即 {@code readyTime} 截断到分钟后加 {@link #PLANNED_OFFSET_MINUTES} 分钟。
 * 完成延误通知与表格图片标题共用本实现，任何一侧不得另行复制该公式。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcPreparationTimeCalculator {

    /**
     * 计划执行时间相对准备时间分钟截断值的偏移分钟数。
     */
    private static final long PLANNED_OFFSET_MINUTES = 1L;

    /**
     * 计算 OC 计划执行时间。
     *
     * @param readyTime OC 准备链结束时间
     * @return 准备时间截断到分钟后加 1 分钟的计划执行时间
     */
    public static LocalDateTime calculatePlannedTime(LocalDateTime readyTime) {
        return readyTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(PLANNED_OFFSET_MINUTES);
    }
}
