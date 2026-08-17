package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 时间线真实价值摘要累积器。只从已完成模拟的状态事实计算实际人天、实际停转、
 * 保证释放和可避免过期，不在该层读取数据库。金额与业务先验由搜索层用静态
 * {@link OcValueEvidence}合并，本层不猜测模板奖励。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public final class OcTimelineValueAccumulator {

    /**
     * 从时间线状态累积真实价值事实。
     *
     * @param state               已完成模拟的时间线状态
     * @param plannedEmptyExpired 模拟中是否出现过被跳过的计划内无人OC
     * @return 真实时间线价值摘要（金额字段由搜索层补充）
     */
    public OcTimelineValueSummary accumulate(OcTimelineState state,
                                             boolean plannedEmptyExpired) {
        return new OcTimelineValueSummary(null,
                actualIncrementalMemberDays(state),
                maxNewPause(state),
                Duration.ZERO,
                !plannedEmptyExpired,
                earliestCompletionRelease(state.events()),
                0, 0, 1, OcValueEvidence.Level.INSUFFICIENT);
    }

    /**
     * 计算实际增量剩余成员人天：仅统计计划内无人OC、已启动链后继和条件性随机结果
     * 的成员占用区间，避免把快照既有固定占用重复计入增量。
     *
     * @param state 时间线状态
     * @return 按24小时折算的实际增量成员人天
     */
    private int actualIncrementalMemberDays(OcTimelineState state) {
        long totalMinutes = 0;
        for (OcMemberInterval interval : state.intervals()) {
            if (interval.source() == OcMemberInterval.IntervalSource.EXISTING_OC) {
                continue;
            }
            totalMinutes += Duration.between(interval.occupiedFrom(),
                    interval.occupiedUntil()).toMinutes();
        }
        return (int) ((totalMinutes + 1439) / 1440);
    }

    /**
     * 获取时间线实际单次最大主动新增停转时长。
     *
     * @param state 时间线状态
     * @return 最大新增停转时长；无主动新增停转时为零
     */
    private Duration maxNewPause(OcTimelineState state) {
        return state.pauses().stream()
                .filter(pause -> !pause.preExistingPause())
                .map(OcPauseAssessment::newPauseDuration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    /**
     * 从完成释放事件中获取最早完整释放时间。
     *
     * @param events 时间线事件
     * @return 最早完成释放时间；无完成事件时为null
     */
    private LocalDateTime earliestCompletionRelease(List<OcTimelineEvent> events) {
        return events.stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.COMPLETION_RELEASE)
                .map(OcTimelineEvent::eventTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }
}
