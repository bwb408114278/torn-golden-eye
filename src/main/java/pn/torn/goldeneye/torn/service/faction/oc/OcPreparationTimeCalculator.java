package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * OC计划执行时间计算器。
 * <p>
 * Torn在准备时间所在分钟的下一分钟统一执行OC；完成通知和后续图片标题应复用此公式。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcPreparationTimeCalculator {

    /**
     * 计算OC计划执行时间。
     *
     * @param readyTime OC准备时间
     * @return 截断到分钟后加一分钟的计划执行时间
     */
    public static LocalDateTime calculatePlannedTime(LocalDateTime readyTime) {
        return readyTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    }
}
