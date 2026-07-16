package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一支OC的可执行规划。
 *
 * @param ocId OC ID；条件性新队不存在ID时为null
 * @param ocName OC名称
 * @param rank OC等级
 * @param existingTeam 是否为已有队伍
 * @param complete 是否已生成完整可执行阵容
 * @param completionAt 全队准备完成时间
 * @param assignments 本次新增岗位分配
 * @param missingSlots 仍未补齐的岗位编码
 * @param unlockScore 完成该队可释放的阻塞评分
 * @param rewardFloor 自动规划使用的最低收益门槛
 * @param note 人工处理说明或规划备注
 */public record OcTeamPlan(long ocId, String ocName, int rank, boolean existingTeam,
                         boolean complete, LocalDateTime completionAt,
                         List<OcPlannedAssignment> assignments,
                         List<String> missingSlots, long unlockScore,
                         long rewardFloor, String note) {
    public OcTeamPlan {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
