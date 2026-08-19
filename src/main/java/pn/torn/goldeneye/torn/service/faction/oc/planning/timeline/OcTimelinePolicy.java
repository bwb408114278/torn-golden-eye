package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC时间线规划阶段一冻结的全局业务参数。只承载已冻结规则，不预埋未使用配置。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public final class OcTimelinePolicy {
    /**
     * 重新评估操作提前量：最晚重新评估时间按业务边界提前30分钟。
     */
    public static final Duration REPLAN_LEAD = Duration.ofMinutes(30);

    /**
     * 均衡模式单次主动新增停转上限。
     */
    public static final Duration BALANCED_MAX_NEW_PAUSE = Duration.ofHours(6);

    /**
     * 收益模式单次主动新增停转上限。
     */
    public static final Duration PROFIT_MAX_NEW_PAUSE = Duration.ofHours(12);

    /**
     * 无人OC首位成员最晚加入期限：创建时间后7天。
     */
    public static final int FIRST_JOIN_EXPIRE_DAYS = 7;

    private OcTimelinePolicy() {
    }

    /**
     * 获取指定模式允许的单次主动新增停转上限。
     *
     * @param mode 规划模式
     * @return 保守模式为零；均衡6小时；收益12小时
     */
    public static Duration maxNewPause(OcPlanMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> Duration.ZERO;
            case BALANCED -> BALANCED_MAX_NEW_PAUSE;
            case PROFIT -> PROFIT_MAX_NEW_PAUSE;
        };
    }

    /**
     * 判断指定模式是否允许出现任何主动新增停转。
     *
     * @param mode 规划模式
     * @return 非保守模式返回true
     */
    public static boolean allowsNewPause(OcPlanMode mode) {
        return !OcPlanMode.CONSERVATIVE.equals(mode);
    }

    /**
     * 计算有限证明窗口结束时间：最早业务边界减操作提前量；
     * 无任何边界时回退为快照时间后1天。
     *
     * @param request 求解请求
     * @return 证明窗口结束时间；不早于快照时间
     */
    public static LocalDateTime proofWindowEnd(OcRefreshSafetyRequest request) {
        return proofWindow(request).proofWindowEnd();
    }

    /**
     * 计算有限证明窗口。若原始最晚重评估时间早于快照时间，窗口收敛为
     * [snapshotTime, snapshotTime]并阻断新增刷新；内部证明窗口不得早于快照。
     *
     * @param request 求解请求
     * @return 有限证明窗口状态
     */
    public static OcProofWindow proofWindow(OcRefreshSafetyRequest request) {
        List<LocalDateTime> boundaries = new ArrayList<>();
        request.obligations().forEach(obligation -> {
            if (obligation.firstJoinDeadline() != null) {
                boundaries.add(obligation.firstJoinDeadline());
            }
            LocalDateTime readyAt = obligation.demand().readyAt();
            if (readyAt != null && readyAt.isAfter(request.planningTime())) {
                boundaries.add(readyAt);
            }
        });
        LocalDateTime snapshotTime = request.planningTime();
        LocalDateTime fallback = snapshotTime.plusDays(1);
        LocalDateTime rawEnd = boundaries.stream().min(LocalDateTime::compareTo)
                .map(boundary -> boundary.minus(REPLAN_LEAD))
                .orElse(fallback);
        if (rawEnd.isBefore(snapshotTime)) {
            return OcProofWindow.expired(snapshotTime);
        }
        return OcProofWindow.valid(rawEnd);
    }
}
