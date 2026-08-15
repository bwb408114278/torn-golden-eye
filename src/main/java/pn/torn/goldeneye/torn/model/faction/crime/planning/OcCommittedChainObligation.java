package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条已在现实中启动的高阶链剩余义务。每个真实链根实例仅生成一条。
 *
 * @param rootOcId            真实链根OC实例ID
 * @param chainCode           链编码
 * @param currentNodeSequence 当前已运行到的节点序号（从1开始）
 * @param remainingNodes      当前节点之后的剩余后继节点需求，按链顺序排列
 * @param nextNodeStartAt     下一节点可开始时间（当前节点实际完成或生成时间）
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcCommittedChainObligation(
        long rootOcId,
        String chainCode,
        int currentNodeSequence,
        List<OcTeamDemand> remainingNodes,
        LocalDateTime nextNodeStartAt) {
    public OcCommittedChainObligation {
        remainingNodes = remainingNodes == null ? List.of() : List.copyOf(remainingNodes);
    }
}
