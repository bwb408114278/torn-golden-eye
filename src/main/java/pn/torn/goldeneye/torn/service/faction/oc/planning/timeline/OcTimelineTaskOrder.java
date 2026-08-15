package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineObligation;

import java.util.Comparator;
import java.util.List;

/**
 * 时间线任务排序协作类。负责任务载体、稳定排序和已投入义务优先级，
 * 由时间线事件推进器显式构造。纯内存对象，不访问数据库、HTTP或Redis。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
final class OcTimelineTaskOrder {
    /**
     * 单个待排程任务及其剩余链后继模板。
     *
     * @param obligation 时间线义务
     * @param successors 当前节点之后的剩余链节点模板
     */
    record Task(
            OcTimelineObligation obligation,
            List<OcTeamDemand> successors) {
    }

    /**
     * 创建时间线任务排序器。
     */
    OcTimelineTaskOrder() {
    }

    /**
     * 任务稳定排序：层级、首人期限、阶段时间、等级、名称和键。
     *
     * @return 任务比较器
     */
    Comparator<Task> taskComparator() {
        return Comparator.comparingInt((Task task) -> tier(task.obligation()))
                .thenComparing(task -> task.obligation().firstJoinDeadline(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(task -> task.obligation().demand().readyAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(task -> -task.obligation().demand().rank())
                .thenComparing(task -> task.obligation().demand().ocName())
                .thenComparing(task -> task.obligation().key());
    }

    /**
     * 获取义务的处理层级：已投入义务最先，计划内无人OC次之，随机结果最后。
     *
     * @param obligation 时间线义务
     * @return 层级编号，越小越优先
     */
    int tier(OcTimelineObligation obligation) {
        if (obligation.isHardObligation()) {
            return 0;
        }
        return obligation.kind() == OcTimelineObligation.ObligationKind.PLANNED_EMPTY ? 1 : 2;
    }
}
