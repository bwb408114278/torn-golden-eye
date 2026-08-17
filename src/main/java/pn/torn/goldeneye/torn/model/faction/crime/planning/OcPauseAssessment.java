package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一条候选时间线上一支OC的停转评估。
 *
 * @param obligationKey    匿名义务键
 * @param pauseStartedAt   停转实际开始时间；无停转时为null
 * @param newPauseDuration 本次规划主动新增的停转时长
 * @param recoverAt        预计恢复时间；无法证明恢复时为null
 * @param preExistingPause 是否为快照前已发生的停转；必须由pauseStartedAt与快照时间比较推导
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcPauseAssessment(
        String obligationKey,
        LocalDateTime pauseStartedAt,
        Duration newPauseDuration,
        LocalDateTime recoverAt,
        boolean preExistingPause) {

    /**
     * 兼容旧测试/旧构造路径：不携带实际开始时间的停转评估。
     * 新生产构造路径必须提供pauseStartedAt。
     */
    public OcPauseAssessment(String obligationKey, Duration newPauseDuration,
                             LocalDateTime recoverAt, boolean preExistingPause) {
        this(obligationKey, null, newPauseDuration, recoverAt, preExistingPause);
    }

    /**
     * 判断当前评估是否属于快照前已发生的停转事实。
     *
     * @param snapshotTime 快照时间
     * @return 停转开始时间不晚于快照时间时返回true
     */
    public boolean isPreExisting(LocalDateTime snapshotTime) {
        return pauseStartedAt != null && !pauseStartedAt.isAfter(snapshotTime);
    }

    /**
     * 构造一个未产生新增停转的评估。
     *
     * @param obligationKey 匿名义务键
     * @return 新增停转为零的评估
     */
    public static OcPauseAssessment none(String obligationKey) {
        return new OcPauseAssessment(obligationKey, null, Duration.ZERO, null, true);
    }
}
