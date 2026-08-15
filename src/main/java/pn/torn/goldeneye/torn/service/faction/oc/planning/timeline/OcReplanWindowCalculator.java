package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcReplanWindow;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重新评估窗口计算器。按业务边界提前30分钟计算最晚重新评估时间。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcReplanWindowCalculator {

    /**
     * 计算当前建议的重新评估窗口。
     *
     * @param snapshotTime       快照时间
     * @param adviceChangeEvents 会实质改变建议的确定事件时间，例如成员释放、链后继生成
     * @param businessBoundaries 业务边界时间，例如硬期限、首人期限、停转恢复上限
     * @return 重新评估窗口；边界减提前量不晚于快照时输出立即重评估
     */
    public OcReplanWindow calculate(LocalDateTime snapshotTime,
                                    List<LocalDateTime> adviceChangeEvents,
                                    List<LocalDateTime> businessBoundaries) {
        Set<OcPlanReasonCodeEnum> reasonCodes = new HashSet<>();
        LocalDateTime latestReplanAt = businessBoundaries.stream()
                .filter(boundary -> boundary != null && boundary.isAfter(snapshotTime))
                .min(LocalDateTime::compareTo)
                .map(boundary -> boundary.minus(OcTimelinePolicy.REPLAN_LEAD))
                .orElse(null);
        LocalDateTime nextReplanAt = adviceChangeEvents.stream()
                .filter(event -> event != null && event.isAfter(snapshotTime))
                .min(LocalDateTime::compareTo)
                .orElse(null);
        if (latestReplanAt != null && !latestReplanAt.isAfter(snapshotTime)) {
            reasonCodes.add(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW);
            return new OcReplanWindow(snapshotTime, snapshotTime, reasonCodes);
        }
        if (nextReplanAt == null) {
            nextReplanAt = latestReplanAt == null ? snapshotTime : latestReplanAt;
        } else if (latestReplanAt != null && nextReplanAt.isAfter(latestReplanAt)) {
            nextReplanAt = latestReplanAt;
            reasonCodes.add(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW);
        }
        return new OcReplanWindow(nextReplanAt,
                latestReplanAt == null ? snapshotTime : latestReplanAt, reasonCodes);
    }

    /**
     * 构造随机结果已确认变化时的立即重评估窗口。
     *
     * <p>调用前置条件是已确认的游戏随机结果状态变化事件，不是完成本地OC数据同步；
     * 本地同步没有数据变化或响应为空时同样不得调用。已确认随机结果改变时旧建议立即失效，
     * 下次和最晚重评估时间均为快照时间。</p>
     *
     * @param snapshotTime 快照时间
     * @return 下次和最晚重评估时间均为快照时间的窗口
     */
    public OcReplanWindow immediateReplan(LocalDateTime snapshotTime) {
        return new OcReplanWindow(snapshotTime, snapshotTime,
                Set.of(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED));
    }
}
