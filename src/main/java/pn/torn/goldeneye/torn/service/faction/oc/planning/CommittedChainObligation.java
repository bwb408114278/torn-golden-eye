package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 已经启动的高阶根队及其尚待履行的后继责任。
 *
 * @param rootOcId 已承诺根OC ID
 * @param chain 该根对应的完整高阶链节点
 * @param nextNodeIndex 下一待履约节点在链中的索引
 * @param successorAvailableAt 后继节点最早可开始时间
 */public record CommittedChainObligation(long rootOcId,
                                       List<OcTeamDemand> chain,
                                       int nextNodeIndex,
                                       LocalDateTime successorAvailableAt) {
    public CommittedChainObligation {
        chain = List.copyOf(chain);
        if (nextNodeIndex < 1 || nextNodeIndex > chain.size()) {
            throw new IllegalArgumentException("已承诺链后继节点索引无效");
        }
    }
}
