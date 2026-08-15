package pn.torn.goldeneye.torn.service.faction.oc.planning.search;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcConfigurationStatusEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePlanningEngine;

import java.time.Duration;
import java.util.Map;

/**
 * 在时间预算内证明普通池与高阶池联合安全刷新候选的时间线求解门面。
 *
 * <p>内部委托{@link OcTimelinePlanningEngine}的有限事件时间线搜索；
 * 不再使用批次永久预留成员或把首人立即加入作为全模式唯一准入。
 * 纯内存对象，不访问数据库、HTTP或Redis。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public class OcRefreshSafetySolver {
    private final OcTimelinePlanningEngine engine;

    /**
     * 创建联合刷新安全候选求解器。
     *
     * @param timeout   单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     */
    public OcRefreshSafetySolver(Duration timeout, int maxSearch) {
        OcTimelineEventScheduler scheduler = new OcTimelineEventScheduler();
        this.engine = new OcTimelinePlanningEngine(timeout, maxSearch, scheduler,
                new OcRefreshVectorSearcher(maxSearch, scheduler));
    }

    /**
     * 求解普通池与高阶池的联合安全候选集合。
     *
     * @param request            当前时间线事实义务和随机池模板
     * @param evidenceByTemplate 按模板键索引的价值证据；高阶链使用chain前缀键
     * @return 含安全评估与已评分候选向量的求解结果
     */
    public OcRefreshSafetyResult solve(OcRefreshSafetyRequest request,
                                       Map<String, OcValueEvidence> evidenceByTemplate) {
        return engine.solve(request, evidenceByTemplate, OcConfigurationStatusEnum.VALID);
    }
}
