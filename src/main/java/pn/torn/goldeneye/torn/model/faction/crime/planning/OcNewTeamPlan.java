package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OC新队命令最终结果。
 */
public record OcNewTeamPlan(long factionId, LocalDateTime snapshotTime,
                            OcPlanMode requestedMode,
                            OcPlanBranch recommendedBranch,
                            List<OcPlanBranch> alternatives,
                            List<String> catalogWarnings) {
    public OcNewTeamPlan {
        alternatives = List.copyOf(alternatives);
        catalogWarnings = List.copyOf(catalogWarnings);
    }
}
