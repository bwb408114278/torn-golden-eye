package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * OC刷新指令门面，保证每次命令只加载一个不可变快照。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Service
public class OcNewTeamPlanningFacade {
    private final OcPlanningSnapshotLoader snapshotLoader;
    private final OcRefreshInstructionPlanner planner;
    private final Clock clock = Clock.systemDefaultZone();

    public OcNewTeamPlanningFacade(OcPlanningSnapshotLoader snapshotLoader,
                                   OcRefreshInstructionPlanner planner) {
        this.snapshotLoader = snapshotLoader;
        this.planner = planner;
    }

    /**
     * 加载同一时间点的快照并生成刷新操作指令。
     *
     * @param factionId 帮派ID
     * @param mode      刷新策略模式
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(long factionId, OcPlanMode mode) {
        return plan(factionId, mode, false);
    }

    /**
     * 加载同一时间点的快照并生成刷新操作指令，并可标记本次规划紧随Torn随机结果刷新之后。
     *
     * @param factionId              帮派ID
     * @param mode                   刷新策略模式
     * @param randomOutcomeRefreshed 本次规划前是否刚从Torn刷新随机结果
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(long factionId, OcPlanMode mode,
                                         boolean randomOutcomeRefreshed) {
        LocalDateTime snapshotTime = LocalDateTime.now(clock);
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, snapshotTime);
        return planner.plan(snapshot, mode, randomOutcomeRefreshed);
    }
}
