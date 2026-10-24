package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OC流程图装配校验测试：配置写错的图必须在装配期被拒绝，而不是留到求值期算出错误概率。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@DisplayName("OC流程图装配校验测试")
class OcFlowGraphTest {
    private static final OcFlowNode START = check("S1", "Muscle#1");
    private static final OcFlowNode GOOD = end("G1", OcFlowNodeTypeEnum.GOOD_END);
    private static final OcFlowNode BAD = end("B1", OcFlowNodeTypeEnum.BAD_END);

    @Test
    @DisplayName("合法图装配后起点唯一且拓扑序覆盖全部节点")
    void of_shouldBuildTopologicalOrderForValidGraph() {
        OcFlowGraph graph = OcFlowGraph.of("Test", 8, List.of(START, GOOD, BAD), List.of(
                new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1")));

        assertThat(graph.startNodeCode()).isEqualTo("S1");
        assertThat(graph.topologicalOrder()).containsExactlyInAnyOrder("S1", "G1", "B1");
        assertThat(graph.topologicalOrder().getFirst()).isEqualTo("S1");
        assertThat(graph.checkCount()).isEqualTo(1);
        assertThat(graph.targetOf("S1", OcFlowBranchEnum.PASS)).isEqualTo("G1");
        assertThat(graph.targetOf("G1", OcFlowBranchEnum.PASS)).isNull();
    }

    @Test
    @DisplayName("判定缺少FAIL分支时拒绝装配")
    void of_shouldRejectMissingBranch() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8, List.of(START, GOOD, BAD),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少PASS或FAIL分支");
    }

    @Test
    @DisplayName("结局节点带出边时拒绝装配")
    void of_shouldRejectEdgeFromEndNode() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8, List.of(START, GOOD, BAD), List.of(
                new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1"),
                new OcFlowEdge("G1", OcFlowBranchEnum.PASS, "B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结局节点不应有出边");
    }

    @Test
    @DisplayName("边终点不存在时拒绝装配")
    void of_shouldRejectDanglingEdge() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8, List.of(START, GOOD, BAD), List.of(
                new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "NOT_EXIST"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("边终点不存在");
    }

    @Test
    @DisplayName("从起点可达的环在装配期被拒绝")
    void of_shouldRejectCycle() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8,
                List.of(START, check("S2", "Muscle#2"), check("S3", "Muscle#3"), GOOD, BAD), List.of(
                        new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "S2"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.PASS, "S3"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.FAIL, "S3"),
                        new OcFlowEdge("S3", OcFlowBranchEnum.PASS, "S2"),
                        new OcFlowEdge("S3", OcFlowBranchEnum.FAIL, "G1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("存在环");
    }

    @Test
    @DisplayName("存在起点不可达节点时拒绝装配")
    void of_shouldRejectUnreachableNode() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8,
                List.of(START, check("S2", "Muscle#2"), check("S3", "Muscle#3"), GOOD, BAD), List.of(
                        new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.PASS, "S3"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.FAIL, "S3"),
                        new OcFlowEdge("S3", OcFlowBranchEnum.PASS, "S2"),
                        new OcFlowEdge("S3", OcFlowBranchEnum.FAIL, "G1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可达");
    }

    @Test
    @DisplayName("起点数量不为1时拒绝装配")
    void of_shouldRejectMultipleStartNodes() {
        assertThatThrownBy(() -> OcFlowGraph.of("Test", 8,
                List.of(START, check("S2", "Muscle#2"), GOOD, BAD), List.of(
                        new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.FAIL, "B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("起点数量应为1");
    }

    private static OcFlowNode check(String nodeCode, String participant) {
        return new OcFlowNode(nodeCode, OcFlowNodeTypeEnum.CHECK, List.of(participant));
    }

    private static OcFlowNode end(String nodeCode, OcFlowNodeTypeEnum nodeType) {
        return new OcFlowNode(nodeCode, nodeType, null);
    }
}
