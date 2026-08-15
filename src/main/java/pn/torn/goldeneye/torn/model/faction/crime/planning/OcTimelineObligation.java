package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 时间线上的一条规划义务。四类义务共用同一载体，通过kind区分硬约束强度。
 *
 * @param key                    匿名义务键，真实实例使用OC ID，模板使用规划键加序号
 * @param kind                   义务类别
 * @param demand                 对应的岗位需求模板
 * @param firstJoinDeadline      首人最晚加入期限；已有成员的义务为null
 * @param predecessorCompletedAt 链后继义务的前置实际完成或生成时间；非链义务为null
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcTimelineObligation(
        String key,
        ObligationKind kind,
        OcTeamDemand demand,
        LocalDateTime firstJoinDeadline,
        LocalDateTime predecessorCompletedAt) {

    /**
     * 时间线义务类别。
     */
    public enum ObligationKind {
        /**
         * 快照中已有人OC，成员和岗位固定，属于已投入硬义务。
         */
        EXISTING_JOINED,
        /**
         * 已启动高阶链的真实后继义务，属于已投入硬义务。
         */
        COMMITTED_CHAIN_SUCCESSOR,
        /**
         * 计划内无人OC，属于未来待启动义务，占未来容量但不锁当前成员。
         */
        PLANNED_EMPTY,
        /**
         * 刷新向量枚举出的条件性随机结果义务。
         */
        CONDITIONAL_RANDOM
    }

    /**
     * 判断义务是否属于已投入硬义务。
     *
     * @return 已有人OC或已启动链后继时返回true
     */
    public boolean isHardObligation() {
        return kind == ObligationKind.EXISTING_JOINED
                || kind == ObligationKind.COMMITTED_CHAIN_SUCCESSOR;
    }

    /**
     * 获取义务完整岗位需求。
     *
     * @return 岗位需求
     */
    public List<OcPlanSlot> slots() {
        return demand.slots();
    }

    /**
     * 获取义务已固定成员集合。
     *
     * @return 已加入成员用户ID集合
     */
    public Set<Long> fixedMemberIds() {
        return demand.fixedMemberIds();
    }
}
