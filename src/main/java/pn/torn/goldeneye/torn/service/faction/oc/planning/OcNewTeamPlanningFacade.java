package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * OC新队规划门面：同步刷新数据、建立一次不可变快照并委托纯规划引擎。
 */
@Service
public class OcNewTeamPlanningFacade {
    private final OcPlanningSnapshotLoader snapshotLoader;
    private final OcNewTeamPlanningEngine planningEngine;
    private final Clock clock;

    public OcNewTeamPlanningFacade(OcPlanningSnapshotLoader snapshotLoader,
                                   OcRefreshStrategyPlanner refreshStrategyPlanner) {
        this(snapshotLoader, new OcNewTeamPlanningEngine(refreshStrategyPlanner),
                Clock.systemDefaultZone());
    }

    OcNewTeamPlanningFacade(OcPlanningSnapshotLoader snapshotLoader,
                            OcNewTeamPlanningEngine planningEngine, Clock clock) {
        this.snapshotLoader = snapshotLoader;
        this.planningEngine = planningEngine;
        this.clock = clock;
    }

    public OcNewTeamPlan plan(long factionId, OcPlanMode requestedMode) {
        LocalDateTime now = LocalDateTime.now(clock);
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, now);
        return planningEngine.plan(snapshot, requestedMode);
    }
}
