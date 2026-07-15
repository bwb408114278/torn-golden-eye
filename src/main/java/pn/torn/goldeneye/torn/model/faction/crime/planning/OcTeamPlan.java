package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一支OC的可执行规划。
 */
public record OcTeamPlan(long ocId, String ocName, int rank, boolean existingTeam,
                         boolean complete, LocalDateTime completionAt,
                         List<OcPlannedAssignment> assignments,
                         List<String> missingSlots, long unlockScore,
                         long rewardFloor, String note) {
    public OcTeamPlan {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
