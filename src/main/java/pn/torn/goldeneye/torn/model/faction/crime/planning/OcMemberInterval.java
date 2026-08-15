package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;

/**
 * 一名成员在一条时间线上的真实或候选占用区间。
 *
 * @param userId        成员用户ID
 * @param occupiedFrom  占用开始时间（实际或计划加入时间）
 * @param occupiedUntil 占用结束时间（所属OC最终完成并释放时间）
 * @param source        占用来源
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcMemberInterval(
        long userId,
        LocalDateTime occupiedFrom,
        LocalDateTime occupiedUntil,
        IntervalSource source) {

    /**
     * 成员占用区间来源。
     */
    public enum IntervalSource {
        /**
         * 快照中已有人OC的真实占用。
         */
        EXISTING_OC,
        /**
         * 已启动链后继的义务占用。
         */
        COMMITTED_CHAIN,
        /**
         * 计划内无人OC启动后的占用。
         */
        PLANNED_EMPTY,
        /**
         * 条件性随机结果候选的占用。
         */
        RANDOM_CANDIDATE
    }

    /**
     * 判断当前区间与另一区间是否重叠。
     *
     * @param other 另一占用区间
     * @return 区间重叠时返回true；首尾相接不算重叠
     */
    public boolean overlaps(OcMemberInterval other) {
        return occupiedFrom.isBefore(other.occupiedUntil())
                && other.occupiedFrom().isBefore(occupiedUntil);
    }
}
