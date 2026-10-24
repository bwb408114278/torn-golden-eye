package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

/**
 * OC流程图边。
 *
 * <p>目标已折叠到下一个判定或结局节点，因此这里不存在只用于叙事的中间节点。</p>
 *
 * @param nodeCode       起点节点编码
 * @param branch         分支
 * @param targetNodeCode 去向节点编码
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public record OcFlowEdge(
        String nodeCode,
        OcFlowBranchEnum branch,
        String targetNodeCode) {
}
