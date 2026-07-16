package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OC新队命令最终结果。
 *
 * @param factionId 帮派ID
 * @param snapshotTime 规划快照时间
 * @param requestedMode 用户请求的规划模式
 * @param recommendedBranch 推荐执行的规划分支
 * @param alternatives 其他模式的备选规划分支
 * @param catalogWarnings 规划目录配置警告
 */public record OcNewTeamPlan(long factionId, LocalDateTime snapshotTime,
                            OcPlanMode requestedMode,
                            OcPlanBranch recommendedBranch,
                            List<OcPlanBranch> alternatives,
                            List<String> catalogWarnings) {
    public OcNewTeamPlan {
        alternatives = List.copyOf(alternatives);
        catalogWarnings = List.copyOf(catalogWarnings);
    }
}
