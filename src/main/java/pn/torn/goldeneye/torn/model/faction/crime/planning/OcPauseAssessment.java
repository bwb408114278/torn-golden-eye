package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一条候选时间线上一支OC的停转评估。
 *
 * @param obligationKey 匿名义务键
 * @param newPauseDuration 本次规划主动新增的停转时长
 * @param recoverAt 预计恢复时间；无法证明恢复时为null
 * @param preExistingPause 是否为快照前已发生的停转
 * @param withinModePolicy 是否符合当前模式停转政策
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcPauseAssessment(String obligationKey, Duration newPauseDuration,
                                LocalDateTime recoverAt, boolean preExistingPause,
                                boolean withinModePolicy) {

    /**
     * 构造一个未产生新增停转的评估。
     *
     * @param obligationKey 匿名义务键
     * @return 新增停转为零的评估
     */
    public static OcPauseAssessment none(String obligationKey) {
        return new OcPauseAssessment(obligationKey, Duration.ZERO, null, false, true);
    }
}
