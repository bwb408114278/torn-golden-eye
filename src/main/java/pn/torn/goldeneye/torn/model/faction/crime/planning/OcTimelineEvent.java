package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;

/**
 * 时间线上的一个匿名规划事件。
 *
 * @param eventTime     事件发生时间
 * @param type          事件类型
 * @param obligationKey 关联OC实例或匿名义务键
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcTimelineEvent(
        LocalDateTime eventTime,
        EventType type,
        String obligationKey) {

    /**
     * 时间线事件类型。
     */
    public enum EventType {
        /**
         * OC正常完成并释放成员。
         */
        COMPLETION_RELEASE,
        /**
         * 准备阶段边界。
         */
        STAGE_BOUNDARY,
        /**
         * 队伍开始停转。
         */
        PAUSE_STARTED,
        /**
         * 停转后被恢复。
         */
        PAUSE_RECOVERED,
        /**
         * 无人OC首人加入期限。
         */
        FIRST_PERSON_DEADLINE,
        /**
         * 高阶链后继生成。
         */
        CHAIN_SUCCESSOR_GENERATED,
        /**
         * 当前快照时间。
         */
        SNAPSHOT
    }
}
