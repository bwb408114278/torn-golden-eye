package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * OC新队规划门面：同步刷新数据、建立一次不可变快照并委托纯规划引擎。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.15
 */
@Service
public class OcNewTeamPlanningFacade {
    private final OcPlanningSnapshotLoader snapshotLoader;
    private final OcNewTeamPlanningEngine planningEngine;
    private final Clock clock;

    public OcNewTeamPlanningFacade(OcPlanningSnapshotLoader snapshotLoader,
                                   OcRefreshStrategyPlanner refreshStrategyPlanner) {
        this.snapshotLoader = snapshotLoader;
        this.planningEngine = new OcNewTeamPlanningEngine(refreshStrategyPlanner);
        this.clock = Clock.systemDefaultZone();
    }

    /**
     * 加载同一时点快照并生成指定模式的OC新队规划。
     *
     * @param factionId     帮派ID
     * @param requestedMode 用户请求的规划模式
     * @return 包含推荐分支和备选分支的规划结果
     */
    public OcNewTeamPlan plan(long factionId, OcPlanMode requestedMode) {
        LocalDateTime now = LocalDateTime.now(clock);
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, now);
        return planningEngine.plan(snapshot, requestedMode);
    }
}
