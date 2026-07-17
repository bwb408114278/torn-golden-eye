package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单支OC阵容匹配结果。
 *
 * @param complete 是否成功匹配全部空缺岗位
 * @param assignments 已匹配的岗位分配
 * @param missingSlots 未匹配的岗位编码
 * @param completionAt 全部岗位准备完成时间；匹配不完整时为null
 */public record OcRosterMatchResult(boolean complete, List<OcPlannedAssignment> assignments,
                                  List<String> missingSlots, LocalDateTime completionAt) {

    public OcRosterMatchResult {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    /**
     * 创建完整匹配成功的结果。
     *
     * @param assignments 岗位分配明细
     * @param completionAt 全部岗位准备完成时间
     * @return 完整匹配结果
     */
    public static OcRosterMatchResult success(List<OcPlannedAssignment> assignments,
                                              LocalDateTime completionAt) {
        return new OcRosterMatchResult(true, assignments, List.of(), completionAt);
    }

    /**
     * 创建岗位匹配失败的结果。
     *
     * @param missingSlots 未匹配的岗位编码
     * @return 不包含岗位分配的失败结果
     */
    public static OcRosterMatchResult failure(List<String> missingSlots) {
        return new OcRosterMatchResult(false, List.of(), missingSlots, null);
    }
}
