package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单支OC阵容匹配结果。
 */
public record OcRosterMatchResult(boolean complete, List<OcPlannedAssignment> assignments,
                                  List<String> missingSlots, LocalDateTime completionAt) {

    public OcRosterMatchResult {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    public static OcRosterMatchResult success(List<OcPlannedAssignment> assignments,
                                              LocalDateTime completionAt) {
        return new OcRosterMatchResult(true, assignments, List.of(), completionAt);
    }

    public static OcRosterMatchResult failure(List<String> missingSlots) {
        return new OcRosterMatchResult(false, List.of(), missingSlots, null);
    }
}
