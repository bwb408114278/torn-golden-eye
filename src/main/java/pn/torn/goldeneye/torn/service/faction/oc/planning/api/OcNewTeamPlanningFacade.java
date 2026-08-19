package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot.OcPlanningSnapshotLoader;

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
     * 加载同一时间点的快照并生成刷新操作指令，并可标记本次规划前随机结果已确认变化。
     *
     * <p>{@code randomOutcomeRefreshed}的调用前置条件是已确认的游戏随机结果状态变化事件，
     * 不是完成本地OC数据同步；完成本地同步后调用方必须传{@code false}，
     * 否则会向用户输出“建议已失效且必须立即重跑”的错误事实。</p>
     *
     * @param factionId              帮派ID
     * @param mode                   刷新策略模式
     * @param randomOutcomeRefreshed 本次规划前是否已确认Torn随机结果发生变化
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(long factionId, OcPlanMode mode,
                                         boolean randomOutcomeRefreshed) {
        LocalDateTime snapshotTime = LocalDateTime.now(clock);
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, snapshotTime);
        return planner.plan(snapshot, mode, randomOutcomeRefreshed);
    }
}
